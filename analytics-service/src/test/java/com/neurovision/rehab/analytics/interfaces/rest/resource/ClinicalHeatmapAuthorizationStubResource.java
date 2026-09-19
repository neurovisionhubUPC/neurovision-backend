package com.neurovision.rehab.analytics.interfaces.rest.resource;

import com.sun.net.httpserver.HttpServer;
import io.quarkus.test.common.QuarkusTestResourceLifecycleManager;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

public class ClinicalHeatmapAuthorizationStubResource implements QuarkusTestResourceLifecycleManager {
    static final String ALLOWED_SESSION = "3f1a860f-cc55-40d9-b86b-377780949b82";
    private HttpServer server;
    private final AtomicReference<String> lastCredential = new AtomicReference<>("");

    @Override
    public Map<String, String> start() {
        try {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/api/internal/v1/therapy-sessions/", exchange -> {
                var credential = exchange.getRequestHeaders().getFirst("Authorization");
                lastCredential.set(credential == null ? "" : credential);
                var expected = "/api/internal/v1/therapy-sessions/" + ALLOWED_SESSION
                        + "/telemetry-read-authorization";
                int status = exchange.getRequestURI().getPath().equals(expected)
                        && credential != null ? 200 : 403;
                String body = status == 200
                        ? "{\"contractVersion\":1,\"permitted\":true,\"therapySessionId\":\""
                                + ALLOWED_SESSION + "\"}"
                        : "{\"code\":\"TELEMETRY_READ_FORBIDDEN\"}";
                respond(exchange, status, body);
            });
            server.createContext("/test-observed-credential", exchange ->
                    respond(exchange, 200, lastCredential.get()));
            server.start();
            return Map.of("analytics.clinical.base-url",
                    "http://127.0.0.1:" + server.getAddress().getPort());
        } catch (Exception failure) {
            throw new IllegalStateException("Cannot start Clinical authorization stub", failure);
        }
    }

    private static void respond(com.sun.net.httpserver.HttpExchange exchange, int status, String body)
            throws java.io.IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(status, bytes.length);
        try (var output = exchange.getResponseBody()) { output.write(bytes); }
    }

    @Override
    public void stop() {
        if (server != null) server.stop(0);
    }
}
