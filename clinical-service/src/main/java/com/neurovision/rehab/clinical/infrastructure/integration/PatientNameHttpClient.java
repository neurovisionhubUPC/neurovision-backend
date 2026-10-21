package com.neurovision.rehab.clinical.infrastructure.integration;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.*;
import com.neurovision.rehab.clinical.application.internal.queryservices.PatientReadFailure;
import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.http.*;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.Flow;
import static com.neurovision.rehab.clinical.application.internal.queryservices.PatientReadFailure.Kind.*;

/** Fixed-destination bounded delegated request. No redirects, retries, or body logging. */
public final class PatientNameHttpClient {
    @FunctionalInterface public interface Post { JsonNode send(String token, JsonNode body) throws Exception; }
    private final URI target;
    private final Duration timeout;
    private final ObjectMapper mapper;
    private final HttpClient client;
    public PatientNameHttpClient(URI target, long timeoutMs, ObjectMapper mapper) {
        if (target == null || !("http".equals(target.getScheme()) || "https".equals(target.getScheme()))
                || target.getHost() == null || target.getUserInfo() != null || target.getFragment() != null
                || target.getQuery() != null || timeoutMs < 100 || timeoutMs > 10000) throw new IllegalArgumentException("Invalid fixed peer configuration");
        this.target=target; this.timeout=Duration.ofMillis(timeoutMs); this.mapper=Objects.requireNonNull(mapper);
        this.client=HttpClient.newBuilder().connectTimeout(timeout).followRedirects(HttpClient.Redirect.NEVER).build();
    }
    // Compact JSON: 35-byte envelope, 100 rows, 123 fixed bytes per row (longest
    // status and UUID), two 100-UTF-16-unit names at up to six escaped bytes/unit,
    // and 99 row separators. Null names and shorter statuses require fewer bytes.
    static final int MAX_RESPONSE_BYTES = 35 + 100 * (123 + 2 * 100 * 6) + 99;
    static LimitedBody responseBody() { return new LimitedBody(MAX_RESPONSE_BYTES); }
    HttpClient.Redirect redirectPolicy() { return client.followRedirects(); }
    HttpRequest request(String token, JsonNode body) {
        if (token == null || token.isBlank() || token.length() > 16384) throw new PatientReadFailure(UNAUTHENTICATED);
        return HttpRequest.newBuilder(target).timeout(timeout).header("Authorization","Bearer "+token)
                .header("Accept","application/json").header("Content-Type","application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body.toString())).build();
    }
    public JsonNode post(String token, JsonNode body) {
        CompletableFuture<HttpResponse<byte[]>> pending=null;
        try {
            pending=client.sendAsync(request(token,body),info->responseBody());
            var response=pending.get(timeout.toMillis(),TimeUnit.MILLISECONDS);
            if (response.statusCode()==401) throw new PatientReadFailure(UNAUTHENTICATED);
            if (response.statusCode()==403) throw new PatientReadFailure(FORBIDDEN);
            if (response.statusCode()!=200) throw new PatientReadFailure(UNAVAILABLE);
            return mapper.reader().with(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
                    .with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).readTree(response.body());
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt(); throw new PatientReadFailure(UNAVAILABLE);
        } catch (PatientReadFailure known) { throw known; }
        catch (Exception unavailable) { throw new PatientReadFailure(UNAVAILABLE); }
        finally { if (pending!=null && !pending.isDone()) pending.cancel(true); }
    }
    /** Limits allocation while receiving, not merely after a full unbounded body. */
    static final class LimitedBody implements HttpResponse.BodySubscriber<byte[]> {
        private final int limit;
        private final ByteArrayOutputStream bytes=new ByteArrayOutputStream();
        private final CompletableFuture<byte[]> result=new CompletableFuture<>();
        private Flow.Subscription subscription;
        LimitedBody(int limit) { this.limit=limit; }
        public CompletionStage<byte[]> getBody() { return result; }
        public void onSubscribe(Flow.Subscription s) { if(subscription!=null){s.cancel();return;} subscription=s;s.request(1); }
        public void onNext(List<ByteBuffer> buffers) {
            if(result.isDone()) return;
            for(var buffer:buffers) {
                if(buffer.remaining() > limit-bytes.size()) { subscription.cancel();result.completeExceptionally(new IllegalStateException("Peer body limit"));return; }
                byte[] chunk=new byte[buffer.remaining()];buffer.get(chunk);bytes.writeBytes(chunk);
            }
            subscription.request(1);
        }
        public void onError(Throwable error) { result.completeExceptionally(new IllegalStateException("Peer body unavailable")); }
        public void onComplete() { result.complete(bytes.toByteArray()); }
    }
}
