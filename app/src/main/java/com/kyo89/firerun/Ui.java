package com.kyo89.firerun;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.TextView;

/** Colori e mattoncini grafici condivisi (stile vetro "Sequoia"). */
final class Ui {
    private Ui() {}

    static final int WHITE = 0xFFFFFFFF;
    static final int INK = 0xFF0B0F17;
    static final int ACCENT = 0xFFC2410C;        // arancio FireRun (testo bianco leggibile)
    static final int ACCENT_LIGHT = 0xFFFFB37A;  // arancio chiaro per etichette
    static final int TEXT_DIM = 0xD1FFFFFF;      // 82 % bianco
    static final int TEXT_FAINT = 0xB3FFFFFF;    // 70 % bianco
    static final int OK_GREEN = 0xFF86E0A8;
    static final int DANGER = 0xFFFF8A80;

    static final Typeface BOLD = Typeface.create("sans-serif", Typeface.BOLD);
    static final Typeface MEDIUM = Typeface.create("sans-serif-medium", Typeface.NORMAL);
    static final Typeface REGULAR = Typeface.create("sans-serif", Typeface.NORMAL);

    static int dp(Context c, float v) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v,
                c.getResources().getDisplayMetrics()));
    }

    static GradientDrawable round(Context c, float radiusDp, int fill) {
        GradientDrawable g = new GradientDrawable();
        g.setCornerRadius(dp(c, radiusDp));
        g.setColor(fill);
        return g;
    }

    /** Pannello in vetro chiaro (barra di navigazione, dock). */
    static GradientDrawable glass(Context c, float radiusDp) {
        GradientDrawable g = round(c, radiusDp, 0x1AFFFFFF);
        g.setStroke(Math.max(1, dp(c, 1)), 0x29FFFFFF);
        return g;
    }

    /** Pannello in vetro scuro (menu e finestre). Fire OS non sfoca, quindi è più opaco. */
    static GradientDrawable sheet(Context c, float radiusDp) {
        GradientDrawable g = round(c, radiusDp, 0xEB20232A);
        g.setStroke(Math.max(1, dp(c, 1)), 0x29FFFFFF);
        return g;
    }

    /** Bordo bianco di selezione. */
    static GradientDrawable ring(Context c, float radiusDp) {
        GradientDrawable g = round(c, radiusDp, Color.TRANSPARENT);
        g.setStroke(dp(c, 3), WHITE);
        return g;
    }

    static int hue(String seed) {
        float h = Math.floorMod(seed.hashCode(), 360);
        return Color.HSVToColor(new float[]{h, 0.62f, 0.46f});
    }

    static TextView text(Context c, CharSequence s, float sp, boolean bold, int color) {
        TextView t = new TextView(c);
        t.setText(s);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(color);
        t.setTypeface(bold ? BOLD : REGULAR);
        // Lasciamo lo spazio interno del font: senza, accenti e lettere come g/p si tagliano.
        return t;
    }

    static TextView oneLine(TextView t) {
        t.setSingleLine(true);
        t.setEllipsize(TextUtils.TruncateAt.END);
        return t;
    }

    /**
     * Pulsante a capsula: a riposo trasparente/vetro (o del colore indicato),
     * selezionato bianco con testo scuro, come i pulsanti di Fire TV.
     */
    static TextView pill(Context c, String label, int iconRes, int normalFill, boolean stroke) {
        final TextView t = text(c, label, 13, true, WHITE);
        t.setTypeface(MEDIUM);
        t.setGravity(Gravity.CENTER);
        t.setPadding(dp(c, 14), dp(c, 8), dp(c, 14), dp(c, 8));
        t.setMinHeight(dp(c, 34));
        t.setFocusable(true);
        t.setClickable(true);

        GradientDrawable normal = round(c, 999, normalFill);
        if (stroke) normal.setStroke(Math.max(1, dp(c, 1)), 0x38FFFFFF);
        GradientDrawable focused = round(c, 999, WHITE);
        StateListDrawable bg = new StateListDrawable();
        bg.addState(new int[]{android.R.attr.state_focused}, focused);
        bg.addState(new int[]{android.R.attr.state_pressed}, focused);
        bg.addState(new int[]{}, normal);
        t.setBackground(bg);

        ColorStateList colors = new ColorStateList(
                new int[][]{{android.R.attr.state_focused}, {android.R.attr.state_pressed}, {}},
                new int[]{INK, INK, WHITE});
        t.setTextColor(colors);
        if (iconRes != 0) {
            Drawable d = c.getDrawable(iconRes).mutate();
            int s = dp(c, 16);
            d.setBounds(0, 0, s, s);
            t.setCompoundDrawablesRelative(d, null, null, null);
            t.setCompoundDrawablePadding(dp(c, 6));
            t.setCompoundDrawableTintList(colors);
        }
        t.setOnFocusChangeListener(new View.OnFocusChangeListener() {
            @Override
            public void onFocusChange(View v, boolean has) {
                float s = has ? 1.06f : 1f;
                v.animate().scaleX(s).scaleY(s).setDuration(120).start();
            }
        });
        return t;
    }

    /** Riga selezionabile dentro menu e finestre. */
    static StateListDrawable rowBackground(Context c, int focusedFill, boolean withRing) {
        GradientDrawable f = round(c, 10, focusedFill);
        if (withRing) f.setStroke(dp(c, 2), WHITE);
        StateListDrawable bg = new StateListDrawable();
        bg.addState(new int[]{android.R.attr.state_focused}, f);
        bg.addState(new int[]{android.R.attr.state_pressed}, f);
        bg.addState(new int[]{}, round(c, 10, Color.TRANSPARENT));
        return bg;
    }

    static void focusScale(View v, boolean has, float scale) {
        float s = has ? scale : 1f;
        v.animate().scaleX(s).scaleY(s).setDuration(140).start();
        v.setElevation(has ? 12 * v.getResources().getDisplayMetrics().density : 0);
    }
}
