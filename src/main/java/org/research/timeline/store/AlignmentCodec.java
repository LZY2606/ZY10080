package org.research.timeline.store;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.research.timeline.model.AlignmentResult;
import org.research.timeline.model.Candidate;
import org.research.timeline.model.Temporal;

/** Decodes the derived alignment results embedded in events for replay. */
final class AlignmentCodec {

    private AlignmentCodec() {
    }

    @SuppressWarnings("unchecked")
    static AlignmentResult decode(Map<String, Object> json) {
        String resultId = String.valueOf(json.get("resultId"));
        String fingerprint = json.get("poseFingerprint") == null ? null : String.valueOf(json.get("poseFingerprint"));
        int tolerance = ((Number) json.get("toleranceFrames")).intValue();
        List<Candidate> candidates = new ArrayList<>();
        for (Object item : (List<Object>) json.get("candidates")) {
            candidates.add(decodeCandidate((Map<String, Object>) item));
        }
        List<Map<String, Object>> gaps = (List<Map<String, Object>>) (List<?>) json.getOrDefault("gaps", List.of());
        return new AlignmentResult(resultId, fingerprint, tolerance, List.copyOf(candidates), List.copyOf(gaps));
    }

    @SuppressWarnings("unchecked")
    private static Candidate decodeCandidate(Map<String, Object> json) {
        Temporal.Window union = WindowCodec.decode((Map<String, Object>) json.get("unionWindow"));
        List<String> segmentIds = (List<String>) json.get("segmentIds");
        String gapReason = json.get("gapReason") == null ? null : String.valueOf(json.get("gapReason"));
        return new Candidate(
                String.valueOf(json.get("candidateId")),
                List.copyOf(segmentIds),
                String.valueOf(json.get("relation")),
                ((Number) json.get("edgeDistanceFrames")).intValue(),
                ((Number) json.get("confidence")).doubleValue(),
                ((Number) json.get("toleranceFrames")).intValue(),
                union,
                gapReason);
    }
}
