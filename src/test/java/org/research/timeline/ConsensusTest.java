package org.research.timeline;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.research.timeline.service.ApiError;

class ConsensusTest {

    @SuppressWarnings("unchecked")
    private Map<String, Object> firstCandidate(TestHarness h, int tolerance) {
        Map<String, Object> alignment = h.generate(tolerance);
        return ((List<Map<String, Object>>) alignment.get("candidates")).get(0);
    }

    @Test
    void acceptCreatesConsensusLayerAndNeverMutatesSources() {
        try (TestHarness h = TestHarness.create()) {
            h.seedFrames(60);
            h.seedTwoSources();
            Map<String, Object> candidate = firstCandidate(h, 2);
            String before = h.api.sources().toString();
            Map<String, Object> decision = h.api.decide(Map.of(
                    "requestId", h.rid(),
                    "action", "accept",
                    "candidateId", candidate.get("candidateId"),
                    "text", "共识：指向动作",
                    "actor", "lead"), "{}");
            Map<String, Object> consensus = (Map<String, Object>) decision.get("consensus");
            assertEquals("active", consensus.get("status"));
            assertEquals(1, ((Number) consensus.get("version")).intValue());
            assertEquals(before, h.api.sources().toString(), "source layer is immutable");
            assertTrue(((List<Object>) consensus.get("evidenceSegmentIds")).size() >= 2);
        }
    }

    @Test
    void overlappingEditsWithDifferentTextConflictAndReturnBothSides() {
        try (TestHarness h = TestHarness.create()) {
            h.seedFrames(60);
            h.seedTwoSources();
            Map<String, Object> candidate = firstCandidate(h, 2);
            String candidateId = String.valueOf(candidate.get("candidateId"));
            h.api.decide(Map.of("requestId", h.rid(), "action", "accept",
                    "candidateId", candidateId, "text", "版本甲", "actor", "甲"), "{}");
            ApiError error = assertThrows(ApiError.class, () -> h.api.decide(Map.of(
                    "requestId", h.rid(), "action", "accept",
                    "candidateId", candidateId, "text", "版本乙", "actor", "乙"), "{}"));
            assertEquals(ApiError.Category.STATE_CONFLICT, error.category());
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> conflicts =
                    (List<Map<String, Object>>) error.details().get("conflicts");
            assertEquals(1, conflicts.size());
            assertEquals("版本甲", conflicts.get(0).get("text"));
            assertTrue(conflicts.get(0).containsKey("overlapWindow"));
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    void nonOverlappingEditsMergeAutomatically() {
        try (TestHarness h = TestHarness.create()) {
            h.seedFrames(60);
            h.seedTwoSources();
            List<Map<String, Object>> candidates =
                    (List<Map<String, Object>>) h.generate(2).get("candidates");
            assertEquals(2, candidates.size(), "pointing pair and turn pair");
            h.api.decide(Map.of("requestId", h.rid(), "action", "accept",
                    "candidateId", candidates.get(0).get("candidateId"),
                    "text", "指向", "actor", "甲"), "{}");
            h.api.decide(Map.of("requestId", h.rid(), "action", "accept",
                    "candidateId", candidates.get(1).get("candidateId"),
                    "text", "转身", "actor", "乙"), "{}");
            long active = h.api.consensus().values().stream().count() > 0
                    ? ((List<Object>) ((Map<String, Object>) h.api.consensus()).get("segments")).stream()
                    .filter(s -> "active".equals(((Map<String, Object>) s).get("status"))).count()
                    : 0;
            assertEquals(2, active);
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    void splitSupersedesParentAndKeepsContiguousChildren() {
        try (TestHarness h = TestHarness.create()) {
            h.seedFrames(60);
            h.seedTwoSources();
            Map<String, Object> candidate = firstCandidate(h, 2);
            Map<String, Object> accepted = (Map<String, Object>) h.api.decide(Map.of(
                    "requestId", h.rid(), "action", "accept",
                    "candidateId", candidate.get("candidateId"),
                    "text", "整段", "actor", "lead"), "{}").get("consensus");
            Map<String, Object> window = (Map<String, Object>) accepted.get("window");
            Map<String, Object> start = (Map<String, Object>) window.get("start");
            Map<String, Object> end = (Map<String, Object>) window.get("end");
            int lo = ((Number) start.get("ordinalLo")).intValue();
            int hi = ((Number) end.get("ordinalHi")).intValue();
            int mid = lo + 2;
            Map<String, Object> splitResult = h.api.decide(Map.of(
                    "requestId", h.rid(), "action", "split",
                    "consensusId", accepted.get("consensusId"),
                    "baseVersion", 1,
                    "parts", List.of(
                            Map.of("text", "前", "start", h.frameRef(lo), "end", h.frameRef(mid - 1)),
                            Map.of("text", "后", "start", h.frameRef(mid), "end", h.frameRef(hi)))),
                    "{}");
            List<Map<String, Object>> parts = (List<Map<String, Object>>) splitResult.get("parts");
            assertEquals(2, parts.size());
            Map<String, Object> parent = (Map<String, Object>) splitResult.get("superseded");
            assertEquals("superseded", parent.get("status"));
            assertEquals(2, ((List<Object>) parent.get("children")).size());
        }
    }

    @Test
    void mergeAcrossGapIsRejected() {
        try (TestHarness h = TestHarness.create()) {
            h.seedFrames(60);
            h.seedTwoSources();
            // A third researcher also annotates the distant bow, producing a far-away candidate
            // (45-50) that cannot be merged with the pointing candidate (4-10) across the gap.
            h.api.importSegments(Map.of("requestId", h.rid(), "sourceId", "src-zhao",
                    "researcher", "Zhao",
                    "segments", List.of(Map.of("rawText", "ending bow 45-50",
                            "start", h.frameRef(45), "end", h.frameRef(50)))), "{}");
            List<Map<String, Object>> candidates =
                    (List<Map<String, Object>>) h.generate(5).get("candidates");
            Map<String, Object> pointing = candidates.stream()
                    .filter(c -> isUnionAt(c, 4, 10)).findFirst().orElseThrow();
            Map<String, Object> bow = candidates.stream()
                    .filter(c -> isUnionAt(c, 45, 50)).findFirst().orElseThrow();
            Map<String, Object> first = (Map<String, Object>) h.api.decide(Map.of(
                    "requestId", h.rid(), "action", "accept",
                    "candidateId", pointing.get("candidateId"),
                    "text", "指向"), "{}").get("consensus");
            Map<String, Object> distant = (Map<String, Object>) h.api.decide(Map.of(
                    "requestId", h.rid(), "action", "accept",
                    "candidateId", bow.get("candidateId"),
                    "text", "鞠躬"), "{}").get("consensus");
            ApiError error = assertThrows(ApiError.class, () -> h.api.decide(Map.of(
                    "requestId", h.rid(), "action", "merge",
                    "segments", List.of(
                            Map.of("consensusId", first.get("consensusId"), "baseVersion", 1),
                            Map.of("consensusId", distant.get("consensusId"), "baseVersion", 1)),
                    "text", "硬合并"), "{}"));
            // The segments do not touch; refusing is an input-format rejection that names the gap,
            // text is never stretched over uncovered frames.
            assertEquals(ApiError.Category.INPUT_FORMAT, error.category());
            assertTrue(error.getMessage().contains("gap"));
        }
    }

    private static boolean isUnionAt(Map<String, Object> candidate, int lo, int hi) {
        Map<String, Object> window = (Map<String, Object>) candidate.get("unionWindow");
        int actualLo = ((Number) ((Map<String, Object>) window.get("start")).get("ordinalLo")).intValue();
        int actualHi = ((Number) ((Map<String, Object>) window.get("end")).get("ordinalHi")).intValue();
        return actualLo == lo && actualHi == hi;
    }

    @Test
    void staleVersionOnSplitIsStateConflict() {
        try (TestHarness h = TestHarness.create()) {
            h.seedFrames(60);
            h.seedTwoSources();
            Map<String, Object> candidate = firstCandidate(h, 2);
            Map<String, Object> accepted = (Map<String, Object>) h.api.decide(Map.of(
                    "requestId", h.rid(), "action", "accept",
                    "candidateId", candidate.get("candidateId"), "text", "X"), "{}").get("consensus");
            String id = String.valueOf(accepted.get("consensusId"));
            Map<String, Object> window = (Map<String, Object>) accepted.get("window");
            int lo = ((Number) ((Map<String, Object>) window.get("start")).get("ordinalLo")).intValue();
            int hi = ((Number) ((Map<String, Object>) window.get("end")).get("ordinalHi")).intValue();
            int mid = lo + 1;
            Map<String, Object> splitBody = Map.of(
                    "requestId", "split-first", "action", "split",
                    "consensusId", id, "baseVersion", 1,
                    "parts", List.of(
                            Map.of("text", "a", "start", h.frameRef(lo), "end", h.frameRef(mid)),
                            Map.of("text", "b", "start", h.frameRef(mid + 1), "end", h.frameRef(hi))));
            h.api.decide(splitBody, "{}");
            Map<String, Object> staleBody = Map.of(
                    "requestId", "split-stale", "action", "split",
                    "consensusId", id, "baseVersion", 1,
                    "parts", List.of(
                            Map.of("text", "c", "start", h.frameRef(lo), "end", h.frameRef(mid)),
                            Map.of("text", "d", "start", h.frameRef(mid + 1), "end", h.frameRef(hi))));
            ApiError error = assertThrows(ApiError.class, () -> h.api.decide(staleBody, "{}"));
            assertEquals(ApiError.Category.STATE_CONFLICT, error.category());
        }
    }
}
