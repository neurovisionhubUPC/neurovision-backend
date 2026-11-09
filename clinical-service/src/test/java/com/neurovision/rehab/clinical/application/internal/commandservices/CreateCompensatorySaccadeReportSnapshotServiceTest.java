package com.neurovision.rehab.clinical.application.internal.commandservices;

import com.neurovision.rehab.clinical.application.ports.out.integration.SessionReportCompensatorySaccadePort.*;
import com.neurovision.rehab.clinical.application.ports.out.persistence.SessionReportSnapshotRepository.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static com.neurovision.rehab.clinical.application.internal.commandservices.CreateSessionReportSnapshotServiceTest.*;

class CreateCompensatorySaccadeReportSnapshotServiceTest {
    Fixture fixture() {
        var f = new Fixture(); f.saccade = true;
        f.saccadeSummary = summary(Origin.SYNTHETIC); return f;
    }
    static Summary summary(Origin origin) {
        return new Summary("compensatory-saccade-trial-v1",SESSION,new UUID(0,4),1,origin,1,1,java.math.BigDecimal.valueOf(1000));
    }
    WriteResult create(Fixture f, String key, Origin origin) { return f.service().create(DOCTOR,TOKEN,PATIENT,SESSION,key,null,null,null,origin); }
    @Test void matchingSelectionReplaysFrozenSectionWithoutEnrichmentAndNewKeyCapturesNewMetrics() {
        var f = fixture(); var first = create(f," key ",Origin.SYNTHETIC); assertTrue(first.created());
        int peers = f.peers; f.failPeer = "names"; f.saccadeSummary = null;
        var replay = create(f,"key",Origin.SYNTHETIC);
        assertEquals(first.snapshot(),replay.snapshot()); assertFalse(replay.created()); assertEquals(peers,f.peers);
        assertThrows(SessionReportSnapshotSelectionConflictException.class,() -> create(f,"key",Origin.PRODUCER_REPORTED));
        assertThrows(SessionReportSnapshotSelectionConflictException.class,() -> create(f,"key",null));
        assertEquals(peers,f.peers); assertEquals(1,f.writes);
        f.failPeer = null; f.saccadeSummary = summary(Origin.PRODUCER_REPORTED);
        var next = create(f,"new",Origin.PRODUCER_REPORTED); assertTrue(next.created());
        assertTrue(next.snapshot().reportJson().contains("PRODUCER_REPORTED"));
        assertFalse(first.snapshot().reportJson().contains("PRODUCER_REPORTED"));
    }
    @Test void unavailableSelectedSummaryNeverCreatesAnOmittedOrZeroCopy() {
        var f=fixture(); f.failPeer="saccade";
        var failure=assertThrows(IllegalStateException.class,()->create(f,"key",Origin.SYNTHETIC));
        assertNull(failure.getCause()); assertEquals(0,f.writes); assertTrue(f.copies.isEmpty());
        f.failPeer=null; f.saccadeSummary=null;
        assertThrows(IllegalStateException.class,()->create(f,"key",Origin.SYNTHETIC)); assertEquals(0,f.writes);
    }
    @Test void legacyOmissionAndSelectionAreDifferentIncludingBothDirections() {
        var f = fixture(); var old = f.create("key"); int peers = f.peers;
        assertFalse(old.snapshot().reportJson().contains("compensatorySaccade"));
        assertThrows(SessionReportSnapshotSelectionConflictException.class,() -> create(f,"key",Origin.SYNTHETIC));
        assertEquals(old.snapshot(),f.create("key").snapshot()); assertEquals(peers,f.peers); assertEquals(0,f.saccadeCalls);
    }
    @Test void malformedPresentSelectionCannotMasqueradeAsOmitted() {
        var f = fixture();
        for (String json : List.of("{\"compensatorySaccade\":null}","{\"compensatorySaccade\":{}}", "{\"compensatorySaccade\":{\"dataOrigin\":\"SYNTHETIC\"}}")) {
            f.copies.put("key",new Snapshot(UUID.randomUUID(),PATIENT,SESSION,TIME,json));
            assertThrows(IllegalStateException.class,() -> f.create("key"));
            assertThrows(IllegalStateException.class,() -> create(f,"key",Origin.SYNTHETIC));
        }
        assertEquals(0,f.peers); assertEquals(0,f.writes);
    }
    @Test void competingDifferentSelectionsCompareActualPersistedWinner() throws Exception {
        var f = fixture(); f.firstRequests = new CyclicBarrier(2); f.echoSaccadeOrigin = true;
        // Both explicit origins are valid, but the actual persisted winner owns the key.
        try (var pool = Executors.newVirtualThreadPerTaskExecutor()) {
            var a = pool.submit(() -> { try { return (Object)create(f,"same",Origin.PRODUCER_REPORTED); } catch (SessionReportSnapshotSelectionConflictException e) { return e; } });
            var b = pool.submit(() -> { try { return (Object)create(f,"same",Origin.SYNTHETIC); } catch (SessionReportSnapshotSelectionConflictException e) { return e; } });
            var results = List.of(a.get(10,TimeUnit.SECONDS),b.get(10,TimeUnit.SECONDS));
            assertEquals(1,results.stream().filter(WriteResult.class::isInstance).count());
            assertEquals(1,results.stream().filter(SessionReportSnapshotSelectionConflictException.class::isInstance).count());
            assertEquals(1,f.copies.size());
            var winner = results.stream().filter(WriteResult.class::isInstance).map(WriteResult.class::cast).findFirst().orElseThrow();
            assertTrue(winner.created()); assertEquals(winner.snapshot(),f.copies.get("same"));
        }
    }
}
