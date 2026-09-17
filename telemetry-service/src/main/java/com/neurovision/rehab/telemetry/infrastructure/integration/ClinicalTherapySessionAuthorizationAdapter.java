package com.neurovision.rehab.telemetry.infrastructure.integration;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.neurovision.rehab.telemetry.application.ports.out.authorization.TherapySessionAuthorizationPort;
import jakarta.enterprise.context.ApplicationScoped;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.UUID;
import org.eclipse.microprofile.config.inject.ConfigProperty;

@ApplicationScoped
public class ClinicalTherapySessionAuthorizationAdapter implements TherapySessionAuthorizationPort {
    private final URI clinicalBaseUri;
    private final Duration timeout;
    private final HttpClient client;
    private final ObjectMapper mapper;

    public ClinicalTherapySessionAuthorizationAdapter(
            @ConfigProperty(name = "telemetry.clinical.base-url") URI clinicalBaseUri,
            @ConfigProperty(name = "telemetry.clinical.timeout-ms") long timeoutMs,
            ObjectMapper mapper) {
        if (timeoutMs <= 0 || !"http".equals(clinicalBaseUri.getScheme())
                && !"https".equals(clinicalBaseUri.getScheme())) {
            throw new IllegalArgumentException("Invalid Clinical integration configuration");
        }
        this.clinicalBaseUri = clinicalBaseUri;
        this.timeout = Duration.ofMillis(timeoutMs);
        this.client = HttpClient.newBuilder().connectTimeout(timeout).followRedirects(HttpClient.Redirect.NEVER).build();
        this.mapper = mapper;
    }

    @Override
    public Decision authorize(UUID therapySessionId, String originalAccessToken) {
        if (therapySessionId == null || originalAccessToken == null || originalAccessToken.isBlank()) {
            return Decision.UNAVAILABLE;
        }
        var path = "/api/internal/v1/therapy-sessions/" + therapySessionId + "/telemetry-authorization";
        var target = clinicalBaseUri.resolve(path);
        var request = HttpRequest.newBuilder(target)
                .timeout(timeout)
                .header("Authorization", "Bearer " + originalAccessToken)
                .header("Accept", "application/json")
                .GET().build();
        try {
            var response = client.send(request, HttpResponse.BodyHandlers.ofInputStream());
            try (var responseBody = response.body()) {
                return switch (response.statusCode()) {
                    case 200 -> {
                        var bytes = responseBody.readNBytes(1025);
                        yield bytes.length <= 1024 && validPermit(new String(bytes, StandardCharsets.UTF_8),
                                therapySessionId) ? Decision.PERMITTED : Decision.UNAVAILABLE;
                    }
                    case 401 -> Decision.INVALID_TOKEN;
                    case 403 -> Decision.DENIED;
                    default -> Decision.UNAVAILABLE;
                };
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return Decision.UNAVAILABLE;
        } catch (IOException | RuntimeException unavailable) {
            return Decision.UNAVAILABLE;
        }
    }

    private boolean validPermit(String body, UUID requestedId) {
        try {
            JsonNode json = mapper.reader()
                    .with(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
                    .readTree(body);
            return json != null && json.isObject() && json.size() == 3
                    && json.path("contractVersion").isIntegralNumber()
                    && json.path("contractVersion").intValue() == 1
                    && json.path("permitted").isBoolean() && json.path("permitted").booleanValue()
                    && json.path("therapySessionId").isTextual()
                    && requestedId.toString().equals(json.path("therapySessionId").textValue());
        } catch (IOException malformedResponse) {
            return false;
        }
    }
}
