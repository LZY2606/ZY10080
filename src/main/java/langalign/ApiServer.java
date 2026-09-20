package langalign;

import com.fasterxml.jackson.databind.JsonNode;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import langalign.Model.*;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.function.Supplier;

/**
 * HTTP API。错误分类：400 INPUT_FORMAT（输入格式）/ 404 NOT_FOUND /
 * 409 STATE_CONFLICT（状态冲突）/ 500 INTERNAL（内部故障）。
 * 所有变更端点要求 request_id：相同 request_id 重放返回首次结果，不产生第二份业务数据。
 */
public class ApiServer {
    private final HttpServer server;
    private final Repo repo;
    private final ConsensusService consensus;

    record ApiResult(int status, Object body, boolean replayed) {}

    public ApiServer(int port, Path dataDir) throws IOException {
        Store store = new Store(dataDir);
        store.init();
        store.recover(); // 启动恢复：清理半成品临时文件
        this.repo = new Repo(store);
        this.consensus = new ConsensusService(repo);
        this.server = HttpServer.create(new InetSocketAddress("127.0.0.1", port), 0);
        server.createContext("/api", this::handleApi);
        server.createContext("/", this::handleStatic);
        server.setExecutor(Executors.newCachedThreadPool());
    }

    public void start() { server.start(); }
    public void stop() { server.stop(0); }
    public int port() { return server.getAddress().getPort(); }
    public Repo repo() { return repo; }
    public ConsensusService consensus() { return consensus; }

    // ---------------- API ----------------

    private void handleApi(HttpExchange ex) {
        try {
            ApiResult r = route(ex.getRequestMethod(), ex.getRequestURI().getPath(), readBody(ex));
            respond(ex, r.status(), Json.toBytes(r.body()), r.replayed());
        } catch (ApiException e) {
            respondQuietly(ex, e.status, errorBody(e.type, e.getMessage(), e.details));
        } catch (IllegalArgumentException e) {
            respondQuietly(ex, 400, errorBody(ApiException.INPUT_FORMAT, e.getMessage(), null));
        } catch (Exception e) {
            e.printStackTrace();
            respondQuietly(ex, 500, errorBody(ApiException.INTERNAL, "内部故障: " + e.getClass().getSimpleName(), null));
        }
    }

    private ApiResult route(String method, String path, JsonNode body) throws IOException {
        String[] p = path.split("/");
        // p: ["", "api", ...]
        if (method.equals("POST") && path.equals("/api/demo/seed")) {
            return idempotent(body, () -> new ApiResult(200, Demo.seed(repo), false));
        }
        if (method.equals("POST") && path.equals("/api/import")) {
            return idempotent(body, () -> {
                JsonNode docNode = require(body, "doc");
                Export.ExportDoc doc;
                try {
                    doc = Json.PRETTY.treeToValue(docNode, Export.ExportDoc.class);
                } catch (Exception e) {
                    throw ApiException.badInput("导出文档格式不合法: " + e.getMessage());
                }
                if (!Export.FORMAT.equals(doc.format()))
                    throw ApiException.badInput("不支持的导出格式: " + doc.format());
                ConsensusLayer l = consensus.importLayer(requestId(body), doc.videoId(),
                        Export.toSegments(doc), doc.keypointFingerprint());
                return new ApiResult(200, l, false);
            });
        }
        if (method.equals("POST") && path.equals("/api/consensus/accept")) {
            return idempotent(body, () -> new ApiResult(200,
                    consensus.accept(requestId(body), text(body, "alignmentId"), text(body, "candidateId")), false));
        }
        if (method.equals("POST") && path.equals("/api/consensus/undecidable")) {
            return idempotent(body, () -> {
                if (body.hasNonNull("candidateId")) {
                    return new ApiResult(200, consensus.undecidableCandidate(requestId(body),
                            text(body, "alignmentId"), text(body, "candidateId")), false);
                }
                return new ApiResult(200, consensus.undecidableSegment(requestId(body),
                        text(body, "segmentId"), body.required("baseVersion").asLong()), false);
            });
        }
        if (method.equals("POST") && path.equals("/api/consensus/split")) {
            return idempotent(body, () -> new ApiResult(200, consensus.split(requestId(body),
                    text(body, "segmentId"), body.required("atFrame").asLong(),
                    body.required("baseVersion").asLong()), false));
        }
        if (method.equals("POST") && path.equals("/api/consensus/merge")) {
            return idempotent(body, () -> {
                List<String> ids = new ArrayList<>();
                body.required("segmentIds").forEach(n -> ids.add(n.asText()));
                List<Long> versions = new ArrayList<>();
                body.required("baseVersions").forEach(n -> versions.add(n.asLong()));
                return new ApiResult(200, consensus.merge(requestId(body), ids, versions), false);
            });
        }
        if (method.equals("POST") && path.equals("/api/consensus/edit")) {
            return idempotent(body, () -> {
                String newText = body.hasNonNull("text") ? body.get("text").asText() : null;
                FuzzyInterval interval = body.hasNonNull("interval")
                        ? parseInterval(body.get("interval")) : null;
                ConsensusService.EditOutcome outcome = consensus.edit(requestId(body),
                        text(body, "segmentId"), newText, interval, body.required("baseVersion").asLong());
                Map<String, Object> resp = new LinkedHashMap<>();
                resp.put("segment", outcome.segment());
                resp.put("merged", outcome.merged());
                return new ApiResult(200, resp, false);
            });
        }
        if (p.length == 3 && p[2].equals("videos")) {
            if (method.equals("GET")) return new ApiResult(200, repo.listVideos(), false);
            if (method.equals("POST")) return idempotent(body, () -> {
                String id = body.hasNonNull("id") ? body.get("id").asText() : "v-" + UUID.randomUUID();
                String name = body.hasNonNull("name") ? body.get("name").asText() : id;
                List<FrameTiming> frames = new ArrayList<>();
                for (JsonNode f : body.required("frames")) {
                    frames.add(new FrameTiming(f.required("frameId").asLong(), f.required("ptsSeconds").asDouble()));
                }
                Video v = new Video(id, name, new TimecodeTrack(frames));
                repo.saveVideo(v);
                return new ApiResult(200, v, false);
            });
        }
        if (p.length >= 4 && p[2].equals("videos")) {
            String videoId = p[3];
            if (p.length == 4 && method.equals("GET")) return new ApiResult(200, videoState(videoId), false);
            if (p.length == 5 && p[4].equals("consensus") && method.equals("GET"))
                return new ApiResult(200, consensus.layer(videoId), false);
            if (p.length == 5 && p[4].equals("export") && method.equals("GET"))
                return new ApiResult(200, Export.build(repo, consensus, videoId), false);
            if (p.length == 5 && p[4].equals("keypoints") && method.equals("POST")) {
                return idempotent(body, () -> {
                    repo.loadVideo(videoId).orElseThrow(() -> ApiException.notFound("视频不存在: " + videoId));
                    String id = body.hasNonNull("id") ? body.get("id").asText() : "kp-" + UUID.randomUUID();
                    String algorithm = text(body, "algorithm");
                    String version = text(body, "version");
                    List<KeypointFrame> frames = new ArrayList<>();
                    for (JsonNode f : body.required("frames")) {
                        List<List<Double>> points = new ArrayList<>();
                        for (JsonNode pt : f.required("points")) {
                            points.add(List.of(pt.get(0).asDouble(), pt.get(1).asDouble()));
                        }
                        frames.add(new KeypointFrame(f.required("frameId").asLong(), points));
                    }
                    Keypoints kp = new Keypoints(id, videoId, algorithm, version,
                            Demo.fingerprint(algorithm, version), frames);
                    repo.saveKeypoints(kp);
                    return new ApiResult(200, kp, false);
                });
            }
            if (p.length == 5 && p[4].equals("layers") && method.equals("POST")) {
                return idempotent(body, () -> {
                    Video video = repo.loadVideo(videoId)
                            .orElseThrow(() -> ApiException.notFound("视频不存在: " + videoId));
                    String id = body.hasNonNull("id") ? body.get("id").asText() : "layer-" + UUID.randomUUID();
                    List<Segment> segments = new ArrayList<>();
                    int n = 0;
                    for (JsonNode s : body.required("segments")) {
                        String sid = s.hasNonNull("id") ? s.get("id").asText() : id + "-s" + (n++);
                        segments.add(new Segment(sid, s.required("text").asText(), parseInterval(s.required("interval"))));
                    }
                    SourceLayer layer = new SourceLayer(id, videoId, text(body, "researcher"), segments);
                    validateLayer(video, layer);
                    repo.saveLayer(layer);
                    return new ApiResult(200, layer, false);
                });
            }
            if (p.length == 5 && p[4].equals("alignments") && method.equals("POST")) {
                return idempotent(body, () -> {
                    Video video = repo.loadVideo(videoId)
                            .orElseThrow(() -> ApiException.notFound("视频不存在: " + videoId));
                    SourceLayer layer = repo.loadLayer(text(body, "sourceLayerId"))
                            .orElseThrow(() -> ApiException.notFound("来源层不存在"));
                    Keypoints kp = repo.loadKeypoints(text(body, "keypointsId"))
                            .orElseThrow(() -> ApiException.notFound("关键点不存在"));
                    int tol = body.required("toleranceFrames").asInt();
                    if (tol < 0) throw ApiException.badInput("toleranceFrames 不能为负");
                    Alignment al = AlignmentEngine.generate("al-" + UUID.randomUUID(), video, layer, kp, tol);
                    repo.saveAlignment(al);
                    return new ApiResult(200, al, false);
                });
            }
        }
        throw ApiException.notFound("路由不存在: " + method + " " + path);
    }

    private Map<String, Object> videoState(String videoId) {
        Video video = repo.loadVideo(videoId).orElseThrow(() -> ApiException.notFound("视频不存在: " + videoId));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("video", video);
        out.put("keypoints", repo.listKeypoints().stream().filter(k -> k.videoId().equals(videoId)).toList());
        out.put("layers", repo.layersOf(videoId));
        out.put("alignments", repo.alignmentsOf(videoId));
        out.put("consensus", consensus.layer(videoId));
        return out;
    }

    private void validateLayer(Video video, SourceLayer layer) {
        long first = video.track().frames().get(0).frameId();
        long last = video.track().frames().get(video.track().frames().size() - 1).frameId();
        for (Segment s : layer.segments()) {
            if (s.interval().start().low() < first || s.interval().end().high() > last)
                throw ApiException.badInput("片段 " + s.id() + " 超出视频帧范围 [" + first + ", " + last + "]");
        }
    }

    static FuzzyInterval parseInterval(JsonNode node) {
        return new FuzzyInterval(parseBound(node.required("start")), parseBound(node.required("end")));
    }

    static FuzzyBound parseBound(JsonNode node) {
        Long earliest = node.hasNonNull("earliest") ? node.get("earliest").asLong() : null;
        Long exact = node.hasNonNull("exact") ? node.get("exact").asLong() : null;
        Long latest = node.hasNonNull("latest") ? node.get("latest").asLong() : null;
        return new FuzzyBound(earliest, exact, latest);
    }

    // ---------------- 幂等 ----------------

    private ApiResult idempotent(JsonNode body, Supplier<ApiResult> action) throws IOException {
        String rid = requestId(body);
        var existing = repo.findRequestResponse(rid);
        if (existing.isPresent()) return new ApiResult(200, existing.get(), true);
        ApiResult r = action.get();
        repo.saveRequestResponse(rid, Json.PRETTY.valueToTree(r.body()));
        return r;
    }

    private static String requestId(JsonNode body) {
        if (body == null || !body.hasNonNull("request_id") || body.get("request_id").asText().isBlank())
            throw ApiException.badInput("缺少 request_id（幂等键）");
        return body.get("request_id").asText();
    }

    // ---------------- 工具 ----------------

    private static String text(JsonNode body, String field) {
        if (body == null || !body.hasNonNull(field)) throw ApiException.badInput("缺少字段: " + field);
        return body.get(field).asText();
    }

    private static JsonNode require(JsonNode body, String field) {
        if (body == null || !body.hasNonNull(field)) throw ApiException.badInput("缺少字段: " + field);
        return body.get(field);
    }

    private static JsonNode readBody(HttpExchange ex) throws IOException {
        try (InputStream in = ex.getRequestBody()) {
            byte[] bytes = in.readAllBytes();
            if (bytes.length == 0) return null;
            try {
                return Json.PRETTY.readTree(bytes);
            } catch (Exception e) {
                throw ApiException.badInput("请求体不是合法 JSON");
            }
        }
    }

    private static Map<String, Object> errorBody(String type, String message, Object details) {
        Map<String, Object> err = new LinkedHashMap<>();
        err.put("type", type);
        err.put("message", message);
        if (details != null) err.put("details", details);
        return Map.of("error", err);
    }

    private void respondQuietly(HttpExchange ex, int status, Object body) {
        try {
            respond(ex, status, Json.toBytes(body), false);
        } catch (IOException ignored) {}
    }

    private static void respond(HttpExchange ex, int status, byte[] bytes, boolean replayed) throws IOException {
        ex.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        if (replayed) ex.getResponseHeaders().set("X-Replayed", "true");
        ex.sendResponseHeaders(status, bytes.length);
        try (OutputStream os = ex.getResponseBody()) {
            os.write(bytes);
        }
    }

    // ---------------- 静态页面 ----------------

    private void handleStatic(HttpExchange ex) throws IOException {
        String path = ex.getRequestURI().getPath();
        if (path.equals("/")) path = "/index.html";
        if (!path.matches("/[A-Za-z0-9._-]+")) {
            ex.sendResponseHeaders(404, -1);
            ex.close();
            return;
        }
        String resource = "/static" + path;
        try (InputStream in = ApiServer.class.getResourceAsStream(resource)) {
            if (in == null) {
                ex.sendResponseHeaders(404, -1);
                ex.close();
                return;
            }
            byte[] bytes = in.readAllBytes();
            String type = path.endsWith(".html") ? "text/html; charset=utf-8"
                    : path.endsWith(".js") ? "application/javascript; charset=utf-8"
                    : path.endsWith(".css") ? "text/css; charset=utf-8" : "application/octet-stream";
            ex.getResponseHeaders().set("Content-Type", type);
            ex.sendResponseHeaders(200, bytes.length);
            try (OutputStream os = ex.getResponseBody()) {
                os.write(bytes);
            }
        }
    }
}
