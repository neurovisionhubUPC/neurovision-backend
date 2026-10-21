package com.neurovision.rehab.clinical.interfaces.rest.resource;

import static org.junit.jupiter.api.Assertions.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.neurovision.rehab.clinical.application.internal.commandservices.RehabilitationContextService;
import com.neurovision.rehab.clinical.application.ports.out.persistence.RehabilitationContextStore;
import com.neurovision.rehab.clinical.domain.model.valueobjects.RehabilitationContext;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class DoctorRehabilitationContextControllerTest {
    final UUID id = new UUID(0, 1);
    int reads, writes;
    RehabilitationContextStore.Snapshot saved = new RehabilitationContextStore.Snapshot(id,
            RehabilitationContext.rehydrate(null, "Existing", List.of("Legacy"), null), 3, Instant.EPOCH);
    final RehabilitationContextStore store = new RehabilitationContextStore() {
        public Optional<Snapshot> readAssigned(UUID target, String doctor) {
            reads++;
            return id.equals(target) && "doctor".equals(doctor) ? Optional.of(saved) : Optional.empty();
        }
        public Optional<Snapshot> replaceAssigned(UUID target, String doctor, long expected,
                RehabilitationContext context, Instant time) {
            writes++;
            saved = new Snapshot(target, context, expected + 1, time);
            return Optional.of(saved);
        }
    };
    DoctorRehabilitationContextController controller(Set<String> roles, String subject, boolean anonymous) {
        return new DoctorRehabilitationContextController(new RehabilitationContextService(store,
                Clock.fixed(Instant.EPOCH.plusSeconds(10), ZoneOffset.UTC)),
                new AssignedPatientRequestSupportTest().identity(anonymous, roles, subject), new ObjectMapper());
    }
    DoctorRehabilitationContextController doctor() { return controller(Set.of("DOCTOR"), "doctor", false); }

    @Test void doctorReadsAndPatchesFourManualFieldsAndCanClear() {
        assertEquals(200, doctor().read(id.toString()).getStatus());
        var response = doctor().patch(id.toString(), """
                {"expectedVersion":3,"affectedHemifield":"LEFT","previousDiagnosis":null,
                 "sequelae":[],"observations":" Manual notes "}
                """);
        assertEquals(200, response.getStatus());
        var body = (Map<?, ?>) response.getEntity();
        assertEquals(Set.of("clinicalPatientId", "version", "updatedAt", "affectedHemifield",
                "previousDiagnosis", "sequelae", "observations"), body.keySet());
        assertNull(body.get("previousDiagnosis"));
        assertEquals(List.of(), body.get("sequelae"));
        assertEquals("Manual notes", body.get("observations"));
        assertEquals(4L, body.get("version"));
        assertEquals("no-store", response.getHeaderString("Cache-Control"));
        assertEquals(1, writes);
        assertEquals(200, doctor().read(id.toString()).getStatus());
    }

    @Test void patientAnonymousAndMixedRolesCannotReadOrWrite() {
        for (var actor : List.of(controller(Set.of("PATIENT"), "patient", false),
                controller(Set.of("DOCTOR", "PATIENT"), "doctor", false),
                controller(Set.of(), null, true))) {
            int status = actor.read(id.toString()).getStatus();
            assertTrue(status == 401 || status == 403);
            assertEquals(status, actor.patch(id.toString(), "{\"expectedVersion\":3,\"observations\":\"x\"}").getStatus());
        }
        assertEquals(0, reads);
        assertEquals(0, writes);
    }

    @Test void foreignDoctorAndUnknownPatientShareDeniedResponseWithoutChanges() {
        var foreign = controller(Set.of("DOCTOR"), "other", false);
        var denied = foreign.patch(id.toString(), "{\"expectedVersion\":3,\"observations\":\"x\"}");
        var unknown = doctor().read(new UUID(0, 2).toString());
        assertEquals(403, denied.getStatus());
        assertEquals(denied.getEntity(), unknown.getEntity());
        assertEquals(0, writes);
        assertEquals("Existing", saved.context().previousDiagnosis());
    }

    @Test void invalidClosedRequestsAndStaleVersionsNeverWrite() {
        var initial = saved;
        for (String body : List.of("{}", "{\"expectedVersion\":3}",
                "{\"expectedVersion\":3,\"doctorSubject\":\"spoof\",\"observations\":\"x\"}",
                "{\"expectedVersion\":3.0,\"observations\":\"x\"}",
                "{\"expectedVersion\":-1,\"observations\":\"x\"}",
                "{\"expectedVersion\":3,\"observations\":42}",
                "{\"expectedVersion\":3,\"affectedHemifield\":\"left\"}",
                "{\"expectedVersion\":3,\"sequelae\":[null]}",
                "{\"expectedVersion\":3,\"sequelae\":[\" \"]}",
                "{\"expectedVersion\":3,\"observations\":null,\"observations\":\"x\"}",
                "{\"expectedVersion\":3,\"observations\":null}{}", "x".repeat(65537))) {
            assertEquals(400, doctor().patch(id.toString(), body).getStatus(),
                    body.substring(0, Math.min(50, body.length())));
        }
        assertEquals(409, doctor().patch(id.toString(), "{\"expectedVersion\":2,\"observations\":\"x\"}").getStatus());
        assertEquals(initial, saved);
        assertEquals(0, writes);
    }

    @Test void omittedFieldsStayUnchanged() {
        assertEquals(200, doctor().patch(id.toString(), "{\"expectedVersion\":3,\"observations\":\"notes\"}").getStatus());
        assertEquals("Existing", saved.context().previousDiagnosis());
        assertEquals(List.of("Legacy"), saved.context().sequelae());
    }

    @Test void routesDeclareDoctorOnlyAccessAndNeverExposeIdentityFields() throws Exception {
        assertArrayEquals(new String[]{"DOCTOR"}, DoctorRehabilitationContextController.class
                .getAnnotation(jakarta.annotation.security.RolesAllowed.class).value());
        assertNotNull(DoctorRehabilitationContextController.class.getMethod("read", String.class)
                .getAnnotation(jakarta.ws.rs.GET.class));
        assertNotNull(DoctorRehabilitationContextController.class.getMethod("patch", String.class, String.class)
                .getAnnotation(jakarta.ws.rs.PATCH.class));
        var response = doctor().read(id.toString());
        assertFalse(((Map<?, ?>) response.getEntity()).containsKey("patientSubject"));
    }
}
