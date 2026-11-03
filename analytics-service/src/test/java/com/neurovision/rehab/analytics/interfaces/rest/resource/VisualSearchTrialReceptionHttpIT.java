package com.neurovision.rehab.analytics.interfaces.rest.resource;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.neurovision.rehab.analytics.application.ports.out.authorization.VisualSearchTrialAuthorizationPort;
import com.neurovision.rehab.analytics.application.ports.out.persistence.VisualSearchTrialResultStore;
import com.neurovision.rehab.analytics.application.ports.out.persistence.VisualSearchTrialResultReader;
import com.neurovision.rehab.analytics.application.ports.out.authorization.VisualSearchTrialSummaryAuthorizationPort;
import com.neurovision.rehab.analytics.application.internal.queryservices.VisualSearchTrialSummaryService;
import com.neurovision.rehab.analytics.domain.model.valueobjects.VisualSearchTrialContext;
import com.neurovision.rehab.analytics.domain.model.valueobjects.VisualSearchTrialResult;
import com.neurovision.rehab.analytics.domain.model.valueobjects.VisualSearchTrialResult.DataOrigin;
import com.neurovision.rehab.analytics.domain.model.valueobjects.VisualSearchTrialResult.Outcome;
import com.neurovision.rehab.analytics.infrastructure.integration.ClinicalVisualSearchSummaryAuthorizationAdapter;
import com.neurovision.rehab.analytics.infrastructure.integration.ClinicalVisualSearchTrialAuthorizationAdapter;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.smallrye.jwt.build.Jwt;
import jakarta.inject.Inject;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.eclipse.microprofile.config.ConfigProvider;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;

/** Prepared incoming HTTP/CDI checks: real local OIDC/Clinical transport, profile-only storage double. */
@QuarkusTest
@TestProfile(VisualSearchTrialReceptionHttpProfile.class)
@Execution(ExecutionMode.SAME_THREAD)
@Timeout(30)
class VisualSearchTrialReceptionHttpIT {
    private static final String SESSION = ClinicalHeatmapAuthorizationStubResource.ALLOWED_SESSION;
    private static final UUID SESSION_ID = UUID.fromString(SESSION);
    private static final String BODY = "{\"schemaVersion\":1,\"trialId\":\"trial-001\",\"outcome\":\"SUCCESS\","
            + "\"searchDurationMs\":2250,\"confirmedDwellMs\":600,\"dataOrigin\":\"SYNTHETIC\"}";
    @Inject VisualSearchTrialResultStore storage;
    @Inject VisualSearchTrialStoreStub stub;
    @Inject VisualSearchTrialAuthorizationPort authorization;
    @Inject VisualSearchTrialResultReader summaryReader;
    @Inject VisualSearchTrialSummaryAuthorizationPort summaryAuthorization;
    @Inject VisualSearchTrialSummaryService summaryService;
    @Inject ObjectMapper mapper;
    @TestHTTPResource URI base;
    ClinicalHeatmapAuthorizationStubResource peer;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2))
            .followRedirects(HttpClient.Redirect.NEVER).build();
    private String patient;
    private String doctor;

    @BeforeEach void requireTheRealCompositionAndResetOnlyTestState() {
        VisualSearchTrialReceptionHttpProfile.requireOptIn();
        assertInstanceOf(VisualSearchTrialStoreStub.class, storage);
        assertInstanceOf(ClinicalVisualSearchTrialAuthorizationAdapter.class, authorization);
        assertNotNull(peer, "Scoped Clinical peer was not injected");
        for (URI target : List.of(base, ConfigProvider.getConfig().getValue("keycloak.url", URI.class)))
            assertTrue("http".equals(target.getScheme()) && Set.of("localhost","127.0.0.1","::1").contains(target.getHost()),
                    "Test traffic must target local HTTP peers");
        stub.reset();
        patient = token(List.of("PATIENT"));
        peer.visualSearchExpectedToken(patient);
        peer.visualSearchReply(200, permit(true));
        peer.resetVisualSearchObservation();
        assertInstanceOf(VisualSearchTrialStoreStub.class, summaryReader);
        assertInstanceOf(ClinicalVisualSearchSummaryAuthorizationAdapter.class, summaryAuthorization);
        assertNotNull(summaryService, "Production summary composition must resolve through CDI");
        doctor = doctorToken();
        peer.visualSearchSummaryExpectedToken(doctor);
        peer.visualSearchSummaryReply(200,summaryPermit());
        peer.resetVisualSearchSummaryObservation();
    }

    @AfterEach void closeOwnedHttpClient() { http.close(); }

    @Test void createsAndConfirmsAnIdenticalAttemptThroughTheProductionCdiPath() throws Exception {
        status(post(patient, "application/json", BODY), 201, "CREATED");
        var before = stub.stored(SESSION_ID,"trial-001"); assertNotNull(before);
        status(post(patient, "application/json", BODY), 200, "ALREADY_STORED");
        assertTrue(before.equals(stub.stored(SESSION_ID,"trial-001")), "Replay changed stored test state");
        assertEquals(2250, before.result().searchDurationMs());
        assertEquals(600L, before.result().confirmedDwellMs());
        counts(2,2,1,1);
    }

    @Test void closedPermissionAllowsConfirmationButNotCreationOrOverwrite() throws Exception {
        status(post(patient, "application/json", BODY),201,"CREATED");
        var before = stub.stored(SESSION_ID,"trial-001");
        peer.visualSearchReply(200,permit(false));
        status(post(patient, "application/json", BODY),200,"ALREADY_STORED");
        status(post(patient, "application/json", BODY.replace("trial-001","trial-002")),403,"DENIED");
        status(post(patient, "application/json", BODY.replace("2250","2200")),409,"CONFLICT");
        assertTrue(before.equals(stub.stored(SESSION_ID,"trial-001")), "Closed-session conflict changed stored test state");
        counts(4,4,1,1);
    }

    @Test void nativeAuthenticationAndRoleRejectionsNeverCallApplicationPorts() throws Exception {
        status(post(null,"application/json",BODY),401,"INVALID_TOKEN");
        String[] parts = patient.split("\\.");
        String damagedSignature = (parts[2].charAt(0) == 'A' ? "B" : "A") + parts[2].substring(1);
        status(post(parts[0]+"."+parts[1]+"."+damagedSignature,"application/json",BODY),401,"INVALID_TOKEN");
        status(post(token(List.of("DOCTOR")),"application/json",BODY),403,"DENIED");
        status(post(token(List.of("PATIENT","DOCTOR")),"application/json",BODY),403,"DENIED");
        counts(0,0,0,0);
    }

    @Test void nativeBodyAndMediaRejectionsNeverReachClinicalOrStorage() throws Exception {
        status(post(patient,"application/json","{}"),400,"INVALID_RESULT");
        status(post(patient,"application/json"," ".repeat(8193)),413,"PAYLOAD_TOO_LARGE");
        status(post(patient,"text/plain",BODY),415,"UNSUPPORTED_MEDIA_TYPE");
        counts(0,0,0,0);
    }

    @Test void clinicalDenialAndUnavailabilityAreDistinctAndDoNotAccessStorage() throws Exception {
        peer.visualSearchReply(403,"{}");
        status(post(patient,"application/json",BODY),403,"DENIED");
        peer.visualSearchReply(503,"{}");
        status(post(patient,"application/json",BODY),503,"UNAVAILABLE");
        counts(2,0,0,0);
    }

    @Test void uncertainAcknowledgementCanBeConfirmedWithoutAnotherInsert() throws Exception {
        stub.loseAcknowledgement();
        status(post(patient,"application/json",BODY),503,"WRITE_OUTCOME_UNKNOWN");
        counts(1,1,1,1);
        var before = stub.stored(SESSION_ID,"trial-001"); assertNotNull(before);
        peer.visualSearchReply(200,permit(false));
        status(post(patient,"application/json",BODY),200,"ALREADY_STORED");
        assertTrue(before.equals(stub.stored(SESSION_ID,"trial-001")), "Confirmation changed stored test state");
        counts(2,2,1,1);
    }

    @Test void doctorSummaryUsesProductionCompositionWithExactMetricsOriginsAndEmptyEvidence() throws Exception {
        var emptyBefore = stub.summarySnapshot();
        var empty = summaryJson(summaryGet(doctor,SESSION,"SYNTHETIC"),"SYNTHETIC");
        for (String key : List.of("reportedTrialCount","hitCount","omissionCount","interruptedCount","eligibleSearchCount"))
            assertEquals(0,empty.path(key).longValue());
        assertTrue(empty.has("medianSearchDurationMs") && empty.path("medianSearchDurationMs").isNull());
        assertTrue(emptyBefore.equals(stub.summarySnapshot()), "Empty read changed the fixture");
        stub.seedSummary(List.of(summaryRow("success-one",Outcome.SUCCESS,1000,DataOrigin.SYNTHETIC),
                summaryRow("success-two",Outcome.SUCCESS,1001,DataOrigin.SYNTHETIC),
                summaryRow("omitted",Outcome.TIMEOUT,3000,DataOrigin.SYNTHETIC),
                summaryRow("interrupted",Outcome.INTERRUPTED,200,DataOrigin.SYNTHETIC),
                summaryRow("reported",Outcome.SUCCESS,2000,DataOrigin.PRODUCER_REPORTED)));
        var before = stub.summarySnapshot();
        var synthetic = summaryJson(summaryGet(doctor,SESSION,"SYNTHETIC"),"SYNTHETIC");
        assertEquals(4,synthetic.path("reportedTrialCount").longValue()); assertEquals(2,synthetic.path("hitCount").longValue());
        assertEquals(1,synthetic.path("omissionCount").longValue()); assertEquals(1,synthetic.path("interruptedCount").longValue());
        assertEquals(2,synthetic.path("eligibleSearchCount").longValue());
        assertTrue(synthetic.path("medianSearchDurationMs").isNumber());
        assertEquals(0,new java.math.BigDecimal("1000.5").compareTo(synthetic.path("medianSearchDurationMs").decimalValue()));
        var reported = summaryJson(summaryGet(doctor,SESSION,"PRODUCER_REPORTED"),"PRODUCER_REPORTED");
        assertEquals(1,reported.path("reportedTrialCount").longValue()); assertEquals(1,reported.path("hitCount").longValue());
        assertEquals(0,reported.path("omissionCount").longValue()); assertEquals(0,reported.path("interruptedCount").longValue());
        assertEquals(1,reported.path("eligibleSearchCount").longValue()); assertEquals(2000,reported.path("medianSearchDurationMs").longValue());
        summaryCounts(3,3,5); assertTrue(before.equals(stub.summarySnapshot()), "Summary changed stored fixture records");
    }

    @Test void summaryNativeAuthenticationRolesAndSiblingRoutePreserveTheirHeaders() throws Exception {
        summaryNative(summaryGet(null,SESSION,"SYNTHETIC"),401);
        String[] parts = doctor.split("\\.");
        String damaged = (parts[2].charAt(0) == 'A' ? "B" : "A") + parts[2].substring(1);
        summaryNative(summaryGet(parts[0]+"."+parts[1]+"."+damaged,SESSION,"SYNTHETIC"),401);
        summaryNative(summaryGet(patient,SESSION,"SYNTHETIC"),403);
        summaryNative(summaryGet(token(List.of("ADMIN")),SESSION,"SYNTHETIC"),403);
        summaryError(summaryGet(token(List.of("DOCTOR","PATIENT")),SESSION,"SYNTHETIC"),403,"DENIED");
        var sibling = http.send(HttpRequest.newBuilder(base.resolve("/api/v1/analytics/sessions/"+SESSION+"/visual-search/trial-results"))
                .timeout(Duration.ofSeconds(5)).header("Authorization","Bearer "+patient).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(405,sibling.statusCode());
        assertTrue(sibling.headers().allValues("Cache-Control").stream().noneMatch(value -> value.equalsIgnoreCase("no-store")),
                "Summary GET cache headers leaked to the unsupported reception GET");
        summaryCounts(0,0,0);
    }

    @Test void summaryClinicalRevocationAndUnavailabilityNeverReadAfterPermissionIsLost() throws Exception {
        summaryJson(summaryGet(doctor,SESSION,"SYNTHETIC"),"SYNTHETIC");
        peer.visualSearchSummaryReply(403,"{}"); summaryError(summaryGet(doctor,SESSION,"SYNTHETIC"),403,"DENIED");
        peer.visualSearchSummaryReply(503,"{}"); summaryError(summaryGet(doctor,SESSION,"SYNTHETIC"),503,"UNAVAILABLE");
        summaryCounts(3,1,0);
    }

    @Test void summaryInvalidSessionAndRequiredOriginStopBeforeClinicalAndStorage() throws Exception {
        summaryError(summaryGet(doctor,"invalid","SYNTHETIC"),400,"INVALID_REQUEST");
        summaryError(summaryGet(doctor,SESSION,null),400,"INVALID_REQUEST");
        summaryError(summaryGet(doctor,SESSION,"ALL"),400,"INVALID_REQUEST");
        summaryCounts(0,0,0);
    }

    @Test void summaryReaderFailureAndOverflowNeverReturnPartialSuccessOrChangeRecords() throws Exception {
        stub.seedSummary(java.util.stream.IntStream.range(0,1001)
                .mapToObj(i -> summaryRow("limit-"+i,Outcome.SUCCESS,1000,DataOrigin.SYNTHETIC)).toList());
        var before = stub.summarySnapshot();
        stub.failSummaryReads(true); summaryError(summaryGet(doctor,SESSION,"SYNTHETIC"),503,"UNAVAILABLE");
        stub.failSummaryReads(false); summaryError(summaryGet(doctor,SESSION,"SYNTHETIC"),422,"LIMIT_EXCEEDED");
        summaryCounts(2,2,1001); assertTrue(before.equals(stub.summarySnapshot()), "Failure or overflow changed fixture records");
    }

    private HttpResponse<String> summaryGet(String bearer,String session,String origin) throws Exception {
        String path = "/api/v1/analytics/sessions/"+session+"/visual-search/summary"
                + (origin == null ? "" : "?dataOrigin="+origin);
        var request = HttpRequest.newBuilder(base.resolve(path)).timeout(Duration.ofSeconds(5))
                .header("Accept","application/json").GET();
        if (bearer != null) request.header("Authorization","Bearer "+bearer);
        return http.send(request.build(),HttpResponse.BodyHandlers.ofString());
    }
    private void summaryNative(HttpResponse<String> reply,int expected) {
        assertEquals(expected,reply.statusCode());
        assertEquals("no-store",reply.headers().firstValue("Cache-Control").orElse(""));
        if (expected == 401) assertTrue(reply.headers().allValues("WWW-Authenticate").stream()
                .anyMatch(value -> value.startsWith("Bearer")), "Expected native Bearer challenge");
        assertTrue(!reply.body().contains(doctor) && !reply.body().contains(patient), "Response exposed a synthetic credential");
        // Native rejection body semantics belong to Quarkus; never assert a fabricated fixed JSON body.
    }
    private com.fasterxml.jackson.databind.JsonNode summaryBody(HttpResponse<String> reply) {
        assertTrue(reply.headers().firstValue("Content-Type").orElse("").startsWith("application/json"), "Expected summary JSON");
        try {
            var value = mapper.readTree(reply.body());
            assertTrue(value != null && value.isObject(), "Expected summary object; raw response suppressed");
            return value;
        } catch (java.io.IOException invalid) { return fail("Invalid summary JSON; raw response suppressed"); }
    }
    private com.fasterxml.jackson.databind.JsonNode summaryJson(HttpResponse<String> reply,String origin) {
        summaryNative(reply,200); var value = summaryBody(reply); assertEquals(11,value.size());
        assertTrue("visual-search-trial-v1".equals(value.path("calculationVersion").textValue()), "Wrong calculation version");
        assertTrue(SESSION.equals(value.path("therapySessionId").textValue()), "Wrong session binding");
        assertTrue("00000000-0000-0000-0000-000000000002".equals(value.path("configurationId").textValue()), "Wrong frozen configuration");
        assertEquals(3,value.path("configurationVersion").longValue());
        assertTrue(origin.equals(value.path("dataOrigin").textValue()), "Wrong data origin");
        return value;
    }
    private void summaryError(HttpResponse<String> reply,int expected,String code) {
        summaryNative(reply,expected); var value = summaryBody(reply);
        assertTrue(value.size() == 1 && ("VISUAL_SEARCH_SUMMARY_"+code).equals(value.path("code").textValue()),
                "Expected only fixed summary error code; raw response suppressed");
    }
    private void summaryCounts(int clinical,int readCount,int records) {
        var observed = peer.visualSearchSummaryObservation(); assertEquals(clinical,observed.requests());
        assertTrue(observed.exactWire(), "Summary authorization did not forward the exact DOCTOR request");
        assertEquals(0,peer.visualSearchObservation().requests(), "Summary called PATIENT admission");
        var read = stub.summaryObservation(); assertEquals(readCount,read.reads()); assertEquals(readCount == 0 ? 0 : 1001,read.limit());
        var stored = stub.observation(); assertEquals(0,stored.reads()); assertEquals(0,stored.writes()); assertEquals(records,stored.records());
    }
    private static VisualSearchTrialResultStore.Stored summaryRow(String id,Outcome outcome,long duration,DataOrigin origin) {
        var context = new VisualSearchTrialContext(SESSION_ID,new UUID(0,2),3,500,3000);
        return new VisualSearchTrialResultStore.Stored(context,new VisualSearchTrialResult(1,SESSION_ID,id,outcome,duration,
                outcome == Outcome.SUCCESS ? 500L : null,origin));
    }
    private static String summaryPermit() {
        return "{\"contractVersion\":1,\"permitted\":true,\"therapySessionId\":\""+SESSION
                +"\",\"configurationId\":\"00000000-0000-0000-0000-000000000002\","
                +"\"configurationVersion\":3,\"minimumFixationMs\":500,\"maximumSearchTimeMs\":3000}";
    }
    private static String doctorToken() {
        long now = Instant.now().getEpochSecond();
        return Jwt.subject("synthetic-doctor").issuer("https://server.example.com")
                .claim("client_id","test-client").claim("token_use","access").claim("cognito:groups",List.of("DOCTOR"))
                .issuedAt(now-60).expiresAt(now+300).sign();
    }

    private HttpResponse<String> post(String bearer, String media, String body) throws Exception {
        var request = HttpRequest.newBuilder(base.resolve("/api/v1/analytics/sessions/"+SESSION+"/visual-search/trial-results"))
                .timeout(Duration.ofSeconds(5)).header("Accept","application/json").header("Content-Type",media)
                .POST(HttpRequest.BodyPublishers.ofString(body));
        if (bearer != null) request.header("Authorization","Bearer "+bearer);
        return http.send(request.build(),HttpResponse.BodyHandlers.ofString());
    }
    private void status(HttpResponse<String> reply, int expected, String value) {
        assertEquals(expected,reply.statusCode());
        assertEquals("no-store",reply.headers().firstValue("Cache-Control").orElse(""));
        assertTrue(reply.headers().firstValue("Content-Type").orElse("").startsWith("application/json"), "Expected JSON response");
        if (expected == 401) assertTrue(reply.headers().allValues("WWW-Authenticate").stream()
                .anyMatch(header -> header.startsWith("Bearer")), "Expected Bearer challenge");
        try {
            var root = mapper.readTree(reply.body());
            assertTrue(root != null && root.isObject() && root.size() == 1
                    && root.path("status").isTextual() && value.equals(root.path("status").textValue()),
                    "Expected only the fixed response status");
        } catch (java.io.IOException invalid) { fail("Expected fixed JSON response; raw content suppressed"); }
    }
    private void counts(int clinical, int reads, int writes, int records) {
        var observation = peer.visualSearchObservation();
        assertEquals(clinical,observation.requests());
        assertTrue(observation.exactWire(), "Clinical request did not preserve the exact expected wire contract");
        var stored = stub.observation();
        assertEquals(reads,stored.reads()); assertEquals(writes,stored.writes()); assertEquals(records,stored.records());
    }
    private static String permit(boolean mayInsert) {
        return "{\"contractVersion\":1,\"permitted\":true,\"therapySessionId\":\""+SESSION
                +"\",\"mayInsert\":"+mayInsert+",\"configurationId\":\"00000000-0000-0000-0000-000000000002\","
                +"\"configurationVersion\":3,\"minimumFixationMs\":500,\"maximumSearchTimeMs\":3000}";
    }
    private static String token(List<String> groups) {
        long now = Instant.now().getEpochSecond();
        return Jwt.subject("synthetic-patient").issuer("https://server.example.com")
                .claim("client_id","test-client").claim("token_use","access").claim("cognito:groups",groups)
                .issuedAt(now-60).expiresAt(now+300).sign();
    }
}
