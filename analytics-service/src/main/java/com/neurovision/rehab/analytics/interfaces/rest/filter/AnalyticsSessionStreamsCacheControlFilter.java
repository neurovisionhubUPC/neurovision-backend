package com.neurovision.rehab.analytics.interfaces.rest.filter;

import io.quarkus.vertx.http.runtime.filters.Filters;
import io.quarkus.vertx.http.runtime.security.SecurityHandlerPriorities;
import io.vertx.core.http.HttpMethod;
import io.vertx.ext.web.RoutingContext;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;

/** Sets no-store before authentication, scoped only to the new discovery GET route. */
@ApplicationScoped
public class AnalyticsSessionStreamsCacheControlFilter {
    public void initialize(@Observes Filters filters) {
        filters.register(AnalyticsSessionStreamsCacheControlFilter::handle,SecurityHandlerPriorities.AUTHENTICATION+1);
    }
    static void handle(RoutingContext context) {
        if (isDiscoveryGet(context.request().method().name(), context.normalizedPath())) {
            context.response().putHeader("Cache-Control","no-store");
            context.addHeadersEndHandler(ignored -> context.response().putHeader("Cache-Control","no-store"));
        }
        context.next();
    }
    static boolean isDiscoveryGet(String method, String path) {
        return HttpMethod.GET.name().equals(method) && path != null
                && path.matches("/api/v1/analytics/sessions/[^/]+/streams/?");
    }
}
