package com.neurovision.rehab.analytics.interfaces.rest.resource;

import static org.junit.jupiter.api.Assertions.*;
import com.neurovision.rehab.analytics.application.internal.commandservices.ReceiveSustainedFixationTrialResultService;
import com.neurovision.rehab.analytics.application.ports.out.authorization.SustainedFixationTrialAuthorizationPort;
import com.neurovision.rehab.analytics.application.ports.out.persistence.SustainedFixationTrialResultStore;
import com.neurovision.rehab.analytics.domain.model.valueobjects.*;
import io.quarkus.oidc.AccessTokenCredential;
import io.quarkus.security.identity.SecurityIdentity;
import io.smallrye.mutiny.Uni;
import java.io.*;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.util.*;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.Test;

class SustainedFixationTrialResultsControllerTest {
    static final String SESSION = new UUID(0, 1).toString();
    static final String BODY = "{\"schemaVersion\":1,\"trialId\":\"trial-001\",\"outcome\":\"SUCCESS\",\"elapsedMs\":2250,\"insideTargetMs\":1000,\"longestContinuousInsideMs\":600,\"dataOrigin\":\"SYNTHETIC\"}";
    final SustainedFixationTrialContext context = new SustainedFixationTrialContext(new UUID(0,1), new UUID(0,2), 3,500,3000);
    int authCalls, reads, writes;
    boolean mayInsert = true, writeFailure;
    SustainedFixationTrialAuthorizationPort.Permission permission = SustainedFixationTrialAuthorizationPort.Permission.PERMITTED;
    SustainedFixationTrialResultStore.Stored stored;
    SecurityIdentity identity(Set<String> roles, boolean anonymous, boolean token) {
        return (SecurityIdentity)Proxy.newProxyInstance(SecurityIdentity.class.getClassLoader(), new Class[]{SecurityIdentity.class},
                (p,m,a) -> switch(m.getName()) {
                    case "isAnonymous" -> anonymous; case "getRoles" -> roles;
                    case "hasRole" -> roles.contains(a[0]);
                    case "getCredential" -> token ? new AccessTokenCredential("original") : null;
                    default -> throw new AssertionError(m.getName());
                });
    }
    SustainedFixationTrialResultsController controller(SecurityIdentity identity) {
        return new SustainedFixationTrialResultsController(new ReceiveSustainedFixationTrialResultService((session,token) -> {
            authCalls++; assertEquals(new UUID(0,1),session); assertEquals("original",token);
            return permission == SustainedFixationTrialAuthorizationPort.Permission.PERMITTED
                    ? new SustainedFixationTrialAuthorizationPort.Decision(permission,context,mayInsert)
                    : new SustainedFixationTrialAuthorizationPort.Decision(permission,null,false);
        },new SustainedFixationTrialResultStore() {
            public Uni<Optional<Stored>> find(UUID s,String t) { reads++; return Uni.createFrom().item(Optional.ofNullable(stored)); }
            public Uni<WriteResult> insertIfAbsent(Stored value) {
                writes++; stored=value;
                return writeFailure ? Uni.createFrom().failure(new IllegalStateException("private"))
                        : Uni.createFrom().item(new WriteResult(true,value));
            }
        }),identity);
    }
    SustainedFixationTrialResultsController controller() { return controller(identity(Set.of("PATIENT"),false,true)); }
    Response call(SustainedFixationTrialResultsController c,String session,String body) {
        return c.receive(session,new ByteArrayInputStream(body.getBytes(StandardCharsets.UTF_8))).await().indefinitely();
    }
    void outcome(Response response,int status,String value) {
        try(response) {
            assertEquals(status,response.getStatus()); assertEquals(Map.of("status",value),response.getEntity());
            assertEquals("no-store",response.getHeaderString("Cache-Control"));
            if(status==401) assertEquals("Bearer",response.getHeaderString("WWW-Authenticate"));
        }
    }
    @Test void createsThenConfirmsIdenticalReplayAfterClosureWithFreshAuthorization() {
        var c=controller(); outcome(call(c,SESSION,BODY),201,"CREATED"); mayInsert=false;
        outcome(call(c,SESSION,BODY),200,"ALREADY_STORED");
        assertEquals(2,authCalls);assertEquals(2,reads);assertEquals(1,writes);
    }
    @Test void conflictingReplayDoesNotOverwriteAndClosedAbsentCannotCreate() {
        var c=controller();outcome(call(c,SESSION,BODY),201,"CREATED");var before=stored;
        outcome(call(c,SESSION,BODY.replace("2250","2200")),409,"CONFLICT");
        assertSame(before,stored);assertEquals(1,writes);
        stored=null;mayInsert=false;outcome(call(c,SESSION,BODY),403,"DENIED");assertEquals(1,writes);
    }
    @Test void securityAndInvalidInputNeverReachPorts() {
        for(var roles:List.of(Set.of("DOCTOR"),Set.of("ADMIN"),Set.of("PATIENT","DOCTOR"),Set.of("PATIENT","ADMIN"),Set.<String>of()))
            outcome(call(controller(identity(roles,false,true)),SESSION,BODY),403,"DENIED");
        outcome(call(controller(identity(Set.of(),true,false)),SESSION,BODY),401,"INVALID_TOKEN");
        outcome(call(controller(identity(Set.of("PATIENT"),false,false)),SESSION,BODY),401,"INVALID_TOKEN");
        var c=controller();for(String id:List.of("invalid","1-1-1-1-1"))outcome(call(c,id,BODY),400,"INVALID_RESULT");
        outcome(call(c,SESSION,"{}"),400,"INVALID_RESULT");outcome(call(c,SESSION," ".repeat(8193)),413,"PAYLOAD_TOO_LARGE");
        assertEquals(0,authCalls);assertEquals(0,reads);assertEquals(0,writes);
    }
    @Test void deniedInvalidAndUnavailableClinicalNeverReadOrWrite() {
        var c=controller();for(var value:List.of(SustainedFixationTrialAuthorizationPort.Permission.DENIED,
                SustainedFixationTrialAuthorizationPort.Permission.INVALID_TOKEN,SustainedFixationTrialAuthorizationPort.Permission.UNAVAILABLE)) {
            permission=value;
            outcome(call(c,SESSION,BODY),value==SustainedFixationTrialAuthorizationPort.Permission.DENIED?403:
                    value==SustainedFixationTrialAuthorizationPort.Permission.INVALID_TOKEN?401:503,
                    value==SustainedFixationTrialAuthorizationPort.Permission.INVALID_TOKEN?"INVALID_TOKEN":value.name());
        }
        assertEquals(0,reads);assertEquals(0,writes);
    }
    @Test void uncertainWriteIsDistinctAndCanBeConfirmedWithoutSecondInsert() {
        var c=controller();writeFailure=true;
        outcome(call(c,SESSION,BODY),503,"WRITE_OUTCOME_UNKNOWN");assertEquals(1,writes);
        mayInsert=false;outcome(call(c,SESSION,BODY),200,"ALREADY_STORED");assertEquals(1,writes);
    }
    @Test void frozenTimingValidationRemainsInTheExistingService() {
        outcome(call(controller(),SESSION,BODY.replace("600","100")),400,"INVALID_RESULT");
        assertEquals(1,authCalls);assertEquals(0,reads);assertEquals(0,writes);
    }
    @Test void exposesOnlyTheAgreedPostRouteAndMediaType() throws Exception {
        var type=SustainedFixationTrialResultsController.class;
        assertEquals("/api/v1/analytics/sessions/{therapySessionId}/sustained-fixation/trial-results",type.getAnnotation(Path.class).value());
        assertEquals(List.of("application/json"),List.of(type.getAnnotation(Consumes.class).value()));
        var method=type.getMethod("receive",String.class,InputStream.class);
        assertNotNull(method.getAnnotation(POST.class));
        assertEquals(List.of("PATIENT"),List.of(method.getAnnotation(jakarta.annotation.security.RolesAllowed.class).value()));
    }
}
