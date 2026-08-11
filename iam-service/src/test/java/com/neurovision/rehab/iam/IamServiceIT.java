package com.neurovision.rehab.iam;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;

import io.quarkus.test.junit.QuarkusIntegrationTest;
import org.junit.jupiter.api.Test;

@QuarkusIntegrationTest
class IamServiceIT {

    @Test
    void shouldExposeTheLivenessProbe() {
        given()
                .when()
                .get("/q/health/live")
                .then()
                .statusCode(200)
                .body("status", equalTo("UP"));
    }

    @Test
    void shouldRejectAnonymousPatientInvitations() {
        given()
                .contentType("application/json")
                .body("{\"patientEmail\":\"patient@example.com\"}")
                .when()
                .post("/api/v1/patient-invitations")
                .then()
                .statusCode(401);
    }
}
