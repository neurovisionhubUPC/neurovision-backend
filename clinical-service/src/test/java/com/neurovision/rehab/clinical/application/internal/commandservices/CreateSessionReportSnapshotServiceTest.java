package com.neurovision.rehab.clinical.application.internal.commandservices;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.neurovision.rehab.clinical.application.internal.queryservices.*;
import com.neurovision.rehab.clinical.application.ports.out.integration.*;
import com.neurovision.rehab.clinical.application.ports.out.persistence.*;
import com.neurovision.rehab.clinical.application.ports.out.persistence.SessionReportSnapshotRepository.*;
import com.neurovision.rehab.clinical.application.ports.out.persistence.DoctorTherapySessionReadRepository.*;
import com.neurovision.rehab.clinical.support.TherapeuticConfigurationTestData;
import com.neurovision.rehab.clinical.interfaces.rest.transform.SessionReportRestAssembler;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

public class CreateSessionReportSnapshotServiceTest {
    public static final UUID PATIENT = new UUID(0, 1), SESSION = new UUID(0, 2);
    public static final String DOCTOR = "synthetic-doctor", TOKEN = "synthetic-token";
    public static final Instant TIME = Instant.parse("2026-10-01T00:03:00Z");

    public static class Fixture implements SessionReportSnapshotRepository {
        public final Map<String, Snapshot> copies = new ConcurrentHashMap<>();
        public volatile boolean allowed = true, compositionDenied, revokeAtWrite, failRead, failWrite, uncertain, failEncoder;
        public volatile String failPeer;
        public int reads, writes, peers;
        public CyclicBarrier firstRequests;
        public String firstName = "Synthetic";
        public boolean visualSearch, echoVisualOrigin, standardScanning, echoScanningOrigin;
        public volatile SessionReportStandardScanningPort.Summary scanningSummary;
        public int scanningCalls;
        public volatile SessionReportSustainedFixationPort.Summary fixationSummary;
        public boolean echoFixationOrigin;
        public int fixationCalls;
        public boolean exploration, echoExplorationOrigin;
        public int explorationCalls;
        public volatile SessionReportSystematicExplorationPort.Summary explorationSummary;
        public boolean saccade, echoSaccadeOrigin;
        public int saccadeCalls;
        public volatile SessionReportCompensatorySaccadePort.Summary saccadeSummary;
        public volatile SessionReportVisualSearchPort.Summary visualSummary;
        public int visualCalls;
        public final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        public Observation observeByKey(String doctor, UUID patient, UUID session, String key) {
            reads++;
            if (failRead) throw new IllegalStateException("private read");
            var existing = copies.get(key);
            if (firstRequests != null && existing == null) {
                try { firstRequests.await(5, TimeUnit.SECONDS); } catch (Exception failure) { throw new IllegalStateException(failure); }
            }
            return new Observation(allowed, allowed ? Optional.ofNullable(existing) : Optional.empty());
        }
        public Observation observeById(String doctor, UUID patient, UUID session, UUID id) {
            reads++;
            if (failRead) throw new IllegalStateException("private read");
            return new Observation(allowed, allowed ? copies.values().stream().filter(s -> s.id().equals(id)).findFirst() : Optional.empty());
        }
        public synchronized WriteResult insertAuthorized(String doctor, UUID patient, UUID session, String key, Snapshot candidate) {
            writes++;
            if (revokeAtWrite || !allowed) throw new SecurityException("private denial");
            if (failWrite) throw new IllegalStateException("private database");
            var previous = copies.putIfAbsent(key, candidate);
            if (uncertain) throw new IllegalStateException("private commit uncertainty");
            return new WriteResult(previous == null, previous == null ? candidate : previous);
        }
        void peer(String name) {
            peers++;
            if (name.equals(failPeer)) throw new IllegalStateException("private peer");
        }
        public ComposeSessionReportService composer() {
            var sessions = new DoctorTherapySessionQueryService(new DoctorTherapySessionReadRepository() {
                public DoctorTherapySessionReadRepository.Observation observe(UUID p, String d, Filters f, int o, int l) { throw new AssertionError(); }
                public Optional<Detail> observeDetail(UUID p, String d, UUID s) {
                    if (compositionDenied) return Optional.empty();
                    var parameters = exploration ? com.neurovision.rehab.clinical.application.internal.queryservices.ComposeSystematicExplorationReportServiceTest.systematicExplorationParameters()
                            : saccade ? com.neurovision.rehab.clinical.application.internal.queryservices.ComposeCompensatorySaccadeReportServiceTest.compensatorySaccadeParameters()
                            : standardScanning ? com.neurovision.rehab.clinical.application.internal.queryservices.ComposeStandardScanningReportServiceTest.standardScanningParameters() : visualSearch ? TherapeuticConfigurationTestData.visualSearchSnapshot().parameters()
                            : TherapeuticConfigurationTestData.fixationSnapshot().parameters();
                    return Optional.of(new Detail(p, new Session(s, new UUID(0, 3), parameters.exercise().code().value(), "COMPLETED",
                            TIME.minusSeconds(180), TIME.minusSeconds(60), 120, LocalDate.of(2026, 10, 1), new UUID(0, 4), 1, 1),
                            parameters, new SessionLimits(1, 10, 25), TechnicalProgress.notApplicable()));
                }
            });
            AssignedPatientNamesPort names = (targets, token) -> { peer("names"); return Map.of(PATIENT,
                    new AssignedPatientNamesPort.Name("AVAILABLE", firstName, "Patient")); };
            var patients = new AssignedPatientQueryService(new AssignedPatientReadPort() {
                public Page list(String d, int o, int l) { throw new AssertionError(); }
                public Optional<Patient> detail(String d, UUID p) { throw new AssertionError(); }
                public Map<UUID, String> resolve(String d, List<UUID> ids) { return Map.of(PATIENT, "synthetic-patient"); }
            }, names);
            return new ComposeSessionReportService(sessions, patients, names,
                    (d, t) -> { peer("doctor"); return new DoctorReportIdentityPort.Identity(d,
                            DoctorReportIdentityPort.Availability.AVAILABLE, "Synthetic", "Doctor", null, null); },
                    (s, t) -> { peer("analytics"); return new SessionReportAnalyticsPort.ConfirmedNoEvidence(s); }, (s, o, t) -> { peer("visual"); visualCalls++; assertEquals(TOKEN,t);
                        if (echoVisualOrigin) return new SessionReportVisualSearchPort.Summary("visual-search-trial-v1",s,new UUID(0,4),1,o,1,1,0,0,1,java.math.BigDecimal.TEN);
                        return visualSummary; }, (s,o,t) -> { peer("scanning"); scanningCalls++; assertEquals(TOKEN,t);
                        if (echoScanningOrigin) return new SessionReportStandardScanningPort.Summary("standard-scanning-trial-v1",s,new UUID(0,4),1,o,1,1,0,0,1,java.math.BigDecimal.TEN);
                        return scanningSummary; }, (s,o,t) -> { peer("fixation"); fixationCalls++; assertEquals(TOKEN,t);
                        if (echoFixationOrigin) return new SessionReportSustainedFixationPort.Summary("sustained-fixation-trial-v1",s,new UUID(0,4),1,o,1,1000,1);
                        return fixationSummary; }, (s,o,t) -> {
                        peer("saccade"); saccadeCalls++; assertEquals(TOKEN,t);
                        if (echoSaccadeOrigin) return new SessionReportCompensatorySaccadePort.Summary(
                                "compensatory-saccade-trial-v1",s,new UUID(0,4),1,o,1,1,java.math.BigDecimal.valueOf(1000));
                        return saccadeSummary; }, (s,o,t) -> {
                        peer("exploration"); explorationCalls++; assertEquals(TOKEN,t);
                        if (echoExplorationOrigin) return new SessionReportSystematicExplorationPort.Summary(
                                "systematic-exploration-trial-v1",s,new UUID(0,4),1,o,1,List.of(
                                new SessionReportSystematicExplorationPort.Traversal("trial-1",SessionReportSystematicExplorationPort.Outcome.COMPLETED,4,100,4000)));
                        return explorationSummary; }, Clock.fixed(TIME, ZoneOffset.UTC));
        }
        public CreateSessionReportSnapshotService service() {
            var codec = new com.neurovision.rehab.clinical.infrastructure.integration.JsonSessionReportSnapshotEncoder(mapper);
            return new CreateSessionReportSnapshotService(this, composer(), new SessionReportSnapshotEncoder() {
                public String encode(SessionReportV1 report) {
                    if (failEncoder) throw new IllegalArgumentException("private encoder");
                    return codec.encode(report);
                }
                public SessionReportVisualSearchPort.Origin visualSearchSelection(String json, UUID session) { return codec.visualSearchSelection(json,session); }
                public SessionReportStandardScanningPort.Origin standardScanningSelection(String json, UUID session) { return codec.standardScanningSelection(json,session); }
                public SessionReportSustainedFixationPort.Origin sustainedFixationSelection(String json, UUID session) { return codec.sustainedFixationSelection(json,session); }
                public SessionReportSystematicExplorationPort.Origin systematicExplorationSelection(String json, UUID session) { return codec.systematicExplorationSelection(json,session); }
                public SessionReportCompensatorySaccadePort.Origin compensatorySaccadeSelection(String json, UUID session) { return codec.compensatorySaccadeSelection(json,session); }
            });
        }
        public WriteResult create(String key) { return service().create(DOCTOR, TOKEN, PATIENT, SESSION, key); }
    }

    @Test void createsFrozenWhitelistAndReplaysWithoutCompositionWhileNewKeyCreatesAnotherCopy() {
        var f = new Fixture(); var first = f.create(" key-one ");
        assertTrue(first.created()); assertEquals(1, f.writes); assertEquals(3, f.peers);
        assertEquals(PATIENT, first.snapshot().clinicalPatientId()); assertEquals(SESSION, first.snapshot().therapySessionId());
        assertEquals(first.snapshot(), f.copies.get("key-one"));
        assertEquals(TIME, first.snapshot().createdAt()); assertTrue(first.snapshot().reportJson().contains("Synthetic"));
        assertFalse(first.snapshot().reportJson().contains(DOCTOR)); assertFalse(first.snapshot().reportJson().contains(TOKEN));
        f.firstName = "Changed"; f.failPeer = "names";
        var replay = f.create("key-one");
        assertFalse(replay.created()); assertEquals(first.snapshot(), replay.snapshot()); assertEquals(3, f.peers); assertEquals(1, f.writes);
        f.failPeer = null; var second = f.create("key-two");
        assertTrue(second.created()); assertNotEquals(first.snapshot().id(), second.snapshot().id());
        assertTrue(second.snapshot().reportJson().contains("Changed")); assertFalse(first.snapshot().reportJson().contains("Changed"));
        assertEquals(first.snapshot(), f.copies.get("key-one")); assertEquals(second.snapshot(), f.copies.get("key-two"));
        assertEquals(2, f.copies.size());
    }
    @Test void deniedOrUnavailableReadsNeverComposeOrWriteAndReplayRequiresCurrentAuthorization() {
        var f = new Fixture(); f.create("key"); f.allowed = false;
        assertThrows(SecurityException.class, () -> f.create("key")); assertEquals(3, f.peers); assertEquals(1, f.writes);
        f.allowed = true; f.failRead = true;
        var error = assertThrows(IllegalStateException.class, () -> f.create("new"));
        assertEquals("Session report snapshot unavailable", error.getMessage()); assertNull(error.getCause());
        assertEquals(1, f.writes); assertEquals(3, f.peers);
    }
    @Test void failuresBeforePersistenceAndRevocationAtWriteLeaveNoCopy() {
        for (String peer : List.of("names", "doctor", "analytics")) {
            var f = new Fixture(); f.failPeer = peer;
            assertThrows(IllegalStateException.class, () -> f.create("key")); assertEquals(0, f.writes); assertTrue(f.copies.isEmpty());
        }
        var denied = new Fixture(); denied.compositionDenied = true;
        assertThrows(SecurityException.class, () -> denied.create("key")); assertEquals(0, denied.peers); assertEquals(0, denied.writes);
        var encoding = new Fixture(); encoding.failEncoder = true;
        assertThrows(IllegalStateException.class, () -> encoding.create("key")); assertEquals(0, encoding.writes);
        var revoked = new Fixture(); revoked.revokeAtWrite = true;
        assertThrows(SecurityException.class, () -> revoked.create("key")); assertTrue(revoked.copies.isEmpty());
    }
    @Test void writeFailureOrUncertainCommitIsNotRetriedAndExplicitSameKeyReplayFindsWinner() {
        for (boolean uncertain : List.of(false, true)) {
            var f = new Fixture(); f.failWrite = !uncertain; f.uncertain = uncertain;
            var failure = assertThrows(IllegalStateException.class, () -> f.create("key"));
            assertNull(failure.getCause()); assertEquals(1, f.writes); assertEquals(1, f.reads);
            f.failWrite = false; f.uncertain = false;
            var retry = f.create("key"); assertEquals(!uncertain, retry.created());
            assertEquals(uncertain ? 1 : 2, f.writes); assertEquals(uncertain ? 3 : 6, f.peers);
        }
    }
    @Test void simultaneousFirstRequestsReturnOnePersistedWinner() throws Exception {
        var f = new Fixture(); f.firstRequests = new CyclicBarrier(2);
        try (var pool = Executors.newVirtualThreadPerTaskExecutor()) {
            var a = pool.submit(() -> f.create("same")); var b = pool.submit(() -> f.create("same"));
            var first = a.get(10, TimeUnit.SECONDS); var second = b.get(10, TimeUnit.SECONDS);
            assertNotEquals(first.created(), second.created()); assertEquals(first.snapshot(), second.snapshot()); assertEquals(1, f.copies.size());
        }
    }
    @Test void invalidInputNeverReadsAndSensitiveRecordsHaveRedactedDiagnostics() {
        var f = new Fixture();
        for (String key : Arrays.asList(null, "", " ", "x".repeat(129)))
            assertThrows(IllegalArgumentException.class, () -> f.create(key));
        assertThrows(IllegalArgumentException.class, () -> f.service().create(" padded", TOKEN, PATIENT, SESSION, "key"));
        assertThrows(IllegalArgumentException.class, () -> f.service().create(DOCTOR, "", PATIENT, SESSION, "key"));
        assertThrows(IllegalArgumentException.class, () -> f.service().create(DOCTOR, TOKEN, null, SESSION, "key"));
        assertEquals(0, f.reads); var result = f.create("key");
        assertFalse(result.toString().contains("Synthetic")); assertFalse(result.snapshot().toString().contains("Synthetic"));
    }
}
