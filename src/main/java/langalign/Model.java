package langalign;

import com.fasterxml.jackson.annotation.JsonIgnore;
import java.util.List;

/**
 * 领域模型。所有时间都以“原始帧标识”(frameId) 表达，
 * 系统内部从不把帧换算成固定毫秒来比较或存储。
 */
public final class Model {
    private Model() {}

    public enum Precision { EXACT, FUZZY }
    public enum CandidateStatus { PENDING, ACCEPTED, UNDECIDABLE }
    public enum ConsensusStatus { ACTIVE, UNDECIDABLE, SPLIT, MERGED }

    /** 单帧时间码：原始帧标识 + 该帧的呈现时间（仅用于展示，不参与对齐计算）。 */
    public record FrameTiming(long frameId, double ptsSeconds) {}

    /**
     * 时间码轨。支持可变帧率（相邻 pts 差值不等）、帧率变化与丢帧（frameId 跳号）。
     * 帧间距离一律按“帧在轨道上的序位”计算，而不是毫秒。
     */
    public record TimecodeTrack(List<FrameTiming> frames) {
        public TimecodeTrack {
            frames = List.copyOf(frames);
            if (frames.isEmpty()) throw new IllegalArgumentException("时间码轨不能为空");
            for (int i = 1; i < frames.size(); i++) {
                if (frames.get(i).frameId() <= frames.get(i - 1).frameId())
                    throw new IllegalArgumentException("frameId 必须严格递增（丢帧用跳号表示）");
            }
        }

        /** 精确帧位置；丢帧位置返回 -1。 */
        public int indexOf(long frameId) {
            int lo = 0, hi = frames.size() - 1;
            while (lo <= hi) {
                int mid = (lo + hi) >>> 1;
                long v = frames.get(mid).frameId();
                if (v == frameId) return mid;
                if (v < frameId) lo = mid + 1; else hi = mid - 1;
            }
            return -1;
        }

        public boolean hasFrame(long frameId) { return indexOf(frameId) >= 0; }

        /** 距离目标帧最近的真实存在的帧位置（用于模糊代表帧落在丢帧空隙时）。 */
        public int nearestIndex(long frameId) {
            int exact = indexOf(frameId);
            if (exact >= 0) return exact;
            int lo = 0, hi = frames.size() - 1;
            while (lo <= hi) {
                int mid = (lo + hi) >>> 1;
                long v = frames.get(mid).frameId();
                if (v < frameId) lo = mid + 1; else hi = mid - 1;
            }
            if (lo <= 0) return 0;
            if (lo >= frames.size()) return frames.size() - 1;
            long before = frames.get(lo - 1).frameId();
            long after = frames.get(lo).frameId();
            return (frameId - before) <= (after - frameId) ? lo - 1 : lo;
        }

        public double ptsOf(long frameId) {
            int i = indexOf(frameId);
            if (i < 0) throw new IllegalArgumentException("帧 " + frameId + " 不在时间码轨上（可能为丢帧）");
            return frames.get(i).ptsSeconds();
        }

        /** 两帧之间的帧序距离（不是毫秒）。 */
        public int distanceFrames(long a, long b) {
            return Math.abs(nearestIndex(a) - nearestIndex(b));
        }
    }

    public record Video(String id, String name, TimecodeTrack track) {}

    /**
     * 模糊边界：earliest/latest 为允许范围，exact 为精确帧。
     * 有 exact 时归一化为三点相同；无 exact 时保持模糊，绝不压缩成精确点。
     */
    public record FuzzyBound(Long earliest, Long exact, Long latest) {
        public FuzzyBound {
            if (exact != null) { earliest = exact; latest = exact; }
            if (earliest == null || latest == null)
                throw new IllegalArgumentException("边界缺少帧引用（需要 earliest/latest 或 exact）");
            if (earliest > latest) throw new IllegalArgumentException("边界范围倒置");
        }
        public static FuzzyBound exact(long f) { return new FuzzyBound(f, f, f); }
        public static FuzzyBound fuzzy(long earliest, long latest) { return new FuzzyBound(earliest, null, latest); }
        @JsonIgnore public Precision precision() { return exact != null ? Precision.EXACT : Precision.FUZZY; }
        @JsonIgnore public long low() { return earliest; }
        @JsonIgnore public long high() { return latest; }
        @JsonIgnore public long representative() { return exact != null ? exact : (earliest + latest) / 2; }
    }

    public record FuzzyInterval(FuzzyBound start, FuzzyBound end) {
        public FuzzyInterval {
            if (start == null || end == null) throw new IllegalArgumentException("区间缺少起止边界");
            if (start.low() > end.high()) throw new IllegalArgumentException("区间起止倒置");
        }
        public static FuzzyInterval of(FuzzyBound start, FuzzyBound end) { return new FuzzyInterval(start, end); }
    }

    public record Segment(String id, String text, FuzzyInterval interval) {}

    /** 来源层：一位研究者的一份分段，导入后不可变。允许重叠、嵌套、模糊边界。 */
    public record SourceLayer(String id, String videoId, String researcher, List<Segment> segments) {}

    public record KeypointFrame(long frameId, List<List<Double>> points) {}

    /** 姿态关键点，fingerprint 绑定算法身份；换算法即换指纹。 */
    public record Keypoints(String id, String videoId, String algorithm, String version,
                            String fingerprint, List<KeypointFrame> frames) {}

    public record Anchor(long frameId, double energy) {}

    public record Candidate(String id, String sourceSegmentId, String text, FuzzyInterval target,
                            boolean gap, CandidateStatus status) {}

    public record Alignment(String id, String videoId, String sourceLayerId, String keypointsId,
                            String keypointFingerprint, int toleranceFrames, List<Candidate> candidates) {}

    public record Evidence(String type, String refId) {}

    public record ConsensusSegment(String id, String videoId, String text, FuzzyInterval interval,
                                   ConsensusStatus status, long version, String parentId,
                                   List<Evidence> evidence, String keypointFingerprint) {}

    public record ConsensusLayer(String videoId, String keypointFingerprint, List<ConsensusSegment> segments) {}

    /** 操作事件：追加写日志，崩溃后据此重建共识层。 */
    public record Event(String eventId, long seq, long ts, String requestId, String op,
                        String segmentId, ConsensusSegment segment, ConsensusLayer layer, String note) {}
}
