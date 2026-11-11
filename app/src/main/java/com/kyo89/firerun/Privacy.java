package com.kyo89.firerun;

import android.content.ContentResolver;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.provider.Settings;

/**
 * Interruttore "Privacy": niente tracciamento pubblicitario, niente invio dei dati di utilizzo,
 * niente video automatici in primo piano e niente notifiche a comparsa.
 * Valori ripresi da dumbtv (https://github.com/humblemuzzu/dumbtv, MIT).
 *
 * Prima di cambiare qualcosa salva i valori originali; spegnendo l'interruttore li rimette.
 * Serve WRITE_SECURE_SETTINGS, concesso una volta dal PC (deploy.ps1 -AttivaTastoHome o -Pulizia).
 * Non tocca nulla che riguardi gli aggiornamenti di Fire OS.
 */
final class Privacy {
    private Privacy() {}

    private static final boolean SECURE = true, GLOBAL = false;

    private static final Object[][] TARGETS = {
            {SECURE, "amz_limit_ad_tracking", "1"},
            {SECURE, "limit_ad_tracking", "1"},
            {SECURE, "non_amz_limit_ad_tracking", "1"},
            {SECURE, "advertisingIdApp:interestBasedAds:value", "0"},
            {SECURE, "USAGE_METRICS_UPLOAD_ENABLED", "0"},
            {SECURE, "usage_metrics_marketing_enabled", "0"},
            {SECURE, "settingsApp:appUsageData:value", "0"},
            {SECURE, "settingsApp:deviceUsageData:value", "0"},
            {GLOBAL, "heads_up_notifications_enabled", "0"},
            {GLOBAL, "com.amazon.tv.settings.featured_item_video_enabled", "0"},
            {GLOBAL, "com.amazon.tv.settings.featured_item_video_volume", "0.0"},
    };

    private static final String FILE = "privacy";
    private static final String NULL = "\u0000null";

    static boolean canWrite(Context c) {
        return c.checkSelfPermission("android.permission.WRITE_SECURE_SETTINGS")
                == PackageManager.PERMISSION_GRANTED;
    }

    /** Attiva se tutte le impostazioni hanno già il valore "privato". */
    static boolean isOn(Context c) {
        ContentResolver cr = c.getContentResolver();
        for (Object[] t : TARGETS) {
            String v = get(cr, (Boolean) t[0], (String) t[1]);
            if (!same((String) t[2], v)) return false;
        }
        return true;
    }

    /** @return false se manca il permesso o una scrittura non è riuscita. */
    static boolean set(Context c, boolean on) {
        if (!canWrite(c)) return false;
        ContentResolver cr = c.getContentResolver();
        SharedPreferences sp = c.getApplicationContext().getSharedPreferences(FILE, Context.MODE_PRIVATE);
        boolean ok = true;
        try {
            if (on) {
                // Fotografia solo se non ce n'è già una: così resta quella originale.
                if (!sp.getBoolean("saved", false)) {
                    SharedPreferences.Editor e = sp.edit();
                    for (Object[] t : TARGETS) {
                        String v = get(cr, (Boolean) t[0], (String) t[1]);
                        e.putString(key(t), v == null ? NULL : v);
                    }
                    e.putBoolean("saved", true).commit();
                }
                for (Object[] t : TARGETS) ok &= put(cr, (Boolean) t[0], (String) t[1], (String) t[2]);
            } else {
                for (Object[] t : TARGETS) {
                    String old = sp.getString(key(t), null);
                    if (old == null) {
                        // nessuna fotografia: torna al comportamento predefinito di Fire OS
                        old = defaultOf((String) t[1]);
                    }
                    ok &= put(cr, (Boolean) t[0], (String) t[1], NULL.equals(old) ? null : old);
                }
                sp.edit().clear().commit();
            }
        } catch (SecurityException e) {
            return false;
        }
        return ok;
    }

    private static String defaultOf(String name) {
        if (name.contains("limit_ad_tracking")) return "0";
        if (name.endsWith("featured_item_video_volume")) return "1.0";
        return "1";
    }

    private static String key(Object[] t) {
        return ((Boolean) t[0] ? "s:" : "g:") + t[1];
    }

    private static boolean same(String want, String have) {
        if (have == null) return false;
        if (want.equals(have.trim())) return true;
        try {
            return Float.parseFloat(want) == Float.parseFloat(have.trim());
        } catch (NumberFormatException e) {
            return false;
        }
    }

    private static String get(ContentResolver cr, boolean secure, String name) {
        try {
            return secure ? Settings.Secure.getString(cr, name) : Settings.Global.getString(cr, name);
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** value null = valore assente prima: lo si rimette "vuoto" togliendo il nostro. */
    private static boolean put(ContentResolver cr, boolean secure, String name, String value) {
        try {
            return secure ? Settings.Secure.putString(cr, name, value)
                    : Settings.Global.putString(cr, name, value);
        } catch (SecurityException e) {
            throw e;
        } catch (RuntimeException e) {
            return false;
        }
    }
}
