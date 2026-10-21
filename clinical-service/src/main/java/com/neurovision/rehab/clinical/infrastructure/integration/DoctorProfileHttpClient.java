package com.neurovision.rehab.clinical.infrastructure.integration;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.*;
import com.neurovision.rehab.clinical.application.internal.commandservices.DoctorSchedulingUnavailableException;
import java.net.URI;
import java.net.http.*;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.*;

/** Bounded fixed-destination self-profile read. No redirects, application retries or body logging. */
public final class DoctorProfileHttpClient {
    @FunctionalInterface public interface Get { JsonNode send(String token) throws Exception; }
    static final int MAX_RESPONSE_BYTES = 8192;
    private final URI target;
    private final Duration timeout;
    private final ObjectMapper mapper;
    private final HttpClient client;

    public DoctorProfileHttpClient(URI base, long timeoutMs, ObjectMapper mapper) {
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
    }

    HttpRequest request(String token) {
        if (token == null || token.isBlank() || token.length() > 16384) throw new SecurityException("Doctor scheduling is not permitted");
        return HttpRequest.newBuilder(target).timeout(timeout).header("Authorization", "Bearer " + token)
                .header("Accept", "application/json").GET().build();
    }
    HttpClient.Redirect redirectPolicy() { return client.followRedirects(); }
    static PatientNameHttpClient.LimitedBody responseBody() { return new PatientNameHttpClient.LimitedBody(MAX_RESPONSE_BYTES); }

    public JsonNode get(String token) {
        CompletableFuture<HttpResponse<byte[]>> pending = null;
        try {
            pending = client.sendAsync(request(token), info -> responseBody());
            var response = pending.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
            return readResponse(response.statusCode(), response.body());
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt(); throw new DoctorSchedulingUnavailableException();
        } catch (SecurityException | DoctorSchedulingUnavailableException known) { throw known; }
        catch (Exception unavailable) { throw new DoctorSchedulingUnavailableException(); }
        finally { if (pending != null && !pending.isDone()) pending.cancel(true); }
    }

    JsonNode readResponse(int status, byte[] body) {
        if (status == 401 || status == 403 || status == 404) throw new SecurityException("Doctor scheduling is not permitted");
        if (status != 200 || body == null || body.length > MAX_RESPONSE_BYTES) throw new DoctorSchedulingUnavailableException();
        try {
            return mapper.reader().with(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
                    .with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).readTree(body);
        } catch (Exception invalid) { throw new DoctorSchedulingUnavailableException(); }
    }
}
