package org.research.timeline.model;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Derived (never authoritative) alignment candidate between source segments.
 * Every ordinal number is accompanied by the backing frame identifiers.
 */
public record Candidate(
        String candidateId,
        List<String> segmentIds,
        String relation,
        int edgeDistance,
        double confidence,
        int toleranceFrames,
        Temporal.Window unionWindow,
        String gapReason) {

    public Map<String, Object> toJson() {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("candidateId", candidateId);
        map.put("segmentIds", segmentIds);
        map.put("relation", relation);
        map.put("edgeDistanceFrames", edgeDistance);
        map.put("toleranceFrames", toleranceFrames);
        map.put("confidence", confidence);
        map.put("unionWindow", unionWindow.toJson());
        if (gapReason != null) {
            map.put("gapReason", gapReason);
        }
        return map;
    }
}
