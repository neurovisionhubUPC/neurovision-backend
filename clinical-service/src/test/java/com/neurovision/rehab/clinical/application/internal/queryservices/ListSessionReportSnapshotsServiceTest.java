package com.neurovision.rehab.clinical.application.internal.queryservices;

import com.neurovision.rehab.clinical.application.ports.out.persistence.SessionReportSnapshotListRepository;
import com.neurovision.rehab.clinical.application.ports.out.persistence.SessionReportSnapshotListRepository.*;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ListSessionReportSnapshotsServiceTest {
    final UUID patient = new UUID(0, 1), session = new UUID(0, 2);
    final Instant time = Instant.parse("2026-10-31T00:00:00Z");
    final List<Summary> rows = List.of(new Summary(UUID.fromString("80000000-0000-0000-0000-000000000001"), time),
            new Summary(UUID.fromString("7fffffff-ffff-ffff-ffff-ffffffffffff"), time), new Summary(new UUID(0, 3), time.minusSeconds(1)));
    int calls, offset, limit;
    boolean allowed = true;
    ListObservation override;
    RuntimeException failure;
    final SessionReportSnapshotListRepository repo = (doctor, p, s, o, l) -> {
        calls++; assertEquals("synthetic-doctor", doctor); assertEquals(patient, p); assertEquals(session, s); offset = o; limit = l;
        if (failure != null) throw failure;
        if (override != null) return override;
        if (!allowed) return new ListObservation(false, List.of(), 0);
        return new ListObservation(true, rows.subList(Math.min(o, rows.size()), (int)Math.min((long)o + l, rows.size())), rows.size());
    };
    final ListSessionReportSnapshotsService service = new ListSessionReportSnapshotsService(repo);
    ListSessionReportSnapshotsService.Page list(int page, int size) { return service.list("synthetic-doctor", patient, session, page, size); }
    @Test void returnsExactMetadataAndCoherentPagesWithPostgresUuidTieOrder() {
        var first = list(0, 2); assertEquals(rows.subList(0, 2), first.snapshots());
        assertEquals(patient, first.clinicalPatientId()); assertEquals(session, first.therapySessionId());
        assertEquals(0, first.page()); assertEquals(2, first.size()); assertEquals(3, first.total());
        assertEquals(0, offset); assertEquals(2, limit);
        assertEquals(rows.subList(2, 3), list(1, 2).snapshots()); assertEquals(2, offset);
        var beyond = list(2, 2); assertTrue(beyond.snapshots().isEmpty()); assertEquals(3, beyond.total());
        assertEquals(3, calls); assertThrows(UnsupportedOperationException.class, () -> first.snapshots().clear());
    }
    @Test void authorizedEmptyIsNotDenialAndRevocationIsNotAnEmptyPage() {
        override = new ListObservation(true, List.of(), 0); assertEquals(0, list(0, 20).total());
        override = null; allowed = false; assertThrows(SecurityException.class, () -> list(0, 20));
    }
    @Test void invalidPaginationOrScopeNeverReads() {
        for (int[] v : List.of(new int[]{-1,20}, new int[]{0,0}, new int[]{0,101}, new int[]{Integer.MAX_VALUE,100}))
            assertThrows(IllegalArgumentException.class, () -> list(v[0],v[1]));
        for (String doctor : Arrays.asList(null, "", " padded", "x".repeat(129)))
            assertThrows(IllegalArgumentException.class, () -> service.list(doctor, patient, session, 0,20));
        assertThrows(IllegalArgumentException.class, () -> service.list("synthetic-doctor",null,session,0,20));
        assertThrows(IllegalArgumentException.class, () -> service.list("synthetic-doctor",patient,null,0,20));
        assertEquals(0,calls);
    }
    @Test void unavailableOrInconsistentObservationFailsClosedWithoutPrivateCause() {
        failure = new IllegalStateException("private SQL");
        var error = assertThrows(IllegalStateException.class, () -> list(0,20));
        assertEquals("Session report snapshot list unavailable",error.getMessage()); assertNull(error.getCause()); failure=null;
        for (var invalid : List.of(new ListObservation(true, List.of(rows.get(1),rows.get(0)),2),
                new ListObservation(true,List.of(rows.get(0),rows.get(0)),2), new ListObservation(true,List.of(),3))) {
            override=invalid; assertThrows(IllegalStateException.class, () -> list(0,2));
        }
    }
    @Test void metadataAndPageDiagnosticsAreRedacted() {
        var page=list(0,2); assertFalse(page.toString().contains(patient.toString()));
        assertEquals("ReportSnapshotSummary[redacted]", rows.getFirst().toString());
    }
}
