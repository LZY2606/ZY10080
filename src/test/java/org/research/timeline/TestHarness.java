package org.research.timeline;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.research.timeline.service.ApiService;
import org.research.timeline.store.Store;
import org.research.timeline.util.Json;

/** Builds an isolated store with a VFR frame table and two researchers. */
final class TestHarness implements AutoCloseable {

    final Path dir;
    final Store store;
    final ApiService api;
    private int counter;

    private TestHarness(Path dir) {
        this.dir = dir;
        this.store = new Store(dir);
        this.api = new ApiService(store);
    }

    static TestHarness create() {
        Path dir = Path.of("build", "test-data", "harness-" + java.util.UUID.randomUUID());
        return new TestHarness(dir);
    }

    String rid() {
        return "req-" + (counter++);
    }

    void seedFrames(int n) {
        List<Object> frames = new ArrayList<>();
        long nanos = 0;
        for (int i = 0; i < n; i++) {
            nanos += (i % 4 == 0) ? 60_000_000L : 30_000_000L;
            frames.add(Map.of(
                    "ordinal", i,
                    "frameId", "f" + String.format("%03d", i),
                    "timecode", String.format("00:00:00.%03d", nanos / 1_000_000),
                    "timeNanos", nanos));
        }
        api.importFrameTable(Map.of("requestId", rid(), "videoId", "vid", "frames", frames),
                Json.write(Map.of("requestId", "seed", "videoId", "vid", "frames", frames)));
    }

    Map<String, Object> frameRef(int n) {
        return Map.of("frameId", "f" + String.format("%03d", n));
    }

    Map<String, Object> fuzzyRef(int lo, int hi) {
        return Map.of("fuzzyBetween", Map.of("from", frameRef(lo), "to", frameRef(hi)));
    }

    void seedTwoSources() {
        api.importSegments(Map.of(
                "requestId", rid(),
                "sourceId", "src-li",
                "researcher", "Li",
                "segments", List.of(
                        Map.of("rawText", "points at board",
                                "start", frameRef(4), "end", frameRef(9)),
                        Map.of("rawText", "turns around ~18-23",
                                "start", fuzzyRef(17, 19), "end", fuzzyRef(21, 23)),
                        Map.of("rawText", "bows at end",
                                "start", frameRef(45), "end", frameRef(50)))),
                "{\"sourceId\":\"src-li\"}");
        api.importSegments(Map.of(
                "requestId", rid(),
                "sourceId", "src-watanabe",
                "researcher", "Watanabe",
                "segments", List.of(
                        Map.of("rawText", "pointing gesture 5-10",
                                "start", frameRef(5), "end", frameRef(10)),
                        Map.of("rawText", "turn 18-22 uncertain",
                                "start", fuzzyRef(18, 20), "end", fuzzyRef(20, 22)),
                        Map.of("rawText", "walks off 30-38",
                                "start", frameRef(30), "end", frameRef(38)))),
                "{\"sourceId\":\"src-watanabe\"}");
    }

    String seedPose() {
        List<Object> frames = new ArrayList<>();
        for (int i = 0; i < 60; i++) {
            frames.add(Map.of("frameId", "f" + String.format("%03d", i),
                    "ordinal", i, "keypoints", List.of(Map.of("x", i, "y", 2))));
        }
        Map<String, Object> result = api.importPose(Map.of(
                "requestId", rid(),
                "algorithm", "pose-algo",
                "algorithmVersion", "v1",
                "frames", frames), "{}");
        return String.valueOf(result.get("poseId"));
    }

    @SuppressWarnings("unchecked")
    Map<String, Object> generate(int tolerance) {
        return api.generateAlignment(Map.of("requestId", rid(), "toleranceFrames", tolerance));
    }

    @SuppressWarnings("unchecked")
    Map<String, Object> generateWithPose(int tolerance, String poseId) {
        return api.generateAlignment(Map.of("requestId", rid(), "toleranceFrames", tolerance,
                "poseId", poseId));
    }

    @Override
    public void close() {
        store.close();
    }
}
