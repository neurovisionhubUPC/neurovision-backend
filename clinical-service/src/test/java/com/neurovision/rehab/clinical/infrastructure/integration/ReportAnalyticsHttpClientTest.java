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

    @Test void visualSearchUsesDedicatedFixedQueryOriginalTokenAndSmallBound() {
        var client = new ReportAnalyticsHttpClient(BASE,250,JSON);
        for (var origin : com.neurovision.rehab.clinical.application.ports.out.integration.SessionReportVisualSearchPort.Origin.values()) {
            var request=client.visualSearchRequest(SESSION,origin,"original-token");
            assertEquals(BASE+"/api/v1/analytics/sessions/"+SESSION+"/visual-search/summary?dataOrigin="+origin.name(),request.uri().toString());
            assertEquals("Bearer original-token",request.headers().firstValue("Authorization").orElseThrow());
            assertEquals("GET",request.method()); assertEquals(Duration.ofMillis(250),request.timeout().orElseThrow());
            assertTrue(request.bodyPublisher().isEmpty());
        }
        assertThrows(IllegalStateException.class,()->client.visualSearchRequest(SESSION,null,"token"));
        assertThrows(IllegalStateException.class,()->client.visualSearchRequest(null,com.neurovision.rehab.clinical.application.ports.out.integration.SessionReportVisualSearchPort.Origin.SYNTHETIC,"token"));
        assertThrows(IllegalStateException.class,()->client.visualSearchRequest(SESSION,com.neurovision.rehab.clinical.application.ports.out.integration.SessionReportVisualSearchPort.Origin.SYNTHETIC,"bad token"));
    }
    @Test void visualSearchStrictWireFailuresBoundTimeoutAndInterrupt() throws Exception {
        var origin=com.neurovision.rehab.clinical.application.ports.out.integration.SessionReportVisualSearchPort.Origin.SYNTHETIC;
        for (int status:List.of(201,204,301,302,307,308,400,401,403,404,409,429,500,503))
            assertThrows(IllegalStateException.class,()->client(status,"private").getVisualSearch(SESSION,origin,"token"));
        for (String body:List.of("{\"x\":1,\"x\":2}","{} {}","[]","null","{broken"," ".repeat(2048)+"{}"))
            assertThrows(IllegalStateException.class,()->client(200,body).getVisualSearch(SESSION,origin,"token"));
        assertTrue(client(200,"{}").getVisualSearch(SESSION,origin,"token").isObject());
        var bounded = new ReportAnalyticsHttpClient(BASE,100,JSON,(r,h)->{
            var body=h.apply(null); var subscription=new Subscription(); body.onSubscribe(subscription);
            body.onNext(List.of(ByteBuffer.wrap(new byte[2049]))); assertTrue(subscription.cancelled);
            assertThrows(ExecutionException.class,()->body.getBody().toCompletableFuture().get());
            return CompletableFuture.failedFuture(new IllegalStateException());
        });
        assertThrows(IllegalStateException.class,()->bounded.getVisualSearch(SESSION,origin,"token"));
        var pending=new CompletableFuture<HttpResponse<byte[]>>();
        var timeout=new ReportAnalyticsHttpClient(BASE,100,JSON,(r,h)->pending);
        assertThrows(IllegalStateException.class,()->timeout.getVisualSearch(SESSION,origin,"token")); assertTrue(pending.isCancelled());
        var interrupted=new CompletableFuture<HttpResponse<byte[]>>(); var client=new ReportAnalyticsHttpClient(BASE,100,JSON,(r,h)->interrupted);
        try {
            Thread.currentThread().interrupt(); assertThrows(IllegalStateException.class,()->client.getVisualSearch(SESSION,origin,"token"));
            assertTrue(Thread.currentThread().isInterrupted()); assertTrue(interrupted.isCancelled());
        } finally { Thread.interrupted(); }
    }

    @Test void standardScanningUsesDedicatedFixedQueryOriginalTokenAndSmallBound() {
        var client = new ReportAnalyticsHttpClient(BASE,250,JSON);
        for (var origin : com.neurovision.rehab.clinical.application.ports.out.integration.SessionReportStandardScanningPort.Origin.values()) {
            var request=client.standardScanningRequest(SESSION,origin,"original-token");
            assertEquals(BASE+"/api/v1/analytics/sessions/"+SESSION+"/standard-scanning/summary?dataOrigin="+origin.name(),request.uri().toString());
            assertEquals("Bearer original-token",request.headers().firstValue("Authorization").orElseThrow());
            assertEquals("GET",request.method()); assertEquals(Duration.ofMillis(250),request.timeout().orElseThrow());
            assertTrue(request.bodyPublisher().isEmpty());
        }
        assertThrows(IllegalStateException.class,()->client.standardScanningRequest(SESSION,null,"token"));
        assertThrows(IllegalStateException.class,()->client.standardScanningRequest(null,com.neurovision.rehab.clinical.application.ports.out.integration.SessionReportStandardScanningPort.Origin.SYNTHETIC,"token"));
        assertThrows(IllegalStateException.class,()->client.standardScanningRequest(SESSION,com.neurovision.rehab.clinical.application.ports.out.integration.SessionReportStandardScanningPort.Origin.SYNTHETIC,"bad token"));
    }
    @Test void standardScanningStrictWireFailuresBoundTimeoutAndInterrupt() throws Exception {
        var origin=com.neurovision.rehab.clinical.application.ports.out.integration.SessionReportStandardScanningPort.Origin.SYNTHETIC;
        for (int status:List.of(201,204,301,302,307,308,400,401,403,404,409,429,500,503))
            assertThrows(IllegalStateException.class,()->client(status,"private").getStandardScanning(SESSION,origin,"token"));
        for (String body:List.of("{\"x\":1,\"x\":2}","{} {}","[]","null","{broken"," ".repeat(2048)+"{}"))
            assertThrows(IllegalStateException.class,()->client(200,body).getStandardScanning(SESSION,origin,"token"));
        assertTrue(client(200,"{}").getStandardScanning(SESSION,origin,"token").isObject());
        var bounded = new ReportAnalyticsHttpClient(BASE,100,JSON,(r,h)->{
            var body=h.apply(null); var subscription=new Subscription(); body.onSubscribe(subscription);
            body.onNext(List.of(ByteBuffer.wrap(new byte[2049]))); assertTrue(subscription.cancelled);
            assertThrows(ExecutionException.class,()->body.getBody().toCompletableFuture().get());
            return CompletableFuture.failedFuture(new IllegalStateException());
        });
        assertThrows(IllegalStateException.class,()->bounded.getStandardScanning(SESSION,origin,"token"));
        var pending=new CompletableFuture<HttpResponse<byte[]>>();
        var timeout=new ReportAnalyticsHttpClient(BASE,100,JSON,(r,h)->pending);
        assertThrows(IllegalStateException.class,()->timeout.getStandardScanning(SESSION,origin,"token")); assertTrue(pending.isCancelled());
        var interrupted=new CompletableFuture<HttpResponse<byte[]>>(); var client=new ReportAnalyticsHttpClient(BASE,100,JSON,(r,h)->interrupted);
        try {
            Thread.currentThread().interrupt(); assertThrows(IllegalStateException.class,()->client.getStandardScanning(SESSION,origin,"token"));
            assertTrue(Thread.currentThread().isInterrupted()); assertTrue(interrupted.isCancelled());
        } finally { Thread.interrupted(); }
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

    @Test void sustainedFixationUsesDedicatedFixedQueryOriginalTokenAndSmallBound() {
        var client = new ReportAnalyticsHttpClient(BASE,250,JSON);
        for (var origin : com.neurovision.rehab.clinical.application.ports.out.integration.SessionReportSustainedFixationPort.Origin.values()) {
            var request=client.sustainedFixationRequest(SESSION,origin,"original-token");
            assertEquals(BASE+"/api/v1/analytics/sessions/"+SESSION+"/sustained-fixation/summary?dataOrigin="+origin.name(),request.uri().toString());
            assertEquals("Bearer original-token",request.headers().firstValue("Authorization").orElseThrow());
            assertEquals("GET",request.method()); assertEquals(Duration.ofMillis(250),request.timeout().orElseThrow());
            assertTrue(request.bodyPublisher().isEmpty());
        }
        assertThrows(IllegalStateException.class,()->client.sustainedFixationRequest(SESSION,null,"token"));
        assertThrows(IllegalStateException.class,()->client.sustainedFixationRequest(null,com.neurovision.rehab.clinical.application.ports.out.integration.SessionReportSustainedFixationPort.Origin.SYNTHETIC,"token"));
        assertThrows(IllegalStateException.class,()->client.sustainedFixationRequest(SESSION,com.neurovision.rehab.clinical.application.ports.out.integration.SessionReportSustainedFixationPort.Origin.SYNTHETIC,"bad token"));
    }
    @Test void sustainedFixationStrictWireFailuresBoundTimeoutAndInterrupt() throws Exception {
        var origin=com.neurovision.rehab.clinical.application.ports.out.integration.SessionReportSustainedFixationPort.Origin.SYNTHETIC;
        for (int status:List.of(201,204,301,302,307,308,400,401,403,404,409,429,500,503))
            assertThrows(IllegalStateException.class,()->client(status,"private").getSustainedFixation(SESSION,origin,"token"));
        for (String body:List.of("{\"x\":1,\"x\":2}","{} {}","[]","null","{broken"," ".repeat(2048)+"{}"))
            assertThrows(IllegalStateException.class,()->client(200,body).getSustainedFixation(SESSION,origin,"token"));
        assertTrue(client(200,"{}").getSustainedFixation(SESSION,origin,"token").isObject());
        var bounded = new ReportAnalyticsHttpClient(BASE,100,JSON,(r,h)->{
            var body=h.apply(null); var subscription=new Subscription(); body.onSubscribe(subscription);
            body.onNext(List.of(ByteBuffer.wrap(new byte[2049]))); assertTrue(subscription.cancelled);
            assertThrows(ExecutionException.class,()->body.getBody().toCompletableFuture().get());
            return CompletableFuture.failedFuture(new IllegalStateException());
        });
        assertThrows(IllegalStateException.class,()->bounded.getSustainedFixation(SESSION,origin,"token"));
        var pending=new CompletableFuture<HttpResponse<byte[]>>();
        var timeout=new ReportAnalyticsHttpClient(BASE,100,JSON,(r,h)->pending);
        assertThrows(IllegalStateException.class,()->timeout.getSustainedFixation(SESSION,origin,"token")); assertTrue(pending.isCancelled());
        var interrupted=new CompletableFuture<HttpResponse<byte[]>>(); var client=new ReportAnalyticsHttpClient(BASE,100,JSON,(r,h)->interrupted);
        try {
            Thread.currentThread().interrupt(); assertThrows(IllegalStateException.class,()->client.getSustainedFixation(SESSION,origin,"token"));
            assertTrue(Thread.currentThread().isInterrupted()); assertTrue(interrupted.isCancelled());
        } finally { Thread.interrupted(); }
    }

    @Test void compensatorySaccadeUsesDedicatedFixedQueryOriginalTokenAndSmallBound() {
        var client = new ReportAnalyticsHttpClient(BASE,250,JSON);
        for (var origin : com.neurovision.rehab.clinical.application.ports.out.integration.SessionReportCompensatorySaccadePort.Origin.values()) {
            var request=client.compensatorySaccadeRequest(SESSION,origin,"original-token");
            assertEquals(BASE+"/api/v1/analytics/sessions/"+SESSION+"/compensatory-saccade/summary?dataOrigin="+origin.name(),request.uri().toString());
            assertEquals("Bearer original-token",request.headers().firstValue("Authorization").orElseThrow());
            assertEquals("GET",request.method()); assertEquals(Duration.ofMillis(250),request.timeout().orElseThrow());
            assertTrue(request.bodyPublisher().isEmpty());
        }
        assertThrows(IllegalStateException.class,()->client.compensatorySaccadeRequest(SESSION,null,"token"));
        assertThrows(IllegalStateException.class,()->client.compensatorySaccadeRequest(null,com.neurovision.rehab.clinical.application.ports.out.integration.SessionReportCompensatorySaccadePort.Origin.SYNTHETIC,"token"));
        assertThrows(IllegalStateException.class,()->client.compensatorySaccadeRequest(SESSION,com.neurovision.rehab.clinical.application.ports.out.integration.SessionReportCompensatorySaccadePort.Origin.SYNTHETIC,"bad token"));
    }
    @Test void compensatorySaccadeStrictWireFailuresBoundTimeoutAndInterrupt() throws Exception {
        var origin=com.neurovision.rehab.clinical.application.ports.out.integration.SessionReportCompensatorySaccadePort.Origin.SYNTHETIC;
        for (int status:List.of(201,204,301,302,307,308,400,401,403,404,409,429,500,503))
            assertThrows(IllegalStateException.class,()->client(status,"private").getCompensatorySaccade(SESSION,origin,"token"));
        for (String body:List.of("{\"x\":1,\"x\":2}","{} {}","[]","null","{broken"," ".repeat(2048)+"{}"))
            assertThrows(IllegalStateException.class,()->client(200,body).getCompensatorySaccade(SESSION,origin,"token"));
        assertTrue(client(200,"{}").getCompensatorySaccade(SESSION,origin,"token").isObject());
        var bounded = new ReportAnalyticsHttpClient(BASE,100,JSON,(r,h)->{
            var body=h.apply(null); var subscription=new Subscription(); body.onSubscribe(subscription);
            body.onNext(List.of(ByteBuffer.wrap(new byte[2049]))); assertTrue(subscription.cancelled);
            assertThrows(ExecutionException.class,()->body.getBody().toCompletableFuture().get());
            return CompletableFuture.failedFuture(new IllegalStateException());
        });
        assertThrows(IllegalStateException.class,()->bounded.getCompensatorySaccade(SESSION,origin,"token"));
        var pending=new CompletableFuture<HttpResponse<byte[]>>();
        var timeout=new ReportAnalyticsHttpClient(BASE,100,JSON,(r,h)->pending);
        assertThrows(IllegalStateException.class,()->timeout.getCompensatorySaccade(SESSION,origin,"token")); assertTrue(pending.isCancelled());
        var interrupted=new CompletableFuture<HttpResponse<byte[]>>(); var client=new ReportAnalyticsHttpClient(BASE,100,JSON,(r,h)->interrupted);
        try {
            Thread.currentThread().interrupt(); assertThrows(IllegalStateException.class,()->client.getCompensatorySaccade(SESSION,origin,"token"));
            assertTrue(Thread.currentThread().isInterrupted()); assertTrue(interrupted.isCancelled());
        } finally { Thread.interrupted(); }
    }

    @Test void systematicExplorationUsesDedicatedFixedQueryOriginalTokenAndExistingSummaryBound() {
        var client = new ReportAnalyticsHttpClient(BASE,250,JSON);
        for (var origin : com.neurovision.rehab.clinical.application.ports.out.integration.SessionReportSystematicExplorationPort.Origin.values()) {
            var request=client.systematicExplorationRequest(SESSION,origin,"original-token");
            assertEquals(BASE+"/api/v1/analytics/sessions/"+SESSION+"/systematic-exploration/summary?dataOrigin="+origin.name(),request.uri().toString());
            assertEquals("Bearer original-token",request.headers().firstValue("Authorization").orElseThrow());
            assertEquals("GET",request.method()); assertEquals(Duration.ofMillis(250),request.timeout().orElseThrow());
            assertTrue(request.bodyPublisher().isEmpty());
        }
        assertThrows(IllegalStateException.class,()->client.systematicExplorationRequest(SESSION,null,"token"));
        assertThrows(IllegalStateException.class,()->client.systematicExplorationRequest(null,com.neurovision.rehab.clinical.application.ports.out.integration.SessionReportSystematicExplorationPort.Origin.SYNTHETIC,"token"));
        assertThrows(IllegalStateException.class,()->client.systematicExplorationRequest(SESSION,com.neurovision.rehab.clinical.application.ports.out.integration.SessionReportSystematicExplorationPort.Origin.SYNTHETIC,"bad token"));
    }
    @Test void systematicExplorationStrictWireFailuresBoundTimeoutAndInterrupt() throws Exception {
        var origin=com.neurovision.rehab.clinical.application.ports.out.integration.SessionReportSystematicExplorationPort.Origin.SYNTHETIC;
        for (int status:List.of(201,204,301,302,307,308,400,401,403,404,409,429,500,503))
            assertThrows(IllegalStateException.class,()->client(status,"private").getSystematicExploration(SESSION,origin,"token"));
        for (String body:List.of("{\"x\":1,\"x\":2}","{} {}","[]","null","{broken"," ".repeat(262144)+"{}"))
            assertThrows(IllegalStateException.class,()->client(200,body).getSystematicExploration(SESSION,origin,"token"));
        assertTrue(client(200,"{}").getSystematicExploration(SESSION,origin,"token").isObject());
        var bounded = new ReportAnalyticsHttpClient(BASE,100,JSON,(r,h)->{
            var body=h.apply(null); var subscription=new Subscription(); body.onSubscribe(subscription);
            body.onNext(List.of(ByteBuffer.wrap(new byte[262145]))); assertTrue(subscription.cancelled);
            assertThrows(ExecutionException.class,()->body.getBody().toCompletableFuture().get());
            return CompletableFuture.failedFuture(new IllegalStateException());
        });
        assertThrows(IllegalStateException.class,()->bounded.getSystematicExploration(SESSION,origin,"token"));
        var pending=new CompletableFuture<HttpResponse<byte[]>>();
        var timeout=new ReportAnalyticsHttpClient(BASE,100,JSON,(r,h)->pending);
        assertThrows(IllegalStateException.class,()->timeout.getSystematicExploration(SESSION,origin,"token")); assertTrue(pending.isCancelled());
        var interrupted=new CompletableFuture<HttpResponse<byte[]>>(); var client=new ReportAnalyticsHttpClient(BASE,100,JSON,(r,h)->interrupted);
        try {
            Thread.currentThread().interrupt(); assertThrows(IllegalStateException.class,()->client.getSystematicExploration(SESSION,origin,"token"));
            assertTrue(Thread.currentThread().isInterrupted()); assertTrue(interrupted.isCancelled());
        } finally { Thread.interrupted(); }
    }

}
