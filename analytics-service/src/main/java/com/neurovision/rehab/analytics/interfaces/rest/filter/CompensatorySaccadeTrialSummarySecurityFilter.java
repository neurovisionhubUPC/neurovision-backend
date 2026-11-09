package com.neurovision.rehab.analytics.interfaces.rest.filter;

import io.quarkus.vertx.http.runtime.filters.Filters;
import io.quarkus.vertx.http.runtime.security.SecurityHandlerPriorities;
import io.vertx.ext.web.RoutingContext;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerResponseContext;
import jakarta.ws.rs.container.ContainerResponseFilter;
import jakarta.ws.rs.ext.Provider;

/** Header presentation only; native authentication and Clinical authorization remain unchanged. */
@Provider
@ApplicationScoped
public class CompensatorySaccadeTrialSummarySecurityFilter implements ContainerResponseFilter {
    static boolean isSummaryGet(String method,String path) {
        return "GET".equals(method) && path != null
                && path.matches("/api/v1/analytics/sessions/[^/]+/compensatory-saccade/summary/?");
    }
    public void initialize(@Observes Filters filters) {
        filters.register(CompensatorySaccadeTrialSummarySecurityFilter::handle,SecurityHandlerPriorities.AUTHENTICATION+1);
    }
    static void handle(RoutingContext context) {
        if (isSummaryGet(context.request().method().name(),context.normalizedPath())) {
            context.response().putHeader("Cache-Control","no-store");
            context.addHeadersEndHandler(ignored -> {
                context.response().putHeader("Cache-Control","no-store");
                if (context.response().getStatusCode() == 401
                        && !context.response().headers().contains("WWW-Authenticate"))
                    context.response().putHeader("WWW-Authenticate","Bearer");
            });
        }
        context.next();
    }
    @Override public void filter(ContainerRequestContext request,ContainerResponseContext response) {
        String path = request.getUriInfo().getPath();
        if (!isSummaryGet(request.getMethod(),path.startsWith("/") ? path : "/"+path)) return;
        response.getHeaders().putSingle("Cache-Control","no-store");
        if (response.getStatus() == 401 && !response.getHeaders().containsKey("WWW-Authenticate"))
            response.getHeaders().putSingle("WWW-Authenticate","Bearer");
    }
}
