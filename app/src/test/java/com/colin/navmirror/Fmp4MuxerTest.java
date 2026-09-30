package com.colin.navmirror;

import org.junit.Test;
import java.nio.ByteBuffer;
import static org.junit.Assert.*;

public class Fmp4MuxerTest {

    // 模拟 SPS (7) + PPS (8) + IDR (5) + P-frame (1)，Annex-B 格式（4-byte startcode）
    private static byte[] nal(int type, int size) {
        byte[] n = new byte[4 + size];
        n[0] = 0; n[1] = 0; n[2] = 0; n[3] = 1; // startcode
        n[4] = (byte) type; // NAL type in low 5 bits (简化)
        return n;
    }

    @Test
    public void initSegment_has_ftyp_and_moov() {
        Fmp4Muxer m = new Fmp4Muxer(1920, 864, 30, 1_000_000);
        byte[] sps = nal(7, 16);
        byte[] pps = nal(8, 8);
        byte[] initSeg = m.makeInitSegment(sps, pps);
        assertNotNull(initSeg);
        // ftyp magic at offset 8: 'isom'
        assertEquals("isom", new String(initSeg, 8, 4));
        // moov 存在：搜 'moov' magic
        boolean foundMoov = false;
        for (int i = 0; i + 4 <= initSeg.length; i++) {
            if (initSeg[i]=='m' && initSeg[i+1]=='o' && initSeg[i+2]=='o' && initSeg[i+3]=='v') { foundMoov = true; break; }
        }
        assertTrue("moov box missing", foundMoov);
        assertTrue("mvex box missing", containsType(initSeg, "mvex"));
        assertTrue("trex box missing", containsType(initSeg, "trex"));
    }

    @Test
    public void mediaSegment_starts_with_moof_and_includes_avcC_length_prefix() {
        Fmp4Muxer m = new Fmp4Muxer(1920, 864, 30, 1_000_000);
        m.makeInitSegment(nal(7, 16), nal(8, 8));
        byte[] idr = nal(5, 100);
        byte[] seg = m.makeMediaSegment(idr, 0, true /*keyframe*/);
        // moov? no — media segment 必须以 moof 开头
        assertEquals("moof", new String(seg, 4, 4)); // [size(4)] [moov? -> moof]
        boolean foundMdat = false;
        for (int i = 0; i + 4 <= seg.length; i++) {
            if (seg[i]=='m' && seg[i+1]=='d' && seg[i+2]=='a' && seg[i+3]=='t') { foundMdat = true; break; }
        }
        assertTrue("mdat box missing", foundMdat);
        assertTrue("tfdt box missing", containsType(seg, "tfdt"));
    }

    @Test
    public void annexB_to_avcc_converts_startcode_to_length() {
        byte[] annexB = {0,0,0,1, 0x65, 0x01, 0x02}; // type 5 (IDR simplified)
        byte[] avcc = Fmp4Muxer.annexBToAvcc(annexB);
        // 4-byte length prefix = 3 (NAL payload length)
        int len = ((avcc[0]&0xFF)<<24)|((avcc[1]&0xFF)<<16)|((avcc[2]&0xFF)<<8)|(avcc[3]&0xFF);
        assertEquals(3, len);
        assertEquals(0x65, avcc[4] & 0xFF);
    }

    @Test
    public void avcc_input_is_preserved_as_length_prefixed_units() {
        byte[] avccInput = {0,0,0,3, 0x65, 0x01, 0x02};
        assertArrayEquals(avccInput, Fmp4Muxer.annexBToAvcc(avccInput));
    }

    @Test
    public void sparseEncoderPts_producesContinuousTimelineWithoutHoles() {
        Fmp4Muxer m = new Fmp4Muxer(1920, 864, 30, 1_000_000);
        m.makeInitSegment(nal(7, 16), nal(8, 8));
        byte[] first = m.makeMediaSegment(nal(5, 32), 0, true);
        byte[] second = m.makeMediaSegment(nal(1, 32), 1_000_000, false);
        byte[] third = m.makeMediaSegment(nal(1, 32), 2_000_000, false);

        assertEquals(0L, readTfdt(first));
        assertEquals(33_333L, readTfdt(second));
        // The one-second encoder gap is capped to four frame intervals rather
        // than becoming an unbuffered one-second hole in MSE.
        assertEquals(166_665L, readTfdt(third));
        assertEquals(133_332L, readTrunDuration(second));
    }

    private static boolean containsType(byte[] data, String type) {
        byte[] wanted = type.getBytes(java.nio.charset.StandardCharsets.US_ASCII);
        for (int i = 0; i + wanted.length <= data.length; i++) {
            boolean matches = true;
            for (int j = 0; j < wanted.length; j++) {
                if (data[i + j] != wanted[j]) { matches = false; break; }
            }
            if (matches) return true;
        }
        return false;
    }

    @Test
    public void containsAnnexBNalType_matchesSplitNalUnitsOnMixedStreams() {
        // 4-byte 与 3-byte 起始码混合、多 NAL 串接——零分配扫描必须与 splitNalUnits 一致
        java.io.ByteArrayOutputStream buf = new java.io.ByteArrayOutputStream();
        buf.write(nal(7, 16), 0, 4 + 16);          // SPS
        buf.write(nal(8, 8), 0, 4 + 8);           // PPS
        // 3-byte 起始码的 IDR
        buf.write(new byte[]{0, 0, 1}, 0, 3);
        buf.write(new byte[]{5, 1, 2, 3, 4, 5}, 0, 6);
        buf.write(nal(1, 40), 0, 4 + 40);         // 非 IDR
        byte[] mixed = buf.toByteArray();
        for (int type = 1; type <= 8; type++) {
            boolean expected = !Fmp4Muxer.splitNalUnits(mixed).isEmpty()
                    ? containsViaSplit(mixed, type) : false;
            assertEquals("type " + type + " 检测不一致", expected,
                    Fmp4Muxer.containsAnnexBNalType(mixed, type));
        }
        // 单独验证已知类型
        assertTrue(Fmp4Muxer.containsAnnexBNalType(mixed, 5));
        assertTrue(Fmp4Muxer.containsAnnexBNalType(mixed, 7));
        assertTrue(Fmp4Muxer.containsAnnexBNalType(mixed, 8));
        assertTrue(Fmp4Muxer.containsAnnexBNalType(mixed, 1));
        assertFalse(Fmp4Muxer.containsAnnexBNalType(mixed, 6));
        // 边界：空/极短输入
        assertFalse(Fmp4Muxer.containsAnnexBNalType(null, 5));
        assertFalse(Fmp4Muxer.containsAnnexBNalType(new byte[0], 5));
        assertFalse(Fmp4Muxer.containsAnnexBNalType(new byte[]{1, 2, 3}, 5));
    }

    private static boolean containsViaSplit(byte[] data, int wanted) {
        for (byte[] unit : Fmp4Muxer.splitNalUnits(data)) {
            if (unit.length > 0 && (unit[0] & 0x1f) == wanted) return true;
        }
        return false;
    }

    private static long readTfdt(byte[] data) {
        int type = findType(data, "tfdt");
        assertTrue("tfdt missing", type >= 0);
        int value = type + 8; // type + version/flags
        return ((long) (data[value] & 0xFF) << 56)
                | ((long) (data[value + 1] & 0xFF) << 48)
                | ((long) (data[value + 2] & 0xFF) << 40)
                | ((long) (data[value + 3] & 0xFF) << 32)
                | ((long) (data[value + 4] & 0xFF) << 24)
                | ((long) (data[value + 5] & 0xFF) << 16)
                | ((long) (data[value + 6] & 0xFF) << 8)
                | (long) (data[value + 7] & 0xFF);
    }

    private static long readTrunDuration(byte[] data) {
        int type = findType(data, "trun");
        assertTrue("trun missing", type >= 0);
        int value = type + 16; // type + version/flags + sample_count + data_offset
        return ((long) (data[value] & 0xFF) << 24)
                | ((long) (data[value + 1] & 0xFF) << 16)
                | ((long) (data[value + 2] & 0xFF) << 8)
                | (long) (data[value + 3] & 0xFF);
    }

    private static int findType(byte[] data, String type) {
        byte[] wanted = type.getBytes(java.nio.charset.StandardCharsets.US_ASCII);
        for (int i = 0; i + wanted.length <= data.length; i++) {
            boolean matches = true;
            for (int j = 0; j < wanted.length; j++) {
                if (data[i + j] != wanted[j]) { matches = false; break; }
            }
            if (matches) return i;
        }
        return -1;
    }
}
