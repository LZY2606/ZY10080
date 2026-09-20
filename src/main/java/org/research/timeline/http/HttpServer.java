package org.research.timeline.http;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import org.research.timeline.service.ApiError;
import org.research.timeline.service.ApiService;
import org.research.timeline.util.Json;

/** Thin HTTP transport: routing, JSON encoding and stable error envelopes. */
public final class HttpServer {

    private final ApiService api;
    private com.sun.net.httpserver.HttpServer server;

    public HttpServer(ApiService api) {
        this.api = api;
    }

    public void start(int port) throws IOException {
        server = com.sun.net.httpserver.HttpServer.create(new InetSocketAddress("127.0.0.1", port), 0);
        server.createContext("/", new RootHandler());
        server.start();
    }

    public int port() {
        return server == null ? -1 : server.getAddress().getPort();
    }

    public void stop() {
        if (server != null) {
            server.stop(0);
        }
    }

    private final class RootHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            try {
                route(exchange);
            } catch (ApiError error) {
                writeError(exchange, error);
            } catch (Json.JsonException error) {
                writeError(exchange, new ApiError(ApiError.Category.INPUT_FORMAT,
                        "Malformed JSON: " + error.getMessage()));
            } catch (Exception error) {
                writeError(exchange, new ApiError(ApiError.Category.INTERNAL,
                        "Internal failure: " + error));
            } finally {
                exchange.close();
            }
        }

        private void route(HttpExchange exchange) throws IOException {
            String method = exchange.getRequestMethod();
            String path = exchange.getRequestURI().getPath();

            if ("GET".equals(method) && ("/".equals(path) || "/index.html".equals(path))) {
                writeResource(exchange, "/web/index.html", "text/html; charset=utf-8");
                return;
            }
            if ("GET".equals(method) && "/app.js".equals(path)) {
                writeResource(exchange, "/web/app.js", "application/javascript; charset=utf-8");
                return;
            }
            if ("GET".equals(method) && "/styles.css".equals(path)) {
                writeResource(exchange, "/web/styles.css", "text/css; charset=utf-8");
                return;
            }
            if ("GET".equals(method) && "/health".equals(path)) {
                writeJson(exchange, 200, Map.of("status", "ok"));
                return;
            }

            String body = readBody(exchange);
            Map<String, Object> payload = body.isBlank() ? Map.of() : Json.parseObject(body);
            Object result = switch (method + " " + path) {
                case "POST /api/import/frame-table" -> api.importFrameTable(payload, body);
                case "POST /api/import/segments" -> api.importSegments(payload, body);
                case "POST /api/import/pose" -> api.importPose(payload, body);
                case "POST /api/alignment/generate" -> api.generateAlignment(payload);
                case "GET /api/state" -> api.state();
                case "GET /api/sources" -> api.sources();
                case "GET /api/consensus" -> api.consensus();
                case "POST /api/consensus/decide" -> api.decide(payload, body);
                case "GET /api/export" -> api.exportBundle();
                case "POST /api/reimport" -> api.reimport(payload);
                default -> throw new ApiError(ApiError.Category.NOT_FOUND,
                        "No route for " + method + " " + path);
            };
            writeJson(exchange, 200, result);
        }
    }

    private static String readBody(HttpExchange exchange) throws IOException {
        try (InputStream in = exchange.getRequestBody()) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private void writeResource(HttpExchange exchange, String resource, String contentType) throws IOException {
        try (InputStream in = HttpServer.class.getResourceAsStream(resource)) {
            if (in == null) {
                writeError(exchange, new ApiError(ApiError.Category.INTERNAL, "Missing resource " + resource));
                return;
            }
            byte[] bytes = in.readAllBytes();
            exchange.getResponseHeaders().set("Content-Type", contentType);
            exchange.sendResponseHeaders(200, bytes.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(bytes);
            }
        }
    }

    static void writeJson(HttpExchange exchange, int status, Object value) throws IOException {
        byte[] bytes = Json.write(value).getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }

    static void writeError(HttpExchange exchange, ApiError error) throws IOException {
        Map<String, Object> envelope = new LinkedHashMap<>();
        envelope.put("error", error.category().code);
        envelope.put("message", error.getMessage());
        if (error.details() != null) {
            envelope.put("details", error.details());
        }
        byte[] bytes = Json.write(envelope).getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        exchange.sendResponseHeaders(error.category().httpStatus, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }
}
