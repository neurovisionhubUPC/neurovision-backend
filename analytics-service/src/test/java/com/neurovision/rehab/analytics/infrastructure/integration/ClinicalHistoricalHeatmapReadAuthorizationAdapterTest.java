package com.neurovision.rehab.analytics.infrastructure.integration;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.neurovision.rehab.analytics.application.ports.out.authorization.HistoricalHeatmapReadAuthorizationPort.Decision;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ClinicalHistoricalHeatmapReadAuthorizationAdapterTest {
    private HttpServer server;
    private final UUID session = UUID.randomUUID();
    private final AtomicReference<String> path = new AtomicReference<>();
    private final AtomicReference<String> credential = new AtomicReference<>();
    private volatile int status = 200;
    private volatile String body;

    @BeforeEach
    void start() throws Exception {
        body = permit(session);
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            path.set(exchange.getRequestURI().getPath());
            credential.set(exchange.getRequestHeaders().getFirst("Authorization"));
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status, bytes.length);
            try (var output = exchange.getResponseBody()) { output.write(bytes); }
        });
        server.start();
    }

    @AfterEach
    void stop() {
        if (server != null) server.stop(0);
    }

    @Test
    void reusesHistoricalReadContractAndForwardsOriginalBearerToken() {
        assertEquals(Decision.PERMITTED, adapter().authorize(session, "original-jwt"));
        assertEquals("Bearer original-jwt", credential.get());
        assertEquals("/api/internal/v1/therapy-sessions/" + session
                + "/telemetry-read-authorization", path.get());
    }

    @Test
    void failsClosedOnDenialInvalidPermitMalformedResponseAndUnavailableClinical() {
        var adapter = adapter();
        status = 403;
        assertEquals(Decision.DENIED, adapter.authorize(session, "jwt"));
        status = 401;
        assertEquals(Decision.INVALID_TOKEN, adapter.authorize(session, "jwt"));
        status = 200;
        body = permit(UUID.randomUUID());
        assertEquals(Decision.UNAVAILABLE, adapter.authorize(session, "jwt"));
        body = permit(session).replace("true", "false");
        assertEquals(Decision.UNAVAILABLE, adapter.authorize(session, "jwt"));
        body = "{\"contractVersion\":1,\"contractVersion\":1,\"permitted\":true,"
                + "\"therapySessionId\":\"" + session + "\"}";
        assertEquals(Decision.UNAVAILABLE, adapter.authorize(session, "jwt"));
        status = 503;
        assertEquals(Decision.UNAVAILABLE, adapter.authorize(session, "jwt"));
        server.stop(0);
        server = null;
        assertEquals(Decision.UNAVAILABLE, adapter.authorize(session, "jwt"));
    }

    private ClinicalHistoricalHeatmapReadAuthorizationAdapter adapter() {
        return new ClinicalHistoricalHeatmapReadAuthorizationAdapter(
                URI.create("http://127.0.0.1:" + server.getAddress().getPort()),
                1000, new ObjectMapper());
    }

    private static String permit(UUID id) {
        return "{\"contractVersion\":1,\"permitted\":true,\"therapySessionId\":\"" + id + "\"}";
    }
}
