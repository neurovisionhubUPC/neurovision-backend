package com.neurovision.rehab.analytics.infrastructure.integration;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.*;
import com.neurovision.rehab.analytics.application.ports.out.authorization.StandardScanningTrialAuthorizationPort;
import com.neurovision.rehab.analytics.domain.model.valueobjects.StandardScanningTrialContext;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.http.*;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.Flow;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/** Fixed Clinical destination, original patient token, bounded body/deadline, no retries or permission cache. */
@ApplicationScoped
public class ClinicalStandardScanningTrialAuthorizationAdapter implements StandardScanningTrialAuthorizationPort {
    static final int MAX_RESPONSE_BYTES = 2048;
    @FunctionalInterface interface Exchange {
        CompletableFuture<HttpResponse<byte[]>> send(HttpRequest request, HttpResponse.BodyHandler<byte[]> handler);
    }
    private final URI base;
    private final Duration timeout;
    private final ObjectReader reader;
    private final HttpClient client;
    private final Exchange exchange;

    @Inject
    public ClinicalStandardScanningTrialAuthorizationAdapter(
            @ConfigProperty(name = "analytics.clinical.base-url") URI base,
            @ConfigProperty(name = "analytics.clinical.timeout-ms") long timeoutMs, ObjectMapper mapper) {
        this(base, timeoutMs, mapper, null);
    }

    // Adapter-local seam follows Clinical's bounded HTTP pattern; no cross-service code dependency.
    ClinicalStandardScanningTrialAuthorizationAdapter(URI base, long timeoutMs, ObjectMapper mapper, Exchange exchange) {
        if (base == null || !("http".equals(base.getScheme()) || "https".equals(base.getScheme()))
                || base.getHost() == null || base.getUserInfo() != null || base.getRawQuery() != null
                || base.getRawFragment() != null || !(base.getRawPath().isEmpty() || "/".equals(base.getRawPath()))
                || base.getPort() == 0 || base.getPort() > 65535 || timeoutMs < 100 || timeoutMs > 10000)
            throw new IllegalArgumentException("Invalid fixed Clinical authorization configuration");
        this.base = base; this.timeout = Duration.ofMillis(timeoutMs);
        reader = Objects.requireNonNull(mapper).reader().with(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
                .with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
        client = HttpClient.newBuilder().connectTimeout(timeout).followRedirects(HttpClient.Redirect.NEVER).build();
        this.exchange = exchange == null ? client::sendAsync : exchange;
    }

    private static boolean validToken(String token) {
        return token != null && !token.isEmpty() && token.length() <= 16384
                && token.chars().allMatch(c -> c >= 'A' && c <= 'Z' || c >= 'a' && c <= 'z'
                    || c >= '0' && c <= '9' || "-._~+/=".indexOf(c) >= 0);
    }

    HttpRequest request(UUID session, String token) {
        if (session == null || !validToken(token)) throw new IllegalArgumentException("Invalid authorization request");
        URI target = base.resolve("/api/internal/v1/therapy-sessions/" + session + "/standard-scanning-result-authorization");
        return HttpRequest.newBuilder(target).timeout(timeout).header("Authorization", "Bearer " + token)
                .header("Accept", "application/json").GET().build();
    }
    Duration connectTimeout() { return client.connectTimeout().orElseThrow(); }
    HttpClient.Redirect redirectPolicy() { return client.followRedirects(); }
    static LimitedBody responseBody() { return new LimitedBody(); }

    @Override public Decision authorize(UUID session, String originalPatientAccessToken) {
        if (!validToken(originalPatientAccessToken)) return rejected(Permission.INVALID_TOKEN);
        if (session == null) return rejected(Permission.UNAVAILABLE);
        CompletableFuture<HttpResponse<byte[]>> pending = null;
        try {
            pending = exchange.send(request(session, originalPatientAccessToken), info -> responseBody());
            var response = pending.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
            return readResponse(response.statusCode(), response.body(), session);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return rejected(Permission.UNAVAILABLE);
        } catch (Exception unavailable) {
            return rejected(Permission.UNAVAILABLE);
        } finally {
            if (pending != null && !pending.isDone()) pending.cancel(true);
        }
    }

    Decision readResponse(int status, byte[] bytes, UUID requestedSession) {
        if (status == 401) return rejected(Permission.INVALID_TOKEN);
        if (status == 403) return rejected(Permission.DENIED);
        if (status != 200 || requestedSession == null || bytes == null || bytes.length > MAX_RESPONSE_BYTES)
            return rejected(Permission.UNAVAILABLE);
        try {
            JsonNode json = reader.readTree(bytes);
            if (json == null || !json.isObject() || json.size() != 8
                    || !json.path("contractVersion").isIntegralNumber() || !json.path("contractVersion").canConvertToInt()
                    || json.path("contractVersion").intValue() != 1
                    || !json.path("permitted").isBoolean() || !json.path("permitted").booleanValue()
                    || !json.path("mayInsert").isBoolean()
                    || !requestedSession.equals(canonicalUuid(json.path("therapySessionId"))))
                return rejected(Permission.UNAVAILABLE);
            var context = new StandardScanningTrialContext(requestedSession, canonicalUuid(json.path("configurationId")),
                    positiveLong(json.path("configurationVersion")), positiveInt(json.path("minimumFixationMs")),
                    positiveInt(json.path("maximumStimulusDurationMs")));
            return new Decision(Permission.PERMITTED, context, json.path("mayInsert").booleanValue());
        } catch (Exception invalid) { return rejected(Permission.UNAVAILABLE); }
    }

    private static UUID canonicalUuid(JsonNode value) {
        if (!value.isTextual()) throw new IllegalArgumentException();
        UUID id = UUID.fromString(value.textValue());
        if (!id.toString().equals(value.textValue())) throw new IllegalArgumentException();
        return id;
    }
    private static long positiveLong(JsonNode value) {
        if (!value.isIntegralNumber() || !value.canConvertToLong() || value.longValue() < 1)
            throw new IllegalArgumentException();
        return value.longValue();
    }
    private static int positiveInt(JsonNode value) {
        if (!value.canConvertToInt()) throw new IllegalArgumentException();
        return Math.toIntExact(positiveLong(value));
    }
    private static Decision rejected(Permission permission) { return new Decision(permission, null, false); }

    /** Same small bounded-subscriber pattern as existing Clinical clients; never buffers an unbounded response. */
    static final class LimitedBody implements HttpResponse.BodySubscriber<byte[]> {
        private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        private final CompletableFuture<byte[]> result = new CompletableFuture<>();
        private Flow.Subscription subscription;
        public CompletionStage<byte[]> getBody() { return result; }
        public void onSubscribe(Flow.Subscription next) {
            if (subscription != null || result.isDone()) { next.cancel(); return; }
            subscription = next; next.request(1);
        }
        public void onNext(List<ByteBuffer> buffers) {
            if (result.isDone()) return;
            for (var buffer : buffers) {
                if (buffer.remaining() > MAX_RESPONSE_BYTES - bytes.size()) {
                    subscription.cancel();
                    result.completeExceptionally(new IllegalStateException("Clinical authorization body limit"));
                    return;
                }
                byte[] chunk = new byte[buffer.remaining()]; buffer.get(chunk); bytes.writeBytes(chunk);
            }
            subscription.request(1);
        }
        public void onError(Throwable failure) {
            result.completeExceptionally(new IllegalStateException("Clinical authorization body unavailable"));
        }
        public void onComplete() { result.complete(bytes.toByteArray()); }
    }
}
