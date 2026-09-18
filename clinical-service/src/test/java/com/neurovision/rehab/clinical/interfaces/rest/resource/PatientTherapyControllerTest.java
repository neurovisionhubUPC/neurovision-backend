package com.neurovision.rehab.clinical.interfaces.rest.resource;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;
import static org.junit.jupiter.api.Assertions.assertEquals;

import com.neurovision.rehab.clinical.application.ports.out.persistence.*;
import com.neurovision.rehab.clinical.domain.model.aggregates.*;
import com.neurovision.rehab.clinical.domain.model.valueobjects.*;
import com.neurovision.rehab.clinical.domain.model.valueobjects.TherapeuticExerciseParameters.*;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import io.quarkus.test.security.oidc.Claim;
import io.quarkus.test.security.oidc.OidcSecurity;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

@QuarkusTest
class PatientTherapyControllerTest {
    private static final String PATIENT = "patient-session-api-test";
    private static final String OTHER = "other-patient-session-api-test";
    private static final CognitoSubject DOCTOR = new CognitoSubject("doctor-session-api-test");
    private static final ClinicalPatientId PATIENT_ID = new ClinicalPatientId(UUID.randomUUID());
    private static final ClinicalPatientId OTHER_ID = new ClinicalPatientId(UUID.randomUUID());
    private static final TherapyPlanId PLAN_ID = new TherapyPlanId(UUID.randomUUID());
    private static final TherapyPlanId OTHER_PLAN_ID = new TherapyPlanId(UUID.randomUUID());

    @Inject ClinicalPatientRepository patients;
    @Inject DoctorPatientAssignmentRepository assignments;
    @Inject TherapyPlanRepository plans;
    @Inject TherapeuticExerciseConfigurationRepository configurations;
    @Inject EntityManager entityManager;

    @BeforeEach
    void seed() {
        clean();
        var at = Instant.now().minusSeconds(3600);
        patients.save(ClinicalPatient.registerInvited(PATIENT_ID, new CognitoSubject(PATIENT), at));
        patients.save(ClinicalPatient.registerInvited(OTHER_ID, new CognitoSubject(OTHER), at));
        assignments.save(DoctorPatientAssignment.assign(new DoctorPatientAssignmentId(UUID.randomUUID()),
                PATIENT_ID, DOCTOR, at));
        assignments.save(DoctorPatientAssignment.assign(new DoctorPatientAssignmentId(UUID.randomUUID()),
                OTHER_ID, DOCTOR, at));
        plans.save(plan(PLAN_ID, PATIENT_ID));
        plans.save(plan(OTHER_PLAN_ID, OTHER_ID));
        QuarkusTransaction.requiringNew().run(() -> configurations.append(
                TherapeuticExerciseConfiguration.create(new TherapeuticConfigurationId(UUID.randomUUID()),
                        PLAN_ID, TherapeuticExercise.FIXATION, 1, 1, at, DOCTOR, at,
                        new Fixation(FixationMode.SUSTAINED_TARGET_FIXATION, TargetPosition.CENTER,
                                1000, 3000, 70, 10, 1000, 1))));
    }

    @AfterEach
    void cleanup() { clean(); }

    @Test
    @TestSecurity(user = "patient", roles = "PATIENT")
    @OidcSecurity(claims = @Claim(key = "sub", value = PATIENT))
    void listsOnlyOwnCurrentPlanAndEffectiveConfiguration() {
        given().get("/api/v1/me/therapy-plans").then().statusCode(200)
                .body("size()", is(1), "[0].therapyPlanId", is(PLAN_ID.value().toString()),
                        "[0].status", is("ACTIVE"), "[0].effectiveConfigurations.size()", is(1),
                        "[0].effectiveConfigurations[0].exerciseCode", is("fixation"),
                        "[0].effectiveConfigurations[0].parameters.targetSizePx", is(70));
    }

    @Test
    @TestSecurity(user = "patient", roles = "PATIENT")
    @OidcSecurity(claims = @Claim(key = "sub", value = PATIENT))
    void startsAndReplaysSameSessionButRejectsNewActiveSession() {
        String first = given().header("Idempotency-Key", "same-request")
                .contentType("application/json").body("{\"exerciseCode\":\"fixation\"}")
                .post(startUrl(PLAN_ID)).then().statusCode(201)
                .body("exerciseCode", is("fixation"), "status", is("ACTIVE"),
                        "parameters.targetSizePx", is(70))
                .extract().path("therapySessionId");
        String replay = given().header("Idempotency-Key", "same-request")
                .contentType("application/json").body("{\"exerciseCode\":\"fixation\"}")
                .post(startUrl(PLAN_ID)).then().statusCode(200).extract().path("therapySessionId");
        assertEquals(first, replay);
        given().header("Idempotency-Key", "different-request")
                .contentType("application/json").body("{\"exerciseCode\":\"fixation\"}")
                .post(startUrl(PLAN_ID)).then().statusCode(409);
    }

    @Test
    @TestSecurity(user = "patient", roles = "PATIENT")
    @OidcSecurity(claims = @Claim(key = "sub", value = PATIENT))
    void deniesForeignAndUnknownPlanIdentically() {
        var foreign = given().header("Idempotency-Key", "foreign")
                .contentType("application/json").body("{\"exerciseCode\":\"fixation\"}")
                .post(startUrl(OTHER_PLAN_ID)).then().statusCode(403).extract().asString();
        var unknown = given().header("Idempotency-Key", "unknown")
                .contentType("application/json").body("{\"exerciseCode\":\"fixation\"}")
                .post(startUrl(new TherapyPlanId(UUID.randomUUID()))).then().statusCode(403).extract().asString();
        assertEquals(foreign, unknown);
    }

    @Test
    @TestSecurity(user = "patient", roles = "PATIENT")
    @OidcSecurity(claims = @Claim(key = "sub", value = PATIENT))
    void rejectsMissingOrBlankIdempotencyKey() {
        given().contentType("application/json").body("{\"exerciseCode\":\"fixation\"}")
                .post(startUrl(PLAN_ID)).then().statusCode(400);
        given().header("Idempotency-Key", " ").contentType("application/json")
                .body("{\"exerciseCode\":\"fixation\"}")
                .post(startUrl(PLAN_ID)).then().statusCode(400);
    }

    @Test
    @TestSecurity(user = "patient", roles = "PATIENT")
    @OidcSecurity(claims = @Claim(key = "sub", value = PATIENT))
    void rejectsReusedKeyWithChangedRequest() {
        given().header("Idempotency-Key", "immutable-key").contentType("application/json")
                .body("{\"exerciseCode\":\"fixation\"}")
                .post(startUrl(PLAN_ID)).then().statusCode(201);
        given().header("Idempotency-Key", "immutable-key").contentType("application/json")
                .body("{\"exerciseCode\":\"visual-search\"}")
                .post(startUrl(PLAN_ID)).then().statusCode(409);
    }

    @Test
    @TestSecurity(user = "unknown", roles = "PATIENT")
    @OidcSecurity(claims = @Claim(key = "sub", value = "unregistered-session-api-test"))
    void deniesUnregisteredPatientWithoutRevealingPlans() {
        given().get("/api/v1/me/therapy-plans").then().statusCode(403);
        given().header("Idempotency-Key", "unregistered").contentType("application/json")
                .body("{\"exerciseCode\":\"fixation\"}")
                .post(startUrl(PLAN_ID)).then().statusCode(403);
    }

    @Test
    void rejectsAnonymousCaller() {
        given().get("/api/v1/me/therapy-plans").then().statusCode(401);
        given().header("Idempotency-Key", "anonymous").contentType("application/json")
                .body("{\"exerciseCode\":\"fixation\"}")
                .post(startUrl(PLAN_ID)).then().statusCode(401);
    }

    @Test
    @TestSecurity(user = "doctor", roles = "DOCTOR")
    @OidcSecurity(claims = @Claim(key = "sub", value = "doctor-session-api-test"))
    void rejectsDoctorCaller() {
        given().get("/api/v1/me/therapy-plans").then().statusCode(403);
    }

    @Test
    @TestSecurity(user = "dual", roles = {"DOCTOR", "PATIENT"})
    @OidcSecurity(claims = @Claim(key = "sub", value = PATIENT))
    void rejectsAmbiguousDualRole() {
        given().get("/api/v1/me/therapy-plans").then().statusCode(403);
        given().header("Idempotency-Key", "dual").contentType("application/json")
                .body("{\"exerciseCode\":\"fixation\"}")
                .post(startUrl(PLAN_ID)).then().statusCode(403);
    }

    private static TherapyPlan plan(TherapyPlanId id, ClinicalPatientId patient) {
        return TherapyPlan.rehydrate(id, patient, DOCTOR, TherapyPlanStatus.ACTIVE, 1,
                2, 30, 60, 5, 15, 50, Map.of(TherapeuticExercise.FIXATION.code(), 2));
    }

    private static String startUrl(TherapyPlanId id) {
        return "/api/v1/me/therapy-plans/" + id.value() + "/sessions";
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
