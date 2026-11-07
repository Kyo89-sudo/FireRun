package com.kyo89.firerun;

import android.app.Activity;
import android.app.Dialog;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.Editable;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.text.format.DateUtils;
import android.util.DisplayMetrics;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.view.WindowManager;
import android.view.inputmethod.EditorInfo;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.GridLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.text.Normalizer;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Home di FireRun: barra in alto, area "hero" dell'app selezionata, barra delle
 * app in vetro con il pulsante +, Recenti e Tutte le app. Nessuna pubblicità.
 */
public class MainActivity extends Activity {

    private static final String SEC_DOCK = "dock:";
    private static final String SEC_REC = "rec:";
    private static final String SEC_ALL = "all:";
    private static final long UPDATE_EVERY_MS = 2L * 60 * 60 * 1000;   // controllo ogni 2 ore
    private static final long REPROMPT_MS = 24L * 60 * 60 * 1000;      // se annulli, riprova dopo 24 h
    private static final int DEFAULT_DOCK_SIZE = 6;

    private final Handler ui = new Handler(Looper.getMainLooper());
    private final ExecutorService io = Executors.newSingleThreadExecutor();   // lettura app
    private final ExecutorService net = Executors.newSingleThreadExecutor();  // rete (aggiornamenti)
    private final Map<String, AppEntry> tagApps = new HashMap<>();

    private Prefs prefs;
    private List<AppEntry> apps = new ArrayList<>();
    private Map<String, AppEntry> byKey = new HashMap<>();

    private WallpaperView wallpaper;
    private ScrollView scroll;
    private LinearLayout dockRow, recentRow, recentBlock;
    private GridLayout grid;
    private TextView allHeader, heroOver, heroTitle, heroMeta, clock, dateView;
    private TextView updatePill;
    private View firstDockView, firstAllView;

    private AppEntry heroApp;
    private String focusTag;
    private int loadGen, tileW, tileH, columns;
    private boolean receiverOn;

    private final BroadcastReceiver packageReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context c, Intent i) {
            reload();
        }
    };

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            updateClock();
            ui.postDelayed(this, 15_000);
        }
    };

    // ================================================================ ciclo di vita

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        prefs = new Prefs(this);
        computeSizes();
        buildShell();
    }

    @Override
    protected void onStart() {
        super.onStart();
        if (!receiverOn) {
            IntentFilter f = new IntentFilter();
            f.addAction(Intent.ACTION_PACKAGE_ADDED);
            f.addAction(Intent.ACTION_PACKAGE_REMOVED);
            f.addAction(Intent.ACTION_PACKAGE_CHANGED);
            f.addDataScheme("package");
            registerReceiver(packageReceiver, f);
            receiverOn = true;
        }
        ui.removeCallbacks(tick);
        ui.post(tick);
        reload();
    }

    /** Letto dal servizio Tasto Home (stesso processo). */
    private static volatile boolean visible;

    static boolean isVisible() {
        return visible;
    }

    @Override
    protected void onResume() {
        super.onResume();
        visible = true;
        // Tornati in FireRun: il tasto Home riprende a funzionare normalmente.
        if (prefs.bypassUntil() != 0 || prefs.systemPauseUntil() != 0) prefs.clearPauses();
        checkUpdate(false); // dopo visible=true: l'auto-aggiornamento parte solo con FireRun davanti
    }

    @Override
    protected void onPause() {
        visible = false;
        super.onPause();
    }

    @Override
    protected void onStop() {
        super.onStop();
        if (receiverOn) {
            unregisterReceiver(packageReceiver);
            receiverOn = false;
        }
        ui.removeCallbacks(tick);
    }

    @Override
    protected void onDestroy() {
        io.shutdownNow();
        net.shutdownNow();
        super.onDestroy();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        goTop();
    }

    @Override
    public void onBackPressed() {
        goTop();
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_MENU) {
            View f = getCurrentFocus();
            AppEntry app = f != null && f.getTag() instanceof String ? tagApps.get(f.getTag()) : null;
            if (app != null) showAppMenu(app);
            else showOptions(0);
            return true;
        }
        if (keyCode == KeyEvent.KEYCODE_SEARCH) {
            showSearch();
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    // ================================================================ struttura

    private int dp(float v) {
        return Ui.dp(this, v);
    }

    private void computeSizes() {
        DisplayMetrics dm = getResources().getDisplayMetrics();
        int screenW = Math.max(dm.widthPixels, dm.heightPixels);
        int avail = screenW - 2 * dp(36) - dp(8);
        columns = Math.max(3, avail / dp(178));
        tileW = avail / columns - dp(16);
        tileH = tileW * 9 / 16;
    }

    private void buildShell() {
        FrameLayout root = new FrameLayout(this);
        wallpaper = new WallpaperView(this);
        wallpaper.setTheme(prefs.theme());
        root.addView(wallpaper, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        scroll = new ScrollView(this);
        scroll.setVerticalScrollBarEnabled(false);
        scroll.setClipToPadding(false);
        scroll.setFillViewport(true);

        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setClipChildren(false);
        col.setClipToPadding(false);
        col.setPadding(dp(36), dp(20), dp(36), dp(48));
        scroll.addView(col, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        root.addView(scroll, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        col.addView(buildHeader());
        LinearLayout.LayoutParams heroLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(196));
        heroLp.topMargin = dp(24);
        col.addView(buildHero(), heroLp);
        LinearLayout.LayoutParams dockLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        dockLp.topMargin = dp(10);
        col.addView(buildDock(), dockLp);

        recentBlock = new LinearLayout(this);
        recentBlock.setOrientation(LinearLayout.VERTICAL);
        recentBlock.setClipChildren(false);
        recentBlock.addView(sectionHeader("Recenti"));
        HorizontalScrollView hs = new HorizontalScrollView(this);
        hs.setHorizontalScrollBarEnabled(false);
        hs.setClipChildren(false);
        hs.setClipToPadding(false);
        recentRow = new LinearLayout(this);
        recentRow.setOrientation(LinearLayout.HORIZONTAL);
        recentRow.setClipChildren(false);
        recentRow.setPadding(dp(2), dp(10), dp(24), dp(10));
        hs.addView(recentRow);
        recentBlock.addView(hs);
        col.addView(recentBlock);

        allHeader = sectionHeader("Tutte le app");
        col.addView(allHeader);
        grid = new GridLayout(this);
        grid.setColumnCount(columns);
        grid.setUseDefaultMargins(false);
        grid.setClipChildren(false);
        grid.setClipToPadding(false);
        grid.setPadding(0, dp(6), 0, 0);
        col.addView(grid);

        setContentView(root);
        updateClock();
    }

    private View buildHeader() {
        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);

        ImageView logo = new ImageView(this);
        logo.setImageResource(R.drawable.ic_launcher);
        bar.addView(logo, new LinearLayout.LayoutParams(dp(26), dp(26)));
        TextView brand = Ui.text(this, "FireRun", 17, true, Ui.WHITE);
        LinearLayout.LayoutParams blp = wrap();
        blp.leftMargin = dp(8);
        bar.addView(brand, blp);

        LinearLayout nav = new LinearLayout(this);
        nav.setOrientation(LinearLayout.HORIZONTAL);
        nav.setBackground(Ui.glass(this, 999));
        nav.setPadding(dp(4), dp(4), dp(4), dp(4));
        nav.addView(navPill("Home", R.drawable.ic_home, v -> goTop()));
        nav.addView(navPill("Cerca", R.drawable.ic_search, v -> showSearch()));
        nav.addView(navPill("App", R.drawable.ic_apps, v -> scrollToAll()));
        nav.addView(navPill("Impostazioni", R.drawable.ic_settings, v -> openSystemSettings()));
        LinearLayout.LayoutParams nlp = wrap();
        nlp.leftMargin = dp(16);
        bar.addView(nav, nlp);

        bar.addView(new View(this), new LinearLayout.LayoutParams(0, 1, 1f));

        updatePill = Ui.pill(this, "Aggiornamento", R.drawable.ic_download, Ui.ACCENT, false);
        updatePill.setVisibility(View.GONE);
        updatePill.setOnClickListener(v -> Updater.downloadAndInstall(this, net, ui));
        LinearLayout.LayoutParams ulp = wrap();
        ulp.rightMargin = dp(16);
        bar.addView(updatePill, ulp);

        LinearLayout clockBox = new LinearLayout(this);
        clockBox.setOrientation(LinearLayout.VERTICAL);
        clockBox.setGravity(Gravity.END);
        clock = Ui.text(this, "", 20, true, Ui.WHITE);
        dateView = Ui.text(this, "", 11, false, Ui.TEXT_DIM);
        dateView.setPadding(0, dp(3), 0, 0);
        clockBox.addView(clock);
        clockBox.addView(dateView);
        bar.addView(clockBox);
        return bar;
    }

    private TextView navPill(String label, int icon, View.OnClickListener click) {
        TextView t = Ui.pill(this, label, icon, Color.TRANSPARENT, false);
        t.setOnClickListener(click);
        LinearLayout.LayoutParams lp = wrap();
        lp.rightMargin = dp(2);
        t.setLayoutParams(lp);
        return t;
    }

    private View buildHero() {
        LinearLayout hero = new LinearLayout(this);
        hero.setOrientation(LinearLayout.VERTICAL);
        hero.setGravity(Gravity.BOTTOM);
        hero.setPadding(dp(12), 0, 0, 0);

        heroOver = Ui.text(this, "", 11, true, Ui.ACCENT_LIGHT);
        heroOver.setLetterSpacing(0.14f);
        heroTitle = Ui.oneLine(Ui.text(this, "FireRun", 46, true, Ui.WHITE));
        heroTitle.setLetterSpacing(-0.02f);
        heroTitle.setPadding(0, dp(6), 0, dp(4));
        heroMeta = Ui.oneLine(Ui.text(this, "Caricamento app…", 14, false, Ui.TEXT_DIM));

        LinearLayout buttons = new LinearLayout(this);
        buttons.setOrientation(LinearLayout.HORIZONTAL);
        buttons.setClipChildren(false);
        TextView open = Ui.pill(this, "Apri", R.drawable.ic_play, 0x24FFFFFF, true);
        open.setOnClickListener(v -> {
            if (heroApp != null) launch(heroApp);
        });
        TextView opts = Ui.pill(this, "Opzioni", 0, 0x24FFFFFF, true);
        opts.setOnClickListener(v -> {
            if (heroApp != null) showAppMenu(heroApp);
        });
        buttons.addView(open, wrap());
        LinearLayout.LayoutParams olp = wrap();
        olp.leftMargin = dp(10);
        buttons.addView(opts, olp);

        hero.addView(heroOver);
        hero.addView(heroTitle);
        hero.addView(heroMeta);
        LinearLayout.LayoutParams blp = wrap();
        blp.topMargin = dp(16);
        hero.addView(buttons, blp);
        return hero;
    }

    private View buildDock() {
        FrameLayout box = new FrameLayout(this);
        box.setBackground(Ui.glass(this, 22));
        box.setClipChildren(false);
        box.setClipToPadding(false);
        box.setPadding(0, dp(12), 0, dp(6));

        HorizontalScrollView hs = new HorizontalScrollView(this);
        hs.setHorizontalScrollBarEnabled(false);
        hs.setClipChildren(false);
        hs.setClipToPadding(false);
        hs.setPadding(dp(20), dp(4), dp(20), 0);
        dockRow = new LinearLayout(this);
        dockRow.setOrientation(LinearLayout.HORIZONTAL);
        dockRow.setGravity(Gravity.TOP);
        dockRow.setClipChildren(false);
        hs.addView(dockRow);
        box.addView(hs);
        return box;
    }

    private TextView sectionHeader(String s) {
        TextView t = Ui.text(this, s, 16, true, Ui.WHITE);
        t.setPadding(dp(2), dp(20), 0, dp(2));
        return t;
    }

    private LinearLayout.LayoutParams wrap() {
        return new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    // ================================================================ dati

    private void reload() {
        final int gen = ++loadGen;
        final Context app = getApplicationContext();
        try {
            io.execute(() -> {
                final List<AppEntry> list = AppRepository.load(app);
                ui.post(() -> {
                    if (gen != loadGen || isFinishing() || isDestroyed()) return;
                    apps = list;
                    rebuildAll();
                });
            });
        } catch (RuntimeException ignored) {
        }
    }

    private void rebuildAll() {
        tagApps.clear();
        byKey = new HashMap<>();
        for (AppEntry a : apps) byKey.put(a.key, a);
        firstDockView = null;
        firstAllView = null;

        // Primo avvio: riempi la barra con alcune app TV.
        if (!prefs.hasDock() && !apps.isEmpty()) {
            List<String> start = new ArrayList<>();
            for (AppEntry a : apps) if (a.leanback && start.size() < DEFAULT_DOCK_SIZE) start.add(a.key);
            for (AppEntry a : apps) if (!start.contains(a.key) && start.size() < DEFAULT_DOCK_SIZE) start.add(a.key);
            prefs.setDock(start);
        }

        // --- barra delle app ---
        dockRow.removeAllViews();
        List<String> keys = prefs.dock();
        List<String> cleaned = new ArrayList<>();
        for (String k : keys) {
            AppEntry a = byKey.get(k);
            if (a == null) continue;
            cleaned.add(k);
            View item = dockItem(a);
            if (firstDockView == null) firstDockView = item.findViewWithTag(SEC_DOCK + a.key);
            dockRow.addView(item, marginEnd(16));
        }
        if (cleaned.size() != keys.size() && !apps.isEmpty()) prefs.setDock(cleaned);

        View sep = new View(this);
        sep.setBackgroundColor(0x38FFFFFF);
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(Math.max(1, dp(1)), dp(54));
        slp.setMargins(dp(4), dp(10), dp(20), 0);
        dockRow.addView(sep, slp);

        View plus = dockAction(R.drawable.ic_plus, "Aggiungi", true, v -> showAddApps());
        dockRow.addView(plus, marginEnd(16));
        View all = dockAction(R.drawable.ic_grid, "Tutte le app", false, v -> scrollToAll());
        dockRow.addView(all, marginEnd(0));
        if (firstDockView == null) firstDockView = plus.findViewWithTag("act:Aggiungi");

        // --- recenti ---
        recentRow.removeAllViews();
        int shown = 0;
        for (String k : prefs.recents()) {
            AppEntry a = byKey.get(k);
            if (a == null || shown >= 8) continue;
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(180), dp(101));
            lp.rightMargin = dp(14);
            recentRow.addView(card(a, SEC_REC, true), lp);
            shown++;
        }
        recentBlock.setVisibility(shown > 0 ? View.VISIBLE : View.GONE);

        // --- tutte le app ---
        grid.removeAllViews();
        Set<String> hidden = prefs.hidden();
        int n = 0;
        for (AppEntry a : apps) {
            if (hidden.contains(a.key)) continue;
            GridLayout.LayoutParams lp = new GridLayout.LayoutParams();
            lp.width = tileW;
            lp.height = tileH;
            lp.setMargins(dp(8), dp(8), dp(8), dp(8));
            View tile = card(a, SEC_ALL, false);
            if (firstAllView == null) firstAllView = tile;
            grid.addView(tile, lp);
            n++;
        }
        allHeader.setText("Tutte le app (" + n + ")");

        // Dopo ogni ricarica le AppEntry sono nuove: aggancia l'hero a quella aggiornata.
        if (heroApp != null) heroApp = byKey.get(heroApp.key);
        if (heroApp == null) {
            List<String> d = prefs.dock();
            heroApp = !d.isEmpty() ? byKey.get(d.get(0)) : (apps.isEmpty() ? null : apps.get(0));
            if (heroApp != null) showHero(heroApp, SEC_DOCK);
        }
        restoreFocus();
    }

    private LinearLayout.LayoutParams marginEnd(int dpEnd) {
        LinearLayout.LayoutParams lp = wrap();
        lp.rightMargin = dp(dpEnd);
        return lp;
    }

    // ================================================================ elementi

    /** Icona della barra: quadrato arrotondato con etichetta che compare quando è selezionata. */
    private View dockItem(final AppEntry app) {
        LinearLayout item = new LinearLayout(this);
        item.setOrientation(LinearLayout.VERTICAL);
        item.setGravity(Gravity.CENTER_HORIZONTAL);
        item.setClipChildren(false);

        final FrameLayout icon = new FrameLayout(this);
        icon.setBackground(Ui.round(this, 18, Ui.hue(app.pkg)));
        icon.setElevation(dp(4));
        if (app.icon != null) {
            ImageView iv = new ImageView(this);
            iv.setImageDrawable(copy(app.icon));
            iv.setScaleType(ImageView.ScaleType.FIT_CENTER);
            icon.addView(iv, new FrameLayout.LayoutParams(dp(50), dp(50), Gravity.CENTER));
        } else {
            icon.addView(Ui.text(this, app.label.substring(0, 1), 30, true, Ui.WHITE),
                    new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                            ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER));
        }
        final TextView label = Ui.oneLine(Ui.text(this, app.label, 11, true, Ui.WHITE));
        label.setGravity(Gravity.CENTER);
        label.setAlpha(0f);

        item.addView(icon, new LinearLayout.LayoutParams(dp(75), dp(75)));
        LinearLayout.LayoutParams llp = new LinearLayout.LayoutParams(dp(100), ViewGroup.LayoutParams.WRAP_CONTENT);
        llp.topMargin = dp(8);
        item.addView(label, llp);

        bindApp(icon, app, SEC_DOCK, 1.12f, 18, label);
        return item;
    }

    /** Pulsanti speciali della barra: "+" (tratteggiato) e "Tutte le app". */
    private View dockAction(int iconRes, String text, boolean dashed, View.OnClickListener click) {
        LinearLayout item = new LinearLayout(this);
        item.setOrientation(LinearLayout.VERTICAL);
        item.setGravity(Gravity.CENTER_HORIZONTAL);
        item.setClipChildren(false);

        final FrameLayout box = new FrameLayout(this);
        GradientDrawable bg;
        if (dashed) {
            bg = Ui.round(this, 18, Color.TRANSPARENT);
            bg.setStroke(dp(2), 0x80FFFFFF, dp(6), dp(5));
        } else {
            bg = Ui.round(this, 18, 0x24FFFFFF);
            bg.setStroke(Math.max(1, dp(1)), 0x33FFFFFF);
        }
        box.setBackground(bg);
        ImageView iv = new ImageView(this);
        iv.setImageResource(iconRes);
        iv.setImageTintList(android.content.res.ColorStateList.valueOf(Ui.WHITE));
        box.addView(iv, new FrameLayout.LayoutParams(dp(30), dp(30), Gravity.CENTER));
        box.setFocusable(true);
        box.setClickable(true);
        box.setTag("act:" + text);
        box.setContentDescription(text);
        box.setOnClickListener(click);

        final TextView label = Ui.oneLine(Ui.text(this, text, 11, true, Ui.WHITE));
        label.setGravity(Gravity.CENTER);
        label.setAlpha(0f);
        item.addView(box, new LinearLayout.LayoutParams(dp(75), dp(75)));
        LinearLayout.LayoutParams llp = new LinearLayout.LayoutParams(dp(100), ViewGroup.LayoutParams.WRAP_CONTENT);
        llp.topMargin = dp(8);
        item.addView(label, llp);

        final GradientDrawable ring = Ui.ring(this, 18);
        box.setOnFocusChangeListener((v, has) -> {
            Ui.focusScale(v, has, 1.12f);
            v.animate().translationY(has ? -dp(4) : 0).setDuration(140).start();
            box.setForeground(has ? ring : null);
            label.animate().alpha(has ? 1f : 0f).setDuration(140).start();
        });
        return item;
    }

    /** Riquadro 16:9 (Recenti e Tutte le app): banner TV se c'è, altrimenti icona e nome. */
    private View card(final AppEntry app, String section, boolean translucent) {
        final FrameLayout tile = new FrameLayout(this);
        int base = Ui.hue(app.pkg);
        tile.setBackground(Ui.round(this, 14, translucent ? (base & 0x00FFFFFF) | 0xBF000000 : base));
        tile.setOutlineProvider(ViewOutlineProvider.BACKGROUND);
        tile.setClipToOutline(true);

        if (app.banner != null) {
            ImageView iv = new ImageView(this);
            iv.setScaleType(ImageView.ScaleType.CENTER_CROP);
            iv.setImageDrawable(copy(app.banner));
            tile.addView(iv, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        } else {
            LinearLayout inner = new LinearLayout(this);
            inner.setOrientation(LinearLayout.VERTICAL);
            inner.setPadding(dp(12), dp(12), dp(12), dp(10));
            FrameLayout ib = new FrameLayout(this);
            ib.setBackground(Ui.round(this, 9, 0x2EFFFFFF));
            if (app.icon != null) {
                ImageView iv = new ImageView(this);
                iv.setImageDrawable(copy(app.icon));
                ib.addView(iv, new FrameLayout.LayoutParams(dp(26), dp(26), Gravity.CENTER));
            }
            inner.addView(ib, new LinearLayout.LayoutParams(dp(34), dp(34)));
            inner.addView(new View(this), new LinearLayout.LayoutParams(1, 0, 1f));
            inner.addView(Ui.oneLine(Ui.text(this, app.label, 13, true, Ui.WHITE)));
            tile.addView(inner, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        }
        bindApp(tile, app, section, 1.08f, 14, null);
        if (section.equals(SEC_ALL) && firstAllView == null) firstAllView = tile;
        return tile;
    }

    /** Comportamento comune: selezione, apertura, menu, aggiornamento dell'area hero. */
    private void bindApp(final FrameLayout v, final AppEntry app, final String section,
                         final float scale, float radius, final View label) {
        final String tag = section + app.key;
        v.setTag(tag);
        tagApps.put(tag, app);
        v.setFocusable(true);
        v.setClickable(true);
        v.setLongClickable(true);
        v.setContentDescription(app.label);
        final GradientDrawable ring = Ui.ring(this, radius);
        v.setOnFocusChangeListener((view, has) -> {
            Ui.focusScale(view, has, scale);
            if (label != null) {
                view.animate().translationY(has ? -dp(4) : 0).setDuration(140).start();
                label.animate().alpha(has ? 1f : 0f).setDuration(140).start();
            }
            v.setForeground(has ? ring : null);
            if (has) {
                focusTag = tag;
                showHero(app, section);
            }
        });
        v.setOnClickListener(view -> launch(app));
        v.setOnLongClickListener(view -> {
            showAppMenu(app);
            return true;
        });
    }

    private void showHero(AppEntry app, String section) {
        heroApp = app;
        heroOver.setText(SEC_DOCK.equals(section) ? "NELLA BARRA" : SEC_REC.equals(section) ? "RECENTE" : "APP");
        heroTitle.setText(app.label);
        long ts = prefs.lastLaunch(app.key);
        String meta = app.pkg;
        if (ts > 0) {
            long now = System.currentTimeMillis();
            CharSequence when = now - ts < DateUtils.MINUTE_IN_MILLIS ? "adesso"
                    : DateUtils.getRelativeTimeSpanString(ts, now, DateUtils.MINUTE_IN_MILLIS);
            meta = "Aperta " + when + "  ·  " + app.pkg;
        }
        heroMeta.setText(meta);
    }

    private void restoreFocus() {
        View target = focusTag != null ? scroll.findViewWithTag(focusTag) : null;
        if (target == null) target = firstDockView != null ? firstDockView : firstAllView;
        if (target != null) target.requestFocus();
    }

    private void goTop() {
        if (scroll != null) scroll.smoothScrollTo(0, 0);
        focusTag = null;
        if (firstDockView != null) firstDockView.requestFocus();
    }

    private void scrollToAll() {
        if (firstAllView != null) firstAllView.requestFocus();
        ui.post(() -> scroll.smoothScrollTo(0, Math.max(0, allHeader.getTop() - dp(12))));
    }

    // ================================================================ azioni sulle app

    private void launch(AppEntry app) {
        prefs.addRecent(app.key);
        try {
            startActivity(app.launchIntent());
        } catch (RuntimeException e) {
            toast("Impossibile aprire " + app.label);
        }
    }

    private void showAppMenu(final AppEntry app) {
        final List<String> dock = prefs.dock();
        final int idx = dock.indexOf(app.key);
        final Set<String> hidden = prefs.hidden();
        List<GlassMenu.Item> items = GlassMenu.list();

        items.add(new GlassMenu.Item("Apri", () -> launch(app)));
        if (idx < 0) {
            items.add(new GlassMenu.Item("Aggiungi alla barra", () -> {
                dock.add(app.key);
                prefs.setDock(dock);
                focusTag = SEC_DOCK + app.key;
                rebuildAll();
            }));
        } else {
            if (idx > 0) items.add(new GlassMenu.Item("Sposta a sinistra", () -> moveDock(dock, idx, idx - 1, app)));
            if (idx < dock.size() - 1) items.add(new GlassMenu.Item("Sposta a destra", () -> moveDock(dock, idx, idx + 1, app)));
            items.add(new GlassMenu.Item("Rimuovi dalla barra", () -> {
                dock.remove(app.key);
                prefs.setDock(dock);
                if ((SEC_DOCK + app.key).equals(focusTag)) focusTag = null;
                rebuildAll();
            }));
        }
        if (hidden.contains(app.key)) {
            items.add(new GlassMenu.Item("Mostra in «Tutte le app»", () -> {
                hidden.remove(app.key);
                prefs.setHidden(hidden);
                rebuildAll();
            }).divider());
        } else {
            items.add(new GlassMenu.Item("Nascondi da «Tutte le app»", () -> {
                hidden.add(app.key);
                prefs.setHidden(hidden);
                if ((SEC_ALL + app.key).equals(focusTag)) focusTag = null;
                rebuildAll();
            }).divider());
        }
        items.add(new GlassMenu.Item("Informazioni app", () -> startSafe(new Intent(
                Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + app.pkg)))));
        items.add(new GlassMenu.Item("Disinstalla", () -> startSafe(new Intent(
                Intent.ACTION_DELETE, Uri.parse("package:" + app.pkg)))).divider().danger());

        GlassMenu.show(this, copy(app.icon), Ui.hue(app.pkg), app.label, app.pkg, items);
    }

    private void moveDock(List<String> dock, int from, int to, AppEntry app) {
        String k = dock.remove(from);
        dock.add(to, k);
        prefs.setDock(dock);
        focusTag = SEC_DOCK + app.key;
        rebuildAll();
    }

    // ================================================================ pulsante + : aggiungi alla barra

    private void showAddApps() {
        final List<String> dock = prefs.dock();

        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setBackground(Ui.sheet(this, 24));
        panel.setPadding(dp(24), dp(20), dp(24), dp(16));

        LinearLayout head = new LinearLayout(this);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);
        FrameLayout badge = new FrameLayout(this);
        badge.setBackground(Ui.round(this, 10, Ui.ACCENT));
        ImageView plus = new ImageView(this);
        plus.setImageResource(R.drawable.ic_plus);
        badge.addView(plus, new FrameLayout.LayoutParams(dp(18), dp(18), Gravity.CENTER));
        head.addView(badge, new LinearLayout.LayoutParams(dp(30), dp(30)));
        LinearLayout texts = new LinearLayout(this);
        texts.setOrientation(LinearLayout.VERTICAL);
        texts.addView(Ui.text(this, "Aggiungi alla barra", 19, true, Ui.WHITE));
        TextView sub = Ui.text(this, "Premi OK per aggiungere o togliere un'app", 12, false, Ui.TEXT_FAINT);
        sub.setPadding(0, dp(3), 0, 0);
        texts.addView(sub);
        LinearLayout.LayoutParams tlp = wrap();
        tlp.leftMargin = dp(12);
        head.addView(texts, tlp);
        panel.addView(head);

        int cols = 7;
        GridLayout g = new GridLayout(this);
        g.setColumnCount(cols);
        g.setClipChildren(false);
        g.setClipToPadding(false);
        g.setPadding(dp(6), dp(10), dp(6), dp(10));
        final TextView count = Ui.text(this, "", 12, false, Ui.TEXT_FAINT);
        final Runnable refreshCount = () -> count.setText(dock.size() + " app nella barra  ·  Per riordinarle usa «Sposta» nel menu dell'app");
        refreshCount.run();

        View first = null;
        for (final AppEntry a : apps) {
            LinearLayout cell = new LinearLayout(this);
            cell.setOrientation(LinearLayout.VERTICAL);
            cell.setGravity(Gravity.CENTER_HORIZONTAL);
            cell.setClipChildren(false);

            final FrameLayout icon = new FrameLayout(this);
            icon.setBackground(Ui.round(this, 15, Ui.hue(a.pkg)));
            icon.setClipChildren(false);
            if (a.icon != null) {
                ImageView iv = new ImageView(this);
                iv.setImageDrawable(copy(a.icon));
                icon.addView(iv, new FrameLayout.LayoutParams(dp(42), dp(42), Gravity.CENTER));
            }
            // Segno di spunta disegnato (il carattere ✓ può mancare nei font di Fire OS)
            final ImageView check = new ImageView(this);
            check.setImageResource(R.drawable.ic_check);
            check.setPadding(dp(4), dp(4), dp(4), dp(4));
            GradientDrawable cb = Ui.round(this, 999, Ui.ACCENT);
            cb.setStroke(dp(2), 0xFF20232A);
            check.setBackground(cb);
            check.setTranslationX(dp(6));
            check.setTranslationY(-dp(6));
            check.setVisibility(dock.contains(a.key) ? View.VISIBLE : View.INVISIBLE);
            icon.addView(check, new FrameLayout.LayoutParams(dp(22), dp(22), Gravity.TOP | Gravity.END));

            icon.setFocusable(true);
            icon.setClickable(true);
            icon.setContentDescription(a.label);
            final GradientDrawable ring = Ui.ring(this, 15);
            icon.setOnFocusChangeListener((v, has) -> {
                Ui.focusScale(v, has, 1.1f);
                icon.setForeground(has ? ring : null);
            });
            icon.setOnClickListener(v -> {
                if (dock.contains(a.key)) dock.remove(a.key);
                else dock.add(a.key);
                check.setVisibility(dock.contains(a.key) ? View.VISIBLE : View.INVISIBLE);
                refreshCount.run();
            });
            cell.addView(icon, new LinearLayout.LayoutParams(dp(63), dp(63)));
            TextView name = Ui.oneLine(Ui.text(this, a.label, 11, false, Ui.WHITE));
            name.setGravity(Gravity.CENTER);
            LinearLayout.LayoutParams nlp = new LinearLayout.LayoutParams(dp(84), ViewGroup.LayoutParams.WRAP_CONTENT);
            nlp.topMargin = dp(6);
            cell.addView(name, nlp);

            GridLayout.LayoutParams glp = new GridLayout.LayoutParams();
            glp.setMargins(dp(2), dp(8), dp(2), dp(8));
            g.addView(cell, glp);
            if (first == null) first = icon;
        }
        ScrollView sv = new ScrollView(this);
        sv.setVerticalScrollBarEnabled(false);
        sv.setClipToPadding(false);
        sv.addView(g);
        LinearLayout.LayoutParams svlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(300));
        svlp.topMargin = dp(8);
        panel.addView(sv, svlp);

        View line = new View(this);
        line.setBackgroundColor(0x1FFFFFFF);
        panel.addView(line, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Math.max(1, dp(1))));
        LinearLayout foot = new LinearLayout(this);
        foot.setOrientation(LinearLayout.HORIZONTAL);
        foot.setGravity(Gravity.CENTER_VERTICAL);
        foot.setPadding(0, dp(12), 0, 0);
        foot.addView(count, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        TextView done = Ui.pill(this, "Fine", 0, 0x24FFFFFF, true);
        foot.addView(done, wrap());
        panel.addView(foot);

        final Dialog d = GlassMenu.frame(this, panel, dp(720), Gravity.CENTER, 0);
        done.setOnClickListener(v -> d.dismiss());
        d.setOnDismissListener(di -> {
            prefs.setDock(dock);
            focusTag = null;
            rebuildAll();
        });
        d.show();
        if (first != null) first.requestFocus();
    }

    // ================================================================ cerca

    private void showSearch() {
        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setBackground(Ui.sheet(this, 22));
        panel.setPadding(dp(16), dp(16), dp(16), dp(12));

        final EditText field = new EditText(this);
        field.setSingleLine(true);
        field.setHint("Cerca tra le tue app");
        field.setHintTextColor(Ui.TEXT_FAINT);
        field.setTextColor(Ui.WHITE);
        field.setTextSize(18);
        field.setImeOptions(EditorInfo.IME_ACTION_SEARCH);
        GradientDrawable fbg = Ui.round(this, 12, 0x1FFFFFFF);
        fbg.setStroke(Math.max(1, dp(1)), 0x33FFFFFF);
        field.setBackground(fbg);
        field.setPadding(dp(14), dp(10), dp(14), dp(10));
        android.graphics.drawable.Drawable lens = getDrawable(R.drawable.ic_search).mutate();
        lens.setBounds(0, 0, dp(18), dp(18));
        field.setCompoundDrawablesRelative(lens, null, null, null);
        field.setCompoundDrawablePadding(dp(10));
        panel.addView(field);

        final LinearLayout results = new LinearLayout(this);
        results.setOrientation(LinearLayout.VERTICAL);
        results.setPadding(0, dp(8), 0, 0);
        panel.addView(results);

        final Dialog d = GlassMenu.frame(this, panel, dp(600), Gravity.TOP | Gravity.CENTER_HORIZONTAL, dp(64));
        final List<AppEntry> matches = new ArrayList<>();
        final Runnable refresh = () -> {
            results.removeAllViews();
            matches.clear();
            String q = fold(field.getText().toString().trim());
            if (q.isEmpty()) {
                TextView hint = Ui.text(this, "Scrivi il nome di un'app", 13, false, Ui.TEXT_FAINT);
                hint.setPadding(dp(8), dp(10), 0, dp(6));
                results.addView(hint);
                return;
            }
            for (AppEntry a : apps) {
                if (fold(a.label).contains(q) || a.pkg.contains(q)) matches.add(a);
                if (matches.size() >= 8) break;
            }
            if (matches.isEmpty()) {
                TextView none = Ui.text(this, "Nessuna app trovata", 13, false, Ui.TEXT_FAINT);
                none.setPadding(dp(8), dp(10), 0, dp(6));
                results.addView(none);
            }
            for (final AppEntry a : matches) {
                TextView row = GlassMenu.menuRow(this, a.label, false);
                android.graphics.drawable.Drawable ic = copy(a.icon);
                if (ic != null) {
                    ic.setBounds(0, 0, dp(24), dp(24));
                    row.setCompoundDrawablesRelative(ic, null, null, null);
                    row.setCompoundDrawablePadding(dp(12));
                }
                row.setOnClickListener(v -> {
                    d.dismiss();
                    launch(a);
                });
                results.addView(row, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT));
            }
        };
        field.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void afterTextChanged(Editable s) { refresh.run(); }
        });
        field.setOnEditorActionListener((v, actionId, ev) -> {
            if (!matches.isEmpty()) {
                d.dismiss();
                launch(matches.get(0));
                return true;
            }
            return false;
        });
        refresh.run();
        if (d.getWindow() != null) {
            d.getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE
                    | WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING);
        }
        d.show();
        field.requestFocus();
    }

    private static String fold(String s) {
        String n = Normalizer.normalize(s.toLowerCase(Locale.ROOT), Normalizer.Form.NFD);
        return n.replaceAll("\\p{M}+", "");
    }

    // ================================================================ opzioni

    private interface RowAction {
        void run(int rowIndex);
    }

    private void showOptions(final int focusIndex) {
        final boolean svc = isRedirectServiceEnabled();
        final int theme = Math.floorMod(prefs.theme(), WallpaperView.themeCount());
        final int hiddenCount = prefs.hidden().size();
        final int latest = prefs.latestVersion();

        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setBackground(Ui.sheet(this, 24));
        panel.setPadding(dp(22), dp(20), dp(22), dp(20));

        LinearLayout head = new LinearLayout(this);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);
        ImageView logo = new ImageView(this);
        logo.setImageResource(R.drawable.ic_launcher);
        head.addView(logo, new LinearLayout.LayoutParams(dp(26), dp(26)));
        TextView title = Ui.text(this, "Opzioni FireRun", 19, true, Ui.WHITE);
        LinearLayout.LayoutParams tlp = wrap();
        tlp.leftMargin = dp(10);
        head.addView(title, tlp);
        head.addView(new View(this), new LinearLayout.LayoutParams(0, 1, 1f));
        head.addView(Ui.text(this, "v" + BuildConfig.VERSION_NAME, 12, false, Ui.TEXT_FAINT));
        panel.addView(head);

        final Dialog[] holder = new Dialog[1];
        final List<View> rows = new ArrayList<>();

        LinearLayout g1 = group(panel);
        addRow(g1, rows, "Tasto Home → FireRun", svc ? "Attivo" : "Da attivare", svc ? Ui.OK_GREEN : Ui.ACCENT_LIGHT,
                holder, i -> openAccessibilitySettings());
        addRow(g1, rows, "Sostituisci la home Amazon", prefs.redirectEnabled() ? "Sì" : "No", Ui.TEXT_DIM,
                holder, i -> {
                    prefs.setRedirectEnabled(!prefs.redirectEnabled());
                    showOptions(i);
                });
        addRow(g1, rows, "Apri la home Amazon per 60 secondi", "Apri ›", Ui.TEXT_DIM, holder, i -> openAmazonHome());

        LinearLayout g2 = group(panel);
        addRow(g2, rows, "Sfondo", WallpaperView.NAMES[theme], Ui.TEXT_DIM, holder, i -> {
            prefs.setTheme((theme + 1) % WallpaperView.themeCount());
            wallpaper.setTheme(prefs.theme());
            showOptions(i);
        });
        addRow(g2, rows, "App nascoste", hiddenCount + (hiddenCount > 0 ? "  ·  Ripristina" : ""), Ui.TEXT_DIM,
                holder, i -> {
                    prefs.setHidden(new java.util.HashSet<String>());
                    rebuildAll();
                    showOptions(i);
                });

        LinearLayout g3 = group(panel);
        addRow(g3, rows, "Aggiornamenti automatici", prefs.autoUpdate() ? "Sì" : "No", Ui.TEXT_DIM,
                holder, i -> {
                    prefs.setAutoUpdate(!prefs.autoUpdate());
                    showOptions(i);
                });
        String upd = latest > BuildConfig.VERSION_CODE ? "Versione " + latest + " disponibile" : "Controlla ›";
        addRow(g3, rows, "Aggiorna FireRun", upd, latest > BuildConfig.VERSION_CODE ? Ui.ACCENT_LIGHT : Ui.TEXT_DIM,
                holder, i -> checkUpdate(true));
        addRow(g3, rows, "Diagnostica finestre Amazon", "›", Ui.TEXT_DIM, holder, i -> showDiagnostics());
        addRow(g3, rows, "Launcher predefinito (Android TV)", "›", Ui.TEXT_DIM, holder,
                i -> startSafe(new Intent(Settings.ACTION_HOME_SETTINGS)));

        holder[0] = GlassMenu.frame(this, panel, dp(600), Gravity.CENTER, 0);
        holder[0].show();
        rows.get(Math.max(0, Math.min(focusIndex, rows.size() - 1))).requestFocus();
    }

    private LinearLayout group(LinearLayout panel) {
        LinearLayout g = new LinearLayout(this);
        g.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable bg = Ui.round(this, 14, 0x12FFFFFF);
        bg.setStroke(Math.max(1, dp(1)), 0x14FFFFFF);
        g.setBackground(bg);
        g.setPadding(dp(4), dp(4), dp(4), dp(4));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(14);
        panel.addView(g, lp);
        return g;
    }

    private void addRow(LinearLayout group, final List<View> rows, String label, String value, int valueColor,
                        final Dialog[] holder, final RowAction action) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(14), dp(11), dp(14), dp(11));
        row.setMinimumHeight(dp(40));
        row.setFocusable(true);
        row.setClickable(true);
        row.setBackground(Ui.rowBackground(this, 0x2EFFFFFF, true));
        row.addView(Ui.text(this, label, 14, false, Ui.WHITE),
                new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        row.addView(Ui.text(this, value, 13, true, valueColor));
        final int index = rows.size();
        row.setOnClickListener(v -> {
            if (holder[0] != null) holder[0].dismiss();
            action.run(index);
        });
        rows.add(row);
        group.addView(row, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
    }

    private boolean isRedirectServiceEnabled() {
        String enabled = Settings.Secure.getString(getContentResolver(),
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
        if (enabled == null) return false;
        ComponentName me = new ComponentName(this, HomeRedirectService.class);
        for (String s : enabled.split(":")) {
            if (me.equals(ComponentName.unflattenFromString(s))) return true;
        }
        return false;
    }

    private void openAccessibilitySettings() {
        try {
            Intent i = new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS);
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            prefs.pauseRedirectForSystemScreen();
            startActivity(i);
            toast("Attiva «FireRun – Tasto Home» nell'elenco");
        } catch (RuntimeException e) {
            GlassMenu.message(this, "Accessibilità non raggiungibile",
                    "Questa versione di Fire OS non apre il menu. Attivalo dal PC con lo script deploy.ps1 "
                            + "(opzione -AttivaTastoHome) oppure con ADB:\n\n"
                            + "adb shell settings put secure enabled_accessibility_services "
                            + getPackageName() + "/.HomeRedirectService\n"
                            + "adb shell settings put secure accessibility_enabled 1");
        }
    }

    private void openAmazonHome() {
        prefs.setBypassUntil(System.currentTimeMillis() + 60_000);
        Intent i = getPackageManager().getLaunchIntentForPackage(HomeRedirectService.AMAZON_LAUNCHER);
        if (i == null) {
            i = new Intent(Intent.ACTION_MAIN)
                    .addCategory(Intent.CATEGORY_HOME)
                    .setPackage(HomeRedirectService.AMAZON_LAUNCHER);
        }
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            startActivity(i);
        } catch (RuntimeException e) {
            toast("Home Amazon non trovata (forse è disattivata)");
        }
    }

    private void showDiagnostics() {
        List<String> w = prefs.recentWindows();
        GlassMenu.message(this, "Ultime finestre Amazon", w.isEmpty()
                ? "Nessuna finestra registrata. Attiva il servizio Tasto Home e premi Home."
                : TextUtils.join("\n", w));
    }

    // ================================================================ aggiornamenti

    private void checkUpdate(final boolean manual) {
        long now = System.currentTimeMillis();
        if (!manual && now - prefs.lastUpdateCheck() < UPDATE_EVERY_MS) {
            applyUpdateUi(prefs.latestVersion());
            maybeAutoInstall(prefs.latestVersion());
            return;
        }
        if (manual) toast("Controllo aggiornamenti…");
        Updater.fetchLatest(net, ui, latest -> {
            if (isDestroyed()) return;
            prefs.setLastUpdateCheck(System.currentTimeMillis());
            if (latest > 0) prefs.setLatestVersion(latest);
            applyUpdateUi(latest);
            if (!manual) {
                maybeAutoInstall(latest);
                return;
            }
            if (latest < 0) toast("Controllo non riuscito: verifica la connessione");
            else if (latest > BuildConfig.VERSION_CODE) Updater.downloadAndInstall(this, net, ui);
            else toast("FireRun è aggiornato (v" + BuildConfig.VERSION_NAME + ")");
        });
    }

    /**
     * Aggiornamento automatico: se c'è una versione nuova la scarica e apre subito
     * la richiesta di installazione (Android 11 richiede sempre una conferma).
     * Se la richiesta viene annullata, non la ripropone per 24 ore.
     */
    private void maybeAutoInstall(int latest) {
        if (latest <= BuildConfig.VERSION_CODE || !prefs.autoUpdate() || !visible) return;
        if (android.os.Build.VERSION.SDK_INT >= 26 && !getPackageManager().canRequestPackageInstalls()) {
            return; // senza permesso resta il pulsante «Aggiornamento», che spiega cosa attivare
        }
        long now = System.currentTimeMillis();
        if (prefs.promptedVersion() == latest && now - prefs.promptedAt() < REPROMPT_MS) return;
        prefs.setPrompted(latest, now);
        Updater.downloadAndInstall(this, net, ui);
    }

    private void applyUpdateUi(int latest) {
        updatePill.setVisibility(latest > BuildConfig.VERSION_CODE ? View.VISIBLE : View.GONE);
    }

    // ================================================================ varie

    private void openSystemSettings() {
        startSafe(new Intent(Settings.ACTION_SETTINGS));
    }

    /**
     * Apre una schermata di sistema (Impostazioni, Info app, Disinstalla…).
     * Su Fire OS 8 molte di queste vivono nel pacchetto della home Amazon:
     * il Tasto Home le scambierebbe per la home e le chiuderebbe subito,
     * quindi il reindirizzamento resta sospeso finché non si torna in FireRun.
     */
    private void startSafe(Intent i) {
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            prefs.pauseRedirectForSystemScreen();
            startActivity(i);
        } catch (RuntimeException e) {
            prefs.clearPauses();
            toast("Funzione non disponibile su questo dispositivo");
        }
    }

    private void updateClock() {
        if (clock == null) return;
        Date now = new Date();
        clock.setText(android.text.format.DateFormat.getTimeFormat(this).format(now));
        String d = new SimpleDateFormat("EEEE d MMMM", Locale.getDefault()).format(now);
        dateView.setText(d.isEmpty() ? d : Character.toUpperCase(d.charAt(0)) + d.substring(1));
    }

    /**
     * Una Drawable può stare in una sola vista alla volta: la stessa icona usata
     * nella barra, nei Recenti e nella griglia va duplicata, altrimenti sparisce
     * o si deforma.
     */
    private android.graphics.drawable.Drawable copy(android.graphics.drawable.Drawable d) {
        if (d == null) return null;
        android.graphics.drawable.Drawable.ConstantState cs = d.getConstantState();
        return cs != null ? cs.newDrawable(getResources()).mutate() : d;
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_LONG).show();
    }
}
