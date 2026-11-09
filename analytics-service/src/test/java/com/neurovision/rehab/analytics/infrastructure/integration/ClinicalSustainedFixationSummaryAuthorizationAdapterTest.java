package com.neurovision.rehab.analytics.infrastructure.integration;

import static org.junit.jupiter.api.Assertions.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.neurovision.rehab.analytics.application.ports.out.authorization.SustainedFixationTrialSummaryAuthorizationPort.Permission;
import java.net.URI;
import java.net.http.*;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.Flow;
import javax.net.ssl.SSLSession;
import org.junit.jupiter.api.Test;

class ClinicalSustainedFixationSummaryAuthorizationAdapterTest {
    static final URI BASE = URI.create("http://clinical:8080");
    static final UUID SESSION = new UUID(0, 1), CONFIGURATION = new UUID(0, 2);
    final ObjectMapper mapper = new ObjectMapper();
    String permit() {
        return "{\"contractVersion\":1,\"permitted\":true,\"therapySessionId\":\"" + SESSION
                + "\",\"configurationId\":\"" + CONFIGURATION
                + "\",\"configurationVersion\":3,\"minimumFixationMs\":500,\"maximumFixationMs\":3000}";
    }
    byte[] bytes(String value) { return value.getBytes(StandardCharsets.UTF_8); }
    ClinicalSustainedFixationSummaryAuthorizationAdapter adapter(ClinicalVisualSearchTrialAuthorizationAdapter.Exchange exchange) {
        return new ClinicalSustainedFixationSummaryAuthorizationAdapter(BASE, 100, mapper, exchange);
    }

    @Test void fixedRequestUsesOriginalBearerNoBodyNoRedirectsAndBoundedTimeouts() {
        var client = adapter((request, handler) -> CompletableFuture.completedFuture(response(200, bytes(permit()))));
        var request = client.request(SESSION, "original.doctor-token");
        assertEquals(BASE.resolve("/api/internal/v1/therapy-sessions/" + SESSION + "/sustained-fixation-summary-authorization"), request.uri());
        assertEquals("GET", request.method()); assertTrue(request.bodyPublisher().isEmpty());
        assertEquals("Bearer original.doctor-token", request.headers().firstValue("Authorization").orElseThrow());
        assertEquals("application/json", request.headers().firstValue("Accept").orElseThrow());
        assertEquals(100, request.timeout().orElseThrow().toMillis());
        assertEquals(100, client.connectTimeout().toMillis()); assertEquals(HttpClient.Redirect.NEVER, client.redirectPolicy());
    }

    @Test void permitsBuildOnlyFrozenContextAndAuthorizationIsNeverCached() {
        int[] calls = {0};
        var client = adapter((request, handler) -> {
            assertEquals("Bearer original", request.headers().firstValue("Authorization").orElseThrow());
            calls[0]++;
            return CompletableFuture.completedFuture(response(calls[0] == 3 ? 403 : 200, bytes(permit())));
        });
        var first = client.authorize(SESSION, "original");
        assertEquals(Permission.PERMITTED, first.permission());
        var context = first.context();
        assertEquals(SESSION, context.therapySessionId()); assertEquals(CONFIGURATION, context.configurationId());
        assertEquals(3, context.configurationVersion()); assertEquals(500, context.minimumFixationMs());
        assertEquals(3000, context.maximumFixationMs());
        var second = client.authorize(SESSION, "original");
        assertEquals(Permission.PERMITTED, second.permission());
        assertEquals(context, second.context());
        var revoked = client.authorize(SESSION, "original");
        assertEquals(Permission.DENIED, revoked.permission()); assertNull(revoked.context());
        assertEquals(3, calls[0]); assertEquals("SustainedFixationSummaryAuthorization[redacted]", first.toString());
    }

    @Test void invalidCredentialsOrSessionNeverInvokeTransportAndNeverNormalizeToken() {
        int[] calls = {0};
        var client = adapter((request, handler) -> { calls[0]++; throw new AssertionError("Transport must not run"); });
        for (String token : Arrays.asList(null, "", " ", " token", "token ", "x\r\ny", "x\ty", "t\u00f6k\u00e9n", "a".repeat(16385)))
            assertEquals(Permission.INVALID_TOKEN, client.authorize(SESSION, token).permission());
        assertEquals(Permission.UNAVAILABLE, client.authorize(null, "original").permission()); assertEquals(0, calls[0]);
    }

    @Test void invalidPeerOriginsAndTimeoutsFailBeforeRequests() {
        for (String value : List.of("file:///test", "http://user:secret@clinical", "http://clinical?x=1", "http://clinical#x",
                "http://clinical/path", "http://clinical:0", "http://clinical:65536", "http:/missing-host"))
            assertThrows(IllegalArgumentException.class, () -> new ClinicalSustainedFixationSummaryAuthorizationAdapter(URI.create(value), 100, mapper));
        for (long timeout : new long[]{0, 99, 10001})
            assertThrows(IllegalArgumentException.class, () -> new ClinicalSustainedFixationSummaryAuthorizationAdapter(BASE, timeout, mapper));
        assertThrows(IllegalArgumentException.class, () -> new ClinicalSustainedFixationSummaryAuthorizationAdapter(null, 100, mapper));
    }

    @Test void statusMappingNeverTreatsMissingDependencyOrRedirectAsPermission() {
        var client = adapter((request, handler) -> null);
        assertEquals(Permission.INVALID_TOKEN, client.readResponse(401, null, SESSION).permission());
        assertEquals(Permission.DENIED, client.readResponse(403, null, SESSION).permission());
        for (int status : new int[]{201, 204, 301, 302, 307, 404, 409, 429, 500, 503}) {
            var denied = client.readResponse(status, bytes(permit()), SESSION);
            assertEquals(Permission.UNAVAILABLE, denied.permission()); assertNull(denied.context());
        }
    }

    @Test void strictPayloadRejectsStructuralAndNumericAmbiguityWithoutLeakingValues() {
        var client = adapter((request, handler) -> null);
        String good = permit();
        for (String bad : List.of("", "null", "[]", "{}", good + "{}", good + " true", good.substring(0, good.length() - 1)
                + ",\"contractVersion\":1}", good.replace("\"contractVersion\":1", "\"contractVersion\":4294967297"),
                good.replace("\"permitted\":true", "\"permitted\":false"), good.substring(0, good.length() - 1) + ",\"mayInsert\":true}",
                good.replace(SESSION.toString(), new UUID(0, 99).toString()), good.replace(CONFIGURATION.toString(), "1-1-1-1-1"),
                good.replace("\"configurationVersion\":3", "\"configurationVersion\":9223372036854775808"),
                good.replace("\"configurationVersion\":3", "\"configurationVersion\":0"),
                good.replace("\"minimumFixationMs\":500", "\"minimumFixationMs\":0"),
                good.replace("\"minimumFixationMs\":500", "\"minimumFixationMs\":500.0"),
                good.replace("\"maximumFixationMs\":3000", "\"maximumFixationMs\":1"),
                good.replace("\"maximumFixationMs\":3000", "\"maximumFixationMs\":5001"),
                good.replace("\"maximumFixationMs\":3000", "\"maximumFixationMs\":4294970296"),
                good.replace("\"maximumFixationMs\":", "\"maximumStimulusDurationMs\":"),
                good.replace("\"configurationId\":", "\"unknown\":"), good.replace("\"configurationVersion\":3,", ""),
                good.substring(0, good.length() - 1) + ",\"private\":\"secret\"}")) {
            var decision = client.readResponse(200, bytes(bad), SESSION);
            assertEquals(Permission.UNAVAILABLE, decision.permission()); assertNull(decision.context());
            assertEquals("SustainedFixationSummaryAuthorization[redacted]", decision.toString());
        }
        assertEquals(Permission.UNAVAILABLE, client.readResponse(200, null, SESSION).permission());
        assertEquals(Permission.UNAVAILABLE, client.readResponse(200, new byte[2049], SESSION).permission());
        assertEquals(Permission.UNAVAILABLE, client.readResponse(200, bytes(good), null).permission());
        assertEquals(Permission.PERMITTED, client.readResponse(200, bytes(good + " \n"), SESSION).permission());
    }

    @Test void boundedSubscriberCancelsBeforeCopyingExcessAndRedactsFailure() throws Exception {
        boolean[] cancelled = {false};
        var subscription = new Flow.Subscription() { public void request(long n) {} public void cancel() { cancelled[0] = true; } };
        var body = ClinicalVisualSearchTrialAuthorizationAdapter.responseBody(); body.onSubscribe(subscription);
        body.onNext(List.of(ByteBuffer.wrap(new byte[2048]))); body.onComplete();
        assertEquals(2048, body.getBody().toCompletableFuture().get().length); assertFalse(cancelled[0]);
        body = ClinicalVisualSearchTrialAuthorizationAdapter.responseBody(); body.onSubscribe(subscription);
        var excess = ByteBuffer.wrap(new byte[2049]); body.onNext(List.of(excess));
        assertTrue(cancelled[0]); assertEquals(0, excess.position());
        assertTrue(body.getBody().toCompletableFuture().isCompletedExceptionally());
        body = ClinicalVisualSearchTrialAuthorizationAdapter.responseBody(); body.onError(new RuntimeException("private-upstream"));
        var failedBody = body;
        var failure = assertThrows(ExecutionException.class, () -> failedBody.getBody().toCompletableFuture().get());
        assertEquals("Clinical authorization body unavailable", failure.getCause().getMessage()); assertNull(failure.getCause().getCause());
    }

    @Test void pendingBodyWaitHasOverallDeadlineSingleAttemptAndCancellation() {
        int[] calls = {0}; var pending = new CompletableFuture<HttpResponse<byte[]>>();
        var client = adapter((request, handler) -> { calls[0]++; assertNotNull(handler.apply(info())); return pending; });
        assertEquals(Permission.UNAVAILABLE, client.authorize(SESSION, "original").permission());
        assertEquals(1, calls[0]); assertTrue(pending.isCancelled());
    }

    @Test void transportFailuresAndInterruptsFailClosedWithoutCauseOrRetries() {
        for (int kind = 0; kind < 3; kind++) {
            int value = kind;
            var client = adapter((request, handler) -> {
                if (value == 0) throw new IllegalArgumentException("private-upstream");
                if (value == 1) return null;
                return CompletableFuture.failedFuture(new IllegalStateException("private-upstream"));
            });
            assertEquals(Permission.UNAVAILABLE, client.authorize(SESSION, "original").permission());
        }
        var pending = new CompletableFuture<HttpResponse<byte[]>>();
        try {
            Thread.currentThread().interrupt();
            assertEquals(Permission.UNAVAILABLE, adapter((request, handler) -> pending).authorize(SESSION, "original").permission());
            assertTrue(Thread.currentThread().isInterrupted()); assertTrue(pending.isCancelled());
        } finally { Thread.interrupted(); }
    }

    private HttpResponse.ResponseInfo info() { return new HttpResponse.ResponseInfo() {
        public int statusCode() { return 200; }
        public HttpHeaders headers() { return HttpHeaders.of(Map.of(), (a,b) -> true); }
        public HttpClient.Version version() { return HttpClient.Version.HTTP_1_1; }
    }; }
    private HttpResponse<byte[]> response(int status, byte[] bytes) { return new HttpResponse<>() {
        public int statusCode() { return status; } public byte[] body() { return bytes; }
        public HttpRequest request() { return null; } public Optional<HttpResponse<byte[]>> previousResponse() { return Optional.empty(); }
        public HttpHeaders headers() { return HttpHeaders.of(Map.of(), (a,b) -> true); }
        public Optional<SSLSession> sslSession() { return Optional.empty(); } public URI uri() { return BASE; }
        public HttpClient.Version version() { return HttpClient.Version.HTTP_1_1; }
    }; }
    @Test void authorizeSuppliesTheBoundedBodyHandlerAndFailsClosedOnFragmentedOverflow() {
        int[] calls = {0}; boolean[] cancelled = {false};
        var overflow = ByteBuffer.wrap(new byte[1025]);
        var client = adapter((request, handler) -> {
            calls[0]++;
            var body = handler.apply(info());
            body.onSubscribe(new Flow.Subscription() {
                public void request(long n) {} public void cancel() { cancelled[0] = true; }
            });
            body.onNext(List.of(ByteBuffer.wrap(new byte[1024])));
            body.onNext(List.of(overflow));
            return body.getBody().toCompletableFuture().thenApply(value -> response(200, value));
        });
        var decision = client.authorize(SESSION, "synthetic-original-doctor");
        assertEquals(Permission.UNAVAILABLE, decision.permission()); assertNull(decision.context());
        assertEquals(1, calls[0]); assertTrue(cancelled[0]); assertEquals(0, overflow.position());
    }
    @Test void escapedDuplicateFieldCannotProduceAPermit() {
        var client = adapter((request, handler) -> null);
        String escapedKey = "contract" + "\\u0056" + "ersion";
        var json = permit();
        var decision = client.readResponse(200,
                bytes(json.substring(0, json.length()-1) + ",\"" + escapedKey + "\":1}"), SESSION);
        assertEquals(Permission.UNAVAILABLE, decision.permission()); assertNull(decision.context());
    }
}
