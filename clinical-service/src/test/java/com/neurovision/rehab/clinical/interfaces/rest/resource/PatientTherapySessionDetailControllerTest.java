package com.neurovision.rehab.clinical.interfaces.rest.resource;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.neurovision.rehab.clinical.application.internal.queryservices.GetPatientTherapySessionQueryServiceTest.Fixture;
import com.neurovision.rehab.clinical.domain.model.valueobjects.TherapySessionStatus;
import io.quarkus.oidc.AccessTokenCredential;
import io.quarkus.security.identity.SecurityIdentity;
import jakarta.annotation.security.RolesAllowed;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.Response;
import java.util.*;
import org.eclipse.microprofile.jwt.JsonWebToken;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static com.neurovision.rehab.clinical.application.internal.queryservices.GetPatientCurrentSessionQueryServiceTest.readOnly;

class PatientTherapySessionDetailControllerTest {
    final Fixture f = new Fixture();
    Set<String> roles = Set.of("PATIENT");
    boolean anonymous, nonJwt, missingCredential, blankCredential;
    String subject = f.frozen.subject.value();
    final ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule()).disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    final JsonWebToken jwt = readOnly(JsonWebToken.class, (name, args) -> {
        if (name.equals("getSubject")) return subject; throw new AssertionError("Unexpected JWT operation");
    });
    final SecurityIdentity identity = readOnly(SecurityIdentity.class, (name, args) -> switch (name) {
        case "isAnonymous" -> anonymous;
        case "hasRole" -> roles.contains(args[0]);
        case "getPrincipal" -> nonJwt ? (java.security.Principal) () -> "synthetic" : jwt;
        case "getCredential" -> missingCredential ? null : new AccessTokenCredential(blankCredential ? " " : "synthetic-local-token");
        default -> throw new AssertionError("Unexpected identity operation");
    });
    PatientTherapySessionDetailController controller() { return new PatientTherapySessionDetailController(f.service(), identity, mapper); }
    Response get() { return controller().detail(f.frozen.session.id().value().toString()); }
    void error(Response response, int status, String code) {
        assertEquals(status, response.getStatus()); assertEquals("no-store", response.getHeaderString("Cache-Control"));
        assertEquals("application/json", response.getMediaType().toString());
        assertEquals(Map.of("code", "PATIENT_SESSION_DETAIL_" + code), response.getEntity());
        assertFalse(response.getEntity().toString().contains("synthetic"));
    }
    @Test void exposesFrozenFactsAndNullLegacyProgressWithoutPrivateFields() {
        var response = get(); assertEquals(200, response.getStatus()); assertEquals("no-store", response.getHeaderString("Cache-Control"));
        var body = mapper.valueToTree(response.getEntity()); var s = f.detail.session();
        assertEquals(Set.of("therapySessionId", "therapyPlanId", "exerciseCode", "status", "startedAt", "endedAt",
                "activeDurationSeconds", "quotaDate", "configurationId", "configurationVersion", "parameterSchemaVersion",
                "parameters", "sessionLimits", "technicalProgress"), properties(body));
        assertEquals(s.therapySessionId().toString(), body.path("therapySessionId").asText());
        assertEquals(s.therapyPlanId().toString(), body.path("therapyPlanId").asText());
        assertEquals(s.configurationId().toString(), body.path("configurationId").asText());
        assertEquals(s.exerciseCode(), body.path("exerciseCode").asText());
        assertEquals(s.status(), body.path("status").asText());
        assertEquals(s.startedAt().toString(), body.path("startedAt").asText()); assertTrue(body.path("endedAt").isNull());
        assertEquals(s.activeDurationSeconds(), body.path("activeDurationSeconds").asLong());
        assertEquals(s.quotaDate().toString(), body.path("quotaDate").asText());
        assertEquals(s.configurationVersion(), body.path("configurationVersion").asLong());
        assertEquals(s.parameterSchemaVersion(), body.path("parameterSchemaVersion").asInt());
        assertEquals(mapper.valueToTree(f.detail.configurationParameters()), body.path("parameters"));
        assertEquals(3, body.path("sessionLimits").size());
        assertEquals(f.detail.sessionLimits().minimumValidDurationMinutes(), body.path("sessionLimits").path("minimumValidDurationMinutes").asInt());
        assertEquals(f.detail.sessionLimits().maximumSessionDurationMinutes(), body.path("sessionLimits").path("maximumSessionDurationMinutes").asInt());
        assertEquals(f.detail.sessionLimits().minimumValidSampleCount(), body.path("sessionLimits").path("minimumValidSampleCount").asLong());
        assertTrue(body.has("technicalProgress")); assertTrue(body.path("technicalProgress").isNull());
        assertFalse(body.toString().contains("synthetic")); assertFalse(response.getEntity().toString().contains(s.therapySessionId().toString()));
    }
    @Test void terminalPrescribedSessionIncludesOnlyFrozenTechnicalAccounting() {
        f.detail = f.detail(TherapySessionStatus.COMPLETED, true, true);
        var response = get(); assertEquals(200, response.getStatus());
        var body = mapper.valueToTree(response.getEntity());
        assertEquals("COMPLETED", body.path("status").asText()); assertEquals(f.detail.session().endedAt().toString(), body.path("endedAt").asText());
        var p = body.path("technicalProgress");
        assertEquals(Set.of("criterion", "target", "activeMilliseconds", "completedRepetitions", "targetReached"), properties(p));
        assertEquals("REPETITIONS", p.path("criterion").asText()); assertEquals(5, p.path("target").asLong());
        assertEquals(2000, p.path("activeMilliseconds").asLong()); assertEquals(5, p.path("completedRepetitions").asLong()); assertTrue(p.path("targetReached").asBoolean());
    }
    static Set<String> properties(com.fasterxml.jackson.databind.JsonNode node) {
        var names = new HashSet<String>(); node.fieldNames().forEachRemaining(names::add); return names;
    }
    @Test void deniesWrongAndMixedRolesAndMalformedSubjectsBeforeReads() {
        for (var denied : List.of(Set.<String>of(), Set.of("DOCTOR"), Set.of("ADMIN"), Set.of("PATIENT", "DOCTOR"), Set.of("PATIENT", "ADMIN"))) {
            roles = denied; error(get(), 403, "FORBIDDEN");
        }
        roles = Set.of("PATIENT"); nonJwt = true; error(get(), 403, "FORBIDDEN"); nonJwt = false;
        for (String invalid : Arrays.asList(null, "", " ", " synthetic-patient ")) {
            subject = invalid; error(get(), 403, "FORBIDDEN");
        }
        assertTrue(f.calls.isEmpty());
    }
    @Test void unauthenticatedFailuresIncludeBearerAndDoNotReadPersistence() {
        anonymous = true; var response = get(); error(response, 401, "UNAUTHENTICATED");
        assertEquals("Bearer", response.getHeaderString("WWW-Authenticate")); anonymous = false;
        missingCredential = true; response = get(); error(response, 401, "UNAUTHENTICATED");
        assertEquals("Bearer", response.getHeaderString("WWW-Authenticate")); missingCredential = false;
        blankCredential = true; response = get(); error(response, 401, "UNAUTHENTICATED");
        assertEquals("Bearer", response.getHeaderString("WWW-Authenticate")); assertTrue(f.calls.isEmpty());
    }
    @Test void invalidIdentifiersAre400BeforeReads() {
        for (String id : Arrays.asList(null, "", "not-a-uuid", "1-1-1-1-1", " " + f.frozen.session.id().value()))
            error(controller().detail(id), 400, "INVALID_REQUEST");
        assertTrue(f.calls.isEmpty());
    }
    @Test void missingForeignAndUnassignedSessionsShareSafeDenial() {
        f.registered = false; error(get(), 403, "FORBIDDEN"); f.registered = true;
        var saved = f.source; f.source = null; error(get(), 403, "FORBIDDEN"); f.source = saved;
        f.detail = null; error(get(), 403, "FORBIDDEN");
    }
    @Test void readAndRepresentationFailuresAre503WithoutPrivateCauses() {
        for (String read : List.of("patient", "session", "detail")) { f.failedRead = read; error(get(), 503, "UNAVAILABLE"); }
        f.failedRead = null;
        ObjectMapper broken = new ObjectMapper() {
            @Override public <T extends com.fasterxml.jackson.databind.JsonNode> T valueToTree(Object value) { throw new IllegalArgumentException("synthetic private failure"); }
        };
        error(new PatientTherapySessionDetailController(f.service(), identity, broken).detail(f.frozen.session.id().value().toString()), 503, "UNAVAILABLE");
    }
    @Test void routeAndProducerAreDistinctFromCurrentAndProgressRoutes() throws Exception {
        assertEquals("/api/v1/me/therapy-sessions/{therapySessionId}", PatientTherapySessionDetailController.class.getAnnotation(Path.class).value());
        assertArrayEquals(new String[]{"PATIENT"}, PatientTherapySessionDetailController.class.getAnnotation(RolesAllowed.class).value());
        assertNotNull(PatientTherapySessionDetailController.class.getMethod("detail", String.class).getAnnotation(GET.class));
        var producer = com.neurovision.rehab.clinical.infrastructure.configuration.PatientTherapySessionDetailConfiguration.class
                .getMethod("patientTherapySessionDetail", com.neurovision.rehab.clinical.application.ports.out.persistence.ClinicalPatientRepository.class,
                        com.neurovision.rehab.clinical.application.ports.out.persistence.TherapySessionRepository.class,
                        com.neurovision.rehab.clinical.application.ports.out.persistence.DoctorTherapySessionReadRepository.class);
        assertNotNull(producer.getAnnotation(jakarta.enterprise.inject.Produces.class));
        assertNotNull(producer.getAnnotation(jakarta.enterprise.context.Dependent.class));
        assertEquals(com.neurovision.rehab.clinical.application.internal.queryservices.GetPatientTherapySessionQueryService.class, producer.getReturnType());
    }
}
