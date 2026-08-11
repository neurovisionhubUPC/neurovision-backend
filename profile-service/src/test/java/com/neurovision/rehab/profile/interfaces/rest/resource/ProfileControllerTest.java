package com.neurovision.rehab.profile.interfaces.rest.resource;

import static io.restassured.RestAssured.given;
import static io.restassured.http.ContentType.JSON;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.jupiter.api.Assertions.assertEquals;

import com.neurovision.rehab.profile.application.internal.commandservices.RegisterInvitedPatientCommand;
import com.neurovision.rehab.profile.application.internal.commandservices.CompleteCurrentProfileCommand;
import com.neurovision.rehab.profile.application.internal.commandservices.OnboardDoctorCommand;
import com.neurovision.rehab.profile.infrastructure.persistence.jpa.transaction.TransactionalCurrentProfileService;
import com.neurovision.rehab.profile.infrastructure.persistence.jpa.transaction.TransactionalInvitedPatientRegistrar;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import io.quarkus.test.security.oidc.Claim;
import io.quarkus.test.security.oidc.OidcSecurity;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

@QuarkusTest
class ProfileControllerTest {

    private static final String ENDPOINT = "/api/v1/profile";
    private static final String PATIENT_SUBJECT = "rest-patient-cognito-sub";
    private static final String DOCTOR_SUBJECT = "rest-doctor-cognito-sub";

    @Inject
    TransactionalInvitedPatientRegistrar registrar;

    @Inject
    TransactionalCurrentProfileService profiles;

    @Inject
    EntityManager entityManager;

    @BeforeEach
    @AfterEach
    void cleanDatabase() {
        QuarkusTransaction.requiringNew().run(() -> {
            entityManager.createNativeQuery("DELETE FROM doctor_profiles").executeUpdate();
            entityManager.createNativeQuery("DELETE FROM inbox_events").executeUpdate();
            entityManager.createNativeQuery("DELETE FROM user_profiles").executeUpdate();
        });
    }

    @Test
    @TestSecurity(user = "patient", roles = "PATIENT")
    @OidcSecurity(claims = @Claim(key = "sub", value = PATIENT_SUBJECT))
    void shouldReturnTheAuthenticatedUsersProfile() {
        seedProfile(PATIENT_SUBJECT);

        given()
                .when()
                .get(ENDPOINT)
                .then()
                .statusCode(200)
                .contentType(JSON)
                .body("profileType", equalTo("PATIENT"))
                .body("status", equalTo("PENDING_COMPLETION"));
    }

    @Test
    @TestSecurity(user = "patient", roles = "PATIENT")
    @OidcSecurity(claims = @Claim(key = "sub", value = PATIENT_SUBJECT))
    void shouldCompleteTheAuthenticatedPatientsProfile() {
        seedProfile(PATIENT_SUBJECT);

        given()
                .contentType(JSON)
                .body("""
                        {
                          "firstName": "Andrea",
                          "lastName": "Salazar",
                          "documentNumber": "74859621",
                          "birthDate": "1998-04-12",
                          "phone": "+51 987 654 321"
                        }
                        """)
                .when()
                .put(ENDPOINT)
                .then()
                .statusCode(200)
                .body("status", equalTo("ACTIVE"))
                .body("firstName", equalTo("Andrea"))
                .body("documentNumber", equalTo("74859621"));
    }

    @Test
    @TestSecurity(user = "patient", roles = "PATIENT")
    @OidcSecurity(claims = @Claim(key = "sub", value = "unknown-patient-sub"))
    void shouldReturnNotFoundWhenTheAuthenticatedUserHasNoProfile() {
        given().when().get(ENDPOINT).then().statusCode(404).body("code", equalTo("PROFILE_NOT_FOUND"));
    }

    @Test
    void shouldRejectAnonymousRequests() {
        given().when().get(ENDPOINT).then().statusCode(401);
    }

    @Test
    @TestSecurity(user = "doctor", roles = "DOCTOR")
    @OidcSecurity(claims = @Claim(key = "sub", value = "doctor-cognito-sub"))
    void shouldRejectDoctorsCompletingThePatientProfileContract() {
        given()
                .contentType(JSON)
                .body(validRequest())
                .when()
                .put(ENDPOINT)
                .then()
                .statusCode(403);
    }

    @Test
    @TestSecurity(user = "patient", roles = "PATIENT")
    @OidcSecurity(claims = @Claim(key = "sub", value = PATIENT_SUBJECT))
    void shouldRejectInvalidProfileDetails() {
        seedProfile(PATIENT_SUBJECT);

        given()
                .contentType(JSON)
                .body("{\"firstName\":\"\"}")
                .when()
                .put(ENDPOINT)
                .then()
                .statusCode(400);
    }

    @Test
    @TestSecurity(user = "doctor", roles = "DOCTOR")
    @OidcSecurity(claims = @Claim(key = "sub", value = DOCTOR_SUBJECT))
    void shouldOnboardTheAuthenticatedDoctorAndExposeProfessionalDetails() {
        given()
                .contentType(JSON)
                .body(validDoctorRequest("44556677", "CMP-123456"))
                .when()
                .put(ENDPOINT + "/professional")
                .then()
                .statusCode(200)
                .body("profileType", equalTo("DOCTOR"))
                .body("status", equalTo("ACTIVE"))
                .body("specialty", equalTo("Neurology"))
                .body("medicalLicense", equalTo("CMP-123456"));

        given()
                .when()
                .get(ENDPOINT)
                .then()
                .statusCode(200)
                .body("specialty", equalTo("Neurology"))
                .body("medicalLicense", equalTo("CMP-123456"));
    }

    @Test
    @TestSecurity(user = "doctor", roles = "DOCTOR")
    @OidcSecurity(claims = @Claim(key = "sub", value = DOCTOR_SUBJECT))
    void shouldKeepRepeatedDoctorOnboardingIdempotent() {
        given().contentType(JSON).body(validDoctorRequest("44556677", "CMP-123456"))
                .when().put(ENDPOINT + "/professional").then().statusCode(200);

        given().contentType(JSON).body(validDoctorRequest("44556677", "CMP-123456"))
                .when().put(ENDPOINT + "/professional").then().statusCode(200);

        QuarkusTransaction.requiringNew().run(() -> {
            var count = ((Number) entityManager
                            .createNativeQuery("SELECT COUNT(*) FROM doctor_profiles")
                            .getSingleResult())
                    .longValue();
            assertEquals(1L, count);
        });
    }

    @Test
    @TestSecurity(user = "patient", roles = "PATIENT")
    @OidcSecurity(claims = @Claim(key = "sub", value = PATIENT_SUBJECT))
    void shouldRejectPatientsUsingTheDoctorOnboardingContract() {
        given()
                .contentType(JSON)
                .body(validDoctorRequest("44556677", "CMP-123456"))
                .when()
                .put(ENDPOINT + "/professional")
                .then()
                .statusCode(403);
    }

    @Test
    @TestSecurity(user = "doctor", roles = "DOCTOR")
    @OidcSecurity(claims = @Claim(key = "sub", value = DOCTOR_SUBJECT))
    void shouldReturnConflictForADuplicateDocumentNumber() {
        seedProfile(PATIENT_SUBJECT);
        profiles.complete(new CompleteCurrentProfileCommand(
                PATIENT_SUBJECT,
                "Andrea",
                "Salazar",
                "44556677",
                LocalDate.of(1998, 4, 12),
                "+51 987 654 321"));

        given()
                .contentType(JSON)
                .body(validDoctorRequest("44556677", "CMP-123456"))
                .when()
                .put(ENDPOINT + "/professional")
                .then()
                .statusCode(409)
                .body("code", equalTo("DOCUMENT_NUMBER_ALREADY_REGISTERED"));
    }

    @Test
    @TestSecurity(user = "doctor", roles = "DOCTOR")
    @OidcSecurity(claims = @Claim(key = "sub", value = DOCTOR_SUBJECT))
    void shouldReturnConflictForADuplicateMedicalLicense() {
        profiles.onboardDoctor(new OnboardDoctorCommand(
                "existing-doctor-sub",
                "Elena",
                "Torres",
                "11223344",
                LocalDate.of(1980, 1, 5),
                "+51 999 111 222",
                "Physical Medicine",
                "CMP-123456"));

        given()
                .contentType(JSON)
                .body(validDoctorRequest("44556677", "CMP-123456"))
                .when()
                .put(ENDPOINT + "/professional")
                .then()
                .statusCode(409)
                .body("code", equalTo("MEDICAL_LICENSE_ALREADY_REGISTERED"));
    }

    private void seedProfile(String subject) {
        registrar.handle(new RegisterInvitedPatientCommand(
                UUID.randomUUID(), subject, Instant.parse("2026-08-10T21:04:30Z")));
    }

    private static String validRequest() {
        return """
                {
                  "firstName": "Andrea",
                  "lastName": "Salazar",
                  "documentNumber": "74859621",
                  "birthDate": "1998-04-12",
                  "phone": "+51 987 654 321"
                }
                """;
    }

    private static String validDoctorRequest(String documentNumber, String medicalLicense) {
        return """
                {
                  "firstName": "Marco",
                  "lastName": "Bill",
                  "documentNumber": "%s",
                  "birthDate": "1985-06-15",
                  "phone": "+51 987 654 321",
                  "specialty": "Neurology",
                  "medicalLicense": "%s"
                }
                """.formatted(documentNumber, medicalLicense);
    }
}
