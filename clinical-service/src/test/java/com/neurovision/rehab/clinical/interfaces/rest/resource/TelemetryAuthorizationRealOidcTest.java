package com.neurovision.rehab.clinical.interfaces.rest.resource;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.neurovision.rehab.clinical.application.internal.commandservices.StartTherapySessionCommand;
import com.neurovision.rehab.clinical.application.ports.out.persistence.ClinicalPatientRepository;
import com.neurovision.rehab.clinical.application.ports.out.persistence.DoctorPatientAssignmentRepository;
import com.neurovision.rehab.clinical.application.ports.out.persistence.TherapeuticExerciseConfigurationRepository;
import com.neurovision.rehab.clinical.application.ports.out.persistence.TherapyPlanRepository;
import com.neurovision.rehab.clinical.domain.model.aggregates.ClinicalPatient;
import com.neurovision.rehab.clinical.domain.model.aggregates.DoctorPatientAssignment;
import com.neurovision.rehab.clinical.domain.model.aggregates.TherapyPlan;
import com.neurovision.rehab.clinical.domain.model.valueobjects.*;
import com.neurovision.rehab.clinical.infrastructure.persistence.jpa.transaction.TransactionalTherapySessionStarter;
import com.neurovision.rehab.clinical.support.TherapeuticConfigurationTestData;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.quarkus.test.oidc.server.OidcWiremockTestResource;
import io.smallrye.jwt.build.Jwt;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

@QuarkusTest
@TestProfile(ClinicalRealOidcProfile.class)
@QuarkusTestResource(OidcWiremockTestResource.class)
class TelemetryAuthorizationRealOidcTest {
    private static final ClinicalPatientId PATIENT_ID = new ClinicalPatientId(UUID.randomUUID());
    private static final TherapyPlanId PLAN_ID = new TherapyPlanId(UUID.randomUUID());
    private static final CognitoSubject DOCTOR = new CognitoSubject("doctor-subject");
    private static final String SUBJECT = "real-oidc-patient";
    private static final String UNKNOWN_URL = url(UUID.fromString(
            "92000000-0000-0000-0000-000000000001"));

    @Inject ClinicalPatientRepository patients;
    @Inject DoctorPatientAssignmentRepository assignments;
    @Inject TherapyPlanRepository plans;
    @Inject TherapeuticExerciseConfigurationRepository configurations;
    @Inject TransactionalTherapySessionStarter starter;
    @Inject EntityManager entityManager;

    @BeforeEach
    @AfterEach
    void cleanDatabase() {
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

    @Test
    void permitsOwnedSessionThenDeniesAfterCommittedAssignmentRevocation() throws Exception {
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
        var sessionId = starter.handle(new StartTherapySessionCommand(PATIENT_ID, PLAN_ID,
                new ExerciseCode("visual-search"), new IdempotencyKey("real-oidc-attempt")))
                .session().id().value();

        var accessToken = token("https://server.example.com", "test-client", "access",
                List.of("PATIENT"), Instant.now().plusSeconds(300));
        var body = given().auth().oauth2(accessToken)
                .get(url(sessionId)).then().statusCode(200).extract().asString();
        var json = new ObjectMapper().readTree(body);
        assertEquals(3, json.size());
        assertEquals(1, json.get("contractVersion").asInt());
        assertEquals(true, json.get("permitted").asBoolean());
        assertEquals(sessionId.toString(), json.get("therapySessionId").asText());
        assertFalse(body.contains(SUBJECT));
        assertFalse(body.contains(DOCTOR.value()));

        var assignment = assignments.findActiveByClinicalPatientIdAndDoctorSubject(PATIENT_ID, DOCTOR)
                .orElseThrow();
        assignment.end(Instant.now());
        assignments.save(assignment);
        given().auth().oauth2(accessToken).get(url(sessionId)).then().statusCode(403)
                .body("code", equalTo("TELEMETRY_INGESTION_FORBIDDEN"));
    }

    @Test
    void acceptsValidSignedPatientTokenBeforeApplicationDenial() {
        given().auth().oauth2(token("https://server.example.com", "test-client", "access",
                        List.of("PATIENT"), Instant.now().plusSeconds(300)))
                .get(UNKNOWN_URL).then().statusCode(403)
                .body("code", equalTo("TELEMETRY_INGESTION_FORBIDDEN"));
    }

    @Test
    void rejectsWrongIssuerClientTokenUseExpiredAndWrongRole() {
        given().auth().oauth2(token("https://other.example.com", "test-client", "access",
                List.of("PATIENT"), Instant.now().plusSeconds(300)))
                .get(UNKNOWN_URL).then().statusCode(401);
        given().auth().oauth2(token("https://server.example.com", "other-client", "access",
                List.of("PATIENT"), Instant.now().plusSeconds(300)))
                .get(UNKNOWN_URL).then().statusCode(401);
        given().auth().oauth2(token("https://server.example.com", "test-client", "id",
                List.of("PATIENT"), Instant.now().plusSeconds(300)))
                .get(UNKNOWN_URL).then().statusCode(401);
        given().auth().oauth2(token("https://server.example.com", "test-client", "access",
                List.of("PATIENT"), Instant.now().minusSeconds(30)))
                .get(UNKNOWN_URL).then().statusCode(401);
        given().auth().oauth2(token("https://server.example.com", "test-client", "access",
                List.of("DOCTOR"), Instant.now().plusSeconds(300)))
                .get(UNKNOWN_URL).then().statusCode(403);
    }

    @Test
    void rejectsMalformedAndTamperedSignatures() {
        given().auth().oauth2("not-a-jwt").get(UNKNOWN_URL).then().statusCode(401);
        var signed = token("https://server.example.com", "test-client", "access",
                List.of("PATIENT"), Instant.now().plusSeconds(300));
        var tampered = signed.substring(0, signed.lastIndexOf('.') + 1) + "bad-signature";
        given().auth().oauth2(tampered).get(UNKNOWN_URL).then().statusCode(401);
    }

    private static String url(UUID id) {
        return "/api/internal/v1/therapy-sessions/" + id + "/telemetry-authorization";
    }

    private static String token(String issuer, String client, String use, List<String> groups,
            Instant expiresAt) {
        return Jwt.subject(SUBJECT)
                .issuer(issuer)
                .claim("client_id", client)
                .claim("token_use", use)
                .claim("cognito:groups", groups)
                .expiresAt(expiresAt.getEpochSecond())
                .sign();
    }
}
