package com.neurovision.rehab.analytics.interfaces.rest.resource;

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
@TestProfile(AnalyticsRealOidcProfile.class)
@QuarkusTestResource(OidcWiremockTestResource.class)
@QuarkusTestResource(value = ClinicalHeatmapAuthorizationStubResource.class,
        restrictToAnnotatedClass = true)
class HeatmapProjectionRealOidcTest {
    private static final String SESSION = ClinicalHeatmapAuthorizationStubResource.ALLOWED_SESSION;
    private static final String STREAM = "40ae017b-d707-43e3-a78c-dc617b632ced";

    @Inject
    @ConfigProperty(name = "analytics.clinical.base-url")
    URI clinicalBaseUri;

    @Test
    void rejectsMissingTokenWrongRoleAndMalformedSessionAtTheHttpBoundary() {
        given().get(url(SESSION)).then().statusCode(401);
        given().auth().oauth2("not-a-jwt").get(url(SESSION)).then().statusCode(401);
        given().auth().oauth2(token(List.of("ADMIN"))).get(url(SESSION)).then().statusCode(403);
        given().auth().oauth2(token(List.of("PATIENT"))).get(url("invalid"))
                .then().statusCode(403).body("code", equalTo("HEATMAP_READ_FORBIDDEN"));
    }

    @Test
    void genericallyDeniesAnUnknownOrUnassociatedClinicalSession() {
        given().auth().oauth2(token(List.of("PATIENT")))
                .get(url("92000000-0000-0000-0000-000000000001"))
                .then().statusCode(403).body("code", equalTo("HEATMAP_READ_FORBIDDEN"));
    }

    @Test
    void forwardsTheOriginalTokenAndMapsMongoDependencyFailureToUnavailable() throws Exception {
        var accessToken = token(List.of("DOCTOR"));
        given().auth().oauth2(accessToken).get(url(SESSION)).then().statusCode(503)
                .body("code", equalTo("HEATMAP_READ_UNAVAILABLE"));

        String observed = HttpClient.newHttpClient().send(HttpRequest.newBuilder(
                clinicalBaseUri.resolve("/test-observed-credential")).GET().build(),
                HttpResponse.BodyHandlers.ofString()).body();
        assertEquals("Bearer " + accessToken, observed);
    }

    @Test
    void gazePlotUsesTheSameRealOidcRolesAndGenericClinicalDenial() {
        given().get(gazeUrl(SESSION)).then().statusCode(401);
        given().auth().oauth2(token(List.of("ADMIN"))).get(gazeUrl(SESSION)).then().statusCode(403);
        given().auth().oauth2(token(List.of("PATIENT")))
                .get(gazeUrl("92000000-0000-0000-0000-000000000001"))
                .then().statusCode(403).body("code", equalTo("GAZE_PLOT_READ_FORBIDDEN"));
    }

    @Test
    void gazePlotForwardsTheOriginalBearerToClinicalBeforeMongo() throws Exception {
        var accessToken = token(List.of("DOCTOR"));
        given().auth().oauth2(accessToken).get(gazeUrl(SESSION) + "?pageSize=10")
                .then().statusCode(503).body("code", equalTo("GAZE_PLOT_READ_UNAVAILABLE"));

        String observed = HttpClient.newHttpClient().send(HttpRequest.newBuilder(
                clinicalBaseUri.resolve("/test-observed-credential")).GET().build(),
                HttpResponse.BodyHandlers.ofString()).body();
        assertEquals("Bearer " + accessToken, observed);
    }

    @Test
    void allFourQueryRoutesEnforceOidcRolesAndAuthorizeThroughClinicalBeforeUnavailableMongo() {
        var routes = List.of(
                "/api/v1/analytics/sessions/" + SESSION + "/streams/" + STREAM + "/summary",
                url(SESSION),
                gazeUrl(SESSION) + "?pageSize=10",
                replayUrl(SESSION) + "?pageSize=10");

        for (String route : routes) {
            given().get(route).then().statusCode(401);
            given().auth().oauth2(token(List.of("ADMIN"))).get(route).then().statusCode(403);
            for (String allowedRole : List.of("PATIENT", "DOCTOR")) {
                given().auth().oauth2(token(List.of(allowedRole))).get(route)
                        .then().statusCode(503);
            }
        }
    }

    @Test
    void pagedRoutesRejectMalformedBoundsAndCursorShapesAtHttpBoundary() {
        given().auth().oauth2(token(List.of("PATIENT"))).get(gazeUrl(SESSION) + "?pageSize=501")
                .then().statusCode(400).body("code", equalTo("GAZE_PLOT_READ_INVALID_REQUEST"));
        given().auth().oauth2(token(List.of("DOCTOR"))).get(gazeUrl(SESSION) + "?cursor=%20")
                .then().statusCode(400).body("code", equalTo("GAZE_PLOT_READ_INVALID_REQUEST"));
        given().auth().oauth2(token(List.of("PATIENT"))).get(replayUrl(SESSION) + "?pageSize=501")
                .then().statusCode(400).body("code", equalTo("REPLAY_READ_INVALID_REQUEST"));
        given().auth().oauth2(token(List.of("DOCTOR"))).get(replayUrl(SESSION) + "?gazeCursor=%20")
                .then().statusCode(400).body("code", equalTo("REPLAY_READ_INVALID_REQUEST"));
    }

    private static String url(String session) {
        return "/api/v1/analytics/sessions/" + session + "/streams/" + STREAM + "/heatmap";
    }

    private static String gazeUrl(String session) {
        return "/api/v1/analytics/sessions/" + session + "/streams/" + STREAM + "/gaze-plot";
    }

    private static String replayUrl(String session) {
        return "/api/v1/analytics/sessions/" + session + "/streams/" + STREAM + "/replay";
    }

    private static String token(List<String> groups) {
        return Jwt.subject("subject").issuer("https://server.example.com")
                .claim("client_id", "test-client").claim("token_use", "access")
                .claim("cognito:groups", groups)
                .expiresAt(Instant.now().plusSeconds(300).getEpochSecond()).sign();
    }
}
