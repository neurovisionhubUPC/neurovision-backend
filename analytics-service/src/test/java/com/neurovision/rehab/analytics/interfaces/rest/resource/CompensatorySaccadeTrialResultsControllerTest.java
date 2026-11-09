package com.neurovision.rehab.analytics.interfaces.rest.resource;

import static org.junit.jupiter.api.Assertions.*;
import com.neurovision.rehab.analytics.application.internal.commandservices.ReceiveCompensatorySaccadeTrialResultService;
import com.neurovision.rehab.analytics.application.ports.out.authorization.CompensatorySaccadeTrialAuthorizationPort;
import com.neurovision.rehab.analytics.application.ports.out.persistence.CompensatorySaccadeTrialResultStore;
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

class CompensatorySaccadeTrialResultsControllerTest {
    static final String SESSION = new UUID(0, 1).toString();
    static final String BODY = "{\"schemaVersion\":1,\"trialId\":\"trial-001\",\"outcome\":\"SUCCESS\",\"elapsedMs\":2250,\"confirmedDwellMs\":600,\"dataOrigin\":\"SYNTHETIC\"}";
    final CompensatorySaccadeTrialContext context = new CompensatorySaccadeTrialContext(new UUID(0,1), new UUID(0,2), 3,500,3000);
    int authCalls, reads, writes;
    boolean mayInsert = true, writeFailure;
    CompensatorySaccadeTrialAuthorizationPort.Permission permission = CompensatorySaccadeTrialAuthorizationPort.Permission.PERMITTED;
    CompensatorySaccadeTrialResultStore.Stored stored;
    SecurityIdentity identity(Set<String> roles, boolean anonymous, boolean token) {
        return (SecurityIdentity)Proxy.newProxyInstance(SecurityIdentity.class.getClassLoader(), new Class[]{SecurityIdentity.class},
                (p,m,a) -> switch(m.getName()) {
                    case "isAnonymous" -> anonymous; case "getRoles" -> roles;
                    case "hasRole" -> roles.contains(a[0]);
                    case "getCredential" -> token ? new AccessTokenCredential("original") : null;
                    default -> throw new AssertionError(m.getName());
                });
    }
    CompensatorySaccadeTrialResultsController controller(SecurityIdentity identity) {
        return new CompensatorySaccadeTrialResultsController(new ReceiveCompensatorySaccadeTrialResultService((session,token) -> {
            authCalls++; assertEquals(new UUID(0,1),session); assertEquals("original",token);
            return permission == CompensatorySaccadeTrialAuthorizationPort.Permission.PERMITTED
                    ? new CompensatorySaccadeTrialAuthorizationPort.Decision(permission,context,mayInsert)
                    : new CompensatorySaccadeTrialAuthorizationPort.Decision(permission,null,false);
        },new CompensatorySaccadeTrialResultStore() {
            public Uni<Optional<Stored>> find(UUID s,String t) { reads++; return Uni.createFrom().item(Optional.ofNullable(stored)); }
            public Uni<WriteResult> insertIfAbsent(Stored value) {
                writes++; stored=value;
                return writeFailure ? Uni.createFrom().failure(new IllegalStateException("private"))
                        : Uni.createFrom().item(new WriteResult(true,value));
            }
        }),identity);
    }
    CompensatorySaccadeTrialResultsController controller() { return controller(identity(Set.of("PATIENT"),false,true)); }
    Response call(CompensatorySaccadeTrialResultsController c,String session,String body) {
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
        var c=controller();for(var value:List.of(CompensatorySaccadeTrialAuthorizationPort.Permission.DENIED,
                CompensatorySaccadeTrialAuthorizationPort.Permission.INVALID_TOKEN,CompensatorySaccadeTrialAuthorizationPort.Permission.UNAVAILABLE)) {
            permission=value;
            outcome(call(c,SESSION,BODY),value==CompensatorySaccadeTrialAuthorizationPort.Permission.DENIED?403:
                    value==CompensatorySaccadeTrialAuthorizationPort.Permission.INVALID_TOKEN?401:503,
                    value==CompensatorySaccadeTrialAuthorizationPort.Permission.INVALID_TOKEN?"INVALID_TOKEN":value.name());
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
        var type=CompensatorySaccadeTrialResultsController.class;
        assertEquals("/api/v1/analytics/sessions/{therapySessionId}/compensatory-saccade/trial-results",type.getAnnotation(Path.class).value());
        assertEquals(List.of("application/json"),List.of(type.getAnnotation(Consumes.class).value()));
        var method=type.getMethod("receive",String.class,InputStream.class);
        assertNotNull(method.getAnnotation(POST.class));
        assertEquals(List.of("PATIENT"),List.of(method.getAnnotation(jakarta.annotation.security.RolesAllowed.class).value()));
    }
}
