package org.research.timeline.store;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.nio.file.StandardOpenOption;
import org.research.timeline.util.Json;

/**
 * Append-only event journal. Each line is one complete, self-delimiting JSON
 * object. Appends are atomic with respect to recovery: a torn final line (crash
 * mid-write) is detected and quarantined, so half-written business results are
 * never exposed.
 */
public final class Journal implements AutoCloseable {

    private final Path file;
    private final java.nio.channels.FileChannel channel;

    private Journal(Path file, java.nio.channels.FileChannel channel) {
        this.file = file;
        this.channel = channel;
    }

    /** Open for appending, creating the file if needed. Parent directory must exist. */
    public static Journal open(Path file) {
        try {
            Files.createDirectories(file.getParent());
            java.nio.channels.FileChannel channel = java.nio.channels.FileChannel.open(file,
                    StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.APPEND);
            return new Journal(file, channel);
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot open journal " + file, e);
        }
    }

    /**
     * Append one event and force it (and the file metadata) to disk before returning.
     */
    public synchronized long append(Map<String, Object> event) {
        try {
            long seq = event.get("seq") instanceof Number n ? n.longValue() : -1L;
            byte[] payload = (Json.write(event) + '\n').getBytes(StandardCharsets.UTF_8);
            ByteBuffer buffer = ByteBuffer.wrap(payload);
            while (buffer.hasRemaining()) {
                channel.write(buffer);
            }
            channel.force(true);
            return seq;
        } catch (IOException e) {
            throw new UncheckedIOException("Journal append failed", e);
        }
    }

    /**
     * Read all complete events. A malformed final line means a crash happened
     * mid-append: the partial line is moved aside (never applied) and the caller
     * rebuilds from the last consistent state.
     */
    public static List<Map<String, Object>> recover(Path file, Path quarantineDir) throws IOException {
        if (!Files.exists(file)) {
            return List.of();
        }
        List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
        List<Map<String, Object>> events = new ArrayList<>();
        int validUpTo = -1;
        boolean torn = false;
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i);
            if (line.isBlank()) {
                continue;
            }
            try {
                Object parsed = Json.parse(line);
                if (parsed instanceof Map<?, ?>) {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> event = (Map<String, Object>) parsed;
                    events.add(event);
                    validUpTo = i;
                    continue;
                }
                throw new IllegalArgumentException("event is not a JSON object");
            } catch (RuntimeException bad) {
                // Any corruption at a non-final line is fatal; only a torn tail can be quarantined.
                if (i < lines.size() - 1) {
                    throw new IOException("Corrupt journal record at line " + (i + 1) + ": " + bad.getMessage());
                }
                torn = true;
            }
        }
        if (torn) {
            Files.createDirectories(quarantineDir);
            Path quarantine = quarantineDir.resolve("journal-torn-" + System.nanoTime() + ".log");
            Files.writeString(quarantine, lines.get(lines.size() - 1) + System.lineSeparator(), StandardCharsets.UTF_8);
            // Truncate the journal back to the last complete line.
            StringBuilder kept = new StringBuilder();
            for (int i = 0; i <= validUpTo; i++) {
                kept.append(lines.get(i)).append('\n');
            }
            Files.writeString(file, kept.toString(), StandardCharsets.UTF_8,
                    StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING);
        }
        return events;
    }

    @Override
    public synchronized void close() {
        try {
            channel.force(true);
            channel.close();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
