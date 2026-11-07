package com.kyo89.firerun;

import android.accessibilityservice.AccessibilityService;
import android.content.Intent;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.accessibility.AccessibilityEvent;

import java.util.Locale;

/**
 * Su Fire OS il tasto Home apre sempre il launcher Amazon. Questo servizio di
 * accessibilità vede solo i cambi di finestra del pacchetto Amazon (non legge
 * lo schermo) e, quando compare la home Amazon, riporta subito a FireRun.
 */
public class HomeRedirectService extends AccessibilityService {
    static final String AMAZON_LAUNCHER = "com.amazon.tv.launcher";

    /** Finestre Amazon da NON sostituire (menu rapidi, dialoghi, impostazioni…). */
    private static final String[] SKIP = {
            "dialog", "menu", "quick", "toast", "notification", "notif",
            "settings", "overlay", "popup", "panel", "profile", "search", "voice"
    };

    /** Attesa prima di reindirizzare: lascia il tempo a FireRun di risultare in pausa. */
    private static final long CONFIRM_DELAY_MS = 250;
    private static final long MIN_GAP_MS = 700;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private long lastRedirect;

    private final Runnable redirect = new Runnable() {
        @Override
        public void run() {
            // La home Amazon a volte "si aggiorna" in secondo piano: se FireRun è
            // già davanti non va riaperto (altrimenti torna in cima mentre navighi).
            if (MainActivity.isVisible()) return;
            long now = SystemClock.uptimeMillis();
            if (now - lastRedirect < MIN_GAP_MS) return;
            lastRedirect = now;
            Intent home = new Intent(HomeRedirectService.this, MainActivity.class);
            home.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                    | Intent.FLAG_ACTIVITY_CLEAR_TOP
                    | Intent.FLAG_ACTIVITY_SINGLE_TOP);
            try {
                startActivity(home);
            } catch (RuntimeException ignored) {
            }
        }
    };

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event == null || event.getEventType() != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return;
        CharSequence pkg = event.getPackageName();
        if (pkg == null || !AMAZON_LAUNCHER.contentEquals(pkg)) return;

        String cls = event.getClassName() != null ? event.getClassName().toString() : "";
        Prefs prefs = new Prefs(this);

        long now = System.currentTimeMillis();
        // Mentre è aperta una schermata di sistema lanciata da FireRun (Impostazioni, Info app…)
        // non si reindirizza nulla: su Fire OS 8 le Impostazioni girano nella stessa finestra
        // della home Amazon e verrebbero chiuse subito. Si riattiva al ritorno in FireRun.
        boolean systemScreen = now < prefs.systemPauseUntil();
        boolean redirectIt = prefs.redirectEnabled()
                && now >= prefs.bypassUntil()
                && !systemScreen
                && !shouldSkip(cls);
        prefs.logWindow(cls, redirectIt);
        if (!redirectIt) return;

        handler.removeCallbacks(redirect);
        handler.postDelayed(redirect, CONFIRM_DELAY_MS);
    }

    private static boolean shouldSkip(String cls) {
        String c = cls.toLowerCase(Locale.ROOT);
        for (String s : SKIP) {
            if (c.contains(s)) return true;
        }
        return false;
    }

    @Override
    public void onInterrupt() {
        handler.removeCallbacks(redirect);
    }

    @Override
    public void onDestroy() {
        handler.removeCallbacks(redirect);
        super.onDestroy();
    }
}
