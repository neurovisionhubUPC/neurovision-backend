package com.neurovision.rehab.clinical.application.internal.commandservices;

import com.neurovision.rehab.clinical.application.ports.out.integration.SessionReportSystematicExplorationPort.*;
import com.neurovision.rehab.clinical.application.ports.out.persistence.SessionReportSnapshotRepository.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static com.neurovision.rehab.clinical.application.internal.commandservices.CreateSessionReportSnapshotServiceTest.*;

class CreateSystematicExplorationReportSnapshotServiceTest {
    Fixture fixture() {
        var f = new Fixture(); f.exploration = true;
        f.explorationSummary = summary(Origin.SYNTHETIC); return f;
    }
    static Summary summary(Origin origin) {
        return new Summary("systematic-exploration-trial-v1",SESSION,new UUID(0,4),1,origin,1,List.of(new Traversal("trial-1",Outcome.COMPLETED,4,100,4000)));
    }
    WriteResult create(Fixture f, String key, Origin origin) { return f.service().create(DOCTOR,TOKEN,PATIENT,SESSION,key,null,null,null,null,origin); }
    private void assertFrozenSection(Fixture f, Snapshot copy, Summary expected) throws Exception {
        var json = f.mapper.readTree(copy.reportJson());
        // Parse both wire documents identically; valueToTree retains Java LongNode widths.
        var expectedSection = f.mapper.readTree(f.mapper.writeValueAsString(expected));
        assertEquals(expectedSection, json.get("systematicExploration"));
        assertEquals(PATIENT, copy.clinicalPatientId()); assertEquals(SESSION, copy.therapySessionId());
        assertEquals(TIME, copy.createdAt());
        assertEquals(PATIENT.toString(), json.path("clinicalPatientId").textValue());
        assertEquals(SESSION.toString(), json.path("execution").path("therapySessionId").textValue());
        assertEquals(expected.configurationId().toString(), json.path("configuration").path("configurationId").textValue());
        assertEquals(expected.configurationVersion(), json.path("configuration").path("configurationVersion").longValue());
    }
    @Test void matchingSelectionReplaysFrozenSectionWithoutEnrichmentAndNewKeyCapturesNewMetrics() throws Exception {
        var f = fixture(); var first = create(f," key ",Origin.SYNTHETIC); assertTrue(first.created());
        var originalSummary = f.explorationSummary; var originalJson = first.snapshot().reportJson();
        assertFrozenSection(f,first.snapshot(),originalSummary);
        int peers = f.peers; f.failPeer = "names"; f.explorationSummary = null;
        f.firstName = "Changed";
        var replay = create(f,"key",Origin.SYNTHETIC);
        assertEquals(first.snapshot(),replay.snapshot()); assertFalse(replay.created()); assertEquals(peers,f.peers);
        assertThrows(SessionReportSnapshotSelectionConflictException.class,() -> create(f,"key",Origin.PRODUCER_REPORTED));
        assertThrows(SessionReportSnapshotSelectionConflictException.class,() -> create(f,"key",null));
        assertEquals(peers,f.peers); assertEquals(1,f.writes);
        assertEquals(originalJson,f.copies.get("key").reportJson());
        f.failPeer = null; f.explorationSummary = new Summary("systematic-exploration-trial-v1",SESSION,
                new UUID(0,4),1,Origin.PRODUCER_REPORTED,1,List.of(new Traversal("trial-new",Outcome.TIMED_OUT,1,25,4000)));
        var next = create(f,"new",Origin.PRODUCER_REPORTED); assertTrue(next.created());
        assertNotEquals(first.snapshot().id(),next.snapshot().id()); assertEquals(2,f.copies.size());
        assertFrozenSection(f,next.snapshot(),f.explorationSummary);
        assertFrozenSection(f,first.snapshot(),originalSummary);
        assertEquals(originalJson,f.copies.get("key").reportJson());
        assertEquals(next.snapshot(),f.copies.get("new"));
        assertTrue(next.snapshot().reportJson().contains("Changed")); assertFalse(originalJson.contains("Changed"));
        assertTrue(next.snapshot().reportJson().contains("PRODUCER_REPORTED"));
        assertFalse(first.snapshot().reportJson().contains("PRODUCER_REPORTED"));
    }
    @Test void unavailableSelectedSummaryNeverCreatesAnOmittedOrZeroCopy() {
        var f=fixture(); f.failPeer="exploration";
        var failure=assertThrows(IllegalStateException.class,()->create(f,"key",Origin.SYNTHETIC));
        assertNull(failure.getCause()); assertEquals(0,f.writes); assertTrue(f.copies.isEmpty());
        f.failPeer=null; f.explorationSummary=null;
        assertThrows(IllegalStateException.class,()->create(f,"key",Origin.SYNTHETIC)); assertEquals(0,f.writes);
        assertTrue(f.copies.isEmpty());
    }
    @Test void incompatibleSummaryBindingsNeverReachSnapshotInsertion() {
        for (var invalid : List.of(
                new Summary("systematic-exploration-trial-v1",new UUID(0,99),new UUID(0,4),1,Origin.SYNTHETIC,1,
                        List.of(new Traversal("trial-1",Outcome.COMPLETED,4,100,4000))),
                new Summary("systematic-exploration-trial-v1",SESSION,new UUID(0,99),1,Origin.SYNTHETIC,1,
                        List.of(new Traversal("trial-1",Outcome.COMPLETED,4,100,4000))),
                new Summary("systematic-exploration-trial-v1",SESSION,new UUID(0,4),2,Origin.SYNTHETIC,1,
                        List.of(new Traversal("trial-1",Outcome.COMPLETED,4,100,4000))),
                summary(Origin.PRODUCER_REPORTED))) {
            var f = fixture(); f.explorationSummary = invalid;
            var failure = assertThrows(IllegalStateException.class,()->create(f,"key",Origin.SYNTHETIC));
            assertNull(failure.getCause()); assertEquals(0,f.writes); assertTrue(f.copies.isEmpty());
            assertEquals(1,f.explorationCalls);
        }
    }
    @Test void omittedSectionAndConfirmedEmptySelectionProduceDifferentFrozenCopies() throws Exception {
        var f = fixture(); var omitted = f.create("omitted").snapshot();
        assertFalse(f.mapper.readTree(omitted.reportJson()).has("systematicExploration"));
        assertEquals(0,f.explorationCalls);
        f.explorationSummary = new Summary("systematic-exploration-trial-v1",SESSION,new UUID(0,4),1,
                Origin.SYNTHETIC,0,List.of());
        var empty = create(f,"empty",Origin.SYNTHETIC).snapshot();
        assertFrozenSection(f,empty,f.explorationSummary);
        var section = f.mapper.readTree(empty.reportJson()).path("systematicExploration");
        assertEquals(0,section.path("reportedTrialCount").longValue());
        assertTrue(section.path("traversals").isArray()); assertEquals(0,section.path("traversals").size());
        assertEquals(1,f.explorationCalls); assertEquals(2,f.writes); assertEquals(2,f.copies.size());
        assertEquals(omitted,f.copies.get("omitted")); assertEquals(empty,f.copies.get("empty"));
    }
    @Test void legacyOmissionAndSelectionAreDifferentIncludingBothDirections() {
        var f = fixture(); var old = f.create("key"); int peers = f.peers;
        assertFalse(old.snapshot().reportJson().contains("systematicExploration"));
        assertThrows(SessionReportSnapshotSelectionConflictException.class,() -> create(f,"key",Origin.SYNTHETIC));
        assertEquals(old.snapshot(),f.create("key").snapshot()); assertEquals(peers,f.peers); assertEquals(0,f.explorationCalls);
    }
    @Test void malformedPresentSelectionCannotMasqueradeAsOmitted() {
        var f = fixture();
        for (String json : List.of("{\"systematicExploration\":null}","{\"systematicExploration\":{}}", "{\"systematicExploration\":{\"dataOrigin\":\"SYNTHETIC\"}}")) {
            f.copies.put("key",new Snapshot(UUID.randomUUID(),PATIENT,SESSION,TIME,json));
            assertThrows(IllegalStateException.class,() -> f.create("key"));
            assertThrows(IllegalStateException.class,() -> create(f,"key",Origin.SYNTHETIC));
        }
        assertEquals(0,f.peers); assertEquals(0,f.writes);
    }
    @Test void competingDifferentSelectionsCompareActualPersistedWinner() throws Exception {
        var f = fixture(); f.firstRequests = new CyclicBarrier(2); f.echoExplorationOrigin = true;
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
