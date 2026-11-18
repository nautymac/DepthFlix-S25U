package com.nauty.p3d.glass;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Shader;

/**
 * A built-in full side-by-side test photo, so the 3D can be checked without
 * hunting for an SBS file first.
 *
 * Three depth layers: a grid behind the screen, a yellow disc on the screen
 * plane, and a red "3D" box in front of it. Disparity is split evenly between
 * the eyes; positive pushes a layer behind the screen.
 */
final class SampleImage {
    private static final int EYE_W = 1600;
    private static final int EYE_H = 900;

    static Bitmap create() {
        Bitmap bmp = Bitmap.createBitmap(EYE_W * 2, EYE_H, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(bmp);
        for (int eye = 0; eye < 2; eye++) {
            c.save();
            c.clipRect(eye * EYE_W, 0, eye * EYE_W + EYE_W, EYE_H);
            c.translate(eye * EYE_W, 0);
            drawScene(c, EYE_W, EYE_H, eye == 0 ? -0.5f : 0.5f);
            c.restore();
        }
        return bmp;
    }

    private static void drawScene(Canvas c, int w, int h, float side) {
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);

        p.setShader(new LinearGradient(0, 0, 0, h, 0xFF1B2A49, 0xFF4A6FA5, Shader.TileMode.CLAMP));
        c.drawRect(0, 0, w, h, p);
        p.setShader(null);

        // Background grid, behind the screen.
        float back = 20 * side;
        p.setColor(0x66FFFFFF);
        p.setStrokeWidth(4);
        for (int x = -200; x < w + 200; x += 100) {
            c.drawLine(x + back, 0, x + back, h, p);
        }
        for (int y = 50; y < h; y += 100) {
            c.drawLine(back - 200, y, w + back + 200, y, p);
        }
        p.setColor(Color.WHITE);
        p.setTextSize(70);
        p.setFakeBoldText(true);
        p.setTextAlign(Paint.Align.CENTER);
        c.drawText("BACK", w * 0.18f + back, h * 0.16f, p);

        // Disc on the screen plane: identical in both eyes.
        p.setColor(0xFFF2B134);
        c.drawCircle(w * 0.30f, h * 0.58f, 180, p);
        p.setColor(0xFF3A2A00);
        p.setTextSize(64);
        c.drawText("SCREEN", w * 0.30f, h * 0.58f + 22, p);

        // Box in front of the screen.
        float front = -36 * side;
        RectF r = new RectF(w * 0.55f + front, h * 0.26f, w * 0.82f + front, h * 0.74f);
        p.setColor(0xFFE84855);
        c.drawRoundRect(r, 36, 36, p);
        p.setColor(Color.WHITE);
        p.setTextSize(170);
        c.drawText("3D", r.centerX(), r.centerY() + 60, p);
    }

    private SampleImage() {
    }
}
