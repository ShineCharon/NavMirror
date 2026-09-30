package com.colin.navmirror;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Minimal pure-Java fMP4 fragment muxer: converts Annex-B H.264 NAL units
 * into MSE-compatible fMP4 segments (init segment + media segments).
 *
 * <p>Zero runtime dependencies (java.* only). Box layout follows ISO/IEC
 * 14496-12 (ISOBMFF); the avcC payload follows ISO/IEC 14496-15.
 *
 * <p>Produced segments:
 * <ul>
 *   <li>{@link #makeInitSegment}: {@code ftyp + moov(mvhd + trak(tkhd + mdia(mdhd + hdlr + minf(stbl))))}
 *       where {@code stsd} carries an {@code avc1} sample entry embedding SPS/PPS in {@code avcC}.</li>
 *   <li>{@link #makeMediaSegment}: {@code moof(mfhd + traf(tfhd + trun)) + mdat}.</li>
 * </ul>
 */
public final class Fmp4Muxer {

    private final int width;
    private final int height;
    private final int fps;
    private final long timescale;       // clock ticks per second (1_000_000 == microseconds)
    private final long frameDuration;  // timescale / fps

    private byte[] spsNal;
    private byte[] ppsNal;
    private long nextDts = 0; // continuous decode time for the next sample
    private long lastPtsUs = -1;
    private int sequenceNumber = 1; // mfhd sequence number (first fragment == 1)

    public Fmp4Muxer(int width, int height, int fps, long timescale) {
        this.width = width;
        this.height = height;
        this.fps = fps;
        this.timescale = timescale;
        this.frameDuration = timescale / fps;
    }

    // ---------------------------------------------------------------- public API

    /**
     * Builds the init segment ({@code ftyp + moov}) from Annex-B SPS/PPS.
     * Stores the AVCC config for subsequent media segments.
     */
    public byte[] makeInitSegment(byte[] spsAnnexB, byte[] ppsAnnexB) {
        this.spsNal = firstNal(spsAnnexB, 7);
        this.ppsNal = firstNal(ppsAnnexB, 8);
        if (spsNal == null || ppsNal == null) {
            throw new IllegalArgumentException("SPS/PPS missing from codec config");
        }
        byte[] ftyp = box("ftyp",
                bytes("isom"),            // major brand
                u32(0x00000200),           // minor version
                bytes("isom", "avc1", "mp42")); // compatible brands (each 4 bytes per ISO)
        return concat(ftyp, makeMoov());
    }

    /**
     * Builds one media segment ({@code moof + mdat}) from a single Annex-B frame.
     * Each call advances a continuous decode timeline. Android surface encoders can
     * output fewer frames than the configured FPS (especially on static screens).
     * Using their sparse absolute PTS directly creates holes between fragments;
     * Chromium then stalls or repeatedly seeks across those holes. We instead use
     * the recent PTS delta as this sample's duration and clamp exceptional pauses.
     *
     * @param ptsUs encoder presentation timestamp in microseconds
     */
    public byte[] makeMediaSegment(byte[] frameAnnexB, long ptsUs, boolean keyframe) {
        byte[] avcc = annexBToAvcc(frameAnnexB);
        if (avcc.length == 0) throw new IllegalArgumentException("empty H.264 access unit");
        long sampleDuration = frameDuration;
        if (lastPtsUs >= 0 && ptsUs > lastPtsUs) {
            long measured = (ptsUs - lastPtsUs) * timescale / 1_000_000L;
            long minimum = Math.max(1L, frameDuration / 2L);
            long maximum = Math.max(minimum, frameDuration * 4L);
            sampleDuration = Math.max(minimum, Math.min(maximum, measured));
        }
        lastPtsUs = ptsUs;
        long dts = nextDts;
        nextDts += sampleDuration;
        int seq = sequenceNumber++;
        byte[] moof = makeMoof(seq, dts, sampleDuration, avcc.length, keyframe);
        byte[] mdat = box("mdat", avcc);
        return concat(moof, mdat);
    }

    /** Resets muxer state (call when the encoder is reset / stream restarted). */
    public void reset() {
        nextDts = 0;
        lastPtsUs = -1;
        sequenceNumber = 1;
    }

    /**
     * Converts Annex-B NAL units (3- or 4-byte start codes) into AVCC form
     * (each NAL prefixed by a 4-byte big-endian length). Multiple NAL units in
     * the input are split and each gets its own length prefix.
     */
    public static byte[] annexBToAvcc(byte[] annexB) {
        List<byte[]> units = splitNalUnits(annexB);
        ByteArrayOutputStream out = new ByteArrayOutputStream(annexB.length + 16);
        for (byte[] unit : units) {
            int nalLen = unit.length;
            if (nalLen == 0) continue;
            out.write((nalLen >>> 24) & 0xFF);
            out.write((nalLen >>> 16) & 0xFF);
            out.write((nalLen >>> 8) & 0xFF);
            out.write(nalLen & 0xFF);
            out.write(unit, 0, nalLen);
        }
        return out.toByteArray();
    }

    /**
     * Annex-B 零分配扫描：判断是否包含指定 NAL 类型（5=IDR，7=SPS 等）。
     * 高帧率路径（每帧关键帧检测/SPS 检测）用它替代 splitNalUnits，避免逐帧创建子数组。
     */
    static boolean containsAnnexBNalType(byte[] sample, int wanted) {
        if (sample == null || sample.length < 4) return false;
        int i = 0;
        while (i + 4 < sample.length) {
            if (sample[i] == 0 && sample[i + 1] == 0
                    && (sample[i + 2] == 1 || (sample[i + 2] == 0 && sample[i + 3] == 1))) {
                int start = i + (sample[i + 2] == 1 ? 3 : 4);
                if (start < sample.length && (sample[start] & 0x1f) == wanted) return true;
                i = start;
            } else {
                i++;
            }
        }
        // 兜底：尾部不足 4 字节也可能是一个带起始码的极短 NAL
        return i + 3 == sample.length - 1
                && sample[i] == 0 && sample[i + 1] == 0 && sample[i + 2] == 1
                && (sample[i + 3] & 0x1f) == wanted;
    }

    /** Accepts Annex-B, AVCC, or one raw NAL and returns raw NAL payloads. */
    static List<byte[]> splitNalUnits(byte[] data) {
        List<byte[]> result = new ArrayList<>();
        if (data == null || data.length == 0) return result;
        int firstStart = findStartcode(data, 0);
        if (firstStart >= 0 && firstStart <= 3) {
            int i = firstStart;
            while (i < data.length) {
                int sc = findStartcode(data, i);
                if (sc < 0) break;
                int start = sc + startcodeLen(data, sc);
                int next = findStartcode(data, start);
                int end = next < 0 ? data.length : next;
                if (end > start) result.add(Arrays.copyOfRange(data, start, end));
                i = end;
            }
            return result;
        }
        int offset = 0;
        boolean validAvcc = data.length >= 5;
        while (validAvcc && offset + 4 <= data.length) {
            int len = readU32(data, offset);
            if (len <= 0 || offset + 4L + len > data.length) {
                validAvcc = false;
                break;
            }
            result.add(Arrays.copyOfRange(data, offset + 4, offset + 4 + len));
            offset += 4 + len;
        }
        if (validAvcc && offset == data.length && !result.isEmpty()) return result;
        result.clear();
        result.add(Arrays.copyOf(data, data.length));
        return result;
    }

    // ---------------------------------------------------------------- Annex-B helpers

    private static int findStartcode(byte[] b, int from) {
        for (int i = from; i + 2 < b.length; i++) {
            if (b[i] == 0 && b[i + 1] == 0) {
                if (b[i + 2] == 1) {
                    return i; // 00 00 01
                }
                if (i + 3 < b.length && b[i + 2] == 0 && b[i + 3] == 1) {
                    return i; // 00 00 00 01
                }
            }
        }
        return -1;
    }

    private static int startcodeLen(byte[] b, int sc) {
        if (sc + 3 < b.length && b[sc] == 0 && b[sc + 1] == 0 && b[sc + 2] == 0 && b[sc + 3] == 1) {
            return 4;
        }
        return 3; // 00 00 01
    }

    // ---------------------------------------------------------------- moov tree

    private byte[] makeMoov() {
        return box("moov", mvhd(), trak(), mvex());
    }

    /** mvhd version0: 108 bytes total. */
    private byte[] mvhd() {
        return box("mvhd",
                versionFlags(0, 0),
                u32(0),                 // creation_time
                u32(0),                 // modification_time
                u32((int) timescale),   // timescale
                u32(0),                 // duration (unknown for live stream)
                u32(0x00010000),         // rate = 1.0 (fixed 16.16)
                u16(0x0100),             // volume = 1.0 (8.8)
                new byte[10],            // reserved
                matrix(),                // identity matrix (9 x u32 = 36 bytes)
                new byte[24],            // pre_defined
                u32(2));                 // next_track_ID
    }

    private byte[] trak() {
        return box("trak", tkhd(), mdia());
    }

    /** tkhd version0: 96 bytes total (flags = enabled | in_movie | in_preview). */
    private byte[] tkhd() {
        return box("tkhd",
                versionFlags(0, 0x000007),
                u32(0),                 // creation_time
                u32(0),                 // modification_time
                u32(1),                 // track_ID
                new byte[4],            // reserved
                u32(0),                 // duration
                new byte[8],            // reserved
                u16(0),                 // layer
                u16(0),                 // alternate_group
                u16(0),                 // volume (0 for video)
                new byte[2],            // reserved
                matrix(),               // identity matrix (36 bytes)
                u32(width << 16),        // width (16.16)
                u32(height << 16));      // height (16.16)
    }

    private byte[] mdia() {
        return box("mdia", mdhd(), hdlr(), minf());
    }

    /** mdhd version0: 32 bytes total. */
    private byte[] mdhd() {
        return box("mdhd",
                versionFlags(0, 0),
                u32(0),                 // creation_time
                u32(0),                 // modification_time
                u32((int) timescale),   // timescale
                u32(0),                 // duration
                u16(0x55C4),             // language = 'und'
                u16(0));                 // pre_defined
    }

    private byte[] hdlr() {
        return box("hdlr",
                versionFlags(0, 0),
                new byte[4],            // pre_defined
                bytes("vide"),           // handler_type
                new byte[12],            // reserved
                bytes("VideoHandler", "\0")); // name (null-terminated)
    }

    private byte[] minf() {
        return box("minf", vmhd(), dinf(), stbl());
    }

    /** vmhd: 20 bytes (flags = 1). */
    private byte[] vmhd() {
        return box("vmhd", versionFlags(0, 1), u16(0), new byte[6]);
    }

    private byte[] dinf() {
        byte[] url = box("url ", versionFlags(0, 1)); // self-contained, no url field
        return box("dinf", box("dref", versionFlags(0, 0), u32(1), url));
    }

    private byte[] stbl() {
        return box("stbl", stsd(), emptyStts(), emptyStsc(), emptyStsz(), emptyStco());
    }

    private byte[] stsd() {
        return box("stsd", versionFlags(0, 0), u32(1), avc1Entry());
    }

    /** VisualSampleEntry 'avc1' carrying the avcC config box. */
    private byte[] avc1Entry() {
        return box("avc1",
                new byte[6],            // reserved
                u16(1),                 // data_reference_index
                new byte[2],            // pre_defined
                new byte[2],            // reserved
                new byte[12],           // pre_defined
                u16(width),            // width
                u16(height),            // height
                u32(0x00480000),         // horizresolution = 72 dpi (16.16)
                u32(0x00480000),         // vertresolution = 72 dpi
                new byte[4],            // reserved
                u16(1),                 // frame_count
                new byte[32],           // compressorname (pascal string, length 0)
                u16(0x0018),             // depth = 24
                u16(0xFFFF),             // pre_defined = -1
                avcC());                 // AVC decoder config
    }

    /**
     * avcC (AVCDecoderConfigurationRecord): configurationVersion + profile/compat/level
     * pulled from the SPS NAL unit, lengthSizeMinusOne = 3 (4-byte NAL lengths),
     * one SPS and one PPS (each 2-byte length prefixed).
     */
    private byte[] avcC() {
        int spsLen = spsNal.length;
        byte profile = spsLen > 1 ? spsNal[1] : 0;
        byte compat = spsLen > 2 ? spsNal[2] : 0;
        byte level = spsLen > 3 ? spsNal[3] : 0;
        int ppsLen = ppsNal.length;
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        b.write(1);                         // configurationVersion
        b.write(profile);                   // AVCProfileIndication
        b.write(compat);                     // profile_compatibility
        b.write(level);                     // AVCLevelIndication
        // 6 reserved bits (1) | lengthSizeMinusOne(2)=3 -> 0b11111111 = 0xFF (NAL length = 4 bytes)
        b.write(0xFF);
        // 3 reserved bits (1) | numOfSequenceParameterSets(5)=1 -> 0b11100001 = 0xE1
        b.write(0xE1);
        b.write((spsLen >>> 8) & 0xFF);     // SPS length (big-endian 16)
        b.write(spsLen & 0xFF);
        b.write(spsNal, 0, spsLen);
        b.write(1);                         // numOfPictureParameterSets
        b.write((ppsLen >>> 8) & 0xFF);     // PPS length (big-endian 16)
        b.write(ppsLen & 0xFF);
        b.write(ppsNal, 0, ppsLen);
        return box("avcC", b.toByteArray());
    }

    private byte[] mvex() {
        byte[] trex = box("trex",
                versionFlags(0, 0),
                u32(1),                 // track_ID
                u32(1),                 // default_sample_description_index
                u32((int) frameDuration),
                u32(0),                 // default_sample_size
                u32(0));                // default_sample_flags
        return box("mvex", trex);
    }

    private byte[] emptyStts() {
        return box("stts", versionFlags(0, 0), u32(0)); // entry_count = 0
    }

    private byte[] emptyStsc() {
        return box("stsc", versionFlags(0, 0), u32(0)); // entry_count = 0
    }

    private byte[] emptyStsz() {
        return box("stsz", versionFlags(0, 0), u32(0), u32(0)); // sample_size=0, sample_count=0
    }

    private byte[] emptyStco() {
        return box("stco", versionFlags(0, 0), u32(0)); // entry_count = 0
    }

    // ---------------------------------------------------------------- moof / trun

    private byte[] makeMoof(int seq, long dts, long sampleDuration,
                            int sampleSize, boolean keyframe) {
        byte[] mfhd = box("mfhd", versionFlags(0, 0), u32(seq));
        byte[] tfhd = box("tfhd", versionFlags(0, 0x020000), u32(1)); // default-base-is-moof, track_ID=1
        byte[] tfdt = box("tfdt", versionFlags(1, 0), u64(dts));
        byte[] trun = makeTrun(sampleDuration, sampleSize, keyframe);
        byte[] traf = box("traf", tfhd, tfdt, trun);
        byte[] moof = box("moof", mfhd, traf);
        // Patch the trun data_offset: offset from moof start to the first byte of
        // sample data inside mdat (mdat header is 8 bytes).
        int dataOffsetPos = 8 + mfhd.length + 8 + tfhd.length + tfdt.length + 16;
        writeU32(moof, dataOffsetPos, moof.length + 8);
        return moof;
    }

    /**
     * trun (version=1): data_offset + one sample with
     * {duration, size, flags, composition_offset} (spec field order).
     */
    private byte[] makeTrun(long sampleDuration, int sampleSize, boolean keyframe) {
        // 0x001 data_offset_present | 0x100 sample_duration | 0x200 sample_size
        // | 0x400 sample_flags | 0x800 sample_composition_time (32-bit since version=1)
        int flags = 0x00000F01;
        return box("trun",
                versionFlags(1, flags),
                u32(1),                             // sample_count
                u32(0),                              // data_offset (patched in makeMoof)
                u32((int) sampleDuration),           // measured, continuous sample duration
                u32(sampleSize),                     // sample size
                u32(keyframe ? 0x02000000 : 0x01010000),
                u32(0));                              // sample composition offset (pts == dts, no B-frames)
    }

    // ---------------------------------------------------------------- box primitives

    private static byte[] box(String type, byte[]... payloads) {
        byte[] body = concat(payloads);
        byte[] b = new byte[8 + body.length];
        writeU32(b, 0, b.length);
        byte[] t = type.getBytes(StandardCharsets.US_ASCII);
        System.arraycopy(t, 0, b, 4, 4);
        System.arraycopy(body, 0, b, 8, body.length);
        return b;
    }

    private static byte[] matrix() {
        return concat(
                u32(0x00010000), u32(0), u32(0),
                u32(0), u32(0x00010000), u32(0),
                u32(0), u32(0), u32(0x40000000));
    }

    String codecString() {
        if (spsNal == null || spsNal.length < 4) return null;
        return String.format("avc1.%02X%02X%02X", spsNal[1] & 0xFF,
                spsNal[2] & 0xFF, spsNal[3] & 0xFF);
    }

    private static byte[] firstNal(byte[] data, int wantedType) {
        for (byte[] unit : splitNalUnits(data)) {
            if (unit.length > 0 && (unit[0] & 0x1F) == wantedType) return unit;
        }
        return null;
    }

    private static byte[] concat(byte[]... arrs) {
        int len = 0;
        for (byte[] a : arrs) {
            len += a.length;
        }
        byte[] r = new byte[len];
        int off = 0;
        for (byte[] a : arrs) {
            System.arraycopy(a, 0, r, off, a.length);
            off += a.length;
        }
        return r;
    }

    private static byte[] bytes(String... ss) {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        for (String s : ss) {
            byte[] data = s.getBytes(StandardCharsets.US_ASCII);
            b.write(data, 0, data.length);
        }
        return b.toByteArray();
    }

    private static byte[] u32(int v) {
        return new byte[]{(byte) (v >>> 24), (byte) (v >>> 16), (byte) (v >>> 8), (byte) v};
    }

    private static byte[] u64(long v) {
        return new byte[]{
                (byte) (v >>> 56), (byte) (v >>> 48), (byte) (v >>> 40), (byte) (v >>> 32),
                (byte) (v >>> 24), (byte) (v >>> 16), (byte) (v >>> 8), (byte) v};
    }

    private static byte[] u16(int v) {
        return new byte[]{(byte) (v >>> 8), (byte) v};
    }

    private static byte[] versionFlags(int version, int flags) {
        return new byte[]{(byte) version, (byte) (flags >>> 16), (byte) (flags >>> 8), (byte) flags};
    }

    private static void writeU32(byte[] b, int off, int v) {
        b[off] = (byte) (v >>> 24);
        b[off + 1] = (byte) (v >>> 16);
        b[off + 2] = (byte) (v >>> 8);
        b[off + 3] = (byte) v;
    }

    private static int readU32(byte[] b, int off) {
        return ((b[off] & 0xFF) << 24)
                | ((b[off + 1] & 0xFF) << 16)
                | ((b[off + 2] & 0xFF) << 8)
                | (b[off + 3] & 0xFF);
    }
}
