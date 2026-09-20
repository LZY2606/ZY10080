package langalign;

import langalign.Model.*;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ModelTest {
    @Test
    void vfrAndDroppedFramesStayFrameAddressed() {
        // 丢帧：frameId 3 不存在；可变帧率：pts 间隔不等
        TimecodeTrack t = new TimecodeTrack(List.of(
                new FrameTiming(0, 0.0), new FrameTiming(1, 0.05),
                new FrameTiming(2, 0.09), new FrameTiming(4, 0.20)));
        assertEquals(-1, t.indexOf(3));
        assertFalse(t.hasFrame(3));
        assertThrows(IllegalArgumentException.class, () -> t.ptsOf(3));
        assertEquals(3, t.distanceFrames(0, 4)); // 帧序距离而非毫秒
        assertEquals(2, t.nearestIndex(3)); // 空隙映射到最近真实帧
    }

    @Test
    void fuzzyBoundKeepsPrecision() {
        FuzzyBound exact = new FuzzyBound(5L, 5L, 5L);
        assertEquals(Precision.EXACT, exact.precision());
        FuzzyBound fuzzy = FuzzyBound.fuzzy(3, 9);
        assertEquals(Precision.FUZZY, fuzzy.precision());
        assertEquals(6, fuzzy.representative());
        assertThrows(IllegalArgumentException.class, () -> new FuzzyBound(9L, null, 3L));
        assertThrows(IllegalArgumentException.class, () -> new FuzzyBound(null, null, null));
    }

    @Test
    void intervalRejectsInvertedBounds() {
        assertThrows(IllegalArgumentException.class,
                () -> new FuzzyInterval(FuzzyBound.exact(10), FuzzyBound.exact(5)));
    }
}
