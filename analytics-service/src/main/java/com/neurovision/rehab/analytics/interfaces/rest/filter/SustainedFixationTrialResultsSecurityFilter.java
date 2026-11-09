package com.neurovision.rehab.analytics.interfaces.rest.filter;

import io.quarkus.security.AuthenticationCompletionException;
import io.quarkus.security.AuthenticationFailedException;
import io.quarkus.vertx.http.runtime.filters.Filters;
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

/** Route-scoped presentation only; authentication and authorization remain enforced by Quarkus/Clinical. */
@Provider
@ApplicationScoped
public class SustainedFixationTrialResultsSecurityFilter implements ContainerResponseFilter {
    static boolean isReceptionPost(String method, String path) {
        return "POST".equals(method) && path != null
                && path.matches("/api/v1/analytics/sessions/[^/]+/sustained-fixation/trial-results/?");
    }

    public void initialize(@Observes Filters filters) {
        filters.register(SustainedFixationTrialResultsSecurityFilter::handle, SecurityHandlerPriorities.AUTHENTICATION + 1);
    }
    static void handle(RoutingContext context) {
        if (isReceptionPost(context.request().method().name(), context.normalizedPath())) {
            context.response().putHeader("Cache-Control", "no-store");
            context.addHeadersEndHandler(ignored -> context.response().putHeader("Cache-Control", "no-store"));
        }
        context.next();
    }

    @Override public void filter(ContainerRequestContext request, ContainerResponseContext response) {
        String path = request.getUriInfo().getPath();
        if (!isReceptionPost(request.getMethod(), path.startsWith("/") ? path : "/" + path)) return;
        response.getHeaders().putSingle("Cache-Control", "no-store");
        String status = switch (response.getStatus()) {
            case 400 -> "INVALID_RESULT";
            case 401 -> "INVALID_TOKEN";
            case 403 -> "DENIED";
            case 413 -> "PAYLOAD_TOO_LARGE";
            case 415 -> "UNSUPPORTED_MEDIA_TYPE";
            default -> null;
        };
        if (status != null) response.setEntity(Map.of("status", status), null, MediaType.APPLICATION_JSON_TYPE);
        if (response.getStatus() == 401 && !response.getHeaders().containsKey("WWW-Authenticate"))
            response.getHeaders().putSingle("WWW-Authenticate", "Bearer");
    }

    public void initialize(@Observes Router router) {
        router.route().order(1 - SecurityHandlerPriorities.AUTHENTICATION)
                .failureHandler(SustainedFixationTrialResultsSecurityFilter::handleAuthenticationFailure);
    }
    static void handleAuthenticationFailure(RoutingContext context) {
        var response = context.response();
        if (!isReceptionPost(context.request().method().name(), context.normalizedPath())
                || response.ended() || response.headWritten() || response.getStatusCode() != 401
                || !(context.failure() instanceof AuthenticationFailedException
                        || context.failure() instanceof AuthenticationCompletionException)) {
            context.next(); return;
        }
        response.putHeader("Content-Type", MediaType.APPLICATION_JSON);
        response.putHeader("Cache-Control", "no-store");
        if (!response.headers().contains("WWW-Authenticate")) response.putHeader("WWW-Authenticate", "Bearer");
        response.end("{\"status\":\"INVALID_TOKEN\"}");
    }
}
