package org.research.timeline.store;

import java.util.LinkedHashMap;
import java.util.Map;
import org.research.timeline.model.Temporal;

/** Serializes resolved windows while retaining the frame identifiers of every edge. */
final class WindowCodec {

    private WindowCodec() {
    }

    static Map<String, Object> decodeBound(Temporal.Bound bound) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("precise", bound.precise());
        map.put("ordinalLo", bound.lo());
        map.put("ordinalHi", bound.hi());
        map.put("frameIdLo", bound.loFrameId());
        if (!bound.precise()) {
            map.put("frameIdHi", bound.hiFrameId());
        }
        if (bound.raw() != null) {
            map.put("raw", bound.raw());
        }
        return map;
    }

    static Temporal.Bound decodeBound(Map<String, Object> json) {
        int lo = ((Number) json.get("ordinalLo")).intValue();
        int hi = ((Number) json.get("ordinalHi")).intValue();
        String loFrame = String.valueOf(json.get("frameIdLo"));
        String hiFrame = json.get("frameIdHi") == null ? loFrame : String.valueOf(json.get("frameIdHi"));
        boolean precise = Boolean.TRUE.equals(json.get("precise"));
        String raw = json.get("raw") == null ? null : String.valueOf(json.get("raw"));
        return new Temporal.Bound(lo, hi, loFrame, hiFrame, raw, precise);
    }

    static Map<String, Object> decode(Temporal.Window window) {
        return Map.of("start", decodeBound(window.start()), "end", decodeBound(window.end()));
    }

    static Temporal.Window decode(Map<String, Object> json) {
        return new Temporal.Window(decodeBound((Map<String, Object>) json.get("start")),
                decodeBound((Map<String, Object>) json.get("end")));
    }
}
