package com.kyo89.firerun;

import android.content.ComponentName;
import android.content.Intent;
import android.graphics.drawable.Drawable;

/** Un'app installata mostrata nel launcher. */
final class AppEntry {
    final String key;          // "pacchetto/attività"
    final String pkg;
    final String label;
    final ComponentName component;
    final boolean leanback;    // ha un ingresso pensato per la TV
    final Drawable banner;     // immagine 16:9, può essere null
    final Drawable icon;

    AppEntry(ComponentName component, String label, boolean leanback, Drawable banner, Drawable icon) {
        this.component = component;
        this.pkg = component.getPackageName();
        this.key = component.flattenToShortString();
        this.label = label;
        this.leanback = leanback;
        this.banner = banner;
        this.icon = icon;
    }

    Intent launchIntent() {
        Intent i = new Intent(Intent.ACTION_MAIN);
        i.addCategory(leanback ? Intent.CATEGORY_LEANBACK_LAUNCHER : Intent.CATEGORY_LAUNCHER);
        i.setComponent(component);
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
        return i;
    }
}
