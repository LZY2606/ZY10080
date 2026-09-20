package org.research.timeline;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.research.timeline.http.HttpServer;
import org.research.timeline.service.ApiService;
import org.research.timeline.store.Store;
import org.research.timeline.util.Json;

class HttpServerTest {

    @Test
    void errorEnvelopesDistinguishCategoriesAndUiIsServed() throws Exception {
        Path dir = Path.of("build", "test-data", "http-" + java.util.UUID.randomUUID());
        Store store = new Store(dir);
        ApiService api = new ApiService(store);
        HttpServer server = new HttpServer(api);
        server.start(0);
        int port = server.port();
        HttpClient client = HttpClient.newHttpClient();
        try {
            // Segments before frame table: 409 STATE_CONFLICT.
            HttpResponse<String> conflict = post(client, port, "/api/import/segments",
                    Map.of("requestId", "r", "sourceId", "s", "researcher", "x",
                            "segments", java.util.List.of()));
            assertEquals(409, conflict.statusCode());
            assertEquals("STATE_CONFLICT", Json.parseObject(conflict.body()).get("error"));

            // Missing field: 400 INPUT_FORMAT.
            HttpResponse<String> bad = post(client, port, "/api/import/frame-table", Map.of("requestId", "r"));
            assertEquals(400, bad.statusCode());
            assertEquals("INPUT_FORMAT", Json.parseObject(bad.body()).get("error"));

            // Malformed JSON: 400 INPUT_FORMAT.
            HttpRequest malformed = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/import/frame-table"))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString("{oops"))
                    .build();
            HttpResponse<String> malformedResponse = client.send(malformed, HttpResponse.BodyHandlers.ofString());
            assertEquals(400, malformedResponse.statusCode());

            // Unknown route: 404.
            HttpRequest missing = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/nope"))
                    .GET().build();
            assertEquals(404, client.send(missing, HttpResponse.BodyHandlers.ofString()).statusCode());

            // UI and assets are served.
            HttpResponse<String> index = client.send(HttpRequest.newBuilder(
                    URI.create("http://127.0.0.1:" + port + "/")).GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            assertEquals(200, index.statusCode());
            assertTrue(index.body().contains("多源动作片段对齐工作台"));
        } finally {
            server.stop();
            store.close();
        }
    }

    private HttpResponse<String> post(HttpClient client, int port, String path, Object body)
            throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(Json.write(body)))
                .build();
        return client.send(request, HttpResponse.BodyHandlers.ofString());
    }
}
