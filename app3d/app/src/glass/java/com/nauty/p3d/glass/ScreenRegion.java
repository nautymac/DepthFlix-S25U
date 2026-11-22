package com.nauty.p3d.glass;

import java.nio.ByteBuffer;

/**
 * Screen 3D: where the side-by-side picture is in the captured screen, and where each eye's
 * picture goes on the panel.
 *
 * The panel is wider than most videos (19.5:9), so a 16:9 side-by-side video played full
 * screen has black bars beside it. Halving the whole screen would halve the bars too and
 * put the two eyes' pictures in different places; the picture has to be found first.
 * It is taken to be centred, which is how players show it.
 */
final class ScreenRegion {
    static final int SRC_AUTO = 0;
    static final int SRC_16_9 = 1;
    static final int SRC_FULL = 2;
    static final int SRC_COUNT = 3;

    static final int FIT = 0;
    static final int FILL = 1;
    static final int STRETCH = 2;
    static final int FIT_COUNT = 3;

    /** Picture shapes players produce; a detected size close to one of these is taken as it. */
    private static final float[] ASPECTS = {4f / 3f, 16f / 9f, 1.85f, 2f, 2.35f, 2.39f, 32f / 9f};
    /** A side-by-side picture wider than this holds two full-width eyes (full SBS, 32:9). */
    private static final float FULL_SBS_ASPECT = 3.0f;
    /** A bar is no brighter than this anywhere (0..255) ... */
    private static final int DARK = 60;
    /** ... and varies no more than this along its length. */
    private static final int FLAT = 12;
    // How many looks in a row (two a second) must agree before the picture's size changes.
    private static final int FIRST_STREAK = 2;
    private static final int GROW_STREAK = 6;
    private static final int SHRINK_KNOWN_STREAK = 2;
    private static final int SHRINK_STREAK = 6;

    /** The picture found in the capture, in capture pixels; 0 = not found yet. */
    volatile int autoW;
    volatile int autoH;

    /** What the last look found, for the log. */
    volatile String last = "nothing yet";

    private int candW;
    private int candH;
    private int streak;
    private int[] colLo = new int[0];
    private int[] colHi = new int[0];
    private int[] rowLo = new int[0];
    private int[] rowHi = new int[0];
    private final int[] size = new int[2];   // compute() scratch (GL thread)

    void reset() {
        autoW = 0;
        autoH = 0;
        candW = 0;
        candH = 0;
        streak = 0;
    }

    /** The largest centred w x h of shape {@code aspect} inside capW x capH. */
    private static void inside(float aspect, int capW, int capH, int[] out) {
        if (aspect <= (float) capW / capH) {
            out[0] = Math.min(capW, Math.round(capH * aspect));
            out[1] = capH;
        } else {
            out[0] = capW;
            out[1] = Math.min(capH, Math.round(capW / aspect));
        }
    }

    /**
     * One small copy of the capture (cols x rows, RGBA). Finds the centred rectangle that is
     * not black and, once it has been the same a few times in a row, takes it.
     *
     * @return true when the picture found has changed
     */
    boolean detect(ByteBuffer rgba, int cols, int rows, int capW, int capH) {
        if (colLo.length != cols) {
            colLo = new int[cols];
            colHi = new int[cols];
        }
        if (rowLo.length != rows) {
            rowLo = new int[rows];
            rowHi = new int[rows];
        }
        java.util.Arrays.fill(colLo, 255);
        java.util.Arrays.fill(colHi, 0);
        java.util.Arrays.fill(rowLo, 255);
        java.util.Arrays.fill(rowHi, 0);
        for (int y = 0; y < rows; y++) {
            int at = y * cols * 4;
            for (int x = 0; x < cols; x++, at += 4) {
                int v = Math.max(rgba.get(at) & 0xff,
                        Math.max(rgba.get(at + 1) & 0xff, rgba.get(at + 2) & 0xff));
                if (v < colLo[x]) colLo[x] = v;
                if (v > colHi[x]) colHi[x] = v;
                if (v < rowLo[y]) rowLo[y] = v;
                if (v > rowHi[y]) rowHi[y] = v;
            }
        }
        // A bar is one dark, flat colour from end to end. It is not always black: YouTube's
        // bars beside a 16:9 video measured 25..28 of 255 (2026-11-22), which a plain
        // "brighter than black" test took for picture. Anything brighter, or uneven along
        // its length, is picture.
        int left = -1;
        int right = -1;
        for (int x = 0; x < cols; x++) {
            if (colHi[x] > DARK || colHi[x] - colLo[x] > FLAT) {
                if (left < 0) left = x;
                right = x;
            }
        }
        int top = -1;
        int bottom = -1;
        for (int y = 0; y < rows; y++) {
            if (rowHi[y] > DARK || rowHi[y] - rowLo[y] > FLAT) {
                if (top < 0) top = y;
                bottom = y;
            }
        }
        if (left < 0 || top < 0 || right - left + 1 < cols * 0.3f || bottom - top + 1 < rows * 0.3f) {
            return false;   // a dark scene: says nothing about the bars
        }
        // Centred picture: the smaller margin of each pair is the bar (the other side may
        // just be a dark part of the picture).
        int marginX = Math.min(left, cols - 1 - right);
        int marginY = Math.min(top, rows - 1 - bottom);
        float wd = capW * (cols - 2f * marginX) / cols;
        float hd = capH * (rows - 2f * marginY) / rows;

        // Snap to a known shape so that the two halves are exact.
        int[] c = new int[2];
        int bestW = capW;
        int bestH = capH;
        float bestErr = Math.max(Math.abs(capW - wd), Math.abs(capH - hd));
        for (float a : ASPECTS) {
            inside(a, capW, capH, c);
            float err = Math.max(Math.abs(c[0] - wd), Math.abs(c[1] - hd));
            if (err < bestErr) {
                bestErr = err;
                bestW = c[0];
                bestH = c[1];
            }
        }
        float tolerance = 2.5f * Math.max((float) capW / cols, (float) capH / rows);
        int w;
        int h;
        if (bestErr <= tolerance) {
            w = bestW;
            h = bestH;
        } else {
            w = Math.round(wd / 2f) * 2;
            h = Math.round(hd / 2f) * 2;
        }

        last = "picture columns " + left + ".." + right + " of " + cols + ", rows " + top + ".." + bottom
                + " of " + rows + " -> " + Math.round(wd) + "x" + Math.round(hd) + " -> " + w + "x" + h;

        if (w == candW && h == candH) {
            streak++;
        } else {
            candW = w;
            candH = h;
            streak = 1;
        }
        if (w == autoW && h == autoH) return false;
        // Things outside the picture are mostly passing (a player's buttons, a banner), so
        // growing waits a few seconds; shrinking to a shape players really produce is
        // taken quickly, to any other size slowly (it may be a dark stretch of the film).
        int need;
        if (autoW == 0) need = FIRST_STREAK;
        else if (w >= autoW && h >= autoH) need = GROW_STREAK;
        else need = bestErr <= tolerance ? SHRINK_KNOWN_STREAK : SHRINK_STREAK;
        if (streak >= need) {
            autoW = w;
            autoH = h;
            return true;
        }
        return false;
    }

    /** The picture's size in the capture for a source choice (pixels). */
    void source(int srcMode, int capW, int capH, int[] out) {
        if (srcMode == SRC_16_9) {
            inside(16f / 9f, capW, capH, out);
        } else if (srcMode == SRC_AUTO && autoW > 0 && autoW <= capW && autoH <= capH) {
            out[0] = autoW;
            out[1] = autoH;
        } else {
            out[0] = capW;
            out[1] = capH;
        }
    }

    /**
     * @param forced the shape (width / height) each eye's picture is shown in; 0 = its own
     * @param mono   the picture is 2D (AI 3D), not side-by-side
     * @param src    out: x, y, width, height of the SBS picture, as fractions of the capture
     * @param dst    out: x, y, width, height of one eye's picture, as fractions of the panel
     *               (larger than 1 when it is cropped to fill)
     */
    void compute(int srcMode, int fitMode, float forced, boolean mono, int capW, int capH,
                 int panelW, int panelH, float[] src, float[] dst) {
        int[] s = size;
        source(srcMode, capW, capH, s);
        src[2] = (float) s[0] / capW;
        src[3] = (float) s[1] / capH;
        src[0] = (1f - src[2]) / 2f;
        src[1] = (1f - src[3]) / 2f;

        float shape = (float) s[0] / s[1];
        // Full SBS: each eye is half as wide. (mono: a 2D picture for AI 3D, never halved.)
        if (!mono && shape >= FULL_SBS_ASPECT) shape /= 2f;
        if (forced > 0f) shape = forced;
        float panel = (float) panelW / panelH;
        float w = 1f;
        float h = 1f;
        if (fitMode == FIT) {
            if (shape < panel) w = shape / panel;
            else h = panel / shape;
        } else if (fitMode == FILL) {
            if (shape < panel) h = panel / shape;
            else w = shape / panel;
        }
        dst[2] = w;
        dst[3] = h;
        dst[0] = (1f - w) / 2f;
        dst[1] = (1f - h) / 2f;
    }
}
