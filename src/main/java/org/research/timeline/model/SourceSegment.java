package org.research.timeline.model;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * One researcher-written action segment. Each segment is an independent source:
 * segments may overlap, nest, or carry fuzzy boundaries. Source text is stored
 * verbatim and is never stretched, normalized or rewritten by alignment.
 */
public record SourceSegment(
        String segmentId,
        String sourceId,
        String researcher,
        String rawText,
        Temporal.Window window) {

    public Map<String, Object> toJson() {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("segmentId", segmentId);
        map.put("sourceId", sourceId);
        map.put("researcher", researcher);
        map.put("rawText", rawText);
        map.put("window", window.toJson());
        return map;
    }
}
