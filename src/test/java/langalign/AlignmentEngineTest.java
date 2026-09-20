package langalign;

import langalign.Model.*;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class AlignmentEngineTest {
    @Test
    void alignsByFrameDistanceNotMillis() {
        // VFR：pts 加速增长。若用固定毫秒换算，帧 12/48 会被算到别处。
        Video video = TestData.vfrVideo("v1");
        Keypoints kp = TestData.keypoints("kp1", "v1", 10, 50);
        SourceLayer layer = new SourceLayer("l1", "v1", "研究者A", List.of(
                new Segment("s1", "抬手", TestData.exact(12, 48))));
        Alignment al = AlignmentEngine.generate("al1", video, layer, kp, 5);
        Candidate c = al.candidates().get(0);
        assertFalse(c.gap());
        assertEquals(10, c.target().start().low());
        assertEquals(50, c.target().end().low());
        assertEquals("抬手", c.text()); // 原文不动
    }

    @Test
    void fuzzySegmentAlignsByRepresentative() {
        Video video = TestData.video("v1");
        Keypoints kp = TestData.keypoints("kp1", "v1", 10, 30, 50, 70);
        SourceLayer layer = new SourceLayer("l1", "v1", "研究者A", List.of(
                new Segment("s1", "模糊段", TestData.fuzzy(28, 32, 48, 52))));
        Alignment al = AlignmentEngine.generate("al1", video, layer, kp, 5);
        Candidate c = al.candidates().get(0);
        assertFalse(c.gap());
        assertEquals(30, c.target().start().low());
        assertEquals(50, c.target().end().low());
    }

    @Test
    void unalignableBecomesGapWithoutStretching() {
        Video video = TestData.video("v1");
        Keypoints kp = TestData.keypoints("kp1", "v1", 10, 30, 50, 70);
        SourceLayer layer = new SourceLayer("l1", "v1", "研究者A", List.of(
                new Segment("s1", "远离锚点", TestData.fuzzy(88, 93, 95, 99))));
        Alignment al = AlignmentEngine.generate("al1", video, layer, kp, 5);
        Candidate c = al.candidates().get(0);
        assertTrue(c.gap());
        assertNull(c.target());
        assertEquals("远离锚点", c.text()); // 缺口不拉伸原文
    }

    @Test
    void overlappingAndNestedSegmentsAllGetCandidates() {
        Video video = TestData.video("v1");
        Keypoints kp = TestData.keypoints("kp1", "v1", 10, 30, 50);
        SourceLayer layer = new SourceLayer("l1", "v1", "研究者A", List.of(
                new Segment("outer", "外层", TestData.exact(8, 52)),
                new Segment("inner", "嵌套", TestData.exact(12, 28)),
                new Segment("overlap", "重叠", TestData.exact(28, 48))));
        Alignment al = AlignmentEngine.generate("al1", video, layer, kp, 5);
        assertEquals(3, al.candidates().size());
        assertTrue(al.candidates().stream().noneMatch(Candidate::gap));
    }
}
