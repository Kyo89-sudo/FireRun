package com.kyo89.firerun;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RadialGradient;
import android.graphics.Shader;
import android.view.View;

/** Sfondo a onde in stile macOS Sequoia, disegnato a codice (niente immagini da scaricare). */
final class WallpaperView extends View {
    static final String[] NAMES = {"Sequoia", "Notte", "Prugna", "Grafite"};

    // base, onda1 da, onda1 a, onda2 da, onda2 a, bagliore
    private static final int[][] COLORS = {
            {0xFF06202A, 0xFF0E6B6B, 0xFF123E73, 0xFF0B4F4A, 0xFF1F7A6A, 0xFFFF8A3D},
            {0xFF070B16, 0xFF1B2A55, 0xFF0B1430, 0xFF14204A, 0xFF2A3E7A, 0xFF5B8CFF},
            {0xFF120818, 0xFF4A1E5E, 0xFF22103A, 0xFF38164A, 0xFF6A2D6E, 0xFFFF7AB8},
            {0xFF0C0C0E, 0xFF2E3036, 0xFF18191C, 0xFF222327, 0xFF3A3C42, 0xFFFFB37A}
    };

    private int theme;
    private final Path wave1 = new Path(), wave2 = new Path(), line = new Path();
    private final Paint p1 = new Paint(Paint.ANTI_ALIAS_FLAG), p2 = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint pLine = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint pGlow = new Paint(), pScrim = new Paint();

    WallpaperView(Context c) {
        super(c);
        pLine.setStyle(Paint.Style.STROKE);
        pLine.setColor(0x1FFFFFFF);
    }

    static int themeCount() {
        return COLORS.length;
    }

    void setTheme(int t) {
        theme = Math.floorMod(t, COLORS.length);
        rebuild(getWidth(), getHeight());
        invalidate();
    }

    @Override
    protected void onSizeChanged(int w, int h, int ow, int oh) {
        super.onSizeChanged(w, h, ow, oh);
        rebuild(w, h);
    }

    private void rebuild(int w, int h) {
        if (w <= 0 || h <= 0) return;
        int[] c = COLORS[theme];
        float sx = w / 1280f, sy = h / 720f;

        wave1.reset();
        wave1.moveTo(0, 250 * sy);
        wave1.cubicTo(260 * sx, 120 * sy, 480 * sx, 380 * sy, 760 * sx, 260 * sy);
        wave1.cubicTo(1040 * sx, 140 * sy, 1160 * sx, 80 * sy, 1280 * sx, 170 * sy);
        wave1.lineTo(1280 * sx, 0);
        wave1.lineTo(0, 0);
        wave1.close();
        p1.setShader(new LinearGradient(0, 0, w, 380 * sy, c[1], c[2], Shader.TileMode.CLAMP));
        p1.setAlpha(230);

        wave2.reset();
        wave2.moveTo(0, 470 * sy);
        wave2.cubicTo(220 * sx, 330 * sy, 520 * sx, 520 * sy, 820 * sx, 380 * sy);
        wave2.cubicTo(1120 * sx, 240 * sy, 1180 * sx, 250 * sy, 1280 * sx, 330 * sy);
        wave2.lineTo(1280 * sx, h);
        wave2.lineTo(0, h);
        wave2.close();
        p2.setShader(new LinearGradient(0, h, w, 250 * sy, c[3], c[4], Shader.TileMode.CLAMP));
        p2.setAlpha(217);

        line.reset();
        line.moveTo(0, 360 * sy);
        line.cubicTo(300 * sx, 250 * sy, 560 * sx, 470 * sy, 900 * sx, 330 * sy);
        line.cubicTo(1240 * sx, 190 * sy, 1200 * sx, 230 * sy, 1280 * sx, 260 * sy);
        pLine.setStrokeWidth(Math.max(1f, 2 * sx));

        int glow = c[5] & 0x00FFFFFF;
        pGlow.setShader(new RadialGradient(0.82f * w, 0.18f * h, 0.5f * w,
                new int[]{glow | 0x8C000000, glow}, null, Shader.TileMode.CLAMP));

        pScrim.setShader(new LinearGradient(0, 0, 0, h,
                new int[]{0x26050A12, 0x59050A12, 0xE0050A12, 0xF7050A12},
                new float[]{0f, 0.45f, 0.75f, 1f}, Shader.TileMode.CLAMP));
    }

    @Override
    protected void onDraw(Canvas canvas) {
        int w = getWidth(), h = getHeight();
        canvas.drawColor(COLORS[theme][0]);
        if (pScrim.getShader() == null) return; // non ancora misurata: solo colore di base
        canvas.drawPath(wave1, p1);
        canvas.drawPath(wave2, p2);
        canvas.drawPath(line, pLine);
        canvas.drawRect(0, 0, w, h, pGlow);
        canvas.drawRect(0, 0, w, h, pScrim);
    }
}
