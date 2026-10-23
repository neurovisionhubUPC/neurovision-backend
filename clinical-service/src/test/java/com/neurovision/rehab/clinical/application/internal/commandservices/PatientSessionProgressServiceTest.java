package com.neurovision.rehab.clinical.application.internal.commandservices;

import static org.junit.jupiter.api.Assertions.*;
import static com.neurovision.rehab.clinical.support.TherapeuticConfigurationTestData.visualSearchSnapshot;
import com.neurovision.rehab.clinical.application.ports.in.PatientSessionProgressUseCase.*;
import com.neurovision.rehab.clinical.application.ports.out.persistence.*;
import com.neurovision.rehab.clinical.domain.model.aggregates.*;
import com.neurovision.rehab.clinical.domain.model.valueobjects.*;
import java.lang.reflect.Proxy;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;

class PatientSessionProgressServiceTest {
    final Instant start = Instant.parse("2026-01-01T00:00:00Z");
    final CognitoSubject patient = new CognitoSubject("synthetic-patient");
    final ClinicalPatient owner = ClinicalPatient.registerInvited(new ClinicalPatientId(UUID.randomUUID()), patient, start);
    TherapySession session;
    Instant now = start;
    int saves, locks;
    SessionActivityProgress state;
    final Map<UUID, SessionActivityRepository.Receipt> receipts = new HashMap<>();
    SessionActivityRepository.Completion completion;
    boolean broken;
    final SessionActivityRepository store = new SessionActivityRepository() {
        public Optional<SessionActivityProgress> find(TherapySessionId id) { return Optional.ofNullable(state); }
        public Optional<Receipt> receipt(TherapySessionId id, UUID event) { return Optional.ofNullable(receipts.get(event)); }
        public Optional<Completion> completion(TherapySessionId id) { return Optional.ofNullable(completion); }
        public void save(TherapySessionId id, SessionActivityProgress next) { state = next; }
        public void append(TherapySessionId id, Receipt receipt) { receipts.put(receipt.event().eventId(), receipt); }
        public void close(TherapySessionId id, Completion value) { completion = value; }
    };
    PatientSessionProgressService service(boolean legacy, CompletionTarget target) {
        var plan = TherapyPlan.rehydrate(new TherapyPlanId(UUID.randomUUID()), owner.id(), new CognitoSubject("doctor"),
                TherapyPlanStatus.ACTIVE, 1, 3, 30, null, 1, 15, 50, Map.of(new ExerciseCode("visual-search"), 1));
        var old = visualSearchSnapshot();
        var snapshot = legacy ? old : new TherapeuticConfigurationSnapshot(old.configurationId(), 2, old.exercise(), 2,
                new TherapeuticExerciseParameters.Prescribed(old.parameters(), target));
        session = TherapySession.start(new TherapySessionId(UUID.randomUUID()), plan, new ExerciseCode("visual-search"),
                LocalDate.of(2026, 1, 1), snapshot, new IdempotencyKey("start-key"), "a".repeat(64), start, start.plusSeconds(120));
        ClinicalPatientRepository patients = new ClinicalPatientRepository() {
            public Optional<ClinicalPatient> findByPatientSubject(CognitoSubject subject) { return patient.equals(subject) ? Optional.of(owner) : Optional.empty(); }
            public void save(ClinicalPatient ignored) { throw new AssertionError(); }
        };
        var sessions = (TherapySessionRepository) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{TherapySessionRepository.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "findByIdForUpdate" -> { locks++; yield session.id().equals(args[0]) ? Optional.of(session) : Optional.empty(); }
                    case "save" -> { if (broken) throw new IllegalStateException("storage unavailable"); saves++; yield null; }
                    default -> throw new AssertionError("Unexpected path " + method.getName());
                });
        var clock = new Clock() {
            public ZoneId getZone() { return ZoneOffset.UTC; }
            public Clock withZone(ZoneId zone) { return this; }
            public Instant instant() { return now; }
        };
        return new PatientSessionProgressService(patients, sessions, store, clock);
    }
    PatientSessionProgressService timed() { return service(false, new CompletionTarget(CompletionTarget.Criterion.ACTIVE_TIME, 10)); }
    SessionActivityEvent event(long sequence, SessionActivityEvent.Type type) { return new SessionActivityEvent(1, UUID.randomUUID(), sequence, type, null, null); }
    @Test void exactActivityReplayPreservesReceiptAndDoesNotRenewOrCredit() {
        var service = timed(); var first = event(0, SessionActivityEvent.Type.ACTIVITY_STARTED);
        var ack = service.activity(patient, session.id(), first); var lease = session.leaseExpiresAt();
        now = start.plusSeconds(5);
        assertEquals(ack, service.activity(patient, session.id(), first));
        assertEquals(lease, session.leaseExpiresAt()); assertEquals(1, saves); assertEquals(0, session.validSampleCount());
        assertThrows(SessionProgressConflictException.class, () -> service.activity(patient, session.id(),
                new SessionActivityEvent(1, first.eventId(), 0, SessionActivityEvent.Type.HEARTBEAT, null, null)));
        assertEquals(1, saves); assertEquals(1, state.nextSequence());
    }
    @Test void finishingRequiresGoalDoesNotAutoCloseAndIsIdempotent() {
        var service = timed(); var key = new IdempotencyKey("complete-key");
        service.activity(patient, session.id(), event(0, SessionActivityEvent.Type.ACTIVITY_STARTED));
        assertThrows(SessionProgressConflictException.class, () -> service.complete(patient, session.id(), key, Action.FINISHED));
        assertEquals(TherapySessionStatus.ACTIVE, session.status()); assertNull(completion);
        now = start.plusSeconds(10);
        var progress = service.activity(patient, session.id(), event(1, SessionActivityEvent.Type.HEARTBEAT));
        assertTrue(progress.targetReached()); assertEquals(TherapySessionStatus.ACTIVE, session.status());
        var result = service.complete(patient, session.id(), key, Action.FINISHED);
        assertEquals("COMPLETED", result.status()); assertTrue(session.consumesDailyQuota());
        int before = saves; now = start.plusSeconds(999);
        assertEquals(result, service.complete(patient, session.id(), key, Action.FINISHED)); assertEquals(before, saves);
        assertThrows(SessionProgressConflictException.class, () -> service.complete(patient, session.id(), key, Action.ABANDONED));
        assertThrows(SessionProgressConflictException.class, () -> service.complete(patient, session.id(), new IdempotencyKey("other-key"), Action.FINISHED));
        assertThrows(SessionProgressConflictException.class, () -> service.activity(patient, session.id(), event(2, SessionActivityEvent.Type.HEARTBEAT)));
    }
    @Test void abandonedRetainsPartialExposureWithoutFabricatingValidSamples() {
        var service = timed(); service.activity(patient, session.id(), event(0, SessionActivityEvent.Type.ACTIVITY_STARTED));
        now = start.plusSeconds(5); service.activity(patient, session.id(), event(1, SessionActivityEvent.Type.HEARTBEAT));
        var result = service.complete(patient, session.id(), new IdempotencyKey("abandon-key"), Action.ABANDONED);
        assertEquals("ABANDONED", result.status()); assertFalse(result.targetReached()); assertEquals(5000, result.activeMilliseconds());
        assertEquals(5, session.activeDurationSeconds()); assertEquals(0, session.validSampleCount());
        assertFalse(session.consumesDailyQuota()); // Existing meaningful-activity rule is deliberately unchanged.
    }
    @Test void foreignUnknownAndLegacyCannotCreateProgress() {
        var service = timed();
        assertThrows(SecurityException.class, () -> service.progress(new CognitoSubject("foreign"), session.id()));
        assertThrows(SecurityException.class, () -> service.progress(patient, new TherapySessionId(UUID.randomUUID())));
        final var legacyService = service(true, null);
        assertThrows(SessionProgressConflictException.class, () -> legacyService.activity(patient, session.id(), event(0, SessionActivityEvent.Type.ACTIVITY_STARTED)));
        assertThrows(SessionProgressConflictException.class, () -> legacyService.complete(patient, session.id(), new IdempotencyKey("legacy-key"), Action.ABANDONED));
        assertNull(state); assertEquals(0, saves); assertTrue(receipts.isEmpty());
    }
    @Test void expiryAndCompetingSafetyTransitionCannotBeOverwritten() {
        var service = timed(); now = start.plusSeconds(120);
        assertThrows(SessionProgressConflictException.class, () -> service.activity(patient, session.id(), event(0, SessionActivityEvent.Type.ACTIVITY_STARTED)));
        now = start.plusSeconds(1); session.cancel("Safety", now);
        assertThrows(SessionProgressConflictException.class, () -> service.complete(patient, session.id(), new IdempotencyKey("key"), Action.ABANDONED));
        assertEquals(TherapySessionStatus.CANCELLED, session.status()); assertEquals(0, saves);
    }
    @Test void progressReadHasNoWritesAndStorageFailureIsNotDomainConflict() {
        var service = timed(); var lease = session.leaseExpiresAt();
        assertEquals(0, service.progress(patient, session.id()).nextSequence()); assertNull(state); assertEquals(0, saves);
        assertEquals(lease, session.leaseExpiresAt());
        broken = true;
        assertThrows(IllegalStateException.class, () -> service.activity(patient, session.id(), event(0, SessionActivityEvent.Type.ACTIVITY_STARTED)));
        assertNull(state); assertTrue(receipts.isEmpty());
    }
    @Test void repetitionTargetFinishesOnlyAfterMatchingPrescribedCycles() {
        var service = service(false, new CompletionTarget(CompletionTarget.Criterion.REPETITIONS, 1));
        assertThrows(IllegalStateException.class, () -> session.complete(start));
        assertThrows(IllegalStateException.class, () -> session.completeWithTechnicalProgress(SessionActivityProgress.empty(), start));
        service.activity(patient, session.id(), event(0, SessionActivityEvent.Type.ACTIVITY_STARTED));
        var attempt = UUID.randomUUID(); now = start.plusSeconds(1);
        service.activity(patient, session.id(), new SessionActivityEvent(1, UUID.randomUUID(), 1,
                SessionActivityEvent.Type.REPETITION_STARTED, attempt, 1));
        assertFalse(service.progress(patient, session.id()).targetReached());
        now = start.plusSeconds(2);
        service.activity(patient, session.id(), new SessionActivityEvent(1, UUID.randomUUID(), 2,
                SessionActivityEvent.Type.REPETITION_FINISHED, attempt, 1));
        assertEquals("COMPLETED", service.complete(patient, session.id(), new IdempotencyKey("done"), Action.FINISHED).status());
        assertEquals(0, session.validSampleCount());
    }
    @Test void sequenceConflictsLeaveEvidenceAndLeaseUnchangedAndExpiredReplayIsOnlyAnOldReceipt() {
        var service = timed(); var startEvent = event(0, SessionActivityEvent.Type.ACTIVITY_STARTED);
        var ack = service.activity(patient, session.id(), startEvent);
        var before = state; var lease = session.leaseExpiresAt(); now = start.plusSeconds(5);
        assertThrows(SessionProgressConflictException.class, () -> service.activity(patient, session.id(), event(0, SessionActivityEvent.Type.HEARTBEAT)));
        assertThrows(SessionProgressConflictException.class, () -> service.activity(patient, session.id(), event(2, SessionActivityEvent.Type.HEARTBEAT)));
        assertEquals(before, state); assertEquals(lease, session.leaseExpiresAt()); assertEquals(1, saves);
        now = start.plusSeconds(300);
        assertEquals(ack, service.activity(patient, session.id(), startEvent));
        assertEquals(lease, session.leaseExpiresAt()); assertEquals(1, saves);
        assertThrows(SessionProgressConflictException.class, () -> service.complete(patient, session.id(), new IdempotencyKey("expired"), Action.ABANDONED));
    }
    @Test void gapInterruptsRepetitionAndRejectedOrReplayedEventsPreserveProgressAndLease() {
        var service = service(false, new CompletionTarget(CompletionTarget.Criterion.REPETITIONS, 2));
        var first = event(0, SessionActivityEvent.Type.ACTIVITY_STARTED);
        var firstAck = service.activity(patient, session.id(), first);
        var attempt = UUID.randomUUID(); now = start.plusSeconds(1);
        service.activity(patient, session.id(), new SessionActivityEvent(1, UUID.randomUUID(), 1,
                SessionActivityEvent.Type.REPETITION_STARTED, attempt, 1));
        now = start.plusSeconds(17);
        service.activity(patient, session.id(), new SessionActivityEvent(1, UUID.randomUUID(), 2,
                SessionActivityEvent.Type.REPETITION_FINISHED, attempt, 1));
        assertEquals(SessionActivityProgress.Mode.RESUME_REQUIRED, state.mode());
        assertEquals(0, state.completedRepetitions()); assertEquals(1000, state.activeMilliseconds());
        var saved = state; var lease = session.leaseExpiresAt(); int writes = saves, count = receipts.size();
        assertThrows(SessionProgressConflictException.class, () -> service.activity(patient, session.id(), event(3, SessionActivityEvent.Type.HEARTBEAT)));
        assertEquals(firstAck, service.activity(patient, session.id(), first));
        assertEquals(saved, state); assertEquals(lease, session.leaseExpiresAt());
        assertEquals(writes, saves); assertEquals(count, receipts.size());
        now = start.plusSeconds(18); service.activity(patient, session.id(), event(3, SessionActivityEvent.Type.RESUMED));
        var resumed = state; var resumedLease = session.leaseExpiresAt(); int resumedWrites = saves;
        assertThrows(SessionProgressConflictException.class, () -> service.activity(patient, session.id(), new SessionActivityEvent(
                1, UUID.randomUUID(), 4, SessionActivityEvent.Type.REPETITION_STARTED, attempt, 1)));
        assertEquals(resumed, state); assertEquals(resumedLease, session.leaseExpiresAt()); assertEquals(resumedWrites, saves);
        var fresh = UUID.randomUUID(); now = start.plusSeconds(19);
        service.activity(patient, session.id(), new SessionActivityEvent(1, UUID.randomUUID(), 4,
                SessionActivityEvent.Type.REPETITION_STARTED, fresh, 1));
        now = start.plusSeconds(20);
        service.activity(patient, session.id(), new SessionActivityEvent(1, UUID.randomUUID(), 5,
                SessionActivityEvent.Type.REPETITION_FINISHED, fresh, 1));
        assertEquals(1, state.completedRepetitions()); assertFalse(service.progress(patient, session.id()).targetReached());
        assertEquals(TherapySessionStatus.ACTIVE, session.status()); assertEquals(0, session.validSampleCount());
    }
}
