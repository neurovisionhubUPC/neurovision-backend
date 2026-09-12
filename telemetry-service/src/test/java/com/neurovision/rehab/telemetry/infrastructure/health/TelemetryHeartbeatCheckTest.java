package com.neurovision.rehab.telemetry.infrastructure.health;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.is;

import org.junit.jupiter.api.Test;

import io.quarkus.test.junit.QuarkusTest;

@QuarkusTest
class TelemetryHeartbeatCheckTest {

    @Test
    void reportsTheTelemetryServiceAsLive() {
        given()
                .when().get("/q/health/live")
                .then()
                .statusCode(200)
                .body("status", is("UP"))
                .body("checks.name", hasItem("telemetry-service-heartbeat"));
    }

    @Test
    void reportsTheTelemetryServiceAsReady() {
        given()
                .when().get("/q/health/ready")
                .then()
                .statusCode(200)
                .body("status", is("UP"));
    }
}
