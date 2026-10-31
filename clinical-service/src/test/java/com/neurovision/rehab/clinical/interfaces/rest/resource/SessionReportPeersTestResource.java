package com.neurovision.rehab.clinical.interfaces.rest.resource;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.quarkus.test.common.QuarkusTestResourceLifecycleManager;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

/** Only the three peer routes used by this suite; no clinical payload or bearer request journal. */
public class SessionReportPeersTestResource implements QuarkusTestResourceLifecycleManager {
    private HttpServer server;
    private volatile String expectedAuthorization;
    private volatile UUID patient;
    private volatile UUID session;
    private volatile boolean profileUnavailable;
    private volatile boolean analyticsUnavailable;
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
    }

    void scenario(UUID patientId, UUID sessionId, String token, boolean profileFailure, boolean analyticsFailure) {
        patient = Objects.requireNonNull(patientId);
        session = Objects.requireNonNull(sessionId);
        expectedAuthorization = "Bearer " + Objects.requireNonNull(token);
        profileUnavailable = profileFailure;
        analyticsUnavailable = analyticsFailure;
        names.set(0); doctor.set(0); analytics.set(0); unexpected.set(0); wrongBearer.set(0);
    }

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
            } else {
                unexpected.incrementAndGet();
                send(exchange, 404, "{}");
            }
        }
    }

    private static void send(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.getResponseHeaders().set("Cache-Control", "no-store");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
    }
}
