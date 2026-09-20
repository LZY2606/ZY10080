package org.research.timeline;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.research.timeline.model.FrameTable;
import org.research.timeline.model.Temporal;
import org.research.timeline.service.ApiError;
import org.research.timeline.util.Json;

class TemporalTest {

    private FrameTable table(int n) {
        List<Object> frames = new ArrayList<>();
        long nanos = 0;
        for (int i = 0; i < n; i++) {
            // Deliberately non-uniform gaps: fixed ms/frame conversion would be wrong.
            nanos += (i % 3 == 0) ? 50_000_000L : 25_000_000L;
            frames.add(Map.of("ordinal", i, "frameId", "f" + i,
                    "timecode", String.format("00:00:00.%03d", nanos / 1_000_000),
                    "timeNanos", nanos));
        }
        return FrameTable.fromImport(Map.of("videoId", "v", "frames", frames));
    }

    @Test
    void resolvesFrameIdAnchorToOrdinal() {
        FrameTable table = table(10);
        Temporal.Bound bound = table.resolve(Map.of("frameId", "f4"));
        assertTrue(bound.precise());
        assertEquals(4, bound.lo());
        assertEquals("f4", bound.loFrameId());
    }

    @Test
    void fuzzyBetweenKeepsOrdinalRangeAndFrameIds() {
        FrameTable table = table(10);
        Temporal.Bound bound = table.resolve(Json.parseObject(
                "{\"fuzzyBetween\":{\"from\":{\"frameId\":\"f2\"},\"to\":{\"frameId\":\"f5\"}}}"));
        assertFalse(bound.precise());
        assertEquals(2, bound.lo());
        assertEquals(5, bound.hi());
        assertEquals("f2", bound.loFrameId());
        assertEquals("f5", bound.hiFrameId());
    }

    @Test
    void rejectsBareMilliseconds() {
        FrameTable table = table(10);
        ApiError error = assertThrows(ApiError.class,
                () -> table.resolve(Map.of("ms", 1200)));
        assertEquals(ApiError.Category.INPUT_FORMAT, error.category());
    }

    @Test
    void rejectsTimecodeThatIsNotAnExactFrame() {
        FrameTable table = table(10);
        ApiError error = assertThrows(ApiError.class,
                () -> table.resolve(Map.of("timecode", "00:00:00.999")));
        assertEquals(ApiError.Category.INPUT_FORMAT, error.category());
    }

    @Test
    void rejectsNonDenseOrdinalSequenceSoDropsCannotBeHidden() {
        List<Object> frames = List.of(
                Map.of("ordinal", 0, "frameId", "f0"),
                Map.of("ordinal", 2, "frameId", "f2"));
        ApiError error = assertThrows(ApiError.class,
                () -> FrameTable.fromImport(Map.of("videoId", "v", "frames", frames)));
        assertTrue(error.getMessage().contains("dense"));
    }

    @Test
    void edgeDistanceUsesFrameOrdinalsNotMilliseconds() {
        FrameTable table = table(20);
        Temporal.Bound s1 = table.resolve(Map.of("frameId", "f1"));
        Temporal.Bound e1 = table.resolve(Map.of("frameId", "f5"));
        Temporal.Bound s2 = table.resolve(Map.of("frameId", "f2"));
        Temporal.Bound e2 = table.resolve(Map.of("frameId", "f6"));
        Temporal.Window a = new Temporal.Window(s1, e1);
        Temporal.Window b = new Temporal.Window(s2, e2);
        assertEquals(1, a.edgeDistance(b));
        assertTrue(a.overlaps(b));
    }
}
