package langalign;

import langalign.Model.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ExportImportTest {
    @Test
    void fuzzyIntervalsSurviveExportImportRoundTrip(@TempDir Path dir) {
        // 系统 1：含模糊区间的共识
        Store store1 = new Store(dir.resolve("a"));
        store1.init();
        store1.recover();
        Repo repo1 = new Repo(store1);
        ConsensusService cs1 = new ConsensusService(repo1);
        Video video = TestData.video("v1");
        repo1.saveVideo(video);
        Keypoints kp = TestData.keypoints("kp1", "v1", 10, 30, 50, 70);
        repo1.saveKeypoints(kp);
        SourceLayer layer = new SourceLayer("l1", "v1", "研究者A", List.of(
                new Segment("s1", "抬手", TestData.exact(8, 32)),
                new Segment("s2", "模糊无锚", TestData.fuzzy(88, 93, 95, 99))));
        repo1.saveLayer(layer);
        Alignment al = AlignmentEngine.generate("al1", video, layer, kp, 5);
        repo1.saveAlignment(al);
        cs1.accept("r1", "al1", al.candidates().get(0).id());
        cs1.undecidableCandidate("r2", "al1", al.candidates().get(1).id());

        Export.ExportDoc doc = Export.build(repo1, cs1, "v1");
        // 导出包含精度与证据
        Export.SegmentExport fuzzySeg = doc.consensus().stream()
                .filter(s -> s.text().equals("模糊无锚")).findFirst().orElseThrow();
        assertEquals(Precision.FUZZY, fuzzySeg.start().precision());
        assertNull(fuzzySeg.start().exact());
        assertFalse(doc.consensus().get(0).evidence().isEmpty());
        assertEquals(1, doc.gaps().size());

        // 系统 2：重新导入，模糊区间不得被压成精确点
        Store store2 = new Store(dir.resolve("b"));
        store2.init();
        store2.recover();
        Repo repo2 = new Repo(store2);
        repo2.saveVideo(video);
        ConsensusService cs2 = new ConsensusService(repo2);
        cs2.importLayer("r3", doc.videoId(), Export.toSegments(doc), doc.keypointFingerprint());

        Export.ExportDoc doc2 = Export.build(repo2, cs2, "v1");
        Export.SegmentExport fuzzy2 = doc2.consensus().stream()
                .filter(s -> s.text().equals("模糊无锚")).findFirst().orElseThrow();
        assertEquals(Precision.FUZZY, fuzzy2.start().precision());
        assertNull(fuzzy2.start().exact());
        assertEquals(88, fuzzy2.start().earliest());
        assertEquals(93, fuzzy2.start().latest());
        assertEquals(95, fuzzy2.end().earliest());
        assertEquals(99, fuzzy2.end().latest());
    }
}
