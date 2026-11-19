package com.kyo89.firerun;

import android.accessibilityservice.AccessibilityService;
import android.app.ActivityOptions;
import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.PixelFormat;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.View;
import android.view.ViewTreeObserver;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;

/**
 * Tasto Home → FireRun.
 *
 * Basato sul "Guard" di dumbtv (https://github.com/humblemuzzu/dumbtv, licenza MIT,
 * © 2026 dumbtv contributors — vedi THIRD_PARTY_NOTICES.md), adattato a FireRun.
 *
 * Fire OS tiene il tasto Home legato al launcher Amazon e lo gestisce prima di qualsiasi
 * servizio di accessibilità, quindi questo servizio aspetta la finestra della home Amazon
 * e la sostituisce. In dettaglio:
 *  - si sostituisce SOLO la home ({@link #AMAZON_HOME}): le Impostazioni e le altre
 *    schermate Amazon sono classi diverse e restano aperte;
 *  - si ascoltano le finestre di tutte le app, per sapere quando si è usciti dalla home Amazon;
 *  - dopo il tasto Home Android ritarda di 5 secondi gli avvii di app: un PendingIntent
 *    lanciato da un allarme parte da system_server, che ne è esente. L'allarme scatta subito
 *    solo con {@code min_futurity=0}, che deploy.ps1 -AttivaTastoHome imposta;
 *  - Android 11 rifiuta l'avvio se l'app non ha una finestra visibile: un velo
 *    (overlay di accessibilità) copre la home Amazon finché FireRun non è disegnato,
 *    così la home Amazon non si vede e l'avvio è consentito;
 *  - al risveglio dallo standby si torna a FireRun solo se l'ultima schermata era la home Amazon.
 */
public class HomeRedirectService extends AccessibilityService {
    static final String AMAZON_LAUNCHER = "com.amazon.tv.launcher";
    /** Solo questa schermata viene sostituita: mai Impostazioni, ricerca o altro. */
    static final String AMAZON_HOME = "com.amazon.tv.launcher.ui.HomeActivity_vNext";

    private static final long COVER_MAX_MS = 1500;
    private static final long LAUNCH_FALLBACK_MS = 150;
    private static final long SHOWN_DELAY_MS = Build.VERSION.SDK_INT >= 34 ? 300 : 0;
    private static final long REPEAT_MS = 1000;
    private static final long BOOT_WINDOW_MS = 4 * 60 * 1000;   // "appena acceso" = primi 4 minuti
    private static final long UPDATE_WINDOW_MS = 2 * 60 * 1000; // "appena aggiornato" = ultimi 2 minuti

    /** La home Amazon è stata aperta apposta (Opzioni): resta finché non si apre un'altra app. */
    private static volatile boolean paused;
    private static HomeRedirectService running;
    /** Un avvio di FireRun è in coda nell'AlarmManager (per non annullarlo a vuoto a ogni finestra). */
    private static volatile boolean armed;
    /** Ultime finestre Amazon viste, per Opzioni → Diagnostica. Solo in memoria: prima ogni
     *  cambio di schermata scriveva sul disco. */
    private static final java.util.ArrayDeque<String> windows = new java.util.ArrayDeque<>();
    private static final int MAX_WINDOWS = 15;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable uncover = this::uncover;
    private final Runnable launch = () -> home(this, 0);
    private final Runnable retry = this::retryHome;
    private View cover;
    private boolean onAmazonHome;
    private boolean retryPending;
    private long lastLaunch;
    private boolean screenOnRegistered;
    private Prefs prefs;
    /** Tastiere installate: compaiono sopra le altre app senza cambiare schermata. */
    private final java.util.Set<String> keyboards = new java.util.HashSet<>();

    private final BroadcastReceiver screenOn = new BroadcastReceiver() {
        @Override
        public void onReceive(Context c, Intent i) {
            loadKeyboards(); // una tastiera installata nel frattempo
            if (!paused && onAmazonHome && prefs.redirectEnabled()) launchWhenCovered();
        }
    };

    // ================================================================ API per MainActivity

    /** Lascia la home Amazon sullo schermo finché l'utente non apre un'altra app. */
    static void pause() {
        paused = true;
        HomeRedirectService s = running;
        if (s != null) s.cancelRetry();
    }

    /** FireRun è sullo schermo: via il velo. */
    static void shown() {
        HomeRedirectService s = running;
        if (s != null) s.handler.post(s.uncover);
    }

    /** Annulla un avvio in coda: non deve riportarti su FireRun dopo che hai aperto un'app. */
    static void arrived(Context c) {
        if (!armed) return;
        armed = false;
        AlarmManager alarms = (AlarmManager) c.getSystemService(Context.ALARM_SERVICE);
        if (alarms != null) alarms.cancel(launchIntent(c));
    }

    static java.util.List<String> recentWindows() {
        synchronized (windows) {
            return new java.util.ArrayList<>(windows);
        }
    }

    private static void logWindow(String className, boolean redirected) {
        String time = new java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.ITALY).format(new java.util.Date());
        synchronized (windows) {
            windows.addFirst(time + (redirected ? "  → FireRun  " : "  (ignorata)  ") + className);
            while (windows.size() > MAX_WINDOWS) windows.removeLast();
        }
    }

    // ================================================================ avvio di FireRun

    private static void home(Context c, long delayMs) {
        AlarmManager alarms = (AlarmManager) c.getSystemService(Context.ALARM_SERVICE);
        if (alarms == null) return;
        armed = true;
        alarms.setExact(AlarmManager.ELAPSED_REALTIME, SystemClock.elapsedRealtime() + delayMs,
                launchIntent(c));
    }

    private static PendingIntent launchIntent(Context c) {
        Intent i = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
                .setClass(c, MainActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT | 0x04000000; // FLAG_IMMUTABLE (API 23)
        return PendingIntent.getActivity(c, 0, i, flags,
                ActivityOptions.makeCustomAnimation(c, 0, 0).toBundle());
    }

    // ================================================================ ciclo di vita del servizio

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        running = this;
        prefs = new Prefs(this);
        try {
            View view = new View(this);
            view.setVisibility(View.GONE);
            WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                    WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT,
                    WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                            | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                            | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                    PixelFormat.OPAQUE);
            lp.windowAnimations = 0;
            ((WindowManager) getSystemService(WINDOW_SERVICE)).addView(view, lp);
            cover = view;
        } catch (RuntimeException e) {
            cover = null; // senza velo funziona lo stesso, si vede solo un attimo la home Amazon
        }
        loadKeyboards();
        if (!screenOnRegistered) {
            registerReceiver(screenOn, new IntentFilter(Intent.ACTION_SCREEN_ON));
            screenOnRegistered = true;
        }
        // All'accensione, e dopo un aggiornamento di FireRun, la home Amazon compare prima che
        // il servizio riparta: quell'evento è perso, quindi in quei momenti portiamo FireRun
        // davanti noi. In altri momenti no: se il servizio ripartisse durante un film non deve
        // toglierti dall'app.
        boolean justStarted = SystemClock.elapsedRealtime() < BOOT_WINDOW_MS || justUpdated();
        if (justStarted && prefs.redirectEnabled() && !MainActivity.isVisible()) {
            onAmazonHome = true;
            handler.postDelayed(this::launchWhenCovered, 1500);
        }
    }

    /** FireRun è stato appena installato o aggiornato (da sé o con deploy.ps1). */
    private boolean justUpdated() {
        try {
            long t = getPackageManager().getPackageInfo(getPackageName(), 0).lastUpdateTime;
            long ago = System.currentTimeMillis() - t;
            return ago >= 0 && ago < UPDATE_WINDOW_MS;
        } catch (Exception e) {
            return false;
        }
    }

    @Override
    public void onDestroy() {
        cancelRetry();
        handler.removeCallbacksAndMessages(null);
        if (screenOnRegistered) {
            try {
                unregisterReceiver(screenOn);
            } catch (RuntimeException ignored) {
            }
            screenOnRegistered = false;
        }
        if (cover != null) {
            try {
                ((WindowManager) getSystemService(WINDOW_SERVICE)).removeView(cover);
            } catch (RuntimeException ignored) {
            }
            cover = null;
        }
        if (running == this) running = null;
        super.onDestroy();
    }

    @Override
    public void onInterrupt() {
    }

    // ================================================================ eventi

    @Override
    public void onAccessibilityEvent(AccessibilityEvent e) {
        if (e == null || e.getEventType() != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return;
        CharSequence pkg = e.getPackageName(), cls = e.getClassName();
        if (pkg == null || prefs == null) return;
        String className = cls != null ? cls.toString() : "";
        // Toast, tastiera, popup: si sovrappongono alla schermata senza cambiarla.
        // Prima azzeravano lo stato e la home Amazon poteva restare davanti.
        if (isOverlay(className) || keyboards.contains(pkg.toString())) return;

        if (!AMAZON_LAUNCHER.contentEquals(pkg)) {
            // Finestre di sistema (barra, dialoghi del sistema): non dicono dove sei.
            if ("com.android.systemui".contentEquals(pkg) || "android".contentEquals(pkg)) return;
            // Un'altra app (o FireRun) è davanti: niente più home Amazon in corso.
            cancelRetry();
            onAmazonHome = false;
            paused = false;
            if (!getPackageName().contentEquals(pkg)) arrived(this);
            return;
        }

        // Dentro il launcher Amazon contano solo le sue schermate: dialoghi e finestre generiche
        // di Android (android.app.Dialog, android.widget.*) sopra la home non devono far
        // credere di averla lasciata.
        if (className.isEmpty() || className.startsWith("android.")) return;
        onAmazonHome = AMAZON_HOME.equals(className);
        boolean redirect = onAmazonHome && !paused && prefs.redirectEnabled();
        logWindow(className, redirect);
        if (!redirect) {
            cancelRetry();
            return;
        }
        retryPending = true;
        retryHome();
    }

    private static boolean isOverlay(String cls) {
        return cls.contains("Toast") || cls.endsWith("$TN") || cls.startsWith("android.widget.PopupWindow")
                || cls.equals("android.inputmethodservice.SoftInputWindow");
    }

    private void loadKeyboards() {
        keyboards.clear();
        try {
            android.view.inputmethod.InputMethodManager imm =
                    (android.view.inputmethod.InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
            if (imm == null) return;
            for (android.view.inputmethod.InputMethodInfo info : imm.getEnabledInputMethodList()) {
                keyboards.add(info.getPackageName());
            }
        } catch (RuntimeException ignored) {
        }
    }

    /** Fire OS a volte lascia la home Amazon davanti dopo una raffica di eventi: si riprova una volta al secondo. */
    private void retryHome() {
        handler.removeCallbacks(retry);
        if (running != this || paused || !onAmazonHome || !retryPending) {
            retryPending = false;
            return;
        }
        long now = SystemClock.uptimeMillis();
        long wait = Math.max(0, lastLaunch + REPEAT_MS - now);
        if (wait == 0) {
            retryPending = false;
            lastLaunch = now;
            launchWhenCovered();
            return;
        }
        handler.postAtTime(retry, now + wait);
    }

    private void cancelRetry() {
        handler.removeCallbacks(retry);
        retryPending = false;
    }

    /**
     * Mostra il velo, poi lancia FireRun appena il velo è davvero sullo schermo
     * (Android 11 vuole una finestra visibile). Se il velo c'è già, si parte subito.
     */
    private void launchWhenCovered() {
        handler.removeCallbacks(launch);
        if (MainActivity.isVisible()) {
            uncover();
            return;
        }
        if (cover == null) {
            home(this, 0);
            return;
        }
        boolean alreadyShown = cover.getVisibility() == View.VISIBLE;
        showCover();
        if (alreadyShown) {
            home(this, 0);
            return;
        }
        final View v = cover;
        v.getViewTreeObserver().addOnPreDrawListener(new ViewTreeObserver.OnPreDrawListener() {
            @Override
            public boolean onPreDraw() {
                v.getViewTreeObserver().removeOnPreDrawListener(this);
                handler.removeCallbacks(launch);
                home(HomeRedirectService.this, SHOWN_DELAY_MS);
                return true;
            }
        });
        handler.postDelayed(launch, LAUNCH_FALLBACK_MS);
    }

    private void showCover() {
        if (cover == null) return;
        cover.setBackgroundColor(0xFF0A0C10); // stesso colore di base di FireRun
        cover.setVisibility(View.VISIBLE);
        handler.removeCallbacks(uncover);
        handler.postDelayed(uncover, COVER_MAX_MS);
    }

    private void uncover() {
        handler.removeCallbacks(uncover);
        if (cover != null) cover.setVisibility(View.GONE);
    }
}
