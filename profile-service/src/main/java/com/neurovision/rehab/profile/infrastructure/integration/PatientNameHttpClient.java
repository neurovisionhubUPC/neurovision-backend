package com.neurovision.rehab.profile.infrastructure.integration;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.*;
import com.neurovision.rehab.profile.application.internal.queryservices.PatientNameFailure;
import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.http.*;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.Flow;
import static com.neurovision.rehab.profile.application.internal.queryservices.PatientNameFailure.Kind.*;

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
    HttpClient.Redirect redirectPolicy() { return client.followRedirects(); }
    HttpRequest request(String token, JsonNode body) {
        if (token == null || token.isBlank() || token.length() > 16384) throw new PatientNameFailure(UNAUTHENTICATED);
        return HttpRequest.newBuilder(target).timeout(timeout).header("Authorization","Bearer "+token)
                .header("Accept","application/json").header("Content-Type","application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body.toString())).build();
    }
    public JsonNode post(String token, JsonNode body) {
        CompletableFuture<HttpResponse<byte[]>> pending=null;
        try {
            pending=client.sendAsync(request(token,body),info->new LimitedBody(65536));
            var response=pending.get(timeout.toMillis(),TimeUnit.MILLISECONDS);
            if (response.statusCode()==401) throw new PatientNameFailure(UNAUTHENTICATED);
            if (response.statusCode()==403) throw new PatientNameFailure(FORBIDDEN);
            if (response.statusCode()!=200) throw new PatientNameFailure(UNAVAILABLE);
            return mapper.reader().with(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
                    .with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).readTree(response.body());
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt(); throw new PatientNameFailure(UNAVAILABLE);
        } catch (PatientNameFailure known) { throw known; }
        catch (Exception unavailable) { throw new PatientNameFailure(UNAVAILABLE); }
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
