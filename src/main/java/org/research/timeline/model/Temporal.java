package org.research.timeline.model;

import java.util.List;
import java.util.Map;

/**
 * All temporal positions are anchored to original frame identifiers.
 * Milliseconds are never used as the primary unit: variable frame rate,
 * dropped frames and frame-rate changes make fixed ms/frame conversion invalid.
 *
 * <p>A {@link Bound} is one segment edge. It is either a precise anchor
 * (frameId or an exact timecode present in the frame table) or a fuzzy range
 * ("sometime between these two frames"). Resolution always returns the range
 * of ordinals the edge may occupy, even for precise anchors ([n,n]).</p>
 */
public final class Temporal {

    private Temporal() {
    }

    /** One row of the imported timecode table. ordinal is the timeline index, frameId is the original id. */
    public record FrameRow(int ordinal, String frameId, String timecode, Long timeNanos) {
        public Map<String, Object> toJson() {
            return Map.of(
                    "ordinal", ordinal,
                    "frameId", frameId,
                    "timecode", timecode == null ? "" : timecode);
        }
    }

    /** Resolved edge: the ordinal range [lo, hi] plus the original frames backing it. */
    public record Bound(int lo, int hi, String loFrameId, String hiFrameId, String raw, boolean precise) {
        public static Bound precise(int ordinal, String frameId, String raw) {
            return new Bound(ordinal, ordinal, frameId, frameId, raw, true);
        }

        public static Bound fuzzy(int lo, int hi, String loFrameId, String hiFrameId, String raw) {
            return new Bound(lo, hi, loFrameId, hiFrameId, raw, false);
        }

        public Map<String, Object> toJson() {
            var map = new java.util.LinkedHashMap<String, Object>();
            map.put("precise", precise);
            map.put("ordinalLo", lo);
            map.put("ordinalHi", hi);
            map.put("frameIdLo", loFrameId);
            if (!precise) {
                map.put("frameIdHi", hiFrameId);
            }
            if (raw != null) {
                map.put("raw", raw);
            }
            return map;
        }
    }

    /** Resolved inclusive ordinal interval with the two backing bounds. */
    public record Window(Bound start, Bound end) {
        public int lo() {
            return start.lo();
        }

        public int hi() {
            return end.hi();
        }

        public boolean precise() {
            return start.precise() && end.precise();
        }

        public boolean overlaps(Window other) {
            return lo() <= other.hi() && other.lo() <= hi();
        }

        public boolean contains(Window other) {
            return lo() <= other.lo() && other.hi() <= hi();
        }

        /** Gap in frame ordinals between the closest edges; 0 when the fuzzy ranges overlap/touch. */
        public int edgeDistance(Window other) {
            int startGap = Math.max(0, Math.max(start.lo() - other.start.hi(), other.start.lo() - start.hi()));
            int endGap = Math.max(0, Math.max(end.lo() - other.end.hi(), other.end.lo() - end.hi()));
            return Math.max(startGap, endGap);
        }

        /** Minimum gap between the two bodies, 0 if they overlap at all. */
        public int bodyGap(Window other) {
            if (overlaps(other)) {
                return 0;
            }
            if (hi() < other.lo()) {
                return other.lo() - hi();
            }
            return lo() - other.hi();
        }

        public Map<String, Object> toJson() {
            return Map.of("start", start.toJson(), "end", end.toJson());
        }
    }

    public static List<Object> boundsJson(List<Bound> bounds) {
        return bounds.stream().map(Bound::toJson).collect(java.util.stream.Collectors.toCollection(java.util.ArrayList::new));
    }
}
