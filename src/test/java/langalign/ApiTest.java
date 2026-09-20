package langalign;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class ApiTest {
    @TempDir Path dir;
    ApiServer server;
    HttpClient client;
    String base;

    @BeforeEach
    void start() throws Exception {
        server = new ApiServer(0, dir);
        server.start();
        base = "http://127.0.0.1:" + server.port();
        client = HttpClient.newHttpClient();
    }

    @AfterEach
    void stop() { server.stop(); }

    HttpResponse<String> post(String path, String json) throws Exception {
        return client.send(HttpRequest.newBuilder(URI.create(base + path))
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(json)).build(),
                HttpResponse.BodyHandlers.ofString());
    }

    HttpResponse<String> get(String path) throws Exception {
        return client.send(HttpRequest.newBuilder(URI.create(base + path)).GET().build(),
                HttpResponse.BodyHandlers.ofString());
    }

    @Test
    void errorTaxonomy() throws Exception {
        // 输入格式：非法 JSON
        HttpResponse<String> r1 = post("/api/videos", "{not json");
        assertEquals(400, r1.statusCode());
        assertEquals("INPUT_FORMAT", Json.PRETTY.readTree(r1.body()).at("/error/type").asText());
        // 输入格式：缺少 request_id
        HttpResponse<String> r2 = post("/api/videos", "{\"frames\":[{\"frameId\":0,\"ptsSeconds\":0}]}");
        assertEquals(400, r2.statusCode());
        assertEquals("INPUT_FORMAT", Json.PRETTY.readTree(r2.body()).at("/error/type").asText());
        // 未找到
        HttpResponse<String> r3 = get("/api/videos/nope");
        assertEquals(404, r3.statusCode());
        assertEquals("NOT_FOUND", Json.PRETTY.readTree(r3.body()).at("/error/type").asText());
        // 状态冲突：过期版本
        post("/api/demo/seed", "{\"request_id\":\"seed-1\"}");
        JsonNode state = Json.PRETTY.readTree(get("/api/videos/demo-video").body());
        String candidateId = null;
        for (JsonNode c : state.at("/alignments/0/candidates")) {
            if (!c.get("gap").asBoolean()) { candidateId = c.get("id").asText(); break; }
        }
        JsonNode accepted = Json.PRETTY.readTree(post("/api/consensus/accept",
                "{\"request_id\":\"acc-1\",\"alignmentId\":\"al-demo\",\"candidateId\":\"" + candidateId + "\"}").body());
        String segId = accepted.get("id").asText();
        HttpResponse<String> r4 = post("/api/consensus/split",
                "{\"request_id\":\"sp-1\",\"segmentId\":\"" + segId + "\",\"atFrame\":50,\"baseVersion\":99}");
        assertEquals(409, r4.statusCode());
        assertEquals("STATE_CONFLICT", Json.PRETTY.readTree(r4.body()).at("/error/type").asText());
    }

    @Test
    void sameRequestIdReplayDoesNotDuplicate() throws Exception {
        post("/api/demo/seed", "{\"request_id\":\"seed-1\"}");
        JsonNode state = Json.PRETTY.readTree(get("/api/videos/demo-video").body());
        String candidateId = null;
        for (JsonNode c : state.at("/alignments/0/candidates")) {
            if (!c.get("gap").asBoolean()) { candidateId = c.get("id").asText(); break; }
        }
        String body = "{\"request_id\":\"acc-same\",\"alignmentId\":\"al-demo\",\"candidateId\":\"" + candidateId + "\"}";
        HttpResponse<String> first = post("/api/consensus/accept", body);
        HttpResponse<String> replay = post("/api/consensus/accept", body);
        assertEquals(200, first.statusCode());
        assertEquals(200, replay.statusCode());
        assertEquals("true", replay.headers().firstValue("X-Replayed").orElse(""));
        String id1 = Json.PRETTY.readTree(first.body()).get("id").asText();
        String id2 = Json.PRETTY.readTree(replay.body()).get("id").asText();
        assertEquals(id1, id2); // 重放返回首次结果
        JsonNode consensus = Json.PRETTY.readTree(get("/api/videos/demo-video/consensus").body());
        assertEquals(1, consensus.get("segments").size()); // 没有第二份业务结果
    }

    @Test
    void happyPathAndStaticPage() throws Exception {
        HttpResponse<String> page = get("/");
        assertEquals(200, page.statusCode());
        assertTrue(page.body().contains("动作分段对齐工作台"));

        post("/api/demo/seed", "{\"request_id\":\"seed-1\"}");
        JsonNode state = Json.PRETTY.readTree(get("/api/videos/demo-video").body());
        assertEquals(2, state.get("layers").size());
        assertTrue(state.get("alignments").get(0).get("candidates").size() >= 3);
        // 演示数据里“整理衣物”应成为缺口
        boolean hasGap = false;
        for (JsonNode c : state.at("/alignments/0/candidates")) hasGap |= c.get("gap").asBoolean();
        assertTrue(hasGap);
        // 导出包含精度与证据
        JsonNode doc = Json.PRETTY.readTree(get("/api/videos/demo-video/export").body());
        assertEquals("pairwise-gsb-export/1", doc.get("format").asText());
        assertTrue(doc.get("gaps").size() >= 1);
    }
}
