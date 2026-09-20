package org.research.timeline.model;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** A snapshot of derived alignment: candidates plus frame ranges no source could cover. */
public record AlignmentResult(
        String resultId,
        String poseFingerprint,
        int toleranceFrames,
        List<Candidate> candidates,
        List<Map<String, Object>> gaps) {

    public Map<String, Object> toJson() {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("resultId", resultId);
        map.put("toleranceFrames", toleranceFrames);
        if (poseFingerprint != null) {
            map.put("poseFingerprint", poseFingerprint);
        }
        map.put("candidates", candidates.stream().map(Candidate::toJson).toList());
        map.put("gaps", gaps);
        return map;
    }
}
