package langalign;

import langalign.Model.*;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 共识层服务。接受/拆分/合并/无法判断/编辑都只产生新的共识层数据，
 * 绝不修改来源层。所有变更先追加事件日志（fsync），再更新内存与快照；
 * 重启后从事件日志重建，半成品不会暴露。
 */
public class ConsensusService {
    private final Repo repo;
    private final Map<String, EventLog> logs = new HashMap<>();
    private final Map<String, ConsensusLayer> layers = new HashMap<>();

    public ConsensusService(Repo repo) { this.repo = repo; }

    public record EditOutcome(ConsensusSegment segment, boolean merged) {}

    public synchronized ConsensusLayer layer(String videoId) {
        ConsensusLayer l = layers.get(videoId);
        if (l == null) {
            l = rebuild(videoId);
            layers.put(videoId, l);
        }
        return l;
    }

    private EventLog logOf(String videoId) {
        return logs.computeIfAbsent(videoId, v -> new EventLog(repo.store().eventsLog(v)));
    }

    private ConsensusLayer rebuild(String videoId) {
        List<ConsensusSegment> segs = new ArrayList<>();
        for (Event e : logOf(videoId).readAll()) {
            if ("IMPORT".equals(e.op()) && e.layer() != null) {
                segs = new ArrayList<>(e.layer().segments());
            } else if (e.segment() != null) {
                segs.removeIf(s -> s.id().equals(e.segment().id()));
                segs.add(e.segment());
            }
        }
        String fp = segs.isEmpty()
                ? repo.latestKeypoints(videoId).map(Keypoints::fingerprint).orElse(null)
                : segs.get(segs.size() - 1).keypointFingerprint();
        return new ConsensusLayer(videoId, fp, segs);
    }

    private void append(String videoId, String requestId, String op, ConsensusSegment seg, String note) {
        EventLog log = logOf(videoId);
        log.append(new Event("ev-" + UUID.randomUUID(), log.nextSeq(), System.currentTimeMillis(),
                requestId, op, seg != null ? seg.id() : null, seg, null, note));
    }

    private void upsert(ConsensusSegment seg) {
        ConsensusLayer l = layer(seg.videoId());
        List<ConsensusSegment> segs = new ArrayList<>(l.segments());
        segs.removeIf(s -> s.id().equals(seg.id()));
        segs.add(seg);
        ConsensusLayer updated = new ConsensusLayer(l.videoId(), seg.keypointFingerprint(), segs);
        layers.put(seg.videoId(), updated);
        repo.saveConsensusSnapshot(updated);
    }

    private ConsensusSegment findSegment(String segmentId) {
        for (Video v : repo.listVideos()) {
            for (ConsensusSegment s : layer(v.id()).segments()) {
                if (s.id().equals(segmentId)) return s;
            }
        }
        throw ApiException.notFound("共识片段不存在: " + segmentId);
    }

    private static void checkActive(ConsensusSegment seg) {
        if (seg.status() != ConsensusStatus.ACTIVE)
            throw ApiException.conflict("片段状态为 " + seg.status() + "，不可再编辑");
    }

    private static void checkVersion(ConsensusSegment seg, long baseVersion) {
        if (seg.version() != baseVersion)
            throw ApiException.conflict("版本过期：当前 v" + seg.version() + "，请求基于 v" + baseVersion);
    }

    // ---- 接受候选：只产生新的共识层数据 ----
    public synchronized ConsensusSegment accept(String requestId, String alignmentId, String candidateId) {
        Alignment al = repo.loadAlignment(alignmentId)
                .orElseThrow(() -> ApiException.notFound("对齐不存在: " + alignmentId));
        Candidate c = al.candidates().stream().filter(x -> x.id().equals(candidateId)).findFirst()
                .orElseThrow(() -> ApiException.notFound("候选不存在: " + candidateId));
        if (c.status() != CandidateStatus.PENDING) throw ApiException.conflict("候选已被处理: " + c.status());
        if (c.gap()) throw ApiException.conflict("缺口候选不能接受；可标记为无法判断");

        String segId = "cs-" + UUID.randomUUID();
        List<Evidence> ev = List.of(
                new Evidence("candidate", c.id()),
                new Evidence("sourceSegment", c.sourceSegmentId()),
                new Evidence("alignment", al.id()),
                new Evidence("keypoints", al.keypointsId() + "@" + al.keypointFingerprint()));
        ConsensusSegment seg = new ConsensusSegment(segId, al.videoId(), c.text(), c.target(),
                ConsensusStatus.ACTIVE, 1, null, ev, al.keypointFingerprint());
        append(al.videoId(), requestId, "ACCEPT", seg, "接受候选 " + c.id());
        upsert(seg);
        updateCandidateStatus(al, c.id(), CandidateStatus.ACCEPTED);
        return seg;
    }

    // ---- 声明无法判断（候选级）：进入共识层，区间取候选目标或原始模糊区间，不拉伸原文 ----
    public synchronized ConsensusSegment undecidableCandidate(String requestId, String alignmentId, String candidateId) {
        Alignment al = repo.loadAlignment(alignmentId)
                .orElseThrow(() -> ApiException.notFound("对齐不存在: " + alignmentId));
        Candidate c = al.candidates().stream().filter(x -> x.id().equals(candidateId)).findFirst()
                .orElseThrow(() -> ApiException.notFound("候选不存在: " + candidateId));
        if (c.status() != CandidateStatus.PENDING) throw ApiException.conflict("候选已被处理: " + c.status());

        FuzzyInterval interval = c.target();
        if (interval == null) {
            SourceLayer layer = repo.loadLayer(al.sourceLayerId())
                    .orElseThrow(() -> ApiException.notFound("来源层不存在: " + al.sourceLayerId()));
            interval = layer.segments().stream().filter(s -> s.id().equals(c.sourceSegmentId())).findFirst()
                    .orElseThrow(() -> ApiException.notFound("来源片段不存在: " + c.sourceSegmentId()))
                    .interval();
        }
        String segId = "cs-" + UUID.randomUUID();
        List<Evidence> ev = List.of(
                new Evidence("candidate", c.id()),
                new Evidence("sourceSegment", c.sourceSegmentId()),
                new Evidence("alignment", al.id()));
        ConsensusSegment seg = new ConsensusSegment(segId, al.videoId(), c.text(), interval,
                ConsensusStatus.UNDECIDABLE, 1, null, ev, al.keypointFingerprint());
        append(al.videoId(), requestId, "UNDECIDABLE", seg, "候选 " + c.id() + " 无法判断");
        upsert(seg);
        updateCandidateStatus(al, c.id(), CandidateStatus.UNDECIDABLE);
        return seg;
    }

    // ---- 声明无法判断（共识片段级） ----
    public synchronized ConsensusSegment undecidableSegment(String requestId, String segmentId, long baseVersion) {
        ConsensusSegment seg = findSegment(segmentId);
        checkActive(seg);
        checkVersion(seg, baseVersion);
        ConsensusSegment updated = new ConsensusSegment(seg.id(), seg.videoId(), seg.text(), seg.interval(),
                ConsensusStatus.UNDECIDABLE, seg.version() + 1, seg.parentId(), seg.evidence(), seg.keypointFingerprint());
        append(seg.videoId(), requestId, "UNDECIDABLE", updated, "片段标记无法判断");
        upsert(updated);
        return updated;
    }

    // ---- 拆分 ----
    public synchronized List<ConsensusSegment> split(String requestId, String segmentId, long atFrame, long baseVersion) {
        ConsensusSegment seg = findSegment(segmentId);
        checkActive(seg);
        checkVersion(seg, baseVersion);
        Video video = repo.loadVideo(seg.videoId())
                .orElseThrow(() -> ApiException.notFound("视频不存在: " + seg.videoId()));
        if (!video.track().hasFrame(atFrame))
            throw ApiException.badInput("切分帧 " + atFrame + " 不在时间码轨上（丢帧位置不可切分）");
        FuzzyInterval in = seg.interval();
        if (!(atFrame > in.start().high() && atFrame < in.end().low()))
            throw ApiException.badInput("切分帧必须位于区间内部（需避开模糊边界范围）");

        List<Evidence> ev = new ArrayList<>(seg.evidence());
        ev.add(new Evidence("parent", seg.id()));
        ConsensusSegment c1 = new ConsensusSegment("cs-" + UUID.randomUUID(), seg.videoId(), seg.text(),
                new FuzzyInterval(in.start(), FuzzyBound.exact(atFrame)), ConsensusStatus.ACTIVE, 1,
                seg.id(), ev, seg.keypointFingerprint());
        ConsensusSegment c2 = new ConsensusSegment("cs-" + UUID.randomUUID(), seg.videoId(), seg.text(),
                new FuzzyInterval(FuzzyBound.exact(atFrame), in.end()), ConsensusStatus.ACTIVE, 1,
                seg.id(), ev, seg.keypointFingerprint());
        ConsensusSegment parent = new ConsensusSegment(seg.id(), seg.videoId(), seg.text(), seg.interval(),
                ConsensusStatus.SPLIT, seg.version() + 1, seg.parentId(), seg.evidence(), seg.keypointFingerprint());
        append(seg.videoId(), requestId, "SPLIT", parent, "拆分为 " + c1.id() + ", " + c2.id());
        append(seg.videoId(), requestId, "SPLIT_CHILD", c1, null);
        append(seg.videoId(), requestId, "SPLIT_CHILD", c2, null);
        upsert(parent); upsert(c1); upsert(c2);
        return List.of(c1, c2);
    }

    // ---- 合并：保留模糊边界（取最左片段的起边界、最右片段的止边界） ----
    public synchronized ConsensusSegment merge(String requestId, List<String> segmentIds, List<Long> baseVersions) {
        if (segmentIds == null || segmentIds.size() < 2) throw ApiException.badInput("合并至少需要两个片段");
        if (baseVersions == null || baseVersions.size() != segmentIds.size())
            throw ApiException.badInput("baseVersions 数量必须与 segmentIds 一致");
        List<ConsensusSegment> segs = new ArrayList<>();
        for (int i = 0; i < segmentIds.size(); i++) {
            ConsensusSegment s = findSegment(segmentIds.get(i));
            checkActive(s);
            checkVersion(s, baseVersions.get(i));
            segs.add(s);
        }
        String videoId = segs.get(0).videoId();
        if (segs.stream().anyMatch(s -> !s.videoId().equals(videoId)))
            throw ApiException.badInput("不能跨视频合并");

        ConsensusSegment left = segs.stream().min((a, b) -> Long.compare(a.interval().start().low(), b.interval().start().low())).get();
        ConsensusSegment right = segs.stream().max((a, b) -> Long.compare(a.interval().end().high(), b.interval().end().high())).get();
        String text = String.join(" ", segs.stream().map(ConsensusSegment::text).toList());
        List<Evidence> ev = new ArrayList<>();
        for (ConsensusSegment s : segs) ev.add(new Evidence("parent", s.id()));
        ConsensusSegment merged = new ConsensusSegment("cs-" + UUID.randomUUID(), videoId, text,
                new FuzzyInterval(left.interval().start(), right.interval().end()), ConsensusStatus.ACTIVE, 1,
                null, ev, segs.get(0).keypointFingerprint());
        append(videoId, requestId, "MERGE", merged, "合并 " + segmentIds);
        upsert(merged);
        for (ConsensusSegment s : segs) {
            ConsensusSegment p = new ConsensusSegment(s.id(), s.videoId(), s.text(), s.interval(),
                    ConsensusStatus.MERGED, s.version() + 1, s.parentId(), s.evidence(), s.keypointFingerprint());
            append(videoId, requestId, "MERGE_PARENT", p, "并入 " + merged.id());
            upsert(p);
        }
        return merged;
    }

    // ---- 编辑（并发控制）：版本一致直接应用；否则按字段三方合并，重叠/文本冲突返回 409 ----
    public synchronized EditOutcome edit(String requestId, String segmentId, String newText,
                                         FuzzyInterval newInterval, long baseVersion) {
        ConsensusSegment cur = findSegment(segmentId);
        checkActive(cur);
        if (newInterval != null) validateInterval(cur.videoId(), newInterval);
        if (cur.version() == baseVersion) {
            ConsensusSegment updated = new ConsensusSegment(cur.id(), cur.videoId(),
                    newText != null ? newText : cur.text(),
                    newInterval != null ? newInterval : cur.interval(),
                    cur.status(), cur.version() + 1, cur.parentId(), cur.evidence(), cur.keypointFingerprint());
            append(cur.videoId(), requestId, "EDIT", updated, null);
            upsert(updated);
            return new EditOutcome(updated, false);
        }
        ConsensusSegment base = snapshotAt(cur.videoId(), segmentId, baseVersion);
        if (base == null) throw ApiException.conflict("找不到基准版本 v" + baseVersion + "，无法合并");

        boolean myText = newText != null && !newText.equals(base.text());
        boolean theirText = !cur.text().equals(base.text());
        boolean myInt = newInterval != null && !newInterval.equals(base.interval());
        boolean theirInt = !cur.interval().equals(base.interval());

        boolean textConflict = myText && theirText && !newText.equals(cur.text());
        boolean intervalConflict = myInt && theirInt && overlaps(newInterval, cur.interval());
        if (textConflict || intervalConflict) {
            Map<String, Object> details = new LinkedHashMap<>();
            details.put("segmentId", segmentId);
            details.put("currentVersion", cur.version());
            if (textConflict) {
                details.put("textDiff", Map.of("base", base.text(), "theirs", cur.text(),
                        "yours", newText == null ? base.text() : newText));
            }
            if (intervalConflict) {
                long s = Math.max(newInterval.start().low(), cur.interval().start().low());
                long e = Math.min(newInterval.end().high(), cur.interval().end().high());
                details.put("overlapRange", Map.of("startFrame", s, "endFrame", e));
            }
            throw ApiException.conflict("并发编辑冲突", details);
        }
        ConsensusSegment merged = new ConsensusSegment(cur.id(), cur.videoId(),
                myText ? newText : cur.text(),
                myInt ? newInterval : cur.interval(),
                cur.status(), cur.version() + 1, cur.parentId(), cur.evidence(), cur.keypointFingerprint());
        append(cur.videoId(), requestId, "EDIT", merged, "自动合入非重叠变更");
        upsert(merged);
        return new EditOutcome(merged, true);
    }

    // ---- 导入：整层替换（作为一条 IMPORT 事件），模糊区间原样保留 ----
    public synchronized ConsensusLayer importLayer(String requestId, String videoId,
                                                   List<ConsensusSegment> segments, String fingerprint) {
        repo.loadVideo(videoId).orElseThrow(() -> ApiException.notFound("视频不存在: " + videoId));
        for (ConsensusSegment s : segments) validateInterval(videoId, s.interval());
        ConsensusLayer l = new ConsensusLayer(videoId, fingerprint, List.copyOf(segments));
        EventLog log = logOf(videoId);
        log.append(new Event("ev-" + UUID.randomUUID(), log.nextSeq(), System.currentTimeMillis(),
                requestId, "IMPORT", null, null, l, "导入共识层"));
        layers.put(videoId, l);
        repo.saveConsensusSnapshot(l);
        return l;
    }

    private void validateInterval(String videoId, FuzzyInterval interval) {
        Video v = repo.loadVideo(videoId).orElseThrow(() -> ApiException.notFound("视频不存在: " + videoId));
        long first = v.track().frames().get(0).frameId();
        long last = v.track().frames().get(v.track().frames().size() - 1).frameId();
        if (interval.start().low() < first || interval.end().high() > last)
            throw ApiException.badInput("区间超出视频帧范围 [" + first + ", " + last + "]");
    }

    private ConsensusSegment snapshotAt(String videoId, String segmentId, long version) {
        ConsensusSegment found = null;
        for (Event e : logOf(videoId).readAll()) {
            if (e.segment() != null && e.segment().id().equals(segmentId) && e.segment().version() <= version) {
                if (found == null || e.segment().version() > found.version()) found = e.segment();
            }
        }
        return found;
    }

    private static boolean overlaps(FuzzyInterval a, FuzzyInterval b) {
        return a.start().low() <= b.end().high() && b.start().low() <= a.end().high();
    }

    private void updateCandidateStatus(Alignment al, String candidateId, CandidateStatus status) {
        List<Candidate> updated = new ArrayList<>();
        for (Candidate c : al.candidates()) {
            updated.add(c.id().equals(candidateId)
                    ? new Candidate(c.id(), c.sourceSegmentId(), c.text(), c.target(), c.gap(), status)
                    : c);
        }
        repo.saveAlignment(new Alignment(al.id(), al.videoId(), al.sourceLayerId(), al.keypointsId(),
                al.keypointFingerprint(), al.toleranceFrames(), updated));
    }
}
