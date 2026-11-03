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
    static final String VISUAL_SEARCH_TOKEN = "synthetic-visual-search-access-token";
    private boolean visualSearchControls;
    private boolean visualSearchReceptionControls;
    private boolean visualSearchSummaryControls;
    static final String VISUAL_SEARCH_SUMMARY_TOKEN = "synthetic-doctor-summary-access-token";
    private final AtomicReference<String> visualSearchSummaryExpectedToken = new AtomicReference<>(VISUAL_SEARCH_SUMMARY_TOKEN);
    private final AtomicReference<VisualSearchReply> visualSearchSummaryReply =
            new AtomicReference<>(new VisualSearchReply(503, "{}"));
    private final AtomicInteger visualSearchSummaryRequests = new AtomicInteger();
    private final AtomicBoolean visualSearchSummaryExactWire = new AtomicBoolean(true);
    private final AtomicReference<String> visualSearchExpectedToken = new AtomicReference<>(VISUAL_SEARCH_TOKEN);
    private record VisualSearchReply(int status, String body) {}
    record VisualSearchObservation(int requests, boolean exactWire) {}
    private final AtomicReference<VisualSearchReply> visualSearchReply = new AtomicReference<>(new VisualSearchReply(503, "{}"));
    private final AtomicInteger visualSearchRequests = new AtomicInteger();
    private final AtomicBoolean visualSearchExactWire = new AtomicBoolean(true);

    @Override
    public void init(Map<String, String> args) {
        discoveryControls = "true".equals(args.get("stream-discovery-controls"));
        visualSearchControls = "true".equals(args.get("visual-search-controls"));
        visualSearchReceptionControls = "true".equals(args.get("visual-search-reception-controls"));
        visualSearchSummaryControls = "true".equals(args.get("visual-search-summary-controls"));
        if (visualSearchSummaryControls) {
            if (visualSearchReceptionControls) VisualSearchTrialReceptionHttpProfile.requireOptIn();
            else VisualSearchAuthorizationHttpProfile.requireOptIn();
            if (discoveryControls || (!visualSearchControls && !visualSearchReceptionControls))
                throw new IllegalArgumentException("Summary controls require a Visual Search test profile");
        }
        if (visualSearchReceptionControls) {
            VisualSearchTrialReceptionHttpProfile.requireOptIn();
            if (discoveryControls || visualSearchControls) throw new IllegalArgumentException("Independent stub modes required");
            visualSearchControls = true;
        }
        if (visualSearchControls) {
            requireVisualSearchOptIn();
            if (discoveryControls) throw new IllegalArgumentException("Independent stub modes required");
        }
    }

    @Override
    public Map<String, String> start() {
        if (visualSearchControls) requireVisualSearchOptIn();
        try {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            if (visualSearchControls && (!"127.0.0.1".equals(server.getAddress().getAddress().getHostAddress())
                    || server.getAddress().getPort() < 1)) throw new IllegalStateException("Loopback peer binding required");
            server.createContext("/api/internal/v1/therapy-sessions/", exchange -> {
                if (visualSearchSummaryControls && exchange.getRequestURI().getRawPath()
                        .endsWith("/visual-search-summary-authorization")) {
                    respondVisualSearchSummary(exchange); return;
                }
                if (visualSearchControls) { respondVisualSearch(exchange); return; }
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
            } else if (!visualSearchControls) {
                server.createContext("/test-observed-credential", exchange ->
                        respond(exchange, 200, lastCredential.get()));
            }
            server.start();
            return Map.of("analytics.clinical.base-url",
                    "http://127.0.0.1:" + server.getAddress().getPort());
        } catch (Exception failure) {
            if (visualSearchControls) {
                stop();
                throw new IllegalStateException("Local Visual Search peer startup unavailable");
            }
            throw new IllegalStateException("Cannot start Clinical authorization stub", failure);
        }
    }

    @Override public void inject(TestInjector injector) {
        if (visualSearchControls)
            injector.injectIntoFields(this, field -> field.getType().equals(ClinicalHeatmapAuthorizationStubResource.class));
    }

    void visualSearchReply(int status, String body) {
        if (!visualSearchControls || !Set.of(200, 401, 403, 503).contains(status) || body == null
                || body.getBytes(StandardCharsets.UTF_8).length > 2048)
            throw new IllegalArgumentException("Invalid synthetic Visual Search reply");
        visualSearchReply.set(new VisualSearchReply(status, body));
    }
    private void requireVisualSearchOptIn() {
        if (visualSearchReceptionControls) VisualSearchTrialReceptionHttpProfile.requireOptIn();
        else VisualSearchAuthorizationHttpProfile.requireOptIn();
    }
    /** Expected synthetic credential only; observations never return or log it. */
    void visualSearchExpectedToken(String token) {
        if (!visualSearchReceptionControls || token == null || token.isBlank() || token.length() > 8192
                || token.indexOf('\r') >= 0 || token.indexOf('\n') >= 0)
            throw new IllegalArgumentException("Invalid synthetic Visual Search credential");
        visualSearchExpectedToken.set(token);
    }
    void resetVisualSearchObservation() {
        if (!visualSearchControls) throw new IllegalStateException("Visual Search mode required");
        visualSearchRequests.set(0); visualSearchExactWire.set(true);
    }
    VisualSearchObservation visualSearchObservation() {
        if (!visualSearchControls) throw new IllegalStateException("Visual Search mode required");
        return new VisualSearchObservation(visualSearchRequests.get(), visualSearchExactWire.get());
    }
    void visualSearchSummaryReply(int status, String body) {
        if (!visualSearchSummaryControls || !Set.of(200, 401, 403, 503).contains(status) || body == null
                || body.getBytes(StandardCharsets.UTF_8).length > 2048)
            throw new IllegalArgumentException("Invalid synthetic summary reply");
        visualSearchSummaryReply.set(new VisualSearchReply(status, body));
    }
    /** Only the explicitly selected inbound HTTP profile may set a locally signed synthetic token. */
    void visualSearchSummaryExpectedToken(String token) {
        if (!visualSearchSummaryControls || !visualSearchReceptionControls || token == null || token.isBlank()
                || token.length() > 8192 || token.indexOf('\r') >= 0 || token.indexOf('\n') >= 0)
            throw new IllegalArgumentException("Invalid synthetic summary credential");
        visualSearchSummaryExpectedToken.set(token);
    }
    void resetVisualSearchSummaryObservation() {
        if (!visualSearchSummaryControls) throw new IllegalStateException("Summary mode required");
        visualSearchSummaryRequests.set(0); visualSearchSummaryExactWire.set(true);
    }
    VisualSearchObservation visualSearchSummaryObservation() {
        if (!visualSearchSummaryControls) throw new IllegalStateException("Summary mode required");
        return new VisualSearchObservation(visualSearchSummaryRequests.get(), visualSearchSummaryExactWire.get());
    }
    private void respondVisualSearchSummary(com.sun.net.httpserver.HttpExchange exchange) throws java.io.IOException {
        try (exchange) {
            visualSearchSummaryRequests.incrementAndGet();
            boolean exact = "GET".equals(exchange.getRequestMethod()) && exchange.getRequestURI().getRawQuery() == null
                    && ("/api/internal/v1/therapy-sessions/" + ALLOWED_SESSION + "/visual-search-summary-authorization")
                            .equals(exchange.getRequestURI().getRawPath())
                    && java.util.List.of("Bearer " + visualSearchSummaryExpectedToken.get())
                            .equals(exchange.getRequestHeaders().get("Authorization"))
                    && java.util.List.of("application/json").equals(exchange.getRequestHeaders().get("Accept"))
                    && exchange.getRequestBody().readNBytes(1).length == 0;
            if (!exact) { visualSearchSummaryExactWire.set(false); respond(exchange, 403, "{}"); return; }
            var reply = visualSearchSummaryReply.get();
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            respond(exchange, reply.status(), reply.body());
        }
    }
    private void respondVisualSearch(com.sun.net.httpserver.HttpExchange exchange) throws java.io.IOException {
        try (exchange) {
            visualSearchRequests.incrementAndGet();
            boolean exact = "GET".equals(exchange.getRequestMethod()) && exchange.getRequestURI().getRawQuery() == null
                    && ("/api/internal/v1/therapy-sessions/" + ALLOWED_SESSION + "/visual-search-result-authorization")
                            .equals(exchange.getRequestURI().getRawPath())
                    && java.util.List.of("Bearer " + visualSearchExpectedToken.get()).equals(exchange.getRequestHeaders().get("Authorization"))
                    && java.util.List.of("application/json").equals(exchange.getRequestHeaders().get("Accept"))
                    && exchange.getRequestBody().readNBytes(1).length == 0;
            if (!exact) { visualSearchExactWire.set(false); respond(exchange, 403, "{}"); return; }
            var reply = visualSearchReply.get();
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            respond(exchange, reply.status(), reply.body());
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
        if (visualSearchControls) {
            server = null;
            visualSearchReply.set(new VisualSearchReply(503, "{}"));
            visualSearchExpectedToken.set(VISUAL_SEARCH_TOKEN);
            resetVisualSearchObservation();
            if (visualSearchSummaryControls) {
                visualSearchSummaryReply.set(new VisualSearchReply(503, "{}"));
                visualSearchSummaryExpectedToken.set(VISUAL_SEARCH_SUMMARY_TOKEN);
                resetVisualSearchSummaryObservation();
            }
        }
    }
}
