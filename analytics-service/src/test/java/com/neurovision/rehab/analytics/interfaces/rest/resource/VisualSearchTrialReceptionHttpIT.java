package com.neurovision.rehab.analytics.interfaces.rest.resource;

import static org.junit.jupiter.api.Assertions.*;
import com.neurovision.rehab.analytics.application.internal.queryservices.SystematicExplorationTrialSummaryService;
import com.neurovision.rehab.analytics.application.ports.out.authorization.SystematicExplorationTrialSummaryAuthorizationPort;
import com.neurovision.rehab.analytics.application.ports.out.persistence.SystematicExplorationTrialResultReader;
import com.neurovision.rehab.analytics.infrastructure.integration.ClinicalSystematicExplorationSummaryAuthorizationAdapter;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.neurovision.rehab.analytics.application.internal.queryservices.CompensatorySaccadeTrialSummaryService;
import com.neurovision.rehab.analytics.application.ports.out.authorization.CompensatorySaccadeTrialSummaryAuthorizationPort;
import com.neurovision.rehab.analytics.application.ports.out.persistence.CompensatorySaccadeTrialResultReader;
import com.neurovision.rehab.analytics.domain.model.valueobjects.CompensatorySaccadeTrialContext;
import com.neurovision.rehab.analytics.domain.model.valueobjects.CompensatorySaccadeTrialResult;
import com.neurovision.rehab.analytics.infrastructure.integration.ClinicalCompensatorySaccadeSummaryAuthorizationAdapter;
import com.neurovision.rehab.analytics.application.internal.commandservices.ReceiveCompensatorySaccadeTrialResultService;
import com.neurovision.rehab.analytics.application.ports.out.authorization.CompensatorySaccadeTrialAuthorizationPort;
import com.neurovision.rehab.analytics.application.ports.out.persistence.CompensatorySaccadeTrialResultStore;
import com.neurovision.rehab.analytics.infrastructure.integration.ClinicalCompensatorySaccadeTrialAuthorizationAdapter;
import com.neurovision.rehab.analytics.application.ports.out.authorization.StandardScanningTrialAuthorizationPort;
import com.neurovision.rehab.analytics.application.ports.out.persistence.StandardScanningTrialResultStore;
import com.neurovision.rehab.analytics.application.internal.commandservices.ReceiveStandardScanningTrialResultService;
import com.neurovision.rehab.analytics.infrastructure.integration.ClinicalStandardScanningTrialAuthorizationAdapter;
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
import com.neurovision.rehab.analytics.application.internal.commandservices.ReceiveSustainedFixationTrialResultService;
import com.neurovision.rehab.analytics.application.ports.out.authorization.SustainedFixationTrialAuthorizationPort;
import com.neurovision.rehab.analytics.application.internal.queryservices.SustainedFixationTrialSummaryService;
import com.neurovision.rehab.analytics.application.ports.out.authorization.SustainedFixationTrialSummaryAuthorizationPort;
import com.neurovision.rehab.analytics.application.ports.out.persistence.SustainedFixationTrialResultReader;
import com.neurovision.rehab.analytics.domain.model.valueobjects.SustainedFixationTrialContext;
import com.neurovision.rehab.analytics.domain.model.valueobjects.SustainedFixationTrialResult;
import com.neurovision.rehab.analytics.infrastructure.integration.ClinicalSustainedFixationSummaryAuthorizationAdapter;
import com.neurovision.rehab.analytics.application.ports.out.persistence.SustainedFixationTrialResultStore;
import com.neurovision.rehab.analytics.infrastructure.integration.ClinicalSustainedFixationTrialAuthorizationAdapter;
import com.neurovision.rehab.analytics.application.internal.commandservices.ReceiveSystematicExplorationTrialResultService;
import com.neurovision.rehab.analytics.application.ports.out.authorization.SystematicExplorationTrialAuthorizationPort;
import com.neurovision.rehab.analytics.application.ports.out.persistence.SystematicExplorationTrialResultStore;
import com.neurovision.rehab.analytics.domain.model.valueobjects.SystematicExplorationTrialContext;
import com.neurovision.rehab.analytics.domain.model.valueobjects.SystematicExplorationTrialResult;
import com.neurovision.rehab.analytics.infrastructure.integration.ClinicalSystematicExplorationTrialAuthorizationAdapter;
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
    private static final String SCANNING_BODY = BODY.replace("searchDurationMs", "localizationDurationMs");
    private static final String SACCADE_BODY = BODY.replace("searchDurationMs", "elapsedMs");
    private static final String SYSTEMATIC_BODY = "{\"schemaVersion\":1,\"trialId\":\"traversal-001\",\"outcome\":\"COMPLETED\",\"elapsedMs\":4000,\"visits\":[{\"quadrant\":\"TOP_LEFT\",\"timeToVisitMs\":1000,\"confirmedDwellMs\":600},{\"quadrant\":\"TOP_RIGHT\",\"timeToVisitMs\":1000,\"confirmedDwellMs\":600},{\"quadrant\":\"BOTTOM_LEFT\",\"timeToVisitMs\":1000,\"confirmedDwellMs\":600},{\"quadrant\":\"BOTTOM_RIGHT\",\"timeToVisitMs\":1000,\"confirmedDwellMs\":600}],\"dataOrigin\":\"SYNTHETIC\"}";
    private static final String FIXATION_BODY = "{\"schemaVersion\":1,\"trialId\":\"trial-001\",\"outcome\":\"SUCCESS\","
            + "\"elapsedMs\":2250,\"insideTargetMs\":1000,\"longestContinuousInsideMs\":600,\"dataOrigin\":\"SYNTHETIC\"}";
    @Inject SustainedFixationTrialResultReader fixationSummaryReader;
    @Inject SustainedFixationTrialSummaryAuthorizationPort fixationSummaryAuthorization;
    @Inject SustainedFixationTrialSummaryService fixationSummaryService;
    @Inject SustainedFixationTrialResultStore fixationStorage;
    @Inject SustainedFixationTrialStoreStub fixationStub;
    @Inject SustainedFixationTrialAuthorizationPort fixationAuthorization;
    @Inject ReceiveSustainedFixationTrialResultService fixationService;
    @Inject CompensatorySaccadeTrialResultReader saccadeSummaryReader;
    @Inject CompensatorySaccadeTrialSummaryAuthorizationPort saccadeSummaryAuthorization;
    @Inject CompensatorySaccadeTrialSummaryService saccadeSummaryService;
    @Inject CompensatorySaccadeTrialResultStore saccadeStorage;
    @Inject CompensatorySaccadeTrialStoreStub saccadeStub;
    @Inject CompensatorySaccadeTrialAuthorizationPort saccadeAuthorization;
    @Inject ReceiveCompensatorySaccadeTrialResultService saccadeService;
    @Inject StandardScanningTrialResultStore scanningStorage;
    @Inject StandardScanningTrialStoreStub scanningStub;
    @Inject StandardScanningTrialAuthorizationPort scanningAuthorization;
    @Inject ReceiveStandardScanningTrialResultService scanningService;
    @Inject VisualSearchTrialResultStore storage;
    @Inject VisualSearchTrialStoreStub stub;
    @Inject VisualSearchTrialAuthorizationPort authorization;
    @Inject VisualSearchTrialResultReader summaryReader;
    @Inject VisualSearchTrialSummaryAuthorizationPort summaryAuthorization;
    @Inject VisualSearchTrialSummaryService summaryService;
    @Inject com.neurovision.rehab.analytics.application.ports.out.persistence.StandardScanningTrialResultReader scanningSummaryReader;
    @Inject com.neurovision.rehab.analytics.application.ports.out.authorization.StandardScanningTrialSummaryAuthorizationPort scanningSummaryAuthorization;
    @Inject com.neurovision.rehab.analytics.application.internal.queryservices.StandardScanningTrialSummaryService scanningSummaryService;
    @Inject SystematicExplorationTrialSummaryService systematicSummaryService;
    @Inject SystematicExplorationTrialSummaryAuthorizationPort systematicSummaryAuthorization;
    @Inject SystematicExplorationTrialResultReader systematicSummaryReader;
    @Inject SystematicExplorationTrialResultStore systematicStorage;
    @Inject SystematicExplorationTrialStoreStub systematicStub;
    @Inject SystematicExplorationTrialAuthorizationPort systematicAuthorization;
    @Inject ReceiveSystematicExplorationTrialResultService systematicService;
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
        assertInstanceOf(StandardScanningTrialStoreStub.class, scanningStorage);
        assertInstanceOf(ClinicalStandardScanningTrialAuthorizationAdapter.class, scanningAuthorization);
        assertNotNull(scanningService, "Production Standard Scanning composition must resolve through CDI");
        scanningStub.reset();
        peer.standardScanningExpectedToken(patient);
        peer.standardScanningReply(200, scanningPermit(true));
        peer.resetStandardScanningObservation();
        assertInstanceOf(StandardScanningTrialStoreStub.class, scanningSummaryReader);
        assertInstanceOf(com.neurovision.rehab.analytics.infrastructure.integration.ClinicalStandardScanningSummaryAuthorizationAdapter.class, scanningSummaryAuthorization);
        assertNotNull(scanningSummaryService, "Production scanning summary composition must resolve through CDI");
        peer.standardScanningSummaryExpectedToken(doctor);
        peer.standardScanningSummaryReply(200, scanningSummaryPermit());
        peer.resetStandardScanningSummaryObservation();
        assertInstanceOf(SustainedFixationTrialStoreStub.class, fixationStorage);
        assertInstanceOf(ClinicalSustainedFixationTrialAuthorizationAdapter.class, fixationAuthorization);
        assertNotNull(fixationService, "Production Sustained Fixation composition must resolve through CDI");
        fixationStub.reset();
        peer.sustainedFixationExpectedToken(patient);
        peer.sustainedFixationReply(200, fixationPermit(true));
        peer.resetSustainedFixationObservation();
        assertInstanceOf(SustainedFixationTrialStoreStub.class, fixationSummaryReader);
        assertInstanceOf(ClinicalSustainedFixationSummaryAuthorizationAdapter.class, fixationSummaryAuthorization);
        assertNotNull(fixationSummaryService, "Production Fixation summary composition must resolve through CDI");
        peer.sustainedFixationSummaryExpectedToken(doctor);
        peer.sustainedFixationSummaryReply(200, fixationSummaryPermit());
        peer.resetSustainedFixationSummaryObservation();
        assertInstanceOf(CompensatorySaccadeTrialStoreStub.class, saccadeStorage);
        assertInstanceOf(ClinicalCompensatorySaccadeTrialAuthorizationAdapter.class, saccadeAuthorization);
        assertNotNull(saccadeService, "Production Compensatory Saccade composition must resolve through CDI");
        saccadeStub.reset();
        peer.compensatorySaccadeExpectedToken(patient);
        peer.compensatorySaccadeReply(200, scanningPermit(true));
        assertInstanceOf(CompensatorySaccadeTrialStoreStub.class, saccadeSummaryReader);
        assertInstanceOf(ClinicalCompensatorySaccadeSummaryAuthorizationAdapter.class, saccadeSummaryAuthorization);
        assertNotNull(saccadeSummaryService, "Production Compensatory Saccade summary composition must resolve through CDI");
        peer.compensatorySaccadeSummaryExpectedToken(doctor);
        peer.compensatorySaccadeSummaryReply(200, scanningSummaryPermit());
        peer.resetCompensatorySaccadeSummaryObservation();
        peer.resetCompensatorySaccadeObservation();
        assertInstanceOf(SystematicExplorationTrialStoreStub.class, systematicStorage);
        assertInstanceOf(ClinicalSystematicExplorationTrialAuthorizationAdapter.class, systematicAuthorization);
        assertNotNull(systematicService, "Production Systematic Exploration composition must resolve through CDI");
        systematicStub.reset();
        peer.systematicExplorationExpectedToken(patient);
        peer.systematicExplorationReply(200, scanningPermit(true));
        peer.resetSystematicExplorationObservation();
        assertInstanceOf(SystematicExplorationTrialStoreStub.class, systematicSummaryReader);
        assertInstanceOf(ClinicalSystematicExplorationSummaryAuthorizationAdapter.class, systematicSummaryAuthorization);
        assertNotNull(systematicSummaryService, "Production Systematic Exploration summary composition must resolve through CDI");
        peer.systematicExplorationSummaryExpectedToken(doctor);
        peer.systematicExplorationSummaryReply(200, scanningSummaryPermit());
        peer.resetSystematicExplorationSummaryObservation();
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

    @Test void standardScanningCreatesAndConfirmsAnIdenticalAttemptThroughTheProductionCdiPath() throws Exception {
        status(scanningPost(patient, "application/json", SCANNING_BODY), 201, "CREATED");
        var before = scanningStub.stored(SESSION_ID,"trial-001"); assertNotNull(before);
        status(scanningPost(patient, "application/json", SCANNING_BODY), 200, "ALREADY_STORED");
        assertTrue(before.equals(scanningStub.stored(SESSION_ID,"trial-001")), "Replay changed stored test state");
        assertEquals(2250, before.result().localizationDurationMs());
        assertEquals(600L, before.result().confirmedDwellMs());
        assertTrue(SESSION_ID.equals(before.context().therapySessionId()), "Wrong frozen session");
        assertTrue(new UUID(0,2).equals(before.context().configurationId()), "Wrong frozen configuration");
        assertEquals(3, before.context().configurationVersion());
        assertEquals(500, before.context().minimumFixationMs());
        assertEquals(3000, before.context().maximumStimulusDurationMs());
        scanningCounts(2,2,1,1);
    }

    @Test void standardScanningClosedPermissionAllowsConfirmationButNotCreationOrOverwrite() throws Exception {
        status(scanningPost(patient, "application/json", SCANNING_BODY),201,"CREATED");
        var before = scanningStub.stored(SESSION_ID,"trial-001");
        peer.standardScanningReply(200,scanningPermit(false));
        status(scanningPost(patient, "application/json", SCANNING_BODY),200,"ALREADY_STORED");
        status(scanningPost(patient, "application/json", SCANNING_BODY.replace("trial-001","trial-002")),403,"DENIED");
        status(scanningPost(patient, "application/json", SCANNING_BODY.replace("2250","2200")),409,"CONFLICT");
        assertTrue(before.equals(scanningStub.stored(SESSION_ID,"trial-001")), "Closed-session conflict changed stored test state");
        scanningCounts(4,4,1,1);
    }

    @Test void standardScanningNativeAuthenticationAndRoleRejectionsNeverCallApplicationPorts() throws Exception {
        status(scanningPost(null,"application/json",SCANNING_BODY),401,"INVALID_TOKEN");
        String[] parts = patient.split("\\.");
        String damagedSignature = (parts[2].charAt(0) == 'A' ? "B" : "A") + parts[2].substring(1);
        status(scanningPost(parts[0]+"."+parts[1]+"."+damagedSignature,"application/json",SCANNING_BODY),401,"INVALID_TOKEN");
        status(scanningPost(token(List.of("DOCTOR")),"application/json",SCANNING_BODY),403,"DENIED");
        status(scanningPost(token(List.of("PATIENT","DOCTOR")),"application/json",SCANNING_BODY),403,"DENIED");
        scanningCounts(0,0,0,0);
    }

    @Test void standardScanningNativeBodyAndMediaRejectionsNeverReachClinicalOrStorage() throws Exception {
        status(scanningPost(patient,"application/json","{}"),400,"INVALID_RESULT");
        status(scanningPost(patient,"application/json"," ".repeat(8193)),413,"PAYLOAD_TOO_LARGE");
        status(scanningPost(patient,"text/plain",SCANNING_BODY),415,"UNSUPPORTED_MEDIA_TYPE");
        scanningCounts(0,0,0,0);
    }

    @Test void standardScanningClinicalDenialAndUnavailabilityAreDistinctAndDoNotAccessStorage() throws Exception {
        peer.standardScanningReply(403,"{}");
        status(scanningPost(patient,"application/json",SCANNING_BODY),403,"DENIED");
        peer.standardScanningReply(503,"{}");
        status(scanningPost(patient,"application/json",SCANNING_BODY),503,"UNAVAILABLE");
        scanningCounts(2,0,0,0);
    }

    @Test void standardScanningUncertainAcknowledgementCanBeConfirmedWithoutAnotherInsert() throws Exception {
        scanningStub.loseAcknowledgement();
        status(scanningPost(patient,"application/json",SCANNING_BODY),503,"WRITE_OUTCOME_UNKNOWN");
        scanningCounts(1,1,1,1);
        var before = scanningStub.stored(SESSION_ID,"trial-001"); assertNotNull(before);
        peer.standardScanningReply(200,scanningPermit(false));
        status(scanningPost(patient,"application/json",SCANNING_BODY),200,"ALREADY_STORED");
        assertTrue(before.equals(scanningStub.stored(SESSION_ID,"trial-001")), "Confirmation changed stored test state");
        scanningCounts(2,2,1,1);
    }

    private HttpResponse<String> scanningPost(String bearer, String media, String body) throws Exception {
        var request = HttpRequest.newBuilder(base.resolve("/api/v1/analytics/sessions/"+SESSION+"/standard-scanning/trial-results"))
                .timeout(Duration.ofSeconds(5)).header("Accept","application/json").header("Content-Type",media)
                .POST(HttpRequest.BodyPublishers.ofString(body));
        if (bearer != null) request.header("Authorization","Bearer "+bearer);
        return http.send(request.build(),HttpResponse.BodyHandlers.ofString());
    }
    private void scanningCounts(int clinical, int reads, int writes, int records) {
        var observation = peer.standardScanningObservation();
        assertEquals(clinical, observation.requests());
        assertTrue(observation.exactWire(), "Standard Scanning request did not preserve the original PATIENT contract");
        var stored = scanningStub.observation();
        assertEquals(reads, stored.reads()); assertEquals(writes, stored.writes()); assertEquals(records, stored.records());
        assertEquals(0, peer.visualSearchObservation().requests(), "Scanning called Visual Search admission");
        assertEquals(0, peer.visualSearchSummaryObservation().requests(), "Scanning called DOCTOR summary");
        var other = stub.observation();
        assertEquals(0, other.reads()); assertEquals(0, other.writes()); assertEquals(0, other.records());
        assertEquals(0, stub.summaryObservation().reads());
    }
    private static String scanningPermit(boolean mayInsert) {
        return permit(mayInsert).replace("maximumSearchTimeMs", "maximumStimulusDurationMs");
    }

    @Test void systematicExplorationCreatesAndConfirmsAfterClosureWithoutDuplicating() throws Exception {
        status(systematicPost(patient), 201, "CREATED");
        var before = systematicStub.stored(SESSION_ID, "traversal-001");
        assertNotNull(before, "Created traversal missing from the storage double");
        assertEquals(new SystematicExplorationTrialContext(SESSION_ID, new UUID(0, 2), 3, 500, 3000), before.context());
        assertEquals("traversal-001", before.result().trialId());
        assertEquals(SESSION_ID, before.result().therapySessionId());
        assertEquals(4000L, before.result().elapsedMs());
        assertEquals(SystematicExplorationTrialResult.Outcome.COMPLETED, before.result().outcome());
        assertEquals(DataOrigin.SYNTHETIC, before.result().dataOrigin());
        var expectedVisits = java.util.Arrays.stream(SystematicExplorationTrialResult.Quadrant.values())
                .map(q -> new SystematicExplorationTrialResult.Visit(q, 1000, 600)).toList();
        assertTrue(expectedVisits.equals(before.result().visits()), "Stored ordered visits differ");
        systematicCounts(1, 1, 1, 1);

        peer.systematicExplorationReply(200, scanningPermit(false));
        status(systematicPost(patient), 200, "ALREADY_STORED");
        assertTrue(before.equals(systematicStub.stored(SESSION_ID, "traversal-001")), "Replay changed stored test state");
        systematicCounts(2, 2, 1, 1);
    }

    @Test void systematicExplorationNativeMissingAndInvalidBearerDoNotReachPorts() throws Exception {
        status(systematicPost(null), 401, "INVALID_TOKEN");
        systematicCounts(0, 0, 0, 0);
        String[] parts = patient.split("\\.");
        String damagedSignature = (parts[2].charAt(0) == 'A' ? "B" : "A") + parts[2].substring(1);
        status(systematicPost(parts[0] + "." + parts[1] + "." + damagedSignature), 401, "INVALID_TOKEN");
        systematicCounts(0, 0, 0, 0);
    }

    private HttpResponse<String> systematicPost(String bearer) throws Exception {
        var request = HttpRequest.newBuilder(base.resolve(
                "/api/v1/analytics/sessions/" + SESSION + "/systematic-exploration/trial-results"))
                .timeout(Duration.ofSeconds(5)).header("Accept", "application/json")
                .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(SYSTEMATIC_BODY));
        if (bearer != null) request.header("Authorization", "Bearer " + bearer);
        return http.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private void systematicCounts(int clinical, int reads, int writes, int records) {
        var observation = peer.systematicExplorationObservation();
        assertEquals(clinical, observation.requests());
        assertTrue(observation.exactWire(), "Systematic Exploration did not forward the exact original PATIENT request");
        assertEquals(new SystematicExplorationTrialStoreStub.Observation(reads, writes, records), systematicStub.observation());
        assertEquals(0, peer.visualSearchObservation().requests());
        assertEquals(0, peer.visualSearchSummaryObservation().requests());
        assertEquals(0, peer.standardScanningObservation().requests());
        assertEquals(0, peer.standardScanningSummaryObservation().requests());
        assertEquals(0, peer.sustainedFixationObservation().requests());
        assertEquals(0, peer.sustainedFixationSummaryObservation().requests());
        assertEquals(0, peer.compensatorySaccadeObservation().requests());
        assertEquals(0, peer.compensatorySaccadeSummaryObservation().requests());
        assertEquals(0, stub.observation().reads()); assertEquals(0, stub.observation().writes());
        assertEquals(0, scanningStub.observation().reads()); assertEquals(0, scanningStub.observation().writes());
        assertEquals(0, fixationStub.observation().reads()); assertEquals(0, fixationStub.observation().writes());
        assertEquals(0, saccadeStub.observation().reads()); assertEquals(0, saccadeStub.observation().writes());
    }

    @Test void compensatorySaccadeCreatesAndConfirmsAfterClosureWithoutDuplicating() throws Exception {
        status(saccadePost(patient), 201, "CREATED");
        var before = saccadeStub.stored(SESSION_ID, "trial-001");
        assertNotNull(before, "Created attempt missing from the storage double");
        assertEquals(2250L, before.result().elapsedMs());
        assertEquals(600L, before.result().confirmedDwellMs());
        assertEquals(Outcome.SUCCESS, before.result().outcome());
        assertEquals(DataOrigin.SYNTHETIC, before.result().dataOrigin());
        saccadeCounts(1, 1, 1, 1);

        peer.compensatorySaccadeReply(200, scanningPermit(false));
        status(saccadePost(patient), 200, "ALREADY_STORED");
        assertTrue(before.equals(saccadeStub.stored(SESSION_ID, "trial-001")), "Replay changed stored test state");
        saccadeCounts(2, 2, 1, 1);
    }

    @Test void compensatorySaccadeNativeMissingAndInvalidBearerDoNotReachPorts() throws Exception {
        status(saccadePost(null), 401, "INVALID_TOKEN");
        saccadeCounts(0, 0, 0, 0);
        String[] parts = patient.split("\\.");
        String damagedSignature = (parts[2].charAt(0) == 'A' ? "B" : "A") + parts[2].substring(1);
        status(saccadePost(parts[0] + "." + parts[1] + "." + damagedSignature), 401, "INVALID_TOKEN");
        saccadeCounts(0, 0, 0, 0);
    }

    private HttpResponse<String> saccadePost(String bearer) throws Exception {
        var request = HttpRequest.newBuilder(base.resolve(
                "/api/v1/analytics/sessions/" + SESSION + "/compensatory-saccade/trial-results"))
                .timeout(Duration.ofSeconds(5)).header("Accept", "application/json")
                .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(SACCADE_BODY));
        if (bearer != null) request.header("Authorization", "Bearer " + bearer);
        return http.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private void saccadeCounts(int clinical, int reads, int writes, int records) {
        var observation = peer.compensatorySaccadeObservation();
        assertEquals(clinical, observation.requests());
        assertTrue(observation.exactWire(), "Compensatory Saccade did not forward the exact original PATIENT request");
        assertEquals(new CompensatorySaccadeTrialStoreStub.Observation(reads, writes, records), saccadeStub.observation());
        assertEquals(0, peer.visualSearchObservation().requests());
        assertEquals(0, peer.visualSearchSummaryObservation().requests());
        assertEquals(0, peer.standardScanningObservation().requests());
        assertEquals(0, peer.standardScanningSummaryObservation().requests());
        assertEquals(0, peer.sustainedFixationObservation().requests());
        assertEquals(0, peer.sustainedFixationSummaryObservation().requests());
    }

    @Test void sustainedFixationCreatesAndConfirmsAnIdenticalAttemptThroughTheProductionCdiPath() throws Exception {
        status(fixationPost(patient, "application/json", FIXATION_BODY), 201, "CREATED");
        var before = fixationStub.stored(SESSION_ID,"trial-001"); assertNotNull(before);
        status(fixationPost(patient, "application/json", FIXATION_BODY), 200, "ALREADY_STORED");
        assertTrue(before.equals(fixationStub.stored(SESSION_ID,"trial-001")), "Replay changed stored test state");
        assertEquals(2250, before.result().elapsedMs());
        assertEquals(1000L, before.result().insideTargetMs());
        assertEquals(600L, before.result().longestContinuousInsideMs());
        assertTrue(SESSION_ID.equals(before.context().therapySessionId()), "Wrong frozen session");
        assertTrue(new UUID(0,2).equals(before.context().configurationId()), "Wrong frozen configuration");
        assertEquals(3, before.context().configurationVersion());
        assertEquals(500, before.context().minimumFixationMs());
        assertEquals(3000, before.context().maximumFixationMs());
        fixationCounts(2,2,1,1);
    }

    @Test void sustainedFixationClosedPermissionAllowsConfirmationButNotCreationOrOverwrite() throws Exception {
        status(fixationPost(patient, "application/json", FIXATION_BODY),201,"CREATED");
        var before = fixationStub.stored(SESSION_ID,"trial-001");
        peer.sustainedFixationReply(200,fixationPermit(false));
        status(fixationPost(patient, "application/json", FIXATION_BODY),200,"ALREADY_STORED");
        status(fixationPost(patient, "application/json", FIXATION_BODY.replace("trial-001","trial-002")),403,"DENIED");
        status(fixationPost(patient, "application/json", FIXATION_BODY.replace("2250","2200")),409,"CONFLICT");
        assertTrue(before.equals(fixationStub.stored(SESSION_ID,"trial-001")), "Closed-session conflict changed stored test state");
        fixationCounts(4,4,1,1);
    }

    @Test void sustainedFixationNativeAuthenticationAndRoleRejectionsNeverCallApplicationPorts() throws Exception {
        status(fixationPost(null,"application/json",FIXATION_BODY),401,"INVALID_TOKEN");
        String[] parts = patient.split("\\.");
        String damagedSignature = (parts[2].charAt(0) == 'A' ? "B" : "A") + parts[2].substring(1);
        status(fixationPost(parts[0]+"."+parts[1]+"."+damagedSignature,"application/json",FIXATION_BODY),401,"INVALID_TOKEN");
        status(fixationPost(token(List.of("DOCTOR")),"application/json",FIXATION_BODY),403,"DENIED");
        status(fixationPost(token(List.of("PATIENT","DOCTOR")),"application/json",FIXATION_BODY),403,"DENIED");
        fixationCounts(0,0,0,0);
    }

    @Test void sustainedFixationNativeBodyAndMediaRejectionsNeverReachClinicalOrStorage() throws Exception {
        status(fixationPost(patient,"application/json","{}"),400,"INVALID_RESULT");
        status(fixationPost(patient,"application/json"," ".repeat(8193)),413,"PAYLOAD_TOO_LARGE");
        status(fixationPost(patient,"text/plain",FIXATION_BODY),415,"UNSUPPORTED_MEDIA_TYPE");
        fixationCounts(0,0,0,0);
    }

    @Test void sustainedFixationClinicalDenialAndUnavailabilityAreDistinctAndDoNotAccessStorage() throws Exception {
        peer.sustainedFixationReply(403,"{}");
        status(fixationPost(patient,"application/json",FIXATION_BODY),403,"DENIED");
        peer.sustainedFixationReply(503,"{}");
        status(fixationPost(patient,"application/json",FIXATION_BODY),503,"UNAVAILABLE");
        fixationCounts(2,0,0,0);
    }

    @Test void sustainedFixationUncertainAcknowledgementCanBeConfirmedWithoutAnotherInsert() throws Exception {
        fixationStub.loseAcknowledgement();
        status(fixationPost(patient,"application/json",FIXATION_BODY),503,"WRITE_OUTCOME_UNKNOWN");
        fixationCounts(1,1,1,1);
        var before = fixationStub.stored(SESSION_ID,"trial-001"); assertNotNull(before);
        peer.sustainedFixationReply(200,fixationPermit(false));
        status(fixationPost(patient,"application/json",FIXATION_BODY),200,"ALREADY_STORED");
        assertTrue(before.equals(fixationStub.stored(SESSION_ID,"trial-001")), "Confirmation changed stored test state");
        fixationCounts(2,2,1,1);
    }

    private HttpResponse<String> fixationPost(String bearer, String media, String body) throws Exception {
        var request = HttpRequest.newBuilder(base.resolve("/api/v1/analytics/sessions/"+SESSION+"/sustained-fixation/trial-results"))
                .timeout(Duration.ofSeconds(5)).header("Accept","application/json").header("Content-Type",media)
                .POST(HttpRequest.BodyPublishers.ofString(body));
        if (bearer != null) request.header("Authorization","Bearer "+bearer);
        return http.send(request.build(),HttpResponse.BodyHandlers.ofString());
    }
    private void fixationCounts(int clinical, int reads, int writes, int records) {
        var observation = peer.sustainedFixationObservation();
        assertEquals(clinical, observation.requests());
        assertTrue(observation.exactWire(), "Sustained Fixation request did not preserve the original PATIENT contract");
        var stored = fixationStub.observation();
        assertEquals(reads, stored.reads()); assertEquals(writes, stored.writes()); assertEquals(records, stored.records());
        assertEquals(0, peer.visualSearchObservation().requests(), "Fixation called Visual Search admission");
        assertEquals(0, peer.visualSearchSummaryObservation().requests(), "Fixation called DOCTOR summary");
        assertEquals(0, peer.standardScanningObservation().requests(), "Fixation called Standard Scanning admission");
        assertEquals(0, peer.standardScanningSummaryObservation().requests(), "Fixation called Standard Scanning summary");
        var scanning = scanningStub.observation();
        assertEquals(0, scanning.reads()); assertEquals(0, scanning.writes()); assertEquals(0, scanning.records());
        assertEquals(0, scanningStub.summaryObservation().reads());
        var other = stub.observation();
        assertEquals(0, other.reads()); assertEquals(0, other.writes()); assertEquals(0, other.records());
        assertEquals(0, stub.summaryObservation().reads());
    }
    private static String fixationPermit(boolean mayInsert) {
        return permit(mayInsert).replace("maximumSearchTimeMs", "maximumFixationMs");
    }

    @Test void scanningDoctorSummaryUsesProductionCompositionWithExactMetricsOriginsAndEmptyEvidence() throws Exception {
        var emptyBefore = scanningStub.summarySnapshot();
        var empty = scanningSummaryJson(scanningSummaryGet(doctor,SESSION,"SYNTHETIC"),"SYNTHETIC");
        for (String key : List.of("reportedTrialCount","detectedTargetCount","omissionCount","interruptedCount","eligibleLocalizationCount"))
            assertEquals(0,empty.path(key).longValue());
        assertTrue(empty.has("medianLocalizationDurationMs") && empty.path("medianLocalizationDurationMs").isNull());
        assertTrue(emptyBefore.equals(scanningStub.summarySnapshot()), "Empty read changed the fixture");
        scanningStub.seedSummary(List.of(scanningSummaryRow("success-one",Outcome.SUCCESS,1000,DataOrigin.SYNTHETIC),
                scanningSummaryRow("success-two",Outcome.SUCCESS,1001,DataOrigin.SYNTHETIC),
                scanningSummaryRow("omitted",Outcome.TIMEOUT,3000,DataOrigin.SYNTHETIC),
                scanningSummaryRow("interrupted",Outcome.INTERRUPTED,200,DataOrigin.SYNTHETIC),
                scanningSummaryRow("reported",Outcome.SUCCESS,2000,DataOrigin.PRODUCER_REPORTED)));
        var before = scanningStub.summarySnapshot();
        var synthetic = scanningSummaryJson(scanningSummaryGet(doctor,SESSION,"SYNTHETIC"),"SYNTHETIC");
        assertEquals(4,synthetic.path("reportedTrialCount").longValue()); assertEquals(2,synthetic.path("detectedTargetCount").longValue());
        assertEquals(1,synthetic.path("omissionCount").longValue()); assertEquals(1,synthetic.path("interruptedCount").longValue());
        assertEquals(2,synthetic.path("eligibleLocalizationCount").longValue());
        assertTrue(synthetic.path("medianLocalizationDurationMs").isNumber());
        assertEquals(0,new java.math.BigDecimal("1000.5").compareTo(synthetic.path("medianLocalizationDurationMs").decimalValue()));
        var reported = scanningSummaryJson(scanningSummaryGet(doctor,SESSION,"PRODUCER_REPORTED"),"PRODUCER_REPORTED");
        assertEquals(1,reported.path("reportedTrialCount").longValue()); assertEquals(1,reported.path("detectedTargetCount").longValue());
        assertEquals(0,reported.path("omissionCount").longValue()); assertEquals(0,reported.path("interruptedCount").longValue());
        assertEquals(1,reported.path("eligibleLocalizationCount").longValue()); assertEquals(2000,reported.path("medianLocalizationDurationMs").longValue());
        scanningSummaryCounts(3,3,5); assertTrue(before.equals(scanningStub.summarySnapshot()), "Summary changed stored fixture records");
    }

    @Test void scanningSummaryNativeAuthenticationRolesAndSiblingRoutePreserveTheirHeaders() throws Exception {
        scanningSummaryNative(scanningSummaryGet(null,SESSION,"SYNTHETIC"),401);
        String[] parts = doctor.split("\\.");
        String damaged = (parts[2].charAt(0) == 'A' ? "B" : "A") + parts[2].substring(1);
        scanningSummaryNative(scanningSummaryGet(parts[0]+"."+parts[1]+"."+damaged,SESSION,"SYNTHETIC"),401);
        scanningSummaryNative(scanningSummaryGet(patient,SESSION,"SYNTHETIC"),403);
        scanningSummaryNative(scanningSummaryGet(token(List.of("ADMIN")),SESSION,"SYNTHETIC"),403);
        scanningSummaryError(scanningSummaryGet(token(List.of("DOCTOR","PATIENT")),SESSION,"SYNTHETIC"),403,"DENIED");
        var sibling = http.send(HttpRequest.newBuilder(base.resolve("/api/v1/analytics/sessions/"+SESSION+"/standard-scanning/trial-results"))
                .timeout(Duration.ofSeconds(5)).header("Authorization","Bearer "+patient).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(405,sibling.statusCode());
        assertTrue(sibling.headers().allValues("Cache-Control").stream().noneMatch(value -> value.equalsIgnoreCase("no-store")),
                "Summary GET cache headers leaked to the unsupported reception GET");
        scanningSummaryCounts(0,0,0);
    }

    @Test void scanningSummaryClinicalRevocationAndUnavailabilityNeverReadAfterPermissionIsLost() throws Exception {
        scanningSummaryJson(scanningSummaryGet(doctor,SESSION,"SYNTHETIC"),"SYNTHETIC");
        peer.standardScanningSummaryReply(403,"{}"); scanningSummaryError(scanningSummaryGet(doctor,SESSION,"SYNTHETIC"),403,"DENIED");
        peer.standardScanningSummaryReply(503,"{}"); scanningSummaryError(scanningSummaryGet(doctor,SESSION,"SYNTHETIC"),503,"UNAVAILABLE");
        scanningSummaryCounts(3,1,0);
    }

    @Test void scanningSummaryInvalidSessionAndRequiredOriginStopBeforeClinicalAndStorage() throws Exception {
        scanningSummaryError(scanningSummaryGet(doctor,"invalid","SYNTHETIC"),400,"INVALID_REQUEST");
        scanningSummaryError(scanningSummaryGet(doctor,SESSION,null),400,"INVALID_REQUEST");
        scanningSummaryError(scanningSummaryGet(doctor,SESSION,"ALL"),400,"INVALID_REQUEST");
        scanningSummaryCounts(0,0,0);
    }

    @Test void scanningSummaryReaderFailureAndOverflowNeverReturnPartialSuccessOrChangeRecords() throws Exception {
        scanningStub.seedSummary(java.util.stream.IntStream.range(0,1001)
                .mapToObj(i -> scanningSummaryRow("limit-"+i,Outcome.SUCCESS,1000,DataOrigin.SYNTHETIC)).toList());
        var before = scanningStub.summarySnapshot();
        scanningStub.failSummaryReads(true); scanningSummaryError(scanningSummaryGet(doctor,SESSION,"SYNTHETIC"),503,"UNAVAILABLE");
        scanningStub.failSummaryReads(false); scanningSummaryError(scanningSummaryGet(doctor,SESSION,"SYNTHETIC"),422,"LIMIT_EXCEEDED");
        scanningSummaryCounts(2,2,1001); assertTrue(before.equals(scanningStub.summarySnapshot()), "Failure or overflow changed fixture records");
    }

    private HttpResponse<String> scanningSummaryGet(String bearer,String session,String origin) throws Exception {
        String path = "/api/v1/analytics/sessions/"+session+"/standard-scanning/summary"
                + (origin == null ? "" : "?dataOrigin="+origin);
        var request = HttpRequest.newBuilder(base.resolve(path)).timeout(Duration.ofSeconds(5))
                .header("Accept","application/json").GET();
        if (bearer != null) request.header("Authorization","Bearer "+bearer);
        return http.send(request.build(),HttpResponse.BodyHandlers.ofString());
    }
    private void scanningSummaryNative(HttpResponse<String> reply,int expected) {
        assertEquals(expected,reply.statusCode());
        assertEquals("no-store",reply.headers().firstValue("Cache-Control").orElse(""));
        if (expected == 401) assertTrue(reply.headers().allValues("WWW-Authenticate").stream()
                .anyMatch(value -> value.startsWith("Bearer")), "Expected native Bearer challenge");
        assertTrue(!reply.body().contains(doctor) && !reply.body().contains(patient), "Response exposed a synthetic credential");
        // Native rejection body semantics belong to Quarkus; never assert a fabricated fixed JSON body.
    }
    private com.fasterxml.jackson.databind.JsonNode scanningSummaryBody(HttpResponse<String> reply) {
        assertTrue(reply.headers().firstValue("Content-Type").orElse("").startsWith("application/json"), "Expected summary JSON");
        try {
            var value = mapper.readTree(reply.body());
            assertTrue(value != null && value.isObject(), "Expected summary object; raw response suppressed");
            return value;
        } catch (java.io.IOException invalid) { return fail("Invalid summary JSON; raw response suppressed"); }
    }
    private com.fasterxml.jackson.databind.JsonNode scanningSummaryJson(HttpResponse<String> reply,String origin) {
        scanningSummaryNative(reply,200); var value = scanningSummaryBody(reply); assertEquals(11,value.size());
        assertTrue("standard-scanning-trial-v1".equals(value.path("calculationVersion").textValue()), "Wrong calculation version");
        assertTrue(SESSION.equals(value.path("therapySessionId").textValue()), "Wrong session binding");
        assertTrue("00000000-0000-0000-0000-000000000002".equals(value.path("configurationId").textValue()), "Wrong frozen configuration");
        assertEquals(3,value.path("configurationVersion").longValue());
        assertTrue(origin.equals(value.path("dataOrigin").textValue()), "Wrong data origin");
        return value;
    }
    private void scanningSummaryError(HttpResponse<String> reply,int expected,String code) {
        scanningSummaryNative(reply,expected); var value = scanningSummaryBody(reply);
        assertTrue(value.size() == 1 && ("STANDARD_SCANNING_SUMMARY_"+code).equals(value.path("code").textValue()),
                "Expected only fixed summary error code; raw response suppressed");
    }
    private void scanningSummaryCounts(int clinical,int readCount,int records) {
        var observed = peer.standardScanningSummaryObservation(); assertEquals(clinical,observed.requests());
        assertTrue(observed.exactWire(), "Summary authorization did not forward the exact DOCTOR request");
        assertEquals(0,peer.visualSearchObservation().requests(), "Summary called PATIENT admission");
        assertEquals(0,peer.visualSearchSummaryObservation().requests(), "Scanning summary called Visual Search summary");
        assertEquals(0,peer.standardScanningObservation().requests(), "Scanning summary called PATIENT scanning admission");
        assertEquals(0,stub.observation().reads()); assertEquals(0,stub.observation().writes());
        assertEquals(0,stub.observation().records()); assertEquals(0,stub.summaryObservation().reads());
        var read = scanningStub.summaryObservation(); assertEquals(readCount,read.reads()); assertEquals(readCount == 0 ? 0 : 1001,read.limit());
        var stored = scanningStub.observation(); assertEquals(0,stored.reads()); assertEquals(0,stored.writes()); assertEquals(records,stored.records());
    }
    private static StandardScanningTrialResultStore.Stored scanningSummaryRow(String id,Outcome outcome,long duration,DataOrigin origin) {
        var context = new com.neurovision.rehab.analytics.domain.model.valueobjects.StandardScanningTrialContext(SESSION_ID,new UUID(0,2),3,500,3000);
        return new StandardScanningTrialResultStore.Stored(context,new com.neurovision.rehab.analytics.domain.model.valueobjects.StandardScanningTrialResult(1,SESSION_ID,id,outcome,duration,
                outcome == Outcome.SUCCESS ? 500L : null,origin));
    }
    private static String scanningSummaryPermit() {
        return "{\"contractVersion\":1,\"permitted\":true,\"therapySessionId\":\""+SESSION
                +"\",\"configurationId\":\"00000000-0000-0000-0000-000000000002\","
                +"\"configurationVersion\":3,\"minimumFixationMs\":500,\"maximumStimulusDurationMs\":3000}";
    }
    @Test void fixationDoctorSummaryUsesProductionCompositionWithExactMetricsOriginsAndEmptyEvidence() throws Exception {
        var empty = fixationSummaryJson(fixationSummaryGet(doctor,SESSION,"SYNTHETIC"),"SYNTHETIC");
        assertFixationMetrics(empty,0,0,0);
        fixationSummaryCounts(1,1,0);
        fixationStub.seedSummary(List.of(fixationSummaryRow("success",Outcome.SUCCESS,1000,DataOrigin.SYNTHETIC),
                fixationSummaryRow("timeout",Outcome.TIMEOUT,3000,DataOrigin.SYNTHETIC),
                fixationSummaryRow("interrupted",Outcome.INTERRUPTED,200,DataOrigin.SYNTHETIC),
                fixationSummaryRow("reported",Outcome.SUCCESS,2000,DataOrigin.PRODUCER_REPORTED)));
        var snapshot = fixationStub.summarySnapshot();

        assertFixationMetrics(fixationSummaryJson(fixationSummaryGet(doctor,SESSION,"SYNTHETIC"),"SYNTHETIC"),3,850,1);
        assertFixationMetrics(fixationSummaryJson(fixationSummaryGet(doctor,SESSION,"PRODUCER_REPORTED"),"PRODUCER_REPORTED"),1,650,1);
        fixationSummaryCounts(3,3,4);
        assertTrue(snapshot.equals(fixationStub.summarySnapshot()), "Summary changed stored fixture records");
    }

    @Test void fixationSummaryPreservesNative401BodyAndRejectsWrongAndMixedRoles() throws Exception {
        String[] parts = doctor.split("\\.");
        String damaged = (parts[2].charAt(0) == 'A' ? "B" : "A") + parts[2].substring(1);
        for (String bearer : new String[]{null,parts[0]+"."+parts[1]+"."+damaged}) {
            var nativeBaseline = summaryGet(bearer,SESSION,"SYNTHETIC");
            var fixation = fixationSummaryGet(bearer,SESSION,"SYNTHETIC");
            summaryNative(nativeBaseline,401);
            summaryNative(fixation,401);
            assertTrue(nativeBaseline.body().equals(fixation.body()), "The summary filter must preserve the native rejection body");
            if (bearer != null) assertFalse(fixation.body().contains(bearer), "Response exposed a synthetic credential");
        }
        summaryNative(fixationSummaryGet(patient,SESSION,"SYNTHETIC"),403);
        fixationSummaryError(fixationSummaryGet(token(List.of("DOCTOR","PATIENT")),SESSION,"SYNTHETIC"),403,"DENIED");
        fixationSummaryCounts(0,0,0);
    }

    @Test void fixationSummaryClinicalRevocationAndUnavailabilityNeverReadAfterPermissionIsLost() throws Exception {
        fixationSummaryJson(fixationSummaryGet(doctor,SESSION,"SYNTHETIC"),"SYNTHETIC");
        fixationSummaryCounts(1,1,0);
        peer.sustainedFixationSummaryReply(403,"{}");
        fixationSummaryError(fixationSummaryGet(doctor,SESSION,"SYNTHETIC"),403,"DENIED");
        fixationSummaryCounts(2,1,0);
        peer.sustainedFixationSummaryReply(503,"{}");
        fixationSummaryError(fixationSummaryGet(doctor,SESSION,"SYNTHETIC"),503,"UNAVAILABLE");
        fixationSummaryCounts(3,1,0);
    }

    @Test void fixationSummaryInvalidSessionAndRequiredOriginStopBeforeClinicalAndStorage() throws Exception {
        fixationSummaryError(fixationSummaryGet(doctor,"invalid","SYNTHETIC"),400,"INVALID_REQUEST");
        fixationSummaryError(fixationSummaryGet(doctor,SESSION,null),400,"INVALID_REQUEST");
        fixationSummaryError(fixationSummaryGet(doctor,SESSION,"ALL"),400,"INVALID_REQUEST");
        fixationSummaryCounts(0,0,0);
    }

    @Test void fixationSummaryReaderFailureAndOverflowNeverReturnPartialSuccessOrChangeRecords() throws Exception {
        fixationStub.seedSummary(java.util.stream.IntStream.range(0,1001)
                .mapToObj(i -> fixationSummaryRow("limit-"+i,Outcome.SUCCESS,1000,DataOrigin.SYNTHETIC)).toList());
        var snapshot = fixationStub.summarySnapshot();
        fixationStub.failSummaryReads(true);
        fixationSummaryError(fixationSummaryGet(doctor,SESSION,"SYNTHETIC"),503,"UNAVAILABLE");
        fixationSummaryCounts(1,1,1001);
        fixationStub.failSummaryReads(false);
        fixationSummaryError(fixationSummaryGet(doctor,SESSION,"SYNTHETIC"),422,"LIMIT_EXCEEDED");
        fixationSummaryCounts(2,2,1001);
        assertTrue(snapshot.equals(fixationStub.summarySnapshot()), "Failure or overflow changed fixture records");
    }

    private HttpResponse<String> fixationSummaryGet(String bearer,String session,String origin) throws Exception {
        String path = "/api/v1/analytics/sessions/"+session+"/sustained-fixation/summary"
                + (origin == null ? "" : "?dataOrigin="+origin);
        var request = HttpRequest.newBuilder(base.resolve(path)).timeout(Duration.ofSeconds(5))
                .header("Accept","application/json").GET();
        if (bearer != null) request.header("Authorization","Bearer "+bearer);
        return http.send(request.build(),HttpResponse.BodyHandlers.ofString());
    }

    private com.fasterxml.jackson.databind.JsonNode fixationSummaryJson(HttpResponse<String> reply,String origin) {
        summaryNative(reply,200);
        var value = summaryBody(reply);
        assertEquals(8,value.size());
        assertTrue("sustained-fixation-trial-v1".equals(value.path("calculationVersion").textValue()), "Wrong calculation version");
        assertTrue(SESSION.equals(value.path("therapySessionId").textValue()), "Wrong session binding");
        assertTrue(new UUID(0,2).toString().equals(value.path("configurationId").textValue()), "Wrong frozen configuration");
        assertTrue(value.path("configurationVersion").isIntegralNumber());
        assertEquals(3,value.path("configurationVersion").longValue());
        assertTrue(origin.equals(value.path("dataOrigin").textValue()), "Wrong data origin");
        return value;
    }

    private void assertFixationMetrics(com.fasterxml.jackson.databind.JsonNode value,long attempts,long time,long completed) {
        for (String name : List.of("reportedTrialCount","totalInsideTargetMs","completedTargetCount"))
            assertTrue(value.path(name).isIntegralNumber(), name);
        assertEquals(attempts,value.path("reportedTrialCount").longValue());
        assertEquals(time,value.path("totalInsideTargetMs").longValue());
        assertEquals(completed,value.path("completedTargetCount").longValue());
    }

    private void fixationSummaryError(HttpResponse<String> reply,int expected,String code) {
        summaryNative(reply,expected);
        var value = summaryBody(reply);
        assertTrue(value.size() == 1 && ("SUSTAINED_FIXATION_SUMMARY_"+code).equals(value.path("code").textValue()),
                "Expected only fixed summary error code; raw response suppressed");
    }

    private void fixationSummaryCounts(int clinical,int reads,int records) {
        var observed = peer.sustainedFixationSummaryObservation();
        assertEquals(clinical,observed.requests());
        assertTrue(observed.exactWire(), "Summary authorization did not forward the exact DOCTOR request");
        assertEquals(0,peer.visualSearchObservation().requests());
        assertEquals(0,peer.visualSearchSummaryObservation().requests());
        assertEquals(0,peer.standardScanningObservation().requests());
        assertEquals(0,peer.standardScanningSummaryObservation().requests());
        assertEquals(0,peer.sustainedFixationObservation().requests());
        assertEquals(0,stub.summaryObservation().reads());
        assertEquals(0,scanningStub.summaryObservation().reads());
        assertEquals(0,stub.observation().reads());
        assertEquals(0,stub.observation().writes());
        assertEquals(0,scanningStub.observation().reads());
        assertEquals(0,scanningStub.observation().writes());
        var read = fixationStub.summaryObservation();
        assertEquals(reads,read.reads()); assertEquals(reads == 0 ? 0 : 1001,read.limit());
        var stored = fixationStub.observation();
        assertEquals(0,stored.reads()); assertEquals(0,stored.writes()); assertEquals(records,stored.records());
    }

    private static SustainedFixationTrialResultStore.Stored fixationSummaryRow(String id,Outcome outcome,long duration,DataOrigin origin) {
        var context = new SustainedFixationTrialContext(SESSION_ID,new UUID(0,2),3,500,3000);
        return new SustainedFixationTrialResultStore.Stored(context,new SustainedFixationTrialResult(1,SESSION_ID,id,outcome,duration,
                outcome == Outcome.SUCCESS ? 650L : 100L,outcome == Outcome.SUCCESS ? 500L : 100L,origin));
    }

    private static String fixationSummaryPermit() {
        return "{\"contractVersion\":1,\"permitted\":true,\"therapySessionId\":\""+SESSION
                +"\",\"configurationId\":\"00000000-0000-0000-0000-000000000002\","
                +"\"configurationVersion\":3,\"minimumFixationMs\":500,\"maximumFixationMs\":3000}";
    }

    @Test void saccadeDoctorSummaryUsesProductionCompositionWithExactMetricsOriginsAndEmptyEvidence() throws Exception {
        var empty = saccadeSummaryJson(saccadeSummaryGet(doctor,SESSION,"SYNTHETIC"),"SYNTHETIC");
        assertSaccadeMetrics(empty,0,0,null);
        saccadeSummaryCounts(1,1,0);
        saccadeStub.seedSummary(List.of(saccadeSummaryRow("success",Outcome.SUCCESS,1000,DataOrigin.SYNTHETIC),
                saccadeSummaryRow("timeout",Outcome.TIMEOUT,3000,DataOrigin.SYNTHETIC),
                saccadeSummaryRow("interrupted",Outcome.INTERRUPTED,200,DataOrigin.SYNTHETIC),
                saccadeSummaryRow("reported",Outcome.SUCCESS,2000,DataOrigin.PRODUCER_REPORTED)));
        var snapshot = saccadeStub.summarySnapshot();

        assertSaccadeMetrics(saccadeSummaryJson(saccadeSummaryGet(doctor,SESSION,"SYNTHETIC"),"SYNTHETIC"),3,1,1000L);
        assertSaccadeMetrics(saccadeSummaryJson(saccadeSummaryGet(doctor,SESSION,"PRODUCER_REPORTED"),"PRODUCER_REPORTED"),1,1,2000L);
        saccadeSummaryCounts(3,3,4);
        assertTrue(snapshot.equals(saccadeStub.summarySnapshot()), "Summary changed stored fixture records");
    }

    @Test void saccadeSummaryPreservesNative401BodyAndRejectsWrongAndMixedRoles() throws Exception {
        String[] parts = doctor.split("\\.");
        String damaged = (parts[2].charAt(0) == 'A' ? "B" : "A") + parts[2].substring(1);
        for (String bearer : new String[]{null,parts[0]+"."+parts[1]+"."+damaged}) {
            var nativeBaseline = summaryGet(bearer,SESSION,"SYNTHETIC");
            var saccade = saccadeSummaryGet(bearer,SESSION,"SYNTHETIC");
            summaryNative(nativeBaseline,401);
            summaryNative(saccade,401);
            assertTrue(nativeBaseline.body().equals(saccade.body()), "The summary filter must preserve the native rejection body");
            assertTrue(nativeBaseline.headers().allValues("WWW-Authenticate")
                    .equals(saccade.headers().allValues("WWW-Authenticate")), "The summary filter must preserve the native challenge");
            if (bearer != null) assertFalse(saccade.body().contains(bearer), "Response exposed a synthetic credential");
        }
        summaryNative(saccadeSummaryGet(patient,SESSION,"SYNTHETIC"),403);
        saccadeSummaryError(saccadeSummaryGet(token(List.of("DOCTOR","PATIENT")),SESSION,"SYNTHETIC"),403,"DENIED");
        saccadeSummaryCounts(0,0,0);
    }

    @Test void saccadeSummaryClinicalRevocationAndUnavailabilityNeverReadAfterPermissionIsLost() throws Exception {
        saccadeSummaryJson(saccadeSummaryGet(doctor,SESSION,"SYNTHETIC"),"SYNTHETIC");
        saccadeSummaryCounts(1,1,0);
        peer.compensatorySaccadeSummaryReply(403,"{}");
        saccadeSummaryError(saccadeSummaryGet(doctor,SESSION,"SYNTHETIC"),403,"DENIED");
        saccadeSummaryCounts(2,1,0);
        peer.compensatorySaccadeSummaryReply(503,"{}");
        saccadeSummaryError(saccadeSummaryGet(doctor,SESSION,"SYNTHETIC"),503,"UNAVAILABLE");
        saccadeSummaryCounts(3,1,0);
    }

    @Test void saccadeSummaryInvalidSessionAndRequiredOriginStopBeforeClinicalAndStorage() throws Exception {
        saccadeSummaryError(saccadeSummaryGet(doctor,"invalid","SYNTHETIC"),400,"INVALID_REQUEST");
        saccadeSummaryError(saccadeSummaryGet(doctor,SESSION,null),400,"INVALID_REQUEST");
        saccadeSummaryError(saccadeSummaryGet(doctor,SESSION,"ALL"),400,"INVALID_REQUEST");
        saccadeSummaryCounts(0,0,0);
    }

    @Test void saccadeSummaryReaderFailureAndOverflowNeverReturnPartialSuccessOrChangeRecords() throws Exception {
        saccadeStub.seedSummary(java.util.stream.IntStream.range(0,1001)
                .mapToObj(i -> saccadeSummaryRow("limit-"+i,Outcome.SUCCESS,1000,DataOrigin.SYNTHETIC)).toList());
        var snapshot = saccadeStub.summarySnapshot();
        saccadeStub.failSummaryReads(true);
        saccadeSummaryError(saccadeSummaryGet(doctor,SESSION,"SYNTHETIC"),503,"UNAVAILABLE");
        saccadeSummaryCounts(1,1,1001);
        saccadeStub.failSummaryReads(false);
        saccadeSummaryError(saccadeSummaryGet(doctor,SESSION,"SYNTHETIC"),422,"LIMIT_EXCEEDED");
        saccadeSummaryCounts(2,2,1001);
        assertTrue(snapshot.equals(saccadeStub.summarySnapshot()), "Failure or overflow changed fixture records");
    }

    private HttpResponse<String> saccadeSummaryGet(String bearer,String session,String origin) throws Exception {
        String path = "/api/v1/analytics/sessions/"+session+"/compensatory-saccade/summary"
                + (origin == null ? "" : "?dataOrigin="+origin);
        var request = HttpRequest.newBuilder(base.resolve(path)).timeout(Duration.ofSeconds(5))
                .header("Accept","application/json").GET();
        if (bearer != null) request.header("Authorization","Bearer "+bearer);
        return http.send(request.build(),HttpResponse.BodyHandlers.ofString());
    }

    private com.fasterxml.jackson.databind.JsonNode saccadeSummaryJson(HttpResponse<String> reply,String origin) {
        summaryNative(reply,200);
        var value = summaryBody(reply);
        assertEquals(8,value.size());
        assertTrue("compensatory-saccade-trial-v1".equals(value.path("calculationVersion").textValue()), "Wrong calculation version");
        assertTrue(SESSION.equals(value.path("therapySessionId").textValue()), "Wrong session binding");
        assertTrue(new UUID(0,2).toString().equals(value.path("configurationId").textValue()), "Wrong frozen configuration");
        assertTrue(value.path("configurationVersion").isIntegralNumber());
        assertEquals(3,value.path("configurationVersion").longValue());
        assertTrue(origin.equals(value.path("dataOrigin").textValue()), "Wrong data origin");
        return value;
    }

    private void assertSaccadeMetrics(com.fasterxml.jackson.databind.JsonNode value,long attempts,long reached,Long median) {
        assertTrue(value.path("reportedTrialCount").isIntegralNumber());
        assertTrue(value.path("reachedTargetCount").isIntegralNumber());
        assertEquals(attempts,value.path("reportedTrialCount").longValue());
        assertEquals(reached,value.path("reachedTargetCount").longValue());
        var time = value.path("medianTimeToTargetMs");
        if (median == null) assertTrue(time.isNull(), "Expected an explicit null median");
        else {
            assertTrue(time.isNumber(), "Expected a numeric median");
            assertEquals(0,java.math.BigDecimal.valueOf(median).compareTo(time.decimalValue()));
        }
    }

    private void saccadeSummaryError(HttpResponse<String> reply,int expected,String code) {
        summaryNative(reply,expected);
        var value = summaryBody(reply);
        assertTrue(value.size() == 1 && ("COMPENSATORY_SACCADE_SUMMARY_"+code).equals(value.path("code").textValue()),
                "Expected only fixed summary error code; raw response suppressed");
    }

    private void saccadeSummaryCounts(int clinical,int reads,int records) {
        var observed = peer.compensatorySaccadeSummaryObservation();
        assertEquals(clinical,observed.requests());
        assertTrue(observed.exactWire(), "Summary authorization did not forward the exact DOCTOR request");
        assertEquals(0,peer.visualSearchObservation().requests());
        assertEquals(0,peer.visualSearchSummaryObservation().requests());
        assertEquals(0,peer.standardScanningObservation().requests());
        assertEquals(0,peer.standardScanningSummaryObservation().requests());
        assertEquals(0,peer.sustainedFixationObservation().requests());
        assertEquals(0,peer.sustainedFixationSummaryObservation().requests());
        assertEquals(0,peer.compensatorySaccadeObservation().requests());
        assertEquals(0,fixationStub.summaryObservation().reads());
        assertEquals(0,stub.summaryObservation().reads());
        assertEquals(0,scanningStub.summaryObservation().reads());
        assertEquals(0,stub.observation().reads());
        assertEquals(0,stub.observation().writes());
        assertEquals(0,scanningStub.observation().reads());
        assertEquals(0,scanningStub.observation().writes());
        var read = saccadeStub.summaryObservation();
        assertEquals(reads,read.reads()); assertEquals(reads == 0 ? 0 : 1001,read.limit());
        var stored = saccadeStub.observation();
        assertEquals(0,stored.reads()); assertEquals(0,stored.writes()); assertEquals(records,stored.records());
    }

    private static CompensatorySaccadeTrialResultStore.Stored saccadeSummaryRow(String id,Outcome outcome,long duration,DataOrigin origin) {
        var context = new CompensatorySaccadeTrialContext(SESSION_ID,new UUID(0,2),3,500,3000);
        return new CompensatorySaccadeTrialResultStore.Stored(context,new CompensatorySaccadeTrialResult(SESSION_ID,id,origin,outcome,duration,
                outcome == Outcome.SUCCESS ? 500L : null));
    }

    @Test void systematicDoctorSummaryUsesProductionCompositionWithExactTraversalsOriginsAndEmptyEvidence() throws Exception {
        var empty = systematicSummaryJson(systematicSummaryGet(doctor,SESSION,"SYNTHETIC"),"SYNTHETIC");
        assertEquals(0,empty.path("reportedTrialCount").longValue());
        assertTrue(empty.path("traversals").isArray() && empty.path("traversals").isEmpty());
        systematicSummaryCounts(1,1,0);
        var partial = new SystematicExplorationTrialResultStore.Stored(systematicSummaryContext(),
                new SystematicExplorationTrialResult(SESSION_ID,"b",DataOrigin.SYNTHETIC,
                        SystematicExplorationTrialResult.Outcome.TIMED_OUT,4700,List.of(
                                new SystematicExplorationTrialResult.Visit(SystematicExplorationTrialResult.Quadrant.TOP_LEFT,800,500),
                                new SystematicExplorationTrialResult.Visit(SystematicExplorationTrialResult.Quadrant.TOP_RIGHT,900,500))));
        systematicStub.seedSummary(List.of(
                systematicSummaryRow("z",SystematicExplorationTrialResult.Outcome.INTERRUPTED,200,DataOrigin.SYNTHETIC),
                partial,systematicSummaryRow("a",SystematicExplorationTrialResult.Outcome.COMPLETED,1000,DataOrigin.SYNTHETIC),
                systematicSummaryRow("reported",SystematicExplorationTrialResult.Outcome.COMPLETED,2000,DataOrigin.PRODUCER_REPORTED)));
        var snapshot = systematicStub.summarySnapshot();
        var synthetic = systematicSummaryJson(systematicSummaryGet(doctor,SESSION,"SYNTHETIC"),"SYNTHETIC");
        assertEquals(3,synthetic.path("reportedTrialCount").longValue());
        assertEquals(3,synthetic.path("traversals").size());
        assertSystematicTraversal(synthetic.path("traversals").get(0),"a","COMPLETED",4,100,4000);
        assertSystematicTraversal(synthetic.path("traversals").get(1),"b","TIMED_OUT",2,50,4700);
        assertSystematicTraversal(synthetic.path("traversals").get(2),"z","INTERRUPTED",0,0,200);
        var reported = systematicSummaryJson(systematicSummaryGet(doctor,SESSION,"PRODUCER_REPORTED"),"PRODUCER_REPORTED");
        assertEquals(1,reported.path("reportedTrialCount").longValue()); assertEquals(1,reported.path("traversals").size());
        assertSystematicTraversal(reported.path("traversals").get(0),"reported","COMPLETED",4,100,8000);
        systematicSummaryCounts(3,3,4);
        assertTrue(snapshot.equals(systematicStub.summarySnapshot()),"Summary changed stored fixture records");
    }

    @Test void systematicSummaryPreservesNative401BodyAndRejectsWrongAndMixedRoles() throws Exception {
        String[] parts = doctor.split("\\.");
        String damaged = (parts[2].charAt(0) == 'A' ? "B" : "A") + parts[2].substring(1);
        for (String bearer : new String[]{null,parts[0]+"."+parts[1]+"."+damaged}) {
            var nativeBaseline = summaryGet(bearer,SESSION,"SYNTHETIC");
            var systematic = systematicSummaryGet(bearer,SESSION,"SYNTHETIC");
            summaryNative(nativeBaseline,401);
            summaryNative(systematic,401);
            assertTrue(nativeBaseline.body().equals(systematic.body()), "The summary filter must preserve the native rejection body");
            assertTrue(nativeBaseline.headers().allValues("WWW-Authenticate")
                    .equals(systematic.headers().allValues("WWW-Authenticate")), "The summary filter must preserve the native challenge");
            if (bearer != null) assertFalse(systematic.body().contains(bearer), "Response exposed a synthetic credential");
        }
        var nativeForbidden = summaryGet(patient,SESSION,"SYNTHETIC");
        var forbidden = systematicSummaryGet(patient,SESSION,"SYNTHETIC");
        summaryNative(nativeForbidden,403); summaryNative(forbidden,403);
        assertTrue(nativeForbidden.body().equals(forbidden.body()),"The summary filter must preserve the native forbidden body");
        systematicSummaryError(systematicSummaryGet(token(List.of("DOCTOR","PATIENT")),SESSION,"SYNTHETIC"),403,"DENIED");
        systematicSummaryCounts(0,0,0);
    }

    @Test void systematicSummaryClinicalRevocationAndUnavailabilityNeverReadAfterPermissionIsLost() throws Exception {
        systematicSummaryJson(systematicSummaryGet(doctor,SESSION,"SYNTHETIC"),"SYNTHETIC");
        systematicSummaryCounts(1,1,0);
        peer.systematicExplorationSummaryReply(403,"{}");
        systematicSummaryError(systematicSummaryGet(doctor,SESSION,"SYNTHETIC"),403,"DENIED");
        systematicSummaryCounts(2,1,0);
        peer.systematicExplorationSummaryReply(503,"{}");
        systematicSummaryError(systematicSummaryGet(doctor,SESSION,"SYNTHETIC"),503,"UNAVAILABLE");
        systematicSummaryCounts(3,1,0);
    }

    @Test void systematicSummaryInvalidAndRepeatedOriginsPreserveEmptyOccurrencesAndStopBeforePorts() throws Exception {
        systematicSummaryError(systematicSummaryGet(doctor,"invalid","SYNTHETIC"),400,"INVALID_REQUEST");
        systematicSummaryError(systematicSummaryGet(doctor,SESSION,null),400,"INVALID_REQUEST");
        systematicSummaryError(systematicSummaryGet(doctor,SESSION,"ALL"),400,"INVALID_REQUEST");
        systematicSummaryCounts(0,0,0);
        // Raw query text: never build a map or let a query builder collapse duplicates or empty values.
        for (String query : List.of("dataOrigin=SYNTHETIC&dataOrigin=SYNTHETIC",
                "dataOrigin=SYNTHETIC&dataOrigin=PRODUCER_REPORTED", "dataOrigin=PRODUCER_REPORTED&dataOrigin=SYNTHETIC",
                "dataOrigin=SYNTHETIC&dataOrigin=", "dataOrigin=&dataOrigin=SYNTHETIC", "dataOrigin=&dataOrigin=")) {
            systematicSummaryError(systematicSummaryRawGet(doctor,SESSION,query),400,"INVALID_REQUEST");
            systematicSummaryCounts(0,0,0);
        }
        var control = systematicSummaryJson(systematicSummaryGet(doctor,SESSION,"SYNTHETIC"),"SYNTHETIC");
        assertEquals(0,control.path("reportedTrialCount").longValue()); assertTrue(control.path("traversals").isEmpty());
        systematicSummaryCounts(1,1,0);
    }

    @Test void systematicSummaryReaderFailureAndOverflowNeverReturnPartialSuccessOrChangeRecords() throws Exception {
        systematicStub.seedSummary(java.util.stream.IntStream.range(0,1001)
                .mapToObj(i -> systematicSummaryRow("limit-"+i,SystematicExplorationTrialResult.Outcome.COMPLETED,1000,DataOrigin.SYNTHETIC)).toList());
        var snapshot = systematicStub.summarySnapshot();
        systematicStub.failSummaryReads(true);
        systematicSummaryError(systematicSummaryGet(doctor,SESSION,"SYNTHETIC"),503,"UNAVAILABLE");
        systematicSummaryCounts(1,1,1001);
        systematicStub.failSummaryReads(false);
        systematicSummaryError(systematicSummaryGet(doctor,SESSION,"SYNTHETIC"),422,"LIMIT_EXCEEDED");
        systematicSummaryCounts(2,2,1001);
        assertTrue(snapshot.equals(systematicStub.summarySnapshot()), "Failure or overflow changed fixture records");
    }

    private HttpResponse<String> systematicSummaryGet(String bearer,String session,String origin) throws Exception {
        return systematicSummaryRawGet(bearer,session,origin == null ? null : "dataOrigin="+origin);
    }
    private HttpResponse<String> systematicSummaryRawGet(String bearer,String session,String query) throws Exception {
        String path = "/api/v1/analytics/sessions/"+session+"/systematic-exploration/summary"
                + (query == null ? "" : "?"+query);
        var request = HttpRequest.newBuilder(base.resolve(path)).timeout(Duration.ofSeconds(5))
                .header("Accept","application/json").GET();
        if (bearer != null) request.header("Authorization","Bearer "+bearer);
        return http.send(request.build(),HttpResponse.BodyHandlers.ofString());
    }

    private com.fasterxml.jackson.databind.JsonNode systematicSummaryJson(HttpResponse<String> reply,String origin) {
        summaryNative(reply,200);
        var value = summaryBody(reply);
        assertEquals(7,value.size());
        assertTrue("systematic-exploration-trial-v1".equals(value.path("calculationVersion").textValue()), "Wrong calculation version");
        assertTrue(SESSION.equals(value.path("therapySessionId").textValue()), "Wrong session binding");
        assertTrue(new UUID(0,2).toString().equals(value.path("configurationId").textValue()), "Wrong frozen configuration");
        assertTrue(value.path("configurationVersion").isIntegralNumber());
        assertEquals(3,value.path("configurationVersion").longValue());
        assertTrue(origin.equals(value.path("dataOrigin").textValue()), "Wrong data origin");
        return value;
    }

    private void assertSystematicTraversal(com.fasterxml.jackson.databind.JsonNode value,String id,String outcome,
            int visited,int coverage,long elapsed) {
        assertEquals(5,value.size());
        assertTrue(id.equals(value.path("trialId").textValue()),"Wrong traversal identity");
        assertTrue(outcome.equals(value.path("outcome").textValue()),"Wrong traversal outcome");
        assertTrue(value.path("visitedRegionCount").isIntegralNumber() && value.path("coveragePercent").isIntegralNumber()
                && value.path("elapsedMs").isIntegralNumber());
        assertEquals(visited,value.path("visitedRegionCount").intValue());
        assertEquals(coverage,value.path("coveragePercent").intValue()); assertEquals(elapsed,value.path("elapsedMs").longValue());
    }

    private void systematicSummaryError(HttpResponse<String> reply,int expected,String code) {
        summaryNative(reply,expected);
        var value = summaryBody(reply);
        assertTrue(value.size() == 1 && ("SYSTEMATIC_EXPLORATION_SUMMARY_"+code).equals(value.path("code").textValue()),
                "Expected only fixed summary error code; raw response suppressed");
    }

    private void systematicSummaryCounts(int clinical,int reads,int records) {
        var observed = peer.systematicExplorationSummaryObservation();
        assertEquals(clinical,observed.requests());
        assertTrue(observed.exactWire(),"Summary authorization did not forward the exact DOCTOR request");
        assertEquals(0,peer.visualSearchObservation().requests()); assertEquals(0,peer.visualSearchSummaryObservation().requests());
        assertEquals(0,peer.standardScanningObservation().requests()); assertEquals(0,peer.standardScanningSummaryObservation().requests());
        assertEquals(0,peer.sustainedFixationObservation().requests()); assertEquals(0,peer.sustainedFixationSummaryObservation().requests());
        assertEquals(0,peer.compensatorySaccadeObservation().requests()); assertEquals(0,peer.compensatorySaccadeSummaryObservation().requests());
        assertEquals(0,peer.systematicExplorationObservation().requests());
        assertEquals(0,stub.summaryObservation().reads()); assertEquals(0,scanningStub.summaryObservation().reads());
        assertEquals(0,fixationStub.summaryObservation().reads()); assertEquals(0,saccadeStub.summaryObservation().reads());
        assertEquals(0,stub.observation().reads()); assertEquals(0,stub.observation().writes());
        assertEquals(0,scanningStub.observation().reads()); assertEquals(0,scanningStub.observation().writes());
        assertEquals(0,fixationStub.observation().reads()); assertEquals(0,fixationStub.observation().writes());
        assertEquals(0,saccadeStub.observation().reads()); assertEquals(0,saccadeStub.observation().writes());
        var read = systematicStub.summaryObservation();
        assertEquals(reads,read.reads()); assertEquals(reads == 0 ? 0 : 1001,read.limit());
        var stored = systematicStub.observation();
        assertEquals(0,stored.reads()); assertEquals(0,stored.writes()); assertEquals(records,stored.records());
    }

    private static SystematicExplorationTrialContext systematicSummaryContext() {
        return new SystematicExplorationTrialContext(SESSION_ID,new UUID(0,2),3,500,3000);
    }
    private static SystematicExplorationTrialResultStore.Stored systematicSummaryRow(String id,
            SystematicExplorationTrialResult.Outcome outcome,long duration,DataOrigin origin) {
        var visits = outcome == SystematicExplorationTrialResult.Outcome.COMPLETED
                ? java.util.Arrays.stream(SystematicExplorationTrialResult.Quadrant.values())
                        .map(q -> new SystematicExplorationTrialResult.Visit(q,duration,500)).toList()
                : List.<SystematicExplorationTrialResult.Visit>of();
        return new SystematicExplorationTrialResultStore.Stored(systematicSummaryContext(),
                new SystematicExplorationTrialResult(SESSION_ID,id,origin,outcome,
                        outcome == SystematicExplorationTrialResult.Outcome.COMPLETED ? duration * 4 : duration,visits));
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
