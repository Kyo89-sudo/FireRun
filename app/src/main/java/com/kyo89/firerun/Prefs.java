package com.kyo89.firerun;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Impostazioni persistenti di FireRun. */
final class Prefs {
    private static final String FILE = "firerun";
    private static final String SEP = "\n";
    private static final String LAUNCH = "t:";

    private final SharedPreferences sp;

    Prefs(Context c) {
        sp = c.getApplicationContext().getSharedPreferences(FILE, Context.MODE_PRIVATE);
        // Voci delle versioni precedenti che non servono più
        if (sp.contains("rec") || sp.contains("windows")) sp.edit().remove("rec").remove("windows").apply();
    }

    // --- barra delle app (dock) ---

    boolean hasDock() {
        return sp.contains("dock");
    }

    List<String> dock() {
        return split(sp.getString("dock", ""));
    }

    void setDock(List<String> keys) {
        sp.edit().putString("dock", join(keys)).apply();
    }

    // --- ultima apertura (mostrata nel Top Shelf) ---

    void markLaunched(String key) {
        sp.edit().putLong(LAUNCH + key, System.currentTimeMillis()).apply();
    }

    long lastLaunch(String key) {
        return sp.getLong(LAUNCH + key, 0L);
    }

    /** Toglie le date delle app non più installate (prima restavano per sempre). */
    void pruneLaunches(Collection<String> installedKeys) {
        SharedPreferences.Editor e = null;
        for (String k : sp.getAll().keySet()) {
            if (k.startsWith(LAUNCH) && !installedKeys.contains(k.substring(LAUNCH.length()))) {
                if (e == null) e = sp.edit();
                e.remove(k);
            }
        }
        if (e != null) e.apply();
    }

    // --- app nascoste dalla griglia ---

    Set<String> hidden() {
        return new HashSet<>(split(sp.getString("hidden", "")));
    }

    void setHidden(Set<String> keys) {
        sp.edit().putString("hidden", join(new ArrayList<>(keys))).apply();
    }

    // --- aspetto ---

    /** Impronta dell'ultima immagine importata da Download (per non reimportarla). */
    long importStamp() {
        return sp.getLong("wall_import", 0L);
    }

    void setImportStamp(long v) {
        sp.edit().putLong("wall_import", v).apply();
    }

    int theme() {
        return sp.getInt("theme", 0);
    }

    void setTheme(int t) {
        sp.edit().putInt("theme", t).apply();
    }

    // --- tasto Home ---

    boolean redirectEnabled() {
        return sp.getBoolean("redirect", true);
    }

    void setRedirectEnabled(boolean on) {
        sp.edit().putBoolean("redirect", on).apply();
    }

    // --- aggiornamenti ---

    long lastUpdateCheck() {
        return sp.getLong("upd_check", 0L);
    }

    void setLastUpdateCheck(long t) {
        sp.edit().putLong("upd_check", t).apply();
    }

    int latestVersion() {
        return sp.getInt("upd_latest", 0);
    }

    void setLatestVersion(int v) {
        sp.edit().putInt("upd_latest", v).apply();
    }

    boolean autoUpdate() {
        return sp.getBoolean("upd_auto", true);
    }

    void setAutoUpdate(boolean on) {
        sp.edit().putBoolean("upd_auto", on).apply();
    }

    /** Ultima versione per cui è stata proposta l'installazione automatica, e quando. */
    int promptedVersion() {
        return sp.getInt("upd_prompted", 0);
    }

    long promptedAt() {
        return sp.getLong("upd_prompted_at", 0L);
    }

    void setPrompted(int version, long when) {
        sp.edit().putInt("upd_prompted", version).putLong("upd_prompted_at", when).apply();
    }

    // --- utilità ---

    private static List<String> split(String s) {
        List<String> out = new ArrayList<>();
        if (s == null || s.isEmpty()) return out;
        for (String p : s.split(SEP)) {
            if (!p.isEmpty()) out.add(p);
        }
        return out;
    }

    private static String join(List<String> l) {
        StringBuilder b = new StringBuilder();
        for (String s : l) {
            if (b.length() > 0) b.append(SEP);
            b.append(s);
        }
        return b.toString();
    }
}
