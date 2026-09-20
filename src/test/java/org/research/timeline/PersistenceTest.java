package org.research.timeline;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.research.timeline.service.ApiError;
import org.research.timeline.service.ApiService;
import org.research.timeline.store.Journal;
import org.research.timeline.store.Store;
import org.research.timeline.util.Json;

class PersistenceTest {

    private Map<String, Object> framePayload() {
        List<Object> frames = new ArrayList<>();
        long nanos = 0;
        for (int i = 0; i < 60; i++) {
            nanos += (i % 4 == 0) ? 60_000_000L : 30_000_000L;
            frames.add(Map.of(
                    "ordinal", i,
                    "frameId", "f" + String.format("%03d", i),
                    "timecode", String.format("00:00:00.%03d", nanos / 1_000_000),
                    "timeNanos", nanos));
        }
        return Map.of("requestId", "frames-1", "videoId", "vid", "frames", frames);
    }

    private Map<String, Object> segmentsPayload(String requestId, String sourceId, String researcher) {
        boolean li = "src-li".equals(sourceId);
        return Map.of("requestId", requestId, "sourceId", sourceId, "researcher", researcher,
                "segments", List.of(
                        Map.of("rawText", li ? "points at board" : "pointing gesture 5-10",
                                "start", Map.of("frameId", li ? "f004" : "f005"),
                                "end", Map.of("frameId", li ? "f009" : "f010")),
                        Map.of("rawText", li ? "turns around ~18-23" : "turn 18-22 uncertain",
                                "start", Map.of("fuzzyBetween", Map.of(
                                        "from", Map.of("frameId", li ? "f017" : "f018"),
                                        "to", Map.of("frameId", li ? "f019" : "f020"))),
                                "end", Map.of("fuzzyBetween", Map.of(
                                        "from", Map.of("frameId", li ? "f021" : "f020"),
                                        "to", Map.of("frameId", li ? "f023" : "f022")))),
                        Map.of("rawText", li ? "bows at end" : "walks off 30-38",
                                "start", Map.of("frameId", li ? "f045" : "f030"),
                                "end", Map.of("frameId", li ? "f050" : "f038"))));
    }

    private Store seed(Store store) {
        ApiService api = new ApiService(store);
        Map<String, Object> frames = framePayload();
        api.importFrameTable(frames, Json.write(frames));
        api.importSegments(segmentsPayload("seg-1", "src-li", "Li"), "{}");
        api.importSegments(segmentsPayload("seg-2", "src-watanabe", "Watanabe"), "{}");
        return store;
    }

    @Test
    void replayWithSameRequestIdDoesNotCreateSecondResult() {
        try (TestHarness h = TestHarness.create()) {
            h.seedFrames(30);
            Map<String, Object> body = Map.of(
                    "requestId", "fixed-segments",
                    "sourceId", "src-x", "researcher", "X",
                    "segments", List.of(Map.of("rawText", "hello",
                            "start", h.frameRef(1), "end", h.frameRef(5))));
            String raw = Json.write(body);
            h.api.importSegments(body, raw);
            h.api.importSegments(body, raw);
            assertEquals(1, ((List<Object>) h.api.sources().get("segments")).size());
        }
    }

    @Test
    void sameRequestIdWithDifferentBodyIsConflict() {
        try (TestHarness h = TestHarness.create()) {
            h.seedFrames(30);
            Map<String, Object> first = Map.of("requestId", "same-id",
                    "sourceId", "s1", "researcher", "X",
                    "segments", List.of(Map.of("rawText", "a",
                            "start", h.frameRef(1), "end", h.frameRef(2))));
            Map<String, Object> second = Map.of("requestId", "same-id",
                    "sourceId", "s2", "researcher", "Y",
                    "segments", List.of(Map.of("rawText", "b",
                            "start", h.frameRef(1), "end", h.frameRef(2))));
            h.api.importSegments(first, Json.write(first));
            ApiError error = assertThrows(ApiError.class,
                    () -> h.api.importSegments(second, Json.write(second)));
            assertEquals(ApiError.Category.STATE_CONFLICT, error.category());
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    void restartRebuildsStateFromJournal() {
        Path dir = Path.of("build", "test-data", "restart-" + java.util.UUID.randomUUID());
        String candidateId;
        try (Store store = new Store(dir)) {
            seed(store);
            ApiService api = new ApiService(store);
            Map<String, Object> alignment = api.generateAlignment(
                    Map.of("requestId", "align-1", "toleranceFrames", 2));
            List<Map<String, Object>> candidates =
                    (List<Map<String, Object>>) alignment.get("candidates");
            candidateId = String.valueOf(candidates.get(0).get("candidateId"));
            api.decide(Map.of("requestId", "dec-1", "action", "accept",
                    "candidateId", candidateId, "text", "consensus text"), "{}");
        }
        try (Store reopened = new Store(dir)) {
            assertEquals(6, reopened.state().sources.size());
            assertEquals(1, reopened.state().alignments.size());
            long active = reopened.state().consensus.values().stream()
                    .filter(c -> "active".equals(c.status())).count();
            assertEquals(1, active);
        }
    }

    @Test
    void tornFinalJournalLineIsQuarantinedAndNotExposed() throws IOException {
        Path dir = Path.of("build", "test-data", "torn-" + java.util.UUID.randomUUID());
        Files.createDirectories(dir.resolve("events"));
        Path journal = dir.resolve("events").resolve("journal.log");
        String good = "{\"seq\":1,\"type\":\"x\",\"requestId\":\"r1\"}\n";
        Files.writeString(journal, good + "{\"seq\":2,\"type\":\"x\",", StandardCharsets.UTF_8);
        List<Map<String, Object>> recovered = Journal.recover(journal, dir.resolve("quarantine"));
        assertEquals(1, recovered.size());
        assertTrue(Files.list(dir.resolve("quarantine")).findAny().isPresent());
        assertEquals(good, Files.readString(journal));
    }

    @Test
    void rawEventsAndDerivedAreInSeparateDirectories() throws IOException {
        try (TestHarness h = TestHarness.create()) {
            h.seedFrames(10);
            h.api.importSegments(Map.of("requestId", "dir-seg", "sourceId", "src-dir",
                    "researcher", "X", "segments", List.of(Map.of("rawText", "z",
                            "start", h.frameRef(0), "end", h.frameRef(2)))), "{}");
            assertTrue(Files.isDirectory(h.dir.resolve("raw")));
            assertTrue(Files.isDirectory(h.dir.resolve("events")));
            assertTrue(Files.isDirectory(h.dir.resolve("derived")));
            assertTrue(Files.list(h.dir.resolve("raw")).count() >= 2);
            assertTrue(Files.exists(h.dir.resolve("events").resolve("journal.log")));
            assertTrue(Files.exists(h.dir.resolve("derived").resolve("state.snapshot.json")));
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    void exportReimportRoundTripPreservesFuzzyWindowsAndLineage() {
        try (TestHarness first = TestHarness.create()) {
            first.seedFrames(60);
            first.seedTwoSources();
            String poseId = first.seedPose();
            Map<String, Object> alignment = first.generateWithPose(3, poseId);
            String candidateId = String.valueOf(
                    ((List<Map<String, Object>>) alignment.get("candidates")).get(0).get("candidateId"));
            first.api.decide(Map.of("requestId", "dec-acc", "action", "accept",
                    "candidateId", candidateId, "text", "共识指向"), "{}");
            Map<String, Object> bundle = first.api.exportBundle();

            Path secondDir = Path.of("build", "test-data", "roundtrip-" + java.util.UUID.randomUUID());
            try (Store store2 = new Store(secondDir)) {
                ApiService api2 = new ApiService(store2);
                Map<String, Object> result = api2.reimport(
                        Map.of("requestId", "reimport-1", "bundle", bundle));
                assertTrue(((Number) result.get("events")).intValue() >= 5);
                Map<String, Object> state2 = api2.state();
                boolean fuzzySurvived = ((List<Object>) state2.get("sources")).stream().anyMatch(s -> {
                    Map<String, Object> seg = (Map<String, Object>) s;
                    Map<String, Object> w = (Map<String, Object>) seg.get("window");
                    return Boolean.FALSE.equals(((Map<String, Object>) w.get("start")).get("precise"));
                });
                assertTrue(fuzzySurvived, "fuzzy intervals must not be collapsed to precise points");
                List<Map<String, Object>> consensusList =
                        (List<Map<String, Object>>) state2.get("consensus");
                Map<String, Object> consensus = consensusList.stream()
                        .filter(c -> "active".equals(c.get("status"))).findFirst().orElseThrow();
                assertTrue(consensus.get("poseFingerprint") != null
                        && !String.valueOf(consensus.get("poseFingerprint")).isBlank());
                assertEquals("共识指向", consensus.get("text"));
            }
        }
    }

    @Test
    void inputFormatErrorIsDistinctFromConflict() {
        try (TestHarness h = TestHarness.create()) {
            ApiError noFrames = assertThrows(ApiError.class,
                    () -> h.api.importFrameTable(Map.of("requestId", "r"), "{}"));
            assertEquals(ApiError.Category.INPUT_FORMAT, noFrames.category());
            ApiError order = assertThrows(ApiError.class,
                    () -> h.api.importSegments(Map.of("requestId", "r", "sourceId", "s",
                            "researcher", "x", "segments", List.of()), "{}"));
            assertEquals(ApiError.Category.STATE_CONFLICT, order.category(),
                    "segments before frame table is a state conflict");
        }
    }
}
