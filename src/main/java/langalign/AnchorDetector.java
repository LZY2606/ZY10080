package langalign;

import langalign.Model.Anchor;
import langalign.Model.KeypointFrame;
import langalign.Model.Keypoints;

import java.util.ArrayList;
import java.util.List;

/**
 * 服务端锚点检测：由相邻帧关键点位移能量找出运动峰值帧作为对齐锚点。
 * 判定全部在服务端完成，前端只展示结果。
 */
public final class AnchorDetector {
    private AnchorDetector() {}

    public static List<Anchor> detect(Keypoints keypoints) {
        List<KeypointFrame> frames = keypoints.frames();
        if (frames.size() < 3) return List.of();
        double[] energy = new double[frames.size()];
        for (int i = 1; i < frames.size(); i++) {
            var prev = frames.get(i - 1).points();
            var cur = frames.get(i).points();
            double e = 0;
            for (int j = 0; j < Math.min(prev.size(), cur.size()); j++) {
                double dx = cur.get(j).get(0) - prev.get(j).get(0);
                double dy = cur.get(j).get(1) - prev.get(j).get(1);
                e += dx * dx + dy * dy;
            }
            energy[i] = e;
        }
        double mean = 0;
        for (int i = 1; i < energy.length; i++) mean += energy[i];
        mean /= (energy.length - 1);
        double var = 0;
        for (int i = 1; i < energy.length; i++) var += (energy[i] - mean) * (energy[i] - mean);
        double std = Math.sqrt(var / (energy.length - 1));
        double threshold = mean + 0.5 * std;

        List<Anchor> anchors = new ArrayList<>();
        for (int i = 1; i < frames.size() - 1; i++) {
            if (energy[i] > threshold && energy[i] >= energy[i - 1] && energy[i] >= energy[i + 1]) {
                anchors.add(new Anchor(frames.get(i).frameId(), energy[i]));
            }
        }
        return anchors;
    }
}
