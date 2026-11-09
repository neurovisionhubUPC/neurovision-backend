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
    private boolean standardScanningControls;
    private boolean standardScanningSummaryControls;
    static final String STANDARD_SCANNING_SUMMARY_TOKEN = "synthetic-doctor-standard-scanning-summary-token";
    private final AtomicReference<String> standardScanningSummaryExpectedToken = new AtomicReference<>(STANDARD_SCANNING_SUMMARY_TOKEN);
    private final AtomicReference<VisualSearchReply> standardScanningSummaryReply =
            new AtomicReference<>(new VisualSearchReply(503, "{}"));
    private final AtomicInteger standardScanningSummaryRequests = new AtomicInteger();
    private final AtomicBoolean standardScanningSummaryExactWire = new AtomicBoolean(true);
    static final String SUSTAINED_FIXATION_SUMMARY_TOKEN = "synthetic-doctor-sustained-fixation-summary-token";
    private final AtomicReference<String> sustainedFixationSummaryExpectedToken =
            new AtomicReference<>(SUSTAINED_FIXATION_SUMMARY_TOKEN);
    private final AtomicReference<VisualSearchReply> sustainedFixationSummaryReply =
            new AtomicReference<>(new VisualSearchReply(503, "{}"));
    private final AtomicInteger sustainedFixationSummaryRequests = new AtomicInteger();
    private final AtomicBoolean sustainedFixationSummaryExactWire = new AtomicBoolean(true);
    private boolean sustainedFixationReceptionControls;
    private boolean sustainedFixationControls;
    static final String SUSTAINED_FIXATION_TOKEN = "synthetic-sustained-fixation-access-token";
    private final AtomicReference<String> sustainedFixationExpectedToken = new AtomicReference<>(SUSTAINED_FIXATION_TOKEN);
    private final AtomicReference<VisualSearchReply> sustainedFixationReply =
            new AtomicReference<>(new VisualSearchReply(503, "{}"));
    private final AtomicInteger sustainedFixationRequests = new AtomicInteger();
    private final AtomicBoolean sustainedFixationExactWire = new AtomicBoolean(true);
    private boolean standardScanningReceptionControls;
    static final String STANDARD_SCANNING_TOKEN = "synthetic-standard-scanning-access-token";
    private final AtomicReference<String> standardScanningExpectedToken = new AtomicReference<>(STANDARD_SCANNING_TOKEN);
    private final AtomicReference<VisualSearchReply> standardScanningReply =
            new AtomicReference<>(new VisualSearchReply(503, "{}"));
    private final AtomicInteger standardScanningRequests = new AtomicInteger();
    private final AtomicBoolean standardScanningExactWire = new AtomicBoolean(true);
    static final String SYSTEMATIC_EXPLORATION_SUMMARY_TOKEN = "synthetic-doctor-systematic-exploration-summary-token";
    private final AtomicReference<String> systematicExplorationSummaryExpectedToken =
            new AtomicReference<>(SYSTEMATIC_EXPLORATION_SUMMARY_TOKEN);
    private final AtomicReference<VisualSearchReply> systematicExplorationSummaryReply =
            new AtomicReference<>(new VisualSearchReply(503, "{}"));
    private final AtomicInteger systematicExplorationSummaryRequests = new AtomicInteger();
    private final AtomicBoolean systematicExplorationSummaryExactWire = new AtomicBoolean(true);
    static final String COMPENSATORY_SACCADE_SUMMARY_TOKEN = "synthetic-doctor-compensatory-saccade-summary-token";
    private final AtomicReference<String> compensatorySaccadeSummaryExpectedToken =
            new AtomicReference<>(COMPENSATORY_SACCADE_SUMMARY_TOKEN);
    private final AtomicReference<VisualSearchReply> compensatorySaccadeSummaryReply =
            new AtomicReference<>(new VisualSearchReply(503, "{}"));
    private final AtomicInteger compensatorySaccadeSummaryRequests = new AtomicInteger();
    private final AtomicBoolean compensatorySaccadeSummaryExactWire = new AtomicBoolean(true);
    static final String COMPENSATORY_SACCADE_TOKEN = "synthetic-compensatory-saccade-access-token";
    private boolean compensatorySaccadeReceptionControls;
    private final AtomicReference<String> compensatorySaccadeExpectedToken =
            new AtomicReference<>(COMPENSATORY_SACCADE_TOKEN);
    private final AtomicReference<VisualSearchReply> compensatorySaccadeReply =
            new AtomicReference<>(new VisualSearchReply(503, "{}"));
    private final AtomicInteger compensatorySaccadeRequests = new AtomicInteger();
    private final AtomicBoolean compensatorySaccadeExactWire = new AtomicBoolean(true);
    private boolean systematicExplorationReceptionControls;
    static final String SYSTEMATIC_EXPLORATION_TOKEN = "synthetic-systematic-exploration-access-token";
    private final AtomicReference<String> systematicExplorationExpectedToken = new AtomicReference<>(SYSTEMATIC_EXPLORATION_TOKEN);
    private final AtomicReference<VisualSearchReply> systematicExplorationReply =
            new AtomicReference<>(new VisualSearchReply(503, "{}"));
    private final AtomicInteger systematicExplorationRequests = new AtomicInteger();
    private final AtomicBoolean systematicExplorationExactWire = new AtomicBoolean(true);
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
        systematicExplorationReceptionControls = "true".equals(args.get("systematic-exploration-reception-controls"));
        compensatorySaccadeReceptionControls = "true".equals(args.get("compensatory-saccade-reception-controls"));
        sustainedFixationReceptionControls = "true".equals(args.get("sustained-fixation-reception-controls"));
        sustainedFixationControls = "true".equals(args.get("sustained-fixation-controls"));
        standardScanningSummaryControls = "true".equals(args.get("standard-scanning-summary-controls"));
        standardScanningControls = "true".equals(args.get("standard-scanning-controls"));
        standardScanningReceptionControls = "true".equals(args.get("standard-scanning-reception-controls"));
        discoveryControls = "true".equals(args.get("stream-discovery-controls"));
        visualSearchControls = "true".equals(args.get("visual-search-controls"));
        visualSearchReceptionControls = "true".equals(args.get("visual-search-reception-controls"));
        visualSearchSummaryControls = "true".equals(args.get("visual-search-summary-controls"));
        if (sustainedFixationControls) {
            VisualSearchAuthorizationHttpProfile.requireOptIn();
            if (!visualSearchControls || !standardScanningControls || !visualSearchSummaryControls
                    || !standardScanningSummaryControls || discoveryControls || visualSearchReceptionControls
                    || standardScanningReceptionControls)
                throw new IllegalArgumentException("Sustained Fixation requires the authorization HTTP profile");
        }
        if (standardScanningControls) {
            VisualSearchAuthorizationHttpProfile.requireOptIn();
            if (discoveryControls || visualSearchReceptionControls || !visualSearchControls)
                throw new IllegalArgumentException("Standard Scanning requires the authorization HTTP profile");
        }
        if (standardScanningSummaryControls) {
            if (standardScanningReceptionControls) {
                VisualSearchTrialReceptionHttpProfile.requireOptIn();
                if (!visualSearchReceptionControls || !visualSearchSummaryControls || discoveryControls
                        || visualSearchControls || standardScanningControls)
                    throw new IllegalArgumentException("Scanning summary requires the isolated reception profile");
            } else {
                VisualSearchAuthorizationHttpProfile.requireOptIn();
                if (!standardScanningControls || !visualSearchControls || !visualSearchSummaryControls
                        || visualSearchReceptionControls || discoveryControls)
                    throw new IllegalArgumentException("Standard Scanning summary requires the authorization HTTP profile");
            }
        }
        if (standardScanningReceptionControls) {
            VisualSearchTrialReceptionHttpProfile.requireOptIn();
            if (!visualSearchReceptionControls || discoveryControls || visualSearchControls || standardScanningControls)
                throw new IllegalArgumentException("Standard Scanning reception requires the isolated reception profile");
            standardScanningControls = true;
        }
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
        if (sustainedFixationReceptionControls) {
            VisualSearchTrialReceptionHttpProfile.requireOptIn();
            if (sustainedFixationControls || discoveryControls || !visualSearchReceptionControls
                    || !standardScanningReceptionControls || !visualSearchSummaryControls || !standardScanningSummaryControls)
                throw new IllegalArgumentException("Sustained Fixation reception requires the isolated reception profile");
            sustainedFixationControls = true;
        }
        if (compensatorySaccadeReceptionControls) {
            VisualSearchTrialReceptionHttpProfile.requireOptIn();
            if (!sustainedFixationReceptionControls || !visualSearchReceptionControls || discoveryControls)
                throw new IllegalArgumentException("Compensatory Saccade reception requires the isolated reception profile");
        }
        if (systematicExplorationReceptionControls) {
            VisualSearchTrialReceptionHttpProfile.requireOptIn();
            if (!compensatorySaccadeReceptionControls || !sustainedFixationReceptionControls
                    || !visualSearchReceptionControls || discoveryControls)
                throw new IllegalArgumentException("Systematic Exploration reception requires the isolated reception profile");
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
                if (supportsSystematicExplorationSummary() && exchange.getRequestURI().getRawPath()
                        .endsWith("/systematic-exploration-summary-authorization")) {
                    respondSystematicExplorationSummary(exchange); return;
                }
                if (supportsCompensatorySaccadeSummary() && exchange.getRequestURI().getRawPath()
                        .endsWith("/compensatory-saccade-summary-authorization")) {
                    respondCompensatorySaccadeSummary(exchange); return;
                }
                if (supportsSystematicExplorationAuthorization() && exchange.getRequestURI().getRawPath()
                        .endsWith("/systematic-exploration-result-authorization")) {
                    respondSystematicExploration(exchange); return;
                }
                if (supportsCompensatorySaccadeAuthorization() && exchange.getRequestURI().getRawPath()
                        .endsWith("/compensatory-saccade-result-authorization")) {
                    respondCompensatorySaccade(exchange); return;
                }
                if (supportsSustainedFixationSummary() && exchange.getRequestURI().getRawPath()
                        .endsWith("/sustained-fixation-summary-authorization")) {
                    respondSustainedFixationSummary(exchange); return;
                }
                if (sustainedFixationControls && exchange.getRequestURI().getRawPath()
                        .endsWith("/sustained-fixation-result-authorization")) {
                    respondSustainedFixation(exchange); return;
                }
                if (standardScanningSummaryControls && exchange.getRequestURI().getRawPath()
                        .endsWith("/standard-scanning-summary-authorization")) {
                    respondStandardScanningSummary(exchange); return;
                }
                if (standardScanningControls && exchange.getRequestURI().getRawPath()
                        .endsWith("/standard-scanning-result-authorization")) {
                    respondStandardScanning(exchange); return;
                }
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

    /** Only the reception profile can supply a locally signed synthetic credential; never expose it. */
    void standardScanningExpectedToken(String token) {
        if (!standardScanningReceptionControls || !visualSearchReceptionControls || token == null || token.isBlank()
                || token.length() > 8192 || token.indexOf('\r') >= 0 || token.indexOf('\n') >= 0)
            throw new IllegalArgumentException("Invalid synthetic Standard Scanning credential");
        standardScanningExpectedToken.set(token);
    }
    void standardScanningReply(int status, String body) {
        if (!standardScanningControls || !Set.of(200, 401, 403, 503).contains(status) || body == null
                || body.getBytes(StandardCharsets.UTF_8).length > 2048)
            throw new IllegalArgumentException("Invalid synthetic Standard Scanning reply");
        standardScanningReply.set(new VisualSearchReply(status, body));
    }
    void resetStandardScanningObservation() {
        if (!standardScanningControls) throw new IllegalStateException("Standard Scanning mode required");
        standardScanningRequests.set(0); standardScanningExactWire.set(true);
    }
    VisualSearchObservation standardScanningObservation() {
        if (!standardScanningControls) throw new IllegalStateException("Standard Scanning mode required");
        return new VisualSearchObservation(standardScanningRequests.get(), standardScanningExactWire.get());
    }
    private void respondStandardScanning(com.sun.net.httpserver.HttpExchange exchange) throws java.io.IOException {
        try (exchange) {
            standardScanningRequests.incrementAndGet();
            boolean exact = "GET".equals(exchange.getRequestMethod()) && exchange.getRequestURI().getRawQuery() == null
                    && ("/api/internal/v1/therapy-sessions/" + ALLOWED_SESSION + "/standard-scanning-result-authorization")
                            .equals(exchange.getRequestURI().getRawPath())
                    && java.util.List.of("Bearer " + standardScanningExpectedToken.get())
                            .equals(exchange.getRequestHeaders().get("Authorization"))
                    && java.util.List.of("application/json").equals(exchange.getRequestHeaders().get("Accept"))
                    && exchange.getRequestBody().readNBytes(1).length == 0;
            if (!exact) { standardScanningExactWire.set(false); respond(exchange, 403, "{}"); return; }
            var reply = standardScanningReply.get();
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            respond(exchange, reply.status(), reply.body());
        }
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
    /** Only the isolated reception profile supplies a locally signed synthetic DOCTOR token. */
    void standardScanningSummaryExpectedToken(String token) {
        if (!standardScanningSummaryControls || !standardScanningReceptionControls || !visualSearchReceptionControls
                || token == null || token.isBlank() || token.length() > 8192
                || token.indexOf('\r') >= 0 || token.indexOf('\n') >= 0)
            throw new IllegalArgumentException("Invalid synthetic scanning summary credential");
        standardScanningSummaryExpectedToken.set(token);
    }
    void standardScanningSummaryReply(int status, String body) {
        if (!standardScanningSummaryControls || !Set.of(200, 401, 403, 503).contains(status) || body == null
                || body.getBytes(StandardCharsets.UTF_8).length > 2048)
            throw new IllegalArgumentException("Invalid synthetic summary reply");
        standardScanningSummaryReply.set(new VisualSearchReply(status, body));
    }
    void resetStandardScanningSummaryObservation() {
        if (!standardScanningSummaryControls) throw new IllegalStateException("Summary mode required");
        standardScanningSummaryRequests.set(0); standardScanningSummaryExactWire.set(true);
    }
    VisualSearchObservation standardScanningSummaryObservation() {
        if (!standardScanningSummaryControls) throw new IllegalStateException("Summary mode required");
        return new VisualSearchObservation(standardScanningSummaryRequests.get(), standardScanningSummaryExactWire.get());
    }
    private void respondStandardScanningSummary(com.sun.net.httpserver.HttpExchange exchange) throws java.io.IOException {
        try (exchange) {
            standardScanningSummaryRequests.incrementAndGet();
            boolean exact = "GET".equals(exchange.getRequestMethod()) && exchange.getRequestURI().getRawQuery() == null
                    && ("/api/internal/v1/therapy-sessions/" + ALLOWED_SESSION + "/standard-scanning-summary-authorization")
                            .equals(exchange.getRequestURI().getRawPath())
                    && java.util.List.of("Bearer " + standardScanningSummaryExpectedToken.get())
                            .equals(exchange.getRequestHeaders().get("Authorization"))
                    && java.util.List.of("application/json").equals(exchange.getRequestHeaders().get("Accept"))
                    && exchange.getRequestBody().readNBytes(1).length == 0;
            if (!exact) { standardScanningSummaryExactWire.set(false); respond(exchange, 403, "{}"); return; }
            var reply = standardScanningSummaryReply.get();
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            respond(exchange, reply.status(), reply.body());
        }
    }
    private boolean supportsSustainedFixationSummary() {
        // Both modes were already attested by their respective profile guards in init.
        return sustainedFixationControls;
    }

    void sustainedFixationSummaryExpectedToken(String token) {
        if (!supportsSustainedFixationSummary() || !sustainedFixationReceptionControls || !visualSearchReceptionControls
                || token == null || token.isBlank() || token.length() > 8192
                || token.indexOf('\r') >= 0 || token.indexOf('\n') >= 0)
            throw new IllegalArgumentException("Invalid synthetic fixation summary credential");
        sustainedFixationSummaryExpectedToken.set(token);
    }
    void sustainedFixationSummaryReply(int status, String body) {
        if (!supportsSustainedFixationSummary() || !Set.of(200, 401, 403, 503).contains(status) || body == null
                || body.getBytes(StandardCharsets.UTF_8).length > 2048)
            throw new IllegalArgumentException("Invalid synthetic summary reply");
        sustainedFixationSummaryReply.set(new VisualSearchReply(status, body));
    }
    void resetSustainedFixationSummaryObservation() {
        if (!supportsSustainedFixationSummary()) throw new IllegalStateException("Summary mode required");
        sustainedFixationSummaryRequests.set(0); sustainedFixationSummaryExactWire.set(true);
    }
    VisualSearchObservation sustainedFixationSummaryObservation() {
        if (!supportsSustainedFixationSummary()) throw new IllegalStateException("Summary mode required");
        return new VisualSearchObservation(sustainedFixationSummaryRequests.get(), sustainedFixationSummaryExactWire.get());
    }
    private void respondSustainedFixationSummary(com.sun.net.httpserver.HttpExchange exchange) throws java.io.IOException {
        try (exchange) {
            sustainedFixationSummaryRequests.incrementAndGet();
            boolean exact = "GET".equals(exchange.getRequestMethod()) && exchange.getRequestURI().getRawQuery() == null
                    && ("/api/internal/v1/therapy-sessions/" + ALLOWED_SESSION + "/sustained-fixation-summary-authorization")
                            .equals(exchange.getRequestURI().getRawPath())
                    && java.util.List.of("Bearer " + sustainedFixationSummaryExpectedToken.get())
                            .equals(exchange.getRequestHeaders().get("Authorization"))
                    && java.util.List.of("application/json").equals(exchange.getRequestHeaders().get("Accept"))
                    && exchange.getRequestBody().readNBytes(1).length == 0;
            if (!exact) { sustainedFixationSummaryExactWire.set(false); respond(exchange, 403, "{}"); return; }
            var reply = sustainedFixationSummaryReply.get();
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            respond(exchange, reply.status(), reply.body());
        }
    }

    void sustainedFixationExpectedToken(String token) {
        if (!sustainedFixationControls || !sustainedFixationReceptionControls || token == null || token.isBlank()
                || token.length() > 8192 || token.indexOf('\r') >= 0 || token.indexOf('\n') >= 0)
            throw new IllegalArgumentException("Invalid synthetic Sustained Fixation credential");
        sustainedFixationExpectedToken.set(token);
    }

    void sustainedFixationReply(int status, String body) {
        if (!sustainedFixationControls || !Set.of(200, 401, 403, 503).contains(status) || body == null
                || body.getBytes(StandardCharsets.UTF_8).length > 2048)
            throw new IllegalArgumentException("Invalid synthetic Sustained Fixation reply");
        sustainedFixationReply.set(new VisualSearchReply(status, body));
    }

    void resetSustainedFixationObservation() {
        if (!sustainedFixationControls) throw new IllegalStateException("Sustained Fixation mode required");
        sustainedFixationRequests.set(0);
        sustainedFixationExactWire.set(true);
    }

    VisualSearchObservation sustainedFixationObservation() {
        if (!sustainedFixationControls) throw new IllegalStateException("Sustained Fixation mode required");
        return new VisualSearchObservation(sustainedFixationRequests.get(), sustainedFixationExactWire.get());
    }

    private void respondSustainedFixation(com.sun.net.httpserver.HttpExchange exchange) throws java.io.IOException {
        try (exchange) {
            sustainedFixationRequests.incrementAndGet();
            boolean exact = "GET".equals(exchange.getRequestMethod()) && exchange.getRequestURI().getRawQuery() == null
                    && ("/api/internal/v1/therapy-sessions/" + ALLOWED_SESSION + "/sustained-fixation-result-authorization")
                            .equals(exchange.getRequestURI().getRawPath())
                    && java.util.List.of("Bearer " + sustainedFixationExpectedToken.get())
                            .equals(exchange.getRequestHeaders().get("Authorization"))
                    && java.util.List.of("application/json").equals(exchange.getRequestHeaders().get("Accept"))
                    && exchange.getRequestBody().readNBytes(1).length == 0;
            if (!exact) {
                sustainedFixationExactWire.set(false);
                respond(exchange, 403, "{}");
                return;
            }
            var reply = sustainedFixationReply.get();
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            respond(exchange, reply.status(), reply.body());
        }
    }

    private boolean supportsCompensatorySaccadeSummary() {
        // Both modes are attested by the existing opt-in guards in init.
        return compensatorySaccadeReceptionControls || (sustainedFixationControls && !sustainedFixationReceptionControls);
    }

    void compensatorySaccadeSummaryExpectedToken(String token) {
        if (!compensatorySaccadeReceptionControls || token == null || token.isBlank()
                || token.length() > 8192 || token.indexOf('\r') >= 0 || token.indexOf('\n') >= 0)
            throw new IllegalArgumentException("Invalid synthetic Compensatory Saccade summary credential");
        compensatorySaccadeSummaryExpectedToken.set(token);
    }

    void compensatorySaccadeSummaryReply(int status, String body) {
        if (!supportsCompensatorySaccadeSummary() || !Set.of(200, 401, 403, 503).contains(status) || body == null
                || body.getBytes(StandardCharsets.UTF_8).length > 2048)
            throw new IllegalArgumentException("Invalid synthetic summary reply");
        compensatorySaccadeSummaryReply.set(new VisualSearchReply(status, body));
    }
    void resetCompensatorySaccadeSummaryObservation() {
        if (!supportsCompensatorySaccadeSummary()) throw new IllegalStateException("Summary mode required");
        compensatorySaccadeSummaryRequests.set(0); compensatorySaccadeSummaryExactWire.set(true);
    }
    VisualSearchObservation compensatorySaccadeSummaryObservation() {
        if (!supportsCompensatorySaccadeSummary()) throw new IllegalStateException("Summary mode required");
        return new VisualSearchObservation(compensatorySaccadeSummaryRequests.get(), compensatorySaccadeSummaryExactWire.get());
    }
    private void respondCompensatorySaccadeSummary(com.sun.net.httpserver.HttpExchange exchange) throws java.io.IOException {
        try (exchange) {
            compensatorySaccadeSummaryRequests.incrementAndGet();
            boolean exact = "GET".equals(exchange.getRequestMethod()) && exchange.getRequestURI().getRawQuery() == null
                    && ("/api/internal/v1/therapy-sessions/" + ALLOWED_SESSION + "/compensatory-saccade-summary-authorization")
                            .equals(exchange.getRequestURI().getRawPath())
                    && java.util.List.of("Bearer " + compensatorySaccadeSummaryExpectedToken.get())
                            .equals(exchange.getRequestHeaders().get("Authorization"))
                    && java.util.List.of("application/json").equals(exchange.getRequestHeaders().get("Accept"))
                    && exchange.getRequestBody().readNBytes(1).length == 0;
            if (!exact) { compensatorySaccadeSummaryExactWire.set(false); respond(exchange, 403, "{}"); return; }
            var reply = compensatorySaccadeSummaryReply.get();
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            respond(exchange, reply.status(), reply.body());
        }
    }

    private boolean supportsSystematicExplorationSummary() {
        // Both modes are attested by the existing opt-in guards in init.
        return (sustainedFixationControls && !sustainedFixationReceptionControls
                && !compensatorySaccadeReceptionControls)
                || (systematicExplorationReceptionControls && visualSearchReceptionControls);
    }

    void systematicExplorationSummaryExpectedToken(String token) {
        if (!systematicExplorationReceptionControls || token == null || token.isBlank()
                || token.length() > 8192 || token.indexOf('\r') >= 0 || token.indexOf('\n') >= 0)
            throw new IllegalArgumentException("Invalid synthetic Systematic Exploration summary credential");
        systematicExplorationSummaryExpectedToken.set(token);
    }

    void systematicExplorationSummaryReply(int status, String body) {
        if (!supportsSystematicExplorationSummary() || !Set.of(200, 401, 403, 503).contains(status) || body == null
                || body.getBytes(StandardCharsets.UTF_8).length > 2048)
            throw new IllegalArgumentException("Invalid synthetic summary reply");
        systematicExplorationSummaryReply.set(new VisualSearchReply(status, body));
    }
    void resetSystematicExplorationSummaryObservation() {
        if (!supportsSystematicExplorationSummary()) throw new IllegalStateException("Summary mode required");
        systematicExplorationSummaryRequests.set(0); systematicExplorationSummaryExactWire.set(true);
    }
    VisualSearchObservation systematicExplorationSummaryObservation() {
        if (!supportsSystematicExplorationSummary()) throw new IllegalStateException("Summary mode required");
        return new VisualSearchObservation(systematicExplorationSummaryRequests.get(), systematicExplorationSummaryExactWire.get());
    }
    private void respondSystematicExplorationSummary(com.sun.net.httpserver.HttpExchange exchange) throws java.io.IOException {
        try (exchange) {
            systematicExplorationSummaryRequests.incrementAndGet();
            boolean exact = "GET".equals(exchange.getRequestMethod()) && exchange.getRequestURI().getRawQuery() == null
                    && ("/api/internal/v1/therapy-sessions/" + ALLOWED_SESSION + "/systematic-exploration-summary-authorization")
                            .equals(exchange.getRequestURI().getRawPath())
                    && java.util.List.of("Bearer " + systematicExplorationSummaryExpectedToken.get())
                            .equals(exchange.getRequestHeaders().get("Authorization"))
                    && java.util.List.of("application/json").equals(exchange.getRequestHeaders().get("Accept"))
                    && exchange.getRequestBody().readNBytes(1).length == 0;
            if (!exact) { systematicExplorationSummaryExactWire.set(false); respond(exchange, 403, "{}"); return; }
            var reply = systematicExplorationSummaryReply.get();
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            respond(exchange, reply.status(), reply.body());
        }
    }

    private boolean supportsCompensatorySaccadeAuthorization() {
        return compensatorySaccadeReceptionControls || (sustainedFixationControls && !sustainedFixationReceptionControls);
    }

    void compensatorySaccadeExpectedToken(String token) {
        if (!compensatorySaccadeReceptionControls || token == null || token.isBlank()
                || token.length() > 16384)
            throw new IllegalArgumentException("Invalid synthetic Compensatory Saccade credential");
        compensatorySaccadeExpectedToken.set(token);
    }

    void compensatorySaccadeReply(int status, String body) {
        if (!supportsCompensatorySaccadeAuthorization() || !Set.of(200, 401, 403, 503).contains(status) || body == null
                || body.getBytes(StandardCharsets.UTF_8).length > 2048)
            throw new IllegalArgumentException("Invalid synthetic Compensatory Saccade reply");
        compensatorySaccadeReply.set(new VisualSearchReply(status, body));
    }

    void resetCompensatorySaccadeObservation() {
        if (!supportsCompensatorySaccadeAuthorization()) throw new IllegalStateException("Compensatory Saccade mode required");
        compensatorySaccadeRequests.set(0);
        compensatorySaccadeExactWire.set(true);
    }

    VisualSearchObservation compensatorySaccadeObservation() {
        if (!supportsCompensatorySaccadeAuthorization()) throw new IllegalStateException("Compensatory Saccade mode required");
        return new VisualSearchObservation(compensatorySaccadeRequests.get(), compensatorySaccadeExactWire.get());
    }

    private void respondCompensatorySaccade(com.sun.net.httpserver.HttpExchange exchange) throws java.io.IOException {
        try (exchange) {
            compensatorySaccadeRequests.incrementAndGet();
            boolean exact = "GET".equals(exchange.getRequestMethod()) && exchange.getRequestURI().getRawQuery() == null
                    && ("/api/internal/v1/therapy-sessions/" + ALLOWED_SESSION + "/compensatory-saccade-result-authorization")
                            .equals(exchange.getRequestURI().getRawPath())
                    && java.util.List.of("Bearer " + compensatorySaccadeExpectedToken.get())
                            .equals(exchange.getRequestHeaders().get("Authorization"))
                    && java.util.List.of("application/json").equals(exchange.getRequestHeaders().get("Accept"))
                    && exchange.getRequestBody().readNBytes(1).length == 0;
            if (!exact) {
                compensatorySaccadeExactWire.set(false);
                respond(exchange, 403, "{}");
                return;
            }
            var reply = compensatorySaccadeReply.get();
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            respond(exchange, reply.status(), reply.body());
        }
    }

    private boolean supportsSystematicExplorationAuthorization() {
        return (sustainedFixationControls && !sustainedFixationReceptionControls && !compensatorySaccadeReceptionControls)
                || (systematicExplorationReceptionControls && visualSearchReceptionControls);
    }

    void systematicExplorationExpectedToken(String token) {
        if (!systematicExplorationReceptionControls || token == null || token.isBlank() || token.length() > 16384)
            throw new IllegalArgumentException("Invalid synthetic Systematic Exploration credential");
        systematicExplorationExpectedToken.set(token);
    }

    void systematicExplorationReply(int status, String body) {
        if (!supportsSystematicExplorationAuthorization() || !Set.of(200, 401, 403, 503).contains(status) || body == null
                || body.getBytes(StandardCharsets.UTF_8).length > 2048)
            throw new IllegalArgumentException("Invalid synthetic Systematic Exploration reply");
        systematicExplorationReply.set(new VisualSearchReply(status, body));
    }

    void resetSystematicExplorationObservation() {
        if (!supportsSystematicExplorationAuthorization()) throw new IllegalStateException("Systematic Exploration mode required");
        systematicExplorationRequests.set(0);
        systematicExplorationExactWire.set(true);
    }

    VisualSearchObservation systematicExplorationObservation() {
        if (!supportsSystematicExplorationAuthorization()) throw new IllegalStateException("Systematic Exploration mode required");
        return new VisualSearchObservation(systematicExplorationRequests.get(), systematicExplorationExactWire.get());
    }

    private void respondSystematicExploration(com.sun.net.httpserver.HttpExchange exchange) throws java.io.IOException {
        try (exchange) {
            systematicExplorationRequests.incrementAndGet();
            boolean exact = "GET".equals(exchange.getRequestMethod()) && exchange.getRequestURI().getRawQuery() == null
                    && ("/api/internal/v1/therapy-sessions/" + ALLOWED_SESSION + "/systematic-exploration-result-authorization")
                            .equals(exchange.getRequestURI().getRawPath())
                    && java.util.List.of("Bearer " + systematicExplorationExpectedToken.get())
                            .equals(exchange.getRequestHeaders().get("Authorization"))
                    && java.util.List.of("application/json").equals(exchange.getRequestHeaders().get("Accept"))
                    && exchange.getRequestBody().readNBytes(1).length == 0;
            if (!exact) {
                systematicExplorationExactWire.set(false);
                respond(exchange, 403, "{}");
                return;
            }
            var reply = systematicExplorationReply.get();
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
        if (supportsSystematicExplorationSummary()) {
            systematicExplorationSummaryExpectedToken.set(SYSTEMATIC_EXPLORATION_SUMMARY_TOKEN);
            systematicExplorationSummaryReply.set(new VisualSearchReply(503, "{}"));
            resetSystematicExplorationSummaryObservation();
        }
        if (supportsSystematicExplorationAuthorization()) {
            systematicExplorationExpectedToken.set(SYSTEMATIC_EXPLORATION_TOKEN);
            systematicExplorationReply.set(new VisualSearchReply(503, "{}"));
            resetSystematicExplorationObservation();
        }
        compensatorySaccadeSummaryExpectedToken.set(COMPENSATORY_SACCADE_SUMMARY_TOKEN);
        if (supportsCompensatorySaccadeSummary()) {
            compensatorySaccadeSummaryReply.set(new VisualSearchReply(503, "{}"));
            resetCompensatorySaccadeSummaryObservation();
        }
        if (supportsCompensatorySaccadeAuthorization()) {
            compensatorySaccadeExpectedToken.set(COMPENSATORY_SACCADE_TOKEN);
            compensatorySaccadeReply.set(new VisualSearchReply(503, "{}"));
            resetCompensatorySaccadeObservation();
        }
        if (supportsSustainedFixationSummary()) {
            sustainedFixationSummaryExpectedToken.set(SUSTAINED_FIXATION_SUMMARY_TOKEN);
            sustainedFixationSummaryReply.set(new VisualSearchReply(503, "{}"));
            resetSustainedFixationSummaryObservation();
        }
        if (sustainedFixationControls) {
            sustainedFixationExpectedToken.set(SUSTAINED_FIXATION_TOKEN);
            sustainedFixationReply.set(new VisualSearchReply(503, "{}"));
            resetSustainedFixationObservation();
        }
        if (standardScanningSummaryControls) {
            standardScanningSummaryExpectedToken.set(STANDARD_SCANNING_SUMMARY_TOKEN);
            standardScanningSummaryReply.set(new VisualSearchReply(503, "{}"));
            resetStandardScanningSummaryObservation();
        }
        if (standardScanningControls) {
            standardScanningExpectedToken.set(STANDARD_SCANNING_TOKEN);
            standardScanningReply.set(new VisualSearchReply(503, "{}"));
            resetStandardScanningObservation();
        }
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
