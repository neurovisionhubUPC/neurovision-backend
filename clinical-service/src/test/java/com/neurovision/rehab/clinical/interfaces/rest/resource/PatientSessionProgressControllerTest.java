package com.neurovision.rehab.clinical.interfaces.rest.resource;

import static org.junit.jupiter.api.Assertions.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.neurovision.rehab.clinical.application.internal.commandservices.SessionProgressConflictException;
import com.neurovision.rehab.clinical.application.ports.in.PatientSessionProgressUseCase;
import com.neurovision.rehab.clinical.domain.model.valueobjects.*;
import io.quarkus.security.identity.SecurityIdentity;
import java.util.*;
import org.junit.jupiter.api.Test;

class PatientSessionProgressControllerTest {
    final String id = new UUID(0, 1).toString();
    final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
    int calls;
    RuntimeException failure;
    final PatientSessionProgressUseCase useCase = new PatientSessionProgressUseCase() {
        View result() { calls++; if (failure != null) throw failure;
            return new View(UUID.fromString(id), "ACTIVE", CompletionTarget.Criterion.ACTIVE_TIME, 10, 0, 0, false, 1,
                    SessionActivityProgress.Mode.ACTIVE, null); }
        public View activity(CognitoSubject p, TherapySessionId s, SessionActivityEvent event) { return result(); }
        public View progress(CognitoSubject p, TherapySessionId s) { return result(); }
        public View complete(CognitoSubject p, TherapySessionId s, IdempotencyKey k, Action action) { return result(); }
    };
    PatientSessionProgressController controller(Set<String> roles, boolean anonymous) {
        SecurityIdentity identity = new AssignedPatientRequestSupportTest().identity(anonymous, roles, "synthetic-patient");
        return new PatientSessionProgressController(useCase, identity, mapper);
    }
    PatientSessionProgressController patient() { return controller(Set.of("PATIENT"), false); }
    String event() { return "{\"schemaVersion\":1,\"eventId\":\"" + new UUID(0, 2) + "\",\"sequence\":0,\"type\":\"ACTIVITY_STARTED\"}"; }
    @Test void exposesOwnActivityProgressAndCompletionWithoutCaching() {
        var resource = patient();
        for (var response : List.of(resource.activity(id, event()), resource.progress(id), resource.complete(id, "key", "{\"action\":\"ABANDONED\"}"))) {
            assertEquals(200, response.getStatus()); assertEquals("no-store", response.getHeaderString("Cache-Control"));
        }
        assertEquals(3, calls);
    }
    @Test void rejectsMixedRolesAndAnonymousBeforeParsingOrService() {
        for (var roles : List.of(Set.of("DOCTOR"), Set.of("ADMIN"), Set.of("PATIENT", "DOCTOR"), Set.of("PATIENT", "ADMIN"))) {
            var controller = controller(roles, false);
            assertEquals(403, controller.activity(id, event()).getStatus());
            assertEquals(403, controller.progress(id).getStatus());
            assertEquals(403, controller.complete(id, "key", "{\"action\":\"FINISHED\"}").getStatus());
        }
        assertEquals(401, controller(Set.of(), true).progress(id).getStatus()); assertEquals(0, calls);
    }
    @Test void strictBodyRejectsClientCountersDuplicatesTrailingJsonAndWrongTypes() {
        for (var body : List.of(event().replace("\"sequence\":0", "\"sequence\":0,\"activeSeconds\":10"),
                event().replace("\"sequence\":0", "\"sequence\":0,\"sequence\":1"), event() + "{}",
                event().replace("\"sequence\":0", "\"sequence\":0.0"), event().replace("\"schemaVersion\":1", "\"schemaVersion\":4294967297"))) {
            assertEquals(400, patient().activity(id, body).getStatus());
        }
        assertEquals(400, patient().progress("1-1-1-1-1").getStatus());
        assertEquals(400, patient().complete(id, "key", "{\"action\":\"FINISHED\",\"duration\":99}").getStatus());
        assertEquals(400, patient().complete(id, null, "{\"action\":\"FINISHED\"}").getStatus());
        assertEquals(0, calls);
    }
    @Test void keepsDomainConflictSeparateFromUnavailableAndNeverLeaksMessages() {
        failure = new SessionProgressConflictException(); assertEquals(409, patient().progress(id).getStatus());
        for (var exception : List.of(new IllegalStateException("private database details"), new IllegalArgumentException("private stored JSON"))) {
            failure = exception; var response = patient().progress(id);
            assertEquals(503, response.getStatus());
            assertEquals(Map.of("code", "SESSION_PROGRESS_UNAVAILABLE"), response.getEntity());
            assertEquals("no-store", response.getHeaderString("Cache-Control"));
        }
        failure = new SecurityException("private patient");
        assertEquals(Map.of("code", "SESSION_PROGRESS_FORBIDDEN"), patient().progress(id).getEntity());
    }
    @Test void transactionAndHttpRoutesAreExplicit() throws Exception {
        var tx = com.neurovision.rehab.clinical.infrastructure.persistence.jpa.transaction.TransactionalPatientSessionProgress.class;
        assertNotNull(tx.getMethod("activity", CognitoSubject.class, TherapySessionId.class, SessionActivityEvent.class).getAnnotation(jakarta.transaction.Transactional.class));
        assertNotNull(tx.getMethod("complete", CognitoSubject.class, TherapySessionId.class, IdempotencyKey.class, PatientSessionProgressUseCase.Action.class).getAnnotation(jakarta.transaction.Transactional.class));
        assertEquals("/progress", PatientSessionProgressController.class.getMethod("progress", String.class).getAnnotation(jakarta.ws.rs.Path.class).value());
        assertArrayEquals(new String[]{"PATIENT"}, PatientSessionProgressController.class.getAnnotation(jakarta.annotation.security.RolesAllowed.class).value());
    }
    @Test void onlyTransactionalWrapperExposesTheDefaultProgressUseCaseBeanType() throws Exception {
        var service = com.neurovision.rehab.clinical.application.internal.commandservices.PatientSessionProgressService.class;
        var configuration = com.neurovision.rehab.clinical.infrastructure.configuration.ApplicationServicesConfiguration.class;
        var producer = Arrays.stream(configuration.getDeclaredMethods())
                .filter(method -> method.getReturnType() == service).findFirst().orElseThrow();
        assertNotNull(producer.getAnnotation(jakarta.enterprise.inject.Produces.class));
        assertNotNull(producer.getAnnotation(jakarta.enterprise.context.ApplicationScoped.class));
        var restrictedTypes = producer.getAnnotation(jakarta.enterprise.inject.Typed.class);
        assertNotNull(restrictedTypes, "The internal service must not compete with the transactional use-case bean");
        assertEquals(Set.of(service), Set.of(restrictedTypes.value()));
        assertFalse(Set.of(restrictedTypes.value()).contains(PatientSessionProgressUseCase.class));
        var wrapper = com.neurovision.rehab.clinical.infrastructure.persistence.jpa.transaction.TransactionalPatientSessionProgress.class;
        assertNotNull(wrapper.getAnnotation(jakarta.enterprise.context.ApplicationScoped.class));
        assertTrue(PatientSessionProgressUseCase.class.isAssignableFrom(wrapper));
        assertEquals(service, wrapper.getConstructors()[0].getParameterTypes()[0]);
        assertEquals(PatientSessionProgressUseCase.class, PatientSessionProgressController.class.getConstructors()[0].getParameterTypes()[0]);
    }
}
