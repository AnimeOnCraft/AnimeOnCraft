package ru.animeoncraft.integration.neoforge;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;

final class TitleAnimationTimelineTest {
    @Test void loopsWithoutSkippingTheLastFrameOrOverflowing() {
        var timeline = new TitleAnimationTimeline(1280, 720, 24, 238);
        assertEquals(9917, timeline.durationMillis());
        assertEquals(0, timeline.frame(-1));
        assertEquals(0, timeline.frame(0));
        assertEquals(1, timeline.frame(42));
        assertEquals(237, timeline.frame(9916));
        assertEquals(0, timeline.frame(9917));
        assertEquals(1, timeline.frame(9959));
        assertTrue(timeline.frame(Long.MAX_VALUE) >= 0 && timeline.frame(Long.MAX_VALUE) < 238);
        assertEquals(1, timeline.opacity(0));
        assertTrue(timeline.opacity(9916) < .01f);
        assertEquals(1, timeline.opacity(9917));
    }
    @Test void controlsFitSmallNormalAndUltrawideGuiSizes() {
        for (int[] size : new int[][]{{320,240},{427,240},{640,360},{800,450},{1280,360},{960,540}}) {
            var layout = TitleAnimationTimeline.layout(size[0], size[1]);
            assertTrue(layout.x() >= 10);
            assertTrue(layout.x() + layout.width() <= size[0] - 10);
            assertTrue(layout.y() >= 80);
            assertTrue(layout.bottom() + 20 <= size[1]);
            assertTrue(layout.half() >= 80);
            assertTrue(layout.row() >= 20);
        }
    }
    @Test void rejectsUnboundedResourcePackMetadata() {
        assertThrows(IllegalArgumentException.class, () -> new TitleAnimationTimeline(9999,720,24,238));
        assertThrows(IllegalArgumentException.class, () -> new TitleAnimationTimeline(1280,720,0,238));
        assertThrows(IllegalArgumentException.class, () -> new TitleAnimationTimeline(1280,720,24,9999));
    }
}
