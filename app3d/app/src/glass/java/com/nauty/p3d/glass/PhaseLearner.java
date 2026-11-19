package com.nauty.p3d.glass;

import android.content.Context;
import android.util.Log;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Learns, on the phone, which lens phase looks right from where the viewer's eyes are.
 *
 * The camera sees the viewer's eyes but not what reaches them through the film, so the only
 * signal of "this looks right" is the viewer's own adjustment: each time they settle on a
 * phase or tracking strength (3D tune bar, or a drag in the 3D Fit tool), the eye position
 * and the setting they chose are recorded. From those samples it learns
 *
 *   1. the two numbers of the lens model -- base phase c and tracking strength G -- with
 *      phase_needed(eye) = c + G * w(eye), w = (x + slant*trackY*y) / (z * pitch)
 *      (the same model the weave uses), by a recency-weighted circular least-squares fit;
 *   2. what that model still gets wrong at particular head positions: a smooth correction
 *      r(x, y, z), a Gaussian-kernel regression over the samples' leftover errors, shrunk to
 *      zero where there is little data.
 *
 * The fit writes c and G into {@link Settings}; the weave adds r(eye) every frame.
 *
 * One instance per process ({@link #get}); samples persist in files/glass_learn.csv, shared
 * by the player and the tool.
 */
final class PhaseLearner {
    private static final String TAG = "PhaseLearner";
    private static final int MAX_SAMPLES = 300;
    /** Recency: a sample this many samples old counts half. */
    private static final float HALF_LIFE = 60f;
    /** Below this spread of w the strength cannot be told apart from the phase: keep it. */
    private static final float MIN_W_SPREAD = 0.05f;
    // Correction kernel: how far one sample's lesson reaches (mm) and how strongly it is
    // pulled back to zero where samples are few.
    private static final float SIGMA_XY = 40f;
    private static final float SIGMA_Z = 80f;
    // Small, so right where the viewer just settled the learned phase stays what they chose
    // (the picture must not shift after they let go).
    private static final float SHRINK = 0.5f;

    private static PhaseLearner instance;

    static synchronized PhaseLearner get(Context ctx) {
        if (instance == null) {
            instance = new PhaseLearner(new File(ctx.getApplicationContext().getFilesDir(), "glass_learn.csv"));
        }
        return instance;
    }

    /** One adjustment: where the eyes were and the effective phase + strength chosen there. */
    private static final class Sample {
        final float x, y, z, phase, gap, slant, trackY, pitch;

        Sample(float x, float y, float z, float phase, float gap, float slant, float trackY, float pitch) {
            this.x = x; this.y = y; this.z = z; this.phase = phase; this.gap = gap;
            this.slant = slant; this.trackY = trackY; this.pitch = pitch;
        }

        float w() {
            return (x + slant * trackY * y) / (z * pitch);
        }

        /** The phase the lens needs at w = 0, as this sample says: c + G*w = phase + gap*w. */
        float observed() {
            return phase + gap * w();
        }
    }

    private final File file;
    private final List<Sample> samples = new ArrayList<>();
    // Correction model, swapped whole so the GL thread reads a consistent set.
    private volatile float[][] kernel = new float[0][];

    private PhaseLearner(File file) {
        this.file = file;
        load();
    }

    synchronized int count() {
        return samples.size();
    }

    /**
     * Learned extra phase for an eye at (x, y, z) mm. Zero with no data. Any thread.
     */
    float correction(float x, float y, float z) {
        float[][] k = kernel;
        if (k.length == 0) {
            return 0f;
        }
        double num = 0;
        double den = SHRINK;
        for (float[] s : k) {
            double dx = (x - s[0]) / SIGMA_XY;
            double dy = (y - s[1]) / SIGMA_XY;
            double dz = (z - s[2]) / SIGMA_Z;
            double wgt = s[4] * Math.exp(-0.5 * (dx * dx + dy * dy + dz * dz));
            num += wgt * s[3];
            den += wgt;
        }
        return (float) (num / den);
    }

    /**
     * Records that at eye (x, y, z) the viewer settled on this phase (base phase plus the
     * correction in force there) and strength, then refits and writes the result into
     * {@code settings} (which the caller saves).
     */
    synchronized void add(float x, float y, float z, Settings settings) {
        float effective = settings.phase + correction(x, y, z);
        Sample added = new Sample(x, y, z, effective, settings.gap,
                settings.slant, settings.trackY, settings.pitch);
        // What the weave shows right here now; it must still show this after the refit.
        double target = added.observed();
        samples.add(added);
        while (samples.size() > MAX_SAMPLES) {
            samples.remove(0);
        }
        save();
        fit(settings);
        pin(x, y, z, target, added.w(), settings);
    }

    /**
     * Makes the correction at (x, y, z) exactly what keeps the picture where the viewer left
     * it, by setting the newest sample's own leftover so the kernel sum lands on it. Without
     * this the refit could shift the picture by a few hundredths right after they let go.
     */
    private void pin(float x, float y, float z, double target, double w, Settings settings) {
        float[][] k = kernel;
        if (k.length == 0) {
            return;
        }
        double need = target - settings.phase - settings.gap * w;
        need -= Math.floor(need + 0.5);
        float[] last = k[k.length - 1];
        double den = SHRINK;
        double numOthers = 0;
        for (float[] s : k) {
            double dx = (x - s[0]) / SIGMA_XY;
            double dy = (y - s[1]) / SIGMA_XY;
            double dz = (z - s[2]) / SIGMA_Z;
            double wgt = s[4] * Math.exp(-0.5 * (dx * dx + dy * dy + dz * dz));
            den += wgt;
            if (s != last) numOthers += wgt * s[3];
        }
        last[3] = (float) ((need * den - numOthers) / last[4]);
        kernel = k;
    }

    synchronized void clear() {
        samples.clear();
        kernel = new float[0][];
        //noinspection ResultOfMethodCallIgnored
        file.delete();
    }

    /** Refits c, G and the correction from the samples; writes c and G into settings. */
    synchronized void fit(Settings settings) {
        int n = samples.size();
        if (n == 0) {
            kernel = new float[0][];
            return;
        }
        double[] w = new double[n];
        double[] obs = new double[n];
        double[] wt = new double[n];
        double wMin = Double.MAX_VALUE;
        double wMax = -Double.MAX_VALUE;
        for (int i = 0; i < n; i++) {
            Sample s = samples.get(i);
            w[i] = s.w();
            obs[i] = s.observed();
            wt[i] = Math.pow(0.5, (n - 1 - i) / HALF_LIFE);
            wMin = Math.min(wMin, w[i]);
            wMax = Math.max(wMax, w[i]);
        }
        // Strength only when the samples are spread enough to tell it from the phase;
        // otherwise keep the current one and fit the phase alone.
        // (Two samples gave a strength that swung widely in simulation; four are steady.)
        boolean fitGap = n >= 4 && wMax - wMin >= MIN_W_SPREAD;
        double gLo = fitGap ? Settings.MIN_GAP : settings.gap;
        double gHi = fitGap ? Settings.MAX_GAP : settings.gap;
        double bestG = settings.gap;
        double bestC = settings.phase;
        double bestCost = Double.MAX_VALUE;
        for (double g = gLo; g <= gHi + 1e-9; g += 0.01) {
            double cs = 0;
            double sn = 0;
            for (int i = 0; i < n; i++) {
                double a = 2 * Math.PI * (obs[i] - g * w[i]);
                cs += wt[i] * Math.cos(a);
                sn += wt[i] * Math.sin(a);
            }
            double c = Math.atan2(sn, cs) / (2 * Math.PI);
            double cost = 0;
            for (int i = 0; i < n; i++) {
                cost += wt[i] * (1 - Math.cos(2 * Math.PI * (obs[i] - g * w[i] - c)));
            }
            if (cost < bestCost - 1e-12) {
                bestCost = cost;
                bestG = g;
                bestC = c;
            }
        }
        float c = (float) (bestC % 1.0);
        settings.phase = c < 0 ? c + 1f : c;
        settings.gap = (float) bestG;

        // What the two-number model still misses, per sample, wrapped to (-0.5, 0.5].
        float[][] k = new float[n][];
        if (n >= 2) {
            for (int i = 0; i < n; i++) {
                double r = obs[i] - bestG * w[i] - bestC;
                r -= Math.floor(r + 0.5);
                Sample s = samples.get(i);
                k[i] = new float[]{s.x, s.y, s.z, (float) r, (float) wt[i]};
            }
        } else {
            k = new float[0][];
        }
        kernel = k;
        Log.i(TAG, String.format(Locale.US, "fit %d samples: phase %.3f, strength %.2f%s",
                n, settings.phase, settings.gap, fitGap ? "" : " (strength kept)"));
    }

    private void load() {
        if (!file.isFile()) {
            return;
        }
        try (BufferedReader r = new BufferedReader(new FileReader(file))) {
            String line;
            while ((line = r.readLine()) != null) {
                String[] f = line.split(",");
                if (f.length < 8) continue;
                samples.add(new Sample(Float.parseFloat(f[0]), Float.parseFloat(f[1]), Float.parseFloat(f[2]),
                        Float.parseFloat(f[3]), Float.parseFloat(f[4]), Float.parseFloat(f[5]),
                        Float.parseFloat(f[6]), Float.parseFloat(f[7])));
            }
        } catch (Exception e) {
            Log.w(TAG, "load", e);
            samples.clear();
        }
    }

    private void save() {
        try (FileWriter wr = new FileWriter(file, false)) {
            for (Sample s : samples) {
                wr.write(String.format(Locale.US, "%.1f,%.1f,%.1f,%.4f,%.3f,%.5f,%.3f,%.5f%n",
                        s.x, s.y, s.z, s.phase, s.gap, s.slant, s.trackY, s.pitch));
            }
        } catch (Exception e) {
            Log.w(TAG, "save", e);
        }
    }

    /** Builds the correction model from the stored samples without changing settings. */
    synchronized void warmUp(Settings settings) {
        float phase = settings.phase;
        float gap = settings.gap;
        fit(settings);
        // The settings already hold the last fit; restore them exactly so loading never
        // moves the picture.
        settings.phase = phase;
        settings.gap = gap;
    }
}
