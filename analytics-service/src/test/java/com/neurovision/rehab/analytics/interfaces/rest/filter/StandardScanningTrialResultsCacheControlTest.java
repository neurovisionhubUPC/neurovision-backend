package com.neurovision.rehab.analytics.interfaces.rest.filter;

import static org.junit.jupiter.api.Assertions.*;
import io.quarkus.vertx.http.runtime.filters.Filters;
import io.quarkus.vertx.http.runtime.security.SecurityHandlerPriorities;
import io.vertx.core.Handler;
import io.vertx.core.http.*;
import io.vertx.ext.web.RoutingContext;
import java.lang.reflect.Proxy;
import java.util.*;
import org.junit.jupiter.api.Test;

class StandardScanningTrialResultsCacheControlTest {
    @SuppressWarnings("unchecked") static <T>T proxy(Class<T> type,java.lang.reflect.InvocationHandler handler) {
        return (T)Proxy.newProxyInstance(type.getClassLoader(),new Class[]{type},handler);
    }
    @Test void runsBeforeAuthenticationAndEnforcesHeaderForDownstreamRejectionsOnlyOnNewPost() {
        var filters=new Filters();new StandardScanningTrialResultsSecurityFilter().initialize(filters);
        var filter=filters.getFilters().get(0);
        assertTrue(filter.getPriority()>SecurityHandlerPriorities.AUTHENTICATION);
        for(var path:List.of("/api/v1/analytics/sessions/uuid/standard-scanning/trial-results","/api/v1/analytics/sessions/uuid/standard-scanning/trial-results/",
                "/api/v1/analytics/sessions/uuid/standard-scanning/trial-results/id/summary","/q/health/ready",
                "/api/v1/analytics/sessions/synthetic/visual-search/trial-results",
                "/api/v1/analytics/sessions//standard-scanning/trial-results","/api/v1/analytics/sessions/a/b/standard-scanning/trial-results")) {
            for(var method:List.of(HttpMethod.POST,HttpMethod.GET,HttpMethod.HEAD)) {
                var headers=new HashMap<String,String>();var end=new ArrayList<Handler<Void>>();var next=new int[]{0};
                var response=proxy(HttpServerResponse.class,(p,m,a)->{
                    if(m.getName().equals("putHeader")){headers.put(a[0].toString(),a[1].toString());return p;}
                    throw new AssertionError(m.getName());
                });
                var request=proxy(HttpServerRequest.class,(p,m,a)->{assertEquals("method",m.getName());return method;});
                var context=proxy(RoutingContext.class,(p,m,a)->switch(m.getName()) {
                    case "request"->request;case "response"->response;case "normalizedPath"->path;
                    case "next"->{next[0]++;yield null;}
                    case "addHeadersEndHandler"->{end.add((Handler<Void>)a[0]);yield 0;}
                    default->throw new AssertionError(m.getName());
                });
                filter.getHandler().handle(context);assertEquals(1,next[0]);
                boolean scoped=method==HttpMethod.POST && path.matches("/api/v1/analytics/sessions/[^/]+/standard-scanning/trial-results/?");
                assertEquals(scoped,headers.containsKey("Cache-Control"));
                if(scoped){headers.put("Cache-Control","public");end.get(0).handle(null);assertEquals("no-store",headers.get("Cache-Control"));}
                else assertTrue(end.isEmpty());
            }
        }
    }
}
