package com.neurovision.rehab.analytics.interfaces.rest.resource;

import static org.junit.jupiter.api.Assertions.*;
import com.neurovision.rehab.analytics.application.ports.out.authorization.VisualSearchTrialAuthorizationPort;
import com.neurovision.rehab.analytics.application.ports.out.authorization.VisualSearchTrialSummaryAuthorizationPort;
import com.neurovision.rehab.analytics.application.ports.out.authorization.VisualSearchTrialAuthorizationPort.Permission;
import com.neurovision.rehab.analytics.infrastructure.integration.ClinicalVisualSearchTrialAuthorizationAdapter;
import com.neurovision.rehab.analytics.infrastructure.integration.ClinicalVisualSearchSummaryAuthorizationAdapter;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
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
