package org.research.timeline.service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.research.timeline.model.Candidate;
import org.research.timeline.model.SourceSegment;
import org.research.timeline.model.Temporal;
import org.research.timeline.store.Store;
import org.research.timeline.util.Hash;

/**
 * Derives candidate alignments between independent source segments.
 * Pure function of (sources, tolerance, frame table): derived results are
 * stored separately from raw inputs and can always be regenerated.
 */
public final class AlignmentService {

    private final Store store;

    public AlignmentService(Store store) {
        this.store = store;
    }

    public Map<String, Object> generate(int toleranceFrames, String poseId) {
        if (toleranceFrames < 0) {
            throw ApiError.input("toleranceFrames must be >= 0");
        }
        if (store.state().frameTable == null) {
            throw ApiError.conflict("Import the frame table before generating alignments");
        }
        String fingerprint = null;
        if (poseId != null && !poseId.isBlank()) {
            var pose = store.state().poses.get(poseId);
            if (pose == null) {
                throw ApiError.input("Unknown poseId '" + poseId + "'");
            }
            fingerprint = pose.fingerprint();
        }
        List<SourceSegment> segments = List.copyOf(store.state().sources.values());
        List<Candidate> candidates = buildCandidates(segments, toleranceFrames);
        List<Map<String, Object>> gaps = findGaps(segments, store.state().frameTable.size());
        String resultId = "al-" + Hash.shortHash(String.valueOf(System.nanoTime()) + candidates.size() + gaps.size());
        var result = new org.research.timeline.model.AlignmentResult(
                resultId, fingerprint, toleranceFrames, candidates, gaps);
        store.state().alignments.put(resultId, result);
        return result.toJson();
    }

    List<Candidate> buildCandidates(List<SourceSegment> segments, int tolerance) {
        List<Candidate> candidates = new ArrayList<>();
        for (int i = 0; i < segments.size(); i++) {
            for (int j = i + 1; j < segments.size(); j++) {
                SourceSegment a = segments.get(i);
                SourceSegment b = segments.get(j);
                int distance = a.window().edgeDistance(b.window());
                if (distance > tolerance) {
                    continue;
                }
                String relation = classify(a.window(), b.window());
                double confidence = confidence(a.window(), b.window(), tolerance, relation);
                // Union keeps the widest fuzzy range so uncertainty is never narrowed by derivation.
                Temporal.Bound start = combineBound(a.window().start(), b.window().start());
                Temporal.Bound end = combineBound(a.window().end(), b.window().end());
                Temporal.Window union = new Temporal.Window(start, end);
                String id = "cand-" + Hash.shortHash(a.segmentId() + "|" + b.segmentId() + "|" + tolerance);
                candidates.add(new Candidate(id, List.of(a.segmentId(), b.segmentId()), relation,
                        distance, confidence, tolerance, union, null));
            }
        }
        return candidates;
    }

    private static Temporal.Bound combineBound(Temporal.Bound x, Temporal.Bound y) {
        int lo = Math.min(x.lo(), y.lo());
        int hi = Math.max(x.hi(), y.hi());
        String loFrame = lo == x.lo() ? x.loFrameId() : y.loFrameId();
        String hiFrame = hi == x.hi() ? x.hiFrameId() : y.hiFrameId();
        // The derived edge is precise only when both sources pinned the exact same frame.
        // Two precise-but-different frames describe uncertainty between those frames, so
        // the union is deliberately fuzzy and keeps both original frame ids.
        if (x.precise() && y.precise() && x.lo() == y.lo()) {
            return Temporal.Bound.precise(lo, loFrame, null);
        }
        return Temporal.Bound.fuzzy(lo, hi, loFrame, hiFrame, null);
    }

    static String classify(Temporal.Window a, Temporal.Window b) {
        boolean sameStart = a.start().lo() == b.start().lo() && a.start().hi() == b.start().hi();
        boolean sameEnd = a.end().lo() == b.end().lo() && a.end().hi() == b.end().hi();
        if (sameStart && sameEnd) {
            return "equal";
        }
        if (a.contains(b)) {
            return "contains";
        }
        if (b.contains(a)) {
            return "inside";
        }
        return "partial";
    }

    private static double confidence(Temporal.Window a, Temporal.Window b, int tolerance, String relation) {
        int distance = a.edgeDistance(b);
        double toleranceScore = tolerance == 0
                ? (distance == 0 ? 1.0 : 0.0)
                : 1.0 - (distance / (double) (tolerance + 1));
        double precisionScore = ((a.precise() ? 1 : 0) + (b.precise() ? 1 : 0)) / 2.0;
        double relationBonus = switch (relation) {
            case "equal" -> 0.15;
            case "contains", "inside" -> 0.05;
            default -> 0.0;
        };
        double raw = 0.65 * toleranceScore + 0.35 * precisionScore + relationBonus;
        return Math.round(Math.min(0.99, raw) * 100.0) / 100.0;
    }

    /**
     * Frame ranges not covered by any source segment are reported as gaps.
     * Gaps are never silently stretched or filled.
     */
    List<Map<String, Object>> findGaps(List<SourceSegment> segments, int frameCount) {
        List<int[]> intervals = new ArrayList<>();
        for (SourceSegment segment : segments) {
            intervals.add(new int[]{segment.window().lo(), segment.window().hi()});
        }
        intervals.sort((x, y) -> x[0] - y[0]);
        List<Map<String, Object>> gaps = new ArrayList<>();
        int cursor = 0;
        for (int[] interval : intervals) {
            if (interval[0] > cursor) {
                gaps.add(gap(cursor, interval[0] - 1, "uncovered_before_" + frameLabel(interval[0])));
            }
            cursor = Math.max(cursor, interval[1] + 1);
        }
        if (cursor < frameCount) {
            gaps.add(gap(cursor, frameCount - 1, "uncovered_tail"));
        }
        return gaps;
    }

    private Map<String, Object> gap(int lo, int hi, String reason) {
        var table = store.state().frameTable;
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("ordinalLo", lo);
        map.put("ordinalHi", hi);
        map.put("frameIdLo", table.frameIdOf(lo));
        map.put("frameIdHi", table.frameIdOf(hi));
        map.put("gapFrames", hi - lo + 1);
        map.put("reason", reason);
        return map;
    }

    private String frameLabel(int ordinal) {
        return store.state().frameTable.frameIdOf(ordinal);
    }
}
