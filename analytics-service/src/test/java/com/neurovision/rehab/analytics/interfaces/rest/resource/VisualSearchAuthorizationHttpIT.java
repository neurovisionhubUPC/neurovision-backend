package com.neurovision.rehab.analytics.interfaces.rest.resource;

import static org.junit.jupiter.api.Assertions.*;
import com.neurovision.rehab.analytics.application.ports.out.authorization.SystematicExplorationTrialSummaryAuthorizationPort;
import com.neurovision.rehab.analytics.infrastructure.integration.ClinicalSystematicExplorationSummaryAuthorizationAdapter;
import com.neurovision.rehab.analytics.application.ports.out.authorization.CompensatorySaccadeTrialSummaryAuthorizationPort;
import com.neurovision.rehab.analytics.application.ports.out.authorization.CompensatorySaccadeTrialAuthorizationPort;
import com.neurovision.rehab.analytics.infrastructure.integration.ClinicalCompensatorySaccadeSummaryAuthorizationAdapter;
import com.neurovision.rehab.analytics.infrastructure.integration.ClinicalCompensatorySaccadeTrialAuthorizationAdapter;
import com.neurovision.rehab.analytics.application.ports.out.authorization.SystematicExplorationTrialAuthorizationPort;
import com.neurovision.rehab.analytics.infrastructure.integration.ClinicalSystematicExplorationTrialAuthorizationAdapter;
import com.neurovision.rehab.analytics.application.ports.out.authorization.VisualSearchTrialAuthorizationPort;
import com.neurovision.rehab.analytics.application.ports.out.authorization.VisualSearchTrialSummaryAuthorizationPort;
import com.neurovision.rehab.analytics.application.ports.out.authorization.VisualSearchTrialAuthorizationPort.Permission;
import com.neurovision.rehab.analytics.infrastructure.integration.ClinicalVisualSearchTrialAuthorizationAdapter;
import com.neurovision.rehab.analytics.infrastructure.integration.ClinicalVisualSearchSummaryAuthorizationAdapter;
import com.neurovision.rehab.analytics.application.ports.out.authorization.StandardScanningTrialAuthorizationPort;
import com.neurovision.rehab.analytics.infrastructure.integration.ClinicalStandardScanningTrialAuthorizationAdapter;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import com.neurovision.rehab.analytics.application.ports.out.authorization.StandardScanningTrialSummaryAuthorizationPort;
import com.neurovision.rehab.analytics.infrastructure.integration.ClinicalStandardScanningSummaryAuthorizationAdapter;
import com.neurovision.rehab.analytics.application.ports.out.authorization.SustainedFixationTrialAuthorizationPort;
import com.neurovision.rehab.analytics.application.ports.out.authorization.SustainedFixationTrialSummaryAuthorizationPort;
import com.neurovision.rehab.analytics.infrastructure.integration.ClinicalSustainedFixationSummaryAuthorizationAdapter;
import com.neurovision.rehab.analytics.infrastructure.integration.ClinicalSustainedFixationTrialAuthorizationAdapter;
import jakarta.inject.Inject;
import java.util.UUID;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;

/** Prepared real outbound HTTP checks; no REST reception endpoint or live Clinical instance is involved. */
@QuarkusTest
@TestProfile(VisualSearchAuthorizationHttpProfile.class)
@Execution(ExecutionMode.SAME_THREAD)
@Timeout(30)
class VisualSearchAuthorizationHttpIT {
    private static final UUID SESSION = UUID.fromString(ClinicalHeatmapAuthorizationStubResource.ALLOWED_SESSION);
    private static final UUID CONFIGURATION = new UUID(0, 2);
    @Inject VisualSearchTrialAuthorizationPort authorization;
    @Inject VisualSearchTrialSummaryAuthorizationPort summaryAuthorization;
    @Inject StandardScanningTrialAuthorizationPort scanningAuthorization;
    @Inject StandardScanningTrialSummaryAuthorizationPort scanningSummaryAuthorization;
    @Inject SustainedFixationTrialAuthorizationPort fixationAuthorization;
    @Inject SustainedFixationTrialSummaryAuthorizationPort fixationSummaryAuthorization;
    @Inject CompensatorySaccadeTrialAuthorizationPort compensatorySaccadeAuthorization;
    @Inject CompensatorySaccadeTrialSummaryAuthorizationPort compensatorySaccadeSummaryAuthorization;
    @Inject SystematicExplorationTrialAuthorizationPort systematicExplorationAuthorization;
    @Inject SystematicExplorationTrialSummaryAuthorizationPort systematicExplorationSummaryAuthorization;
    ClinicalHeatmapAuthorizationStubResource peer;

    @BeforeEach void requireProductionCdiBeanAndResetPeer() {
        VisualSearchAuthorizationHttpProfile.requireOptIn();
        assertInstanceOf(ClinicalVisualSearchTrialAuthorizationAdapter.class, authorization);
        assertNotNull(peer, "Scoped loopback peer must be injected by the test resource");
        peer.visualSearchReply(503, "{}");
        peer.resetVisualSearchObservation();
        assertInstanceOf(ClinicalVisualSearchSummaryAuthorizationAdapter.class, summaryAuthorization);
        peer.visualSearchSummaryReply(503, "{}");
        peer.resetVisualSearchSummaryObservation();
        assertInstanceOf(ClinicalStandardScanningTrialAuthorizationAdapter.class, scanningAuthorization);
        peer.standardScanningReply(503, "{}");
        peer.resetStandardScanningObservation();
        assertInstanceOf(ClinicalStandardScanningSummaryAuthorizationAdapter.class, scanningSummaryAuthorization);
        peer.standardScanningSummaryReply(503, "{}");
        peer.resetStandardScanningSummaryObservation();
        assertInstanceOf(ClinicalSustainedFixationTrialAuthorizationAdapter.class, fixationAuthorization);
        peer.sustainedFixationReply(503, "{}");
        peer.resetSustainedFixationObservation();
        assertInstanceOf(ClinicalSustainedFixationSummaryAuthorizationAdapter.class, fixationSummaryAuthorization);
        peer.sustainedFixationSummaryReply(503, "{}");
        peer.resetSustainedFixationSummaryObservation();
        assertInstanceOf(ClinicalCompensatorySaccadeTrialAuthorizationAdapter.class, compensatorySaccadeAuthorization);
        peer.compensatorySaccadeReply(503, "{}");
        assertInstanceOf(ClinicalCompensatorySaccadeSummaryAuthorizationAdapter.class, compensatorySaccadeSummaryAuthorization);
        peer.compensatorySaccadeSummaryReply(503, "{}");
        peer.resetCompensatorySaccadeSummaryObservation();
        peer.resetCompensatorySaccadeObservation();
        assertInstanceOf(ClinicalSystematicExplorationTrialAuthorizationAdapter.class, systematicExplorationAuthorization);
        peer.systematicExplorationReply(503, "{}");
        peer.resetSystematicExplorationObservation();
        assertInstanceOf(ClinicalSystematicExplorationSummaryAuthorizationAdapter.class, systematicExplorationSummaryAuthorization);
        peer.systematicExplorationSummaryReply(503, "{}");
        peer.resetSystematicExplorationSummaryObservation();
    }

    @Test void cdiBeanUsesRealHttpWithExactRequestAndFrozenPermit() {
        peer.visualSearchReply(200, permit(true));
        verifyPermit(call(), true); observed(1);
    }

    @Test void closedPermissionDoesNotAllowInsertionAndIsFetchedAgain() {
        peer.visualSearchReply(200, permit(false));
        verifyPermit(call(), false); observed(1);
        verifyPermit(call(), false); observed(2);
    }

    @Test void forbiddenResponseRevokesPreviousPermitWithoutCache() {
        peer.visualSearchReply(200, permit(true));
        verifyPermit(call(), true); observed(1);
        peer.visualSearchReply(403, "{}");
        rejected(call(), Permission.DENIED); observed(2);
    }

    @Test void unauthorizedResponseMapsToInvalidToken() {
        peer.visualSearchReply(401, "{}");
        rejected(call(), Permission.INVALID_TOKEN); observed(1);
    }

    @Test void malformedPermitAndUnavailableClinicalFailClosed() {
        peer.visualSearchReply(200, "{\"contractVersion\":1,\"permitted\":true}");
        rejected(call(), Permission.UNAVAILABLE); observed(1);
        peer.visualSearchReply(503, "{}");
        rejected(call(), Permission.UNAVAILABLE); observed(2);
    }

    @Test void summaryCdiBeanUsesOriginalDoctorTokenAndFrozenPolicy() {
        peer.visualSearchSummaryReply(200, summaryPermit());
        var decision = summaryCall();
        assertEquals(VisualSearchTrialSummaryAuthorizationPort.Permission.PERMITTED, decision.permission());
        assertNotNull(decision.context()); var context = decision.context();
        assertTrue(SESSION.equals(context.therapySessionId()), "Wrong session binding");
        assertTrue(CONFIGURATION.equals(context.configurationId()), "Wrong configuration binding");
        assertEquals(3, context.configurationVersion()); assertEquals(500, context.minimumFixationMs());
        assertEquals(3000, context.maximumSearchTimeMs()); summaryObserved(1);
    }

    @Test void summaryRevocationIsObservedWithoutPermissionCache() {
        peer.visualSearchSummaryReply(200, summaryPermit());
        assertEquals(VisualSearchTrialSummaryAuthorizationPort.Permission.PERMITTED, summaryCall().permission());
        summaryObserved(1);
        peer.visualSearchSummaryReply(403, "{}");
        summaryRejected(summaryCall(), VisualSearchTrialSummaryAuthorizationPort.Permission.DENIED);
        summaryObserved(2);
    }

    @Test void summaryUnauthorizedAndUnavailableClinicalRemainDistinct() {
        peer.visualSearchSummaryReply(401, "{}");
        summaryRejected(summaryCall(), VisualSearchTrialSummaryAuthorizationPort.Permission.INVALID_TOKEN);
        summaryObserved(1);
        peer.visualSearchSummaryReply(503, "{}");
        summaryRejected(summaryCall(), VisualSearchTrialSummaryAuthorizationPort.Permission.UNAVAILABLE);
        summaryObserved(2);
    }

    @Test void summaryRejectsMalformedCrossSessionAndPatientAdmissionPayloads() {
        String[] replies = {"{\"contractVersion\":1,\"permitted\":true}",
                summaryPermit().replace(SESSION.toString(), new UUID(0, 99).toString()), permit(true)};
        int calls = 0;
        for (String reply : replies) {
            peer.visualSearchSummaryReply(200, reply);
            summaryRejected(summaryCall(), VisualSearchTrialSummaryAuthorizationPort.Permission.UNAVAILABLE);
            summaryObserved(++calls);
        }
    }


    @Test void standardScanningCdiBeanUsesExactRequestAndFrozenPolicy() {
        peer.standardScanningReply(200, scanningPermit(true));
        scanningPermitted(scanningCall(), true); scanningObserved(1);
    }

    @Test void standardScanningClosedPermissionIsFetchedAgainWithoutInsertion() {
        peer.standardScanningReply(200, scanningPermit(false));
        scanningPermitted(scanningCall(), false); scanningObserved(1);
        scanningPermitted(scanningCall(), false); scanningObserved(2);
    }

    @Test void standardScanningRevocationDoesNotReusePreviousPermission() {
        peer.standardScanningReply(200, scanningPermit(true));
        scanningPermitted(scanningCall(), true); scanningObserved(1);
        peer.standardScanningReply(403, "{}");
        scanningRejected(scanningCall(), StandardScanningTrialAuthorizationPort.Permission.DENIED);
        scanningObserved(2);
    }

    @Test void standardScanningUnauthorizedAndUnavailableRemainDistinct() {
        peer.standardScanningReply(401, "{}");
        scanningRejected(scanningCall(), StandardScanningTrialAuthorizationPort.Permission.INVALID_TOKEN);
        scanningObserved(1);
        peer.standardScanningReply(503, "{}");
        scanningRejected(scanningCall(), StandardScanningTrialAuthorizationPort.Permission.UNAVAILABLE);
        scanningObserved(2);
    }

    @Test void standardScanningRejectsMalformedCrossSessionAndVisualSearchPolicies() {
        String[] replies = {"{\"contractVersion\":1,\"permitted\":true}",
                scanningPermit(true).replace(SESSION.toString(), new UUID(0, 99).toString()), permit(true)};
        int calls = 0;
        for (String reply : replies) {
            peer.standardScanningReply(200, reply);
            scanningRejected(scanningCall(), StandardScanningTrialAuthorizationPort.Permission.UNAVAILABLE);
            scanningObserved(++calls);
        }
    }

    private StandardScanningTrialAuthorizationPort.Decision scanningCall() {
        return scanningAuthorization.authorize(SESSION, ClinicalHeatmapAuthorizationStubResource.STANDARD_SCANNING_TOKEN);
    }
    private void scanningObserved(int count) {
        var observation = peer.standardScanningObservation();
        assertEquals(count, observation.requests());
        assertTrue(observation.exactWire(), "Unexpected Standard Scanning method, path, headers or body");
        assertEquals(0, peer.visualSearchObservation().requests(), "Must not call Visual Search admission");
        assertEquals(0, peer.visualSearchSummaryObservation().requests(), "Must not call DOCTOR summary");
    }
    private void scanningPermitted(StandardScanningTrialAuthorizationPort.Decision decision, boolean mayInsert) {
        assertEquals(StandardScanningTrialAuthorizationPort.Permission.PERMITTED, decision.permission());
        assertEquals(mayInsert, decision.mayInsert()); assertNotNull(decision.context());
        var context = decision.context();
        assertTrue(SESSION.equals(context.therapySessionId()), "Wrong session binding");
        assertTrue(CONFIGURATION.equals(context.configurationId()), "Wrong configuration binding");
        assertEquals(3, context.configurationVersion()); assertEquals(500, context.minimumFixationMs());
        assertEquals(3000, context.maximumStimulusDurationMs());
    }
    private void scanningRejected(StandardScanningTrialAuthorizationPort.Decision decision,
            StandardScanningTrialAuthorizationPort.Permission permission) {
        assertEquals(permission, decision.permission()); assertNull(decision.context()); assertFalse(decision.mayInsert());
        assertEquals("StandardScanningTrialAuthorization[redacted]", decision.toString());
    }
    private static String scanningPermit(boolean mayInsert) {
        return permit(mayInsert).replace("maximumSearchTimeMs", "maximumStimulusDurationMs");
    }

    @Test void compensatorySaccadeCdiBeanUsesExactRequestAndFrozenPolicy() {
        peer.compensatorySaccadeReply(200, scanningPermit(true));
        compensatorySaccadePermitted(compensatorySaccadeCall(), true); compensatorySaccadeObserved(1);
        peer.compensatorySaccadeReply(200, scanningPermit(false));
        compensatorySaccadePermitted(compensatorySaccadeCall(), false); compensatorySaccadeObserved(2);
    }

    @Test void compensatorySaccadeRevocationDoesNotReusePreviousPermission() {
        peer.compensatorySaccadeReply(200, scanningPermit(true));
        compensatorySaccadePermitted(compensatorySaccadeCall(), true); compensatorySaccadeObserved(1);
        peer.compensatorySaccadeReply(403, "{}");
        compensatorySaccadeRejected(compensatorySaccadeCall(), CompensatorySaccadeTrialAuthorizationPort.Permission.DENIED);
        compensatorySaccadeObserved(2);
    }

    @Test void compensatorySaccadeUnauthorizedAndUnavailableRemainDistinct() {
        peer.compensatorySaccadeReply(401, "{}");
        compensatorySaccadeRejected(compensatorySaccadeCall(), CompensatorySaccadeTrialAuthorizationPort.Permission.INVALID_TOKEN);
        compensatorySaccadeObserved(1);
        peer.compensatorySaccadeReply(503, "{}");
        compensatorySaccadeRejected(compensatorySaccadeCall(), CompensatorySaccadeTrialAuthorizationPort.Permission.UNAVAILABLE);
        compensatorySaccadeObserved(2);
    }

    @Test void compensatorySaccadeRejectsCrossSessionAndSustainedFixationPolicies() {
        peer.compensatorySaccadeReply(200, scanningPermit(true).replace(SESSION.toString(), new UUID(0, 99).toString()));
        compensatorySaccadeRejected(compensatorySaccadeCall(), CompensatorySaccadeTrialAuthorizationPort.Permission.UNAVAILABLE);
        compensatorySaccadeObserved(1);
        peer.compensatorySaccadeReply(200, fixationPermit(true));
        compensatorySaccadeRejected(compensatorySaccadeCall(), CompensatorySaccadeTrialAuthorizationPort.Permission.UNAVAILABLE);
        compensatorySaccadeObserved(2);
    }

    private CompensatorySaccadeTrialAuthorizationPort.Decision compensatorySaccadeCall() {
        return compensatorySaccadeAuthorization.authorize(SESSION,
                ClinicalHeatmapAuthorizationStubResource.COMPENSATORY_SACCADE_TOKEN);
    }
    private void compensatorySaccadeObserved(int count) {
        var observation = peer.compensatorySaccadeObservation();
        assertEquals(count, observation.requests());
        assertTrue(observation.exactWire(), "Unexpected Compensatory Saccade method, path, headers or body");
        assertEquals(0, peer.visualSearchObservation().requests(), "Must not call Visual Search admission");
        assertEquals(0, peer.visualSearchSummaryObservation().requests(), "Must not call Visual Search summary");
        assertEquals(0, peer.standardScanningObservation().requests(), "Must not call Standard Scanning admission");
        assertEquals(0, peer.standardScanningSummaryObservation().requests(), "Must not call Standard Scanning summary");
        assertEquals(0, peer.sustainedFixationObservation().requests(), "Must not call Sustained Fixation admission");
        assertEquals(0, peer.sustainedFixationSummaryObservation().requests(), "Must not call Sustained Fixation summary");
    }
    private void compensatorySaccadePermitted(CompensatorySaccadeTrialAuthorizationPort.Decision decision, boolean mayInsert) {
        assertEquals(CompensatorySaccadeTrialAuthorizationPort.Permission.PERMITTED, decision.permission());
        assertEquals(mayInsert, decision.mayInsert()); assertNotNull(decision.context());
        var context = decision.context();
        assertTrue(SESSION.equals(context.therapySessionId()), "Wrong session binding");
        assertTrue(CONFIGURATION.equals(context.configurationId()), "Wrong configuration binding");
        assertEquals(3, context.configurationVersion()); assertEquals(500, context.minimumFixationMs());
        assertEquals(3000, context.maximumStimulusDurationMs());
    }
    private void compensatorySaccadeRejected(CompensatorySaccadeTrialAuthorizationPort.Decision decision,
            CompensatorySaccadeTrialAuthorizationPort.Permission permission) {
        assertEquals(permission, decision.permission()); assertNull(decision.context()); assertFalse(decision.mayInsert());
        assertEquals("CompensatorySaccadeTrialAuthorization[redacted]", decision.toString());
    }

    @Test void systematicExplorationCdiBeanUsesExactRequestAndFrozenPolicy() {
        peer.systematicExplorationReply(200, scanningPermit(true));
        systematicExplorationPermitted(systematicExplorationCall(), true); systematicExplorationObserved(1);
        peer.systematicExplorationReply(200, scanningPermit(false));
        systematicExplorationPermitted(systematicExplorationCall(), false); systematicExplorationObserved(2);
    }

    @Test void systematicExplorationRevocationDoesNotReusePreviousPermission() {
        peer.systematicExplorationReply(200, scanningPermit(true));
        systematicExplorationPermitted(systematicExplorationCall(), true); systematicExplorationObserved(1);
        peer.systematicExplorationReply(403, "{}");
        systematicExplorationRejected(systematicExplorationCall(), SystematicExplorationTrialAuthorizationPort.Permission.DENIED);
        systematicExplorationObserved(2);
    }

    @Test void systematicExplorationUnauthorizedAndUnavailableRemainDistinct() {
        peer.systematicExplorationReply(401, "{}");
        systematicExplorationRejected(systematicExplorationCall(), SystematicExplorationTrialAuthorizationPort.Permission.INVALID_TOKEN);
        systematicExplorationObserved(1);
        peer.systematicExplorationReply(503, "{}");
        systematicExplorationRejected(systematicExplorationCall(), SystematicExplorationTrialAuthorizationPort.Permission.UNAVAILABLE);
        systematicExplorationObserved(2);
    }

    @Test void systematicExplorationRejectsCrossSessionAndInvalidFrozenPolicies() {
        peer.systematicExplorationReply(200, scanningPermit(true).replace(SESSION.toString(), new UUID(0, 99).toString()));
        systematicExplorationRejected(systematicExplorationCall(), SystematicExplorationTrialAuthorizationPort.Permission.UNAVAILABLE);
        systematicExplorationObserved(1);
        peer.systematicExplorationReply(200, scanningPermit(true)
                .replace("\"maximumStimulusDurationMs\":3000", "\"maximumStimulusDurationMs\":10001"));
        systematicExplorationRejected(systematicExplorationCall(), SystematicExplorationTrialAuthorizationPort.Permission.UNAVAILABLE);
        systematicExplorationObserved(2);
    }

    private SystematicExplorationTrialAuthorizationPort.Decision systematicExplorationCall() {
        return systematicExplorationAuthorization.authorize(SESSION,
                ClinicalHeatmapAuthorizationStubResource.SYSTEMATIC_EXPLORATION_TOKEN);
    }
    private void systematicExplorationObserved(int count) {
        var observation = peer.systematicExplorationObservation();
        assertEquals(count, observation.requests());
        assertTrue(observation.exactWire(), "Unexpected Systematic Exploration method, path, headers or body");
        assertEquals(0, peer.visualSearchObservation().requests(), "Must not call Visual Search admission");
        assertEquals(0, peer.visualSearchSummaryObservation().requests(), "Must not call Visual Search summary");
        assertEquals(0, peer.standardScanningObservation().requests(), "Must not call Standard Scanning admission");
        assertEquals(0, peer.standardScanningSummaryObservation().requests(), "Must not call Standard Scanning summary");
        assertEquals(0, peer.sustainedFixationObservation().requests(), "Must not call Sustained Fixation admission");
        assertEquals(0, peer.sustainedFixationSummaryObservation().requests(), "Must not call Sustained Fixation summary");
        assertEquals(0, peer.compensatorySaccadeObservation().requests(), "Must not call Compensatory Saccade admission");
        assertEquals(0, peer.compensatorySaccadeSummaryObservation().requests(), "Must not call Compensatory Saccade summary");
    }
    private void systematicExplorationPermitted(SystematicExplorationTrialAuthorizationPort.Decision decision, boolean mayInsert) {
        assertEquals(SystematicExplorationTrialAuthorizationPort.Permission.PERMITTED, decision.permission());
        assertEquals(mayInsert, decision.mayInsert()); assertNotNull(decision.context());
        var context = decision.context();
        assertTrue(SESSION.equals(context.therapySessionId()), "Wrong session binding");
        assertTrue(CONFIGURATION.equals(context.configurationId()), "Wrong configuration binding");
        assertEquals(3, context.configurationVersion()); assertEquals(500, context.minimumFixationMs());
        assertEquals(3000, context.maximumStimulusDurationMs());
    }
    private void systematicExplorationRejected(SystematicExplorationTrialAuthorizationPort.Decision decision,
            SystematicExplorationTrialAuthorizationPort.Permission permission) {
        assertEquals(permission, decision.permission()); assertNull(decision.context()); assertFalse(decision.mayInsert());
        assertEquals("SystematicExplorationTrialAuthorization[redacted]", decision.toString());
    }

    @Test void sustainedFixationCdiBeanUsesExactRequestAndFrozenPolicy() {
        peer.sustainedFixationReply(200, fixationPermit(true));
        fixationPermitted(fixationCall(), true); fixationObserved(1);
    }

    @Test void sustainedFixationClosedPermissionIsFetchedAgainWithoutInsertion() {
        peer.sustainedFixationReply(200, fixationPermit(false));
        fixationPermitted(fixationCall(), false); fixationObserved(1);
        fixationPermitted(fixationCall(), false); fixationObserved(2);
    }

    @Test void sustainedFixationRevocationDoesNotReusePreviousPermission() {
        peer.sustainedFixationReply(200, fixationPermit(true));
        fixationPermitted(fixationCall(), true); fixationObserved(1);
        peer.sustainedFixationReply(403, "{}");
        fixationRejected(fixationCall(), SustainedFixationTrialAuthorizationPort.Permission.DENIED);
        fixationObserved(2);
    }

    @Test void sustainedFixationUnauthorizedAndUnavailableRemainDistinct() {
        peer.sustainedFixationReply(401, "{}");
        fixationRejected(fixationCall(), SustainedFixationTrialAuthorizationPort.Permission.INVALID_TOKEN);
        fixationObserved(1);
        peer.sustainedFixationReply(503, "{}");
        fixationRejected(fixationCall(), SustainedFixationTrialAuthorizationPort.Permission.UNAVAILABLE);
        fixationObserved(2);
    }

    @Test void sustainedFixationRejectsMalformedCrossSessionAndStandardScanningPolicies() {
        String[] replies = {"{\"contractVersion\":1,\"permitted\":true}",
                fixationPermit(true).replace(SESSION.toString(), new UUID(0, 99).toString()), scanningPermit(true)};
        int calls = 0;
        for (String reply : replies) {
            peer.sustainedFixationReply(200, reply);
            fixationRejected(fixationCall(), SustainedFixationTrialAuthorizationPort.Permission.UNAVAILABLE);
            fixationObserved(++calls);
        }
    }

    private SustainedFixationTrialAuthorizationPort.Decision fixationCall() {
        return fixationAuthorization.authorize(SESSION, ClinicalHeatmapAuthorizationStubResource.SUSTAINED_FIXATION_TOKEN);
    }
    private void fixationObserved(int count) {
        var observation = peer.sustainedFixationObservation();
        assertEquals(count, observation.requests());
        assertTrue(observation.exactWire(), "Unexpected Sustained Fixation method, path, headers or body");
        assertEquals(0, peer.standardScanningObservation().requests(), "Must not call Standard Scanning admission");
        assertEquals(0, peer.standardScanningSummaryObservation().requests(), "Must not call Standard Scanning summary");
        assertEquals(0, peer.visualSearchObservation().requests(), "Must not call Visual Search admission");
        assertEquals(0, peer.visualSearchSummaryObservation().requests(), "Must not call DOCTOR summary");
    }
    private void fixationPermitted(SustainedFixationTrialAuthorizationPort.Decision decision, boolean mayInsert) {
        assertEquals(SustainedFixationTrialAuthorizationPort.Permission.PERMITTED, decision.permission());
        assertEquals(mayInsert, decision.mayInsert()); assertNotNull(decision.context());
        var context = decision.context();
        assertTrue(SESSION.equals(context.therapySessionId()), "Wrong session binding");
        assertTrue(CONFIGURATION.equals(context.configurationId()), "Wrong configuration binding");
        assertEquals(3, context.configurationVersion()); assertEquals(500, context.minimumFixationMs());
        assertEquals(3000, context.maximumFixationMs());
    }
    private void fixationRejected(SustainedFixationTrialAuthorizationPort.Decision decision,
            SustainedFixationTrialAuthorizationPort.Permission permission) {
        assertEquals(permission, decision.permission()); assertNull(decision.context()); assertFalse(decision.mayInsert());
        assertEquals("SustainedFixationTrialAuthorization[redacted]", decision.toString());
    }
    private static String fixationPermit(boolean mayInsert) {
        return permit(mayInsert).replace("maximumSearchTimeMs", "maximumFixationMs");
    }

    private VisualSearchTrialSummaryAuthorizationPort.Decision summaryCall() {
        return summaryAuthorization.authorize(SESSION, ClinicalHeatmapAuthorizationStubResource.VISUAL_SEARCH_SUMMARY_TOKEN);
    }
    private void summaryObserved(int count) {
        var observation = peer.visualSearchSummaryObservation();
        assertEquals(count, observation.requests());
        assertTrue(observation.exactWire(), "Unexpected summary method, path, headers or request body");
        assertEquals(0, peer.visualSearchObservation().requests(), "Summary must not call PATIENT admission");
    }
    private void summaryRejected(VisualSearchTrialSummaryAuthorizationPort.Decision decision,
            VisualSearchTrialSummaryAuthorizationPort.Permission permission) {
        assertEquals(permission, decision.permission()); assertNull(decision.context());
        assertEquals("VisualSearchSummaryAuthorization[redacted]", decision.toString());
    }
    private static String summaryPermit() {
        return "{\"contractVersion\":1,\"permitted\":true,\"therapySessionId\":\"" + SESSION
                + "\",\"configurationId\":\"" + CONFIGURATION
                + "\",\"configurationVersion\":3,\"minimumFixationMs\":500,\"maximumSearchTimeMs\":3000}";
    }

    @Test void scanningSummaryCdiBeanUsesOriginalDoctorTokenAndFrozenPolicy() {
        peer.standardScanningSummaryReply(200, scanningSummaryPermit());
        var decision = scanningSummaryCall();
        assertEquals(StandardScanningTrialSummaryAuthorizationPort.Permission.PERMITTED, decision.permission());
        assertNotNull(decision.context()); var context = decision.context();
        assertTrue(SESSION.equals(context.therapySessionId()), "Wrong session binding");
        assertTrue(CONFIGURATION.equals(context.configurationId()), "Wrong configuration binding");
        assertEquals(3, context.configurationVersion()); assertEquals(500, context.minimumFixationMs());
        assertEquals(3000, context.maximumStimulusDurationMs()); scanningSummaryObserved(1);
    }

    @Test void scanningSummaryRevocationIsObservedWithoutPermissionCache() {
        peer.standardScanningSummaryReply(200, scanningSummaryPermit());
        assertEquals(StandardScanningTrialSummaryAuthorizationPort.Permission.PERMITTED, scanningSummaryCall().permission());
        scanningSummaryObserved(1);
        peer.standardScanningSummaryReply(403, "{}");
        scanningSummaryRejected(scanningSummaryCall(), StandardScanningTrialSummaryAuthorizationPort.Permission.DENIED);
        scanningSummaryObserved(2);
    }

    @Test void scanningSummaryUnauthorizedAndUnavailableClinicalRemainDistinct() {
        peer.standardScanningSummaryReply(401, "{}");
        scanningSummaryRejected(scanningSummaryCall(), StandardScanningTrialSummaryAuthorizationPort.Permission.INVALID_TOKEN);
        scanningSummaryObserved(1);
        peer.standardScanningSummaryReply(503, "{}");
        scanningSummaryRejected(scanningSummaryCall(), StandardScanningTrialSummaryAuthorizationPort.Permission.UNAVAILABLE);
        scanningSummaryObserved(2);
    }

    @Test void scanningSummaryRejectsMalformedCrossSessionAndVisualSearchPolicies() {
        String[] replies = {"{\"contractVersion\":1,\"permitted\":true}",
                scanningSummaryPermit().replace(SESSION.toString(), new UUID(0, 99).toString()), summaryPermit()};
        int calls = 0;
        for (String reply : replies) {
            peer.standardScanningSummaryReply(200, reply);
            scanningSummaryRejected(scanningSummaryCall(), StandardScanningTrialSummaryAuthorizationPort.Permission.UNAVAILABLE);
            scanningSummaryObserved(++calls);
        }
    }

    private StandardScanningTrialSummaryAuthorizationPort.Decision scanningSummaryCall() {
        return scanningSummaryAuthorization.authorize(SESSION, ClinicalHeatmapAuthorizationStubResource.STANDARD_SCANNING_SUMMARY_TOKEN);
    }
    private void scanningSummaryObserved(int count) {
        var observation = peer.standardScanningSummaryObservation();
        assertEquals(count, observation.requests());
        assertTrue(observation.exactWire(), "Unexpected summary method, path, headers or request body");
        assertEquals(0, peer.visualSearchObservation().requests(), "Summary must not call Visual Search admission");
        assertEquals(0, peer.visualSearchSummaryObservation().requests(), "Must not call Visual Search summary");
        assertEquals(0, peer.standardScanningObservation().requests(), "Must not call Standard Scanning admission");
    }
    private void scanningSummaryRejected(StandardScanningTrialSummaryAuthorizationPort.Decision decision,
            StandardScanningTrialSummaryAuthorizationPort.Permission permission) {
        assertEquals(permission, decision.permission()); assertNull(decision.context());
        assertEquals("StandardScanningSummaryAuthorization[redacted]", decision.toString());
    }
    private static String scanningSummaryPermit() {
        return "{\"contractVersion\":1,\"permitted\":true,\"therapySessionId\":\"" + SESSION
                + "\",\"configurationId\":\"" + CONFIGURATION
                + "\",\"configurationVersion\":3,\"minimumFixationMs\":500,\"maximumStimulusDurationMs\":3000}";
    }

    @Test void fixationSummaryCdiBeanUsesOriginalDoctorTokenAndFrozenPolicy() {
        peer.sustainedFixationSummaryReply(200, fixationSummaryPermit());
        var decision = fixationSummaryCall();
        assertEquals(SustainedFixationTrialSummaryAuthorizationPort.Permission.PERMITTED, decision.permission());
        assertNotNull(decision.context()); var context = decision.context();
        assertTrue(SESSION.equals(context.therapySessionId()), "Wrong session binding");
        assertTrue(CONFIGURATION.equals(context.configurationId()), "Wrong configuration binding");
        assertEquals(3, context.configurationVersion()); assertEquals(500, context.minimumFixationMs());
        assertEquals(3000, context.maximumFixationMs()); fixationSummaryObserved(1);
    }

    @Test void fixationSummaryRevocationIsObservedWithoutPermissionCache() {
        peer.sustainedFixationSummaryReply(200, fixationSummaryPermit());
        assertEquals(SustainedFixationTrialSummaryAuthorizationPort.Permission.PERMITTED, fixationSummaryCall().permission());
        fixationSummaryObserved(1);
        peer.sustainedFixationSummaryReply(403, "{}");
        fixationSummaryRejected(fixationSummaryCall(), SustainedFixationTrialSummaryAuthorizationPort.Permission.DENIED);
        fixationSummaryObserved(2);
    }

    @Test void fixationSummaryUnauthorizedAndUnavailableClinicalRemainDistinct() {
        peer.sustainedFixationSummaryReply(401, "{}");
        fixationSummaryRejected(fixationSummaryCall(), SustainedFixationTrialSummaryAuthorizationPort.Permission.INVALID_TOKEN);
        fixationSummaryObserved(1);
        peer.sustainedFixationSummaryReply(503, "{}");
        fixationSummaryRejected(fixationSummaryCall(), SustainedFixationTrialSummaryAuthorizationPort.Permission.UNAVAILABLE);
        fixationSummaryObserved(2);
    }

    @Test void fixationSummaryRejectsCrossSessionAndPatientAdmissionPolicies() {
        String[] replies = {fixationSummaryPermit().replace(SESSION.toString(), new UUID(0, 99).toString()),
                fixationPermit(true)};
        int calls = 0;
        for (String reply : replies) {
            peer.sustainedFixationSummaryReply(200, reply);
            fixationSummaryRejected(fixationSummaryCall(), SustainedFixationTrialSummaryAuthorizationPort.Permission.UNAVAILABLE);
            fixationSummaryObserved(++calls);
        }
    }

    private SustainedFixationTrialSummaryAuthorizationPort.Decision fixationSummaryCall() {
        return fixationSummaryAuthorization.authorize(SESSION, ClinicalHeatmapAuthorizationStubResource.SUSTAINED_FIXATION_SUMMARY_TOKEN);
    }
    private void fixationSummaryObserved(int count) {
        var observation = peer.sustainedFixationSummaryObservation();
        assertEquals(count, observation.requests());
        assertTrue(observation.exactWire(), "Unexpected summary method, path, headers or request body");
        assertEquals(0, peer.visualSearchObservation().requests(), "Summary must not call Visual Search admission");
        assertEquals(0, peer.visualSearchSummaryObservation().requests(), "Must not call Visual Search summary");
        assertEquals(0, peer.standardScanningObservation().requests(), "Must not call Standard Scanning admission");
        assertEquals(0, peer.standardScanningSummaryObservation().requests(), "Must not call Standard Scanning summary");
        assertEquals(0, peer.sustainedFixationObservation().requests(), "Must not call Sustained Fixation admission");
    }
    private void fixationSummaryRejected(SustainedFixationTrialSummaryAuthorizationPort.Decision decision,
            SustainedFixationTrialSummaryAuthorizationPort.Permission permission) {
        assertEquals(permission, decision.permission()); assertNull(decision.context());
        assertEquals("SustainedFixationSummaryAuthorization[redacted]", decision.toString());
    }
    @Test void compensatorySaccadeSummaryCdiBeanUsesOriginalDoctorTokenAndFrozenPolicy() {
        peer.compensatorySaccadeSummaryReply(200, scanningSummaryPermit());
        var decision = compensatorySaccadeSummaryCall();
        assertEquals(CompensatorySaccadeTrialSummaryAuthorizationPort.Permission.PERMITTED, decision.permission());
        assertNotNull(decision.context()); var context = decision.context();
        assertTrue(SESSION.equals(context.therapySessionId()), "Wrong session binding");
        assertTrue(CONFIGURATION.equals(context.configurationId()), "Wrong configuration binding");
        assertEquals(3, context.configurationVersion()); assertEquals(500, context.minimumFixationMs());
        assertEquals(3000, context.maximumStimulusDurationMs()); compensatorySaccadeSummaryObserved(1);
    }

    @Test void compensatorySaccadeSummaryRevocationIsObservedWithoutPermissionCache() {
        peer.compensatorySaccadeSummaryReply(200, scanningSummaryPermit());
        assertEquals(CompensatorySaccadeTrialSummaryAuthorizationPort.Permission.PERMITTED, compensatorySaccadeSummaryCall().permission());
        compensatorySaccadeSummaryObserved(1);
        peer.compensatorySaccadeSummaryReply(403, "{}");
        compensatorySaccadeSummaryRejected(compensatorySaccadeSummaryCall(), CompensatorySaccadeTrialSummaryAuthorizationPort.Permission.DENIED);
        compensatorySaccadeSummaryObserved(2);
    }

    @Test void compensatorySaccadeSummaryUnauthorizedAndUnavailableClinicalRemainDistinct() {
        peer.compensatorySaccadeSummaryReply(401, "{}");
        compensatorySaccadeSummaryRejected(compensatorySaccadeSummaryCall(), CompensatorySaccadeTrialSummaryAuthorizationPort.Permission.INVALID_TOKEN);
        compensatorySaccadeSummaryObserved(1);
        peer.compensatorySaccadeSummaryReply(503, "{}");
        compensatorySaccadeSummaryRejected(compensatorySaccadeSummaryCall(), CompensatorySaccadeTrialSummaryAuthorizationPort.Permission.UNAVAILABLE);
        compensatorySaccadeSummaryObserved(2);
    }

    @Test void compensatorySaccadeSummaryRejectsCrossSessionAndPatientAdmissionPolicies() {
        String[] replies = {scanningSummaryPermit().replace(SESSION.toString(), new UUID(0, 99).toString()),
                scanningPermit(true)};
        int calls = 0;
        for (String reply : replies) {
            peer.compensatorySaccadeSummaryReply(200, reply);
            compensatorySaccadeSummaryRejected(compensatorySaccadeSummaryCall(), CompensatorySaccadeTrialSummaryAuthorizationPort.Permission.UNAVAILABLE);
            compensatorySaccadeSummaryObserved(++calls);
        }
    }

    private CompensatorySaccadeTrialSummaryAuthorizationPort.Decision compensatorySaccadeSummaryCall() {
        return compensatorySaccadeSummaryAuthorization.authorize(SESSION, ClinicalHeatmapAuthorizationStubResource.COMPENSATORY_SACCADE_SUMMARY_TOKEN);
    }
    private void compensatorySaccadeSummaryObserved(int count) {
        var observation = peer.compensatorySaccadeSummaryObservation();
        assertEquals(count, observation.requests());
        assertTrue(observation.exactWire(), "Unexpected summary method, path, headers or request body");
        assertEquals(0, peer.visualSearchObservation().requests(), "Summary must not call Visual Search admission");
        assertEquals(0, peer.visualSearchSummaryObservation().requests(), "Must not call Visual Search summary");
        assertEquals(0, peer.standardScanningObservation().requests(), "Must not call Standard Scanning admission");
        assertEquals(0, peer.standardScanningSummaryObservation().requests(), "Must not call Standard Scanning summary");
        assertEquals(0, peer.sustainedFixationObservation().requests(), "Must not call Sustained Fixation admission");
        assertEquals(0, peer.sustainedFixationSummaryObservation().requests(), "Must not call Sustained Fixation summary");
        assertEquals(0, peer.compensatorySaccadeObservation().requests(), "Must not call Compensatory Saccade admission");
    }
    private void compensatorySaccadeSummaryRejected(CompensatorySaccadeTrialSummaryAuthorizationPort.Decision decision,
            CompensatorySaccadeTrialSummaryAuthorizationPort.Permission permission) {
        assertEquals(permission, decision.permission()); assertNull(decision.context());
        assertEquals("CompensatorySaccadeSummaryAuthorization[redacted]", decision.toString());
    }
    @Test void systematicExplorationSummaryCdiBeanUsesOriginalDoctorTokenAndFrozenPolicy() {
        peer.systematicExplorationSummaryReply(200, scanningSummaryPermit());
        var decision = systematicExplorationSummaryCall();
        assertEquals(SystematicExplorationTrialSummaryAuthorizationPort.Permission.PERMITTED, decision.permission());
        assertNotNull(decision.context()); var context = decision.context();
        assertTrue(SESSION.equals(context.therapySessionId()), "Wrong session binding");
        assertTrue(CONFIGURATION.equals(context.configurationId()), "Wrong configuration binding");
        assertEquals(3, context.configurationVersion()); assertEquals(500, context.minimumFixationMs());
        assertEquals(3000, context.maximumStimulusDurationMs()); systematicExplorationSummaryObserved(1);
    }

    @Test void systematicExplorationSummaryRevocationIsObservedWithoutPermissionCache() {
        peer.systematicExplorationSummaryReply(200, scanningSummaryPermit());
        assertEquals(SystematicExplorationTrialSummaryAuthorizationPort.Permission.PERMITTED, systematicExplorationSummaryCall().permission());
        systematicExplorationSummaryObserved(1);
        peer.systematicExplorationSummaryReply(403, "{}");
        systematicExplorationSummaryRejected(systematicExplorationSummaryCall(), SystematicExplorationTrialSummaryAuthorizationPort.Permission.DENIED);
        systematicExplorationSummaryObserved(2);
    }

    @Test void systematicExplorationSummaryUnauthorizedAndUnavailableClinicalRemainDistinct() {
        peer.systematicExplorationSummaryReply(401, "{}");
        systematicExplorationSummaryRejected(systematicExplorationSummaryCall(), SystematicExplorationTrialSummaryAuthorizationPort.Permission.INVALID_TOKEN);
        systematicExplorationSummaryObserved(1);
        peer.systematicExplorationSummaryReply(503, "{}");
        systematicExplorationSummaryRejected(systematicExplorationSummaryCall(), SystematicExplorationTrialSummaryAuthorizationPort.Permission.UNAVAILABLE);
        systematicExplorationSummaryObserved(2);
    }

    @Test void systematicExplorationSummaryRejectsCrossSessionAndPatientAdmissionPolicies() {
        String[] replies = {scanningSummaryPermit().replace(SESSION.toString(), new UUID(0, 99).toString()),
                scanningPermit(true)};
        int calls = 0;
        for (String reply : replies) {
            peer.systematicExplorationSummaryReply(200, reply);
            systematicExplorationSummaryRejected(systematicExplorationSummaryCall(), SystematicExplorationTrialSummaryAuthorizationPort.Permission.UNAVAILABLE);
            systematicExplorationSummaryObserved(++calls);
        }
    }

    private SystematicExplorationTrialSummaryAuthorizationPort.Decision systematicExplorationSummaryCall() {
        return systematicExplorationSummaryAuthorization.authorize(SESSION, ClinicalHeatmapAuthorizationStubResource.SYSTEMATIC_EXPLORATION_SUMMARY_TOKEN);
    }
    private void systematicExplorationSummaryObserved(int count) {
        var observation = peer.systematicExplorationSummaryObservation();
        assertEquals(count, observation.requests());
        assertTrue(observation.exactWire(), "Unexpected summary method, path, headers or request body");
        assertEquals(0, peer.visualSearchObservation().requests(), "Summary must not call Visual Search admission");
        assertEquals(0, peer.visualSearchSummaryObservation().requests(), "Must not call Visual Search summary");
        assertEquals(0, peer.standardScanningObservation().requests(), "Must not call Standard Scanning admission");
        assertEquals(0, peer.standardScanningSummaryObservation().requests(), "Must not call Standard Scanning summary");
        assertEquals(0, peer.sustainedFixationObservation().requests(), "Must not call Sustained Fixation admission");
        assertEquals(0, peer.sustainedFixationSummaryObservation().requests(), "Must not call Sustained Fixation summary");
        assertEquals(0, peer.compensatorySaccadeObservation().requests(), "Must not call Compensatory Saccade admission");
        assertEquals(0, peer.compensatorySaccadeSummaryObservation().requests(), "Must not call Compensatory Saccade summary");
        assertEquals(0, peer.systematicExplorationObservation().requests(), "Must not call Systematic Exploration admission");
    }
    private void systematicExplorationSummaryRejected(SystematicExplorationTrialSummaryAuthorizationPort.Decision decision,
            SystematicExplorationTrialSummaryAuthorizationPort.Permission permission) {
        assertEquals(permission, decision.permission()); assertNull(decision.context());
        assertEquals("SystematicExplorationSummaryAuthorization[redacted]", decision.toString());
    }
    private static String fixationSummaryPermit() {
        return "{\"contractVersion\":1,\"permitted\":true,\"therapySessionId\":\"" + SESSION
                + "\",\"configurationId\":\"" + CONFIGURATION
                + "\",\"configurationVersion\":3,\"minimumFixationMs\":500,\"maximumFixationMs\":3000}";
    }

    private VisualSearchTrialAuthorizationPort.Decision call() {
        return authorization.authorize(SESSION, ClinicalHeatmapAuthorizationStubResource.VISUAL_SEARCH_TOKEN);
    }
    private void observed(int count) {
        var observation = peer.visualSearchObservation();
        assertEquals(count, observation.requests());
        assertTrue(observation.exactWire(), "Unexpected method, path, headers or request body");
    }
    private void verifyPermit(VisualSearchTrialAuthorizationPort.Decision decision, boolean mayInsert) {
        assertEquals(Permission.PERMITTED, decision.permission()); assertEquals(mayInsert, decision.mayInsert());
        assertNotNull(decision.context()); var context = decision.context();
        assertTrue(SESSION.equals(context.therapySessionId()), "Wrong session binding");
        assertTrue(CONFIGURATION.equals(context.configurationId()), "Wrong configuration binding");
        assertEquals(3, context.configurationVersion()); assertEquals(500, context.minimumFixationMs());
        assertEquals(3000, context.maximumSearchTimeMs());
    }
    private void rejected(VisualSearchTrialAuthorizationPort.Decision decision, Permission permission) {
        assertEquals(permission, decision.permission()); assertNull(decision.context()); assertFalse(decision.mayInsert());
        assertEquals("VisualSearchTrialAuthorization[redacted]", decision.toString());
    }
    private static String permit(boolean mayInsert) {
        return "{\"contractVersion\":1,\"permitted\":true,\"therapySessionId\":\"" + SESSION
                + "\",\"mayInsert\":" + mayInsert + ",\"configurationId\":\"" + CONFIGURATION
                + "\",\"configurationVersion\":3,\"minimumFixationMs\":500,\"maximumSearchTimeMs\":3000}";
    }
}
