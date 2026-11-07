package com.kyo89.firerun;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.drawable.Drawable;

import java.text.Collator;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Legge le app installate. Da chiamare fuori dal thread UI. */
final class AppRepository {
    private AppRepository() {}

    static List<AppEntry> load(Context ctx) {
        PackageManager pm = ctx.getPackageManager();
        Map<String, AppEntry> byPackage = new LinkedHashMap<>();
        // Prima gli ingressi TV (con banner), poi quelli normali per le app solo-telefono.
        collect(pm, byPackage, Intent.CATEGORY_LEANBACK_LAUNCHER, true);
        collect(pm, byPackage, Intent.CATEGORY_LAUNCHER, false);
        byPackage.remove(ctx.getPackageName());

        List<AppEntry> list = new ArrayList<>(byPackage.values());
        final Collator collator = Collator.getInstance();
        collator.setStrength(Collator.PRIMARY);
        Collections.sort(list, new Comparator<AppEntry>() {
            @Override
            public int compare(AppEntry a, AppEntry b) {
                return collator.compare(a.label, b.label);
            }
        });
        return list;
    }

    private static void collect(PackageManager pm, Map<String, AppEntry> out, String category, boolean leanback) {
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

            CharSequence labelCs = ri.loadLabel(pm);
            String label = labelCs != null ? labelCs.toString().trim() : ai.packageName;
            if (label.isEmpty()) label = ai.packageName;

            Drawable banner = null;
            try {
                banner = ai.loadBanner(pm);
                if (banner == null && ai.applicationInfo != null) banner = ai.applicationInfo.loadBanner(pm);
            } catch (RuntimeException ignored) {
            }
            Drawable icon = null;
            try {
                icon = ri.loadIcon(pm);
            } catch (RuntimeException ignored) {
            }

            out.put(ai.packageName, new AppEntry(
                    new ComponentName(ai.packageName, ai.name), label, leanback, banner, icon));
        }
    }
}
