package org.research.timeline.service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.research.timeline.model.Candidate;
import org.research.timeline.model.ConsensusSegment;
import org.research.timeline.model.SourceSegment;
import org.research.timeline.model.Temporal;
import org.research.timeline.store.Store;
import org.research.timeline.util.Hash;
import org.research.timeline.util.Maps;

/**
 * Decisions produce a separate consensus layer; source layers are never mutated.
 * Optimistic versioning detects two researchers editing the same segment:
 * overlapping range + different text is returned as a 409 conflict carrying
 * both sides, while non-overlapping concurrent changes merge automatically.
 */
public final class ConsensusService {

    private final Store store;

    public ConsensusService(Store store) {
        this.store = store;
    }

    public Map<String, Object> decide(Map<String, Object> request, String rawBody) {
        String requestId = Maps.str(request, "requestId");
        String action = Maps.str(request, "action");
        String actor = Maps.optStr(request, "actor", "researcher");

        Map<String, Object> response;
        Map<String, Object> eventData = new LinkedHashMap<>();
        switch (action) {
            case "accept" -> response = accept(request, actor, eventData);
            case "split" -> response = split(request, actor, eventData);
            case "merge" -> response = merge(request, actor, eventData);
            case "unjudgable" -> response = unjudgable(request, actor, eventData);
            default -> throw ApiError.input("Unknown action '" + action
                    + "'; expected accept, split, merge or unjudgable");
        }
        return store.commitOperationEvent("consensus_decision", requestId, rawBody, eventData, response);
    }

    // ------------------------------------------------------------------
    // accept
    // ------------------------------------------------------------------

    private Map<String, Object> accept(Map<String, Object> request, String actor, Map<String, Object> eventData) {
        String candidateId = Maps.str(request, "candidateId");
        Candidate candidate = findCandidate(candidateId);
        String text = Maps.str(request, "text");
        String note = Maps.optStr(request, "note", "");
        validateWindowText(candidate.unionWindow(), text);

        for (String segmentId : candidate.segmentIds()) {
            if (!store.state().sources.containsKey(segmentId)) {
                throw ApiError.input("Candidate references unknown source segment " + segmentId);
            }
        }
        String decisionId = newDecisionId();
        String consensusId = "cs-" + Hash.shortHash(decisionId + candidateId);
        // Pose fingerprint is bound at decision time; old consensus stays on old fingerprints.
        String fingerprint = candidateFingerprint(candidate);
        ConsensusSegment segment = new ConsensusSegment(
                consensusId, 1, "active", text, candidate.unionWindow(),
                List.copyOf(candidate.segmentIds()), List.of(candidateId), List.of(), List.of(),
                fingerprint, decisionId, actor, store.nextSeq(), note);

        checkOverlappingConflicts(candidate.unionWindow(), text, null, actor);

        eventData.put("decisionId", decisionId);
        eventData.put("action", "accept");
        eventData.put("createdSegments", List.of(segment.toJson()));
        eventData.put("supersededIds", List.of());
        eventData.put("updatedParents", List.of());
        eventData.put("candidateId", candidateId);
        return Map.of("decisionId", decisionId, "consensus", segment.toJson());
    }

    private String candidateFingerprint(Candidate candidate) {
        for (var result : store.state().alignments.values()) {
            if (result.candidates().stream().anyMatch(c -> c.candidateId().equals(candidate.candidateId()))) {
                return result.poseFingerprint();
            }
        }
        return null;
    }

    private Candidate findCandidate(String candidateId) {
        for (var result : store.state().alignments.values()) {
            for (Candidate candidate : result.candidates()) {
                if (candidate.candidateId().equals(candidateId)) {
                    return candidate;
                }
            }
        }
        throw ApiError.notFound("No candidate '" + candidateId + "'; generate alignment first");
    }

    // ------------------------------------------------------------------
    // unjudgable
    // ------------------------------------------------------------------

    private Map<String, Object> unjudgable(Map<String, Object> request, String actor, Map<String, Object> eventData) {
        String reason = Maps.str(request, "reason");
        var table = store.state().frameTable;
        if (table == null) {
            throw ApiError.conflict("Frame table must be imported first");
        }
        Temporal.Window window = resolveWindow(request, table);
        String decisionId = newDecisionId();
        String consensusId = "gap-" + Hash.shortHash(decisionId + reason);
        String candidateId = request.containsKey("candidateId") ? Maps.str(request, "candidateId") : null;
        ConsensusSegment gap = new ConsensusSegment(
                consensusId, 1, "unjudgable", "", window, List.of(),
                candidateId == null ? List.of() : List.of(candidateId), List.of(), List.of(),
                candidateFingerprintIfAny(candidateId), decisionId, actor, store.nextSeq(), reason);
        eventData.put("decisionId", decisionId);
        eventData.put("action", "unjudgable");
        eventData.put("createdSegments", List.of(gap.toJson()));
        eventData.put("supersededIds", List.of());
        eventData.put("updatedParents", List.of());
        eventData.put("note", reason);
        return Map.of("decisionId", decisionId, "consensus", gap.toJson());
    }

    private String candidateFingerprintIfAny(String candidateId) {
        if (candidateId == null) {
            return null;
        }
        return candidateFingerprint(findCandidate(candidateId));
    }

    static Temporal.Window resolveWindow(Map<String, Object> request, org.research.timeline.model.FrameTable table) {
        Map<String, Object> start = Maps.obj(request, "start");
        Map<String, Object> end = Maps.obj(request, "end");
        Temporal.Bound startBound = table.resolve(start);
        Temporal.Bound endBound = table.resolve(end);
        if (startBound.lo() > endBound.hi()) {
            throw ApiError.input("Window start must not be after end");
        }
        return new Temporal.Window(startBound, endBound);
    }

    private static void validateWindowText(Temporal.Window window, String text) {
        if (text == null || text.isBlank()) {
            throw ApiError.input("Consensus text must not be empty; leave source text untouched instead");
        }
        if (window.lo() > window.hi()) {
            throw ApiError.input("Window is inverted");
        }
    }

    private String newDecisionId() {
        return "dec-" + Hash.shortHash(String.valueOf(System.nanoTime()) + store.nextSeq() + Math.random());
    }

    // ------------------------------------------------------------------
    // conflict detection (see end of file for split/merge)
    // ------------------------------------------------------------------

    /**
     * Two live consensus edits conflict only when they overlap in ordinals AND
     * carry different text. Non-overlapping changes coexist automatically.
     */
    void checkOverlappingConflicts(Temporal.Window window, String text, String ignoreId, String actor) {
        List<Map<String, Object>> conflicts = new ArrayList<>();
        for (ConsensusSegment existing : store.state().consensus.values()) {
            if (!"active".equals(existing.status())) {
                continue;
            }
            if (existing.consensusId().equals(ignoreId)) {
                continue;
            }
            if (existing.window().overlaps(window) && !existing.text().equals(text)) {
                Map<String, Object> conflict = new LinkedHashMap<>();
                conflict.put("consensusId", existing.consensusId());
                conflict.put("actor", existing.actor());
                conflict.put("text", existing.text());
                conflict.put("window", existing.window().toJson());
                conflict.put("overlapWindow", overlapJson(existing.window(), window));
                conflicts.add(conflict);
            }
        }
        if (!conflicts.isEmpty()) {
            throw ApiError.conflict("Overlapping consensus edits with different text",
                    Map.of("conflicts", conflicts, "submittedBy", actor));
        }
    }

    private Map<String, Object> overlapJson(Temporal.Window a, Temporal.Window b) {
        int lo = Math.max(a.lo(), b.lo());
        int hi = Math.min(a.hi(), b.hi());
        var table = store.state().frameTable;
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("ordinalLo", lo);
        map.put("ordinalHi", hi);
        map.put("frameIdLo", table.frameIdOf(lo));
        map.put("frameIdHi", table.frameIdOf(hi));
        return map;
    }

    // ------------------------------------------------------------------
    // split
    // ------------------------------------------------------------------

    private Map<String, Object> split(Map<String, Object> request, String actor, Map<String, Object> eventData) {
        String consensusId = Maps.str(request, "consensusId");
        int baseVersion = Maps.integer(request, "baseVersion");
        ConsensusSegment parent = requireLive(consensusId, baseVersion);
        List<Object> partList = Maps.list(request, "parts");
        if (partList.size() < 2) {
            throw ApiError.input("A split needs at least two parts");
        }
        var table = store.state().frameTable;
        List<ConsensusSegment> created = new ArrayList<>();
        int expectedStartOrdinal = parent.window().lo();
        List<String> childIds = new ArrayList<>();
        String decisionId = newDecisionId();
        int nextVersion = parent.version() + 1;

        for (int i = 0; i < partList.size(); i++) {
            Map<String, Object> part = Maps.asObj(partList.get(i), "parts[]");
            String text = Maps.str(part, "text");
            Temporal.Bound start = table.resolve(Maps.obj(part, "start"));
            Temporal.Bound end = table.resolve(Maps.obj(part, "end"));
            if (i == 0 && start.lo() != parent.window().lo()) {
                throw ApiError.input("First split part must start at the parent start frame "
                        + table.frameIdOf(parent.window().lo()));
            }
            if (i == partList.size() - 1 && end.hi() != parent.window().hi()) {
                throw ApiError.input("Last split part must end at the parent end frame "
                        + table.frameIdOf(parent.window().hi()));
            }
            if (start.lo() != expectedStartOrdinal) {
                throw ApiError.input("Split parts must be contiguous without gaps or overlaps; part "
                        + (i + 1) + " starts at ordinal " + start.lo()
                        + " but expected " + expectedStartOrdinal);
            }
            if (end.hi() < start.lo()) {
                throw ApiError.input("Split part " + (i + 1) + " is inverted");
            }
            checkOverlappingConflicts(new Temporal.Window(start, end), text, consensusId, actor);
            String childId = "cs-" + Hash.shortHash(decisionId + "-part" + i);
            childIds.add(childId);
            created.add(new ConsensusSegment(childId, nextVersion, "active", text,
                    new Temporal.Window(start, end), parent.evidenceSegmentIds(), parent.evidenceCandidateIds(),
                    List.of(consensusId), List.of(), parent.poseFingerprint(), decisionId, actor,
                    store.nextSeq(), "split of " + consensusId));
            expectedStartOrdinal = end.hi() + 1;
        }

        ConsensusSegment supersededParent = new ConsensusSegment(parent.consensusId(), parent.version(), "superseded",
                parent.text(), parent.window(), parent.evidenceSegmentIds(), parent.evidenceCandidateIds(),
                parent.supersedes(), List.copyOf(childIds), parent.poseFingerprint(), parent.decisionId(),
                parent.actor(), parent.eventSeq(), parent.note());

        eventData.put("decisionId", decisionId);
        eventData.put("action", "split");
        eventData.put("createdSegments", ConsensusSegment.toJsonList(created));
        eventData.put("supersededIds", List.of(consensusId));
        eventData.put("updatedParents", List.of(supersededParent.toJson()));
        eventData.put("parentConsensusId", consensusId);

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("decisionId", decisionId);
        response.put("superseded", supersededParent.toJson());
        response.put("parts", ConsensusSegment.toJsonList(created));
        return response;
    }

    // ------------------------------------------------------------------
    // merge
    // ------------------------------------------------------------------

    private Map<String, Object> merge(Map<String, Object> request, String actor, Map<String, Object> eventData) {
        List<Object> itemList = Maps.list(request, "segments");
        if (itemList.size() < 2) {
            throw ApiError.input("A merge needs at least two segments: [{consensusId, baseVersion}]");
        }
        List<ConsensusSegment> parents = new ArrayList<>();
        int maxBaseVersion = 0;
        for (Object item : itemList) {
            Map<String, Object> ref = Maps.asObj(item, "segments[]");
            int version = Maps.integer(ref, "baseVersion");
            maxBaseVersion = Math.max(maxBaseVersion, version);
            parents.add(requireLive(Maps.str(ref, "consensusId"), version));
        }
        String text = Maps.str(request, "text");

        List<ConsensusSegment> ordered = new ArrayList<>(parents);
        ordered.sort((a, b) -> a.window().lo() - b.window().lo());
        for (int i = 1; i < ordered.size(); i++) {
            if (ordered.get(i - 1).window().hi() + 1 < ordered.get(i).window().lo()) {
                throw ApiError.input("Cannot merge segments with a gap between them; "
                        + "declare the gap unjudgable instead of stretching text over it");
            }
        }
        Temporal.Bound start = ordered.get(0).window().start();
        Temporal.Bound end = ordered.get(ordered.size() - 1).window().end();
        Temporal.Window mergedWindow = new Temporal.Window(start, end);
        checkOverlappingConflicts(mergedWindow, text,
                String.join(",", ordered.stream().map(ConsensusSegment::consensusId).toList()), actor);

        List<String> evidenceSegments = new ArrayList<>();
        List<String> evidenceCandidates = new ArrayList<>();
        List<String> parentIds = new ArrayList<>();
        String fingerprint = null;
        boolean mixedFingerprint = false;
        for (ConsensusSegment parent : ordered) {
            parentIds.add(parent.consensusId());
            for (String segId : parent.evidenceSegmentIds()) {
                if (!evidenceSegments.contains(segId)) {
                    evidenceSegments.add(segId);
                }
            }
            for (String candId : parent.evidenceCandidateIds()) {
                if (!evidenceCandidates.contains(candId)) {
                    evidenceCandidates.add(candId);
            }
            }
            if (parent.poseFingerprint() != null) {
                if (fingerprint == null) {
                    fingerprint = parent.poseFingerprint();
                } else if (!fingerprint.equals(parent.poseFingerprint())) {
                    mixedFingerprint = true;
                }
            }
        }
        String decisionId = newDecisionId();
        String mergedId = "cs-" + Hash.shortHash(decisionId + "-merged");
        String note = mixedFingerprint
                ? "MERGED_ACROSS_MIXED_POSE_FINGERPRINTS"
                : "merge of " + String.join(",", parentIds);
        ConsensusSegment merged = new ConsensusSegment(mergedId, maxBaseVersion + 1, "active", text, mergedWindow,
                List.copyOf(evidenceSegments), List.copyOf(evidenceCandidates), List.copyOf(parentIds), List.of(),
                fingerprint, decisionId, actor, store.nextSeq(), note);

        List<Map<String, Object>> updatedParents = new ArrayList<>();
        for (ConsensusSegment parent : ordered) {
            ConsensusSegment superseded = new ConsensusSegment(parent.consensusId(), parent.version(), "superseded",
                    parent.text(), parent.window(), parent.evidenceSegmentIds(), parent.evidenceCandidateIds(),
                    parent.supersedes(), List.of(mergedId), parent.poseFingerprint(), parent.decisionId(),
                    parent.actor(), parent.eventSeq(), parent.note());
            updatedParents.add(superseded.toJson());
        }

        eventData.put("decisionId", decisionId);
        eventData.put("action", "merge");
        eventData.put("createdSegments", List.of(merged.toJson()));
        eventData.put("supersededIds", parentIds);
        eventData.put("updatedParents", updatedParents);
        return Map.of("decisionId", decisionId, "consensus", merged.toJson());
    }

    private ConsensusSegment requireLive(String consensusId, int baseVersion) {
        ConsensusSegment segment = store.state().consensus.get(consensusId);
        if (segment == null) {
            throw ApiError.notFound("Unknown consensusId '" + consensusId + "'");
        }
        if ("superseded".equals(segment.status())) {
            throw ApiError.conflict("Consensus '" + consensusId + "' has already been superseded",
                    Map.of("consensusId", consensusId, "status", segment.status()));
        }
        if (segment.version() != baseVersion) {
            throw ApiError.conflict("Version conflict on '" + consensusId + "': client saw version "
                    + baseVersion + " but current version is " + segment.version(),
                    Map.of("consensusId", consensusId, "clientVersion", baseVersion,
                            "currentVersion", segment.version()));
        }
        return segment;
    }
}
