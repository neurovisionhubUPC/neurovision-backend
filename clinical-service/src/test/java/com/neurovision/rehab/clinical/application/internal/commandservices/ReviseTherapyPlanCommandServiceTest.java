package com.neurovision.rehab.clinical.application.internal.commandservices;

import static org.junit.jupiter.api.Assertions.*;

import com.neurovision.rehab.clinical.application.ports.out.persistence.*;
import com.neurovision.rehab.clinical.domain.model.aggregates.*;
import com.neurovision.rehab.clinical.domain.model.valueobjects.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ReviseTherapyPlanCommandServiceTest {
    private static final Instant NOW = Instant.parse("2026-09-14T04:30:00Z");
    private final TherapyPlanId planId = new TherapyPlanId(UUID.randomUUID());
    private final ClinicalPatientId patientId = new ClinicalPatientId(UUID.randomUUID());
    private final CognitoSubject doctor = new CognitoSubject("doctor");
    private FakePlans plans;
    private FakeAssignments assignments;
    private FakeRevisions revisions;
    private FakeSessions sessions;
    private ReviseTherapyPlanCommandService service;

    @BeforeEach
    void setUp() {
        plans = new FakePlans(plan());
        assignments = new FakeAssignments(true);
        revisions = new FakeRevisions();
        sessions = new FakeSessions();
        service = new ReviseTherapyPlanCommandService(plans, revisions, assignments, sessions,
                UUID::randomUUID, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    void decomposesMixedEffectsAtomicallyWithOneCommandIdentity() {
        UUID commandId = UUID.randomUUID();
        var result = service.handle(new ReviseTherapyPlanCommand(commandId, planId, patientId, doctor,
                2, 40, 45, Map.of(new ExerciseCode("visual-search"), 1,
                        new ExerciseCode("fixation"), 1), null, "Safety reduction"));

        assertFalse(result.idempotentReplay());
        assertEquals(2, result.revisions().size());
        var immediate = result.revisions().get(0);
        var deferred = result.revisions().get(1);
        assertAll(() -> assertEquals(TherapyPlanRevisionKind.IMMEDIATE, immediate.kind()),
                () -> assertEquals(TherapyPlanRevisionKind.DEFERRED, deferred.kind()),
                () -> assertEquals(commandId, immediate.commandId()),
                () -> assertEquals(commandId, deferred.commandId()),
                () -> assertEquals(immediate.commandFingerprint(), deferred.commandFingerprint()),
                () -> assertEquals(NOW, immediate.effectiveFrom()),
                () -> assertEquals(Instant.parse("2026-09-14T05:00:00Z"), deferred.effectiveFrom()));
        assertEquals(List.of("plan-lock", "assignment-lock", "append"), callsWithoutReads());
    }

    @Test
    void resolvesImmediateReductionAndNextPeruDayIncreaseAndDistribution() {
        service.handle(new ReviseTherapyPlanCommand(UUID.randomUUID(), planId, patientId, doctor,
                2, 40, null, Map.of(new ExerciseCode("fixation"), 1), null, null));

        var beforeMidnight = TherapyPlanRevision.applyEligible(plan(), revisions.values,
                Instant.parse("2026-09-14T04:59:59Z"));
        var atMidnight = TherapyPlanRevision.applyEligible(plan(), revisions.values,
                Instant.parse("2026-09-14T05:00:00Z"));
        assertAll(() -> assertEquals(2, beforeMidnight.dailySessionLimit()),
                () -> assertEquals(30, beforeMidnight.dailyTargetDurationMinutes()),
                () -> assertEquals(2, beforeMidnight.dailyExercisePrescriptions().size()),
                () -> assertEquals(40, atMidnight.dailyTargetDurationMinutes()),
                () -> assertEquals(Map.of(new ExerciseCode("fixation"), 1), atMidnight.dailyExercisePrescriptions()));
    }

    @Test
    void lockWaitAcrossLimaMidnightUsesPostLockTime() {
        var mutableClock = new MutableClock(Instant.parse("2026-09-14T04:59:59Z"));
        plans.onLock = () -> mutableClock.set(Instant.parse("2026-09-14T05:00:01Z"));
        service = new ReviseTherapyPlanCommandService(plans, revisions, assignments, sessions,
                UUID::randomUUID, mutableClock);

        var result = service.handle(new ReviseTherapyPlanCommand(UUID.randomUUID(), planId, patientId, doctor,
                null, 40, null, null, null, null));

        assertAll(() -> assertEquals(Instant.parse("2026-09-14T05:00:01Z"),
                        result.revisions().get(0).createdAt()),
                () -> assertEquals(Instant.parse("2026-09-15T05:00:00Z"),
                        result.revisions().get(0).effectiveFrom()));
    }

    @Test
    void lockWaitBehindTerminalCancellationFailsAgainstPostLockState() {
        var mutableClock = new MutableClock(Instant.parse("2026-09-14T04:59:59Z"));
        plans.onLock = () -> {
            var committedAt = Instant.parse("2026-09-14T05:00:01Z");
            mutableClock.set(committedAt);
            revisions.values.add(TherapyPlanRevision.create(
                    new TherapyPlanRevisionId(UUID.randomUUID()), planId, 1, UUID.randomUUID(),
                    "d".repeat(64), TherapyPlanRevisionKind.IMMEDIATE, committedAt, committedAt,
                    doctor, TherapyPlanStatus.CANCELLED, null, null, null, null,
                    "Treatment cancelled while command waited"));
        };
        service = new ReviseTherapyPlanCommandService(plans, revisions, assignments, sessions,
                UUID::randomUUID, mutableClock);

        assertThrows(IllegalStateException.class, () -> service.handle(new ReviseTherapyPlanCommand(
                UUID.randomUUID(), planId, patientId, doctor,
                null, 40, null, null, null, null)));
        assertEquals(1, revisions.values.size());
    }

    @Test
    void laterSameDayCommandCanRestorePendingNextDayValues() {
        var originalDistribution = plan().dailyExercisePrescriptions();
        service.handle(new ReviseTherapyPlanCommand(UUID.randomUUID(), planId, patientId, doctor,
                null, 40, null, Map.of(new ExerciseCode("fixation"), 1), null, null));

        var restoration = service.handle(new ReviseTherapyPlanCommand(UUID.randomUUID(), planId, patientId, doctor,
                null, 30, null, originalDistribution, null, null));

        assertEquals(1, restoration.revisions().size());
        assertEquals(TherapyPlanRevisionKind.DEFERRED, restoration.revisions().get(0).kind());
        var nextDay = TherapyPlanRevision.applyEligible(plan(), revisions.values,
                Instant.parse("2026-09-14T05:00:00Z"));
        assertAll(() -> assertEquals(30, nextDay.dailyTargetDurationMinutes()),
                () -> assertEquals(originalDistribution, nextDay.dailyExercisePrescriptions()));
    }

    @Test
    void laterSameDayCommandSupersedesPendingNextDayIncrease() {
        service.handle(new ReviseTherapyPlanCommand(UUID.randomUUID(), planId, patientId, doctor,
                null, 40, null, null, null, null));
        service.handle(new ReviseTherapyPlanCommand(UUID.randomUUID(), planId, patientId, doctor,
                null, 50, null, null, null, null));

        var before = TherapyPlanRevision.applyEligible(plan(), revisions.values,
                Instant.parse("2026-09-14T04:59:59Z"));
        var nextDay = TherapyPlanRevision.applyEligible(plan(), revisions.values,
                Instant.parse("2026-09-14T05:00:00Z"));
        assertAll(() -> assertEquals(30, before.dailyTargetDurationMinutes()),
                () -> assertEquals(50, nextDay.dailyTargetDurationMinutes()),
                () -> assertEquals(List.of(1L, 2L), revisions.values.stream()
                        .map(TherapyPlanRevision::sequence).toList()));
    }

    @Test
    void retryReturnsSameRowsAndFingerprintMismatchConflicts() {
        UUID commandId = UUID.randomUUID();
        var command = new ReviseTherapyPlanCommand(commandId, planId, patientId, doctor,
                2, null, null, null, null, null);
        var first = service.handle(command);
        var replay = service.handle(command);
        assertTrue(replay.idempotentReplay());
        assertEquals(first.revisions().stream().map(TherapyPlanRevision::id).toList(),
                replay.revisions().stream().map(TherapyPlanRevision::id).toList());
        assertThrows(TherapyPlanRevisionConflictException.class, () -> service.handle(
                new ReviseTherapyPlanCommand(commandId, planId, patientId, doctor,
                        1, null, null, null, null, null)));
        assertEquals(1, revisions.appendCalls);
    }

    @Test
    void requiresPlanOwnershipAndActiveAssignment() {
        assertThrows(SecurityException.class, () -> service.handle(new ReviseTherapyPlanCommand(
                UUID.randomUUID(), planId, patientId, new CognitoSubject("other"),
                2, null, null, null, null, null)));
        assignments.active = false;
        assertThrows(SecurityException.class, () -> service.handle(new ReviseTherapyPlanCommand(
                UUID.randomUUID(), planId, patientId, doctor,
                2, null, null, null, null, null)));
    }

    @Test
    void suspensionAndCancellationRequireReasonAndCancellationIsTerminal() {
        assertThrows(IllegalArgumentException.class, () -> service.handle(new ReviseTherapyPlanCommand(
                UUID.randomUUID(), planId, patientId, doctor,
                null, null, null, null, TherapyPlanStatus.SUSPENDED, " ")));
        service.handle(new ReviseTherapyPlanCommand(UUID.randomUUID(), planId, patientId, doctor,
                null, null, null, null, TherapyPlanStatus.CANCELLED, "Treatment cancelled"));
        assertThrows(IllegalStateException.class, () -> service.handle(new ReviseTherapyPlanCommand(
                UUID.randomUUID(), planId, patientId, doctor,
                2, null, null, null, null, null)));
    }

    @Test
    void safetyChangeCancelsActiveSessionButOrdinaryReductionDoesNot() {
        sessions.active = activeSession();
        service.handle(new ReviseTherapyPlanCommand(UUID.randomUUID(), planId, patientId, doctor,
                2, null, null, null, null, null));
        assertEquals(TherapySessionStatus.ACTIVE, sessions.active.status());
        assertEquals(0, sessions.saveCalls);

        service.handle(new ReviseTherapyPlanCommand(UUID.randomUUID(), planId, patientId, doctor,
                null, null, 45, null, null, "Safety ceiling reduced"));
        assertEquals(TherapySessionStatus.CANCELLED, sessions.active.status());
        assertEquals(1, sessions.saveCalls);
    }

    @Test
    void assignedDoctorCanCreatePlanAndPatientOrUnassignedIdentityCannot() {
        var creator = new CreateTherapyPlanCommandService(assignments, new FakePlans(null), UUID::randomUUID);
        var command = new CreateTherapyPlanCommand(patientId, doctor, 3, 30, 60,
                5, 15, 50, Map.of(new ExerciseCode("fixation"), 3));
        assertEquals(TherapyPlanStatus.ACTIVE, creator.handle(command).status());
        assignments.active = false;
        assertThrows(SecurityException.class, () -> creator.handle(command));
    }

    private List<String> callsWithoutReads() {
        var calls = new ArrayList<String>();
        calls.addAll(plans.calls);
        calls.addAll(assignments.calls);
        if (revisions.appendCalls > 0) calls.add("append");
        return calls;
    }

    private TherapyPlan plan() {
        return TherapyPlan.rehydrate(planId, patientId, doctor, TherapyPlanStatus.ACTIVE,
                1, 3, 30, 60, 5, 15, 50,
                Map.of(new ExerciseCode("visual-search"), 1, new ExerciseCode("fixation"), 2));
    }

    private TherapySession activeSession() {
        return TherapySession.start(new TherapySessionId(UUID.randomUUID()), plan(),
                new ExerciseCode("fixation"), LocalDate.of(2026, 9, 13),
                new IdempotencyKey("active"), "a".repeat(64), NOW.minusSeconds(30), NOW.plusSeconds(90));
    }

    private static final class FakePlans implements TherapyPlanRepository {
        private TherapyPlan plan;
        private final List<String> calls = new ArrayList<>();
        private Runnable onLock = () -> {};
        private FakePlans(TherapyPlan plan) { this.plan = plan; }
        @Override public Optional<TherapyPlan> findById(TherapyPlanId id) { return Optional.ofNullable(plan); }
        @Override public Optional<TherapyPlan> findByIdForUpdate(TherapyPlanId id) {
            calls.add("plan-lock");
            onLock.run();
            return findById(id);
        }
        @Override public void save(TherapyPlan value) { plan = value; }
    }

    private static final class FakeAssignments implements DoctorPatientAssignmentRepository {
        private boolean active;
        private final List<String> calls = new ArrayList<>();
        private FakeAssignments(boolean active) { this.active = active; }
        @Override public Optional<DoctorPatientAssignment> findActiveByClinicalPatientIdAndDoctorSubject(
                ClinicalPatientId patient, CognitoSubject doctor) { return locked(patient, doctor); }
        @Override public Optional<DoctorPatientAssignment> findActiveByClinicalPatientIdAndDoctorSubjectForUpdate(
                ClinicalPatientId patient, CognitoSubject doctor) { calls.add("assignment-lock"); return locked(patient, doctor); }
        private Optional<DoctorPatientAssignment> locked(ClinicalPatientId patient, CognitoSubject doctor) {
            return active ? Optional.of(DoctorPatientAssignment.assign(
                    new DoctorPatientAssignmentId(UUID.randomUUID()), patient, doctor, Instant.EPOCH)) : Optional.empty();
        }
        @Override public void save(DoctorPatientAssignment assignment) {}
    }

    private static final class FakeRevisions implements TherapyPlanRevisionRepository {
        private final List<TherapyPlanRevision> values = new ArrayList<>();
        private int appendCalls;
        @Override public List<TherapyPlanRevision> findEffective(TherapyPlanId planId, Instant at) {
            return values.stream().filter(r -> !r.effectiveFrom().isAfter(at)).toList();
        }
        @Override public List<TherapyPlanRevision> findByCommandId(TherapyPlanId planId, UUID commandId) {
            return values.stream().filter(r -> r.commandId().equals(commandId)).toList();
        }
        @Override public long findLatestSequence(TherapyPlanId planId) {
            return values.stream().mapToLong(TherapyPlanRevision::sequence).max().orElse(0);
        }
        @Override public void appendAll(List<TherapyPlanRevision> revisions) { appendCalls++; values.addAll(revisions); }
    }

    private static final class FakeSessions implements TherapySessionRepository {
        private TherapySession active;
        private int saveCalls;
        @Override public void acquirePatientStartLock(ClinicalPatientId patientId) {}
        @Override public Optional<TherapySession> findByPatientAndIdempotencyKey(ClinicalPatientId id, IdempotencyKey key) { return Optional.empty(); }
        @Override public Optional<TherapySession> findActiveByPatient(ClinicalPatientId id) { return Optional.ofNullable(active); }
        @Override public Optional<TherapySession> findActiveByPlanForUpdate(TherapyPlanId id) { return Optional.ofNullable(active); }
        @Override public Optional<TherapySession> findByIdForUpdate(TherapySessionId id) { return Optional.ofNullable(active); }
        @Override public long countConsumedSlots(TherapyPlanId id, LocalDate date) { return 0; }
        @Override public long countConsumedExerciseSlots(TherapyPlanId id, LocalDate date, ExerciseCode code) { return 0; }
        @Override public long sumExposureSeconds(TherapyPlanId id, LocalDate date) { return 0; }
        @Override public void save(TherapySession session) { saveCalls++; active = session; }
    }

    private static final class MutableClock extends Clock {
        private volatile Instant value;
        private MutableClock(Instant value) { this.value = value; }
        private void set(Instant value) { this.value = value; }
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return value; }
    }
}
