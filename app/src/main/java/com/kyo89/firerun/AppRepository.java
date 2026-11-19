package com.kyo89.firerun;

import android.content.Context;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.drawable.Drawable;

import java.text.Collator;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Legge le app installate. Da chiamare fuori dal thread UI (un thread alla volta).
 *
 * Le voci già lette restano in cache: a ogni ricarica si rilegge solo ciò che è cambiato
 * (app nuove o aggiornate), invece di decodificare di nuovo tutti i banner.
 */
final class AppRepository {
    private AppRepository() {}

    private static Map<String, AppEntry> cache = new HashMap<>();

    static synchronized List<AppEntry> load(Context ctx) {
        PackageManager pm = ctx.getPackageManager();
        String locale = Locale.getDefault().toString();
        Map<String, AppEntry> byPackage = new LinkedHashMap<>();
        // Prima gli ingressi TV (con banner), poi quelli normali per le app solo-telefono.
        collect(pm, byPackage, Intent.CATEGORY_LEANBACK_LAUNCHER, true, locale);
        collect(pm, byPackage, Intent.CATEGORY_LAUNCHER, false, locale);
        byPackage.remove(ctx.getPackageName());
        cache = new HashMap<>(byPackage); // le app disinstallate escono dalla cache

        List<AppEntry> list = new ArrayList<>(byPackage.values());
        final Collator collator = Collator.getInstance();
        collator.setStrength(Collator.PRIMARY);
        Collections.sort(list, (a, b) -> collator.compare(a.label, b.label));
        return list;
    }

    private static void collect(PackageManager pm, Map<String, AppEntry> out, String category,
                                boolean leanback, String locale) {
        Intent query = new Intent(Intent.ACTION_MAIN).addCategory(category);
        List<ResolveInfo> found;
        try {
            found = pm.queryIntentActivities(query, 0);
        } catch (RuntimeException e) {
            return;
        }
        for (ResolveInfo ri : found) {
            ActivityInfo ai = ri.activityInfo;
            if (ai == null || out.containsKey(ai.packageName)) continue;

            // Un aggiornamento cambia il percorso dell'APK: con lo stesso "timbro" si riusa la voce.
            String dir = ai.applicationInfo != null ? ai.applicationInfo.sourceDir : "";
            String stamp = dir + '|' + ai.name + '|' + leanback + '|' + locale;
            AppEntry old = cache.get(ai.packageName);
            if (old != null && old.stamp.equals(stamp)) {
                out.put(ai.packageName, old);
                continue;
            }

            CharSequence labelCs = ri.loadLabel(pm);
            String label = labelCs != null ? labelCs.toString().trim() : ai.packageName;
            if (label.isEmpty()) label = ai.packageName;

            Drawable banner = null;
            try {
                banner = ai.loadBanner(pm);
                if (banner == null && ai.applicationInfo != null) banner = ai.applicationInfo.loadBanner(pm);
            } catch (RuntimeException ignored) {
            }

            AppEntry e = new AppEntry(ri, label, leanback, banner, stamp);
            if (banner == null) e.icon(pm); // la griglia la mostra subito: meglio leggerla qui, fuori dal thread UI
            out.put(ai.packageName, e);
        }
    }
}
