package com.neurovision.rehab.clinical.infrastructure.integration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.neurovision.rehab.clinical.application.internal.commandservices.DoctorSchedulingUnavailableException;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.Flow;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class DoctorProfileHttpClientTest {
    private final DoctorProfileHttpClient client = new DoctorProfileHttpClient(URI.create("http://profile:8080"), 4000, new ObjectMapper());
    @Test void fixedSelfReadHasBoundedTimeoutOriginalTokenAndNoRedirects() {
        var request = client.request("original");
        assertEquals("http://profile:8080/api/v1/profile", request.uri().toString());
        assertEquals("GET", request.method());
        assertEquals("Bearer original", request.headers().firstValue("Authorization").orElseThrow());
        assertEquals(4000, request.timeout().orElseThrow().toMillis());
        assertEquals(HttpClient.Redirect.NEVER, client.redirectPolicy());
        assertThrows(SecurityException.class, () -> client.request(" "));
        for (String base : List.of("file:///tmp/test", "http://user:secret@profile", "http://profile?token=secret", "http://profile#fragment", "http://profile/other")) {
            assertThrows(IllegalArgumentException.class, () -> new DoctorProfileHttpClient(URI.create(base), 4000, new ObjectMapper()));
        }
    }
    @Test void enforcesStreamingBodyLimitWithoutReadingAnUnboundedResponse() throws Exception {
        var body = DoctorProfileHttpClient.responseBody();
        boolean[] cancelled = {false};
        body.onSubscribe(new Flow.Subscription() { public void request(long n) {} public void cancel() { cancelled[0] = true; } });
        body.onNext(List.of(ByteBuffer.wrap(new byte[DoctorProfileHttpClient.MAX_RESPONSE_BYTES])));
        assertFalse(cancelled[0]); body.onComplete();
        assertEquals(8192, body.getBody().toCompletableFuture().get().length);
        body = DoctorProfileHttpClient.responseBody();
        body.onSubscribe(new Flow.Subscription() { public void request(long n) {} public void cancel() { cancelled[0] = true; } });
        body.onNext(List.of(ByteBuffer.wrap(new byte[8193])));
        assertTrue(cancelled[0]); assertTrue(body.getBody().toCompletableFuture().isCompletedExceptionally());
    }
    @Test void rejectsFailureStatusesMalformedDuplicateAndTrailingJson() {
        for (int code : new int[]{401, 403, 404}) assertThrows(SecurityException.class, () -> client.readResponse(code, new byte[0]));
        for (int code : new int[]{301, 302, 429, 500, 503}) assertThrows(DoctorSchedulingUnavailableException.class, () -> client.readResponse(code, new byte[0]));
        for (String invalid : List.of("not-json", "{}{}", "{\"status\":\"ACTIVE\",\"status\":\"PENDING_COMPLETION\"}")) {
            assertThrows(DoctorSchedulingUnavailableException.class, () -> client.readResponse(200, invalid.getBytes(StandardCharsets.UTF_8)));
        }
        assertTrue(client.readResponse(200, "{}".getBytes(StandardCharsets.UTF_8)).isObject());
    }
}
