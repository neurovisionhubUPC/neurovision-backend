package com.neurovision.rehab.analytics.interfaces.rest.filter;

import static org.junit.jupiter.api.Assertions.*;
import io.vertx.core.Handler;
import io.vertx.core.MultiMap;
import io.vertx.core.http.*;
import io.vertx.ext.web.RoutingContext;
import jakarta.ws.rs.container.*;
import jakarta.ws.rs.core.*;
import java.lang.reflect.Proxy;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class SystematicExplorationTrialSummarySecurityFilterTest {
    static final String PATH = "/api/v1/analytics/sessions/synthetic/systematic-exploration/summary";
    @SuppressWarnings("unchecked")
    static <T> T fake(Class<T> type,java.lang.reflect.InvocationHandler handler) {
        return (T)Proxy.newProxyInstance(type.getClassLoader(),new Class[]{type},handler);
    }
    @Test void routePredicateDoesNotCaptureReceptionStreamsOrOtherMethods() {
        assertTrue(SystematicExplorationTrialSummarySecurityFilter.isSummaryGet("GET",PATH));
        assertTrue(SystematicExplorationTrialSummarySecurityFilter.isSummaryGet("GET",PATH+"/"));
        assertFalse(SystematicExplorationTrialSummarySecurityFilter.isSummaryGet("POST",PATH));
        assertFalse(SystematicExplorationTrialSummarySecurityFilter.isSummaryGet("GET",null));
        assertFalse(SystematicExplorationTrialSummarySecurityFilter.isSummaryGet("GET",PATH.replace("systematic-exploration","visual-search")));
        assertFalse(SystematicExplorationTrialSummarySecurityFilter.isSummaryGet("GET",PATH+"/other"));
        assertFalse(SystematicExplorationTrialSummarySecurityFilter.isSummaryGet("GET",PATH.replace("/summary","/trial-results")));
        assertFalse(SystematicExplorationTrialSummarySecurityFilter.isSummaryGet("GET","/api/v1/analytics/sessions/synthetic/streams"));
    }
    @Test void jaxrsHeadersCoverSummaryResponsesWithoutRewritingNativeBodiesOrSiblings() {
        for (String path : new String[]{PATH,PATH.substring(1),"/other"}) {
            var headers = new MultivaluedHashMap<String,Object>();
            var uri = fake(UriInfo.class,(p,m,a) -> { assertEquals("getPath",m.getName()); return path; });
            var request = fake(ContainerRequestContext.class,(p,m,a) -> switch(m.getName()) {
                case "getMethod" -> "GET"; case "getUriInfo" -> uri; default -> throw new AssertionError(m.getName());
            });
            var response = fake(ContainerResponseContext.class,(p,m,a) -> switch(m.getName()) {
                case "getHeaders" -> headers; case "getStatus" -> 401; default -> throw new AssertionError("No body rewrite: "+m.getName());
            });
            new SystematicExplorationTrialSummarySecurityFilter().filter(request,response);
            if (path.equals("/other")) assertTrue(headers.isEmpty());
            else { assertEquals("no-store",headers.getFirst("Cache-Control")); assertEquals("Bearer",headers.getFirst("WWW-Authenticate")); }
        }
    }
    @Test void preAuthenticationHeadersAreRestoredAtEndWithoutChangingStatusOrBody() {
        for (String path : new String[]{PATH,"/other"}) {
            var headers = MultiMap.caseInsensitiveMultiMap();
            var end = new AtomicReference<Handler<Void>>(); int[] next = {0};
            var request = fake(HttpServerRequest.class,(p,m,a) -> { assertEquals("method",m.getName()); return HttpMethod.GET; });
            var response = fake(HttpServerResponse.class,(p,m,a) -> switch(m.getName()) {
                case "putHeader" -> { headers.set((String)a[0],(String)a[1]); yield p; }
                case "getStatusCode" -> 401; case "headers" -> headers;
                default -> throw new AssertionError("No response completion or body rewrite: "+m.getName());
            });
            var context = fake(RoutingContext.class,(p,m,a) -> switch(m.getName()) {
                case "request" -> request; case "normalizedPath" -> path; case "response" -> response;
                case "addHeadersEndHandler" -> { @SuppressWarnings("unchecked") var handler = (Handler<Void>)a[0]; end.set(handler); yield 0; }
                case "next" -> { next[0]++; yield null; } default -> throw new AssertionError(m.getName());
            });
            SystematicExplorationTrialSummarySecurityFilter.handle(context); assertEquals(1,next[0]);
            if (path.equals("/other")) { assertTrue(headers.isEmpty()); assertNull(end.get()); }
            else { assertEquals("no-store",headers.get("Cache-Control")); headers.clear(); end.get().handle(null);
                assertEquals("no-store",headers.get("Cache-Control")); assertEquals("Bearer",headers.get("WWW-Authenticate")); }
        }
    }
}
