package com.neurovision.rehab.clinical.interfaces.rest.resource;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.neurovision.rehab.clinical.application.internal.commandservices.StartTherapySessionCommand;
import com.neurovision.rehab.clinical.application.ports.out.persistence.*;
import com.neurovision.rehab.clinical.domain.model.aggregates.*;
import com.neurovision.rehab.clinical.domain.model.valueobjects.*;
import com.neurovision.rehab.clinical.infrastructure.persistence.jpa.transaction.TransactionalTherapySessionStarter;
import com.neurovision.rehab.clinical.support.TherapeuticConfigurationTestData;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import io.quarkus.test.security.oidc.Claim;
import io.quarkus.test.security.oidc.OidcSecurity;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import java.time.Instant;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

@QuarkusTest
class TelemetryAuthorizationControllerTest {
    private static final ClinicalPatientId PATIENT_ID = new ClinicalPatientId(UUID.randomUUID());
    private static final TherapyPlanId PLAN_ID = new TherapyPlanId(UUID.randomUUID());
    private static final CognitoSubject DOCTOR = new CognitoSubject("telemetry-test-doctor");
    private static final String SUBJECT = "telemetry-test-patient";
    private static final String ROOT = "/api/internal/v1/therapy-sessions/";

    @Inject ClinicalPatientRepository patients;
    @Inject DoctorPatientAssignmentRepository assignments;
    @Inject TherapyPlanRepository plans;
    @Inject TherapeuticExerciseConfigurationRepository configurations;
    @Inject TransactionalTherapySessionStarter starter;
    @Inject EntityManager entityManager;

    private UUID sessionId;

    @BeforeEach
    void seed() {
        clean();
        var createdAt = Instant.now().minusSeconds(3600);
        patients.save(ClinicalPatient.registerInvited(PATIENT_ID,
                new CognitoSubject(SUBJECT), createdAt));
        assignments.save(DoctorPatientAssignment.assign(new DoctorPatientAssignmentId(UUID.randomUUID()),
                PATIENT_ID, DOCTOR, createdAt));
        plans.save(TherapyPlan.rehydrate(PLAN_ID, PATIENT_ID, DOCTOR, TherapyPlanStatus.ACTIVE,
                1, 3, 30, 60, 5, 15, 50, Map.of(new ExerciseCode("visual-search"), 3)));
        QuarkusTransaction.requiringNew().run(() -> configurations.append(
                TherapeuticConfigurationTestData.visualSearchConfiguration(
                        PLAN_ID, UUID.randomUUID(), 1, createdAt.plusSeconds(60))));
        sessionId = starter.handle(new StartTherapySessionCommand(PATIENT_ID, PLAN_ID,
                new ExerciseCode("visual-search"), new IdempotencyKey("rest-attempt")))
                .session().id().value();
    }

    @AfterEach
    void cleanup() { clean(); }

    @Test
    @TestSecurity(user = "patient", roles = "PATIENT")
    @OidcSecurity(claims = @Claim(key = "sub", value = SUBJECT))
    void allowsOwnedActiveSessionWithoutExposingIdentity() throws Exception {
        String body = given().get(url(sessionId)).then().statusCode(200)
                .contentType("application/json").extract().asString();
        assertTrue(body.contains("\"permitted\":true"));
        assertTrue(body.contains(sessionId.toString()));
        assertTrue(body.contains("\"contractVersion\":1"));
        var fields = new ObjectMapper().readTree(body).properties().stream()
                .map(Map.Entry::getKey).collect(java.util.stream.Collectors.toSet());
        assertEquals(Set.of("contractVersion", "permitted", "therapySessionId"), fields);
        assertFalse(body.contains(SUBJECT));
        assertFalse(body.contains(DOCTOR.value()));
    }

    @Test
    @TestSecurity(user = "patient", roles = "PATIENT")
    @OidcSecurity(claims = @Claim(key = "sub", value = "other-subject"))
    void unknownPatientUnknownSessionAndMalformedIdShareOneDenial() {
        var unknownPatient = given().get(url(sessionId)).then().statusCode(403).extract().asString();
        var unknownSession = given().get(url(UUID.randomUUID())).then().statusCode(403).extract().asString();
        var malformedId = given().get(ROOT + "not-a-uuid/telemetry-authorization")
                .then().statusCode(403).extract().asString();
        assertEquals(unknownPatient, unknownSession);
        assertEquals(unknownPatient, malformedId);
        assertEquals("{\"code\":\"TELEMETRY_INGESTION_FORBIDDEN\",\"message\":\"Telemetry ingestion is not permitted\"}",
                unknownPatient);
    }

    @Test
    void rejectsMissingAccessToken() {
        given().get(url(sessionId)).then().statusCode(401);
    }

    @Test
    @TestSecurity(user = "doctor", roles = "DOCTOR")
    @OidcSecurity(claims = @Claim(key = "sub", value = "doctor-subject"))
    void rejectsWrongRole() {
        given().get(url(sessionId)).then().statusCode(403);
    }

    @Test
    @TestSecurity(user = "patient", roles = "PATIENT")
    @OidcSecurity(claims = @Claim(key = "sub", value = SUBJECT))
    void historicalReadAllowsOwnerIncludingAfterSessionEnds() {
        given().get(readUrl(sessionId)).then().statusCode(200).body("permitted", org.hamcrest.Matchers.is(true));
        QuarkusTransaction.requiringNew().run(() -> entityManager.createNativeQuery(
                        "UPDATE therapy_sessions SET status = 'COMPLETED', lease_expires_at = NULL, "
                                + "ended_at = now() WHERE id = :id")
                .setParameter("id", sessionId).executeUpdate());
        given().get(readUrl(sessionId)).then().statusCode(200).body("permitted", org.hamcrest.Matchers.is(true));
    }

    @Test
    @TestSecurity(user = "doctor", roles = "DOCTOR")
    @OidcSecurity(claims = @Claim(key = "sub", value = "telemetry-test-doctor"))
    void historicalReadAllowsAssignedDoctorButNotAfterRevocation() {
        given().get(readUrl(sessionId)).then().statusCode(200);
        QuarkusTransaction.requiringNew().run(() -> entityManager.createNativeQuery(
                        "UPDATE doctor_patient_assignments SET status = 'ENDED', ended_at = now() "
                                + "WHERE doctor_cognito_sub = :doctor")
                .setParameter("doctor", DOCTOR.value()).executeUpdate());
        given().get(readUrl(sessionId)).then().statusCode(403);
    }

    @Test
    @TestSecurity(user = "patient", roles = "PATIENT")
    @OidcSecurity(claims = @Claim(key = "sub", value = "other-subject"))
    void historicalReadUsesGenericDenialForUnknownOrMalformedSession() {
        var denied = given().get(readUrl(sessionId)).then().statusCode(403).extract().asString();
        assertEquals(denied, given().get(readUrl(UUID.randomUUID())).then().statusCode(403)
                .extract().asString());
        assertEquals(denied, given().get(ROOT + "invalid/telemetry-read-authorization")
                .then().statusCode(403).extract().asString());
    }

    @Test
    @TestSecurity(user = "dual", roles = {"PATIENT", "DOCTOR"})
    @OidcSecurity(claims = @Claim(key = "sub", value = SUBJECT))
    void historicalReadRejectsAmbiguousDualRole() {
        given().get(readUrl(sessionId)).then().statusCode(403);
    }

    private static String url(UUID id) { return ROOT + id + "/telemetry-authorization"; }
    private static String readUrl(UUID id) { return ROOT + id + "/telemetry-read-authorization"; }

    private void clean() {
        QuarkusTransaction.requiringNew().run(() -> {
            entityManager.createNativeQuery("TRUNCATE TABLE therapy_sessions, therapeutic_exercise_configurations, "
                    + "therapy_plan_revision_exercises, therapy_plan_revisions").executeUpdate();
            entityManager.createNativeQuery("DELETE FROM therapy_plan_exercise_prescriptions").executeUpdate();
            entityManager.createNativeQuery("DELETE FROM therapy_plans").executeUpdate();
            entityManager.createNativeQuery("DELETE FROM inbox_events").executeUpdate();
            entityManager.createNativeQuery("DELETE FROM doctor_patient_assignments").executeUpdate();
            entityManager.createNativeQuery("DELETE FROM clinical_patients").executeUpdate();
        });
    }
}
