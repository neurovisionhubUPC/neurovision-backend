package com.neurovision.rehab.clinical.interfaces.rest.resource;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;
import static org.junit.jupiter.api.Assertions.assertEquals;

import com.neurovision.rehab.clinical.application.ports.out.persistence.ClinicalPatientRepository;
import com.neurovision.rehab.clinical.application.ports.out.persistence.DoctorPatientAssignmentRepository;
import com.neurovision.rehab.clinical.domain.model.aggregates.ClinicalPatient;
import com.neurovision.rehab.clinical.domain.model.aggregates.DoctorPatientAssignment;
import com.neurovision.rehab.clinical.domain.model.valueobjects.*;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import io.quarkus.test.security.oidc.Claim;
import io.quarkus.test.security.oidc.OidcSecurity;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

@QuarkusTest
@io.quarkus.test.junit.TestProfile(DoctorTherapyPlanControllerTest.EligibleProfile.class)
class DoctorTherapyPlanControllerTest {
    // This DB/API suite isolates assignment/configuration behavior. Eligibility itself has
    // fake-only adapter/gate tests; no Profile network dependency is introduced here.
    public static class EligibleProfile implements io.quarkus.test.junit.QuarkusTestProfile {
        @Override public java.util.Set<Class<?>> getEnabledAlternatives() { return java.util.Set.of(EligibleDoctor.class); }
    }
    @jakarta.enterprise.inject.Alternative
    @jakarta.enterprise.context.ApplicationScoped
    public static class EligibleDoctor implements com.neurovision.rehab.clinical.application.ports.out.authorization.DoctorSchedulingEligibilityPort {
        @Override public void requireEligible(CognitoSubject doctor) { java.util.Objects.requireNonNull(doctor); }
    }

    private static final String DOCTOR = "doctor-plan-api-test";
    private static final String PATIENT = "patient-plan-api-test";
    private static final ClinicalPatientId PATIENT_ID = new ClinicalPatientId(UUID.randomUUID());

    @Inject ClinicalPatientRepository patients;
    @Inject DoctorPatientAssignmentRepository assignments;
    @Inject EntityManager entityManager;

    @BeforeEach
    void seed() {
        clean();
        var createdAt = Instant.now().minusSeconds(3600);
        patients.save(ClinicalPatient.registerInvited(PATIENT_ID, new CognitoSubject(PATIENT), createdAt));
        assignments.save(DoctorPatientAssignment.assign(new DoctorPatientAssignmentId(UUID.randomUUID()),
                PATIENT_ID, new CognitoSubject(DOCTOR), createdAt));
    }

    @AfterEach
    void cleanup() { clean(); }

    @Test
    @TestSecurity(user = "doctor", roles = "DOCTOR")
    @OidcSecurity(claims = @Claim(key = "sub", value = DOCTOR))
    void createsAssignedPlanAndAppendsIndependentVersions() {
        var planId = createPlan();
        given().contentType("application/json").body(fixationRequest())
                .post(configurationsUrl(planId)).then().statusCode(201)
                .body("exerciseCode", is("fixation"), "version", is(1),
                        "parameterSchemaVersion", is(1), "status", is("ACTIVE"));
        given().contentType("application/json").body(fixationRequest())
                .post(configurationsUrl(planId)).then().statusCode(201)
                .body("exerciseCode", is("fixation"), "version", is(2));
        given().contentType("application/json").body(searchRequest())
                .post(configurationsUrl(planId)).then().statusCode(201)
                .body("exerciseCode", is("visual-search"), "version", is(1));
        given().contentType("application/json").body(scanningRequest())
                .post(configurationsUrl(planId)).then().statusCode(201)
                .body("exerciseCode", is("visual-scanning"), "version", is(1));
    }

    @Test
    void rejectsAnonymousCaller() {
        given().contentType("application/json").body(planRequest()).post(plansUrl()).then().statusCode(401);
    }

    @Test
    @TestSecurity(user = "patient", roles = "PATIENT")
    @OidcSecurity(claims = @Claim(key = "sub", value = PATIENT))
    void rejectsPatientCaller() {
        given().contentType("application/json").body(planRequest()).post(plansUrl()).then().statusCode(403);
    }

    @Test
    @TestSecurity(user = "dual", roles = {"DOCTOR", "PATIENT"})
    @OidcSecurity(claims = @Claim(key = "sub", value = DOCTOR))
    void rejectsAmbiguousDualRole() {
        given().contentType("application/json").body(planRequest()).post(plansUrl()).then().statusCode(403);
    }

    @Test
    @TestSecurity(user = "other", roles = "DOCTOR")
    @OidcSecurity(claims = @Claim(key = "sub", value = "unassigned-doctor"))
    void deniesUnassignedDoctorAndUnknownPatientIdentically() {
        var denied = given().contentType("application/json").body(planRequest())
                .post(plansUrl()).then().statusCode(403).extract().asString();
        var unknown = given().contentType("application/json").body(planRequest())
                .post("/api/v1/clinical-patients/" + UUID.randomUUID() + "/therapy-plans")
                .then().statusCode(403).extract().asString();
        assertEquals(denied, unknown);
    }

    @Test
    @TestSecurity(user = "doctor", roles = "DOCTOR")
    @OidcSecurity(claims = @Claim(key = "sub", value = DOCTOR))
    void rejectsInvalidPlanAndConfigurationPayloads() {
        given().contentType("application/json")
                .body(planRequest().replace("\"fixation\":1", "\"unsupported\":1"))
                .post(plansUrl()).then().statusCode(400);
        var planId = createPlan();
        given().contentType("application/json")
                .body(fixationRequest().replace("\"targetSizePx\":70", "\"targetSizePx\":\"70\""))
                .post(configurationsUrl(planId)).then().statusCode(400);
        given().contentType("application/json")
                .body(fixationRequest().replace("\"difficultyLevel\":1", "\"unexpected\":1"))
                .post(configurationsUrl(planId)).then().statusCode(400);
    }

    @Test
    @TestSecurity(user = "doctor", roles = "DOCTOR")
    @OidcSecurity(claims = @Claim(key = "sub", value = DOCTOR))
    void unknownPlanAndMismatchedPatientHaveSameDenial() {
        var planId = createPlan();
        var denied = given().contentType("application/json").body(fixationRequest())
                .post(configurationsUrl(UUID.randomUUID())).then().statusCode(403).extract().asString();
        var wrongPatient = given().contentType("application/json").body(fixationRequest())
                .post("/api/v1/clinical-patients/" + UUID.randomUUID() + "/therapy-plans/"
                        + planId + "/configurations")
                .then().statusCode(403).extract().asString();
        assertEquals(denied, wrongPatient);
    }

    private static UUID createPlan() {
        String id = given().contentType("application/json").body(planRequest()).post(plansUrl())
                .then().statusCode(201).body("clinicalPatientId", is(PATIENT_ID.value().toString()),
                        "dailyExercisePrescriptions.fixation", is(1), "status", is("ACTIVE"))
                .extract().path("therapyPlanId");
        return UUID.fromString(id);
    }

    private static String plansUrl() {
        return "/api/v1/clinical-patients/" + PATIENT_ID.value() + "/therapy-plans";
    }

    private static String configurationsUrl(UUID planId) { return plansUrl() + "/" + planId + "/configurations"; }

    private static String planRequest() {
        return """
                {"dailySessionLimit":3,"dailyTargetDurationMinutes":30,"dailyHardSafetyLimitMinutes":60,
                 "minimumValidDurationMinutes":5,"maximumSessionDurationMinutes":15,
                 "minimumValidSampleCount":50,
                 "dailyExercisePrescriptions":{"fixation":1,"visual-search":1,"visual-scanning":1}}
                """;
    }

    private static String fixationRequest() {
        return """
                {"exerciseCode":"fixation","parameterSchemaVersion":1,"effectiveFrom":"2099-01-01T00:00:00Z",
                 "parameters":{"mode":"SUSTAINED_TARGET_FIXATION","targetPosition":"CENTER",
                 "minimumFixationMs":1000,"maximumFixationMs":3000,"targetSizePx":70,
                 "repetitions":10,"restBetweenRepetitionsMs":1000,"difficultyLevel":1}}
                """;
    }

    private static String searchRequest() {
        return """
                {"exerciseCode":"visual-search","parameterSchemaVersion":1,"effectiveFrom":"2099-01-01T00:00:00Z",
                 "parameters":{"mode":"TARGET_SEARCH_WITH_DISTRACTORS","affectedHemifield":"LEFT",
                 "targetCount":15,"distractorCount":8,"minimumFixationMs":700,
                 "maximumSearchTimeMs":10000,"affectedHemifieldBias":0.7,"targetSizePx":70,
                 "difficultyLevel":2}}
                """;
    }

    private static String scanningRequest() {
        return """
                {"exerciseCode":"visual-scanning","parameterSchemaVersion":1,"effectiveFrom":"2099-01-01T00:00:00Z",
                 "parameters":{"mode":"STANDARD_SCANNING","affectedHemifield":"LEFT",
                 "minimumFixationMs":700,"maximumStimulusDurationMs":5000,"eccentricityDegrees":15,
                 "targetSizePx":70,"repetitions":20,"interStimulusIntervalMs":1000,"difficultyLevel":2}}
                """;
    }

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
