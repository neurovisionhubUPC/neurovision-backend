package com.neurovision.rehab.analytics.interfaces.rest.resource;

import static org.junit.jupiter.api.Assertions.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.neurovision.rehab.analytics.application.internal.queryservices.SustainedFixationTrialSummaryService;
import com.neurovision.rehab.analytics.application.ports.out.authorization.SustainedFixationTrialSummaryAuthorizationPort;
import com.neurovision.rehab.analytics.application.ports.out.authorization.SustainedFixationTrialSummaryAuthorizationPort.Permission;
import com.neurovision.rehab.analytics.application.ports.out.persistence.SustainedFixationTrialResultStore.Stored;
import com.neurovision.rehab.analytics.domain.model.valueobjects.*;
import com.neurovision.rehab.analytics.domain.model.valueobjects.VisualSearchTrialResult.*;
import io.quarkus.oidc.AccessTokenCredential;
import io.quarkus.security.identity.SecurityIdentity;
import io.smallrye.mutiny.Uni;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.Response;
import java.lang.reflect.Proxy;
import java.util.*;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

class SustainedFixationTrialSummaryControllerTest {
    static final UUID SESSION = new UUID(0,1), CONFIGURATION = new UUID(0,2);
    final SustainedFixationTrialContext context = new SustainedFixationTrialContext(SESSION,CONFIGURATION,3,500,3000);
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
    SustainedFixationTrialSummaryController controller(SecurityIdentity identity, DataOrigin expectedOrigin) {
        var service = new SustainedFixationTrialSummaryService((session,token) -> {
            authCalls++; assertEquals(SESSION,session); assertEquals("original.doctor",token);
            return new SustainedFixationTrialSummaryAuthorizationPort.Decision(permission,
                    permission == Permission.PERMITTED ? context : null);
        }, (session,origin,limit) -> {
            reads++; assertEquals(reads,authCalls,"Authorization must precede the first and only read per request"); assertEquals(SESSION,session); assertEquals(expectedOrigin,origin); assertEquals(1001,limit);
            return readFailure ? Uni.createFrom().failure(new IllegalStateException("private-store-error"))
                    : Uni.createFrom().item(rows);
        });
        return new SustainedFixationTrialSummaryController(service,identity);
    }
    SustainedFixationTrialSummaryController controller() {
        return controller(identity(Set.of("DOCTOR"),false,"original.doctor"),DataOrigin.SYNTHETIC);
    }
    Response call(SustainedFixationTrialSummaryController controller,String session,String origin) {
        return controller.read(session,origin).await().atMost(java.time.Duration.ofSeconds(2));
    }
    Response call(SustainedFixationTrialSummaryController controller) { return call(controller,SESSION.toString(),"SYNTHETIC"); }
    Stored row(String id,Outcome outcome,long duration,DataOrigin origin) {
        return new Stored(context,new SustainedFixationTrialResult(1,SESSION,id,outcome,duration,
                outcome == Outcome.SUCCESS ? 650L : 100L, outcome == Outcome.SUCCESS ? 500L : 100L,origin));
    }
    void error(Response response,int status,String code) {
        try (response) {
            assertEquals(status,response.getStatus()); assertEquals(Map.of("code","SUSTAINED_FIXATION_SUMMARY_"+code),response.getEntity());
            assertEquals("no-store",response.getHeaderString("Cache-Control"));
            if (status == 401) assertEquals("Bearer",response.getHeaderString("WWW-Authenticate"));
        }
    }
    @Test void returnsOnlyExistingMetricsAndIncludesTimeFromNonSuccessfulAttempts() throws Exception {
        rows = List.of(row("one",Outcome.SUCCESS,1000,DataOrigin.SYNTHETIC),row("two",Outcome.SUCCESS,1001,DataOrigin.SYNTHETIC),
                row("three",Outcome.TIMEOUT,3000,DataOrigin.SYNTHETIC),row("four",Outcome.INTERRUPTED,200,DataOrigin.SYNTHETIC));
        try (var response = call(controller())) {
            assertEquals(200,response.getStatus()); assertEquals("no-store",response.getHeaderString("Cache-Control"));
            var json = new ObjectMapper().readTree(new ObjectMapper().writeValueAsBytes(response.getEntity()));
            assertEquals(8,json.size()); assertEquals("sustained-fixation-trial-v1",json.path("calculationVersion").textValue());
            assertEquals(SESSION.toString(),json.path("therapySessionId").textValue());
            assertEquals(CONFIGURATION.toString(),json.path("configurationId").textValue());
            assertEquals(3,json.path("configurationVersion").longValue()); assertEquals("SYNTHETIC",json.path("dataOrigin").textValue());
            assertEquals(4,json.path("reportedTrialCount").longValue());
            assertEquals(1500,json.path("totalInsideTargetMs").longValue());
            assertEquals(2,json.path("completedTargetCount").longValue());
            assertEquals("SustainedFixationTrialSummaryResponse[redacted]",response.getEntity().toString());
        }
        assertEquals(1,authCalls); assertEquals(1,reads);
    }
    @Test void authorizedEmptyEvidenceReturnsAllThreeMetricsAsZero() throws Exception {
        try (var response = call(controller())) {
            var mapper = new ObjectMapper().setSerializationInclusion(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL);
            var json = mapper.readTree(mapper.writeValueAsBytes(response.getEntity()));
            assertEquals(200,response.getStatus()); assertEquals(8,json.size());
            for (String name : List.of("reportedTrialCount","totalInsideTargetMs","completedTargetCount"))
                assertTrue(json.path(name).isIntegralNumber() && json.path(name).longValue() == 0, name);
        }
    }
    @Test void explicitProducerOriginIsForwardedWithoutMixingOrInventingErrorsOrRates() throws Exception {
        rows = List.of(row("one",Outcome.INTERRUPTED,100,DataOrigin.PRODUCER_REPORTED));
        var c = controller(identity(Set.of("DOCTOR"),false,"original.doctor"),DataOrigin.PRODUCER_REPORTED);
        try (var response = call(c,SESSION.toString(),"PRODUCER_REPORTED")) {
            var json = new ObjectMapper().valueToTree(response.getEntity());
            assertEquals(200,response.getStatus()); assertEquals("PRODUCER_REPORTED",json.path("dataOrigin").textValue());
            assertEquals(1,json.path("reportedTrialCount").longValue());
            assertEquals(100,json.path("totalInsideTargetMs").longValue());
            assertEquals(0,json.path("completedTargetCount").longValue());
        }
    }
    @Test void invalidSessionOrMissingUnknownOrNormalizedOriginNeverReachesPorts() {
        var c = controller();
        for (String id : Arrays.asList(null,"","invalid","1-1-1-1-1")) error(call(c,id,"SYNTHETIC"),400,"INVALID_REQUEST");
        for (String origin : Arrays.asList(null,"","synthetic"," SYNTHETIC","SYNTHETIC ","ALL"))
            error(call(c,SESSION.toString(),origin),400,"INVALID_REQUEST");
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
        rows = List.of(row("wrong-origin",Outcome.SUCCESS,1000,DataOrigin.PRODUCER_REPORTED)); error(call(c),503,"UNAVAILABLE");
        assertEquals(3,authCalls); assertEquals(3,reads);
    }
    @Test void thousandResultsAreSupportedButOverflowHasNoPartialBody() {
        rows = IntStream.range(0,1000).mapToObj(i -> row("trial-"+i,Outcome.SUCCESS,1000,DataOrigin.SYNTHETIC)).toList();
        var c = controller(); try (var response = call(c)) {
            assertEquals(200,response.getStatus()); assertEquals(1000,((SustainedFixationTrialSummaryResponse)response.getEntity()).completedTargetCount());
        }
        rows = IntStream.range(0,1001).mapToObj(i -> row("trial-"+i,Outcome.SUCCESS,1000,DataOrigin.SYNTHETIC)).toList();
        error(call(c),422,"LIMIT_EXCEEDED"); assertEquals(2,reads);
    }
    @Test void exposesOnlyTheAgreedGetAndDoctorRole() throws Exception {
        var type = SustainedFixationTrialSummaryController.class;
        assertEquals("/api/v1/analytics/sessions/{therapySessionId}/sustained-fixation/summary",type.getAnnotation(Path.class).value());
        assertEquals(List.of("application/json"),List.of(type.getAnnotation(Produces.class).value()));
        var method = type.getMethod("read",String.class,String.class);
        assertNotNull(method.getAnnotation(GET.class)); assertNotNull(method.getAnnotation(io.smallrye.common.annotation.Blocking.class));
        assertEquals(List.of("DOCTOR"),List.of(method.getAnnotation(jakarta.annotation.security.RolesAllowed.class).value()));
        assertEquals("dataOrigin",method.getParameters()[1].getAnnotation(QueryParam.class).value());
    }
}
