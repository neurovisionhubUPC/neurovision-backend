package com.neurovision.rehab.clinical.infrastructure.integration;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.*;
import com.neurovision.rehab.clinical.application.ports.out.integration.SessionReportVisualSearchPort.Origin;
import java.net.URI;
import java.net.http.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;

/** Fixed-origin, bounded GET transport constructed by the authenticated report-preview wiring. */
public final class ReportAnalyticsHttpClient {
    public enum Resource {
        DISCOVERY(32 * 1024), SUMMARY(256 * 1024), HEATMAP(1024 * 1024), GAZE(1024 * 1024);
        private final int maximumBytes;
        Resource(int maximumBytes) { this.maximumBytes = maximumBytes; }
        int maximumBytes() { return maximumBytes; }
    }
    @FunctionalInterface
    public interface Get {
        JsonNode get(UUID session, UUID stream, Resource resource, String originalAccessToken) throws Exception;
    }
    @FunctionalInterface
    interface Send {
        CompletableFuture<HttpResponse<byte[]>> send(HttpRequest request, HttpResponse.BodyHandler<byte[]> body);
    }
    private final URI origin;
    private final Duration timeout;
    private final ObjectReader json;
    private final HttpClient client;
    private final Send send;

    public ReportAnalyticsHttpClient(URI origin, long timeoutMs, ObjectMapper mapper) {
        this(origin, timeoutMs, mapper, null);
    }
    ReportAnalyticsHttpClient(URI origin, long timeoutMs, ObjectMapper mapper, Send send) {
        if (origin == null || !("http".equals(origin.getScheme()) || "https".equals(origin.getScheme()))
                || origin.getHost() == null || origin.getUserInfo() != null || origin.getFragment() != null
                || origin.getQuery() != null || !(origin.getRawPath().isEmpty() || "/".equals(origin.getRawPath()))
                || timeoutMs < 100 || timeoutMs > 10000)
            throw new IllegalArgumentException("Invalid fixed Analytics configuration");
        this.origin = origin.resolve("/");
        this.timeout = Duration.ofMillis(timeoutMs);
        this.json = Objects.requireNonNull(mapper).reader().with(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
                .with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
        this.client = HttpClient.newBuilder().connectTimeout(timeout).followRedirects(HttpClient.Redirect.NEVER).build();
        this.send = send == null ? client::sendAsync : send;
    }
    HttpClient.Redirect redirectPolicy() { return client.followRedirects(); }
    Duration connectTimeout() { return client.connectTimeout().orElseThrow(); }

    HttpRequest request(UUID session, UUID stream, Resource resource, String token) {
        try {
            requireToken(token);
            Objects.requireNonNull(session); Objects.requireNonNull(resource);
            String path = "/api/v1/analytics/sessions/" + session + "/streams";
            if (resource == Resource.DISCOVERY) {
                if (stream != null) throw unavailable();
                path += "?page=0&size=20";
            } else {
                Objects.requireNonNull(stream);
                path += "/" + stream + switch (resource) {
                    case SUMMARY -> "/summary";
                    case HEATMAP -> "/heatmap";
                    case GAZE -> "/gaze-plot?pageSize=100";
                    default -> throw unavailable();
                };
            }
            return HttpRequest.newBuilder(origin.resolve(path)).timeout(timeout)
                    .header("Authorization", "Bearer " + token).header("Accept", "application/json").GET().build();
        } catch (RuntimeException invalid) { throw unavailable(); }
    }

    HttpRequest visualSearchRequest(UUID session, Origin originSelection, String token) {
        try {
            requireToken(token); Objects.requireNonNull(session); Objects.requireNonNull(originSelection);
            String path = "/api/v1/analytics/sessions/" + session + "/visual-search/summary?dataOrigin=" + originSelection.name();
            return HttpRequest.newBuilder(origin.resolve(path)).timeout(timeout)
                    .header("Authorization", "Bearer " + token).header("Accept", "application/json").GET().build();
        } catch (RuntimeException invalid) { throw unavailable(); }
    }
    public JsonNode getVisualSearch(UUID session, Origin originSelection, String originalAccessToken) {
        return get(visualSearchRequest(session,originSelection,originalAccessToken),2048,json.with(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS));
    }
    public JsonNode get(UUID session, UUID stream, Resource resource, String originalAccessToken) {
        return get(request(session,stream,resource,originalAccessToken),resource.maximumBytes(),json);
    }
    private JsonNode get(HttpRequest request, int maximumBytes, ObjectReader reader) {
        CompletableFuture<HttpResponse<byte[]>> pending = null;
        try {
            pending = send.send(request, info -> new PatientNameHttpClient.LimitedBody(maximumBytes));
            var response = pending.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
            if (response.statusCode() != 200 || response.body() == null || response.body().length > maximumBytes)
                throw unavailable();
            JsonNode result = reader.readTree(response.body());
            if (result == null || !result.isObject()) throw unavailable();
            return result;
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt(); throw unavailable();
        } catch (Exception unavailable) { throw unavailable(); }
        finally { if (pending != null && !pending.isDone()) pending.cancel(true); }
    }
    static void requireToken(String token) {
        if (token == null || token.isBlank() || token.length() > 16384
                || token.chars().anyMatch(c -> c <= 32 || c >= 127)) throw unavailable();
    }
    static IllegalStateException unavailable() { return new IllegalStateException("Report analytics unavailable"); }
}
