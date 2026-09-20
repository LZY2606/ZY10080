package org.research.timeline.store;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.research.timeline.model.AlignmentResult;
import org.research.timeline.model.ConsensusSegment;
import org.research.timeline.model.FrameTable;
import org.research.timeline.model.PoseData;
import org.research.timeline.model.SourceSegment;
import org.research.timeline.service.ApiError;
import org.research.timeline.util.Hash;
import org.research.timeline.util.Json;

/**
 * Single-process, lock-serialized event-sourced store.
 *
 * <p>Three physically separate areas on disk:
 * <ul>
 *   <li>{@code raw/} - immutable original input payloads</li>
 *   <li>{@code events/} - append-only journal of operation events (authoritative)</li>
 *   <li>{@code derived/} - regenerable snapshots (alignment, current state)</li>
 * </ul>
 * In-memory state is rebuilt by replaying the journal, so a crash while writing
 * a derived snapshot can never expose partial derived data: the temp file is
 * atomically renamed only after a full fsync.</p>
 *
 * <p>Every mutating request carries a client-supplied {@code requestId}.
 * Replaying the same id returns the original response instead of producing a
 * second business result; reusing the id with a different body is a 409.</p>
 */
public final class Store implements AutoCloseable {

    public static final class State {
        public FrameTable frameTable;
        public final Map<String, SourceSegment> sources = new LinkedHashMap<>();
        public final Map<String, PoseData> poses = new LinkedHashMap<>();
        public final Map<String, ConsensusSegment> consensus = new LinkedHashMap<>();
        public final Map<String, AlignmentResult> alignments = new LinkedHashMap<>();
        public final List<Map<String, Object>> operationLog = new ArrayList<>();
    }

    private final Path root;
    private final Path rawDir;
    private final Path eventsDir;
    private final Path derivedDir;
    private final Path quarantineDir;
    private final Journal journal;
    private final State state = new State();
    private final Map<String, IdempotencyRecord> requestIndex = new LinkedHashMap<>();
    private long lastSeq;

    private record IdempotencyRecord(String method, String bodyHash, Object response) {
    }

    public Store(Path root) {
        this.root = root;
        this.rawDir = root.resolve("raw");
        this.eventsDir = root.resolve("events");
        this.derivedDir = root.resolve("derived");
        this.quarantineDir = root.resolve("quarantine");
        try {
            Files.createDirectories(rawDir);
            Files.createDirectories(eventsDir);
            Files.createDirectories(derivedDir);
            recover();
            this.journal = Journal.open(eventsDir.resolve("journal.log"));
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot initialize store at " + root, e);
        }
    }

    public State state() {
        return state;
    }

    public synchronized long nextSeq() {
        return lastSeq + 1;
    }

    /** Check an idempotency key. Returns the stored response to replay, or null for a new request. */
    public synchronized Object idempotentReplay(String requestId, String method, String bodyHash) {
        if (requestId == null || requestId.isBlank()) {
            throw ApiError.input("Missing required field 'requestId'");
        }
        IdempotencyRecord existing = requestIndex.get(requestId);
        if (existing == null) {
            return null;
        }
        if (!existing.method.equals(method) || !existing.bodyHash.equals(bodyHash)) {
            throw ApiError.conflict("requestId '" + requestId + "' was already used with a different request",
                    Map.of("originalMethod", existing.method, "replayedMethod", method,
                            "originalBodyHash", existing.bodyHash, "replayedBodyHash", bodyHash));
        }
        return existing.response();
    }

    /**
     * Persist an immutable raw input file, then append the event. The raw file
     * is fsynced first, so every committed event can always be traced to its
     * original bytes.
     */
    public synchronized Map<String, Object> commitInputEvent(String type, String requestId,
                                                             String rawFileName, String rawBody,
                                                             Map<String, Object> eventData,
                                                             Map<String, Object> response) {
        String bodyHash = Hash.sha256(rawBody);
        Object replay = idempotentReplay(requestId, type, bodyHash);
        if (replay != null) {
            return asMap(replay);
        }
        Path rawPath = rawDir.resolve(rawFileName);
        if (!Files.exists(rawPath)) {
            writeAtomic(rawPath, rawBody);
        }
        long seq = ++lastSeq;
        Map<String, Object> event = baseEvent(seq, type, requestId);
        event.put("rawFile", rawPath.getFileName().toString());
        event.put("rawHash", bodyHash);
        event.putAll(eventData);
        event.put("response", response);
        journal.append(event);
        applyEvent(event);
        requestIndex.put(requestId, new IdempotencyRecord(type, bodyHash, response));
        writeDerivedSnapshot();
        return response;
    }

    /** Append a decision/derived operation event (no raw input file). */
    public synchronized Map<String, Object> commitOperationEvent(String type, String requestId,
                                                                 String requestBody,
                                                                 Map<String, Object> eventData,
                                                                 Map<String, Object> response) {
        String bodyHash = Hash.sha256(requestBody);
        Object replay = idempotentReplay(requestId, type, bodyHash);
        if (replay != null) {
            return asMap(replay);
        }
        long seq = ++lastSeq;
        Map<String, Object> event = baseEvent(seq, type, requestId);
        event.put("requestHash", bodyHash);
        event.putAll(eventData);
        event.put("response", response);
        journal.append(event);
        applyEvent(event);
        requestIndex.put(requestId, new IdempotencyRecord(type, bodyHash, response));
        writeDerivedSnapshot();
        return response;
    }

    public synchronized void writeDerivedFile(String fileName, Object content) {
        writeAtomic(derivedDir.resolve(fileName), Json.pretty(content));
    }

    /**
     * Append a fully-formed historical event (used only for bundle re-import,
     * which must preserve original candidate/decision identifiers and lineage).
     */
    public synchronized void replayHistoricalEvent(Map<String, Object> event) {
        long seq = ++lastSeq;
        event = new LinkedHashMap<>(event);
        event.put("seq", seq);
        event.put("replayedFromBundle", true);
        journal.append(event);
        applyEvent(event);
        String requestId = String.valueOf(event.get("requestId"));
        String type = String.valueOf(event.get("type"));
        String hash = event.containsKey("rawHash") ? String.valueOf(event.get("rawHash"))
                : String.valueOf(event.getOrDefault("requestHash", "bundle"));
        requestIndex.put(requestId, new IdempotencyRecord(type, hash, Map.of(
                "replayedFromBundle", true, "requestId", requestId, "seq", seq, "type", type)));
        writeDerivedSnapshot();
    }

    /** Materialize a raw input file referenced by a replayed event. */
    public synchronized void materializeRaw(String fileName, String content) {
        Path target = rawDir.resolve(fileName);
        if (!Files.exists(target)) {
            writeAtomic(target, content);
        }
    }

    private Map<String, Object> baseEvent(long seq, String type, String requestId) {
        Map<String, Object> event = new LinkedHashMap<>();
        event.put("seq", seq);
        event.put("type", type);
        event.put("requestId", requestId);
        event.put("atEpochMillis", System.currentTimeMillis());
        return event;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object value) {
        return (Map<String, Object>) value;
    }

    // ------------------------------------------------------------------
    // Recovery: rebuild all state from the authoritative event journal.
    // ------------------------------------------------------------------

    private void recover() throws IOException {
        List<Map<String, Object>> events = Journal.recover(eventsDir.resolve("journal.log"), quarantineDir);
        for (Map<String, Object> event : events) {
            long seq = event.get("seq") instanceof Number n ? n.longValue() : 0L;
            if (seq <= lastSeq) {
                throw new IOException("Non-monotonic event sequence in journal");
            }
            lastSeq = seq;
            applyEvent(event);
            String requestId = String.valueOf(event.get("requestId"));
            String type = String.valueOf(event.get("type"));
            String hash = event.containsKey("rawHash") ? String.valueOf(event.get("rawHash"))
                    : String.valueOf(event.get("requestHash"));
            Object replayResponse = event.getOrDefault("response", Map.of(
                    "replayedFromJournal", true,
                    "requestId", requestId,
                    "seq", seq,
                    "type", type));
            requestIndex.put(requestId, new IdempotencyRecord(type, hash, replayResponse));
        }
        writeDerivedSnapshot();
    }

    private void applyEvent(Map<String, Object> event) {
        String type = String.valueOf(event.get("type"));
        switch (type) {
            case "frame_table_imported" -> applyFrameTable(event);
            case "segments_imported" -> applySegments(event);
            case "pose_imported" -> applyPose(event);
            case "alignment_generated" -> applyAlignment(event);
            case "consensus_decision" -> applyDecision(event);
            default -> throw new IllegalStateException("Unknown event type: " + type);
        }
        state.operationLog.add(event);
    }

    @SuppressWarnings("unchecked")
    private void applyFrameTable(Map<String, Object> event) {
        state.frameTable = FrameTable.fromImport((Map<String, Object>) event.get("frameTable"));
    }

    @SuppressWarnings("unchecked")
    private void applySegments(Map<String, Object> event) {
        FrameTable table = requireFrameTable();
        List<Object> segments = (List<Object>) event.get("segments");
        String sourceId = String.valueOf(event.get("sourceId"));
        for (Object item : segments) {
            Map<String, Object> raw = (Map<String, Object>) item;
            Map<String, Object> stored = (Map<String, Object>) raw.get("stored");
            SourceSegment segment = Segments.fromStoredJson(table, sourceId, stored);
            state.sources.put(segment.segmentId(), segment);
        }
    }

    @SuppressWarnings("unchecked")
    private void applyPose(Map<String, Object> event) {
        PoseData pose = PoseData.fromImport(String.valueOf(event.get("poseId")),
                (Map<String, Object>) event.get("payload"));
        state.poses.put(pose.poseId(), pose);
    }

    @SuppressWarnings("unchecked")
    private void applyAlignment(Map<String, Object> event) {
        AlignmentResult result = AlignmentCodec.decode((Map<String, Object>) event.get("result"));
        state.alignments.put(result.resultId(), result);
    }

 @SuppressWarnings("unchecked")
    private void applyDecision(Map<String, Object> event) {
        List<Object> created = (List<Object>) event.getOrDefault("createdSegments", List.of());
        List<String> supersededIds = (List<String>) event.getOrDefault("supersededIds", List.of());
        List<Object> updatedParents = (List<Object>) event.getOrDefault("updatedParents", List.of());
        for (String id : supersededIds) {
            ConsensusSegment old = state.consensus.get(id);
            if (old != null) {
                state.consensus.put(id, new ConsensusSegment(old.consensusId(), old.version(), "superseded",
                        old.text(), old.window(), old.evidenceSegmentIds(), old.evidenceCandidateIds(),
                        old.supersedes(), old.children(), old.poseFingerprint(), old.decisionId(),
                        old.actor(), old.eventSeq(), old.note()));
            }
        }
        for (Object item : created) {
            ConsensusSegment segment = ConsensusCodec.decode((Map<String, Object>) item);
            state.consensus.put(segment.consensusId(), segment);
        }
        for (Object item : updatedParents) {
            Map<String, Object> update = (Map<String, Object>) item;
            String parentId = String.valueOf(update.get("consensusId"));
            List<String> children = (List<String>) update.get("children");
            ConsensusSegment parent = state.consensus.get(parentId);
            if (parent != null) {
                state.consensus.put(parentId, parent.withChildren(children));
            }
        }
    }

    private FrameTable requireFrameTable() {
        if (state.frameTable == null) {
            throw ApiError.conflict("Frame table must be imported before any frame-anchored data");
        }
        return state.frameTable;
    }

    private void writeDerivedSnapshot() {
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("frameTable", state.frameTable == null ? null : state.frameTable.toJson());
        snapshot.put("sourceCount", state.sources.size());
        snapshot.put("poseCount", state.poses.size());
        snapshot.put("consensusCount", state.consensus.size());
        snapshot.put("alignmentCount", state.alignments.size());
        snapshot.put("lastSeq", lastSeq);
        writeAtomic(derivedDir.resolve("state.snapshot.json"), Json.pretty(snapshot));
    }

    /** Write bytes to a temp file, fsync, then atomically rename. No half files are observable. */
    static void writeAtomic(Path target, String content) {
        try {
            Path tmp = target.resolveSibling(target.getFileName() + ".tmp-" + UUID.randomUUID());
            Files.writeString(tmp, content, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING);
            try (java.nio.channels.FileChannel ch = java.nio.channels.FileChannel.open(tmp, StandardOpenOption.WRITE)) {
                ch.force(true);
            }
            try {
                Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (java.nio.file.AtomicMoveNotSupportedException e) {
                Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
            }
            // fsync the directory so the rename itself survives a crash.
            try (java.nio.channels.FileChannel dir = java.nio.channels.FileChannel.open(target.getParent())) {
                dir.force(true);
            } catch (IOException ignored) {
                // Directory fsync is not supported on every platform; best effort.
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Atomic write failed for " + target, e);
        }
    }

    public Path root() {
        return root;
    }

    @Override
    public synchronized void close() {
        journal.close();
    }
}
