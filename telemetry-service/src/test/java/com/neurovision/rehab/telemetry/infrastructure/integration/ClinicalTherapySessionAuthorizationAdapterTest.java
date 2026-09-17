package com.neurovision.rehab.telemetry.infrastructure.integration;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.neurovision.rehab.telemetry.application.ports.out.authorization.TherapySessionAuthorizationPort.Decision;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ClinicalTherapySessionAuthorizationAdapterTest {
    private HttpServer server;
    private final UUID sessionId = UUID.randomUUID();
    private final AtomicReference<String> receivedToken = new AtomicReference<>();
    private final AtomicReference<String> receivedPath = new AtomicReference<>();
    private volatile int status;
    private volatile String body;

    @BeforeEach
    void start() throws Exception {
        status = 200;
        body = permit(sessionId);
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            receivedToken.set(exchange.getRequestHeaders().getFirst("Authorization"));
            receivedPath.set(exchange.getRequestURI().getPath());
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status, bytes.length);
            try (var output = exchange.getResponseBody()) { output.write(bytes); }
        });
        server.start();
    }

    @AfterEach
    void stop() { server.stop(0); }

    @Test
    void forwardsOriginalCredentialAndAcceptsOnlyExactMinimalPermit() {
        var adapter = adapter();
        assertEquals(Decision.PERMITTED, adapter.authorize(sessionId, "original-jwt"));
        assertEquals("Bearer original-jwt", receivedToken.get());
        assertEquals("/api/internal/v1/therapy-sessions/" + sessionId + "/telemetry-authorization",
                receivedPath.get());

        body = permit(UUID.randomUUID());
        assertEquals(Decision.UNAVAILABLE, adapter.authorize(sessionId, "original-jwt"));
        body = permit(sessionId).replace("true", "false");
        assertEquals(Decision.UNAVAILABLE, adapter.authorize(sessionId, "original-jwt"));
        body = permit(sessionId).replace("}", ",\"patientId\":\"leak\"}");
        assertEquals(Decision.UNAVAILABLE, adapter.authorize(sessionId, "original-jwt"));
        body = " ".repeat(1025) + permit(sessionId);
        assertEquals(Decision.UNAVAILABLE, adapter.authorize(sessionId, "original-jwt"));
    }

    @Test
    void mapsDenialTokenRejectionAndUnavailableWithoutGranting() {
        var adapter = adapter();
        status = 403;
        assertEquals(Decision.DENIED, adapter.authorize(sessionId, "original-jwt"));
        status = 401;
        assertEquals(Decision.INVALID_TOKEN, adapter.authorize(sessionId, "original-jwt"));
        status = 503;
        assertEquals(Decision.UNAVAILABLE, adapter.authorize(sessionId, "original-jwt"));
        status = 200;
        body = "not-json";
        assertEquals(Decision.UNAVAILABLE, adapter.authorize(sessionId, "original-jwt"));
    }

    @Test
    void rejectsContradictoryDuplicateClinicalAuthorizationFields() {
        var adapter = adapter();
        body = "{\"contractVersion\":1,\"permitted\":false,\"permitted\":true,"
                + "\"therapySessionId\":\"" + sessionId + "\"}";
        assertEquals(Decision.UNAVAILABLE, adapter.authorize(sessionId, "original-jwt"));
        body = "{\"contractVersion\":2,\"contractVersion\":1,\"permitted\":true,"
                + "\"therapySessionId\":\"" + sessionId + "\"}";
        assertEquals(Decision.UNAVAILABLE, adapter.authorize(sessionId, "original-jwt"));
        body = "{\"contractVersion\":1,\"permitted\":true,"
                + "\"therapySessionId\":\"" + UUID.randomUUID() + "\","
                + "\"therapySessionId\":\"" + sessionId + "\"}";
        assertEquals(Decision.UNAVAILABLE, adapter.authorize(sessionId, "original-jwt"));
    }

    private ClinicalTherapySessionAuthorizationAdapter adapter() {
        return new ClinicalTherapySessionAuthorizationAdapter(
                URI.create("http://127.0.0.1:" + server.getAddress().getPort()),
                1000, new ObjectMapper());
    }

    private static String permit(UUID id) {
        return "{\"contractVersion\":1,\"permitted\":true,\"therapySessionId\":\"" + id + "\"}";
    }
}
