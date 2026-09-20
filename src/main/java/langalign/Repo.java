package langalign;

import com.fasterxml.jackson.databind.JsonNode;
import langalign.Model.*;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

/** 类型化存取：raw（原始输入）/ derived（派生结果）/ requests（幂等记录）。 */
public class Repo {
    private final Store store;

    public Repo(Store store) { this.store = store; }

    public Store store() { return store; }

    // ---- raw: videos ----
    public void saveVideo(Video v) { Store.writeAtomic(store.rawVideo(v.id()), Json.toBytes(v)); }
    public Optional<Video> loadVideo(String id) {
        return Store.read(store.rawVideo(id)).map(b -> Json.fromBytes(b, Video.class));
    }
    public List<Video> listVideos() { return list(store.root().resolve("raw/videos"), Video.class); }

    // ---- raw: keypoints ----
    public void saveKeypoints(Keypoints k) { Store.writeAtomic(store.rawKeypoints(k.id()), Json.toBytes(k)); }
    public Optional<Keypoints> loadKeypoints(String id) {
        return Store.read(store.rawKeypoints(id)).map(b -> Json.fromBytes(b, Keypoints.class));
    }
    public List<Keypoints> listKeypoints() { return list(store.root().resolve("raw/keypoints"), Keypoints.class); }
    public Optional<Keypoints> latestKeypoints(String videoId) {
        return listKeypoints().stream().filter(k -> k.videoId().equals(videoId)).reduce((a, b) -> b);
    }

    // ---- raw: source layers ----
    public void saveLayer(SourceLayer l) { Store.writeAtomic(store.rawLayer(l.id()), Json.toBytes(l)); }
    public Optional<SourceLayer> loadLayer(String id) {
        return Store.read(store.rawLayer(id)).map(b -> Json.fromBytes(b, SourceLayer.class));
    }
    public List<SourceLayer> listLayers() { return list(store.root().resolve("raw/layers"), SourceLayer.class); }
    public List<SourceLayer> layersOf(String videoId) {
        return listLayers().stream().filter(l -> l.videoId().equals(videoId)).toList();
    }

    // ---- derived: alignments ----
    public void saveAlignment(Alignment a) { Store.writeAtomic(store.derivedAlignment(a.id()), Json.toBytes(a)); }
    public Optional<Alignment> loadAlignment(String id) {
        return Store.read(store.derivedAlignment(id)).map(b -> Json.fromBytes(b, Alignment.class));
    }
    public List<Alignment> alignmentsOf(String videoId) {
        return list(store.root().resolve("derived/alignments"), Alignment.class).stream()
                .filter(a -> a.videoId().equals(videoId)).toList();
    }

    // ---- derived: consensus snapshot (缓存；权威来源是事件日志) ----
    public void saveConsensusSnapshot(ConsensusLayer l) {
        Store.writeAtomic(store.derivedConsensus(l.videoId()), Json.toBytes(l));
    }

    // ---- requests: 幂等 ----
    public Optional<JsonNode> findRequestResponse(String requestId) {
        return Store.read(store.requestPath(requestId)).map(b -> {
            try { return Json.PRETTY.readTree(b); } catch (IOException e) { throw new UncheckedIOException(e); }
        });
    }
    public void saveRequestResponse(String requestId, JsonNode response) {
        Store.writeAtomic(store.requestPath(requestId), Json.toBytes(response));
    }

    private <T> List<T> list(Path dir, Class<T> type) {
        List<T> out = new ArrayList<>();
        if (!Files.isDirectory(dir)) return out;
        try (Stream<Path> s = Files.list(dir)) {
            s.filter(p -> p.getFileName().toString().endsWith(".json")).forEach(p ->
                    out.add(Json.fromBytes(Store.read(p).orElseThrow(), type)));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return out;
    }
}
