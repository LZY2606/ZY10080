package org.research.timeline.service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.research.timeline.model.ConsensusSegment;
import org.research.timeline.model.FrameTable;
import org.research.timeline.model.PoseData;
import org.research.timeline.model.SourceSegment;
import org.research.timeline.model.Temporal;
import org.research.timeline.store.Store;
import org.research.timeline.util.Hash;
import org.research.timeline.util.Json;
import org.research.timeline.util.Maps;

/**
 * Import handling. Original locators are stored verbatim in raw files and events;
 * fuzzy locators are therefore preserved exactly through export/re-import.
 */
public final class ImportService {

    private final Store store;

    public ImportService(Store store) {
        this.store = store;
    }

    public Map<String, Object> importFrameTable(Map<String, Object> payload, String rawBody) {
        String requestId = Maps.str(payload, "requestId");
        FrameTable table = FrameTable.fromImport(payload);
        if (store.state().frameTable != null
                && !store.state().frameTable.videoId().equals(table.videoId())) {
            throw ApiError.conflict("A frame table for video '" + store.state().frameTable.videoId()
                    + "' already exists; use a fresh data directory for another video");
        }
        Map<String, Object> response = table.toJson();
        String fileName = "frame-table-" + table.videoId() + "-" + Hash.shortHash(rawBody) + ".json";
        Map<String, Object> eventData = Map.of("frameTable", table.toJson(), "videoId", table.videoId());
        return store.commitInputEvent("frame_table_imported", requestId, fileName, rawBody, eventData, response);
    }

    @SuppressWarnings("unchecked")
    public Map<String, Object> importSegments(Map<String, Object> payload, String rawBody) {
        String requestId = Maps.str(payload, "requestId");
        // Idempotent replay must short-circuit before any state-dependent validation,
        // otherwise reposting the same accepted request would look like a conflict.
        String bodyHash = org.research.timeline.util.Hash.sha256(rawBody);
        Object replay = store.idempotentReplay(requestId, "segments_imported", bodyHash);
        if (replay != null) {
            return (Map<String, Object>) replay;
        }
        if (store.state().frameTable == null) {
            throw ApiError.conflict("Import the frame table before researcher segments");
        }
        String sourceId = Maps.str(payload, "sourceId");
        if (store.state().sources.values().stream().anyMatch(s -> s.sourceId().equals(sourceId))) {
            throw ApiError.conflict("sourceId '" + sourceId + "' has already been imported; "
                    + "each researcher write-up is one independent source");
        }
        String researcher = Maps.str(payload, "researcher");
        List<Object> rawSegments = Maps.list(payload, "segments");
        if (rawSegments.isEmpty()) {
            throw ApiError.input("segments must not be empty");
        }
        FrameTable table = store.state().frameTable;

        // Resolve everything first so a bad locator rejects the whole import atomically.
        record Parsed(Map<String, Object> stored, SourceSegment segment) {
        }
        List<Parsed> parsedList = new ArrayList<>();
        for (int i = 0; i < rawSegments.size(); i++) {
            Map<String, Object> raw = Maps.asObj(rawSegments.get(i), "segments[]");
            Map<String, Object> startLocator = Maps.obj(raw, "start");
            Map<String, Object> endLocator = Maps.obj(raw, "end");
            Temporal.Bound start = table.resolve(startLocator);
            Temporal.Bound end = table.resolve(endLocator);
            if (start.lo() > end.hi()) {
                throw ApiError.input("Segment #" + (i + 1) + " starts after it ends");
            }
            String rawText = Maps.str(raw, "rawText");
            String providedId = raw.containsKey("segmentId") ? Maps.str(raw, "segmentId") : null;
            String segmentId = providedId != null ? providedId
                    : "seg-" + Hash.shortHash(sourceId + i + rawText);
            if (store.state().sources.containsKey(segmentId)) {
                throw ApiError.conflict("segmentId '" + segmentId + "' already exists");
            }
            Map<String, Object> stored = new LinkedHashMap<>();
            stored.put("segmentId", segmentId);
            stored.put("sourceId", sourceId);
            stored.put("researcher", researcher);
            stored.put("rawText", rawText);
            stored.put("start", startLocator);
            stored.put("end", endLocator);
            Temporal.Window window = new Temporal.Window(start, end);
            parsedList.add(new Parsed(stored, new SourceSegment(segmentId, sourceId, researcher, rawText, window)));
        }

        List<Object> eventSegments = new ArrayList<>();
        List<Object> responseSegments = new ArrayList<>();
        for (Parsed parsed : parsedList) {
            eventSegments.add(Map.of("stored", parsed.stored()));
            responseSegments.add(parsed.segment().toJson());
        }
        Map<String, Object> eventData = Map.of(
                "sourceId", sourceId,
                "researcher", researcher,
                "segments", eventSegments);
        Map<String, Object> response = Map.of(
                "sourceId", sourceId,
                "researcher", researcher,
                "segments", responseSegments);
        String fileName = "segments-" + sourceId + "-" + Hash.shortHash(rawBody) + ".json";
        return store.commitInputEvent("segments_imported", requestId, fileName, rawBody, eventData, response);
    }

    public Map<String, Object> importPose(Map<String, Object> payload, String rawBody) {
        String requestId = Maps.str(payload, "requestId");
        if (store.state().frameTable == null) {
            throw ApiError.conflict("Import the frame table before pose data");
        }
        String poseId = "pose-" + Hash.shortHash(Maps.str(payload, "algorithm")
                + Maps.str(payload, "algorithmVersion") + rawBody);
        if (store.state().poses.containsKey(poseId)) {
            throw ApiError.conflict("Identical pose payload already imported as " + poseId);
        }
        PoseData pose = PoseData.fromImport(poseId, payload);
        Map<String, Object> eventData = Map.of("poseId", poseId, "payload", payload);
        Map<String, Object> response = pose.toJson();
        return store.commitInputEvent("pose_imported", requestId,
                "pose-" + poseId + ".json", rawBody, eventData, response);
    }

    /**
     * Full hierarchical export. Includes every decision's evidence references
     * and precision metadata. Re-importing this bundle preserves fuzzy windows.
     */
    public Map<String, Object> export() {
        var state = store.state();
        Map<String, Object> bundle = new LinkedHashMap<>();
        bundle.put("exportVersion", 1);
        bundle.put("frameTable", state.frameTable == null ? null : state.frameTable.toJson());

        List<Object> sources = new ArrayList<>();
        for (SourceSegment segment : state.sources.values()) {
            sources.add(segment.toJson());
        }
        bundle.put("sources", sources);

        List<Object> poses = new ArrayList<>();
        for (PoseData pose : state.poses.values()) {
            poses.add(pose.toJson());
        }
        bundle.put("poses", poses);

        List<Object> alignments = new ArrayList<>();
        for (var alignment : state.alignments.values()) {
            alignments.add(alignment.toJson());
        }
        bundle.put("derivedAlignments", alignments);

        List<Object> decisions = new ArrayList<>();
        List<Object> events = new ArrayList<>();
        for (Map<String, Object> event : state.operationLog) {
            events.add(event);
            if ("consensus_decision".equals(event.get("type"))) {
                decisions.add(event);
            }
        }
        bundle.put("decisions", decisions);
        // The event log is the authoritative replay source: fuzzy locators, candidate ids,
        // lineage and pose fingerprints all survive a round trip without recomputation.
        bundle.put("events", events);

        List<Object> consensus = ConsensusSegment.toJsonList(new ArrayList<>(state.consensus.values()));
        bundle.put("consensus", consensus);
        return bundle;
    }
}
