package com.kyo89.firerun;

import android.app.Activity;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInstaller;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.provider.Settings;
import android.widget.Toast;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.concurrent.ExecutorService;

/**
 * Aggiornamento dall'ultima release GitHub del progetto:
 *   .../releases/latest/download/version.json  → {"versionCode": N}
 *   .../releases/latest/download/FireRun.apk
 */
final class Updater {
    private Updater() {}

    interface Result {
        void done(int latestVersionCode); // -1 se il controllo non è riuscito
    }

    private static final int FLAG_MUTABLE_COMPAT = 0x02000000; // PendingIntent.FLAG_MUTABLE (API 31)

    static String base() {
        return "https://github.com/" + BuildConfig.GITHUB_REPO + "/releases/latest/download/";
    }

    static void fetchLatest(ExecutorService io, final Handler ui, final Result r) {
        try {
            io.execute(() -> {
                int v = -1;
                try {
                    String json = new String(readAll(base() + "version.json"), "UTF-8");
                    v = new JSONObject(json).optInt("versionCode", -1);
                } catch (Exception ignored) {
                }
                final int fv = v;
                ui.post(() -> r.done(fv));
            });
        } catch (RuntimeException ignored) {
        }
    }

    static void downloadAndInstall(final Activity a, ExecutorService io, final Handler ui) {
        if (Build.VERSION.SDK_INT >= 26 && !a.getPackageManager().canRequestPackageInstalls()) {
            Toast.makeText(a, "Consenti a FireRun di installare app, poi riprova", Toast.LENGTH_LONG).show();
            try {
                Intent i = new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                        Uri.parse("package:" + a.getPackageName()));
                // aperta sopra FireRun: Indietro torna a FireRun
                new Prefs(a).pauseRedirectForSystemScreen(); // la schermata permessi può stare nel pacchetto Amazon
                a.startActivity(i);
            } catch (RuntimeException e) {
                GlassMenu.message(a, "Permesso necessario",
                        "Vai in Impostazioni → La mia Fire TV → Opzioni sviluppatore → "
                                + "Installa app sconosciute e attiva FireRun. Poi riprova l'aggiornamento.");
            }
            return;
        }
        Toast.makeText(a, "Scarico l'aggiornamento…", Toast.LENGTH_SHORT).show();
        final Context app = a.getApplicationContext();
        try {
            io.execute(() -> {
                try {
                    File apk = new File(app.getCacheDir(), "update.apk");
                    download(base() + "FireRun.apk", apk);
                    install(app, apk);
                } catch (final Exception e) {
                    ui.post(() -> Toast.makeText(app, "Aggiornamento non riuscito: " + e.getMessage(),
                            Toast.LENGTH_LONG).show());
                }
            });
        } catch (RuntimeException ignored) {
        }
    }

    private static void install(Context c, File apk) throws IOException {
        PackageInstaller installer = c.getPackageManager().getPackageInstaller();
        PackageInstaller.SessionParams params =
                new PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL);
        params.setAppPackageName(c.getPackageName());
        int id = installer.createSession(params);
        try (PackageInstaller.Session session = installer.openSession(id)) {
            try (OutputStream out = session.openWrite("base.apk", 0, apk.length());
                 InputStream in = new FileInputStream(apk)) {
                copy(in, out);
                session.fsync(out);
            }
            Intent done = new Intent(c, InstallReceiver.class);
            PendingIntent pi = PendingIntent.getBroadcast(c, id, done,
                    PendingIntent.FLAG_UPDATE_CURRENT | FLAG_MUTABLE_COMPAT);
            session.commit(pi.getIntentSender());
        }
    }

    // --- rete ---

    private static HttpURLConnection open(String url) throws IOException {
        for (int hop = 0; hop < 6; hop++) {
            HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
            c.setInstanceFollowRedirects(false);
            c.setConnectTimeout(15_000);
            c.setReadTimeout(60_000);
            c.setRequestProperty("User-Agent", "FireRun-Updater");
            int code = c.getResponseCode();
            if (code >= 300 && code < 400) {
                String loc = c.getHeaderField("Location");
                c.disconnect();
                if (loc == null) throw new IOException("redirect senza destinazione");
                url = new URL(new URL(url), loc).toString();
                continue;
            }
            if (code != 200) {
                c.disconnect();
                throw new IOException("HTTP " + code);
            }
            return c;
        }
        throw new IOException("troppi redirect");
    }

    private static byte[] readAll(String url) throws IOException {
        HttpURLConnection c = open(url);
        try (InputStream in = c.getInputStream()) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            copy(in, out);
            return out.toByteArray();
        } finally {
            c.disconnect();
        }
    }

    private static void download(String url, File dest) throws IOException {
        HttpURLConnection c = open(url);
        try (InputStream in = c.getInputStream(); OutputStream out = new FileOutputStream(dest)) {
            copy(in, out);
        } finally {
            c.disconnect();
        }
    }

    private static void copy(InputStream in, OutputStream out) throws IOException {
        byte[] buf = new byte[64 * 1024];
        int n;
        while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
    }
}
