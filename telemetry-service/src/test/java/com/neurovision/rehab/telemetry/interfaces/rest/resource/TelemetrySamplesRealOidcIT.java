package com.neurovision.rehab.telemetry.interfaces.rest.resource;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;
import static org.junit.jupiter.api.Assertions.*;

import com.neurovision.rehab.telemetry.infrastructure.persistence.timescale.TimescaleTestResource;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.quarkus.test.oidc.server.OidcWiremockTestResource;
import io.smallrye.jwt.build.Jwt;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;

@QuarkusTest
@TestProfile(TelemetryRealOidcProfile.class)
@QuarkusTestResource(OidcWiremockTestResource.class)
@QuarkusTestResource(value = ClinicalAuthorizationStubResource.class, restrictToAnnotatedClass = true)
@QuarkusTestResource(value = TimescaleTestResource.class, restrictToAnnotatedClass = true)
class TelemetrySamplesRealOidcIT {
    private static final UUID SESSION = UUID.fromString("3f1a860f-cc55-40d9-b86b-377780949b82");
    private static final UUID STREAM = UUID.fromString("40ae017b-d707-43e3-a78c-dc617b632ced");
    private static final Instant AT = Instant.parse("2026-09-17T12:00:00.123456Z");

    @Inject Instance<DataSource> dataSource;

    @Test
    void authenticatedClientPaginatesEqualTimeGazeAndGameplayWithoutIdentity() throws Exception {
        try (var connection = dataSource.get().getConnection()) {
            insert(connection, new UUID(0, 1), "GAZE", 0);
            insert(connection, new UUID(0, 2), "GAMEPLAY", 1);
            insert(connection, new UUID(0, 3), "GAZE", 2);
        }
        var jwt = Jwt.subject("patient-subject").issuer("https://server.example.com")
                .claim("client_id", "test-client").claim("token_use", "access")
                .claim("cognito:groups", List.of("PATIENT"))
                .expiresAt(Instant.now().plusSeconds(300).getEpochSecond()).sign();
        String base = "/api/v1/telemetry/sessions/" + SESSION + "/samples?streamId=" + STREAM
                + "&from=2026-09-17T12:00:00Z&to=2026-09-17T12:01:00Z&limit=2";
        var first = given().auth().oauth2(jwt).get(base).then().statusCode(200)
                .body("contractVersion", equalTo(1))
                .body("samples.size()", equalTo(2))
                .body("samples[0].kind", equalTo("GAZE"))
                .body("samples[1].kind", equalTo("GAMEPLAY"))
                .extract().asString();
        assertFalse(first.contains("patient-subject"));
        assertFalse(first.contains(jwt));
        var tree = new com.fasterxml.jackson.databind.ObjectMapper().readTree(first);
        String cursor = tree.path("nextCursor").asText();
        assertFalse(cursor.isBlank());
        given().auth().oauth2(jwt).queryParam("cursor", cursor).get(base).then().statusCode(200)
                .body("samples.size()", equalTo(1))
                .body("samples[0].sequenceNumber", equalTo(2))
                .body("nextCursor", nullValue());
    }

    private static void insert(java.sql.Connection connection, UUID batch, String kind,
            int sequence) throws Exception {
        String fields = kind.equals("GAZE") ? "x, y, confidence" :
                "player_x, player_y, target_x, target_y, score, speed, progress";
        String values = kind.equals("GAZE") ? "0.1, 0.2, 0.9" :
                "0.1, 0.2, 0.3, 0.4, 10, 1.5, 0.5";
        try (var insert = connection.prepareStatement("INSERT INTO telemetry_samples "
                + "(sample_time, batch_id, therapy_session_id, stream_id, sequence_number, "
                + "sample_kind, sample_index, offset_ms, server_received_at, " + fields + ") "
                + "VALUES (?, ?, ?, ?, ?, ?, 0, 0, ?, " + values + ")")) {
            OffsetDateTime time = AT.atOffset(ZoneOffset.UTC);
            insert.setObject(1, time);
            insert.setObject(2, batch);
            insert.setObject(3, SESSION);
            insert.setObject(4, STREAM);
            insert.setLong(5, sequence);
            insert.setString(6, kind);
            insert.setObject(7, time);
            insert.executeUpdate();
        }
    }
}
