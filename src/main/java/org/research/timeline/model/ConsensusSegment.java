package org.research.timeline.model;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Consensus layer content. Accepting a candidate never mutates source segments;
 * it creates a new versioned consensus segment. Splits/merges supersede earlier
 * versions instead of deleting them, which keeps every decision auditable.
 */
public record ConsensusSegment(
        String consensusId,
        int version,
        String status,
        String text,
        Temporal.Window window,
        List<String> evidenceSegmentIds,
        List<String> evidenceCandidateIds,
        List<String> supersedes,
        List<String> children,
        String poseFingerprint,
        String decisionId,
        String actor,
        long eventSeq,
        String note) {

    public ConsensusSegment withChildren(List<String> newChildren) {
        return new ConsensusSegment(consensusId, version, status, text, window, evidenceSegmentIds,
                evidenceCandidateIds, supersedes, List.copyOf(newChildren), poseFingerprint,
                decisionId, actor, eventSeq, note);
    }

    public Map<String, Object> toJson() {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("consensusId", consensusId);
        map.put("version", version);
        map.put("status", status);
        map.put("text", text);
        map.put("window", window.toJson());
        map.put("evidenceSegmentIds", evidenceSegmentIds);
        map.put("evidenceCandidateIds", evidenceCandidateIds);
        map.put("supersedes", supersedes);
        map.put("children", children);
        if (poseFingerprint != null) {
            map.put("poseFingerprint", poseFingerprint);
        }
        map.put("decisionId", decisionId);
        map.put("actor", actor);
        map.put("note", note == null ? "" : note);
        return map;
    }

    public static List<Object> toJsonList(List<ConsensusSegment> segments) {
        List<Object> out = new ArrayList<>();
        for (ConsensusSegment segment : segments) {
            out.add(segment.toJson());
        }
        return out;
    }
}
