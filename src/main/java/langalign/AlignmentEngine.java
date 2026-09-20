package langalign;

import langalign.Model.*;

import java.util.ArrayList;
import java.util.List;

/**
 * 候选对齐：以锚点为基准、以“帧序距离”为允许偏差。
 * 找不到锚点的区间生成缺口(gap)候选，绝不拉伸研究者原文或伪造精确边界。
 */
public final class AlignmentEngine {
    private AlignmentEngine() {}

    public static Alignment generate(String id, Video video, SourceLayer layer,
                                     Keypoints keypoints, int toleranceFrames) {
        List<Anchor> anchors = AnchorDetector.detect(keypoints);
        TimecodeTrack track = video.track();
        List<Candidate> candidates = new ArrayList<>();
        int n = 0;
        for (Segment seg : layer.segments()) {
            Anchor a0 = nearest(anchors, seg.interval().start().representative(), track, toleranceFrames);
            Anchor a1 = nearest(anchors, seg.interval().end().representative(), track, toleranceFrames);
            String cid = id + "-c" + (n++);
            if (a0 == null || a1 == null || a0.frameId() >= a1.frameId()) {
                candidates.add(new Candidate(cid, seg.id(), seg.text(), null, true, CandidateStatus.PENDING));
            } else {
                candidates.add(new Candidate(cid, seg.id(), seg.text(),
                        new FuzzyInterval(FuzzyBound.exact(a0.frameId()), FuzzyBound.exact(a1.frameId())),
                        false, CandidateStatus.PENDING));
            }
        }
        return new Alignment(id, video.id(), layer.id(), keypoints.id(),
                keypoints.fingerprint(), toleranceFrames, candidates);
    }

    private static Anchor nearest(List<Anchor> anchors, long frame, TimecodeTrack track, int tolerance) {
        int idx = track.nearestIndex(frame);
        Anchor best = null;
        int bestDist = Integer.MAX_VALUE;
        for (Anchor a : anchors) {
            int ai = track.indexOf(a.frameId());
            if (ai < 0) continue;
            int d = Math.abs(ai - idx);
            if (d < bestDist) { bestDist = d; best = a; }
        }
        return bestDist <= tolerance ? best : null;
    }
}
