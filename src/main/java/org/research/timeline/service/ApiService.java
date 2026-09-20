package org.research.timeline.service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.research.timeline.model.ConsensusSegment;
import org.research.timeline.model.SourceSegment;
import org.research.timeline.store.Store;
import org.research.timeline.util.Json;
import org.research.timeline.util.Maps;

/** Server-side facade. No alignment or decision judgment exists in the browser. */
public final class ApiService {

    private final Store store;
    private final ImportService importService;
    private final AlignmentService alignmentService;
    private final ConsensusService consensusService;

    public ApiService(Store store) {
        this.store = store;
        this.importService = new ImportService(store);
        this.alignmentService = new AlignmentService(store);
        this.consensusService = new ConsensusService(store);
    }

    public Map<String, Object> importFrameTable(Map<String, Object> payload, String rawBody) {
        return importService.importFrameTable(payload, rawBody);
    }

    public Map<String, Object> importSegments(Map<String, Object> payload, String rawBody) {
        return importService.importSegments(payload, rawBody);
    }

    public Map<String, Object> importPose(Map<String, Object> payload, String rawBody) {
        return importService.importPose(payload, rawBody);
    }

    public Map<String, Object> generateAlignment(Map<String, Object> payload) {
        String requestId = Maps.str(payload, "requestId");
        int tolerance = Maps.integer(payload, "toleranceFrames");
        String poseId = payload.containsKey("poseId") ? Maps.str(payload, "poseId") : null;
        String rawBody = Json.write(payload);
        // Idempotency is enforced before deriving; the derived snapshot is regenerable anyway.
        Object replay = store.idempotentReplay(requestId, "alignment_generated",
                org.research.timeline.util.Hash.sha256(rawBody));
        if (replay != null) {
            return Maps.asObj(replay, "replay");
        }
        Map<String, Object> result = alignmentService.generate(tolerance, poseId);
        Map<String, Object> eventData = Map.of("result", result, "toleranceFrames", tolerance);
        return store.commitOperationEvent("alignment_generated", requestId, rawBody, eventData, result);
    }

    public Map<String, Object> decide(Map<String, Object> payload, String rawBody) {
        return consensusService.decide(payload, rawBody);
    }

    public Map<String, Object> sources() {
        Map<String, Object> out = new LinkedHashMap<>();
        List<Object> list = new ArrayList<>();
        for (SourceSegment segment : store.state().sources.values()) {
            list.add(segment.toJson());
        }
        out.put("segments", list);
        return out;
    }

    public Map<String, Object> consensus() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("segments", ConsensusSegment.toJsonList(new ArrayList<>(store.state().consensus.values())));
        return out;
    }

    public Map<String, Object> state() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("frameTable", store.state().frameTable == null ? null : store.state().frameTable.toJson());
        out.put("sources", sources().get("segments"));
        out.put("poses", store.state().poses.values().stream().map(p -> p.toJson()).toList());
        out.put("alignments", store.state().alignments.values().stream().map(a -> a.toJson()).toList());
        out.put("consensus", ConsensusSegment.toJsonList(new ArrayList<>(store.state().consensus.values())));
        out.put("eventCount", store.state().operationLog.size());
        return out;
    }

    public Map<String, Object> exportBundle() {
        return importService.export();
    }

    /**
     * Re-import an exported bundle into an empty data directory by replaying its
     * authoritative event log. Events carry the original locators (including
     * fuzzyBetween ranges), candidate ids, lineage and pose fingerprints, so a
     * round trip cannot collapse fuzzy intervals into precise time points.
     */
    @SuppressWarnings("unchecked")
    public Map<String, Object> reimport(Map<String, Object> payload) {
        String requestId = Maps.str(payload, "requestId");
        Map<String, Object> bundle = Maps.obj(payload, "bundle");
        if (store.state().frameTable != null || !store.state().sources.isEmpty()
                || !store.state().operationLog.isEmpty()) {
            throw ApiError.conflict("Re-import is only allowed into an empty data directory");
        }
        List<Object> events = Maps.list(bundle, "events");
        for (Object item : events) {
            Map<String, Object> event = Maps.asObj(item, "events[]");
            store.replayHistoricalEvent(event);
        }
        return Map.of("reimported", true,
                "events", events.size(),
                "requestId", requestId);
    }

}
