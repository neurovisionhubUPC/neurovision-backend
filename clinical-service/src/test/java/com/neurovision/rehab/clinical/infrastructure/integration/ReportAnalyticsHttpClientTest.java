package com.neurovision.rehab.clinical.infrastructure.integration;

import java.net.*;
import java.net.http.*;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.Flow;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static com.neurovision.rehab.clinical.infrastructure.integration.ReportAnalyticsDecoderTest.*;

class ReportAnalyticsHttpClientTest {
    static final URI BASE=URI.create("https://analytics.invalid");

    @Test void buildsOnlyFixedRequestsWithOriginalBearerAndConfiguredBounds() {
        var client=new ReportAnalyticsHttpClient(BASE,250,JSON);
        assertEquals(HttpClient.Redirect.NEVER,client.redirectPolicy());
        assertEquals(Duration.ofMillis(250),client.connectTimeout());
        for(var resource:ReportAnalyticsHttpClient.Resource.values()) {
            var request=client.request(SESSION,resource==ReportAnalyticsHttpClient.Resource.DISCOVERY?null:STREAM,resource,"a.b.synthetic");
            String root="https://analytics.invalid/api/v1/analytics/sessions/"+SESSION+"/streams";
            String suffix=switch(resource) {
                case DISCOVERY->"?page=0&size=20";
                case SUMMARY->"/"+STREAM+"/summary";
                case HEATMAP->"/"+STREAM+"/heatmap";
                case GAZE->"/"+STREAM+"/gaze-plot?pageSize=100";
            };
            assertEquals(root+suffix,request.uri().toString()); assertEquals("GET",request.method());
            assertEquals("Bearer a.b.synthetic",request.headers().firstValue("Authorization").orElseThrow());
            assertEquals("application/json",request.headers().firstValue("Accept").orElseThrow());
            assertEquals(Duration.ofMillis(250),request.timeout().orElseThrow()); assertTrue(request.bodyPublisher().isEmpty());
        }
    }

    @Test void rejectsUntrustedFixedOriginsAndInvalidTokens() {
        for(String uri:List.of("file:///tmp/data","https://name:password@analytics.invalid","https://analytics.invalid/base",
                "https://analytics.invalid/?query=x","https://analytics.invalid/#fragment","https://analytics.invalid/%2e%2e"))
            assertThrows(IllegalArgumentException.class,()->new ReportAnalyticsHttpClient(URI.create(uri),100,JSON));
        for(long timeout:List.of(99L,10001L)) assertThrows(IllegalArgumentException.class,()->new ReportAnalyticsHttpClient(BASE,timeout,JSON));
        var client=new ReportAnalyticsHttpClient(BASE,100,JSON);
        for(String token:List.of("", " ","line\r\nbreak", "a".repeat(16385)))
            assertThrows(IllegalStateException.class,()->client.request(SESSION,null,ReportAnalyticsHttpClient.Resource.DISCOVERY,token));
    }

    @Test void strictJsonAndEveryNonSuccessStatusFailClosedWithoutPayloadOrCause() {
        for(int status:List.of(201,204,301,302,307,308,400,401,403,404,409,429,500,503)) {
            var error=assertThrows(IllegalStateException.class,()->client(status,"sensitive response").get(SESSION,null,ReportAnalyticsHttpClient.Resource.DISCOVERY,"token"));
            assertEquals("Report analytics unavailable",error.getMessage()); assertNull(error.getCause());
        }
        for(String body:List.of("{\"x\":1,\"x\":2}","{} {}","null","[]","","{broken","{\"x\":NaN}"))
            assertThrows(IllegalStateException.class,()->client(200,body).get(SESSION,null,ReportAnalyticsHttpClient.Resource.DISCOVERY,"token"));
        assertTrue(client(200,"{\"items\":[]}").get(SESSION,null,ReportAnalyticsHttpClient.Resource.DISCOVERY,"token").isObject());
    }

    @Test void streamingLimitRejectsAndCancelsBeforeAllocatingOversizedBody() throws Exception {
        var client=new ReportAnalyticsHttpClient(BASE,100,JSON,(request,handler)->{
            var body=handler.apply(null); var subscription=new Subscription(); body.onSubscribe(subscription);
            body.onNext(List.of(ByteBuffer.wrap(new byte[ReportAnalyticsHttpClient.Resource.DISCOVERY.maximumBytes()+1])));
            assertTrue(subscription.cancelled);
            assertThrows(ExecutionException.class,()->body.getBody().toCompletableFuture().get());
            return CompletableFuture.failedFuture(new IllegalStateException("sensitive"));
        });
        assertThrows(IllegalStateException.class,()->client.get(SESSION,null,ReportAnalyticsHttpClient.Resource.DISCOVERY,"token"));
        var body=new PatientNameHttpClient.LimitedBody(4); var subscription=new Subscription(); body.onSubscribe(subscription);
        body.onNext(List.of(ByteBuffer.wrap(new byte[]{1,2}),ByteBuffer.wrap(new byte[]{3,4}))); body.onComplete();
        assertArrayEquals(new byte[]{1,2,3,4},body.getBody().toCompletableFuture().get()); assertFalse(subscription.cancelled);
    }

    @Test void boundedWaitCancelsPendingAndInterruptIsPreserved() {
        var pending=new CompletableFuture<HttpResponse<byte[]>>();
        var timeout=new ReportAnalyticsHttpClient(BASE,100,JSON,(r,h)->pending);
        assertThrows(IllegalStateException.class,()->timeout.get(SESSION,null,ReportAnalyticsHttpClient.Resource.DISCOVERY,"token"));
        assertTrue(pending.isCancelled());
        var interrupted=new CompletableFuture<HttpResponse<byte[]>>();
        var client=new ReportAnalyticsHttpClient(BASE,100,JSON,(r,h)->interrupted);
        try {
            Thread.currentThread().interrupt();
            var error=assertThrows(IllegalStateException.class,()->client.get(SESSION,null,ReportAnalyticsHttpClient.Resource.DISCOVERY,"token"));
            assertTrue(Thread.currentThread().isInterrupted()); assertTrue(interrupted.isCancelled()); assertNull(error.getCause());
        } finally { Thread.interrupted(); }
    }

    @Test void transportDoesNotTruncateOversizedCompletedBodiesOrRetrySendFailures() {
        for(var resource:ReportAnalyticsHttpClient.Resource.values()) {
            var oversized=" ".repeat(resource.maximumBytes())+"{}";
            assertThrows(IllegalStateException.class,()->client(200,oversized).get(SESSION,
                    resource==ReportAnalyticsHttpClient.Resource.DISCOVERY?null:STREAM,resource,"token"));
        }
        var count=new java.util.concurrent.atomic.AtomicInteger();
        var failed=new ReportAnalyticsHttpClient(BASE,100,JSON,(r,h)->{
            count.incrementAndGet(); return CompletableFuture.failedFuture(new IllegalStateException("sensitive"));
        });
        var error=assertThrows(IllegalStateException.class,()->failed.get(SESSION,null,ReportAnalyticsHttpClient.Resource.DISCOVERY,"token"));
        assertEquals(1,count.get()); assertNull(error.getCause()); assertEquals("Report analytics unavailable",error.getMessage());
    }

    static ReportAnalyticsHttpClient client(int status,String body) {
        return new ReportAnalyticsHttpClient(BASE,100,JSON,(r,h)->CompletableFuture.completedFuture(new Response(status,body.getBytes(StandardCharsets.UTF_8))));
    }
    static final class Subscription implements Flow.Subscription {
        boolean cancelled;
        public void request(long n) {}
        public void cancel() {cancelled=true;}
    }
    record Response(int statusCode,byte[] body) implements HttpResponse<byte[]> {
        public HttpRequest request() {return null;}
        public Optional<HttpResponse<byte[]>> previousResponse() {return Optional.empty();}
        public HttpHeaders headers() {return HttpHeaders.of(Map.of(),(a,b)->true);}
        public Optional<javax.net.ssl.SSLSession> sslSession() {return Optional.empty();}
        public URI uri() {return BASE;}
        public HttpClient.Version version() {return HttpClient.Version.HTTP_1_1;}
    }
}
