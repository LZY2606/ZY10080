package langalign;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * 文件存储。三类数据分开保存：
 *   raw/      原始输入（视频时间码、关键点、研究者分段），写入后不可变
 *   derived/  派生结果（对齐候选、共识快照）
 *   events/   操作事件日志（追加写）
 *   requests/ 幂等记录（request_id -> 响应）
 * 所有文件写入均为“临时文件 + 原子 rename”，崩溃不会暴露半成品。
 */
public class Store {
    private final Path root;

    public Store(Path root) { this.root = root; }

    public Path root() { return root; }

    public void init() {
        try {
            for (String d : new String[]{"raw/videos", "raw/keypoints", "raw/layers",
                    "derived/alignments", "derived/consensus", "events", "requests"}) {
                Files.createDirectories(root.resolve(d));
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** 启动恢复：删除所有未完成的临时文件，半成品对外不可见。 */
    public void recover() {
        try (Stream<Path> walk = Files.walk(root)) {
            walk.filter(p -> p.getFileName().toString().endsWith(".tmp"))
                .forEach(p -> {
                    try { Files.deleteIfExists(p); } catch (IOException ignored) {}
                });
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public Path rawVideo(String id) { return root.resolve("raw/videos/" + id + ".json"); }
    public Path rawKeypoints(String id) { return root.resolve("raw/keypoints/" + id + ".json"); }
    public Path rawLayer(String id) { return root.resolve("raw/layers/" + id + ".json"); }
    public Path derivedAlignment(String id) { return root.resolve("derived/alignments/" + id + ".json"); }
    public Path derivedConsensus(String videoId) { return root.resolve("derived/consensus/" + videoId + ".json"); }
    public Path eventsLog(String videoId) { return root.resolve("events/" + videoId + ".jsonl"); }
    public Path requestPath(String requestId) { return root.resolve("requests/" + sanitize(requestId) + ".json"); }

    private static String sanitize(String id) { return id.replaceAll("[^A-Za-z0-9._-]", "_"); }

    public static void writeAtomic(Path path, byte[] bytes) {
        try {
            Path tmp = path.resolveSibling(path.getFileName() + ".tmp");
            Files.write(tmp, bytes,
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.SYNC);
            Files.move(tmp, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public static Optional<byte[]> read(Path path) {
        try {
            return Files.exists(path) ? Optional.of(Files.readAllBytes(path)) : Optional.empty();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
