package com.neurovision.rehab.clinical.application.internal.queryservices;

import com.neurovision.rehab.clinical.application.internal.commandservices.CreateSessionReportSnapshotServiceTest.Fixture;
import static com.neurovision.rehab.clinical.application.internal.commandservices.CreateSessionReportSnapshotServiceTest.*;
import java.util.UUID;
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
}
