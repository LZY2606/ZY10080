package langalign;

import langalign.Model.*;

import java.util.ArrayList;
import java.util.List;

/**
 * 导出/导入。导出包含层次关系（parentId）、每个边界的精度、每个决定的证据引用；
 * 导入按原样还原模糊区间，绝不压缩成精确时间点。
 */
public final class Export {
    private Export() {}

    public record BoundExport(long earliest, Long exact, long latest, Precision precision) {}
    public record SegmentExport(String id, String text, BoundExport start, BoundExport end,
                                String status, long version, String parentId,
                                List<Evidence> evidence, String keypointFingerprint) {}
    public record GapExport(String candidateId, String sourceSegmentId, String text, String reason) {}
    public record ExportDoc(String format, String videoId, String keypointFingerprint, long exportedAt,
                            List<SegmentExport> consensus, List<GapExport> gaps,
                            List<SourceLayer> sources, List<Alignment> alignments) {}

    public static final String FORMAT = "pairwise-gsb-export/1";

    static BoundExport bound(FuzzyBound b) {
        return new BoundExport(b.low(), b.exact(), b.high(), b.precision());
    }

    static FuzzyBound toBound(BoundExport b) {
        return new FuzzyBound(b.earliest(), b.exact(), b.latest());
    }

    public static ExportDoc build(Repo repo, ConsensusService consensus, String videoId) {
        repo.loadVideo(videoId).orElseThrow(() -> ApiException.notFound("视频不存在: " + videoId));
        ConsensusLayer layer = consensus.layer(videoId);
        List<SegmentExport> segs = new ArrayList<>();
        for (ConsensusSegment s : layer.segments()) {
            segs.add(new SegmentExport(s.id(), s.text(), bound(s.interval().start()), bound(s.interval().end()),
                    s.status().name(), s.version(), s.parentId(), s.evidence(), s.keypointFingerprint()));
        }
        List<Alignment> alignments = repo.alignmentsOf(videoId);
        List<GapExport> gaps = new ArrayList<>();
        for (Alignment al : alignments) {
            for (Candidate c : al.candidates()) {
                if (c.gap()) gaps.add(new GapExport(c.id(), c.sourceSegmentId(), c.text(),
                        "允许偏差内无锚点（保持原区间，不拉伸）"));
            }
        }
        return new ExportDoc(FORMAT, videoId, layer.keypointFingerprint(), System.currentTimeMillis(),
                segs, gaps, repo.layersOf(videoId), alignments);
    }

    /** 导出文档 -> 共识层（导入路径；模糊边界逐字段还原）。 */
    public static List<ConsensusSegment> toSegments(ExportDoc doc) {
        List<ConsensusSegment> out = new ArrayList<>();
        for (SegmentExport s : doc.consensus()) {
            out.add(new ConsensusSegment(s.id(), doc.videoId(), s.text(),
                    new FuzzyInterval(toBound(s.start()), toBound(s.end())),
                    ConsensusStatus.valueOf(s.status()), s.version(), s.parentId(),
                    s.evidence() == null ? List.of() : s.evidence(), s.keypointFingerprint()));
        }
        return out;
    }
}
