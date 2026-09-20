package langalign;

import java.nio.file.Path;

public final class Main {
    public static void main(String[] args) throws Exception {
        int port = 5211;
        Path data = Path.of("data");
        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--port" -> port = Integer.parseInt(args[++i]);
                case "--data" -> data = Path.of(args[++i]);
                default -> { }
            }
        }
        ApiServer server = new ApiServer(port, data);
        server.start();
        System.out.println("数据目录: " + data.toAbsolutePath());
        System.out.println("页面: http://127.0.0.1:" + server.port());
        Thread.currentThread().join();
    }
}
