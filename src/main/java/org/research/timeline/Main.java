package org.research.timeline;

import java.nio.file.Path;
import org.research.timeline.http.HttpServer;
import org.research.timeline.service.ApiService;
import org.research.timeline.store.Store;

/**
 * Entry point.
 * Usage: run --port 5211 [--data ./data]
 * The data directory holds raw/, events/ and derived/ subdirectories.
 */
public final class Main {

    private Main() {
    }

    public static void main(String[] args) throws Exception {
        int port = 5211;
        String dataDir = "./data";
        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--port" -> {
                    i++;
                    if (i >= args.length) {
                        throw new IllegalArgumentException("--port requires a value");
                    }
                    port = Integer.parseInt(args[i]);
                }
                case "--data" -> {
                    i++;
                    if (i >= args.length) {
                        throw new IllegalArgumentException("--data requires a value");
                    }
 dataDir = args[i];
                }
                default -> throw new IllegalArgumentException("Unknown argument: " + args[i]);
            }
        }

        Store store = new Store(Path.of(dataDir));
        ApiService api = new ApiService(store);
        HttpServer httpServer = new HttpServer(api);
        httpServer.start(port);

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            httpServer.stop();
            store.close();
        }));

        System.out.println("Timeline alignment service running at http://127.0.0.1:" + httpServer.port());
        System.out.println("Data directory: " + Path.of(dataDir).toAbsolutePath());
        Thread.currentThread().join();
    }
}
