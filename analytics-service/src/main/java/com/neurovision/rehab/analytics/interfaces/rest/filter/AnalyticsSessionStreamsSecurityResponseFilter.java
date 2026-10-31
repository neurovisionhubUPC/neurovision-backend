package com.neurovision.rehab.analytics.interfaces.rest.filter;

import io.quarkus.security.AuthenticationCompletionException;
import io.quarkus.security.AuthenticationFailedException;
import io.quarkus.vertx.http.runtime.security.SecurityHandlerPriorities;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.RoutingContext;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerResponseContext;
import jakarta.ws.rs.container.ContainerResponseFilter;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.ext.Provider;
import java.util.Map;

/** Presents discovery security rejections without replacing authentication or authorization. */
@Provider
@ApplicationScoped
public class AnalyticsSessionStreamsSecurityResponseFilter implements ContainerResponseFilter {
    @Override
    public void filter(ContainerRequestContext request, ContainerResponseContext response) {
        String path = request.getUriInfo().getPath();
        if (!AnalyticsSessionStreamsCacheControlFilter.isDiscoveryGet(
                request.getMethod(), path.startsWith("/") ? path : "/" + path)) return;
        String code = switch (response.getStatus()) {
            case 401 -> "UNAUTHENTICATED";
            case 403 -> "STREAM_DISCOVERY_FORBIDDEN";
            default -> null;
        };
        if (code != null) {
            // setEntity preserves the status and existing challenge/other response headers.
            response.setEntity(Map.of("code", code), null, MediaType.APPLICATION_JSON_TYPE);
            response.getHeaders().putSingle("Cache-Control", "no-store");
        }
    }

    public void initialize(@Observes Router router) {
        // Quarkus 3.38.1 sends the challenge before failing proactive authentication.
        // Run after its authentication route and before the terminal QuarkusErrorHandler.
        router.route().order(1 - SecurityHandlerPriorities.AUTHENTICATION)
                .failureHandler(AnalyticsSessionStreamsSecurityResponseFilter::handleAuthenticationFailure);
    }

    static void handleAuthenticationFailure(RoutingContext context) {
        var response = context.response();
        if (!AnalyticsSessionStreamsCacheControlFilter.isDiscoveryGet(
                context.request().method().name(), context.normalizedPath())
                || response.ended() || response.headWritten() || response.getStatusCode() != 401
                || !(context.failure() instanceof AuthenticationFailedException
                        || context.failure() instanceof AuthenticationCompletionException)) {
            context.next();
            return;
        }
        // Never replace challenge processing, reinterpret dependency failures, or expose failure messages.
        response.putHeader("Content-Type", MediaType.APPLICATION_JSON);
        response.putHeader("Cache-Control", "no-store");
        response.end("{\"code\":\"UNAUTHENTICATED\"}");
    }
}
