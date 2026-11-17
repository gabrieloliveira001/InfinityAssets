package net.byebye.infinityAssets.pack;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.logging.Logger;

/** Tiny built-in HTTP server that serves the generated pack (IA "self-host"). */
public class PackServer {
    private final Logger logger;
    private HttpServer server;
    private ExecutorService executor;

    public PackServer(Logger logger) {
        this.logger = logger;
    }

    public synchronized boolean start(int port, File packFile, boolean blockNonGameRequests) {
        stop();
        try {
            server = HttpServer.create(new InetSocketAddress(port), 0);
            server.createContext("/", exchange -> handle(exchange, packFile, blockNonGameRequests));
            executor = Executors.newFixedThreadPool(2, r -> {
                Thread t = new Thread(r, "InfinityAssets-PackServer");
                t.setDaemon(true);
                return t;
            });
            server.setExecutor(executor);
            server.start();
            return true;
        } catch (IOException e) {
            logger.severe("Falha ao iniciar o servidor HTTP do resource pack na porta " + port + ": " + e.getMessage());
            server = null;
            return false;
        }
    }

    private void handle(HttpExchange exchange, File packFile, boolean blockNonGameRequests) throws IOException {
        try (exchange) {
            if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
                exchange.sendResponseHeaders(405, -1);
                return;
            }
            if (blockNonGameRequests) {
                String ua = exchange.getRequestHeaders().getFirst("User-Agent");
                String mcUuid = exchange.getRequestHeaders().getFirst("X-Minecraft-UUID");
                if (mcUuid == null && (ua == null || !ua.startsWith("Minecraft Java"))) {
                    exchange.sendResponseHeaders(403, -1);
                    return;
                }
            }
            if (!packFile.exists()) {
                exchange.sendResponseHeaders(404, -1);
                return;
            }
            exchange.getResponseHeaders().set("Content-Type", "application/zip");
            exchange.sendResponseHeaders(200, packFile.length());
            try (OutputStream os = exchange.getResponseBody()) {
                Files.copy(packFile.toPath(), os);
            }
        }
    }

    public synchronized void stop() {
        if (server != null) {
            server.stop(0);
            server = null;
        }
        if (executor != null) {
            executor.shutdownNow();
            executor = null;
        }
    }

    public boolean isRunning() {
        return server != null;
    }
}
