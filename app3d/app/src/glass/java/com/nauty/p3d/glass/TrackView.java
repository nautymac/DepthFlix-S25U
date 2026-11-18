package com.nauty.p3d.glass;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.View;

import java.util.Locale;

/**
 * Debug overlay for eye tracking: a small mirror-view camera image with the
 * detected face and eye point in the top-right corner, the numbers under it,
 * and a cross on the screen where the eyes are (straight in front of the viewer
 * when the mapping is right). Not clickable, so touches fall through.
 */
final class TrackView extends View {
    private volatile EyeTracker.State state;
    private volatile boolean tracking = true;
    private float pixelMm = 0.05f;

    private final Paint img = new Paint(Paint.FILTER_BITMAP_FLAG);
    private final Paint box = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint dot = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint back = new Paint();
    private final float density;

    TrackView(Context context) {
        super(context);
        density = context.getResources().getDisplayMetrics().density;
        box.setStyle(Paint.Style.STROKE);
        box.setStrokeWidth(2 * density);
        box.setColor(0xFF33DD55);
        dot.setColor(0xFFFF3344);
        dot.setStrokeWidth(2 * density);
        text.setColor(Color.WHITE);
        text.setTextSize(12 * density);
        text.setShadowLayer(3 * density, 0, 0, Color.BLACK);
        back.setColor(0xCC000000);
    }

    void setState(EyeTracker.State s) {
        state = s;
        postInvalidate();
    }

    void setTracking(boolean on) {
        tracking = on;
        postInvalidate();
    }

    void setPixelMm(float mm) {
        pixelMm = mm;
    }

    @Override
    protected void onDraw(Canvas c) {
        EyeTracker.State s = state;
        float m = 12 * density;
        float tw = 150 * density;
        float th = tw * 0.75f;
        if (s != null && s.thumb != null) {
            th = tw * s.thumb.getHeight() / s.thumb.getWidth();
        }
        RectF dst = new RectF(getWidth() - m - tw, m, getWidth() - m, m + th);
        c.drawRect(dst.left - 4, dst.top - 4, dst.right + 4, dst.bottom + 4 + 70 * density, back);

        if (s != null && s.thumb != null) {
            c.drawBitmap(s.thumb, null, dst, img);
        }
        if (s != null && s.face != null) {
            c.drawRect(dst.left + s.face.left * dst.width(), dst.top + s.face.top * dst.height(),
                    dst.left + s.face.right * dst.width(), dst.top + s.face.bottom * dst.height(), box);
        }
        if (s != null && s.eye != null) {
            c.drawCircle(dst.left + s.eye.x * dst.width(), dst.top + s.eye.y * dst.height(), 4 * density, dot);
        }

        float y = dst.bottom + 16 * density;
        float x = dst.left;
        String l1;
        String l2;
        String l3;
        if (s == null) {
            l1 = "카메라 시작 중…";
            l2 = "";
            l3 = "";
        } else if (s.error != null) {
            l1 = "추적 오류";
            l2 = s.error;
            l3 = "";
        } else {
            l1 = String.format(Locale.US, "추적 %s · %s · %.0f fps",
                    tracking ? "켬" : "끔",
                    s.faceMode == 3 ? "홍채 추적" : s.faceMode == 2 ? "눈 인식" : "얼굴 상자", s.fps);
            l2 = s.found ? "얼굴 인식됨" : (s.valid ? "얼굴 없음 (마지막 위치 유지)" : "얼굴 없음");
            l3 = s.valid ? String.format(Locale.US, "X %+.0f  Y %+.0f  거리 %.0f mm", s.xMm, s.yMm, s.zMm) : "";
        }
        c.drawText(l1, x, y, text);
        c.drawText(l2, x, y + 18 * density, text);
        c.drawText(l3, x, y + 36 * density, text);

        // Where the eyes are, projected straight onto the screen.
        if (s != null && s.valid) {
            float cx = getWidth() / 2f + s.xMm / pixelMm;
            float cy = getHeight() / 2f - s.yMm / pixelMm;
            float r = 18 * density;
            c.drawLine(cx - r, cy, cx + r, cy, dot);
            c.drawLine(cx, cy - r, cx, cy + r, dot);
        }
    }
}
