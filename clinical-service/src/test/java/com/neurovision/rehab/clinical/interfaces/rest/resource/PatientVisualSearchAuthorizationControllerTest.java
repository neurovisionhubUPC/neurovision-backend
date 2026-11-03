package com.neurovision.rehab.clinical.interfaces.rest.resource;

import static org.junit.jupiter.api.Assertions.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.neurovision.rehab.clinical.application.internal.queryservices.AuthorizeVisualSearchSessionQueryService;
import com.neurovision.rehab.clinical.application.ports.out.authorization.VisualSearchAuthorizationSnapshotRepository;
import com.neurovision.rehab.clinical.domain.model.valueobjects.*;
import com.neurovision.rehab.clinical.infrastructure.configuration.VisualSearchAuthorizationConfiguration;
import io.quarkus.oidc.AccessTokenCredential;
import io.quarkus.security.runtime.QuarkusSecurityIdentity;
import jakarta.ws.rs.core.Response;
import java.lang.reflect.Proxy;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import org.eclipse.microprofile.jwt.JsonWebToken;
import org.junit.jupiter.api.Test;

class PatientVisualSearchAuthorizationControllerTest {
    static final UUID SESSION = new UUID(0, 1), CONFIG = new UUID(0, 2);
    static final Instant NOW = Instant.parse("2026-10-31T00:00:00Z");
    int reads; boolean deny, closed; RuntimeException failure;
    final VisualSearchAuthorizationSnapshotRepository snapshots = (subject, session) -> {
        reads++; assertEquals("synthetic-patient", subject.value()); assertEquals(SESSION, session.value());
        if (failure != null) throw failure;
        if (deny) return Optional.empty();
        var parameters = new TherapeuticExerciseParameters.VisualSearch(TherapeuticExerciseParameters.VisualSearchMode.TARGET_SEARCH_WITH_DISTRACTORS,
                TherapeuticExerciseParameters.AffectedHemifield.LEFT, 5, 2, 500, 3000, new BigDecimal("0.5"), 50, 1);
        return Optional.of(new VisualSearchAuthorizationSnapshotRepository.Snapshot(session, closed ? TherapySessionStatus.COMPLETED : TherapySessionStatus.ACTIVE,
                closed ? null : NOW.plusSeconds(1), new TherapeuticConfigurationSnapshot(new TherapeuticConfigurationId(CONFIG), 3,
                TherapeuticExercise.VISUAL_SEARCH, 1, parameters)));
    };
    PatientVisualSearchAuthorizationController controller(Set<String> roles, String subject, boolean credential) {
        JsonWebToken jwt = (JsonWebToken) Proxy.newProxyInstance(JsonWebToken.class.getClassLoader(), new Class<?>[]{JsonWebToken.class},
                (p, method, args) -> switch (method.getName()) { case "getSubject", "getName" -> subject; default -> null; });
        var identity = QuarkusSecurityIdentity.builder().setPrincipal(jwt).addRoles(roles);
        if (credential) identity.addCredential(new AccessTokenCredential("synthetic-original-token"));
        return new PatientVisualSearchAuthorizationController(new AuthorizeVisualSearchSessionQueryService(snapshots, Clock.fixed(NOW, ZoneOffset.UTC)), identity.build());
    }
    Response request() { return controller(Set.of("PATIENT"), "synthetic-patient", true).authorize(SESSION.toString()); }

    @Test void returnsOnlyTheSmallFrozenPolicyWithNoStoreForActiveAndClosedSessions() {
        var mapper = new ObjectMapper();
        for (boolean isClosed : List.of(false, true)) {
            closed = isClosed; var response = request(); assertEquals(200, response.getStatus());
            assertEquals("no-store", response.getHeaderString("Cache-Control"));
            var json = mapper.valueToTree(response.getEntity());
            assertEquals(8, json.size()); assertEquals(1, json.path("contractVersion").asInt());
            assertTrue(json.path("permitted").asBoolean()); assertEquals(!closed, json.path("mayInsert").asBoolean());
            assertEquals(SESSION.toString(), json.path("therapySessionId").asText());
            assertEquals(CONFIG.toString(), json.path("configurationId").asText()); assertEquals(3, json.path("configurationVersion").asLong());
            assertEquals(500, json.path("minimumFixationMs").asInt()); assertEquals(3000, json.path("maximumSearchTimeMs").asInt());
            assertFalse(json.toString().contains("synthetic-patient")); assertFalse(json.toString().contains("synthetic-original-token"));
        }
    }

    @Test void roleCredentialSubjectAndCanonicalIdRejectionsDoNotReadPersistence() {
        for (Set<String> roles : List.of(Set.<String>of(), Set.of("DOCTOR"), Set.of("ADMIN"), Set.of("PATIENT", "DOCTOR"), Set.of("PATIENT", "ADMIN")))
            assertEquals(403, controller(roles, "synthetic-patient", true).authorize(SESSION.toString()).getStatus());
        var absent = controller(Set.of("PATIENT"), "synthetic-patient", false).authorize(SESSION.toString());
        assertEquals(401, absent.getStatus()); assertEquals("Bearer", absent.getHeaderString("WWW-Authenticate"));
        assertEquals(401, new PatientVisualSearchAuthorizationController(null, null).authorize(SESSION.toString()).getStatus());
        assertEquals(403, controller(Set.of("PATIENT"), " synthetic-patient ", true).authorize(SESSION.toString()).getStatus());
        for (String invalid : Arrays.asList(null, "bad", "1-1-1-1-1", "00000000-0000-0000-0000-00000000000A"))
            assertEquals(400, controller(Set.of("PATIENT"), "synthetic-patient", true).authorize(invalid).getStatus());
        assertEquals(0, reads);
    }

    @Test void errorsAreGenericAndDependencyFailuresDoNotBecomeDenialsOrPermits() {
        deny = true; var denied = request(); assertEquals(403, denied.getStatus());
        assertEquals(Map.of("code", "VISUAL_SEARCH_AUTHORIZATION_FORBIDDEN"), denied.getEntity());
        deny = false; failure = new IllegalArgumentException("private stored JSON");
        var unavailable = request(); assertEquals(503, unavailable.getStatus());
        assertEquals(Map.of("code", "VISUAL_SEARCH_AUTHORIZATION_UNAVAILABLE"), unavailable.getEntity());
        assertEquals("no-store", unavailable.getHeaderString("Cache-Control"));
    }

    @Test void routeRoleAndProducerAreExplicitWithoutChangingExistingWiring() throws Exception {
        assertEquals("/api/internal/v1/therapy-sessions/{therapySessionId}/visual-search-result-authorization",
                PatientVisualSearchAuthorizationController.class.getAnnotation(jakarta.ws.rs.Path.class).value());
        assertArrayEquals(new String[]{"PATIENT"}, PatientVisualSearchAuthorizationController.class.getAnnotation(jakarta.annotation.security.RolesAllowed.class).value());
        assertNotNull(PatientVisualSearchAuthorizationController.class.getMethod("authorize", String.class).getAnnotation(jakarta.ws.rs.GET.class));
        var producer = VisualSearchAuthorizationConfiguration.class.getMethod("visualSearchAuthorization", VisualSearchAuthorizationSnapshotRepository.class);
        assertNotNull(producer.getAnnotation(jakarta.enterprise.inject.Produces.class));
        assertNotNull(producer.getAnnotation(jakarta.enterprise.context.ApplicationScoped.class));
        deny = true;
        assertThrows(SecurityException.class, () -> new VisualSearchAuthorizationConfiguration().visualSearchAuthorization(snapshots)
                .authorize("synthetic-patient", new TherapySessionId(SESSION)));
    }
}
