package com.neurovision.rehab.profile;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;

import io.quarkus.test.junit.QuarkusIntegrationTest;
import org.junit.jupiter.api.Test;

@QuarkusIntegrationTest
class ProfileServiceIT {

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
    void shouldRejectAnonymousProfileQueries() {
        given()
                .when()
                .get("/api/v1/profile")
                .then()
                .statusCode(401);
    }
}
