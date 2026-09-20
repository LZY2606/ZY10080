package org.research.timeline.store;

import java.util.List;
import java.util.Map;
import org.research.timeline.model.ConsensusSegment;
import org.research.timeline.model.FrameTable;
import org.research.timeline.model.Temporal;

/** Encodes/decodes consensus segments as locator-based JSON (fuzzy windows stay fuzzy on re-import). */
final class ConsensusCodec {

    private ConsensusCodec() {
    }

    @SuppressWarnings("unchecked")
    static ConsensusSegment decode(Map<String, Object> json) {
        String id = String.valueOf(json.get("consensusId"));
        int version = ((Number) json.get("version")).intValue();
        String status = String.valueOf(json.get("status"));
        String text = String.valueOf(json.get("text"));
        Temporal.Window window = WindowCodec.decode((Map<String, Object>) json.get("window"));
        List<String> evidenceSegments = (List<String>) json.getOrDefault("evidenceSegmentIds", List.of());
        List<String> evidenceCandidates = (List<String>) json.getOrDefault("evidenceCandidateIds", List.of());
        List<String> supersedes = (List<String>) json.getOrDefault("supersedes", List.of());
        List<String> children = (List<String>) json.getOrDefault("children", List.of());
        String fingerprint = json.get("poseFingerprint") == null ? null : String.valueOf(json.get("poseFingerprint"));
        String decisionId = String.valueOf(json.get("decisionId"));
        String actor = String.valueOf(json.get("actor"));
        long eventSeq = ((Number) json.getOrDefault("eventSeq", 0)).longValue();
        String note = json.get("note") == null ? null : String.valueOf(json.get("note"));
        return new ConsensusSegment(id, version, status, text, window, evidenceSegments, evidenceCandidates,
                supersedes, children, fingerprint, decisionId, actor, eventSeq, note);
    }

    static Map<String, Object> encode(ConsensusSegment segment) {
        return segment.toJson();
    }
}
