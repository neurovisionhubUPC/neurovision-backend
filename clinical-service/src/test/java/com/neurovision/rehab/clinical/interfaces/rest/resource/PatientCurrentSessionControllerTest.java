package com.neurovision.rehab.clinical.interfaces.rest.resource;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.neurovision.rehab.clinical.application.internal.queryservices.GetPatientCurrentSessionQueryServiceTest.Fixture;
import com.neurovision.rehab.clinical.domain.model.valueobjects.*;
import io.quarkus.oidc.AccessTokenCredential;
import io.quarkus.security.identity.SecurityIdentity;
import jakarta.annotation.security.RolesAllowed;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.Response;
import java.util.*;
import org.eclipse.microprofile.jwt.JsonWebToken;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static com.neurovision.rehab.clinical.application.internal.queryservices.GetPatientCurrentSessionQueryServiceTest.*;

class PatientCurrentSessionControllerTest {
    final Fixture f = new Fixture();
    Set<String> roles = Set.of("PATIENT"); boolean anonymous, nonJwt, missingCredential, blankCredential;
    String subject = f.subject.value();
    final ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule()).disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    final JsonWebToken jwt = readOnly(JsonWebToken.class, (name, args) -> {
        if (name.equals("getSubject")) return subject; throw new AssertionError("Unexpected JWT operation");
    });
    final SecurityIdentity identity = readOnly(SecurityIdentity.class, (name, args) -> switch (name) {
        case "isAnonymous" -> anonymous;
        case "hasRole" -> roles.contains(args[0]);
        case "getPrincipal" -> nonJwt ? (java.security.Principal) () -> "synthetic-principal" : jwt;
        case "getCredential" -> missingCredential ? null : new AccessTokenCredential(blankCredential ? " " : "synthetic-local-token");
        default -> throw new AssertionError("Unexpected identity operation");
    });
    PatientCurrentSessionController controller() { return new PatientCurrentSessionController(f.service(), identity, mapper); }
    void error(Response response, int status, String code) {
        assertEquals(status, response.getStatus()); assertEquals("no-store", response.getHeaderString("Cache-Control"));
        assertEquals(Map.of("code", "PATIENT_CURRENT_SESSION_" + code), response.getEntity());
        assertFalse(response.getEntity().toString().contains("synthetic"));
    }

    @Test void mapsThreeStatesUsingExistingSessionPayloadAndOneEvaluationInstant() {
        for (String state : List.of("ACTIVE", "EXPIRED", "NONE")) {
            f.session = state.equals("NONE") ? null : f.session(f.patientId, TherapySessionStatus.ACTIVE,
                    state.equals("EXPIRED") ? NOW : NOW.plusSeconds(60), state.equals("ACTIVE"));
            Response response = controller().current(); assertEquals(200, response.getStatus());
            assertEquals("no-store", response.getHeaderString("Cache-Control"));
            var body = mapper.valueToTree(response.getEntity()); assertEquals(3, body.size());
            assertEquals(state, body.path("state").asText()); assertEquals(NOW.toString(), body.path("evaluatedAt").asText());
            if (state.equals("NONE")) assertTrue(body.path("session").isNull());
            else {
                var session = body.path("session");
                assertEquals(14, session.size()); assertEquals("ACTIVE", session.path("status").asText());
                assertEquals(f.session.id().value().toString(), session.path("therapySessionId").asText());
                assertEquals(f.session.therapyPlanId().value().toString(), session.path("therapyPlanId").asText());
                assertEquals(f.session.therapeuticConfigurationId().value().toString(), session.path("configurationId").asText());
                assertEquals(f.session.configurationVersion(), session.path("configurationVersion").asLong());
                assertEquals(f.session.parameterSchemaVersion(), session.path("parameterSchemaVersion").asInt());
                assertEquals(mapper.valueToTree(f.session.configurationParameters()), session.path("parameters"));
                assertEquals(f.session.exerciseCode().value(), session.path("exerciseCode").asText());
                assertEquals(f.session.quotaDate().toString(), session.path("quotaDate").asText());
                assertEquals(f.session.minimumValidDurationMinutes(), session.path("minimumValidDurationMinutes").asInt());
                assertEquals(f.session.maximumSessionDurationMinutes(), session.path("maximumSessionDurationMinutes").asInt());
                assertEquals(f.session.minimumValidSampleCount(), session.path("minimumValidSampleCount").asLong());
                assertEquals(f.session.startedAt().toString(), session.path("startedAt").asText());
                assertEquals(f.session.leaseExpiresAt().orElseThrow().toString(), session.path("leaseExpiresAt").asText());
                assertFalse(body.toString().contains("synthetic-current-patient")); assertFalse(body.toString().contains("synthetic-local-token"));
            }
        }
    }

    @Test void deniesWrongAndMixedRolesAndInvalidPrincipalsBeforePersistence() {
        for (Set<String> denied : List.of(Set.<String>of(), Set.of("DOCTOR"), Set.of("ADMIN"),
                Set.of("PATIENT", "DOCTOR"), Set.of("PATIENT", "ADMIN"))) {
            roles = denied; error(controller().current(), 403, "FORBIDDEN");
        }
        roles = Set.of("PATIENT"); nonJwt = true; error(controller().current(), 403, "FORBIDDEN");
        nonJwt = false;
        for (String invalid : Arrays.asList(null, "", " ", " synthetic-current-patient ")) {
            subject = invalid; error(controller().current(), 403, "FORBIDDEN");
        }
        assertEquals(0, f.patientReads);
    }

    @Test void anonymousAndMissingOrBlankCredentialReturnBearerWithoutReads() {
        anonymous = true; Response response = controller().current(); error(response, 401, "UNAUTHENTICATED");
        assertEquals("Bearer", response.getHeaderString("WWW-Authenticate")); anonymous = false;
        missingCredential = true; response = controller().current(); error(response, 401, "UNAUTHENTICATED");
        assertEquals("Bearer", response.getHeaderString("WWW-Authenticate")); missingCredential = false;
        blankCredential = true; error(controller().current(), 401, "UNAUTHENTICATED"); assertEquals(0, f.patientReads);
    }

    @Test void deniedBindingIs403AndAllReadFailuresAreSanitized503() {
        f.registered = false; error(controller().current(), 403, "FORBIDDEN"); f.registered = true;
        f.assigned = false; error(controller().current(), 403, "FORBIDDEN"); f.assigned = true;
        for (String stage : List.of("patient", "session", "assignment")) {
            f.failedRead = stage; error(controller().current(), 503, "UNAVAILABLE");
        }
    }

    @Test void serializationFailureDoesNotReturnPartialOrEmptySuccess() {
        ObjectMapper unavailable = new ObjectMapper() {
            @Override public <T extends com.fasterxml.jackson.databind.JsonNode> T valueToTree(Object value) {
                throw new IllegalArgumentException("synthetic serialization cause");
            }
        };
        error(new PatientCurrentSessionController(f.service(), identity, unavailable).current(), 503, "UNAVAILABLE");
    }

    @Test void routeAndRoleAnnotationsDoNotChangeExistingControllers() throws Exception {
        assertEquals("/api/v1/me/therapy-sessions/current", PatientCurrentSessionController.class.getAnnotation(Path.class).value());
        assertArrayEquals(new String[]{"PATIENT"}, PatientCurrentSessionController.class.getAnnotation(RolesAllowed.class).value());
        assertNotNull(PatientCurrentSessionController.class.getMethod("current").getAnnotation(GET.class));
        var producer = com.neurovision.rehab.clinical.infrastructure.configuration.PatientCurrentSessionConfiguration.class
                .getMethod("patientCurrentSession",
                        com.neurovision.rehab.clinical.application.ports.out.persistence.ClinicalPatientRepository.class,
                        com.neurovision.rehab.clinical.application.ports.out.persistence.TherapySessionRepository.class,
                        com.neurovision.rehab.clinical.application.ports.out.persistence.DoctorPatientAssignmentRepository.class);
        assertNotNull(producer.getAnnotation(jakarta.enterprise.inject.Produces.class));
        assertNotNull(producer.getAnnotation(jakarta.enterprise.context.Dependent.class));
        assertEquals(com.neurovision.rehab.clinical.application.internal.queryservices.GetPatientCurrentSessionQueryService.class,
                producer.getReturnType());
    }
}
