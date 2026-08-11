package com.neurovision.rehab.profile.interfaces.rest.resource;

import static io.restassured.RestAssured.given;
import static io.restassured.http.ContentType.JSON;
import static org.hamcrest.Matchers.equalTo;

import com.neurovision.rehab.profile.application.internal.commandservices.RegisterInvitedPatientCommand;
import com.neurovision.rehab.profile.infrastructure.persistence.jpa.transaction.TransactionalInvitedPatientRegistrar;
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
class ProfileControllerTest {

    private static final String ENDPOINT = "/api/v1/profile";
    private static final String PATIENT_SUBJECT = "rest-patient-cognito-sub";

    @Inject
    TransactionalInvitedPatientRegistrar registrar;

    @Inject
    EntityManager entityManager;

    @BeforeEach
    @AfterEach
    void cleanDatabase() {
        QuarkusTransaction.requiringNew().run(() -> {
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
}
