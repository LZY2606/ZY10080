package langalign;

import langalign.Model.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ConsensusServiceTest {
    @TempDir Path dir;
    Store store;
    Repo repo;
    ConsensusService cs;
    Video video;
    Keypoints kp;
    Alignment al;

    @BeforeEach
    void setup() {
        store = new Store(dir);
        store.init();
        store.recover();
        repo = new Repo(store);
        cs = new ConsensusService(repo);
        video = TestData.video("v1");
        repo.saveVideo(video);
        kp = TestData.keypoints("kp1", "v1", 10, 30, 50, 70);
        repo.saveKeypoints(kp);
        SourceLayer layer = new SourceLayer("l1", "v1", "研究者A", List.of(
                new Segment("s1", "抬手", TestData.exact(8, 32)),
                new Segment("s2", "保持", TestData.fuzzy(28, 32, 48, 52)),
                new Segment("s3", "模糊无锚", TestData.fuzzy(88, 93, 95, 99))));
        repo.saveLayer(layer);
        al = AlignmentEngine.generate("al1", video, layer, kp, 5);
        repo.saveAlignment(al);
    }

    Candidate candidateOf(String sourceSegmentId) {
        return repo.loadAlignment("al1").orElseThrow().candidates().stream()
                .filter(c -> c.sourceSegmentId().equals(sourceSegmentId)).findFirst().orElseThrow();
    }

    @Test
    void acceptCreatesConsensusAndKeepsSourceUntouched() throws Exception {
        byte[] before = Files.readAllBytes(store.rawLayer("l1"));
        Candidate c = candidateOf("s1");
        ConsensusSegment seg = cs.accept("r1", "al1", c.id());
        assertEquals(1, seg.version());
        assertEquals("抬手", seg.text());
        assertEquals(kp.fingerprint(), seg.keypointFingerprint());
        assertEquals(10, seg.interval().start().low());
        assertEquals(30, seg.interval().end().low());
        assertTrue(seg.evidence().stream().anyMatch(e -> e.type().equals("sourceSegment")));
        // 来源层一个字节都没变
        assertArrayEquals(before, Files.readAllBytes(store.rawLayer("l1")));
        // 候选状态推进，不能重复接受
        assertThrows(ApiException.class, () -> cs.accept("r2", "al1", c.id()));
    }

    @Test
    void undecidableGapKeepsOriginalFuzzyInterval() {
        Candidate gap = candidateOf("s3");
        assertTrue(gap.gap());
        ConsensusSegment seg = cs.undecidableCandidate("r1", "al1", gap.id());
        assertEquals(ConsensusStatus.UNDECIDABLE, seg.status());
        assertEquals(Precision.FUZZY, seg.interval().start().precision());
        assertEquals(88, seg.interval().start().low());
        assertEquals(93, seg.interval().start().high());
        assertEquals("模糊无锚", seg.text()); // 原文不拉伸
    }

    @Test
    void splitThenMergeRoundTrip() {
        ConsensusSegment seg = cs.accept("r1", "al1", candidateOf("s1").id());
        List<ConsensusSegment> children = cs.split("r2", seg.id(), 20, 1);
        assertEquals(2, children.size());
        assertEquals(10, children.get(0).interval().start().low());
        assertEquals(20, children.get(0).interval().end().low());
        assertEquals(20, children.get(1).interval().start().low());
        assertEquals(30, children.get(1).interval().end().low());
        assertEquals(seg.id(), children.get(0).parentId());
        // 父片段已 SPLIT，不能再拆
        assertThrows(ApiException.class, () -> cs.split("r3", seg.id(), 25, 2));
        ConsensusSegment merged = cs.merge("r4",
                List.of(children.get(0).id(), children.get(1).id()), List.of(1L, 1L));
        assertEquals(10, merged.interval().start().low());
        assertEquals(30, merged.interval().end().low());
    }

    @Test
    void mergePreservesFuzzyBounds() {
        ConsensusSegment a = cs.accept("r1", "al1", candidateOf("s1").id());
        // 把 a 的区间改成模糊边界
        cs.edit("r2", a.id(), null, TestData.fuzzy(10, 12, 29, 31), 1);
        ConsensusSegment b = cs.accept("r3", "al1", candidateOf("s2").id());
        ConsensusSegment merged = cs.merge("r4", List.of(a.id(), b.id()), List.of(2L, 1L));
        assertEquals(Precision.FUZZY, merged.interval().start().precision());
        assertEquals(10, merged.interval().start().low());
        assertEquals(12, merged.interval().start().high());
        assertEquals(Precision.EXACT, merged.interval().end().precision());
    }

    @Test
    void concurrentEditConflictAndAutoMerge() {
        ConsensusSegment seg = cs.accept("r1", "al1", candidateOf("s1").id()); // v1, [10,31] "抬手"
        cs.edit("r-a", seg.id(), "抬手改", null, 1); // A 改文本 -> v2
        // B 基于 v1 只改区间：非重叠字段 -> 自动合入
        ConsensusService.EditOutcome o = cs.edit("r-b", seg.id(), null, TestData.exact(10, 28), 1);
        assertTrue(o.merged());
        assertEquals("抬手改", o.segment().text());
        assertEquals(28, o.segment().interval().end().low());
        // C 基于 v1 改文本（与 A 不同）-> 文本冲突
        ApiException e1 = assertThrows(ApiException.class,
                () -> cs.edit("r-c", seg.id(), "抬手再改", null, 1));
        assertEquals(409, e1.status);
        assertEquals(ApiException.STATE_CONFLICT, e1.type);
        assertTrue(e1.details.containsKey("textDiff"));
        // D 基于 v1 改区间且与当前 [10,28] 重叠 -> 范围冲突
        ApiException e2 = assertThrows(ApiException.class,
                () -> cs.edit("r-d", seg.id(), null, TestData.exact(12, 33), 1));
        assertEquals(409, e2.status);
        assertTrue(e2.details.containsKey("overlapRange"));
    }

    @Test
    void oldConsensusStaysBoundToOldFingerprint() {
        ConsensusSegment seg = cs.accept("r1", "al1", candidateOf("s1").id());
        String oldFp = seg.keypointFingerprint();
        // 换用新姿态算法 -> 新指纹
        Keypoints kp2 = new Keypoints("kp2", "v1", "pose-est", "2.0",
                Demo.fingerprint("pose-est", "2.0"), kp.frames());
        repo.saveKeypoints(kp2);
        assertNotEquals(oldFp, kp2.fingerprint());
        ConsensusSegment still = cs.layer("v1").segments().stream()
                .filter(s -> s.id().equals(seg.id())).findFirst().orElseThrow();
        assertEquals(oldFp, still.keypointFingerprint()); // 旧共识仍绑定旧指纹
    }
}
