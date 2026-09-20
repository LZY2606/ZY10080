package org.research.timeline.store;

import java.util.Map;
import org.research.timeline.model.FrameTable;
import org.research.timeline.model.SourceSegment;
import org.research.timeline.model.Temporal;

/** Decodes source segments stored verbatim inside events. */
final class Segments {

    private Segments() {
    }

    static SourceSegment fromStoredJson(FrameTable table, String sourceIdFallback, Map<String, Object> stored) {
        String segmentId = String.valueOf(stored.get("segmentId"));
        String sourceId = stored.containsKey("sourceId") ? String.valueOf(stored.get("sourceId")) : sourceIdFallback;
        String researcher = String.valueOf(stored.get("researcher"));
        String rawText = String.valueOf(stored.get("rawText"));
        @SuppressWarnings("unchecked")
        Map<String, Object> startLocator = (Map<String, Object>) stored.get("start");
        @SuppressWarnings("unchecked")
        Map<String, Object> endLocator = (Map<String, Object>) stored.get("end");
        Temporal.Bound start = table.resolve(startLocator);
        Temporal.Bound end = table.resolve(endLocator);
        if (start.lo() > end.hi()) {
            throw new IllegalArgumentException("Segment " + segmentId + " starts after it ends");
        }
        return new SourceSegment(segmentId, sourceId, researcher, rawText, new Temporal.Window(start, end));
    }
}
