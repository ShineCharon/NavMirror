package com.colin.navmirror;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.PixelFormat;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.Image;
import android.media.ImageReader;
import android.media.projection.MediaProjection;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.Display;
import android.view.Surface;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayDeque;
import java.util.Iterator;

final class CaptureEngine implements DisplayManager.DisplayListener {
    interface CaptureFailureListener {
        void onCaptureCreateFailed(Throwable t);
    }
    static final class Profile {
        final int width, height, fps, bitrate;
        final long frameMs;   // 1000/fps
        final String label;
        Profile(int w, int h, int fps, int bitrate, String label) {
            this.width = w; this.height = h; this.fps = fps; this.bitrate = bitrate;
            this.frameMs = 1000L / fps; this.label = label;
        }
    }
    // 1080P 档使用 30FPS：特斯拉车机浏览器(Chromium)按 60Hz 合成，45FPS 会导致
    // 不均匀帧步进(每 3 帧丢 1 个 vsync 节拍)产生顿挫感，且 33% 额外解码压力；
    // 30FPS 与 60Hz 严格 2:2 对齐，画面明显更顺。8Mbps 兼顾热点 Wi-Fi 余量。
    static final Profile PROFILE_1080P45 = new Profile(1920, 864, 30, 8_000_000, "1080P 30FPS");
    static final Profile PROFILE_720P60 = new Profile(1600, 720, 60, 8_000_000, "720P 60FPS");

    static final class VideoSegment {
        final long sequence;
        final byte[] data;
        final boolean keyframe;
        VideoSegment(long sequence, byte[] data, boolean keyframe) {
            this.sequence = sequence; this.data = data; this.keyframe = keyframe;
        }
    }

    /** One Annex-B access unit for the low-latency WebSocket/WebCodecs path. */
    static final class EncodedFrame {
        final long sequence;
        final byte[] data;
        final boolean keyframe;
        EncodedFrame(long sequence, byte[] data, boolean keyframe) {
            this.sequence = sequence; this.data = data; this.keyframe = keyframe;
        }
    }

    private static final String TAG = "NavMirror";
    private static final int JPEG_QUALITY = 88;
    private static final int MAX_VIDEO_SEGMENTS = 180;
    /** Annex-B 缓存上限：保留最近 2 个 GOP（fps × 0.5s × 2）。静态页面 3s 一个保活关键帧时也至少覆盖 10 个。 */
    private final int maxEncodedFrames;

    private final Context context;
    private final MediaProjection projection;
    private final Profile profile;
    /** App 侧「投屏模式」：true=直接走 JPEG 图像管线（老方案，绕开车机 MSE/H.264 视频解码） */
    private final boolean preferJpeg;
    private final Object lock = new Object();
    private final Object videoLock = new Object();
    private final ArrayDeque<VideoSegment> videoSegments = new ArrayDeque<>();
    private final ArrayDeque<EncodedFrame> encodedFrames = new ArrayDeque<>();
    private final MediaProjection.Callback projectionCallback = new MediaProjection.Callback() {
        @Override
        public void onStop() {
            Log.e(TAG, "!!! projection STOPPED by system (rotation/foreground change?)");
            if (!running) return;
            running = false;
            teardown();
            CaptureFailureListener l = failureListener;
            if (l != null) {
                try { l.onCaptureCreateFailed(new IllegalStateException("MediaProjection stopped")); }
                catch (Exception ignored) {}
            }
        }
    };

    private volatile ImageReader imageReader;
    private volatile byte[] latestJpeg;
    private volatile byte[] initSegment;
    private volatile H264Encoder h264Encoder;
    /** Surface currently attached to the one-shot VirtualDisplay. */
    private volatile Surface captureSurface;
    private volatile Fmp4Muxer muxer;
    private volatile boolean h264Mode;
    private volatile boolean jpegProducerStarted;
    /** 活跃的 /video(MSE) 观众数。默认网页走 WSS/WebCodecs；无人观看时不再产出 fMP4 分片，省 CPU/GC/内存。 */
    private final java.util.concurrent.atomic.AtomicInteger mseClients = new java.util.concurrent.atomic.AtomicInteger();
    private long videoSequence;
    private long encodedSequence;
    private byte[] annexBCodecConfig;
    private volatile boolean running;
    /** H.264 编码器致命错误后置位：WSS/MSE 循环据此退出，避免"假在线"。 */
    private volatile boolean encoderFailed;
    // JPEG 管线复用对象（produceLoop 单线程访问）：每帧 ~6MB 的 Bitmap 分配是
    // JPEG 模式 GC 压力/卡顿主因，维度不变时复用；结果数组必须新分配（latestJpeg 持有引用）
    private Bitmap jpegBitmap;
    private Bitmap jpegCropBitmap;
    private final android.graphics.Canvas jpegCropCanvas = new android.graphics.Canvas();
    private final ByteArrayOutputStream jpegBuffer = new ByteArrayOutputStream(256 * 1024);

    private int rotation = -1;
    private CaptureFailureListener failureListener;
    private volatile boolean createFailed;
    private int captureWidth;
    private int captureHeight;
    private volatile boolean sourcePortrait;
    private volatile double sourceAspect = 1.0;
    private final Object statsLock = new Object();
    private long statsStartedMs = SystemClock.elapsedRealtime();
    private int statsFrames;
    private long statsBytes;
    private volatile double actualFps;
    private volatile long actualBitrate;

    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    CaptureEngine(Context context, MediaProjection projection, int quality, boolean preferJpeg) {
        this.context = context;
        this.projection = projection;
        this.profile = quality == NavMirrorService.QUALITY_720P60
                ? PROFILE_720P60 : PROFILE_1080P45;
        this.preferJpeg = preferJpeg;
        this.maxEncodedFrames = Math.max(30, profile.fps);
    }

    int getCaptureWidth() { return captureWidth; }
    int getCaptureHeight() { return captureHeight; }
    int getFps() { return profile.fps; }
    int getBitrate() { return profile.bitrate; }
    // 30FPS: 每 66ms 批次 2 帧（60FPS: 4 帧）。
    int getH264BatchFrames() { return profile.fps >= 55 ? 4 : profile.fps >= 35 ? 3 : 2; }
    double getActualFps() { return actualFps; }
    long getActualBitrate() { return actualBitrate; }
    boolean isSourcePortrait() { return sourcePortrait; }
    double getSourceAspect() { return sourceAspect; }
    byte[] getLatestJpeg() { return latestJpeg; }
    boolean isH264Mode() { return h264Mode; }
    String codecString() {
        Fmp4Muxer m = muxer;
        String codec = m == null ? null : m.codecString();
        H264Encoder encoder = h264Encoder;
        return codec != null ? codec : encoder != null ? encoder.fallbackCodecString() : "";
    }

    void setCaptureFailureListener(CaptureFailureListener l) {
        failureListener = l;
    }

    boolean isEncoderFailed() { return encoderFailed; }

    /** 当前最新 Annex-B 帧序号：新 WSS 观众回放缓存关键帧后跳到这里等待实时流。 */
    long latestEncodedSequence() {
        synchronized (videoLock) { return encodedSequence; }
    }

    void start() {
        running = true;
        DisplayManager dm = (DisplayManager) context.getSystemService(Context.DISPLAY_SERVICE);
        try { dm.registerDisplayListener(this, mainHandler); } catch (Exception ignored) {}
        // Android 14+ (API 34) requires a registered callback before any capture starts
        try { projection.registerCallback(projectionCallback, mainHandler); } catch (Exception ignored) {}
        rebuild();
        if (virtualDisplay == null || (!h264Mode && imageReader == null)) {
            RuntimeException re = new IllegalStateException("createVirtualDisplay failed, no live capture");
            CaptureFailureListener l = failureListener;
            if (l != null) { try { l.onCaptureCreateFailed(re); } catch (Exception ignored) {} }
            throw re;
        }
        if (!h264Mode) startJpegProducer();
    }

    void stop() {
        running = false;
        DisplayManager dm = (DisplayManager) context.getSystemService(Context.DISPLAY_SERVICE);
        try { dm.unregisterDisplayListener(this); } catch (Exception ignored) {}
        try { projection.unregisterCallback(projectionCallback); } catch (Exception ignored) {}
        teardown();
        try { projection.stop(); } catch (Exception ignored) {}
    }

    private void rebuild() {
        DisplayManager dm = (DisplayManager) context.getSystemService(Context.DISPLAY_SERVICE);
        Display display = dm.getDisplay(Display.DEFAULT_DISPLAY);
        if (display == null) return;
        DisplayMetrics metrics = new DisplayMetrics();
        display.getRealMetrics(metrics);
        sourcePortrait = metrics.heightPixels > metrics.widthPixels;
        sourceAspect = metrics.heightPixels > 0
                ? metrics.widthPixels / (double) metrics.heightPixels : 1.0;
        int rot = display.getRotation();
        if (virtualDisplay != null) return;
        rotation = rot;

        // 固定横屏画幅(2.22:1≈手机横屏比例)：vD 只建一次(本 ROM 禁止重建)，手机横屏时内容与画布同比例=零黑边，旋转无需重建
        int w = profile.width;
        int h = profile.height;
        createFailed = false;
        Log.i(TAG, "rebuild VD -> " + w + "x" + h + " rot=" + rot);
        synchronized (lock) {
            teardownLocked();
            captureWidth = w;
            captureHeight = h;
            Surface surface = preferJpeg ? null : tryStartH264();
            ImageReader ir = null;
            if (surface == null) {
                if (!preferJpeg) {
                    IllegalStateException error = new IllegalStateException(
                            "H.264 capture unavailable; mode changes require a phone restart");
                    createFailed = true;
                    CaptureFailureListener listener = failureListener;
                    if (listener != null) {
                        try { listener.onCaptureCreateFailed(error); } catch (Exception ignored) {}
                    }
                    return;
                }
                ir = ImageReader.newInstance(w, h, PixelFormat.RGBA_8888, 2);
                imageReader = ir;
                surface = ir.getSurface();
                h264Mode = false;
            }
            captureSurface = surface;
            try {
                VirtualDisplay vd = projection.createVirtualDisplay(
                        "NavMirrorCapture", w, h, Math.max(metrics.densityDpi, 120),
                        DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, surface, null, mainHandler);
                virtualDisplay = vd;
            } catch (Exception e) {
                Log.e(TAG, "createVirtualDisplay failed: " + e.getMessage());
                if (ir != null) ir.close();
                captureSurface = null;
                H264Encoder encoder = h264Encoder;
                h264Encoder = null;
                if (encoder != null) encoder.stop();
                createFailed = true;
                // 锁屏/系统回收后 token 变 non-current，无法再建 VD：
                // 通知上层触发重新授权（REAUTH），拉起授权页让用户重新共享 -> 新 token -> 流恢复
                CaptureFailureListener l = failureListener;
                if (l != null) { try { l.onCaptureCreateFailed(e); } catch (Exception ignored) {} }
            }
        }
    }

    private VirtualDisplay virtualDisplay;

    private void teardown() {
        synchronized (lock) { teardownLocked(); }
    }

    private void teardownLocked() {
        if (virtualDisplay != null) { try { virtualDisplay.release(); } catch (Exception ignored) {} virtualDisplay = null; }
        if (imageReader != null) { try { imageReader.close(); } catch (Exception ignored) {} imageReader = null; }
        captureSurface = null;
        H264Encoder encoder = h264Encoder;
        h264Encoder = null;
        if (encoder != null) encoder.stop();
        h264Mode = false;
        // JPEG 复用位图随管线销毁回收（下一次 produceLoop 会按需重建）
        if (jpegBitmap != null) { try { jpegBitmap.recycle(); } catch (Exception ignored) {} jpegBitmap = null; }
        if (jpegCropBitmap != null) { try { jpegCropBitmap.recycle(); } catch (Exception ignored) {} jpegCropBitmap = null; }
        synchronized (videoLock) { videoLock.notifyAll(); }
    }

    private Surface tryStartH264() {
        resetStats();
        encoderFailed = false;
        final Fmp4Muxer newMuxer = new Fmp4Muxer(
                profile.width, profile.height, profile.fps, 1_000_000L);
        // 在役实例持有者：listener 需要在 encoder 完成赋值前创建（Java 明确赋值限制用数组绕开）
        final H264Encoder[] active = new H264Encoder[1];
        H264Encoder encoder = new H264Encoder(profile.width, profile.height,
                profile.fps, profile.bitrate, new H264Encoder.Listener() {
            @Override public void onCodecConfig(byte[] sps, byte[] pps) {
                try {
                    byte[] init = newMuxer.makeInitSegment(sps, pps);
                    byte[] config = annexB(sps, pps);
                    synchronized (videoLock) {
                        if (initSegment != null) return;
                        initSegment = init;
                        annexBCodecConfig = config;
                        videoSegments.clear();
                        encodedFrames.clear();
                        // 序号保持跨编码器重启单调递增：重置会让仍在运行的 WS 写线程
                        // 拿着旧序号永远等不到比它大的帧（画面冻结直到客户端断开）
                        videoLock.notifyAll();
                    }
                } catch (Throwable t) {
                    onError(t);
                }
            }

            @Override public void onAccessUnit(byte[] data, long ptsUs, boolean keyframe) {
                if (!h264Mode || initSegment == null) return;
                try {
                    // fMP4(MSE) 只有在 /video 观众存在时才封装；默认 WSS/WebCodecs 路径只要 Annex-B。
                    byte[] fragment = mseClients.get() > 0
                            ? newMuxer.makeMediaSegment(data, ptsUs, keyframe) : null;
                    // c2.qti.avc 硬编输出即 Annex-B（player.js 起始码扫描实测可证）：
                    // 直接复用编码线程拷出的数组，不再逐帧 split+重拷（高帧率下省 2 次全帧复制+N 个子数组）。
                    byte[] raw = data;
                    if (keyframe && annexBCodecConfig != null
                            && !Fmp4Muxer.containsAnnexBNalType(raw, 7)) {
                        raw = concat(annexBCodecConfig, raw);
                    }
                    recordEncodedFrame(raw.length);
                    synchronized (videoLock) {
                        if (fragment != null) {
                            videoSegments.addLast(new VideoSegment(++videoSequence, fragment, keyframe));
                            while (videoSegments.size() > MAX_VIDEO_SEGMENTS) videoSegments.removeFirst();
                        }
                        encodedFrames.addLast(new EncodedFrame(++encodedSequence, raw, keyframe));
                        while (encodedFrames.size() > maxEncodedFrames) encodedFrames.removeFirst();
                        videoLock.notifyAll();
                    }
                } catch (Throwable t) {
                    onError(t);
                }
            }

            @Override public void onError(Throwable error) {
                // 以"在役编码器实例"而不是 h264Mode 判定：h264Mode 在 start() 返回后才置位，
                // start 期间/刚返回的异步 onError 若按 h264Mode 过滤会被丢弃——
                // 之后 h264Mode=true，出现"服务器在线但永远无画面"（假在线）。
                // 旧编码器（已换新/已停）的迟到错误同样忽略。
                if (!running || h264Encoder != active[0]) return;
                // 致命错误进入失败状态：唤醒所有等待帧的 WSS/MSE 线程让其干净退出，并走恢复授权流程。
                Log.e(TAG, "H.264 encoder fatal -> failed state (manual restore required)", error);
                encoderFailed = true;
                synchronized (videoLock) { videoLock.notifyAll(); }
                CaptureFailureListener l = failureListener;
                if (l != null) {
                    try { l.onCaptureCreateFailed(
                            new IllegalStateException("H.264 encoder failed: " + error)); }
                    catch (Exception ignored) {}
                }
            }
        });
        active[0] = encoder;
        try {
            muxer = newMuxer;
            // 先于 start() 置位：上面 onError 以 h264Encoder==在役实例 识别启动期错误
            h264Encoder = encoder;
            Surface surface = encoder.start();
            if (encoderFailed) {
                // start() 期间异步 onError 已置位：不能无条件把 h264Mode 拉回 true——
                // 那会让失败态被覆盖，回到"服务器在线但永远无画面"的假在线。
                // 编码器错误已触发恢复授权通知；此处按"编码器不可用"收尾走降级路径。
                Log.w(TAG, "H.264 encoder failed during start; treating as unavailable");
                encoder.stop();
                h264Encoder = null;
                muxer = null;
                h264Mode = false;
                return null;
            }
            h264Mode = true;
            return surface;
        } catch (Exception e) {
            Log.w(TAG, "H.264 unavailable; selected mode remains unchanged: " + e.getMessage());
            encoder.stop();
            muxer = null;
            h264Encoder = null;
            h264Mode = false;
            return null;
        }
    }

    byte[] awaitInitSegment(long timeoutMs) throws InterruptedException {
        long end = SystemClock.elapsedRealtime() + timeoutMs;
        synchronized (videoLock) {
            while (running && h264Mode && initSegment == null) {
                long wait = end - SystemClock.elapsedRealtime();
                if (wait <= 0) break;
                videoLock.wait(wait);
            }
            return initSegment;
        }
    }

    long latestVideoSequence() {
        synchronized (videoLock) { return videoSequence; }
    }

    long sequenceBeforeLatestKeyframe() {
        synchronized (videoLock) {
            Iterator<VideoSegment> iterator = videoSegments.descendingIterator();
            while (iterator.hasNext()) {
                VideoSegment segment = iterator.next();
                if (segment.keyframe) return Math.max(0, segment.sequence - 1);
            }
            return videoSequence;
        }
    }

    VideoSegment waitForVideoSegmentAfter(long sequence, long timeoutMs) throws InterruptedException {
        long end = SystemClock.elapsedRealtime() + timeoutMs;
        synchronized (videoLock) {
            while (running && h264Mode) {
                for (VideoSegment segment : videoSegments) {
                    if (segment.sequence > sequence) return segment;
                }
                long wait = end - SystemClock.elapsedRealtime();
                if (wait <= 0) return null;
                videoLock.wait(wait);
            }
            return null;
        }
    }

    void requestSyncFrame() {
        H264Encoder encoder = h264Encoder;
        if (encoder != null) encoder.requestSyncFrame();
    }

    long sequenceBeforeLatestEncodedKeyframe() {
        synchronized (videoLock) {
            Iterator<EncodedFrame> iterator = encodedFrames.descendingIterator();
            while (iterator.hasNext()) {
                EncodedFrame frame = iterator.next();
                if (frame.keyframe) return Math.max(0, frame.sequence - 1);
            }
            return encodedSequence;
        }
    }

    EncodedFrame waitForEncodedFrameAfter(long sequence, long timeoutMs)
            throws InterruptedException {
        long end = SystemClock.elapsedRealtime() + timeoutMs;
        synchronized (videoLock) {
            while (running && h264Mode) {
                // If a blocked client fell far behind, resume at the newest complete GOP.
                if (encodedSequence - sequence > 12) {
                    Iterator<EncodedFrame> reverse = encodedFrames.descendingIterator();
                    while (reverse.hasNext()) {
                        EncodedFrame frame = reverse.next();
                        if (frame.sequence <= sequence) break;
                        if (frame.keyframe) return frame;
                    }
                }
                for (EncodedFrame frame : encodedFrames) {
                    if (frame.sequence > sequence) return frame;
                }
                long wait = end - SystemClock.elapsedRealtime();
                if (wait <= 0) return null;
                videoLock.wait(wait);
            }
            return null;
        }
    }

    /** /video(MSE) 客户端接入计数：>0 时才产出 fMP4 分片。 */
    void addMseClient() {
        mseClients.incrementAndGet();
    }

    /** /video(MSE) 客户端断开：归零时清空分片缓存释放内存（新观众从下一个关键帧起播）。 */
    void removeMseClient() {
        int remaining = mseClients.updateAndGet(v -> v > 0 ? v - 1 : 0);
        if (remaining == 0) {
            synchronized (videoLock) {
                videoSegments.clear();
            }
        }
    }

    private static byte[] annexB(byte[]... samples) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (byte[] sample : samples) {
            if (sample == null) continue;
            for (byte[] nal : Fmp4Muxer.splitNalUnits(sample)) {
                if (nal.length == 0) continue;
                out.write(new byte[]{0, 0, 0, 1});
                out.write(nal);
            }
        }
        return out.toByteArray();
    }

    private static byte[] concat(byte[] first, byte[] second) {
        byte[] result = new byte[first.length + second.length];
        System.arraycopy(first, 0, result, 0, first.length);
        System.arraycopy(second, 0, result, first.length, second.length);
        return result;
    }

    private void startJpegProducer() {
        if (jpegProducerStarted) return;
        jpegProducerStarted = true;
        Thread producer = new Thread(this::produceLoop, "navmirror-capture");
        producer.setDaemon(true);
        producer.start();
    }

    private void resetStats() {
        synchronized (statsLock) {
            statsStartedMs = SystemClock.elapsedRealtime();
            statsFrames = 0;
            statsBytes = 0;
            actualFps = 0;
            actualBitrate = 0;
        }
    }

    private void recordEncodedFrame(int bytes) {
        long now = SystemClock.elapsedRealtime();
        synchronized (statsLock) {
            statsFrames++;
            statsBytes += Math.max(0, bytes);
            long elapsed = now - statsStartedMs;
            if (elapsed >= 2000) {
                actualFps = statsFrames * 1000.0 / elapsed;
                actualBitrate = statsBytes * 8000L / elapsed;
                statsStartedMs = now;
                statsFrames = 0;
                statsBytes = 0;
            }
        }
    }

    private void produceLoop() {
        long t0 = SystemClock.elapsedRealtime();
        long lastFrameTime = SystemClock.elapsedRealtime();
        int frames = 0;
        int consecutiveErrors = 0;
        while (running) {
            long encodeStart = SystemClock.elapsedRealtime();
            boolean gotFrame = false;
            try {
                gotFrame = produceOnce();
                if (gotFrame) consecutiveErrors = 0;
            } catch (Throwable t) {
                // 每帧 ~6MB 的 Bitmap/JPEG 分配：OOM 或连续失败时不能无限重试，
                // 否则反复分配-GC-失败循环只会加剧内存压力。停止管线并走恢复授权。
                consecutiveErrors++;
                Log.e(TAG, "capture error (" + consecutiveErrors + "): " + t.getMessage(), t);
                if (t instanceof OutOfMemoryError || consecutiveErrors >= 3) {
                    running = false;
                    CaptureFailureListener l = failureListener;
                    if (l != null) {
                        try { l.onCaptureCreateFailed(
                                new IllegalStateException("JPEG pipeline exhausted: " + t)); }
                        catch (Exception ignored) {}
                    }
                    return;
                }
                sleep(400);
            }
            if (gotFrame) {
                frames++;
                lastFrameTime = SystemClock.elapsedRealtime();
                // 目标帧率节流：周期 = 编码耗时 + 剩余等待；编码快则稳定目标 fps，慢则取其上限。
                // JPEG 是软件压缩：不管档位多少都不超过 30FPS（720P60 档跑 60FPS 只会
                // 压满 CPU 且延迟堆积，观感反而更差——弱车机端 JPEG 本来也不是主力模式）
                long frameMs = Math.max(profile.frameMs, 33L);
                long cost = SystemClock.elapsedRealtime() - encodeStart;
                long wait = frameMs - cost;
                if (wait > 0) sleep(wait);
                if (SystemClock.elapsedRealtime() - t0 >= 5000) {
                    byte[] lj = latestJpeg;
                    String dim = "";
                    if (lj != null && lj.length > 2) {
                        dim = " (" + jpegDims(lj) + ")";
                    }
                    Log.i(TAG, "capture " + (frames * 1000 / 5000) + " fps (" + frames + " frames/5s)"
                            + dim + " enc=" + cost + "ms");
                    frames = 0;
                    t0 = SystemClock.elapsedRealtime();
                }
            } else {
                long idle = SystemClock.elapsedRealtime() - lastFrameTime;
                if (idle > 3000 && imageReader == null && !createFailed) {
                    Log.e(TAG, "no frames and no live VD - retry createVirtualDisplay");
                    lastFrameTime = SystemClock.elapsedRealtime();
                    rebuild();
                }
                sleep(20);
            }
        }
    }

    /** @return true if a new JPEG frame was produced */
    private boolean produceOnce() throws IOException {
        ImageReader ir = imageReader;
        if (ir == null) return false;
        // 像素拷出后立即归还 ImageReader buffer，压缩在归还之后进行——
        // 软件压缩耗时（~80ms）不再占用 VD 的输出 buffer
        Bitmap bmp;
        {
            Image image = null;
            try {
                image = ir.acquireLatestImage();
                if (image == null) return false;
                bmp = imageToBitmap(image);
                if (bmp == null) return false;
            } finally {
                if (image != null) { try { image.close(); } catch (Exception ignored) {} }
            }
        }
        // 返回 null = compress 返回 false（编码失败），按帧失败计数熔断
        byte[] jpg = compressJpeg(bmp);
        if (jpg == null || jpg.length == 0) throw new IOException("JPEG compress returned false");
        latestJpeg = jpg;
        recordEncodedFrame(jpg.length);
        return true;
    }

    /** 从 JPEG 头部解出宽高（SOF marker），仅用于诊断 */
    private static String jpegDims(byte[] jpg) {
        int i = 2;
        while (i + 9 < jpg.length) {
            if ((jpg[i] & 0xFF) == 0xFF) {
                int marker = jpg[i + 1] & 0xFF;
                if (marker == 0xC0 || marker == 0xC1 || marker == 0xC2) {
                    int h = ((jpg[i + 5] & 0xFF) << 8) | (jpg[i + 6] & 0xFF);
                    int w = ((jpg[i + 7] & 0xFF) << 8) | (jpg[i + 8] & 0xFF);
                    return w + "x" + h;
                }
                if (marker >= 0xD0 && marker <= 0xD9) { i += 2; continue; }
                int len = ((jpg[i + 2] & 0xFF) << 8) | (jpg[i + 3] & 0xFF);
                if (len < 2) return "?";
                i += 2 + len;
            } else {
                i++;
            }
        }
        return "?";
    }

    /** 维度不变时复用 Bitmap（rowPadding 变化会自动重建）；不回收，交给缓存管理。 */
    private Bitmap imageToBitmap(Image image) {
        Image.Plane plane = image.getPlanes()[0];
        ByteBuffer buffer = plane.getBuffer();
        int pixelStride = plane.getPixelStride();
        int rowStride = plane.getRowStride();
        int rowPadding = rowStride - pixelStride * image.getWidth();
        int pad = (pixelStride == 4 && rowPadding > 0) ? rowPadding / 4 : 0;
        int w = image.getWidth(), h = image.getHeight();
        if (pad > 0) {
            Bitmap full = jpegBitmap;
            if (full == null || full.getWidth() != w + pad || full.getHeight() != h) {
                if (full != null) full.recycle();
                full = Bitmap.createBitmap(w + pad, h, Bitmap.Config.ARGB_8888);
                jpegBitmap = full;
            }
            buffer.rewind();
            full.copyPixelsFromBuffer(buffer);
            Bitmap crop = jpegCropBitmap;
            if (crop == null || crop.getWidth() != w || crop.getHeight() != h) {
                if (crop != null) crop.recycle();
                crop = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
                jpegCropBitmap = crop;
            }
            jpegCropCanvas.setBitmap(crop);
            // 从 x=0 原样绘制：填充带在每行右侧，目标 Bitmap 宽度 w 自动裁掉它。
            // （此前误用 -pad 左移：会裁掉左侧内容、把右侧填充带进画面）
            jpegCropCanvas.drawBitmap(full, 0, 0, null);
            return crop;
        }
        Bitmap bmp = jpegBitmap;
        if (bmp == null || bmp.getWidth() != w || bmp.getHeight() != h) {
            if (bmp != null) bmp.recycle();
            bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
            jpegBitmap = bmp;
        }
        buffer.rewind();
        bmp.copyPixelsFromBuffer(buffer);
        return bmp;
    }

    /** 压缩到复用的缓冲区；结果数组必须新分配（latestJpeg 长期持有引用）。 */
    private byte[] compressJpeg(Bitmap bmp) {
        ByteArrayOutputStream baos = jpegBuffer;
        baos.reset();
        if (!bmp.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, baos)) return null;
        return baos.toByteArray();
    }

    private static void sleep(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }

    @Override public void onDisplayAdded(int displayId) { }
    @Override public void onDisplayRemoved(int displayId) { }
    @Override public void onDisplayChanged(int displayId) {
        if (displayId != Display.DEFAULT_DISPLAY) return;
        // 不在这里重建 VirtualDisplay：横竖屏切换/普通 display 变化不应打断投屏。
        // （历史上曾在此无脑 rebuild，撞上小米"旋转后 token non-current"导致横竖屏切换也断流）
        // VD 被系统回收（锁屏等）时，由采集循环的空闲检测触发 rebuild 恢复。
        Display d = null;
        try {
            DisplayManager dm = (DisplayManager) context.getSystemService(Context.DISPLAY_SERVICE);
            if (dm != null) d = dm.getDisplay(Display.DEFAULT_DISPLAY);
        } catch (Exception ignored) {}
        int rot = d != null ? d.getRotation() : -1;
        boolean orientationChanged = rot >= 0 && rotation >= 0 && rot != rotation;
        if (d != null) {
            DisplayMetrics metrics = new DisplayMetrics();
            d.getRealMetrics(metrics);
            sourcePortrait = metrics.heightPixels > metrics.widthPixels;
            sourceAspect = metrics.heightPixels > 0
                    ? metrics.widthPixels / (double) metrics.heightPixels : 1.0;
        }
        if (orientationChanged) {
            rotation = rot;
            // This ROM only allows one VirtualDisplay per projection token. Rebinding
            // its existing Surface forces SurfaceFlinger to refresh the projection
            // transform without recreating the VD. JPEG already got this effect when
            // setSurface() was called; the persistent codec Surface previously kept a
            // stale portrait transform after rotating to landscape.
            synchronized (lock) {
                VirtualDisplay vd = virtualDisplay;
                Surface surface = captureSurface;
                if (vd != null && surface != null) {
                    try {
                        int density = Math.max(context.getResources()
                                .getDisplayMetrics().densityDpi, 120);
                        vd.setSurface(null);
                        vd.resize(captureWidth, captureHeight, density);
                        vd.setSurface(surface);
                        Log.i(TAG, "refreshed VD surface transform after rotation");
                    } catch (Exception e) {
                        Log.w(TAG, "refresh VD surface after rotation failed: " + e.getMessage());
                    }
                }
            }
            requestSyncFrame();
            mainHandler.postDelayed(this::requestSyncFrame, 180);
        } else if (rot >= 0) {
            rotation = rot;
        }
        Log.i(TAG, "display changed, rotation=" + rot + ", portrait=" + sourcePortrait);
    }
}
