package com.neurovision.rehab.telemetry.interfaces.rest.resource;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.jupiter.api.Assertions.assertEquals;

import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.quarkus.test.oidc.server.OidcWiremockTestResource;
import io.smallrye.jwt.build.Jwt;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.util.List;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

@QuarkusTest
@TestProfile(TelemetryRealOidcProfile.class)
@QuarkusTestResource(OidcWiremockTestResource.class)
@QuarkusTestResource(value = ClinicalAuthorizationStubResource.class, restrictToAnnotatedClass = true)
class TelemetryBatchRealOidcTest {
    private static final String URL = "/api/v1/telemetry/batches";

    @Inject
    @ConfigProperty(name = "telemetry.clinical.base-url")
    URI clinicalBaseUri;

    @Test
    void rejectsMissingMalformedAndWrongClaimsBeforeClinical() {
        given().contentType("application/json").body(fixture()).post(URL).then().statusCode(401);
        given().auth().oauth2("not-a-jwt").contentType("application/json")
                .body(fixture()).post(URL).then().statusCode(401);
        given().auth().oauth2(token("other-issuer", "test-client", "access", List.of("PATIENT")))
                .contentType("application/json").body(fixture()).post(URL).then().statusCode(401);
        given().auth().oauth2(token("https://server.example.com", "other-client", "access", List.of("PATIENT")))
                .contentType("application/json").body(fixture()).post(URL).then().statusCode(401);
        given().auth().oauth2(token("https://server.example.com", "test-client", "id", List.of("PATIENT")))
                .contentType("application/json").body(fixture()).post(URL).then().statusCode(401);
        given().auth().oauth2(token("https://server.example.com", "test-client", "access", List.of("DOCTOR")))
                .contentType("application/json").body(fixture()).post(URL).then().statusCode(403);
        var signed = patientToken();
        var tampered = signed.substring(0, signed.lastIndexOf('.') + 1) + "bad-signature";
        given().auth().oauth2(tampered).contentType("application/json")
                .body(fixture()).post(URL).then().statusCode(401);
        var expired = Jwt.subject("patient-subject")
                .issuer("https://server.example.com")
                .claim("client_id", "test-client")
                .claim("token_use", "access")
                .claim("cognito:groups", List.of("PATIENT"))
                .expiresAt(Instant.now().minusSeconds(30).getEpochSecond()).sign();
        given().auth().oauth2(expired).contentType("application/json")
                .body(fixture()).post(URL).then().statusCode(401);
    }

    @Test
    void authorizedPatientTokenReachesClinicalButCannotSucceedWithoutKafka() throws Exception {
        var accessToken = patientToken();
        given().auth().oauth2(accessToken).contentType("application/json")
                .body(fixture()).post(URL).then().statusCode(503)
                .body("code", equalTo("TELEMETRY_INGESTION_UNAVAILABLE"));
        var observed = HttpClient.newHttpClient().send(HttpRequest.newBuilder(
                clinicalBaseUri.resolve("/test-observed-credential")).GET().build(),
                HttpResponse.BodyHandlers.ofString()).body();
        assertEquals("Bearer " + accessToken, observed);
    }

    @Test
    void clinicalDenialIsGenericAndCannotRevealSessionExistence() {
        given().auth().oauth2(patientToken()).contentType("application/json")
                .body(fixture().replace("3f1a860f-cc55-40d9-b86b-377780949b82",
                        "92000000-0000-0000-0000-000000000001"))
                .post(URL).then().statusCode(403)
                .body("code", equalTo("TELEMETRY_INGESTION_FORBIDDEN"));
    }

    @Test
    void rejectsUnknownFrameFieldAndInvalidCoordinatesWithoutExposingValues() {
        var token = patientToken();
        given().auth().oauth2(token).contentType("application/json")
                .body(fixture().replace("\"schemaVersion\"", "\"videoFrame\":\"private\",\"schemaVersion\""))
                .post(URL).then().statusCode(400).body("code", equalTo("INVALID_BATCH"));
        given().auth().oauth2(token).contentType("application/json")
                .body(fixture().replace("\"x\": 0.25", "\"x\": 1.25"))
                .post(URL).then().statusCode(400).body("code", equalTo("INVALID_GAZE_X"));
    }

    @Test
    void rejectsRequestAboveConfiguredBodyLimitBeforeApplicationPublication() {
        given().auth().oauth2(patientToken()).contentType("application/json")
                .body("{" + " ".repeat(70_000) + "}")
                .post(URL).then().statusCode(413);
    }

    private static String patientToken() {
        return token("https://server.example.com", "test-client", "access", List.of("PATIENT"));
    }

    private static String token(String issuer, String client, String use, List<String> groups) {
        return Jwt.subject("patient-subject")
                .issuer(issuer)
                .claim("client_id", client)
                .claim("token_use", use)
                .claim("cognito:groups", groups)
                .expiresAt(Instant.now().plusSeconds(300).getEpochSecond())
                .sign();
    }

    private static String fixture() {
        try (InputStream input = TelemetryBatchRealOidcTest.class
                .getResourceAsStream("/fixtures/telemetry/v1/dual-typed-batch.json")) {
            return new String(input.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        } catch (Exception unavailableFixture) {
            throw new IllegalStateException("Missing Telemetry V1 fixture", unavailableFixture);
        }
    }
}
