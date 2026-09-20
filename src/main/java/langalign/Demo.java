package langalign;

import langalign.Model.*;

import java.security.MessageDigest;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 演示数据：VFR + 丢帧视频、合成姿态关键点（含运动峰值）、两位研究者的重叠/嵌套/模糊分段。 */
public final class Demo {
    private Demo() {}

    public static String fingerprint(String algorithm, String version) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] h = md.digest((algorithm + ":" + version).getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < 8; i++) sb.append(String.format("%02x", h[i]));
            return sb.toString();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    public static Map<String, Object> seed(Repo repo) {
        // 视频：300 帧，丢帧 137，帧率变化 + 可变帧率
        List<FrameTiming> frames = new ArrayList<>();
        double pts = 0;
        for (long f = 0; f < 300; f++) {
            if (f == 137) continue; // 丢帧
            frames.add(new FrameTiming(f, pts));
            pts += (f % 60 < 30) ? 0.033 : 0.05;
        }
        Video video = new Video("demo-video", "演示视频（VFR + 丢帧）", new TimecodeTrack(frames));
        repo.saveVideo(video);

        // 关键点：在 40/90/150/210/260 附近有运动峰值（服务端据此检测锚点）
        long[] peaks = {40, 90, 150, 210, 260};
        List<KeypointFrame> kfs = new ArrayList<>();
        for (FrameTiming ft : frames) {
            double pos = 0;
            for (long p : peaks) pos += 1.0 / (1.0 + Math.exp(-(ft.frameId() - p) / 2.0));
            kfs.add(new KeypointFrame(ft.frameId(), List.of(
                    List.of(pos * 100, 50.0), List.of(pos * 80, 120.0))));
        }
        Keypoints kp = new Keypoints("kp-v1", video.id(), "pose-est", "1.0",
                fingerprint("pose-est", "1.0"), kfs);
        repo.saveKeypoints(kp);

        // 研究者 A：精确 + 模糊 + 嵌套 + 一段无锚点（将成为缺口）
        SourceLayer a = new SourceLayer("layer-a", video.id(), "研究者A", List.of(
                new Segment("a1", "抬手", new FuzzyInterval(FuzzyBound.exact(38), FuzzyBound.exact(92))),
                new Segment("a2", "保持举手", new FuzzyInterval(FuzzyBound.exact(46), FuzzyBound.exact(86))),
                new Segment("a3", "挥手（起止模糊）", new FuzzyInterval(FuzzyBound.fuzzy(145, 155), FuzzyBound.fuzzy(208, 214))),
                new Segment("a4", "整理衣物（范围模糊）", new FuzzyInterval(FuzzyBound.fuzzy(8, 12), FuzzyBound.fuzzy(18, 22)))));
        repo.saveLayer(a);

        // 研究者 B：与 A 重叠的独立分段
        SourceLayer b = new SourceLayer("layer-b", video.id(), "研究者B", List.of(
                new Segment("b1", "手开始抬", new FuzzyInterval(FuzzyBound.exact(36), FuzzyBound.exact(50))),
                new Segment("b2", "抬手全过程", new FuzzyInterval(FuzzyBound.exact(44), FuzzyBound.exact(95))),
                new Segment("b3", "挥手", new FuzzyInterval(FuzzyBound.fuzzy(147, 153), FuzzyBound.fuzzy(207, 213)))));
        repo.saveLayer(b);

        // 为 A 生成候选（允许偏差 6 帧）
        Alignment al = AlignmentEngine.generate("al-demo", video, a, kp, 6);
        repo.saveAlignment(al);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("videoId", video.id());
        out.put("keypointsId", kp.id());
        out.put("keypointFingerprint", kp.fingerprint());
        out.put("layers", List.of(a.id(), b.id()));
        out.put("alignmentId", al.id());
        return out;
    }
}
