package com.neurovision.rehab.clinical.infrastructure.integration;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.*;
import java.net.URI;
import java.net.http.*;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.*;

/** Fixed self-profile read for report identity, deliberately separate from scheduling policy. */
public final class ReportProfileHttpClient {
    @FunctionalInterface public interface Get { Profile send(String originalToken) throws Exception; }
    public record Profile(JsonNode body, boolean missing) {
        @Override public String toString() { return "ReportProfile[redacted]"; }
    }
    @FunctionalInterface interface Exchange {
        CompletableFuture<HttpResponse<byte[]>> send(HttpRequest request, HttpResponse.BodyHandler<byte[]> handler);
    }

    static final int MAX_RESPONSE_BYTES = 8192;
    private final URI target;
    private final Duration timeout;
    private final ObjectMapper mapper;
    private final HttpClient client;
    private final Exchange exchange;

    public ReportProfileHttpClient(URI base, long timeoutMs, ObjectMapper mapper) {
        this(base, timeoutMs, mapper, null);
    }

    // Transport seam for deterministic offline checks, not a configurable destination or redirect policy.
    ReportProfileHttpClient(URI base, long timeoutMs, ObjectMapper mapper, Exchange exchange) {
        if (base == null || !("http".equals(base.getScheme()) || "https".equals(base.getScheme()))
                || base.getHost() == null || base.getUserInfo() != null || base.getQuery() != null
                || base.getFragment() != null || !(base.getPath().isEmpty() || base.getPath().equals("/"))
                || timeoutMs < 100 || timeoutMs > 10000) {
            throw new IllegalArgumentException("Invalid fixed Profile peer configuration");
        }
        target = base.resolve("/api/v1/profile");
        timeout = Duration.ofMillis(timeoutMs);
        this.mapper = Objects.requireNonNull(mapper);
        client = HttpClient.newBuilder().connectTimeout(timeout).followRedirects(HttpClient.Redirect.NEVER).build();
        this.exchange = exchange == null ? client::sendAsync : exchange;
    }

    HttpRequest request(String token) {
        requireToken(token);
        return HttpRequest.newBuilder(target).timeout(timeout).header("Authorization", "Bearer " + token)
                .header("Accept", "application/json").GET().build();
    }

    static void requireToken(String token) {
        if (token == null || token.isBlank() || token.length() > 16384
                || token.chars().anyMatch(c -> c < 32 || c == 127)) throw unavailable();
    }

    HttpClient.Redirect redirectPolicy() { return client.followRedirects(); }
    Duration connectTimeout() { return client.connectTimeout().orElseThrow(); }
    static PatientNameHttpClient.LimitedBody responseBody() {
        return new PatientNameHttpClient.LimitedBody(MAX_RESPONSE_BYTES);
    }

    public Profile get(String originalToken) {
        CompletableFuture<HttpResponse<byte[]>> pending = null;
        try {
            pending = exchange.send(request(originalToken), info -> responseBody());
            var response = pending.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
            return readResponse(response.statusCode(), response.body());
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw unavailable();
        } catch (Exception failure) {
            // No dependency exception, payload, token or target is retained in the error chain.
            throw unavailable();
        } finally {
            if (pending != null && !pending.isDone()) pending.cancel(true);
        }
    }

    Profile readResponse(int status, byte[] bytes) {
        if ((status != 200 && status != 404) || bytes == null || bytes.length > MAX_RESPONSE_BYTES) throw unavailable();
        try {
            var body = mapper.reader().with(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
                    .with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).readTree(bytes);
            if (body == null || !body.isObject()) throw unavailable();
            if (status == 404) {
                var code = body.get("code");
                if (code == null || !code.isTextual() || !"PROFILE_NOT_FOUND".equals(code.textValue())) throw unavailable();
                return new Profile(null, true);
            }
            return new Profile(body, false);
        } catch (Exception failure) { throw unavailable(); }
    }

    static IllegalStateException unavailable() {
        return new IllegalStateException("Report Profile identity unavailable");
    }
}
