package com.neurovision.rehab.analytics.interfaces.rest.resource;

import static org.junit.jupiter.api.Assertions.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.neurovision.rehab.analytics.application.internal.queryservices.SystematicExplorationTrialSummaryService;
import com.neurovision.rehab.analytics.application.ports.out.authorization.SystematicExplorationTrialSummaryAuthorizationPort;
import com.neurovision.rehab.analytics.application.ports.out.authorization.SystematicExplorationTrialSummaryAuthorizationPort.Permission;
import com.neurovision.rehab.analytics.application.ports.out.persistence.SystematicExplorationTrialResultStore.Stored;
import com.neurovision.rehab.analytics.domain.model.valueobjects.*;
import com.neurovision.rehab.analytics.domain.model.valueobjects.VisualSearchTrialResult.*;
import io.quarkus.oidc.AccessTokenCredential;
import io.quarkus.security.identity.SecurityIdentity;
import io.smallrye.mutiny.Uni;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.UriInfo;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MultivaluedHashMap;
import java.lang.reflect.Proxy;
import com.neurovision.rehab.analytics.domain.model.valueobjects.SystematicExplorationTrialResult.*;
import com.neurovision.rehab.analytics.domain.model.valueobjects.SystematicExplorationTrialResult.Outcome;
import java.util.*;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

class SystematicExplorationTrialSummaryControllerTest {
    static final UUID SESSION = new UUID(0,1), CONFIGURATION = new UUID(0,2);
    final SystematicExplorationTrialContext context = new SystematicExplorationTrialContext(SESSION,CONFIGURATION,3,500,3000);
    int authCalls, reads;
    Permission permission = Permission.PERMITTED;
    boolean readFailure;
    List<Stored> rows = List.of();
    SecurityIdentity identity(Set<String> roles, boolean anonymous, String token) {
        return (SecurityIdentity) Proxy.newProxyInstance(SecurityIdentity.class.getClassLoader(), new Class[]{SecurityIdentity.class},
                (p,m,a) -> switch (m.getName()) {
                    case "isAnonymous" -> anonymous;
                    case "getRoles" -> roles;
                    case "getCredential" -> token == null ? null : new AccessTokenCredential("original") {
                        @Override public String getToken() { return token; }
                    };
                    default -> throw new AssertionError(m.getName());
                });
    }
    SystematicExplorationTrialSummaryController controller(SecurityIdentity identity, DataOrigin expectedOrigin) {
        var service = new SystematicExplorationTrialSummaryService((session,token) -> {
            authCalls++; assertEquals(SESSION,session); assertEquals("original.doctor",token);
            return new SystematicExplorationTrialSummaryAuthorizationPort.Decision(permission,
                    permission == Permission.PERMITTED ? context : null);
        }, (session,origin,limit) -> {
            reads++; assertEquals(SESSION,session); assertEquals(expectedOrigin,origin); assertEquals(1001,limit);
            return readFailure ? Uni.createFrom().failure(new IllegalStateException("private-store-error"))
                    : Uni.createFrom().item(rows);
        });
        return new SystematicExplorationTrialSummaryController(service,identity);
    }
    SystematicExplorationTrialSummaryController controller() {
        return controller(identity(Set.of("DOCTOR"),false,"original.doctor"),DataOrigin.SYNTHETIC);
    }
    Response call(SystematicExplorationTrialSummaryController controller,String session,String origin) {
        return callOrigins(controller,session,origin == null ? List.of() : List.of(origin));
    }
    Response callOrigins(SystematicExplorationTrialSummaryController controller,String session,List<String> origins) {
        var parameters = new MultivaluedHashMap<String,String>();
        parameters.addAll("dataOrigin",origins);
        var before = new MultivaluedHashMap<String,String>(parameters);
        var uri = (UriInfo)Proxy.newProxyInstance(UriInfo.class.getClassLoader(),new Class[]{UriInfo.class},
                (p,m,a) -> { assertEquals("getQueryParameters",m.getName()); return parameters; });
        var response = controller.read(session,origins.isEmpty() ? null : origins.getFirst(),uri).await().indefinitely();
        assertEquals(before,parameters,"Query parameters must remain unchanged");
        return response;
    }
    Response call(SystematicExplorationTrialSummaryController controller) { return call(controller,SESSION.toString(),"SYNTHETIC"); }
    Stored row(String id,Outcome outcome,long duration,DataOrigin origin) {
        var visits = outcome == Outcome.COMPLETED
                ? Arrays.stream(Quadrant.values()).map(q -> new Visit(q,duration,500)).toList()
                : List.<Visit>of();
        return new Stored(context,new SystematicExplorationTrialResult(SESSION,id,origin,outcome,
                outcome == Outcome.COMPLETED ? duration * 4 : duration,visits));
    }
    void error(Response response,int status,String code) {
        try (response) {
            assertEquals(status,response.getStatus()); assertEquals(Map.of("code","SYSTEMATIC_EXPLORATION_SUMMARY_"+code),response.getEntity());
            assertEquals("no-store",response.getHeaderString("Cache-Control"));
            if (status == 401) assertEquals("Bearer",response.getHeaderString("WWW-Authenticate"));
        }
    }
    @Test void returnsOnlyExistingSortedTraversalMetricsWithoutChangingOrDuplicatingEvidence() throws Exception {
        var completed = row("a",Outcome.COMPLETED,1000,DataOrigin.SYNTHETIC);
        var partial = new Stored(context,new SystematicExplorationTrialResult(SESSION,"b",DataOrigin.SYNTHETIC,
                Outcome.TIMED_OUT,4700,List.of(new Visit(Quadrant.TOP_LEFT,800,500),new Visit(Quadrant.TOP_RIGHT,900,500))));
        rows = List.of(row("z",Outcome.INTERRUPTED,200,DataOrigin.SYNTHETIC),partial,completed,completed);
        var before = List.copyOf(rows);
        try (var response = call(controller())) {
            assertEquals(200,response.getStatus()); assertEquals("no-store",response.getHeaderString("Cache-Control"));
            var json = new ObjectMapper().valueToTree(response.getEntity());
            assertEquals(7,json.size()); assertEquals("systematic-exploration-trial-v1",json.path("calculationVersion").textValue());
            assertEquals(SESSION.toString(),json.path("therapySessionId").textValue());
            assertEquals(CONFIGURATION.toString(),json.path("configurationId").textValue());
            assertEquals(3,json.path("configurationVersion").longValue()); assertEquals("SYNTHETIC",json.path("dataOrigin").textValue());
            assertEquals(3,json.path("reportedTrialCount").longValue());
            var traversals = json.path("traversals"); assertEquals(3,traversals.size());
            String[] ids = {"a","b","z"}, outcomes = {"COMPLETED","TIMED_OUT","INTERRUPTED"};
            int[] visited = {4,2,0}, coverage = {100,50,0}; long[] elapsed = {4000,4700,200};
            for (int i = 0; i < 3; i++) {
                var t = traversals.get(i); assertEquals(5,t.size());
                assertEquals(ids[i],t.path("trialId").textValue()); assertEquals(outcomes[i],t.path("outcome").textValue());
                assertEquals(visited[i],t.path("visitedRegionCount").intValue());
                assertEquals(coverage[i],t.path("coveragePercent").intValue()); assertEquals(elapsed[i],t.path("elapsedMs").longValue());
            }
            var body = (SystematicExplorationTrialSummaryResponse)response.getEntity();
            assertEquals("SystematicExplorationTrialSummaryResponse[redacted]",body.toString());
            assertEquals("SystematicExplorationTraversalResponse[redacted]",body.traversals().getFirst().toString());
            assertThrows(UnsupportedOperationException.class,() -> body.traversals().clear());
        }
        assertEquals(before,rows); assertEquals(1,authCalls); assertEquals(1,reads);
    }
    @Test void authorizedEmptyEvidenceKeepsAnExplicitEmptyListWithoutManufacturingATraversal() throws Exception {
        try (var response = call(controller())) {
            var mapper = new ObjectMapper().setSerializationInclusion(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_EMPTY);
            var json = mapper.readTree(mapper.writeValueAsBytes(response.getEntity()));
            assertEquals(200,response.getStatus()); assertEquals(7,json.size());
            assertEquals(0,json.path("reportedTrialCount").longValue());
            assertTrue(json.path("traversals").isArray()); assertTrue(json.path("traversals").isEmpty());
        }
        assertEquals(1,authCalls); assertEquals(1,reads);
    }
    @Test void explicitProducerOriginIsForwardedWithoutMixingOrInventingAggregateMetrics() {
        rows = List.of(row("one",Outcome.INTERRUPTED,100,DataOrigin.PRODUCER_REPORTED));
        var c = controller(identity(Set.of("DOCTOR"),false,"original.doctor"),DataOrigin.PRODUCER_REPORTED);
        try (var response = call(c,SESSION.toString(),"PRODUCER_REPORTED")) {
            var json = new ObjectMapper().valueToTree(response.getEntity());
            assertEquals(200,response.getStatus()); assertEquals("PRODUCER_REPORTED",json.path("dataOrigin").textValue());
            assertEquals(1,json.path("reportedTrialCount").longValue()); assertEquals(1,json.path("traversals").size());
            assertEquals("INTERRUPTED",json.path("traversals").get(0).path("outcome").textValue());
            assertEquals(7,json.size()); assertFalse(json.has("averageCoveragePercent")); assertFalse(json.has("medianDurationMs"));
        }
    }
    @Test void invalidSessionOrMissingUnknownOrNormalizedOriginNeverReachesPorts() {
        var c = controller();
        for (String id : Arrays.asList(null,"","invalid","1-1-1-1-1")) error(call(c,id,"SYNTHETIC"),400,"INVALID_REQUEST");
        for (String origin : Arrays.asList(null,"","synthetic"," SYNTHETIC","SYNTHETIC ","ALL"))
            error(call(c,SESSION.toString(),origin),400,"INVALID_REQUEST");
        assertEquals(0,authCalls); assertEquals(0,reads);
    }
    @Test void repeatedIdenticalOriginsAreRejectedBeforeAuthorizationOrStorage() {
        var c = controller();
        for (String origin : List.of("SYNTHETIC","PRODUCER_REPORTED"))
            error(callOrigins(c,SESSION.toString(),List.of(origin,origin)),400,"INVALID_REQUEST");
        assertEquals(0,authCalls); assertEquals(0,reads);
    }
    @Test void repeatedMixedOriginsAreRejectedRegardlessOfOrderBeforeEitherPort() {
        var c = controller();
        for (var origins : List.of(List.of("SYNTHETIC","PRODUCER_REPORTED"),List.of("PRODUCER_REPORTED","SYNTHETIC")))
            error(callOrigins(c,SESSION.toString(),origins),400,"INVALID_REQUEST");
        assertEquals(0,authCalls); assertEquals(0,reads);
    }
    @Test void repeatedOriginsIncludingEmptyValuesCannotBeCollapsedToAValidSingleton() {
        var c = controller();
        for (var origins : List.of(List.of("SYNTHETIC",""),List.of("","SYNTHETIC"),List.of("",""),
                List.of("PRODUCER_REPORTED",""),List.of("","PRODUCER_REPORTED")))
            error(callOrigins(c,SESSION.toString(),origins),400,"INVALID_REQUEST");
        assertEquals(0,authCalls); assertEquals(0,reads);
    }
    @Test void onlySoleDoctorWithOriginalCredentialMayInvokeTheApplication() {
        for (var roles : List.of(Set.of("PATIENT"),Set.of("ADMIN"),Set.of("DOCTOR","PATIENT"),Set.of("DOCTOR","ADMIN"),Set.<String>of()))
            error(call(controller(identity(roles,false,"original.doctor"),DataOrigin.SYNTHETIC)),403,"DENIED");
        error(call(controller(null,DataOrigin.SYNTHETIC)),401,"INVALID_TOKEN");
        error(call(controller(identity(Set.of(),true,null),DataOrigin.SYNTHETIC)),401,"INVALID_TOKEN");
        for (String token : Arrays.asList(null,""," "))
            error(call(controller(identity(Set.of("DOCTOR"),false,token),DataOrigin.SYNTHETIC)),401,"INVALID_TOKEN");
        assertEquals(0,authCalls); assertEquals(0,reads);
    }
    @Test void freshClinicalRevocationAndFailureNeverBecomeAnEmptySummary() {
        var c = controller(); try (var response = call(c)) { assertEquals(200,response.getStatus()); }
        permission = Permission.DENIED; error(call(c),403,"DENIED");
        permission = Permission.INVALID_TOKEN; error(call(c),401,"INVALID_TOKEN");
        permission = Permission.UNAVAILABLE; error(call(c),503,"UNAVAILABLE");
        assertEquals(4,authCalls); assertEquals(1,reads);
    }
    @Test void corruptOrFailedReaderIsUnavailableWithoutReturningDetails() {
        var c = controller(); readFailure = true; error(call(c),503,"UNAVAILABLE"); readFailure = false;
        rows = null; error(call(c),503,"UNAVAILABLE");
        rows = List.of(row("wrong-origin",Outcome.COMPLETED,1000,DataOrigin.PRODUCER_REPORTED)); error(call(c),503,"UNAVAILABLE");
        assertEquals(3,authCalls); assertEquals(3,reads);
    }
    @Test void thousandResultsAreSupportedButOverflowHasNoPartialBody() {
        rows = IntStream.range(0,1000).mapToObj(i -> row("trial-"+i,Outcome.COMPLETED,1000,DataOrigin.SYNTHETIC)).toList();
        var c = controller(); try (var response = call(c)) {
            assertEquals(200,response.getStatus()); assertEquals(1000,((SystematicExplorationTrialSummaryResponse)response.getEntity()).reportedTrialCount());
        }
        rows = IntStream.range(0,1001).mapToObj(i -> row("trial-"+i,Outcome.COMPLETED,1000,DataOrigin.SYNTHETIC)).toList();
        error(call(c),422,"LIMIT_EXCEEDED"); assertEquals(2,reads);
    }
    @Test void exposesOnlyTheAgreedGetAndDoctorRole() throws Exception {
        var type = SystematicExplorationTrialSummaryController.class;
        assertEquals("/api/v1/analytics/sessions/{therapySessionId}/systematic-exploration/summary",type.getAnnotation(Path.class).value());
        assertEquals(List.of("application/json"),List.of(type.getAnnotation(Produces.class).value()));
        var method = type.getMethod("read",String.class,String.class,UriInfo.class);
        assertNotNull(method.getAnnotation(GET.class)); assertNotNull(method.getAnnotation(io.smallrye.common.annotation.Blocking.class));
        assertEquals(List.of("DOCTOR"),List.of(method.getAnnotation(jakarta.annotation.security.RolesAllowed.class).value()));
        assertEquals("dataOrigin",method.getParameters()[1].getAnnotation(QueryParam.class).value());
        assertNotNull(method.getParameters()[2].getAnnotation(Context.class));
    }
}
