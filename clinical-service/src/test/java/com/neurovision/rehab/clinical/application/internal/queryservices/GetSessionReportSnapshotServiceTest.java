package com.neurovision.rehab.clinical.application.internal.queryservices;

import com.neurovision.rehab.clinical.application.internal.commandservices.CreateSessionReportSnapshotServiceTest.Fixture;
import com.neurovision.rehab.clinical.application.ports.out.integration.SessionReportSystematicExplorationPort.*;
import static com.neurovision.rehab.clinical.application.internal.commandservices.CreateSessionReportSnapshotServiceTest.*;
import java.util.UUID;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class GetSessionReportSnapshotServiceTest {
    @Test void getsFrozenCopyWithClinicalOnlyAndSeparatesAuthorizedMissingFromDenied() {
        var f = new Fixture(); var snapshot = f.create("key").snapshot(); var service = new GetSessionReportSnapshotService(f);
        f.failPeer = "names"; f.firstName = "Changed";
        assertEquals(snapshot, service.get(DOCTOR, PATIENT, SESSION, snapshot.id()).orElseThrow());
        assertTrue(service.get(DOCTOR, PATIENT, SESSION, UUID.randomUUID()).isEmpty()); assertEquals(3, f.peers);
        f.allowed = false;
        assertThrows(SecurityException.class, () -> service.get(DOCTOR, PATIENT, SESSION, snapshot.id()));
        assertThrows(SecurityException.class, () -> service.get(DOCTOR, PATIENT, SESSION, UUID.randomUUID()));
    }
    @Test void invalidInputAndReadUncertaintyFailClosedWithoutDependencyDetails() {
        var f = new Fixture(); var service = new GetSessionReportSnapshotService(f);
        assertThrows(IllegalArgumentException.class, () -> service.get(" padded", PATIENT, SESSION, UUID.randomUUID()));
        assertThrows(IllegalArgumentException.class, () -> service.get(DOCTOR, PATIENT, SESSION, null)); assertEquals(0, f.reads);
        f.failRead = true;
        var failure = assertThrows(IllegalStateException.class, () -> service.get(DOCTOR, PATIENT, SESSION, UUID.randomUUID()));
        assertEquals("Session report snapshot unavailable", failure.getMessage()); assertNull(failure.getCause()); assertEquals(0, f.peers);
    }
    @Test void selectedCopyReadPreservesStoredJsonWithoutReenrichmentAndStillRequiresCurrentAuthorization() {
        var f = new Fixture(); f.exploration = true;
        f.explorationSummary = new Summary("systematic-exploration-trial-v1",SESSION,new UUID(0,4),1,
                Origin.PRODUCER_REPORTED,1,List.of(new Traversal("trial-1",Outcome.COMPLETED,4,100,4000)));
        var snapshot = f.service().create(DOCTOR,TOKEN,PATIENT,SESSION,"selected",null,null,null,null,
                Origin.PRODUCER_REPORTED).snapshot();
        var originalJson = snapshot.reportJson(); int peers = f.peers, writes = f.writes, explorationCalls = f.explorationCalls;
        f.firstName = "Changed"; f.explorationSummary = null; f.failPeer = "exploration";
        var service = new GetSessionReportSnapshotService(f);
        var loaded = service.get(DOCTOR,PATIENT,SESSION,snapshot.id()).orElseThrow();
        assertEquals(snapshot,loaded); assertEquals(originalJson,loaded.reportJson());
        assertEquals(peers,f.peers); assertEquals(explorationCalls,f.explorationCalls); assertEquals(writes,f.writes);
        f.allowed = false;
        assertThrows(SecurityException.class,()->service.get(DOCTOR,PATIENT,SESSION,snapshot.id()));
        assertEquals(snapshot,f.copies.get("selected")); assertEquals(originalJson,f.copies.get("selected").reportJson());
        assertEquals(peers,f.peers); assertEquals(explorationCalls,f.explorationCalls); assertEquals(writes,f.writes);
    }
}
