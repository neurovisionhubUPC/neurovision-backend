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
                    var parameters = TherapeuticConfigurationTestData.fixationSnapshot().parameters();
                    return Optional.of(new Detail(p, new Session(s, new UUID(0, 3), "fixation", "COMPLETED",
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
                    (s, t) -> { peer("analytics"); return new SessionReportAnalyticsPort.ConfirmedNoEvidence(s); }, Clock.fixed(TIME, ZoneOffset.UTC));
        }
        public CreateSessionReportSnapshotService service() {
            return new CreateSessionReportSnapshotService(this, composer(), report -> {
                if (failEncoder) throw new IllegalArgumentException("private encoder");
                return SessionReportRestAssembler.toResponse(report, mapper).representation().toString();
            });
        }
        public WriteResult create(String key) { return service().create(DOCTOR, TOKEN, PATIENT, SESSION, key); }
    }

    @Test void createsFrozenWhitelistAndReplaysWithoutCompositionWhileNewKeyCreatesAnotherCopy() {
        var f = new Fixture(); var first = f.create(" key-one ");
        assertTrue(first.created()); assertEquals(1, f.writes); assertEquals(3, f.peers);
        assertEquals(TIME, first.snapshot().createdAt()); assertTrue(first.snapshot().reportJson().contains("Synthetic"));
        assertFalse(first.snapshot().reportJson().contains(DOCTOR)); assertFalse(first.snapshot().reportJson().contains(TOKEN));
        f.firstName = "Changed"; f.failPeer = "names";
        var replay = f.create("key-one");
        assertFalse(replay.created()); assertEquals(first.snapshot(), replay.snapshot()); assertEquals(3, f.peers); assertEquals(1, f.writes);
        f.failPeer = null; var second = f.create("key-two");
        assertTrue(second.created()); assertNotEquals(first.snapshot().id(), second.snapshot().id());
        assertTrue(second.snapshot().reportJson().contains("Changed")); assertFalse(first.snapshot().reportJson().contains("Changed"));
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
