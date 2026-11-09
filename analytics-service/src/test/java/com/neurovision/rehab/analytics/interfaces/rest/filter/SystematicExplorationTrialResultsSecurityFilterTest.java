package com.neurovision.rehab.analytics.interfaces.rest.filter;

import static org.junit.jupiter.api.Assertions.*;

import io.quarkus.security.AuthenticationCompletionException;
import io.quarkus.security.AuthenticationFailedException;
import io.quarkus.vertx.http.runtime.security.SecurityHandlerPriorities;
import io.vertx.core.Handler;
import io.vertx.core.http.*;
import io.vertx.ext.web.*;
import jakarta.ws.rs.container.*;
import jakarta.ws.rs.core.*;
import java.lang.reflect.Proxy;
import java.util.*;
import org.junit.jupiter.api.Test;

class SystematicExplorationTrialResultsSecurityFilterTest {
    private static final String PATH = "/api/v1/analytics/sessions/synthetic/systematic-exploration/trial-results";
    @Test void malformedLargeAndWrongMediaResponsesHaveOnlyFixedStatuses() throws Exception {
        var expected = Map.of(400,"INVALID_RESULT",413,"PAYLOAD_TOO_LARGE",415,"UNSUPPORTED_MEDIA_TYPE");
        for (var entry : expected.entrySet()) {
            var state = new RestState("POST", PATH, entry.getKey());
            new SystematicExplorationTrialResultsSecurityFilter().filter(state.request(), state.response());
            assertEquals(Map.of("status",entry.getValue()),state.entity);
            assertEquals(entry.getKey().intValue(),state.status);
            assertEquals("no-store",state.headers.getFirst("Cache-Control"));
        }
        var missing = new RestState("POST",PATH,401); missing.headers.remove("WWW-Authenticate");
        new SystematicExplorationTrialResultsSecurityFilter().filter(missing.request(),missing.response());
        assertEquals("Bearer",missing.headers.getFirst("WWW-Authenticate"));
    }
    @Test void restRejectionsKeepStatusAndHeadersWithOnlyTheFixedJsonBody() throws Exception {
        for (int status : List.of(401, 403)) {
            var state = new RestState("POST", PATH, status);
            new SystematicExplorationTrialResultsSecurityFilter().filter(state.request(), state.response());
            assertEquals(status, state.status);
            assertEquals(Map.of("status", status == 401 ? "INVALID_TOKEN" : "DENIED"), state.entity);
            assertEquals(MediaType.APPLICATION_JSON_TYPE, state.mediaType);
            assertEquals("no-store", state.headers.getFirst("Cache-Control"));
            assertEquals(List.of("Bearer", "additional-challenge"), state.headers.get("WWW-Authenticate"));
            assertEquals("preserved", state.headers.getFirst("X-Existing"));
        }
    }

    @Test void restScopePreservesSuccessDependenciesOtherRoutesAndMethods() throws Exception {
        for (var path : List.of(PATH, PATH + "/", PATH + "/id/summary", "/q/health/ready",
                "/api/v1/analytics/sessions/synthetic/visual-search/trial-results",
                "/api/v1/analytics/sessions/synthetic/standard-scanning/trial-results",
                "/api/v1/analytics/sessions/synthetic/sustained-fixation/trial-results",
                "/api/v1/analytics/sessions/synthetic/compensatory-saccade/trial-results",
                "/api/v1/analytics/sessions//systematic-exploration/trial-results", "/api/v1/analytics/sessions/a/b/systematic-exploration/trial-results")) {
            for (var method : List.of("POST", "GET", "HEAD")) {
                for (int status : List.of(200, 400, 401, 403, 503)) {
                    var state = new RestState(method, path, status);
                    var original = state.entity;
                    new SystematicExplorationTrialResultsSecurityFilter().filter(state.request(), state.response());
                    boolean scoped = method.equals("POST") && (path.equals(PATH) || path.equals(PATH + "/"))
                            && List.of(400,401,403,413,415).contains(status);
                    assertEquals(scoped, state.entity != original, method + " " + path + " " + status);
                    assertEquals(status, state.status);
                    if (!SystematicExplorationTrialResultsSecurityFilter.isReceptionPost(method,path)) assertEquals("public", state.headers.getFirst("Cache-Control"));
                }
            }
        }
    }

    @Test void registeredFailureHandlerRunsAfterAuthenticationAndEndsOnlyTypedRejections() throws Exception {
        var registration = new Registration();
        new SystematicExplorationTrialResultsSecurityFilter().initialize(registration.router());
        assertEquals(1 - SecurityHandlerPriorities.AUTHENTICATION, registration.order);
        assertNotNull(registration.handler);
        for (var failure : List.of(new AuthenticationFailedException("private token detail"),
                new AuthenticationCompletionException("private token detail"))) {
            var state = new FailureState(HttpMethod.POST, PATH, 401, failure);
            registration.handler.handle(state.context());
            assertEquals(0, state.next);
            assertEquals(1, state.ends);
            assertEquals(401, state.status);
            assertEquals("{\"status\":\"INVALID_TOKEN\"}", state.body);
            assertEquals("application/json", state.headers.get("Content-Type"));
            assertEquals("no-store", state.headers.get("Cache-Control"));
            assertEquals("Bearer existing-challenge", state.headers.get("WWW-Authenticate"));
            assertEquals("preserved", state.headers.get("X-Existing"));
        }
    }

    @Test void failureScopeDoesNotConvertOtherFailuresOrStatuses() throws Exception {
        var registration = new Registration();
        new SystematicExplorationTrialResultsSecurityFilter().initialize(registration.router());
        for (var path : List.of(PATH, PATH + "/", PATH + "/id/summary", "/q/health/ready",
                "/api/v1/analytics/sessions/synthetic/visual-search/trial-results",
                "/api/v1/analytics/sessions/synthetic/standard-scanning/trial-results",
                "/api/v1/analytics/sessions/synthetic/sustained-fixation/trial-results",
                "/api/v1/analytics/sessions/synthetic/compensatory-saccade/trial-results",
                "/api/v1/analytics/sessions//systematic-exploration/trial-results", "/api/v1/analytics/sessions/a/b/systematic-exploration/trial-results")) {
            for (var method : List.of(HttpMethod.POST, HttpMethod.GET, HttpMethod.HEAD)) {
                for (int status : List.of(200, 401, 403, 503)) {
                    for (var failure : List.of(new AuthenticationFailedException(), new IllegalStateException())) {
                        var state = new FailureState(method, path, status, failure);
                        registration.handler.handle(state.context());
                        boolean scoped = method == HttpMethod.POST && (path.equals(PATH) || path.equals(PATH + "/"))
                                && status == 401 && failure instanceof AuthenticationFailedException;
                        assertEquals(scoped ? 0 : 1, state.next);
                        assertEquals(scoped ? 1 : 0, state.ends);
                        assertEquals(status, state.status);
                        if (!scoped) {
                            assertNull(state.body);
                            assertEquals("public", state.headers.get("Cache-Control"));
                        }
                    }
                }
            }
        }
    }

    @Test void alreadyStartedOrEndedResponsesPassThroughWithoutWriting() throws Exception {
        var registration = new Registration();
        new SystematicExplorationTrialResultsSecurityFilter().initialize(registration.router());
        for (boolean ended : List.of(false, true)) {
            var state = new FailureState(HttpMethod.POST, PATH, 401, new AuthenticationFailedException());
            state.ended = ended;
            state.headWritten = !ended;
            registration.handler.handle(state.context());
            assertEquals(1, state.next);
            assertEquals(0, state.ends);
            assertEquals("public", state.headers.get("Cache-Control"));
        }
    }

    @SuppressWarnings("unchecked")
    private static <T> T proxy(Class<T> type, java.lang.reflect.InvocationHandler handler) {
        return (T) Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, handler);
    }

    private static final class RestState {
        final String method, path;
        int status;
        Object entity = Map.of("private", "must not escape");
        MediaType mediaType = MediaType.TEXT_PLAIN_TYPE;
        final MultivaluedMap<String, Object> headers = new MultivaluedHashMap<>();
        RestState(String method, String path, int status) {
            this.method = method; this.path = path; this.status = status;
            headers.putSingle("Cache-Control", "public");
            headers.put("WWW-Authenticate", new ArrayList<>(List.of("Bearer", "additional-challenge")));
            headers.putSingle("X-Existing", "preserved");
        }
        ContainerRequestContext request() {
            var uri = proxy(UriInfo.class, (p, m, a) -> {
                assertEquals("getPath", m.getName()); return path;
            });
            return proxy(ContainerRequestContext.class, (p, m, a) -> switch (m.getName()) {
                case "getMethod" -> method; case "getUriInfo" -> uri;
                default -> throw new AssertionError(m.getName());
            });
        }
        ContainerResponseContext response() {
            return proxy(ContainerResponseContext.class, (p, m, a) -> switch (m.getName()) {
                case "getStatus" -> status; case "getHeaders" -> headers;
                case "setEntity" -> { entity = a[0]; mediaType = (MediaType) a[2]; yield null; }
                default -> throw new AssertionError(m.getName());
            });
        }
    }

    private static final class Registration {
        int order;
        Handler<RoutingContext> handler;
        @SuppressWarnings("unchecked") Router router() {
            var route = proxy(Route.class, (p, m, a) -> switch (m.getName()) {
                case "order" -> { order = (int) a[0]; yield p; }
                case "failureHandler" -> { handler = (Handler<RoutingContext>) a[0]; yield p; }
                default -> throw new AssertionError(m.getName());
            });
            return proxy(Router.class, (p, m, a) -> { assertEquals("route", m.getName()); return route; });
        }
    }

    private static final class FailureState {
        final HttpMethod method;
        final String path;
        final Throwable failure;
        int status, next, ends;
        boolean ended, headWritten;
        String body;
        final Map<String, String> headers = new HashMap<>();
        FailureState(HttpMethod method, String path, int status, Throwable failure) {
            this.method = method; this.path = path; this.status = status; this.failure = failure;
            headers.put("Cache-Control", "public");
            headers.put("WWW-Authenticate", "Bearer existing-challenge");
            headers.put("X-Existing", "preserved");
        }
        RoutingContext context() {
            var response = proxy(HttpServerResponse.class, (p, m, a) -> switch (m.getName()) {
                case "headers" -> io.vertx.core.MultiMap.caseInsensitiveMultiMap().addAll(headers);
                case "getStatusCode" -> status; case "ended" -> ended; case "headWritten" -> headWritten;
                case "putHeader" -> { headers.put(a[0].toString(), a[1].toString()); yield p; }
                case "end" -> { ends++; body = (String) a[0]; yield null; }
                default -> throw new AssertionError(m.getName());
            });
            var request = proxy(HttpServerRequest.class, (p, m, a) -> {
                assertEquals("method", m.getName()); return method;
            });
            return proxy(RoutingContext.class, (p, m, a) -> switch (m.getName()) {
                case "request" -> request; case "normalizedPath" -> path; case "response" -> response;
                case "failure" -> failure; case "next" -> { next++; yield null; }
                default -> throw new AssertionError(m.getName());
            });
        }
    }
}
