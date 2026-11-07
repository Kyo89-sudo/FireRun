package com.kyo89.firerun;

import android.animation.ArgbEvaluator;
import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RadialGradient;
import android.graphics.Shader;
import android.view.View;

/**
 * Sfondo in stile Apple TV: la metà alta ("Top Shelf") si tinge del colore
 * dell'app selezionata con una transizione morbida, sfuma nel nero verso la
 * griglia e si dissolve quando si scorre verso il basso.
 */
final class ShelfView extends View {
    static final String[] NAMES = {"Colore dell'app", "Sequoia", "Notte", "Prugna", "Grafite", "Immagine dal PC"};
    static final int THEME_IMAGE = 5;
    private static final int[] FIXED = {0, 0xFF0E7A72, 0xFF2A4A9A, 0xFF6A2D7E, 0xFF4A4D55, 0};
    private static final int BASE = 0xFF0A0C10;

    private int theme;
    private int color = 0xFF2A4A9A;
    private float reveal = 1f;
    private ValueAnimator anim;

    private final Paint glow = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint wave = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint line = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint fade = new Paint();
    private final Path wavePath = new Path(), linePath = new Path();
    private final Paint imagePaint = new Paint(Paint.FILTER_BITMAP_FLAG);
    private final Paint imageShade = new Paint();
    private final android.graphics.Rect src = new android.graphics.Rect();
    private final android.graphics.Rect dst = new android.graphics.Rect();
    private android.graphics.Bitmap image;

    ShelfView(Context c) {
        super(c);
        wave.setColor(Color.WHITE);
        line.setColor(Color.WHITE);
        line.setStyle(Paint.Style.STROKE);
    }

    static int themeCount() {
        return NAMES.length;
    }

    void setTheme(int t) {
        theme = Math.floorMod(t, NAMES.length);
        if (theme > 0 && theme != THEME_IMAGE) animateTo(FIXED[theme]);
        invalidate();
    }

    /** Immagine scelta dal PC (deploy.ps1 -Sfondo); null per toglierla. */
    void setImage(android.graphics.Bitmap b) {
        image = b;
        invalidate();
    }

    /** Colore dell'app selezionata: usato solo con il tema «Colore dell'app». */
    void setAppColor(int c) {
        if (theme == 0) animateTo(c);
    }

    /** 1 = Top Shelf pieno, 0 = scomparso (pagina scorsa verso il basso). */
    void setReveal(float r) {
        float v = Math.max(0f, Math.min(1f, r));
        if (Math.abs(v - reveal) < 0.01f) return;
        reveal = v;
        invalidate();
    }

    private void animateTo(int target) {
        if (anim != null) anim.cancel();
        if (getWidth() == 0) {
            color = target;
            invalidate();
            return;
        }
        anim = ValueAnimator.ofObject(new ArgbEvaluator(), color, target);
        anim.setDuration(350);
        anim.addUpdateListener(a -> {
            color = (Integer) a.getAnimatedValue();
            invalidate();
        });
        anim.start();
    }

    @Override
    protected void onSizeChanged(int w, int h, int ow, int oh) {
        super.onSizeChanged(w, h, ow, oh);
        float sx = w / 1280f, sy = h / 720f;
        wavePath.reset();
        wavePath.moveTo(0, 300 * sy);
        wavePath.cubicTo(260 * sx, 220 * sy, 520 * sx, 360 * sy, 800 * sx, 280 * sy);
        wavePath.cubicTo(1080 * sx, 200 * sy, 1160 * sx, 170 * sy, 1280 * sx, 220 * sy);
        wavePath.lineTo(1280 * sx, 470 * sy);
        wavePath.lineTo(0, 470 * sy);
        wavePath.close();
        linePath.reset();
        linePath.moveTo(0, 360 * sy);
        linePath.cubicTo(300 * sx, 300 * sy, 600 * sx, 420 * sy, 900 * sx, 340 * sy);
        linePath.cubicTo(1200 * sx, 260 * sy, 1200 * sx, 280 * sy, 1280 * sx, 310 * sy);
        line.setStrokeWidth(Math.max(1f, 2 * sx));
        fade.setShader(new LinearGradient(0, 0.40f * h, 0, 0.66f * h,
                BASE & 0x00FFFFFF, BASE, Shader.TileMode.CLAMP));
    }

    @Override
    protected void onDraw(Canvas canvas) {
        int w = getWidth(), h = getHeight();
        canvas.drawColor(BASE);
        if (w == 0) return;

        if (theme == THEME_IMAGE && image != null) {
            // Immagine a tutto schermo ("riempi e ritaglia"), un velo scuro per leggere i
            // testi e la solita sfumatura verso la griglia. Scorrendo resta più tenue.
            int iw = image.getWidth(), ih = image.getHeight();
            float scale = Math.max(w / (float) iw, h / (float) ih);
            int cw = Math.round(w / scale), ch = Math.round(h / scale);
            src.set((iw - cw) / 2, (ih - ch) / 2, (iw - cw) / 2 + cw, (ih - ch) / 2 + ch);
            dst.set(0, 0, w, h);
            imagePaint.setAlpha(Math.round(255 * (0.40f + 0.60f * reveal)));
            canvas.drawBitmap(image, src, dst, imagePaint);
            imageShade.setColor(0x47000000);
            canvas.drawRect(0, 0, w, h, imageShade);
            canvas.drawRect(0, 0, w, h, fade);
            return;
        }
        if (reveal <= 0f) return;

        int rgb = color & 0x00FFFFFF;
        float[] hsv = new float[3];
        Color.colorToHSV(color, hsv);
        hsv[2] *= 0.45f;
        int dark = Color.HSVToColor(hsv) & 0x00FFFFFF;
        glow.setShader(new RadialGradient(0.78f * w, 0.08f * h, 1.15f * w,
                new int[]{rgb | alpha(0.90f), dark | alpha(0.75f), BASE & 0x00FFFFFF},
                new float[]{0f, 0.45f, 1f}, Shader.TileMode.CLAMP));
        glow.setAlpha(Math.round(255 * reveal));
        canvas.drawRect(0, 0, w, h, glow);

        wave.setAlpha(Math.round(10 * reveal));
        canvas.drawPath(wavePath, wave);
        line.setAlpha(Math.round(26 * reveal));
        canvas.drawPath(linePath, line);

        canvas.drawRect(0, 0, w, h, fade);
    }

    private static int alpha(float a) {
        return Math.round(a * 255) << 24;
    }
}
