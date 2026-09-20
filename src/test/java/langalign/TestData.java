package langalign;

import langalign.Model.*;

import java.util.ArrayList;
import java.util.List;

/** 测试数据构造：100 帧视频 + 指定峰值帧的合成关键点（锚点出现在 peak 与 peak+1）。 */
final class TestData {
    private TestData() {}

    static Video video(String id) {
        List<FrameTiming> frames = new ArrayList<>();
        for (long f = 0; f < 100; f++) frames.add(new FrameTiming(f, f * 0.04));
        return new Video(id, id, new TimecodeTrack(frames));
    }

    /** 可变帧率视频：pts 加速增长，毫秒换算会失真。 */
    static Video vfrVideo(String id) {
        List<FrameTiming> frames = new ArrayList<>();
        for (long f = 0; f < 100; f++) frames.add(new FrameTiming(f, 0.001 * f * f));
        return new Video(id, id, new TimecodeTrack(frames));
    }

    static Keypoints keypoints(String id, String videoId, long... peaks) {
        List<KeypointFrame> frames = new ArrayList<>();
        for (long f = 0; f < 100; f++) {
            double pos = 0;
            for (long p : peaks) pos += 1.0 / (1.0 + Math.exp(-(f - p) / 0.5));
            frames.add(new KeypointFrame(f, List.of(List.of(pos * 100, 0.0))));
        }
        return new Keypoints(id, videoId, "pose-est", "1.0", Demo.fingerprint("pose-est", "1.0"), frames);
    }

    static FuzzyInterval exact(long s, long e) {
        return new FuzzyInterval(FuzzyBound.exact(s), FuzzyBound.exact(e));
    }

    static FuzzyInterval fuzzy(long s1, long s2, long e1, long e2) {
        return new FuzzyInterval(FuzzyBound.fuzzy(s1, s2), FuzzyBound.fuzzy(e1, e2));
    }
}
