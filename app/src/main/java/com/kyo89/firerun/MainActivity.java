package com.kyo89.firerun;

import android.app.Activity;
import android.app.Dialog;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.res.ColorStateList;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
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
import android.view.ViewTreeObserver;
import android.view.WindowManager;
import android.view.inputmethod.EditorInfo;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.GridLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.text.Normalizer;
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
 * Home di FireRun in stile Apple TV: in alto il "Top Shelf" dell'app selezionata
 * (nome grande, sfondo del suo colore), sotto la griglia a 5 colonne con prima i
 * preferiti (e il riquadro Aggiungi), poi Tutte le app. Nessuna pubblicità.
 */
public class MainActivity extends Activity {

    private static final String SEC_FAV = "fav:";
    private static final String SEC_ALL = "all:";
    private static final long UPDATE_EVERY_MS = 2L * 60 * 60 * 1000;   // controllo ogni 2 ore
    private static final long REPROMPT_MS = 24L * 60 * 60 * 1000;      // se annulli, riprova dopo 24 h
    private static final int DEFAULT_FAVS = 5;
    private static final int COLUMNS = 5;
    private static final float FOCUS_SCALE = 1.12f;

    private final Handler ui = new Handler(Looper.getMainLooper());
    private final ExecutorService io = Executors.newSingleThreadExecutor();   // lettura app
    private final ExecutorService net = Executors.newSingleThreadExecutor();  // rete (aggiornamenti)
    private final Map<String, AppEntry> tagApps = new HashMap<>();
    private final Map<String, Integer> colorCache = new HashMap<>();

    private Prefs prefs;
    private List<AppEntry> apps = new ArrayList<>();
    private Map<String, AppEntry> byKey = new HashMap<>();

    private ShelfView shelf;
    private ScrollView scroll;
    private LinearLayout col;
    private GridLayout favGrid, allGrid;
    private TextView allHeader, heroOver, heroTitle, heroMeta, clock;
    private TextView updatePill;
    private View firstFavView, firstAllView;

    private AppEntry heroApp;
    private String focusTag;
    private String lastSignature;   // per non ricostruire la griglia se non è cambiato nulla
    private int loadGen, tileW, tileH, gap, margin, heroH, screenH;
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

    // ================================================================ misure

    private int dp(float v) {
        return Ui.dp(this, v);
    }

    /**
     * Proporzioni prese da Apple TV su 1280×720 (margini 93, spazi 36, riquadri 5:3),
     * riportate sullo schermo reale: 5 colonne che entrano esattamente.
     */
    private void computeSizes() {
        DisplayMetrics dm = getResources().getDisplayMetrics();
        int w = Math.max(dm.widthPixels, dm.heightPixels);
        screenH = Math.min(dm.widthPixels, dm.heightPixels);
        margin = Math.round(w * 0.072f);
        gap = Math.round(w * 0.028f);
        tileW = (w - 2 * margin - (COLUMNS - 1) * gap) / COLUMNS;
        tileH = tileW * 3 / 5;
    }

    // ================================================================ struttura

    private void buildShell() {
        FrameLayout root = new FrameLayout(this);
        shelf = new ShelfView(this);
        shelf.setTheme(prefs.theme());
        root.addView(shelf, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        // Lo scorrimento lo decidiamo noi (ensureVisible): quello automatico di Android
        // lascia le icone ingrandite a filo del bordo e non riporta su il Top Shelf.
        scroll = new ScrollView(this) {
            @Override
            protected int computeScrollDeltaToGetChildRectOnScreen(Rect rect) {
                return 0;
            }
        };
        scroll.setVerticalScrollBarEnabled(false);
        scroll.setClipToPadding(false);
        scroll.setFillViewport(true);
        scroll.setOverScrollMode(View.OVER_SCROLL_NEVER);

        // Le griglie usano mezzo spazio per lato in ogni cella: il bordo esterno
        // risulta esattamente "margin".
        col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setClipChildren(false);
        col.setClipToPadding(false);
        col.setPadding(margin - gap / 2, dp(18), margin - gap / 2, dp(64));
        scroll.addView(col, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        root.addView(scroll, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        col.addView(buildHeader(), insetParams(ViewGroup.LayoutParams.WRAP_CONTENT));

        // La prima riga di app parte a circa il 60 % dello schermo, come su Apple TV.
        heroH = Math.max(dp(150), Math.round(screenH * 0.60f) - dp(18) - dp(42) - dp(16));
        col.addView(buildHero(), insetParams(heroH));

        favGrid = newGrid();
        LinearLayout.LayoutParams flp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        flp.topMargin = dp(16);
        col.addView(favGrid, flp);

        allHeader = Ui.text(this, "Tutte le app", 20, true, Ui.WHITE);
        LinearLayout.LayoutParams hlp = insetParams(ViewGroup.LayoutParams.WRAP_CONTENT);
        hlp.topMargin = dp(18);
        hlp.bottomMargin = dp(14);
        col.addView(allHeader, hlp);

        allGrid = newGrid();
        col.addView(allGrid);

        scroll.getViewTreeObserver().addOnScrollChangedListener(() ->
                shelf.setReveal(1f - scroll.getScrollY() / (float) Math.max(1, heroH)));

        setContentView(root);
        updateClock();
    }

    /** Parametri per gli elementi allineati al margine (non alle celle della griglia). */
    private LinearLayout.LayoutParams insetParams(int height) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, height);
        lp.leftMargin = gap / 2;
        lp.rightMargin = gap / 2;
        return lp;
    }

    private GridLayout newGrid() {
        GridLayout g = new GridLayout(this);
        g.setColumnCount(COLUMNS);
        g.setUseDefaultMargins(false);
        g.setClipChildren(false);
        g.setClipToPadding(false);
        return g;
    }

    private View buildHeader() {
        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL | Gravity.END);
        bar.setMinimumHeight(dp(42));
        bar.setClipChildren(false);

        updatePill = Ui.pill(this, "Aggiornamento", R.drawable.ic_download, Ui.ACCENT, false);
        updatePill.setVisibility(View.GONE);
        updatePill.setOnClickListener(v -> Updater.downloadAndInstall(this, net, ui));
        LinearLayout.LayoutParams ulp = wrap();
        ulp.rightMargin = dp(14);
        bar.addView(updatePill, ulp);

        bar.addView(roundButton(R.drawable.ic_search, "Cerca", 0x24FFFFFF, v -> showSearch()), roundParams());
        View settings = roundButton(R.drawable.ic_settings, "Impostazioni del Fire Stick", 0x24FFFFFF,
                v -> openSystemSettings());
        settings.setOnLongClickListener(v -> {
            showOptions(0);
            return true;
        });
        bar.addView(settings, roundParams());
        bar.addView(roundButton(R.drawable.ic_flame, "Opzioni FireRun", Ui.ACCENT, v -> showOptions(0)), roundParams());

        clock = Ui.text(this, "", 20, true, Ui.WHITE);
        LinearLayout.LayoutParams clp = wrap();
        clp.leftMargin = dp(10);
        bar.addView(clock, clp);
        return bar;
    }

    private LinearLayout.LayoutParams roundParams() {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(38), dp(38));
        lp.rightMargin = dp(10);
        return lp;
    }

    /** Pulsante rotondo in alto a destra: a riposo vetro (o arancio), selezionato bianco. */
    private View roundButton(int icon, String description, int normalFill, View.OnClickListener click) {
        FrameLayout b = new FrameLayout(this);
        GradientDrawable normal = new GradientDrawable();
        normal.setShape(GradientDrawable.OVAL);
        normal.setColor(normalFill);
        GradientDrawable focused = new GradientDrawable();
        focused.setShape(GradientDrawable.OVAL);
        focused.setColor(Ui.WHITE);
        StateListDrawable bg = new StateListDrawable();
        bg.addState(new int[]{android.R.attr.state_focused}, focused);
        bg.addState(new int[]{android.R.attr.state_pressed}, focused);
        bg.addState(new int[]{}, normal);
        b.setBackground(bg);
        b.setFocusable(true);
        b.setClickable(true);
        b.setContentDescription(description);
        b.setOnClickListener(click);

        ImageView iv = new ImageView(this);
        iv.setImageResource(icon);
        iv.setDuplicateParentStateEnabled(true);
        iv.setImageTintList(new ColorStateList(
                new int[][]{{android.R.attr.state_focused}, {android.R.attr.state_pressed}, {}},
                new int[]{Ui.INK, Ui.INK, Ui.WHITE}));
        b.addView(iv, new FrameLayout.LayoutParams(dp(18), dp(18), Gravity.CENTER));

        b.setOnFocusChangeListener((v, has) -> {
            float s = has ? 1.12f : 1f;
            v.animate().scaleX(s).scaleY(s).setDuration(120).start();
            if (has) scroll.smoothScrollTo(0, 0);
        });
        return b;
    }

    private View buildHero() {
        LinearLayout hero = new LinearLayout(this);
        hero.setOrientation(LinearLayout.VERTICAL);
        hero.setGravity(Gravity.BOTTOM);
        hero.setPadding(0, 0, 0, dp(6));

        heroOver = Ui.text(this, "", 11, true, 0xB8FFFFFF);
        heroOver.setLetterSpacing(0.16f);
        heroTitle = Ui.oneLine(Ui.text(this, "FireRun", 46, true, Ui.WHITE));
        heroTitle.setLetterSpacing(-0.03f);
        heroTitle.setPadding(0, dp(6), 0, dp(6));
        heroMeta = Ui.oneLine(Ui.text(this, "Caricamento app…", 14, false, 0xC7FFFFFF));

        hero.addView(heroOver);
        hero.addView(heroTitle);
        hero.addView(heroMeta);
        return hero;
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
                    if (signature(list).equals(lastSignature)) {
                        // Nulla di cambiato: niente ricostruzione (niente sfarfallio), solo il Top Shelf aggiornato.
                        if (heroApp != null) showHero(heroApp, focusTag != null && focusTag.startsWith(SEC_ALL) ? SEC_ALL : SEC_FAV);
                        return;
                    }
                    apps = list;
                    rebuildAll();
                });
            });
        } catch (RuntimeException ignored) {
        }
    }

    /** Impronta di ciò che si vede in griglia: app installate (e nomi), preferiti, app nascoste. */
    private String signature(List<AppEntry> list) {
        StringBuilder b = new StringBuilder();
        for (AppEntry a : list) b.append(a.key).append('=').append(a.label).append('|');
        b.append("#").append(prefs.dock()).append("#").append(new java.util.TreeSet<>(prefs.hidden()));
        return b.toString();
    }

    private void rebuildAll() {
        lastSignature = signature(apps);
        tagApps.clear();
        colorCache.clear();
        byKey = new HashMap<>();
        for (AppEntry a : apps) byKey.put(a.key, a);
        firstFavView = null;
        firstAllView = null;

        // Primo avvio: alcune app TV come preferite.
        if (!prefs.hasDock() && !apps.isEmpty()) {
            List<String> start = new ArrayList<>();
            for (AppEntry a : apps) if (a.leanback && start.size() < DEFAULT_FAVS) start.add(a.key);
            for (AppEntry a : apps) if (!start.contains(a.key) && start.size() < DEFAULT_FAVS) start.add(a.key);
            prefs.setDock(start);
        }

        // --- preferiti + Aggiungi ---
        favGrid.removeAllViews();
        List<String> keys = prefs.dock();
        List<String> cleaned = new ArrayList<>();
        int i = 0;
        for (String k : keys) {
            AppEntry a = byKey.get(k);
            if (a == null) continue;
            cleaned.add(k);
            View cell = appCell(a, SEC_FAV, i < COLUMNS);
            if (firstFavView == null) firstFavView = cell.findViewWithTag(SEC_FAV + a.key);
            favGrid.addView(cell, cellParams());
            i++;
        }
        if (cleaned.size() != keys.size() && !apps.isEmpty()) prefs.setDock(cleaned);
        View add = addCell(i < COLUMNS);
        favGrid.addView(add, cellParams());
        if (firstFavView == null) firstFavView = add.findViewWithTag("act:add");

        // --- tutte le app ---
        allGrid.removeAllViews();
        Set<String> hidden = prefs.hidden();
        int n = 0;
        for (AppEntry a : apps) {
            if (hidden.contains(a.key)) continue;
            View cell = appCell(a, SEC_ALL, false);
            if (firstAllView == null) firstAllView = cell.findViewWithTag(SEC_ALL + a.key);
            allGrid.addView(cell, cellParams());
            n++;
        }
        allHeader.setText("Tutte le app (" + n + ")");

        // Dopo ogni ricarica le AppEntry sono nuove: aggancia il Top Shelf a quella aggiornata.
        if (heroApp != null) heroApp = byKey.get(heroApp.key);
        if (heroApp == null) {
            List<String> d = prefs.dock();
            heroApp = !d.isEmpty() ? byKey.get(d.get(0)) : (apps.isEmpty() ? null : apps.get(0));
        }
        if (heroApp != null) showHero(heroApp, SEC_FAV);

        // La selezione va ripristinata DOPO il layout: prima le posizioni sono tutte 0
        // e lo scorrimento riporterebbe in cima anche se l'app era in fondo alla griglia.
        col.getViewTreeObserver().addOnGlobalLayoutListener(new ViewTreeObserver.OnGlobalLayoutListener() {
            @Override
            public void onGlobalLayout() {
                col.getViewTreeObserver().removeOnGlobalLayoutListener(this);
                restoreFocus();
            }
        });
        col.requestLayout();
    }

    /** Ogni cella occupa un riquadro più mezzo spazio per lato: 5 colonne = larghezza esatta. */
    private GridLayout.LayoutParams cellParams() {
        GridLayout.LayoutParams lp = new GridLayout.LayoutParams();
        lp.width = tileW;
        lp.height = ViewGroup.LayoutParams.WRAP_CONTENT;
        lp.setMargins(gap / 2, 0, gap / 2, dp(14));
        return lp;
    }

    // ================================================================ riquadri

    /** Riquadro di un'app: banner TV (o icona e nome) e, sotto, il nome visibile solo se selezionato. */
    private View appCell(final AppEntry app, final String section, final boolean firstRow) {
        LinearLayout cell = new LinearLayout(this);
        cell.setOrientation(LinearLayout.VERTICAL);
        cell.setGravity(Gravity.CENTER_HORIZONTAL);
        cell.setClipChildren(false);

        FrameLayout tile = new FrameLayout(this);
        tile.setBackground(Ui.round(this, 12, Ui.hue(app.pkg)));
        tile.setOutlineProvider(ViewOutlineProvider.BACKGROUND);
        tile.setClipToOutline(true);
        tile.setElevation(dp(2));

        if (app.banner != null) {
            ImageView iv = new ImageView(this);
            iv.setScaleType(ImageView.ScaleType.CENTER_CROP);
            iv.setImageDrawable(copy(app.banner));
            tile.addView(iv, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        } else {
            LinearLayout inner = new LinearLayout(this);
            inner.setOrientation(LinearLayout.VERTICAL);
            inner.setGravity(Gravity.CENTER);
            inner.setPadding(dp(8), dp(6), dp(8), dp(6));
            if (app.icon != null) {
                ImageView iv = new ImageView(this);
                iv.setImageDrawable(copy(app.icon));
                iv.setScaleType(ImageView.ScaleType.FIT_CENTER);
                int s = Math.min(dp(44), tileH / 2);
                inner.addView(iv, new LinearLayout.LayoutParams(s, s));
            }
            TextView name = Ui.text(this, app.label, 13, true, Ui.WHITE);
            name.setGravity(Gravity.CENTER);
            name.setMaxLines(2);
            name.setEllipsize(TextUtils.TruncateAt.END);
            LinearLayout.LayoutParams nlp = wrap();
            nlp.topMargin = dp(4);
            inner.addView(name, nlp);
            tile.addView(inner, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        }
        cell.addView(tile, new LinearLayout.LayoutParams(tileW, tileH));

        TextView label = Ui.oneLine(Ui.text(this, app.label, 13, true, Ui.WHITE));
        label.setGravity(Gravity.CENTER);
        label.setAlpha(0f);
        cell.addView(label, labelParams());

        bindTile(tile, label, section + app.key, firstRow, app, section, v -> launch(app));
        tile.setLongClickable(true);
        tile.setOnLongClickListener(v -> {
            showAppMenu(app);
            return true;
        });
        return cell;
    }

    /** Riquadro tratteggiato «Aggiungi» in fondo ai preferiti. */
    private View addCell(boolean firstRow) {
        LinearLayout cell = new LinearLayout(this);
        cell.setOrientation(LinearLayout.VERTICAL);
        cell.setGravity(Gravity.CENTER_HORIZONTAL);
        cell.setClipChildren(false);

        LinearLayout tile = new LinearLayout(this);
        tile.setOrientation(LinearLayout.VERTICAL);
        tile.setGravity(Gravity.CENTER);
        GradientDrawable bg = Ui.round(this, 12, 0x0FFFFFFF);
        bg.setStroke(dp(2), 0x73FFFFFF, dp(7), dp(5));
        tile.setBackground(bg);
        ImageView plus = new ImageView(this);
        plus.setImageResource(R.drawable.ic_plus);
        tile.addView(plus, new LinearLayout.LayoutParams(dp(26), dp(26)));
        TextView t = Ui.text(this, "Aggiungi", 13, true, 0xD9FFFFFF);
        LinearLayout.LayoutParams tlp = wrap();
        tlp.topMargin = dp(6);
        tile.addView(t, tlp);
        tile.setContentDescription("Aggiungi ai preferiti");
        cell.addView(tile, new LinearLayout.LayoutParams(tileW, tileH));

        TextView label = Ui.oneLine(Ui.text(this, "Aggiungi ai preferiti", 13, true, Ui.WHITE));
        label.setGravity(Gravity.CENTER);
        label.setAlpha(0f);
        cell.addView(label, labelParams());

        bindTile(tile, label, "act:add", firstRow, null, null, v -> showAddApps());
        return cell;
    }

    /** L'etichetta può essere più larga del riquadro (occupa anche gli spazi) senza allargare la colonna. */
    private LinearLayout.LayoutParams labelParams() {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(tileW + gap, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMargins(-gap / 2, dp(14), -gap / 2, 0);
        return lp;
    }

    /**
     * Comportamento della selezione, come su Apple TV: il riquadro si solleva
     * (più grande, ombra, riflesso in alto), compare il nome sotto, il Top Shelf
     * mostra l'app e la pagina scorre per tenerlo ben visibile.
     */
    private void bindTile(final ViewGroup tile, final TextView label, final String tag, final boolean firstRow,
                          final AppEntry app, final String section, View.OnClickListener click) {
        tile.setTag(tag);
        if (app != null) {
            tagApps.put(tag, app);
            tile.setContentDescription(app.label);
        }
        tile.setFocusable(true);
        tile.setClickable(true);
        tile.setOnClickListener(click);
        final Drawable sheen = sheen();
        tile.setOnFocusChangeListener((v, has) -> {
            float s = has ? FOCUS_SCALE : 1f;
            v.animate().scaleX(s).scaleY(s).translationZ(has ? dp(16) : 0).setDuration(160).start();
            v.setForeground(has ? sheen : null);
            label.animate().alpha(has ? 1f : 0f).setDuration(160).start();
            if (!has) return;
            focusTag = tag;
            if (app != null) showHero(app, section);
            if (firstRow) scroll.smoothScrollTo(0, 0);
            else ensureVisible(v);
        });
    }

    /** Riflesso chiaro in alto + bordo sottile: l'effetto "vetro" dei riquadri selezionati. */
    private Drawable sheen() {
        GradientDrawable g = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{0x40FFFFFF, 0x0DFFFFFF, 0x00FFFFFF});
        g.setCornerRadius(dp(12));
        g.setStroke(Math.max(1, dp(1)), 0x66FFFFFF);
        return g;
    }

    /** Scorre la pagina quanto basta perché il riquadro (ingrandito) e il suo nome restino ben dentro lo schermo. */
    private void ensureVisible(View v) {
        int top = 0;
        View p = v;
        while (p != null && p != col) {
            top += p.getTop();
            p = p.getParent() instanceof View ? (View) p.getParent() : null;
        }
        int bottom = top + v.getHeight() + dp(36);   // spazio per il nome sotto
        int y = scroll.getScrollY();
        int h = scroll.getHeight();
        int pad = dp(40);
        if (bottom + pad > y + h) scroll.smoothScrollTo(0, bottom + pad - h);
        else if (top - pad < y) scroll.smoothScrollTo(0, Math.max(0, top - pad));
    }

    private void showHero(AppEntry app, String section) {
        heroApp = app;
        heroOver.setText(SEC_FAV.equals(section) ? "PREFERITO" : "APP");
        heroTitle.setText(app.label);
        long ts = prefs.lastLaunch(app.key);
        String meta = "Premi OK per aprire  ·  ≡ per le opzioni";
        if (ts > 0) {
            long now = System.currentTimeMillis();
            CharSequence when = now - ts < DateUtils.MINUTE_IN_MILLIS ? "adesso"
                    : DateUtils.getRelativeTimeSpanString(ts, now, DateUtils.MINUTE_IN_MILLIS);
            meta = "Aperta " + when + "   ·   " + meta;
        }
        heroMeta.setText(meta);
        shelf.setAppColor(appColor(app));
    }

    /** Colore medio del banner (o dell'icona) per tingere il Top Shelf, come fa Apple TV. */
    private int appColor(AppEntry a) {
        Integer cached = colorCache.get(a.key);
        if (cached != null) return cached;
        int color = Ui.hue(a.pkg);
        Drawable d = copy(a.banner != null ? a.banner : a.icon);
        if (d != null) {
            try {
                Bitmap b = Bitmap.createBitmap(16, 9, Bitmap.Config.ARGB_8888);
                d.setBounds(0, 0, 16, 9);
                d.draw(new Canvas(b));
                long r = 0, g = 0, bl = 0, n = 0;
                for (int y = 0; y < 9; y++) {
                    for (int x = 0; x < 16; x++) {
                        int c = b.getPixel(x, y);
                        if (Color.alpha(c) < 128) continue;
                        r += Color.red(c);
                        g += Color.green(c);
                        bl += Color.blue(c);
                        n++;
                    }
                }
                b.recycle();
                if (n > 0) {
                    float[] hsv = new float[3];
                    Color.RGBToHSV((int) (r / n), (int) (g / n), (int) (bl / n), hsv);
                    hsv[1] = Math.max(hsv[1], 0.45f);                   // un po' di colore anche dai banner grigi
                    hsv[2] = Math.min(Math.max(hsv[2], 0.35f), 0.62f);  // né nero né abbagliante
                    color = Color.HSVToColor(hsv);
                }
            } catch (RuntimeException ignored) {
            }
        }
        colorCache.put(a.key, color);
        return color;
    }

    private void restoreFocus() {
        View target = focusTag != null ? col.findViewWithTag(focusTag) : null;
        if (target == null) target = firstFavView != null ? firstFavView : firstAllView;
        if (target != null) target.requestFocus();
    }

    private void goTop() {
        focusTag = null;
        if (scroll != null) scroll.smoothScrollTo(0, 0);
        if (firstFavView != null) firstFavView.requestFocus();
    }

    private void scrollToAll() {
        if (firstAllView != null) firstAllView.requestFocus();
        ui.post(() -> scroll.smoothScrollTo(0, Math.max(0, allHeader.getTop() - dp(24))));
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
            items.add(new GlassMenu.Item("Aggiungi ai preferiti", () -> {
                dock.add(app.key);
                prefs.setDock(dock);
                focusTag = SEC_FAV + app.key;
                rebuildAll();
            }));
        } else {
            if (idx > 0) items.add(new GlassMenu.Item("Sposta a sinistra", () -> moveDock(dock, idx, idx - 1, app)));
            if (idx < dock.size() - 1) items.add(new GlassMenu.Item("Sposta a destra", () -> moveDock(dock, idx, idx + 1, app)));
            items.add(new GlassMenu.Item("Rimuovi dai preferiti", () -> {
                dock.remove(app.key);
                prefs.setDock(dock);
                if ((SEC_FAV + app.key).equals(focusTag)) focusTag = null;
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
        focusTag = SEC_FAV + app.key;
        rebuildAll();
    }

    // ================================================================ Aggiungi: scegli i preferiti

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
        texts.addView(Ui.text(this, "Aggiungi ai preferiti", 19, true, Ui.WHITE));
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
        final Runnable refreshCount = () -> count.setText(dock.size() + " app nei preferiti  ·  Per riordinarle usa «Sposta» nel menu dell'app");
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
        final int theme = Math.floorMod(prefs.theme(), ShelfView.themeCount());
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
        addRow(g2, rows, "Sfondo", ShelfView.NAMES[theme], Ui.TEXT_DIM, holder, i -> {
            prefs.setTheme((theme + 1) % ShelfView.themeCount());
            shelf.setTheme(prefs.theme());
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
    }

    /**
     * Una Drawable può stare in una sola vista alla volta: la stessa icona usata
     * nei preferiti, nella griglia e nei menu va duplicata, altrimenti sparisce
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
