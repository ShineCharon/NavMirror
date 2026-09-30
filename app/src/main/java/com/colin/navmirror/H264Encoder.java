package com.colin.navmirror;

import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaFormat;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.util.Log;
import android.view.Surface;

import java.nio.ByteBuffer;
import java.util.List;

/** Surface-input H.264 hardware encoder. */
final class H264Encoder {
    interface Listener {
        void onCodecConfig(byte[] sps, byte[] pps);
        void onAccessUnit(byte[] data, long ptsUs, boolean keyframe);
        void onError(Throwable error);
    }

    private static final String TAG = "NavMirror";

    private final int width;
    private final int height;
    private final int fps;
    private final int bitrate;
    private final Listener listener;

    private HandlerThread callbackThread;
    private MediaCodec codec;
    private Surface inputSurface;
    private volatile boolean running;
    private int profile = MediaCodecInfo.CodecProfileLevel.AVCProfileBaseline;

    H264Encoder(int width, int height, int fps, int bitrate, Listener listener) {
        this.width = width;
        this.height = height;
        this.fps = fps;
        this.bitrate = bitrate;
        this.listener = listener;
    }

    Surface start() throws Exception {
        callbackThread = new HandlerThread("navmirror-h264");
        callbackThread.start();
        Handler callbackHandler = new Handler(callbackThread.getLooper());

        codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC);
        MediaCodecInfo.CodecCapabilities caps = codec.getCodecInfo()
                .getCapabilitiesForType(MediaFormat.MIMETYPE_VIDEO_AVC);
        profile = chooseProfile(caps.profileLevels);

        MediaFormat format = MediaFormat.createVideoFormat(
                MediaFormat.MIMETYPE_VIDEO_AVC, width, height);
        format.setInteger(MediaFormat.KEY_COLOR_FORMAT,
                MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface);
        format.setInteger(MediaFormat.KEY_BIT_RATE, bitrate);
        format.setInteger(MediaFormat.KEY_FRAME_RATE, fps);
        // GOP 1s：单个 IDR ~300-500KB，0.5s GOP 在弱车机浏览器上造成周期性解码/带宽尖峰
        // （拖动画面时大关键帧集中到达）。起播由新连接主动 requestSyncFrame 解决，
        // 积压恢复由客户端 waitKey 上行请求关键帧解决（player.js 'needkey' → /ws 'K'），
        // 正常播放期间不再依赖短 GOP。
        format.setFloat(MediaFormat.KEY_I_FRAME_INTERVAL, 1.0f);
        format.setInteger(MediaFormat.KEY_PRIORITY, 0);
        format.setInteger(MediaFormat.KEY_PROFILE, profile);
        // Surface capture may stop producing buffers while the screen is static. Keep a
        // low-rate heartbeat so MSE does not reach the buffered end and stall before the
        // user's first pan/zoom gesture. Values are deliberately below the active FPS.
        format.setLong(MediaFormat.KEY_REPEAT_PREVIOUS_FRAME_AFTER,
                fps >= 40 ? 66_000L : 100_000L);
        if (caps.getEncoderCapabilities().isBitrateModeSupported(
                MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR)) {
            format.setInteger(MediaFormat.KEY_BITRATE_MODE,
                    MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR);
        } else if (caps.getEncoderCapabilities().isBitrateModeSupported(
                MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR)) {
            format.setInteger(MediaFormat.KEY_BITRATE_MODE,
                    MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR);
        }
        format.setFloat(MediaFormat.KEY_OPERATING_RATE, fps);
        if (Build.VERSION.SDK_INT >= 29) {
            format.setInteger(MediaFormat.KEY_MAX_B_FRAMES, 0);
            format.setFloat(MediaFormat.KEY_MAX_FPS_TO_ENCODER, fps);
        }

        codec.setCallback(new MediaCodec.Callback() {
            @Override public void onInputBufferAvailable(MediaCodec c, int index) {
                // Surface-input encoders do not expose input buffers.
            }

            @Override public void onOutputBufferAvailable(
                    MediaCodec c, int index, MediaCodec.BufferInfo info) {
                try {
                    ByteBuffer buffer = c.getOutputBuffer(index);
                    if (buffer == null || info.size <= 0) return;
                    ByteBuffer view = buffer.duplicate();
                    view.position(info.offset);
                    view.limit(info.offset + info.size);
                    byte[] data = new byte[info.size];
                    view.get(data);
                    if ((info.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) {
                        emitConfig(data);
                    } else {
                        boolean keyframe = (info.flags & MediaCodec.BUFFER_FLAG_KEY_FRAME) != 0
                                || Fmp4Muxer.containsAnnexBNalType(data, 5);
                        listener.onAccessUnit(data, info.presentationTimeUs,
                                keyframe);
                    }
                } catch (Throwable t) {
                    reportError(t);
                } finally {
                    try { c.releaseOutputBuffer(index, false); } catch (Exception ignored) {}
                }
            }

            @Override public void onError(MediaCodec c, MediaCodec.CodecException e) {
                reportError(e);
            }

            @Override public void onOutputFormatChanged(MediaCodec c, MediaFormat format) {
                try {
                    byte[] csd0 = readBuffer(format.getByteBuffer("csd-0"));
                    byte[] csd1 = readBuffer(format.getByteBuffer("csd-1"));
                    if (csd0 != null && csd1 != null) {
                        listener.onCodecConfig(csd0, csd1);
                    } else if (csd0 != null) {
                        emitConfig(csd0);
                    }
                } catch (Throwable t) {
                    reportError(t);
                }
            }
        }, callbackHandler);

        try {
            codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
            inputSurface = codec.createInputSurface();
            running = true;
            codec.start();
            trySetParameter(codec, "low-latency", 1);
            trySetParameter(codec, "latency", 1);
            Log.i(TAG, "H.264 encoder started: " + codec.getName() + " "
                    + width + "x" + height + "@" + fps + " bitrate=" + bitrate
                    + " profile=" + profile);
            return inputSurface;
        } catch (Throwable t) {
            stop();
            if (t instanceof Exception) throw (Exception) t;
            throw new RuntimeException(t);
        }
    }

    void requestSyncFrame() {
        MediaCodec c = codec;
        if (!running || c == null) return;
        try {
            Bundle params = new Bundle();
            params.putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0);
            c.setParameters(params);
        } catch (Exception e) {
            Log.w(TAG, "request sync frame failed: " + e.getMessage());
        }
    }

    private static void trySetParameter(MediaCodec codec, String key, int value) {
        try {
            Bundle parameters = new Bundle();
            parameters.putInt(key, value);
            codec.setParameters(parameters);
        } catch (Exception ignored) {
            // Optional vendor/Android low-latency switches are not universal.
        }
    }

    String fallbackCodecString() {
        int profileIdc = profile == MediaCodecInfo.CodecProfileLevel.AVCProfileHigh ? 100
                : profile == MediaCodecInfo.CodecProfileLevel.AVCProfileMain ? 77 : 66;
        int levelIdc = width >= 1900 ? 40 : 32;
        return String.format("avc1.%02X00%02X", profileIdc, levelIdc);
    }

    void stop() {
        running = false;
        MediaCodec c = codec;
        codec = null;
        if (c != null) {
            try { c.stop(); } catch (Exception ignored) {}
            try { c.release(); } catch (Exception ignored) {}
        }
        Surface s = inputSurface;
        inputSurface = null;
        if (s != null) try { s.release(); } catch (Exception ignored) {}
        HandlerThread thread = callbackThread;
        callbackThread = null;
        if (thread != null) thread.quitSafely();
    }

    private void emitConfig(byte[] data) {
        List<byte[]> units = Fmp4Muxer.splitNalUnits(data);
        byte[] sps = null;
        byte[] pps = null;
        for (byte[] unit : units) {
            if (unit.length == 0) continue;
            int type = unit[0] & 0x1F;
            if (type == 7) sps = unit;
            else if (type == 8) pps = unit;
        }
        if (sps != null && pps != null) listener.onCodecConfig(sps, pps);
    }

    private void reportError(Throwable error) {
        if (!running) return;
        Log.e(TAG, "H.264 encoder error: " + error.getMessage(), error);
        listener.onError(error);
    }

    private static int chooseProfile(MediaCodecInfo.CodecProfileLevel[] levels) {
        // Baseline is the safest profile for older Chromium/WebCodecs builds used
        // in vehicle browsers. Prefer it even when the phone also exposes High.
        for (MediaCodecInfo.CodecProfileLevel level : levels) {
            if (level.profile == MediaCodecInfo.CodecProfileLevel.AVCProfileBaseline) {
                return MediaCodecInfo.CodecProfileLevel.AVCProfileBaseline;
            }
        }
        boolean main = false;
        for (MediaCodecInfo.CodecProfileLevel level : levels) {
            if (level.profile == MediaCodecInfo.CodecProfileLevel.AVCProfileMain) main = true;
        }
        return main ? MediaCodecInfo.CodecProfileLevel.AVCProfileMain
                : MediaCodecInfo.CodecProfileLevel.AVCProfileBaseline;
    }

    private static byte[] readBuffer(ByteBuffer source) {
        if (source == null) return null;
        ByteBuffer b = source.duplicate();
        byte[] data = new byte[b.remaining()];
        b.get(data);
        return data;
    }
}
