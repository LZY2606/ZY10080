package org.research.timeline.model;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.research.timeline.service.ApiError;
import org.research.timeline.util.Maps;

/**
 * The imported timecode/frame table is the only authority for mapping frame ids,
 * timecodes and wall-clock times. It supports VFR media: consecutive rows may
 * have arbitrary, non-uniform time gaps.
 */
public final class FrameTable {

    private final String videoId;
    private final List<Temporal.FrameRow> rows;
    private final Map<String, Integer> byFrameId = new HashMap<>();
    private final Map<String, Integer> byTimecode = new HashMap<>();

    public FrameTable(String videoId, List<Temporal.FrameRow> rows) {
        this.videoId = videoId;
        this.rows = List.copyOf(rows);
        for (Temporal.FrameRow row : this.rows) {
            if (byFrameId.putIfAbsent(row.frameId(), row.ordinal()) != null) {
                throw ApiError.input("Duplicate frameId '" + row.frameId() + "' in frame table");
            }
            if (row.timecode() != null && !row.timecode().isBlank()) {
                byTimecode.putIfAbsent(normalizeTimecode(row.timecode()), row.ordinal());
            }
        }
    }

    public String videoId() {
        return videoId;
    }

    public int size() {
        return rows.size();
    }

    public Temporal.FrameRow row(int ordinal) {
        if (ordinal < 0 || ordinal >= rows.size()) {
            throw ApiError.input("Frame ordinal " + ordinal + " is outside the frame table");
        }
        return rows.get(ordinal);
    }

    public String frameIdOf(int ordinal) {
        return row(ordinal).frameId();
    }

    public Integer ordinalByFrameId(String frameId) {
        return byFrameId.get(frameId);
    }

    public Integer ordinalByTimecode(String timecode) {
        return byTimecode.get(normalizeTimecode(timecode));
    }

    public List<Temporal.FrameRow> rows() {
        return rows;
    }

    /**
     * Resolve a single locator object.
     *
     * <p>Supported shapes:
     * <ul>
     *   <li>{"frameId":"f0042"} - exact original frame</li>
     *   <li>{"ordinal":42} - exact timeline ordinal (ordinal is import order, not time math)</li>
     *   <li>{"timecode":"00:00:01:12"} - exact timecode, must exist in the table</li>
     *   <li>{"fuzzyBetween":{...a..., ...b...}} - range between two precise locators</li>
     * </ul>
     * A bare "ms" value is rejected: with VFR/dropped frames it cannot be mapped
     * back to an original frame identifier.
     */
    public Temporal.Bound resolve(Map<String, Object> locator) {
        if (locator == null || locator.isEmpty()) {
            throw ApiError.input("Empty locator");
        }
        if (locator.containsKey("fuzzyBetween")) {
            Map<String, Object> between = Maps.obj(locator, "fuzzyBetween");
            Temporal.Bound lo = resolvePrecise(Maps.obj(between, "from"), "fuzzyBetween.from");
            Temporal.Bound hi = resolvePrecise(Maps.obj(between, "to"), "fuzzyBetween.to");
            if (lo.lo() > hi.hi()) {
                throw ApiError.input("fuzzyBetween 'from' must not be after 'to'");
            }
            String raw = Maps.optStr(locator, "raw", null);
            return Temporal.Bound.fuzzy(lo.lo(), hi.hi(), lo.loFrameId(), hi.hiFrameId(), raw);
        }
        return resolvePrecise(locator, "locator");
    }

    private Temporal.Bound resolvePrecise(Map<String, Object> locator, String context) {
        boolean hasFrame = locator.containsKey("frameId");
        boolean hasOrdinal = locator.containsKey("ordinal");
        boolean hasTimecode = locator.containsKey("timecode");
        int count = (hasFrame ? 1 : 0) + (hasOrdinal ? 1 : 0) + (hasTimecode ? 1 : 0);
        if (count == 0) {
            throw ApiError.input(context + " must use frameId, ordinal or timecode (bare milliseconds are not accepted)");
        }
        if (count > 1) {
            throw ApiError.input(context + " must specify exactly one of frameId/ordinal/timecode");
        }
        String raw = Maps.optStr(locator, "raw", null);
        if (hasFrame) {
            String frameId = Maps.str(locator, "frameId");
            Integer ordinal = byFrameId.get(frameId);
            if (ordinal == null) {
                throw ApiError.input("frameId '" + frameId + "' is not present in the frame table");
            }
            return Temporal.Bound.precise(ordinal, frameId, raw != null ? raw : "frameId=" + frameId);
        }
        if (hasTimecode) {
            String timecode = Maps.str(locator, "timecode");
            Integer ordinal = ordinalByTimecode(timecode);
            if (ordinal == null) {
                throw ApiError.input("timecode '" + timecode + "' is not an exact entry in the frame table; "
                        + "wrap an approximate range in fuzzyBetween");
            }
            Temporal.FrameRow frameRow = rows.get(ordinal);
            return Temporal.Bound.precise(ordinal, frameRow.frameId(), raw != null ? raw : "timecode=" + timecode);
        }
        int ordinal = Maps.integer(locator, "ordinal");
        if (ordinal < 0 || ordinal >= rows.size()) {
            throw ApiError.input("ordinal " + ordinal + " is outside the frame table");
        }
        Temporal.FrameRow frameRow = rows.get(ordinal);
        return Temporal.Bound.precise(ordinal, frameRow.frameId(), raw != null ? raw : "ordinal=" + ordinal);
    }

    /**
     * Parse a timecode string into canonical H:MM:SS:FF(.+) or H:MM:SS(.nnnnnnnnn) form.
     * Accepts HH:MM:SS:FF, HH:MM:SS.mmm and HH:MM:SS.
     */
    public static String normalizeTimecode(String input) {
        String s = input.trim();
        if (s.matches("\\d{1,2}:\\d{2}:\\d{2}[;:.,]\\d+")) {
            String[] parts = s.split("[:;.,]");
            int h = Integer.parseInt(parts[0]);
            int m = Integer.parseInt(parts[1]);
            int sec = Integer.parseInt(parts[2]);
            String frac = parts[3];
            char sep = s.charAt(8);
            return String.format("%02d:%02d:%02d", h, m, sec) + sep + frac;
        }
        if (s.matches("\\d{1,2}:\\d{2}:\\d{2}")) {
            String[] parts = s.split(":");
            return String.format("%02d:%02d:%02d",
                    Integer.parseInt(parts[0]), Integer.parseInt(parts[1]), Integer.parseInt(parts[2]));
        }
        return s;
    }

    /**
     * Build from the import payload:
     * {"videoId":..., "frames":[{"frameId":"f0","ordinal":0,"timecode":"00:00:00:00","timeNanos":0}]}
     */
    public static FrameTable fromImport(Map<String, Object> payload) {
        String videoId = Maps.str(payload, "videoId");
        List<Object> frameList = Maps.list(payload, "frames");
        if (frameList.isEmpty()) {
            throw ApiError.input("Frame table must contain at least one frame");
        }
        List<Temporal.FrameRow> built = new ArrayList<>();
        long prevNanos = Long.MIN_VALUE;
        int expectedOrdinal = 0;
        for (Object item : frameList) {
            Map<String, Object> frame = Maps.asObj(item, "frames[]");
            String frameId = Maps.str(frame, "frameId");
            int ordinal;
            if (frame.containsKey("ordinal")) {
                ordinal = Maps.integer(frame, "ordinal");
            } else {
                ordinal = expectedOrdinal;
            }
            if (ordinal != expectedOrdinal) {
                throw ApiError.input("Frame ordinal sequence must be dense starting at 0; expected "
                        + expectedOrdinal + " but got " + ordinal
                        + " (dropped frames must still appear as rows with their original frameId)");
            }
            expectedOrdinal++;
            String timecode = Maps.optStr(frame, "timecode", null);
            Long timeNanos = null;
            if (frame.get("timeNanos") instanceof Number n) {
                timeNanos = n.longValue();
                if (timeNanos < prevNanos) {
                    throw ApiError.input("timeNanos must be non-decreasing at frameId '" + frameId + "'");
                }
                prevNanos = timeNanos;
            }
            built.add(new Temporal.FrameRow(ordinal, frameId, timecode, timeNanos));
        }
        return new FrameTable(videoId, built);
    }

    public Map<String, Object> toJson() {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("videoId", videoId);
        map.put("frameCount", rows.size());
        List<Object> frameJson = new ArrayList<>();
        for (Temporal.FrameRow row : rows) {
            Map<String, Object> fj = new LinkedHashMap<>();
            fj.put("ordinal", row.ordinal());
            fj.put("frameId", row.frameId());
            if (row.timecode() != null) {
                fj.put("timecode", row.timecode());
            }
            if (row.timeNanos() != null) {
                fj.put("timeNanos", row.timeNanos());
            }
            frameJson.add(fj);
        }
        map.put("frames", frameJson);
        return map;
    }
}
