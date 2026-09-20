package langalign;

import langalign.Model.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class PersistenceTest {
    @TempDir Path dir;
    Store store;
    Repo repo;

    @BeforeEach
    void setup() {
        store = new Store(dir);
        store.init();
        store.recover();
        repo = new Repo(store);
    }

    private Alignment seedAlignment() {
        Video video = TestData.video("v1");
        repo.saveVideo(video);
        Keypoints kp = TestData.keypoints("kp1", "v1", 10, 30, 50, 70);
        repo.saveKeypoints(kp);
        SourceLayer layer = new SourceLayer("l1", "v1", "研究者A", List.of(
                new Segment("s1", "抬手", TestData.exact(8, 32))));
        repo.saveLayer(layer);
        Alignment al = AlignmentEngine.generate("al1", video, layer, kp, 5);
        repo.saveAlignment(al);
        return al;
    }

    @Test
    void tmpFilesRemovedOnRecover() throws Exception {
        Path tmp = store.rawVideo("ghost");
        Files.writeString(store.root().resolve("raw/videos/ghost.json.tmp"), "{\"partial\":");
        new Store(dir).recover();
        assertFalse(Files.exists(store.root().resolve("raw/videos/ghost.json.tmp")));
        assertTrue(repo.listVideos().isEmpty()); // 半成品不可见
    }

    @Test
    void partialEventLineTruncatedOnRestart() throws Exception {
        Alignment al = seedAlignment();
        ConsensusService cs = new ConsensusService(repo);
        cs.accept("r1", "al1", al.candidates().get(0).id());
        // 模拟崩溃：事件日志尾部留下半行
        Files.writeString(store.eventsLog("v1"), "{\"eventId\":\"ev-broken\",",
                StandardOpenOption.APPEND);
        // 重启：新服务从事件日志重建，半行被截断，状态完整
        ConsensusService cs2 = new ConsensusService(repo);
        ConsensusLayer layer = cs2.layer("v1");
        assertEquals(1, layer.segments().size());
        assertEquals("抬手", layer.segments().get(0).text());
        // 重建后还能继续写事件
        ConsensusSegment seg = layer.segments().get(0);
        cs2.edit("r2", seg.id(), "改名", null, 1);
        ConsensusService cs3 = new ConsensusService(repo);
        assertEquals("改名", cs3.layer("v1").segments().get(0).text());
    }

    @Test
    void corruptSnapshotDoesNotAffectRebuild() throws Exception {
        Alignment al = seedAlignment();
        ConsensusService cs = new ConsensusService(repo);
        cs.accept("r1", "al1", al.candidates().get(0).id());
        // 快照文件被写坏（崩溃在写快照中途）
        Files.writeString(store.derivedConsensus("v1"), "{partial-json");
        ConsensusService cs2 = new ConsensusService(repo);
        assertEquals(1, cs2.layer("v1").segments().size()); // 权威来源是事件日志
    }

    @Test
    void eventsAreTheSourceOfTruthAcrossRestart() {
        Alignment al = seedAlignment();
        ConsensusService cs = new ConsensusService(repo);
        ConsensusSegment seg = cs.accept("r1", "al1", al.candidates().get(0).id());
        cs.split("r2", seg.id(), 20, 1);
        ConsensusService cs2 = new ConsensusService(repo);
        List<ConsensusSegment> segs = cs2.layer("v1").segments();
        assertEquals(3, segs.size()); // 父 + 两个子
        assertEquals(2, segs.stream().filter(s -> s.status() == ConsensusStatus.ACTIVE).count());
    }
}
