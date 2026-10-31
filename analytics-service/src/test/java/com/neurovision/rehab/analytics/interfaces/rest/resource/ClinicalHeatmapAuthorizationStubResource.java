package com.neurovision.rehab.analytics.interfaces.rest.resource;

import com.sun.net.httpserver.HttpServer;
import io.quarkus.test.common.QuarkusTestResourceLifecycleManager;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

public class ClinicalHeatmapAuthorizationStubResource implements QuarkusTestResourceLifecycleManager {
    static final String ALLOWED_SESSION = "3f1a860f-cc55-40d9-b86b-377780949b82";
    private HttpServer server;
    private final AtomicReference<String> lastCredential = new AtomicReference<>("");
    private boolean discoveryControls;
    private final Set<String> permittedSessions = ConcurrentHashMap.newKeySet();
    private final AtomicReference<String> expectedCredential = new AtomicReference<>("");
    private final AtomicInteger authorizationRequests = new AtomicInteger();
    private final AtomicBoolean originalBearerMatches = new AtomicBoolean(true);

    @Override
    public void init(Map<String, String> args) {
        discoveryControls = "true".equals(args.get("stream-discovery-controls"));
    }

    @Override
    public Map<String, String> start() {
        try {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/api/internal/v1/therapy-sessions/", exchange -> {
                var credential = exchange.getRequestHeaders().getFirst("Authorization");
                if (discoveryControls) {
                    authorizationRequests.incrementAndGet();
                    if (!expectedCredential.get().equals(credential)) originalBearerMatches.set(false);
                    var path = exchange.getRequestURI().getPath();
                    var permitted = permittedSessions.stream().filter(session -> path.equals(
                            "/api/internal/v1/therapy-sessions/" + session + "/telemetry-read-authorization"))
                            .findFirst();
                    boolean allowed = permitted.isPresent() && credential != null;
                    respond(exchange, allowed ? 200 : 403, allowed
                            ? "{\"contractVersion\":1,\"permitted\":true,\"therapySessionId\":\""
                                    + permitted.orElseThrow() + "\"}"
                            : "{\"code\":\"TELEMETRY_READ_FORBIDDEN\"}");
                    return;
                }
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
            if (discoveryControls) {
                server.createContext("/test-stream-discovery/reset", exchange -> {
                    permittedSessions.clear();
                    expectedCredential.set(exchange.getRequestHeaders().getFirst("Authorization"));
                    authorizationRequests.set(0);
                    originalBearerMatches.set(true);
                    respond(exchange, 200, "{}");
                });
                server.createContext("/test-stream-discovery/permit", exchange -> {
                    permittedSessions.add(exchange.getRequestHeaders().getFirst("X-Test-Session"));
                    respond(exchange, 200, "{}");
                });
                server.createContext("/test-stream-discovery/deny", exchange -> {
                    permittedSessions.remove(exchange.getRequestHeaders().getFirst("X-Test-Session"));
                    respond(exchange, 200, "{}");
                });
                server.createContext("/test-stream-discovery/observed", exchange -> respond(exchange, 200,
                        "{\"requests\":" + authorizationRequests.get()
                                + ",\"originalBearerMatches\":" + originalBearerMatches.get() + "}"));
            } else {
                server.createContext("/test-observed-credential", exchange ->
                        respond(exchange, 200, lastCredential.get()));
            }
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
