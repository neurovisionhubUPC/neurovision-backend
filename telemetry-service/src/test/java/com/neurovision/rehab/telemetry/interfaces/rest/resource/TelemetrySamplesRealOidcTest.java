package com.neurovision.rehab.telemetry.interfaces.rest.resource;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.jupiter.api.Assertions.assertEquals;

import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.quarkus.test.oidc.server.OidcWiremockTestResource;
import io.smallrye.jwt.build.Jwt;
import jakarta.inject.Inject;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.util.List;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.junit.jupiter.api.Test;

@QuarkusTest
@TestProfile(TelemetryRealOidcProfile.class)
@QuarkusTestResource(OidcWiremockTestResource.class)
@QuarkusTestResource(value = ClinicalAuthorizationStubResource.class, restrictToAnnotatedClass = true)
class TelemetrySamplesRealOidcTest {
    private static final String SESSION = "3f1a860f-cc55-40d9-b86b-377780949b82";
    private static final String QUERY = "?streamId=40ae017b-d707-43e3-a78c-dc617b632ced"
            + "&from=2026-09-17T12:00:00Z&to=2026-09-17T12:01:00Z&limit=100";

    @Inject @ConfigProperty(name = "telemetry.clinical.base-url") URI clinicalBaseUri;

    @Test
    void rejectsMissingTokenWrongRoleAndMalformedSession() {
        given().get(url(SESSION)).then().statusCode(401);
        given().auth().oauth2(token(List.of("ADMIN"))).get(url(SESSION)).then().statusCode(403);
        given().auth().oauth2(token(List.of("PATIENT"))).get(url("invalid"))
                .then().statusCode(403).body("code", equalTo("TELEMETRY_READ_FORBIDDEN"));
    }

    @Test
    void rejectsOversizedWindowBeforeClinicalAndGenericallyDeniesUnknownSession() {
        given().auth().oauth2(token(List.of("PATIENT")))
                .get(url(SESSION).replace("12:01:00", "12:16:00"))
                .then().statusCode(400).body("code", equalTo("INVALID_TELEMETRY_QUERY"));
        given().auth().oauth2(token(List.of("PATIENT")))
                .get(url("92000000-0000-0000-0000-000000000001"))
                .then().statusCode(403).body("code", equalTo("TELEMETRY_READ_FORBIDDEN"));
    }

    @Test
    void forwardsOriginalTokenForReadAndFailsClosedWithoutTimescale() throws Exception {
        var accessToken = token(List.of("DOCTOR"));
        given().auth().oauth2(accessToken).get(url(SESSION)).then().statusCode(503)
                .body("code", equalTo("TELEMETRY_READ_UNAVAILABLE"));
        var observed = HttpClient.newHttpClient().send(HttpRequest.newBuilder(
                clinicalBaseUri.resolve("/test-observed-credential")).GET().build(),
                HttpResponse.BodyHandlers.ofString()).body();
        assertEquals("Bearer " + accessToken, observed);
    }

    private static String url(String session) {
        return "/api/v1/telemetry/sessions/" + session + "/samples" + QUERY;
    }

    private static String token(List<String> groups) {
        return Jwt.subject("subject").issuer("https://server.example.com")
                .claim("client_id", "test-client").claim("token_use", "access")
                .claim("cognito:groups", groups)
                .expiresAt(Instant.now().plusSeconds(300).getEpochSecond()).sign();
    }
}
