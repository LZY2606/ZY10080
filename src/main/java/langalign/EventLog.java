package langalign;

import langalign.Model.ConsensusLayer;
import langalign.Model.Event;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;

/**
 * 每个视频一条追加写事件日志（JSONL）。
 * 写入后立即 fsync；启动时若最后一行不完整（崩溃在落盘中途），
 * 截断到最后一条完整记录，半成品不会进入状态。
 */
public class EventLog {
    private final Path file;
    private long seq;

    public EventLog(Path file) {
        this.file = file;
        this.seq = recover();
    }

    private long recover() {
        if (!Files.exists(file)) return 0;
        try {
            byte[] all = Files.readAllBytes(file);
            long count = 0;
            int lastGood = 0;
            int pos = 0;
            while (pos < all.length) {
                int nl = pos;
                while (nl < all.length && all[nl] != '\n') nl++;
                if (nl >= all.length) break; // 最后一行没有换行：不完整，丢弃
                String line = new String(all, pos, nl - pos, StandardCharsets.UTF_8).trim();
                if (!line.isEmpty()) {
                    try {
                        Event e = Json.COMPACT.readValue(line, Event.class);
                        count = Math.max(count, e.seq());
                        lastGood = nl + 1;
                    } catch (Exception bad) {
                        break; // 无法解析的行及其后内容视为崩溃残留
                    }
                } else {
                    lastGood = nl + 1;
                }
                pos = nl + 1;
            }
            if (lastGood < all.length) {
                try (RandomAccessFile raf = new RandomAccessFile(file.toFile(), "rw")) {
                    raf.setLength(lastGood);
                }
            }
            return count;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public synchronized void append(Event event) {
        try {
            byte[] line = (Json.COMPACT.writeValueAsString(event) + "\n").getBytes(StandardCharsets.UTF_8);
            try (FileChannel ch = FileChannel.open(file,
                    StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.APPEND)) {
                ch.write(ByteBuffer.wrap(line));
                ch.force(true);
            }
            seq = Math.max(seq, event.seq());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public long nextSeq() { return seq + 1; }

    public List<Event> readAll() {
        List<Event> out = new ArrayList<>();
        if (!Files.exists(file)) return out;
        try {
            for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                line = line.trim();
                if (!line.isEmpty()) out.add(Json.COMPACT.readValue(line, Event.class));
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return out;
    }
}
