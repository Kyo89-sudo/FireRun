package com.kyo89.firerun;

import android.content.Context;
import android.content.SharedPreferences;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** Impostazioni persistenti di FireRun. */
final class Prefs {
    private static final String FILE = "firerun";
    private static final String SEP = "\n";
    private static final int MAX_RECENTS = 12;
    private static final int MAX_WINDOWS = 15;

    private final SharedPreferences sp;

    Prefs(Context c) {
        sp = c.getApplicationContext().getSharedPreferences(FILE, Context.MODE_PRIVATE);
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

    // --- recenti ---

    List<String> recents() {
        return split(sp.getString("rec", ""));
    }

    void addRecent(String key) {
        List<String> l = recents();
        l.remove(key);
        l.add(0, key);
        while (l.size() > MAX_RECENTS) l.remove(l.size() - 1);
        sp.edit().putString("rec", join(l)).putLong("t:" + key, System.currentTimeMillis()).apply();
    }

    long lastLaunch(String key) {
        return sp.getLong("t:" + key, 0L);
    }

    // --- app nascoste dalla griglia ---

    Set<String> hidden() {
        return new HashSet<>(split(sp.getString("hidden", "")));
    }

    void setHidden(Set<String> keys) {
        sp.edit().putString("hidden", join(new ArrayList<>(keys))).apply();
    }

    // --- aspetto ---

    long wallpaperStamp() {
        return sp.getLong("wall_stamp", 0L);
    }

    void setWallpaperStamp(long v) {
        sp.edit().putLong("wall_stamp", v).apply();
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

    long bypassUntil() {
        return sp.getLong("bypass", 0L);
    }

    void setBypassUntil(long wallClockMs) {
        sp.edit().putLong("bypass", wallClockMs).apply();
    }

    /** Sospende il Tasto Home mentre è aperta una schermata di sistema lanciata da FireRun (max 30 min). */
    void pauseRedirectForSystemScreen() {
        sp.edit().putLong("syspause", System.currentTimeMillis() + 30L * 60 * 1000).apply();
    }

    long systemPauseUntil() {
        return sp.getLong("syspause", 0L);
    }

    void clearPauses() {
        sp.edit().putLong("syspause", 0L).putLong("bypass", 0L).apply();
    }

    List<String> recentWindows() {
        return split(sp.getString("windows", ""));
    }

    void logWindow(String className, boolean redirected) {
        List<String> l = recentWindows();
        String time = new SimpleDateFormat("HH:mm:ss", Locale.ITALY).format(new Date());
        l.add(0, time + (redirected ? "  → FireRun  " : "  (ignorata)  ") + className);
        while (l.size() > MAX_WINDOWS) l.remove(l.size() - 1);
        sp.edit().putString("windows", join(l)).apply();
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
