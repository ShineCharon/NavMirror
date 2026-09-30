package com.colin.navmirror;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class CaptureProfileTest {
    @Test
    public void balancedProfile_is1080p30At8Mbps() {
        // 30FPS 与车机 60Hz 合成 2:2 对齐（45FPS 会帧步进顿挫），8Mbps 留热点余量
        CaptureEngine.Profile profile = CaptureEngine.PROFILE_1080P45;
        assertEquals(1920, profile.width);
        assertEquals(864, profile.height);
        assertEquals(30, profile.fps);
        assertEquals(8_000_000, profile.bitrate);
    }

    @Test
    public void qualityLabels_coverBothProfilesAndLegacyFallback() {
        assertEquals("1080P 30FPS", NavMirrorService.qualityLabel(
                NavMirrorService.QUALITY_1080P45));
        assertEquals("720P 60FPS", NavMirrorService.qualityLabel(
                NavMirrorService.QUALITY_720P60));
        assertEquals("1080P 30FPS", NavMirrorService.qualityLabel(0));
    }
}
