package org.research.timeline.model;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.research.timeline.util.Hash;
import org.research.timeline.util.Json;
import org.research.timeline.util.Maps;

/**
 * Keypoints produced by one pose algorithm version. The server computes a stable
 * fingerprint of the keypoint data; consensus decisions bind to that fingerprint,
 * so consensus made under an old algorithm stays tied to the old keypoints even
 * after a newer algorithm is imported.
 */
public record PoseData(
        String poseId,
        String algorithm,
        String algorithmVersion,
        String fingerprint,
        List<KeypointFrame> frames) {

    public record KeypointFrame(String frameId, Integer ordinal, List<Object> keypoints) {
    }

    public static PoseData fromImport(String poseId, Map<String, Object> payload) {
        String algorithm = Maps.str(payload, "algorithm");
        String version = Maps.str(payload, "algorithmVersion");
        List<Object> frameList = Maps.list(payload, "frames");
        List<KeypointFrame> built = new ArrayList<>();
        for (Object item : frameList) {
            Map<String, Object> frame = Maps.asObj(item, "pose frames[]");
            String frameId = frame.containsKey("frameId")
                    ? Maps.str(frame, "frameId")
                    : Maps.asStr(frame.get("ordinal"), "pose frame ordinal");
            Integer ordinal = frame.containsKey("ordinal") ? Maps.integer(frame, "ordinal") : null;
            List<Object> keypoints = Maps.list(frame, "keypoints");
            built.add(new KeypointFrame(frameId, ordinal, keypoints));
        }
        // Fingerprint covers algorithm identity and the exact submitted keypoint payload.
        String fingerprintInput = algorithm + "|" + version + "|" + Json.write(frameList);
        return new PoseData(poseId, algorithm, version, Hash.sha256(fingerprintInput), List.copyOf(built));
    }

    public Map<String, Object> toJson() {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("poseId", poseId);
        map.put("algorithm", algorithm);
        map.put("algorithmVersion", algorithmVersion);
        map.put("fingerprint", fingerprint);
        map.put("frameCount", frames.size());
        return map;
    }

    public Map<String, Object> detailJson() {
        Map<String, Object> map = toJson();
        List<Object> frameJson = new ArrayList<>();
        for (KeypointFrame frame : frames) {
            Map<String, Object> fj = new LinkedHashMap<>();
            fj.put("frameId", frame.frameId());
            if (frame.ordinal() != null) {
                fj.put("ordinal", frame.ordinal());
            }
            fj.put("keypoints", frame.keypoints());
            frameJson.add(fj);
        }
        map.put("frames", frameJson);
        return map;
    }
}
