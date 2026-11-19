package com.nauty.p3d.glass;

import android.content.Context;
import android.content.res.AssetFileDescriptor;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.SystemClock;
import android.util.Log;

import com.nauty.p3d.gl.DepthProvider;
import com.qualcomm.qti.QnnDelegate;

import org.tensorflow.lite.DataType;
import org.tensorflow.lite.Delegate;
import org.tensorflow.lite.Interpreter;
import org.tensorflow.lite.gpu.GpuDelegate;

import java.io.FileInputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;
import java.util.Locale;

/**
 * AI depth for 2D -> 3D: runs a monocular depth model on snapshots of the picture.
 *
 * Model: Depth Anything V2 Small (Apache-2.0; Qualcomm AI Hub TFLite export, MIT),
 * 518 x 518, input RGB 0..1, output relative inverse depth (larger is nearer).
 *
 * Where it runs: the Snapdragon NPU (Hexagon HTP, Qualcomm QNN delegate) first, then the
 * GPU (LiteRT GPU delegate), then the CPU; the status line says which one and how fast.
 *
 * The raw output is rescaled per frame to 0..1 between its 2nd and 98th percentile (the
 * range eased over frames), then eased per pixel over time so the depth does not shimmer;
 * a scene cut (big change everywhere) resets the easing.
 */
final class DepthEngine implements DepthProvider {
    private static final String TAG = "DepthEngine";

    enum Model {
        DA2("depth/depth_anything_v2.tflite", 518, 518, "Depth Anything V2");

        final String asset;
        final int w, h;
        final String label;

        Model(String asset, int w, int h, String label) {
            this.asset = asset;
            this.w = w;
            this.h = h;
            this.label = label;
        }
    }

    interface Listener {
        /** Any thread: a short status line (model, accelerator, speed), or null. */
        void onStatus(String text);

        /** Any thread: a new depth map is ready (redraw). */
        void onDepth();
    }

    private final Context ctx;
    private final Listener listener;
    private final HandlerThread thread;
    private final Handler worker;

    /** What the user picked; null = off (the shear). */
    private volatile Model wanted;
    // Worker thread only.
    private Interpreter interp;
    // Scene cuts, from a 4 x 4 grid of mean brightness of the snapshot: a cut makes the
    // depth start over (no easing, new range, no video memory) instead of carrying the
    // last shot's depth into the new one for a while.
    private final float[] luma16 = new float[16];
    private final float[] lastLuma = new float[16];
    private boolean haveLuma;
    private static final float CUT = 0.10f;
    private Delegate delegate;
    private String accel;
    private Model loaded;
    private ByteBuffer input;
    private FloatBuffer inputF;
    private ByteBuffer output;
    private FloatBuffer outputF;
    private float[] smooth;
    // Plain arrays: element-by-element ByteBuffer/FloatBuffer access cost ~50 ms a frame
    // at 518 x 518, more than the NPU itself (34 ms).
    private byte[] rgbaArr;
    private float[] inArr;
    private float[] outArr;
    private byte[] depthArr;
    private double prepSum, postSum;
    private float[] rowA, rowB;

    private float[] rowTmp(int n) {
        if (rowA == null || rowA.length != n) rowA = new float[n];
        return rowA;
    }

    private float[] rowTmp2(int n) {
        if (rowB == null || rowB.length != n) rowB = new float[n];
        return rowB;
    }
    private float lo, hi;
    private boolean haveRange;
    private int frames;
    private long statSince;
    private double msSum;
    /** Mean depth of the top and bottom thirds (log check that near = large). */
    private float topMean, bottomMean;

    /** Model ready to take snapshots (set on the worker, read on the GL thread). */
    private volatile Model ready;
    private volatile boolean busy;
    private ByteBuffer snapshot;            // GL thread writes, worker reads (busy guards it)

    private final Object outLock = new Object();
    private ByteBuffer outBytes;            // newest depth map
    private int outW, outH;
    private boolean fresh;

    DepthEngine(Context c, Listener l) {
        ctx = c.getApplicationContext();
        listener = l;
        thread = new HandlerThread("ai-depth");
        thread.start();
        worker = new Handler(thread.getLooper());
    }

    /** Switches to this model (null = off). Loading happens in the background. */
    void setModel(Model m) {
        wanted = m;
        ready = null;
        worker.post(() -> load(m));
    }

    Model model() {
        return wanted;
    }

    void close() {
        wanted = null;
        ready = null;
        worker.post(() -> {
            release();
            thread.quitSafely();
        });
    }

    // ---- DepthProvider (GL thread) ----

    @Override
    public boolean active() {
        Model r = ready;
        return r != null && r == wanted;
    }

    @Override
    public int inputWidth() {
        Model r = ready;
        return r == null ? 256 : r.w;
    }

    @Override
    public int inputHeight() {
        Model r = ready;
        return r == null ? 256 : r.h;
    }

    @Override
    public boolean wantsFrame() {
        return active() && !busy;
    }

    @Override
    public void submit(ByteBuffer rgba) {
        Model r = ready;
        if (r == null || busy) return;
        int bytes = r.w * r.h * 4;
        if (snapshot == null || snapshot.capacity() != bytes) {
            snapshot = ByteBuffer.allocateDirect(bytes).order(ByteOrder.nativeOrder());
        }
        snapshot.position(0);
        rgba.position(0);
        rgba.limit(bytes);
        snapshot.put(rgba);
        rgba.clear();
        busy = true;
        worker.post(() -> infer(r));
    }

    @Override
    public int depthWidth() {
        synchronized (outLock) {
            return outW;
        }
    }

    @Override
    public int depthHeight() {
        synchronized (outLock) {
            return outH;
        }
    }

    @Override
    public ByteBuffer takeDepth() {
        synchronized (outLock) {
            if (!fresh) return null;
            fresh = false;
            outBytes.position(0);
            return outBytes;
        }
    }

    // ---- worker thread ----

    private void load(Model m) {
        if (m == loaded && interp != null) {
            ready = m;
            status(null);
            return;
        }
        release();
        if (m == null || m != wanted) {
            status(null);
            return;
        }
        status(ctx.getString(com.nauty.p3d.R.string.aidepth_loading, m.label));
        MappedByteBuffer buf;
        try (AssetFileDescriptor fd = ctx.getAssets().openFd(m.asset);
             FileInputStream in = new FileInputStream(fd.getFileDescriptor())) {
            buf = in.getChannel().map(FileChannel.MapMode.READ_ONLY, fd.getStartOffset(), fd.getDeclaredLength());
        } catch (Exception e) {
            Log.e(TAG, "model " + m.asset, e);
            status(ctx.getString(com.nauty.p3d.R.string.aidepth_failed, m.label));
            return;
        }
        long t0 = SystemClock.elapsedRealtime();
        if (!tryNpu(m, buf) && !tryGpu(buf) && !tryCpu(buf)) {
            status(ctx.getString(com.nauty.p3d.R.string.aidepth_failed, m.label));
            return;
        }
        int n = m.w;
        int[] inShape = interp.getInputTensor(0).shape();
        int[] outShape = interp.getOutputTensor(0).shape();
        DataType inType = interp.getInputTensor(0).dataType();
        Log.i(TAG, String.format(Locale.US, "%s on %s, load %d ms, in %s %s, out %s",
                m.label, accel, SystemClock.elapsedRealtime() - t0,
                java.util.Arrays.toString(inShape), inType, java.util.Arrays.toString(outShape)));
        input = ByteBuffer.allocateDirect(n * n * 3 * 4).order(ByteOrder.nativeOrder());
        inputF = input.asFloatBuffer();
        output = ByteBuffer.allocateDirect(n * n * 4).order(ByteOrder.nativeOrder());
        outputF = output.asFloatBuffer();
        allocate(m);
        // Warm-up: the first run on the NPU compiles the graph.
        try {
            long w0 = SystemClock.elapsedRealtime();
            interp.run(input, output);
            Log.i(TAG, "warm-up " + (SystemClock.elapsedRealtime() - w0) + " ms");
        } catch (Exception e) {
            Log.e(TAG, "warm-up", e);
        }
        finishLoad(m);
    }

    private void allocate(Model m) {
        int count = m.w * m.h;
        smooth = new float[count];
        rgbaArr = new byte[count * 4];
        inArr = new float[count * 3];
        outArr = new float[count];
        depthArr = new byte[count];
        haveRange = false;
        synchronized (outLock) {
            outBytes = ByteBuffer.allocateDirect(count);
            outW = m.w;
            outH = m.h;
            fresh = false;
        }
    }

    private void finishLoad(Model m) {
        loaded = m;
        frames = 0;
        msSum = 0;
        statSince = SystemClock.elapsedRealtime();
        if (wanted == m) {
            ready = m;
            status(null);   // ready: no line under the button (speed goes to the log only)
        }
    }

    private boolean tryNpu(Model m, MappedByteBuffer buf) {
        try {
            QnnDelegate.Options o = new QnnDelegate.Options();
            o.setBackendType(QnnDelegate.Options.BackendType.HTP_BACKEND);
            o.setSkelLibraryDir(ctx.getApplicationInfo().nativeLibraryDir);
            o.setHtpPrecision(QnnDelegate.Options.HtpPrecision.HTP_PRECISION_FP16);
            o.setHtpPerformanceMode(QnnDelegate.Options.HtpPerformanceMode.HTP_PERFORMANCE_SUSTAINED_HIGH_PERFORMANCE);
            o.setCacheDir(ctx.getCacheDir().getAbsolutePath());
            o.setModelToken(m.name().toLowerCase(Locale.US) + "-1");
            QnnDelegate d = new QnnDelegate(o);
            Interpreter.Options io = new Interpreter.Options();
            io.addDelegate(d);
            io.setNumThreads(2);   // for whatever the NPU does not take
            interp = new Interpreter(buf, io);
            delegate = d;
            accel = "NPU";
            return true;
        } catch (Throwable t) {
            Log.w(TAG, "NPU (QNN HTP) not used: " + t);
            closeQuietly();
            return false;
        }
    }

    private boolean tryGpu(MappedByteBuffer buf) {
        try {
            GpuDelegate.Options go = new GpuDelegate.Options();
            go.setPrecisionLossAllowed(true);
            go.setInferencePreference(GpuDelegate.Options.INFERENCE_PREFERENCE_SUSTAINED_SPEED);
            GpuDelegate d = new GpuDelegate(go);
            Interpreter.Options io = new Interpreter.Options();
            io.addDelegate(d);
            interp = new Interpreter(buf, io);
            delegate = d;
            accel = "GPU";
            return true;
        } catch (Throwable t) {
            Log.w(TAG, "GPU not used: " + t);
            closeQuietly();
            return false;
        }
    }

    private boolean tryCpu(MappedByteBuffer buf) {
        try {
            Interpreter.Options io = new Interpreter.Options();
            io.setNumThreads(4);
            interp = new Interpreter(buf, io);
            accel = "CPU";
            return true;
        } catch (Throwable t) {
            Log.e(TAG, "CPU failed", t);
            closeQuietly();
            return false;
        }
    }

    private void infer(Model m) {
        try {
            if (m != loaded || interp == null) return;
            int w = m.w;
            int h = m.h;
            long p0 = SystemClock.elapsedRealtimeNanos();
            ByteBuffer s = snapshot;
            s.position(0);
            s.get(rgbaArr);
            byte[] src = rgbaArr;
            float[] in = inArr;
            final float k = 1f / 255f;
            long t0, t1;
            boolean cut = sceneCut(src, w, h);
            // RGBA (row 0 = bottom) -> RGB float 0..1 (row 0 = top).
            int o = 0;
            for (int y = 0; y < h; y++) {
                int i = (h - 1 - y) * w * 4;
                for (int x = 0; x < w; x++, i += 4) {
                    in[o++] = (src[i] & 0xFF) * k;
                    in[o++] = (src[i + 1] & 0xFF) * k;
                    in[o++] = (src[i + 2] & 0xFF) * k;
                }
            }
            inputF.position(0);
            inputF.put(in);
            input.position(0);
            output.position(0);
            t0 = SystemClock.elapsedRealtimeNanos();
            interp.run(input, output);
            t1 = SystemClock.elapsedRealtimeNanos();
            outputF.position(0);
            outputF.get(outArr);
            double ms = (t1 - t0) / 1e6;
            if (cut) haveRange = false;
            normalize(w, h, false);
            frames++;
            msSum += ms;
            prepSum += (t0 - p0) / 1e6;
            postSum += (SystemClock.elapsedRealtimeNanos() - t1) / 1e6;
            long now = SystemClock.elapsedRealtime();
            if (now - statSince >= 1000) {
                double fps = frames * 1000.0 / (now - statSince);
                String st = ctx.getString(com.nauty.p3d.R.string.aidepth_status,
                        m.label, accel, msSum / frames, fps);
                Log.i(TAG, st + String.format(Locale.US, "  (prep %.1f, post %.1f ms; top %.2f, bottom %.2f)",
                        prepSum / frames, postSum / frames, topMean, bottomMean));
                prepSum = 0;
                postSum = 0;
                frames = 0;
                msSum = 0;
                statSince = now;
            }
            listener.onDepth();
        } catch (Throwable t) {
            Log.e(TAG, "inference", t);
        } finally {
            busy = false;
        }
    }

    /** True when the snapshot (RGBA, w x h) is a different shot from the last one. */
    private boolean sceneCut(byte[] src, int w, int h) {
        java.util.Arrays.fill(luma16, 0f);
        for (int y = 0; y < h; y += 2) {
            int by = y * 4 / h;
            int i = y * w * 4;
            for (int x = 0; x < w; x += 2, i += 8) {
                luma16[by * 4 + x * 4 / w] += 0.299f * (src[i] & 0xFF) + 0.587f * (src[i + 1] & 0xFF)
                        + 0.114f * (src[i + 2] & 0xFF);
            }
        }
        float cell = ((h + 1) / 2) * ((w + 1) / 2) / 16f * 255f;
        float diff = 0f;
        for (int i = 0; i < 16; i++) {
            luma16[i] /= cell;
            diff += Math.abs(luma16[i] - lastLuma[i]);
        }
        boolean cut = haveLuma && diff / 16f > CUT;
        System.arraycopy(luma16, 0, lastLuma, 0, 16);
        haveLuma = true;
        if (cut) Log.i(TAG, String.format(Locale.US, "scene cut (%.2f)", diff / 16f));
        return cut;
    }

    /** Raw output -> 0..1 (2nd..98th percentile), eased over time, into outBytes (row 0 = bottom). */
    private void normalize(int w, int h, boolean steady) {
        int count = w * h;
        float[] out = outArr;
        float mn = Float.MAX_VALUE;
        float mx = -Float.MAX_VALUE;
        for (int i = 0; i < count; i++) {
            float v = out[i];
            if (v < mn) mn = v;
            if (v > mx) mx = v;
        }
        if (!(mx > mn)) return;
        int[] hist = new int[256];
        float k = 255.99f / (mx - mn);
        for (int i = 0; i < count; i++) {
            hist[(int) ((out[i] - mn) * k)]++;
        }
        int acc = 0;
        int p2 = 0;
        int p98 = 255;
        for (int b = 0; b < 256; b++) {
            acc += hist[b];
            if (acc < count * 0.02f) p2 = b;
            if (acc < count * 0.98f) p98 = b + 1;
        }
        float nlo = mn + p2 / k;
        float nhi = mn + p98 / k;
        if (!haveRange) {
            lo = nlo;
            hi = nhi;
        } else {
            lo += 0.3f * (nlo - lo);
            hi += 0.3f * (nhi - hi);
        }
        float range = Math.max(1e-6f, hi - lo);
        // Scene cut: everything changes at once -> no easing this frame.
        double change = 0;
        if (haveRange) {
            for (int i = 0; i < count; i += 17) {
                float v = Math.min(1f, Math.max(0f, (out[i] - lo) / range));
                change += Math.abs(v - smooth[i]);
            }
            change /= (count + 16) / 17;
        }
        // A video model is steady by itself: ease less, so the depth keeps up.
        float a = !haveRange || change > 0.2 ? 1f : (steady ? 0.8f : 0.5f);
        haveRange = true;
        float inv = 1f / range;
        float[] sm = smooth;
        for (int i = 0; i < count; i++) {
            float v = (out[i] - lo) * inv;
            v = v < 0f ? 0f : (v > 1f ? 1f : v);
            sm[i] += a * (v - sm[i]);
        }
        // Edges: the parallax is horizontal, so only horizontal depth steps tear the
        // picture. Grow near objects a few pixels sideways (max filter), then soften the
        // step (box blur), so a sharp step becomes a short ramp: the warp stretches the
        // edge a little instead of leaving a thin line of the wrong side along it.
        float[] row = rowTmp(w);
        float[] row2 = rowTmp2(w);
        byte[] d = depthArr;
        int rMax = Math.max(1, w / 170);    // 3 px at 518
        int rBlur = Math.max(1, w / 130);   // 4 px at 518
        int n = w;
        for (int y = 0; y < h; y++) {
            int base = y * n;
            for (int x = 0; x < n; x++) {
                float m = sm[base + x];
                int x0 = Math.max(0, x - rMax);
                int x1 = Math.min(n - 1, x + rMax);
                for (int j = x0; j <= x1; j++) {
                    float v = sm[base + j];
                    if (v > m) m = v;
                }
                row[x] = m;
            }
            float sum = 0f;
            int win = 2 * rBlur + 1;
            for (int j = -rBlur; j <= rBlur; j++) sum += row[Math.min(n - 1, Math.max(0, j))];
            for (int x = 0; x < n; x++) {
                row2[x] = sum / win;
                sum += row[Math.min(n - 1, x + rBlur + 1)] - row[Math.max(0, x - rBlur)];
            }
            int dst = (h - 1 - y) * n;     // back to row 0 = bottom
            for (int x = 0; x < n; x++) {
                d[dst + x] = (byte) (int) (row2[x] * 255f + 0.5f);
            }
        }
        synchronized (outLock) {
            outBytes.position(0);
            outBytes.put(d);
            outBytes.position(0);
            fresh = true;
        }
        double t = 0, b = 0;
        int third = h / 3;
        for (int y = 0; y < third; y++) {
            for (int x = 0; x < n; x += 4) {
                t += smooth[y * n + x];
                b += smooth[(h - 1 - y) * n + x];
            }
        }
        double cnt = third * ((n + 3) / 4.0);
        topMean = (float) (t / cnt);
        bottomMean = (float) (b / cnt);
    }

    private void status(String s) {
        listener.onStatus(s);
    }

    private void release() {
        ready = null;
        closeQuietly();

        loaded = null;
    }

    private void closeQuietly() {
        if (interp != null) {
            try { interp.close(); } catch (Throwable ignored) { }
            interp = null;
        }
        if (delegate instanceof AutoCloseable) {
            try { ((AutoCloseable) delegate).close(); } catch (Throwable ignored) { }
        }
        delegate = null;
    }
}
