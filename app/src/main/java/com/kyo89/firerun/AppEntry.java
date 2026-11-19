package com.kyo89.firerun;

import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.drawable.Drawable;

/**
 * Un'app installata mostrata nel launcher.
 * Le voci restano in cache tra una ricarica e l'altra (vedi AppRepository): banner e
 * colore si calcolano una volta sola, l'icona solo quando serve davvero.
 */
final class AppEntry {
    final String key;          // "pacchetto/attività"
    final String pkg;
    final String label;
    final ComponentName component;
    final boolean leanback;    // ha un ingresso pensato per la TV
    final Drawable banner;     // immagine 16:9, può essere null
    final String stamp;        // cambia quando l'app viene aggiornata
    private final ResolveInfo info;

    // Usati solo dal thread UI dopo la creazione.
    private Drawable icon;
    private boolean iconLoaded;
    private int color;
    private boolean colorDone;

    AppEntry(ResolveInfo info, String label, boolean leanback, Drawable banner, String stamp) {
        this.info = info;
        this.component = new ComponentName(info.activityInfo.packageName, info.activityInfo.name);
        this.pkg = component.getPackageName();
        this.key = component.flattenToShortString();
        this.label = label;
        this.leanback = leanback;
        this.banner = banner;
        this.stamp = stamp;
    }

    /** Icona caricata alla prima richiesta: le app con banner spesso non la mostrano mai. */
    Drawable icon(PackageManager pm) {
        if (!iconLoaded) {
            iconLoaded = true;
            try {
                icon = info.loadIcon(pm);
            } catch (RuntimeException ignored) {
            }
        }
        return icon;
    }

    /** Libera l'icona se non serve alla griglia (che usa il banner). */
    void dropIconIfBanner() {
        if (banner != null) {
            icon = null;
            iconLoaded = false;
        }
    }

    boolean hasColor() {
        return colorDone;
    }

    int color() {
        return color;
    }

    void setColor(int c) {
        color = c;
        colorDone = true;
    }

    Intent launchIntent() {
        Intent i = new Intent(Intent.ACTION_MAIN);
        i.addCategory(leanback ? Intent.CATEGORY_LEANBACK_LAUNCHER : Intent.CATEGORY_LAUNCHER);
        i.setComponent(component);
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
        return i;
    }
}
