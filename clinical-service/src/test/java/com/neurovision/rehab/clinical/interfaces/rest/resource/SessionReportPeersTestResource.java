package com.neurovision.rehab.clinical.interfaces.rest.resource;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.quarkus.test.common.QuarkusTestResourceLifecycleManager;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

/** Only the fixed peer routes used by this suite; no clinical payload or bearer request journal. */
public class SessionReportPeersTestResource implements QuarkusTestResourceLifecycleManager {
    private HttpServer server;
    private volatile String expectedAuthorization;
    private volatile UUID patient;
    private volatile UUID session;
    private volatile boolean profileUnavailable;
    private volatile boolean analyticsUnavailable;
    enum VisualSearchScenario { OBSERVED, CHANGED, ZERO, UNAVAILABLE, WRONG_SESSION, WRONG_CONFIGURATION, WRONG_VERSION, WRONG_ORIGIN }
    private volatile UUID visualSearchConfiguration;
    private volatile String visualSearchOrigin;
    private volatile VisualSearchScenario visualSearchScenario;
    private final AtomicInteger visualSearch = new AtomicInteger();
    enum StandardScanningScenario { OBSERVED, CHANGED, ZERO, UNAVAILABLE, WRONG_SESSION, WRONG_CONFIGURATION, WRONG_VERSION, WRONG_ORIGIN, MALFORMED }
    private volatile UUID standardScanningConfiguration;
    private volatile String standardScanningOrigin;
    private volatile StandardScanningScenario standardScanningScenario;
    private final AtomicInteger standardScanning = new AtomicInteger();
    private record SustainedFixationScenario(UUID configurationId, String origin, boolean unavailable) {}
    private volatile SustainedFixationScenario sustainedFixationScenario;
    private final AtomicInteger sustainedFixation = new AtomicInteger();
    private record CompensatorySaccadeScenario(UUID configurationId, String origin, boolean unavailable) {}
    private volatile CompensatorySaccadeScenario compensatorySaccadeScenario;
    private final AtomicInteger compensatorySaccade = new AtomicInteger();
    enum SystematicExplorationScenario { OBSERVED, ZERO, UNAVAILABLE, WRONG_SESSION, WRONG_CONFIGURATION, WRONG_VERSION, WRONG_ORIGIN, MALFORMED }
    private record ExplorationScenario(UUID configurationId, String origin, SystematicExplorationScenario result) {}
    private volatile ExplorationScenario systematicExplorationScenario;
    private final AtomicInteger systematicExploration = new AtomicInteger();
    private final AtomicInteger names = new AtomicInteger();
    private final AtomicInteger doctor = new AtomicInteger();
    private final AtomicInteger analytics = new AtomicInteger();
    private final AtomicInteger unexpected = new AtomicInteger();
    private final AtomicInteger wrongBearer = new AtomicInteger();

    @Override public Map<String, String> start() {
        ClinicalSessionReportHttpProfile.requireOptIn();
        try {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            String origin = "http://127.0.0.1:" + server.getAddress().getPort();
            ClinicalSessionReportHttpProfile.requireLoopbackOrigin(origin);
            server.createContext("/", this::respond);
            server.start();
            return Map.of("clinical.report-http-test.peer-origin", origin);
        } catch (IOException | RuntimeException | Error failure) {
            stop();
            throw new IllegalStateException("Local report peer startup failed");
        }
    }

    @Override public void inject(TestInjector injector) {
        injector.injectIntoFields(this, field -> field.getType().equals(SessionReportPeersTestResource.class));
    }

    @Override public void stop() {
        if (server != null) { server.stop(0); server = null; }
        expectedAuthorization = null;
        patient = null;
        session = null;
        visualSearchConfiguration = null;
        visualSearchOrigin = null;
        visualSearchScenario = null;
        standardScanningConfiguration = null;
        standardScanningOrigin = null;
        standardScanningScenario = null;
        standardScanning.set(0);
        sustainedFixationScenario = null;
        sustainedFixation.set(0);
        compensatorySaccadeScenario = null;
        compensatorySaccade.set(0);
        systematicExplorationScenario = null;
        systematicExploration.set(0);

    }

    void scenario(UUID patientId, UUID sessionId, String token, boolean profileFailure, boolean analyticsFailure) {
        patient = Objects.requireNonNull(patientId);
        session = Objects.requireNonNull(sessionId);
        expectedAuthorization = "Bearer " + Objects.requireNonNull(token);
        profileUnavailable = profileFailure;
        analyticsUnavailable = analyticsFailure;
        names.set(0); doctor.set(0); analytics.set(0); unexpected.set(0); wrongBearer.set(0);
        visualSearch.set(0);
        visualSearchConfiguration = null;
        visualSearchOrigin = null;
        visualSearchScenario = null;
        standardScanningConfiguration = null;
        standardScanningOrigin = null;
        standardScanningScenario = null;
        standardScanning.set(0);
        sustainedFixationScenario = null;
        sustainedFixation.set(0);
        compensatorySaccadeScenario = null;
        compensatorySaccade.set(0);
        systematicExplorationScenario = null;
        systematicExploration.set(0);

    }

    void visualSearchScenario(UUID configurationId, String origin, VisualSearchScenario selected) {
        if (!Set.of("SYNTHETIC", "PRODUCER_REPORTED").contains(origin)) throw new IllegalArgumentException("Invalid synthetic origin");
        visualSearchConfiguration = Objects.requireNonNull(configurationId);
        visualSearchOrigin = origin;
        visualSearchScenario = Objects.requireNonNull(selected);
    }

    int visualSearchCount() { return visualSearch.get(); }

    void standardScanningScenario(UUID configurationId, String origin, StandardScanningScenario selected) {
        if (!Set.of("SYNTHETIC", "PRODUCER_REPORTED").contains(origin)) throw new IllegalArgumentException("Invalid synthetic origin");
        standardScanningConfiguration = Objects.requireNonNull(configurationId);
        standardScanningOrigin = origin;
        standardScanningScenario = Objects.requireNonNull(selected);
    }

    int standardScanningCount() { return standardScanning.get(); }

    void sustainedFixationScenario(UUID configurationId, String origin, boolean unavailable) {
        if (!Set.of("SYNTHETIC", "PRODUCER_REPORTED").contains(origin)) throw new IllegalArgumentException("Invalid synthetic origin");
        sustainedFixationScenario = new SustainedFixationScenario(Objects.requireNonNull(configurationId), origin, unavailable);
    }

    int sustainedFixationCount() { return sustainedFixation.get(); }

    void compensatorySaccadeScenario(UUID configurationId, String origin, boolean unavailable) {
        if (!Set.of("SYNTHETIC", "PRODUCER_REPORTED").contains(origin)) throw new IllegalArgumentException("Invalid synthetic origin");
        compensatorySaccadeScenario = new CompensatorySaccadeScenario(Objects.requireNonNull(configurationId), origin, unavailable);
    }

    int compensatorySaccadeCount() { return compensatorySaccade.get(); }

    void systematicExplorationScenario(UUID configurationId, String origin, SystematicExplorationScenario scenario) {
        if (!Set.of("SYNTHETIC", "PRODUCER_REPORTED").contains(origin)) throw new IllegalArgumentException("Invalid synthetic origin");
        systematicExplorationScenario = new ExplorationScenario(Objects.requireNonNull(configurationId), origin, Objects.requireNonNull(scenario));
    }

    int systematicExplorationCount() { return systematicExploration.get(); }

    record Counts(int names, int doctor, int analytics, int unexpected, int wrongBearer) {}
    Counts counts() { return new Counts(names.get(), doctor.get(), analytics.get(), unexpected.get(), wrongBearer.get()); }

    private void respond(HttpExchange exchange) throws IOException {
        try (exchange) {
            if (!Objects.equals(expectedAuthorization, exchange.getRequestHeaders().getFirst("Authorization"))) {
                wrongBearer.incrementAndGet();
                send(exchange, 403, "{}");
                return;
            }
            String path = exchange.getRequestURI().getRawPath();
            String method = exchange.getRequestMethod();
            String query = exchange.getRequestURI().getRawQuery();
            var fixation = sustainedFixationScenario;
            var saccade = compensatorySaccadeScenario;
            var exploration = systematicExplorationScenario;
            if ("POST".equals(method) && query == null
                    && "/api/internal/v1/profiles/assigned-patient-names".equals(path)) {
                names.incrementAndGet();
                // Bound and discard the synthetic request body, never retain it or log it.
                if (exchange.getRequestBody().readNBytes(4097).length > 4096) {
                    unexpected.incrementAndGet(); send(exchange, 400, "{}"); return;
                }
                send(exchange, 200, "{\"contractVersion\":1,\"patients\":[{\"clinicalPatientId\":\"" + patient
                        + "\",\"nameStatus\":\"AVAILABLE\",\"firstName\":\"Synthetic\",\"lastName\":\"Patient\"}]}");
            } else if ("GET".equals(method) && query == null && "/api/v1/profile".equals(path)) {
                doctor.incrementAndGet();
                send(exchange, profileUnavailable ? 503 : 200, profileUnavailable ? "{}"
                        : "{\"profileType\":\"DOCTOR\",\"status\":\"ACTIVE\",\"firstName\":\"Synthetic\","
                        + "\"lastName\":\"Doctor\",\"specialty\":\"Synthetic specialty\",\"medicalLicense\":\"TEST-ONLY\"}");
            } else if ("GET".equals(method) && ("/api/v1/analytics/sessions/" + session + "/streams").equals(path)
                    && "page=0&size=20".equals(query)) {
                analytics.incrementAndGet();
                send(exchange, analyticsUnavailable ? 503 : 200, analyticsUnavailable ? "{}"
                        : "{\"therapySessionId\":\"" + session + "\",\"page\":0,\"size\":20,\"hasNext\":false,\"items\":[]}");
            } else if ("GET".equals(method) && visualSearchScenario != null
                    && ("/api/v1/analytics/sessions/" + session + "/visual-search/summary").equals(path)
                    && ("dataOrigin=" + visualSearchOrigin).equals(query)) {
                visualSearch.incrementAndGet();
                send(exchange, visualSearchScenario == VisualSearchScenario.UNAVAILABLE ? 503 : 200,
                        visualSearchScenario == VisualSearchScenario.UNAVAILABLE ? "{}" : visualSearchSummary());
            } else if ("GET".equals(method) && standardScanningScenario != null
                    && ("/api/v1/analytics/sessions/" + session + "/standard-scanning/summary").equals(path)
                    && ("dataOrigin=" + standardScanningOrigin).equals(query)) {
                standardScanning.incrementAndGet();
                send(exchange, standardScanningScenario == StandardScanningScenario.UNAVAILABLE ? 503 : 200,
                        standardScanningScenario == StandardScanningScenario.UNAVAILABLE ? "{}" : standardScanningSummary());
            } else if ("GET".equals(method) && fixation != null
                    && ("/api/v1/analytics/sessions/" + session + "/sustained-fixation/summary").equals(path)
                    && ("dataOrigin=" + fixation.origin()).equals(query)) {
                sustainedFixation.incrementAndGet();
                send(exchange, fixation.unavailable() ? 503 : 200, fixation.unavailable() ? "{}"
                        : "{\"calculationVersion\":\"sustained-fixation-trial-v1\",\"therapySessionId\":\"" + session
                        + "\",\"configurationId\":\"" + fixation.configurationId() + "\",\"configurationVersion\":1"
                        + ",\"dataOrigin\":\"" + fixation.origin() + "\",\"reportedTrialCount\":3"
                        + ",\"totalInsideTargetMs\":4200,\"completedTargetCount\":2}");
            } else if ("GET".equals(method) && saccade != null
                    && ("/api/v1/analytics/sessions/" + session + "/compensatory-saccade/summary").equals(path)
                    && ("dataOrigin=" + saccade.origin()).equals(query)) {
                compensatorySaccade.incrementAndGet();
                send(exchange, saccade.unavailable() ? 503 : 200, saccade.unavailable() ? "{}"
                        : "{\"calculationVersion\":\"compensatory-saccade-trial-v1\",\"therapySessionId\":\"" + session
                        + "\",\"configurationId\":\"" + saccade.configurationId() + "\",\"configurationVersion\":1"
                        + ",\"dataOrigin\":\"" + saccade.origin() + "\",\"reportedTrialCount\":3"
                        + ",\"reachedTargetCount\":2,\"medianTimeToTargetMs\":1250.5}");
            } else if ("GET".equals(method) && exploration != null
                    && ("/api/v1/analytics/sessions/" + session + "/systematic-exploration/summary").equals(path)
                    && ("dataOrigin=" + exploration.origin()).equals(query)) {
                systematicExploration.incrementAndGet();
                send(exchange, exploration.result() == SystematicExplorationScenario.UNAVAILABLE ? 503 : 200,
                        exploration.result() == SystematicExplorationScenario.UNAVAILABLE ? "{}" : systematicExplorationSummary(exploration));
            } else {
                unexpected.incrementAndGet();
                send(exchange, 404, "{}");
            }
        }
    }

    private String visualSearchSummary() {
        var selected = visualSearchScenario;
        UUID summarySession = selected == VisualSearchScenario.WRONG_SESSION ? new UUID(22, 99) : session;
        UUID configuration = selected == VisualSearchScenario.WRONG_CONFIGURATION ? new UUID(25, 99) : visualSearchConfiguration;
        String origin = selected == VisualSearchScenario.WRONG_ORIGIN
                ? ("SYNTHETIC".equals(visualSearchOrigin) ? "PRODUCER_REPORTED" : "SYNTHETIC") : visualSearchOrigin;
        int version = selected == VisualSearchScenario.WRONG_VERSION ? 2 : 1;
        String metrics = switch (selected) {
            case ZERO -> "\"reportedTrialCount\":0,\"hitCount\":0,\"omissionCount\":0,\"interruptedCount\":0,\"eligibleSearchCount\":0,\"medianSearchDurationMs\":null";
            case CHANGED -> "\"reportedTrialCount\":4,\"hitCount\":2,\"omissionCount\":1,\"interruptedCount\":1,\"eligibleSearchCount\":2,\"medianSearchDurationMs\":2500.5";
            default -> "\"reportedTrialCount\":6,\"hitCount\":3,\"omissionCount\":2,\"interruptedCount\":1,\"eligibleSearchCount\":3,\"medianSearchDurationMs\":1250.5";
        };
        return "{\"calculationVersion\":\"visual-search-trial-v1\",\"therapySessionId\":\"" + summarySession
                + "\",\"configurationId\":\"" + configuration + "\",\"configurationVersion\":" + version
                + ",\"dataOrigin\":\"" + origin + "\"," + metrics + "}";
    }

    private String standardScanningSummary() {
        var selected = standardScanningScenario;
        if (selected == StandardScanningScenario.MALFORMED) return "{\"calculationVersion\":null}";
        UUID summarySession = selected == StandardScanningScenario.WRONG_SESSION ? new UUID(22, 99) : session;
        UUID configuration = selected == StandardScanningScenario.WRONG_CONFIGURATION ? new UUID(25, 99) : standardScanningConfiguration;
        String origin = selected == StandardScanningScenario.WRONG_ORIGIN
                ? ("SYNTHETIC".equals(standardScanningOrigin) ? "PRODUCER_REPORTED" : "SYNTHETIC") : standardScanningOrigin;
        int version = selected == StandardScanningScenario.WRONG_VERSION ? 2 : 1;
        String metrics = switch (selected) {
            case ZERO -> "\"reportedTrialCount\":0,\"detectedTargetCount\":0,\"omissionCount\":0,\"interruptedCount\":0,\"eligibleLocalizationCount\":0,\"medianLocalizationDurationMs\":null";
            case CHANGED -> "\"reportedTrialCount\":4,\"detectedTargetCount\":2,\"omissionCount\":1,\"interruptedCount\":1,\"eligibleLocalizationCount\":2,\"medianLocalizationDurationMs\":2500.5";
            default -> "\"reportedTrialCount\":6,\"detectedTargetCount\":3,\"omissionCount\":2,\"interruptedCount\":1,\"eligibleLocalizationCount\":3,\"medianLocalizationDurationMs\":1250.5";
        };
        return "{\"calculationVersion\":\"standard-scanning-trial-v1\",\"therapySessionId\":\"" + summarySession
                + "\",\"configurationId\":\"" + configuration + "\",\"configurationVersion\":" + version
                + ",\"dataOrigin\":\"" + origin + "\"," + metrics + "}";
    }

    private String systematicExplorationSummary(ExplorationScenario scenario) {
        var result = scenario.result();
        if (result == SystematicExplorationScenario.MALFORMED) return "{\"calculationVersion\":null}";
        UUID summarySession = result == SystematicExplorationScenario.WRONG_SESSION ? new UUID(22, 99) : session;
        UUID configuration = result == SystematicExplorationScenario.WRONG_CONFIGURATION ? new UUID(25, 99) : scenario.configurationId();
        String origin = result == SystematicExplorationScenario.WRONG_ORIGIN
                ? ("SYNTHETIC".equals(scenario.origin()) ? "PRODUCER_REPORTED" : "SYNTHETIC") : scenario.origin();
        int version = result == SystematicExplorationScenario.WRONG_VERSION ? 2 : 1;
        String rows = result == SystematicExplorationScenario.ZERO ? "[]" : "["
                + "{\"trialId\":\"trial-001\",\"outcome\":\"COMPLETED\",\"visitedRegionCount\":4,\"coveragePercent\":100,\"elapsedMs\":4000},"
                + "{\"trialId\":\"trial-002\",\"outcome\":\"TIMED_OUT\",\"visitedRegionCount\":2,\"coveragePercent\":50,\"elapsedMs\":5000},"
                + "{\"trialId\":\"trial-003\",\"outcome\":\"INTERRUPTED\",\"visitedRegionCount\":1,\"coveragePercent\":25,\"elapsedMs\":1500}]";
        return "{\"calculationVersion\":\"systematic-exploration-trial-v1\",\"therapySessionId\":\"" + summarySession
                + "\",\"configurationId\":\"" + configuration + "\",\"configurationVersion\":" + version
                + ",\"dataOrigin\":\"" + origin + "\",\"reportedTrialCount\":"
                + (result == SystematicExplorationScenario.ZERO ? 0 : 3) + ",\"traversals\":" + rows + "}";
    }

    private static void send(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.getResponseHeaders().set("Cache-Control", "no-store");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
    }
}
