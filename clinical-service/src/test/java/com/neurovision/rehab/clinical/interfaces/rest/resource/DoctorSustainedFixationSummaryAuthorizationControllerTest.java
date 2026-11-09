package com.neurovision.rehab.clinical.interfaces.rest.resource;

import static org.junit.jupiter.api.Assertions.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.neurovision.rehab.clinical.application.internal.queryservices.AuthorizeDoctorSustainedFixationSummaryQueryService;
import com.neurovision.rehab.clinical.application.ports.out.authorization.DoctorVisualSearchSummarySnapshotRepository;
import com.neurovision.rehab.clinical.domain.model.valueobjects.*;
import com.neurovision.rehab.clinical.infrastructure.configuration.DoctorSustainedFixationSummaryAuthorizationConfiguration;
import io.quarkus.oidc.AccessTokenCredential;
import io.quarkus.security.runtime.QuarkusSecurityIdentity;
import jakarta.ws.rs.core.Response;
import java.lang.reflect.Proxy;
import java.util.*;
import org.eclipse.microprofile.jwt.JsonWebToken;
import org.junit.jupiter.api.Test;

class DoctorSustainedFixationSummaryAuthorizationControllerTest {
    static final UUID SESSION = new UUID(0, 1), CONFIG = new UUID(0, 2);
    int reads; boolean deny; RuntimeException failure;
    final DoctorVisualSearchSummarySnapshotRepository snapshots = (doctor, session) -> {
        reads++; assertEquals("synthetic-doctor", doctor.value()); assertEquals(SESSION, session.value());
        if (failure != null) throw failure;
        if (deny) return Optional.empty();
        var parameters = new TherapeuticExerciseParameters.Fixation(TherapeuticExerciseParameters.FixationMode.SUSTAINED_TARGET_FIXATION,
                TherapeuticExerciseParameters.TargetPosition.CENTER, 500, 3000, 50, 5, 500, 1);
        return Optional.of(new DoctorVisualSearchSummarySnapshotRepository.Snapshot(session,
                new TherapeuticConfigurationSnapshot(new TherapeuticConfigurationId(CONFIG), 3, TherapeuticExercise.FIXATION, 1, parameters)));
    };
    DoctorSustainedFixationSummaryAuthorizationController controller(Set<String> roles, String subject, String token) {
        JsonWebToken jwt = (JsonWebToken) Proxy.newProxyInstance(JsonWebToken.class.getClassLoader(), new Class<?>[]{JsonWebToken.class},
                (p, m, a) -> switch (m.getName()) { case "getSubject", "getName" -> subject; default -> null; });
        var identity = QuarkusSecurityIdentity.builder().setPrincipal(jwt).addRoles(roles);
        if (token != null) identity.addCredential(new AccessTokenCredential("synthetic-original-token") {
            @Override public String getToken() { return token; }
        });
        return new DoctorSustainedFixationSummaryAuthorizationController(new AuthorizeDoctorSustainedFixationSummaryQueryService(snapshots), identity.build());
    }
    Response request() { return controller(Set.of("DOCTOR"), "synthetic-doctor", "synthetic-original-token").authorize(SESSION.toString()); }
    @Test void returnsOnlySevenFrozenPolicyFieldsWithoutPatientIdentityOrCredentials() {
        var response = request(); assertEquals(200, response.getStatus()); assertEquals("no-store", response.getHeaderString("Cache-Control"));
        var json = new ObjectMapper().valueToTree(response.getEntity());
        assertEquals(7, json.size()); assertEquals(1, json.path("contractVersion").asInt()); assertTrue(json.path("permitted").asBoolean());
        assertEquals(SESSION.toString(), json.path("therapySessionId").asText()); assertEquals(CONFIG.toString(), json.path("configurationId").asText());
        assertEquals(3, json.path("configurationVersion").asLong()); assertEquals(500, json.path("minimumFixationMs").asInt());
        assertEquals(3000, json.path("maximumFixationMs").asInt());
        assertFalse(json.toString().contains("synthetic-doctor")); assertFalse(json.toString().contains("synthetic-original-token"));
    }
    @Test void wrongRolesMissingCredentialsNonCanonicalSubjectsAndIdsNeverReadPersistence() {
        for (Set<String> roles : List.of(Set.<String>of(), Set.of("PATIENT"), Set.of("ADMIN"), Set.of("DOCTOR", "PATIENT"),
                Set.of("DOCTOR", "ADMIN"), Set.of("DOCTOR", "OTHER")))
            assertEquals(403, controller(roles, "synthetic-doctor", "synthetic-original-token").authorize(SESSION.toString()).getStatus());
        for (String token : Arrays.asList(null, "", " ")) {
            var absent = controller(Set.of("DOCTOR"), "synthetic-doctor", token).authorize(SESSION.toString());
            assertEquals(401, absent.getStatus()); assertEquals("Bearer", absent.getHeaderString("WWW-Authenticate"));
            assertEquals("no-store", absent.getHeaderString("Cache-Control"));
        }
        assertEquals(401, new DoctorSustainedFixationSummaryAuthorizationController(null, null).authorize(SESSION.toString()).getStatus());
        assertEquals(403, controller(Set.of("DOCTOR"), " synthetic-doctor ", "synthetic-original-token").authorize(SESSION.toString()).getStatus());
        for (String id : Arrays.asList(null, "bad", "1-1-1-1-1", "00000000-0000-0000-0000-00000000000A"))
            assertEquals(400, controller(Set.of("DOCTOR"), "synthetic-doctor", "synthetic-original-token").authorize(id).getStatus());
        assertEquals(0, reads);
    }
    @Test void absentPermissionAndDependencyFailureAreDistinctSafeNoStoreResponses() {
        deny = true; var denied = request(); assertEquals(403, denied.getStatus());
        assertEquals(Map.of("code", "SUSTAINED_FIXATION_SUMMARY_AUTHORIZATION_FORBIDDEN"), denied.getEntity());
        deny = false; failure = new IllegalStateException("private stored JSON");
        var unavailable = request(); assertEquals(503, unavailable.getStatus());
        assertEquals(Map.of("code", "SUSTAINED_FIXATION_SUMMARY_AUTHORIZATION_UNAVAILABLE"), unavailable.getEntity());
        assertEquals("no-store", denied.getHeaderString("Cache-Control")); assertEquals("no-store", unavailable.getHeaderString("Cache-Control"));
    }
    @Test void routeRoleAndDependentProducerAreExplicitWithoutChangingPatientWiring() throws Exception {
        assertEquals("/api/internal/v1/therapy-sessions/{therapySessionId}/sustained-fixation-summary-authorization",
                DoctorSustainedFixationSummaryAuthorizationController.class.getAnnotation(jakarta.ws.rs.Path.class).value());
        assertArrayEquals(new String[]{"DOCTOR"}, DoctorSustainedFixationSummaryAuthorizationController.class.getAnnotation(jakarta.annotation.security.RolesAllowed.class).value());
        assertNotNull(DoctorSustainedFixationSummaryAuthorizationController.class.getMethod("authorize", String.class).getAnnotation(jakarta.ws.rs.GET.class));
        var producer = DoctorSustainedFixationSummaryAuthorizationConfiguration.class.getMethod("doctorSustainedFixationSummaryAuthorization", DoctorVisualSearchSummarySnapshotRepository.class);
        assertNotNull(producer.getAnnotation(jakarta.enterprise.inject.Produces.class));
        assertNotNull(producer.getAnnotation(jakarta.enterprise.context.Dependent.class));
        deny = true; assertThrows(SecurityException.class, () -> new DoctorSustainedFixationSummaryAuthorizationConfiguration()
                .doctorSustainedFixationSummaryAuthorization(snapshots).authorize("synthetic-doctor", new TherapySessionId(SESSION)));
    }
}
