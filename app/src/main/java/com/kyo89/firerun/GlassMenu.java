package com.kyo89.firerun;

import android.app.Activity;
import android.app.Dialog;
import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.drawable.Drawable;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;

/** Menu contestuali e finestre in vetro, in stile macOS. */
final class GlassMenu {
    private GlassMenu() {}

    static final class Item {
        final String label;
        final Runnable action;
        boolean danger;
        boolean dividerBefore;
        int icon;

        Item(String label, Runnable action) {
            this.label = label;
            this.action = action;
        }

        Item danger() {
            danger = true;
            return this;
        }

        Item divider() {
            dividerBefore = true;
            return this;
        }

        Item icon(int res) {
            icon = res;
            return this;
        }
    }

    /** Finestra a schermo intero con sfondo scurito; il pannello va aggiunto dal chiamante. */
    static Dialog frame(Activity a, View panel, int widthPx, int gravity, int topMarginPx) {
        final Dialog d = new Dialog(a, android.R.style.Theme_Translucent_NoTitleBar_Fullscreen);
        FrameLayout dim = new FrameLayout(a);
        dim.setBackgroundColor(0x8C03060C);
        dim.setOnClickListener(v -> d.dismiss());
        // Cliccabili ma non selezionabili col telecomando: altrimenti il focus può
        // finire sullo sfondo invisibile e OK chiude il menu per sbaglio.
        dim.setFocusable(false);
        panel.setClickable(true);
        panel.setFocusable(false);
        panel.setElevation(Ui.dp(a, 24));
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(widthPx,
                ViewGroup.LayoutParams.WRAP_CONTENT, gravity);
        lp.topMargin = topMarginPx;
        dim.addView(panel, lp);
        d.setContentView(dim);
        return d;
    }

    /** Menu contestuale di un'app. */
    static Dialog show(Activity a, Drawable icon, int iconBg, String title, String subtitle, List<Item> items) {
        LinearLayout panel = new LinearLayout(a);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setBackground(Ui.sheet(a, 20));
        int p = Ui.dp(a, 6);
        panel.setPadding(p, p, p, p);

        LinearLayout head = new LinearLayout(a);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);
        head.setPadding(Ui.dp(a, 8), Ui.dp(a, 8), Ui.dp(a, 8), Ui.dp(a, 10));
        FrameLayout box = new FrameLayout(a);
        box.setBackground(Ui.round(a, 10, iconBg));
        if (icon != null) {
            ImageView iv = new ImageView(a);
            iv.setImageDrawable(icon);
            box.addView(iv, new FrameLayout.LayoutParams(Ui.dp(a, 24), Ui.dp(a, 24), Gravity.CENTER));
        }
        head.addView(box, new LinearLayout.LayoutParams(Ui.dp(a, 34), Ui.dp(a, 34)));
        LinearLayout texts = new LinearLayout(a);
        texts.setOrientation(LinearLayout.VERTICAL);
        texts.addView(Ui.oneLine(Ui.text(a, title, 14, true, Ui.WHITE)));
        if (subtitle != null) {
            TextView sub = Ui.oneLine(Ui.text(a, subtitle, 11, false, Ui.TEXT_FAINT));
            sub.setPadding(0, Ui.dp(a, 3), 0, 0);
            texts.addView(sub);
        }
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        tlp.leftMargin = Ui.dp(a, 10);
        head.addView(texts, tlp);
        panel.addView(head);

        final Dialog d = frame(a, panel, Ui.dp(a, 290), Gravity.CENTER, 0);
        View first = null;
        for (final Item it : items) {
            if (it.dividerBefore) panel.addView(divider(a));
            TextView row = menuRow(a, it.label, it.danger);
            if (it.icon != 0) setRowIcon(a, row, it.icon, it.danger);
            row.setOnClickListener(v -> {
                d.dismiss();
                it.action.run();
            });
            panel.addView(row, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT));
            if (first == null) first = row;
        }
        d.show();
        if (first != null) first.requestFocus();
        return d;
    }

    /** Finestra con un testo lungo e un pulsante OK. */
    static Dialog message(Activity a, String title, String body) {
        LinearLayout panel = new LinearLayout(a);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setBackground(Ui.sheet(a, 20));
        int p = Ui.dp(a, 20);
        panel.setPadding(p, p, p, Ui.dp(a, 16));
        panel.addView(Ui.text(a, title, 18, true, Ui.WHITE));
        ScrollView sv = new ScrollView(a);
        TextView tv = Ui.text(a, body, 13, false, Ui.TEXT_DIM);
        tv.setLineSpacing(Ui.dp(a, 4), 1f);
        sv.addView(tv);
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(a, 260));
        slp.topMargin = Ui.dp(a, 12);
        panel.addView(sv, slp);
        final TextView ok = Ui.pill(a, "OK", 0, 0x24FFFFFF, true);
        LinearLayout.LayoutParams olp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        olp.gravity = Gravity.END;
        olp.topMargin = Ui.dp(a, 12);
        panel.addView(ok, olp);
        final Dialog d = frame(a, panel, Ui.dp(a, 560), Gravity.CENTER, 0);
        ok.setOnClickListener(v -> d.dismiss());
        d.show();
        ok.requestFocus();
        return d;
    }

    static TextView menuRow(Context c, String label, boolean danger) {
        TextView row = Ui.text(c, label, 13, false, Ui.WHITE);
        row.setTypeface(Ui.MEDIUM);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(Ui.dp(c, 10), Ui.dp(c, 9), Ui.dp(c, 10), Ui.dp(c, 9));
        row.setMinHeight(Ui.dp(c, 34));
        row.setFocusable(true);
        row.setClickable(true);
        row.setBackground(Ui.rowBackground(c, Ui.ACCENT, false));
        row.setTextColor(new ColorStateList(
                new int[][]{{android.R.attr.state_focused}, {}},
                new int[]{Ui.WHITE, danger ? Ui.DANGER : Ui.WHITE}));
        return row;
    }

    /** Icona a sinistra della voce, dello stesso colore del testo (bianca quando selezionata). */
    static void setRowIcon(Context c, TextView row, int res, boolean danger) {
        android.graphics.drawable.Drawable d = c.getDrawable(res);
        if (d == null) return;
        d = d.mutate();
        int s = Ui.dp(c, 17);
        d.setBounds(0, 0, s, s);
        row.setCompoundDrawablesRelative(d, null, null, null);
        row.setCompoundDrawablePadding(Ui.dp(c, 11));
        row.setCompoundDrawableTintList(new ColorStateList(
                new int[][]{{android.R.attr.state_focused}, {}},
                new int[]{Ui.WHITE, danger ? Ui.DANGER : 0xD9FFFFFF}));
    }

    static View divider(Context c) {
        View v = new View(c);
        v.setBackgroundColor(0x24FFFFFF);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Math.max(1, Ui.dp(c, 1)));
        lp.setMargins(Ui.dp(c, 10), Ui.dp(c, 4), Ui.dp(c, 10), Ui.dp(c, 4));
        v.setLayoutParams(lp);
        return v;
    }

    static List<Item> list() {
        return new ArrayList<>();
    }
}
