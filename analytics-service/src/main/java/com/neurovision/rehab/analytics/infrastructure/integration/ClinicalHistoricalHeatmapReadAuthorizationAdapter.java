package com.neurovision.rehab.analytics.infrastructure.integration;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.neurovision.rehab.analytics.application.ports.out.authorization.HistoricalHeatmapReadAuthorizationPort;
import jakarta.enterprise.context.ApplicationScoped;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Objects;
import java.util.UUID;
import org.eclipse.microprofile.config.inject.ConfigProperty;

@ApplicationScoped
public class ClinicalHistoricalHeatmapReadAuthorizationAdapter
        implements HistoricalHeatmapReadAuthorizationPort {
    private final URI clinicalBaseUri;
    private final Duration timeout;
    private final HttpClient client;
    private final ObjectMapper mapper;

    public ClinicalHistoricalHeatmapReadAuthorizationAdapter(
            @ConfigProperty(name = "analytics.clinical.base-url") URI clinicalBaseUri,
            @ConfigProperty(name = "analytics.clinical.timeout-ms") long timeoutMs,
            ObjectMapper mapper) {
        if (clinicalBaseUri == null || timeoutMs <= 0 || !("http".equals(clinicalBaseUri.getScheme())
                || "https".equals(clinicalBaseUri.getScheme()))) {
            throw new IllegalArgumentException("Invalid Clinical integration configuration");
        }
        this.clinicalBaseUri = clinicalBaseUri;
        this.timeout = Duration.ofMillis(timeoutMs);
        this.client = HttpClient.newBuilder().connectTimeout(timeout)
                .followRedirects(HttpClient.Redirect.NEVER).build();
        this.mapper = Objects.requireNonNull(mapper);
    }

    @Override
    public Decision authorize(UUID therapySessionId, String originalAccessToken) {
        if (therapySessionId == null || originalAccessToken == null || originalAccessToken.isBlank()) {
            return Decision.UNAVAILABLE;
        }
        var target = clinicalBaseUri.resolve("/api/internal/v1/therapy-sessions/"
                + therapySessionId + "/telemetry-read-authorization");
        var request = HttpRequest.newBuilder(target).timeout(timeout)
                .header("Authorization", "Bearer " + originalAccessToken)
                .header("Accept", "application/json").GET().build();
        try {
            var response = client.send(request, HttpResponse.BodyHandlers.ofInputStream());
            try (var body = response.body()) {
                return switch (response.statusCode()) {
                    case 200 -> {
                        var bytes = body.readNBytes(1025);
                        yield bytes.length <= 1024 && validPermit(
                                new String(bytes, StandardCharsets.UTF_8), therapySessionId)
                                ? Decision.PERMITTED : Decision.UNAVAILABLE;
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
            JsonNode json = mapper.reader().with(JsonParser.Feature.STRICT_DUPLICATE_DETECTION).readTree(body);
            return json != null && json.isObject() && json.size() == 3
                    && json.path("contractVersion").isIntegralNumber()
                    && json.path("contractVersion").intValue() == 1
                    && json.path("permitted").isBoolean() && json.path("permitted").booleanValue()
                    && json.path("therapySessionId").isTextual()
                    && requestedId.toString().equals(json.path("therapySessionId").textValue());
        } catch (IOException malformed) {
            return false;
        }
    }
}
