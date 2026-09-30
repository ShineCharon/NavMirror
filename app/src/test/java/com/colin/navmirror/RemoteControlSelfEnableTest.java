package com.colin.navmirror;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/** 无障碍自启用的列表合并逻辑（WRITE_SECURE_SETTINGS 路径的纯函数部分）。 */
public class RemoteControlSelfEnableTest {
    private static final String COMPONENT = "com.colin.navmirror/.RemoteControlService";

    @Test
    public void mergeEntry_appendsToExistingListWithoutDamagingOtherServices() {
        // HyperOS 上车机自带的语音无障碍服务必须保留——只追加，不覆盖
        assertEquals("com.voyah.ai.voice/SomeService:" + COMPONENT,
                RemoteControlService.mergeEntry("com.voyah.ai.voice/SomeService", COMPONENT));
    }

    @Test
    public void mergeEntry_handlesEmptyAndMissingList() {
        assertEquals(COMPONENT, RemoteControlService.mergeEntry(null, COMPONENT));
        assertEquals(COMPONENT, RemoteControlService.mergeEntry("", COMPONENT));
    }

    @Test
    public void mergeEntry_isIdempotentWhenAlreadyPresent() {
        String existing = "com.voyah.ai.voice/SomeService:" + COMPONENT;
        assertEquals(existing, RemoteControlService.mergeEntry(existing, COMPONENT));
        assertEquals(COMPONENT, RemoteControlService.mergeEntry(COMPONENT, COMPONENT));
    }

    @Test
    public void removeEntry_detachesOursAndKeepsOthers() {
        String existing = "com.voyah.ai.voice/SomeService:" + COMPONENT;
        assertEquals("com.voyah.ai.voice/SomeService",
                RemoteControlService.removeEntry(existing, COMPONENT));
        // 不存在时原样返回；空列表安全
        assertEquals(existing, RemoteControlService.removeEntry(existing, "com.other/Svc"));
        assertEquals("", RemoteControlService.removeEntry(null, COMPONENT));
        assertEquals("", RemoteControlService.removeEntry(COMPONENT, COMPONENT));
    }

    @Test
    public void removeThenMerge_roundTripsTheList() {
        String original = "com.voyah.ai.voice/SomeService:" + COMPONENT + ":com.other/Svc";
        String removed = RemoteControlService.removeEntry(original, COMPONENT);
        // 完整重绑循环：摘除后写回得到与原列表等价的集合（顺序不敏感地包含全部成员）
        String healed = RemoteControlService.mergeEntry(removed, COMPONENT);
        for (String member : original.split(":")) {
            assertTrue(healed.contains(member));
        }
        assertEquals(3, healed.split(":").length);
    }
}
