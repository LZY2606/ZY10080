package org.research.timeline;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class AlignmentTest {

    @Test
    @SuppressWarnings("unchecked")
    void candidatesPairOverlappingSegmentsWithinToleranceAndReportGaps() {
        try (TestHarness h = TestHarness.create()) {
            h.seedFrames(60);
            h.seedTwoSources();
            Map<String, Object> result = h.generate(2);
            List<Map<String, Object>> candidates = (List<Map<String, Object>>) result.get("candidates");
            List<Map<String, Object>> gaps = (List<Map<String, Object>>) result.get("gaps");

            assertTrue(candidates.size() >= 2, "pointing and fuzzy turn should pair");
            boolean hasPointing = candidates.stream().anyMatch(c ->
                    "equal".equals(c.get("relation")) || "partial".equals(c.get("relation")));
            assertTrue(hasPointing);
            candidates.stream().map(c -> c.get("unionWindow")).forEach(w ->
                    assertTrue(((Map<String, Object>) w).containsKey("start")));
            assertFalse(gaps.isEmpty(), "uncovered frame ranges must be reported as gaps");
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    void zeroToleranceExcludesOneFrameOffset() {
        try (TestHarness h = TestHarness.create()) {
            h.seedFrames(60);
            h.seedTwoSources();
            Map<String, Object> strict = h.generate(0);
            List<Map<String, Object>> candidates = (List<Map<String, Object>>) strict.get("candidates");
            // f4-f9 vs f5-f10 is one frame apart at each edge: excluded with tolerance 0.
            assertTrue(candidates.stream().noneMatch(c ->
                    ((Number) ((Map<String, Object>) ((Map<String, Object>) c.get("unionWindow"))
                            .get("start")).get("ordinalLo")).intValue() == 4
                    && "equal".equals(c.get("relation"))));
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    void fuzzyPairingStaysFuzzyAndKeepsWidestRange() {
        try (TestHarness h = TestHarness.create()) {
            h.seedFrames(60);
            h.seedTwoSources();
            Map<String, Object> result = h.generate(3);
            Map<String, Object> fuzzyCandidate = ((List<Map<String, Object>>) result.get("candidates")).stream()
                    .filter(c -> {
                        Map<String, Object> window = (Map<String, Object>) c.get("unionWindow");
                        Map<String, Object> start = (Map<String, Object>) window.get("start");
                        Map<String, Object> end = (Map<String, Object>) window.get("end");
                        return Boolean.FALSE.equals(start.get("precise"))
                                && Boolean.FALSE.equals(end.get("precise"))
                                && ((Number) start.get("ordinalLo")).intValue() >= 15;
                    })
                    .findFirst().orElseThrow(() -> new AssertionError("no fully fuzzy candidate produced"));
            Map<String, Object> start = (Map<String, Object>)
                    ((Map<String, Object>) fuzzyCandidate.get("unionWindow")).get("start");
            assertEquals(17, ((Number) start.get("ordinalLo")).intValue());
            assertEquals(20, ((Number) start.get("ordinalHi")).intValue());
            assertEquals("f017", start.get("frameIdLo"));
        }
    }
}
