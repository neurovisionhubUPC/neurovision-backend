package com.neurovision.rehab.clinical.infrastructure.integration;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.*;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.Flow;
import javax.net.ssl.SSLSession;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ReportProfileHttpClientTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private final URI base = URI.create("http://profile:8080");

    @Test void fixedSelfGetHasOriginalTokenBoundedTimeoutAndNoRedirects() {
        var client = new ReportProfileHttpClient(base, 100, mapper);
        var request = client.request("original-token");
        assertEquals(URI.create("http://profile:8080/api/v1/profile"), request.uri());
        assertEquals("GET", request.method());
        assertEquals("Bearer original-token", request.headers().firstValue("Authorization").orElseThrow());
        assertEquals("application/json", request.headers().firstValue("Accept").orElseThrow());
        assertEquals(100, request.timeout().orElseThrow().toMillis());
        assertEquals(100, client.connectTimeout().toMillis());
        assertEquals(HttpClient.Redirect.NEVER, client.redirectPolicy());
        assertTrue(request.bodyPublisher().isEmpty());
        for (String value : List.of("file:///tmp/test", "http://user:secret@profile", "http://profile?x=1", "http://profile#x", "http://profile/other")) {
            assertThrows(IllegalArgumentException.class, () -> new ReportProfileHttpClient(URI.create(value), 100, mapper));
        }
        for (long timeout : new long[]{0, 99, 10001}) {
            assertThrows(IllegalArgumentException.class, () -> new ReportProfileHttpClient(base, timeout, mapper));
        }
        for (String token : new String[]{null, " ", "x".repeat(16385), "unsafe\r\nvalue"}) {
            assertThrows(IllegalStateException.class, () -> client.request(token));
        }
    }

    @Test void onlyExactProfileNotFound404ConfirmsMissing() {
        var client = new ReportProfileHttpClient(base, 100, mapper);
        assertTrue(client.readResponse(404, bytes("{\"code\":\"PROFILE_NOT_FOUND\",\"message\":\"not found\"}")).missing());
        assertFalse(client.readResponse(200, bytes("{\"code\":\"PROFILE_NOT_FOUND\"}")).missing());
        for (String body : List.of("{}", "{\"code\":404}", "{\"code\":\"OTHER_NOT_FOUND\"}", "{\"code\":\"profile_not_found\"}")) {
            assertThrows(IllegalStateException.class, () -> client.readResponse(404, bytes(body)));
        }
        for (int status : new int[]{204, 301, 302, 401, 403, 429, 500, 503}) {
            assertThrows(IllegalStateException.class, () -> client.readResponse(status, bytes("{\"code\":\"PROFILE_NOT_FOUND\"}")));
        }
    }

    @Test void strictJsonRejectsMalformedDuplicatesTrailingContentAndOversizeBodies() {
        var client = new ReportProfileHttpClient(base, 100, mapper);
        for (int status : new int[]{200, 404}) {
            for (String body : List.of("", "not-json", "null", "[]", "{}{}", "{\"code\":\"PROFILE_NOT_FOUND\",\"code\":\"PROFILE_NOT_FOUND\"}")) {
                var failure = assertThrows(IllegalStateException.class, () -> client.readResponse(status, bytes(body)));
                assertEquals("Report Profile identity unavailable", failure.getMessage()); assertNull(failure.getCause());
            }
            assertThrows(IllegalStateException.class, () -> client.readResponse(status, null));
            assertThrows(IllegalStateException.class, () -> client.readResponse(status, new byte[8193]));
        }
    }

    @Test void streamingBodyCapCancelsBeforeAllocatingOverLimit() throws Exception {
        boolean[] cancelled = {false};
        var body = ReportProfileHttpClient.responseBody();
        var subscription = new Flow.Subscription() { public void request(long n) {} public void cancel() { cancelled[0] = true; } };
        body.onSubscribe(subscription);
        body.onNext(List.of(ByteBuffer.wrap(new byte[8192]))); body.onComplete();
        assertEquals(8192, body.getBody().toCompletableFuture().get().length); assertFalse(cancelled[0]);
        body = ReportProfileHttpClient.responseBody(); body.onSubscribe(subscription);
        body.onNext(List.of(ByteBuffer.wrap(new byte[8192]), ByteBuffer.wrap(new byte[1])));
        assertTrue(cancelled[0]); assertTrue(body.getBody().toCompletableFuture().isCompletedExceptionally());
    }

    @Test void transportIsSingleAttemptWithOverallDeadlineCancellationAndSafeFailures() {
        int[] calls = {0};
        var pending = new CompletableFuture<HttpResponse<byte[]>>();
        var client = new ReportProfileHttpClient(base, 100, mapper, (request, handler) -> {
            calls[0]++; assertEquals("Bearer original", request.headers().firstValue("Authorization").orElseThrow());
            assertInstanceOf(PatientNameHttpClient.LimitedBody.class, handler.apply(new HttpResponse.ResponseInfo() {
                public int statusCode() { return 200; }
                public HttpHeaders headers() { return HttpHeaders.of(Map.of(), (a,b) -> true); }
                public HttpClient.Version version() { return HttpClient.Version.HTTP_1_1; }
            }));
            return pending;
        });
        var error = assertThrows(IllegalStateException.class, () -> client.get("original"));
        assertEquals("Report Profile identity unavailable", error.getMessage()); assertNull(error.getCause());
        assertEquals(1, calls[0]); assertTrue(pending.isCancelled());
        var failing = new ReportProfileHttpClient(base, 100, mapper, (request, handler) -> {
            throw new IllegalArgumentException("private upstream");
        });
        assertNull(assertThrows(IllegalStateException.class, () -> failing.get("original")).getCause());
    }

    @Test void mockedSuccessAndInterruptedWaitNeedNoSockets() {
        var response = new HttpResponse<byte[]>() {
            public int statusCode() { return 200; }
            public byte[] body() { return bytes("{\"status\":\"ACTIVE\"}"); }
            public HttpRequest request() { return null; }
            public Optional<HttpResponse<byte[]>> previousResponse() { return Optional.empty(); }
            public HttpHeaders headers() { return HttpHeaders.of(Map.of(), (a,b) -> true); }
            public Optional<SSLSession> sslSession() { return Optional.empty(); }
            public URI uri() { return base; }
            public HttpClient.Version version() { return HttpClient.Version.HTTP_1_1; }
        };
        var client = new ReportProfileHttpClient(base, 100, mapper,
                (request, handler) -> CompletableFuture.completedFuture(response));
        assertEquals("ACTIVE", client.get("original").body().get("status").textValue());
        var pending = new CompletableFuture<HttpResponse<byte[]>>();
        client = new ReportProfileHttpClient(base, 100, mapper, (request, handler) -> pending);
        try {
            Thread.currentThread().interrupt();
            var interruptedClient = client;
            assertThrows(IllegalStateException.class, () -> interruptedClient.get("original"));
            assertTrue(Thread.currentThread().isInterrupted()); assertTrue(pending.isCancelled());
        } finally { Thread.interrupted(); }
    }

    private byte[] bytes(String body) { return body.getBytes(StandardCharsets.UTF_8); }
}
