package com.neurovision.rehab.analytics;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import io.quarkus.test.junit.QuarkusTest;
import org.eclipse.microprofile.config.ConfigProvider;
import org.junit.jupiter.api.Test;

@QuarkusTest
class AnalyticsServiceTest {

    @Test
    void shouldStartWithTruthfulPlatformHealth() {
        given()
                .when()
                .get("/q/health/live")
                .then()
                .statusCode(200)
                .body("status", equalTo("UP"));

        given()
                .when()
                .get("/q/health/ready")
                .then()
                .statusCode(200)
                .body("status", equalTo("UP"));
    }

    @Test
    void shouldExposePrometheusMetrics() {
        given()
                .when()
                .get("/q/metrics")
                .then()
                .statusCode(200);
    }

    @Test
    void shouldKeepDeferredAdaptersInactive() {
        var config = ConfigProvider.getConfig();

        assertEquals("analytics-service", config.getValue("quarkus.application.name", String.class));
        assertFalse(config.getValue("quarkus.mongodb.health.enabled", Boolean.class));
        assertFalse(config.getValue("quarkus.kafka.devservices.enabled", Boolean.class));
        assertFalse(config.getOptionalValue("mp.messaging.incoming.analytics-telemetry", String.class).isPresent());
    }
}
