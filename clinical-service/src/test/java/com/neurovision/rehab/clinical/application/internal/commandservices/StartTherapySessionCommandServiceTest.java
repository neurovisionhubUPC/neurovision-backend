package com.neurovision.rehab.clinical.application.internal.commandservices;

import static org.junit.jupiter.api.Assertions.*;

import com.neurovision.rehab.clinical.application.ports.out.persistence.*;
import com.neurovision.rehab.clinical.domain.model.aggregates.*;
import com.neurovision.rehab.clinical.domain.model.valueobjects.*;
import com.neurovision.rehab.clinical.domain.model.valueobjects.TherapeuticExerciseParameters.*;
import com.neurovision.rehab.clinical.support.TherapeuticConfigurationTestData;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class StartTherapySessionCommandServiceTest {
    private static final UUID SESSION_ID = UUID.fromString("10000000-0000-0000-0000-000000000001");
    private static final Instant NOW = Instant.parse("2026-09-14T04:30:00Z");
    private FakePlanRepository plans;
    private FakeSessionRepository sessions;
    private FakeRevisionRepository revisions;
    private FakeAssignments assignments;
    private FakeConfigurations configurations;
    private StartTherapySessionCommandService service;

    @BeforeEach
    void setUp() {
        var lockOrder = new ArrayList<String>();
        plans = new FakePlanRepository(lockOrder);
        plans.plan = TherapySessionTestData.plan();
        sessions = new FakeSessionRepository(lockOrder);
        revisions = new FakeRevisionRepository();
        assignments = new FakeAssignments(lockOrder, plans.plan);
        configurations = new FakeConfigurations();
        configurations.values.add(TherapeuticConfigurationTestData.visualSearchConfiguration(
                plans.plan.id(), UUID.fromString("40000000-0000-0000-0000-000000000001"), 1,
                NOW.minusSeconds(3600)));
        configurations.values.add(fixationConfiguration(plans.plan.id(), 1, NOW.minusSeconds(3600)));
        service = new StartTherapySessionCommandService(
                plans, sessions, revisions, assignments, configurations, () -> SESSION_ID,
                Clock.fixed(NOW, ZoneOffset.UTC), Duration.ofMinutes(2));
    }

    @Test
    void createsSessionWithExactServerResolvedConfigurationAndPeruQuotaDate() {
        var result = service.handle(command("attempt-1", "visual-search"));

        assertEquals(StartTherapySessionResult.Outcome.CREATED, result.outcome());
        assertEquals(LocalDate.of(2026, 9, 13), result.session().quotaDate());
        assertEquals(NOW.plusSeconds(120), result.session().leaseExpiresAt().orElseThrow());
        assertEquals(UUID.fromString("40000000-0000-0000-0000-000000000001"),
                result.session().therapeuticConfigurationId().value());
        assertEquals(1, result.session().configurationVersion());
        assertEquals(TherapeuticExercise.VISUAL_SEARCH, result.session().therapeuticExercise());
        assertEquals(1, result.session().parameterSchemaVersion());
        assertInstanceOf(VisualSearch.class, result.session().configurationParameters());
        assertEquals(List.of("plan-lock", "assignment-lock", "patient-lock"), plans.lockOrder);
    }

    @Test
    void commandCarriesOnlyPatientPlanExerciseAndIdempotencyIntent() {
        assertEquals(4, StartTherapySessionCommand.class.getRecordComponents().length);
        assertArrayEquals(new String[] {"clinicalPatientId", "therapyPlanId", "exerciseCode", "idempotencyKey"},
                Arrays.stream(StartTherapySessionCommand.class.getRecordComponents())
                        .map(java.lang.reflect.RecordComponent::getName).toArray(String[]::new));
    }

    @Test
    void idempotentRetryReturnsSameFrozenSessionAfterNewConfigurationBecomesEffective() {
        var first = service.handle(command("attempt-1", "visual-search"));
        configurations.values.add(TherapeuticConfigurationTestData.visualSearchConfiguration(
                plans.plan.id(), UUID.fromString("40000000-0000-0000-0000-000000000009"), 2, NOW));

        var second = service.handle(command("attempt-1", "visual-search"));

        assertEquals(StartTherapySessionResult.Outcome.IDEMPOTENT_REPLAY, second.outcome());
        assertSame(first.session(), second.session());
        assertEquals(1, second.session().configurationVersion());
        assertEquals(1, sessions.saveCalls);
    }

    @Test
    void rejectsIdempotencyKeyReusedWithDifferentExercise() {
        service.handle(command("attempt-1", "visual-search"));
        assertThrows(TherapySessionStartConflictException.class,
                () -> service.handle(command("attempt-1", "fixation")));
    }

    @Test
    void rejectsNewStartWhilePatientAlreadyHasActiveSession() {
        var active = service.handle(command("attempt-1", "visual-search")).session();
        var conflict = assertThrows(TherapySessionStartConflictException.class,
                () -> service.handle(command("attempt-2", "fixation")));
        assertEquals(active.id(), conflict.activeSessionId().orElseThrow());
        assertEquals(1, sessions.saveCalls);
    }

    @Test
    void preservesSessionExerciseAndSafetyAdmissionRules() {
        sessions.consumedSlots = 3;
        assertThrows(IllegalStateException.class, () -> service.handle(command("quota", "visual-search")));
        sessions.consumedSlots = 0;
        sessions.exposureSeconds = 26 * 60L;
        assertThrows(IllegalStateException.class, () -> service.handle(command("target", "visual-search")));
        plans.plan = TherapySessionTestData.plan(120, 60);
        sessions.exposureSeconds = 46 * 60L;
        assertThrows(IllegalStateException.class, () -> service.handle(command("hard", "visual-search")));

        plans.plan = TherapySessionTestData.plan();
        sessions.exposureSeconds = 0;
        sessions.consumedExerciseSlots.put(new ExerciseCode("visual-search"), 1L);
        assertThrows(IllegalStateException.class,
                () -> service.handle(command("visual-exhausted", "visual-search")));
        assertEquals(new ExerciseCode("fixation"),
                service.handle(command("fixation-available", "fixation")).session().exerciseCode());
    }

    @Test
    void rejectsRevokedAssignmentAndMissingFutureOrMismatchedConfigurationGenerically() {
        assignments.active = false;
        assertThrows(SecurityException.class, () -> service.handle(command("revoked", "visual-search")));

        assignments.active = true;
        configurations.values.clear();
        configurations.values.add(TherapeuticConfigurationTestData.visualSearchConfiguration(
                plans.plan.id(), UUID.randomUUID(), 1, NOW.plusSeconds(1)));
        assertThrows(TherapySessionConfigurationUnavailableException.class,
                () -> service.handle(command("future", "visual-search")));

        configurations.forced = TherapeuticConfigurationTestData.visualSearchConfiguration(
                new TherapyPlanId(UUID.randomUUID()), UUID.randomUUID(), 1, NOW.minusSeconds(1));
        assertThrows(TherapySessionConfigurationUnavailableException.class,
                () -> service.handle(command("cross-plan", "visual-search")));

        configurations.forced = fixationConfiguration(plans.plan.id(), 1, NOW.minusSeconds(1));
        assertThrows(TherapySessionConfigurationUnavailableException.class,
                () -> service.handle(command("wrong-exercise", "visual-search")));

        var active = TherapeuticConfigurationTestData.visualSearchConfiguration(
                plans.plan.id(), UUID.randomUUID(), 4, NOW.minusSeconds(1));
        configurations.forced = TherapeuticExerciseConfiguration.rehydrate(
                active.id(), active.therapyPlanId(), active.exercise(), active.version(),
                active.parameterSchemaVersion(), active.effectiveFrom(), active.createdByDoctorSubject(),
                active.createdAt(), TherapeuticConfigurationStatus.REVOKED, active.parameters());
        assertThrows(TherapySessionConfigurationUnavailableException.class,
                () -> service.handle(command("inactive", "visual-search")));
    }

    @Test
    void usesLatestEffectiveConfigurationAndLatestEligiblePlanRevision() {
        configurations.values.add(TherapeuticConfigurationTestData.visualSearchConfiguration(
                plans.plan.id(), UUID.fromString("40000000-0000-0000-0000-000000000002"), 2,
                NOW.minusSeconds(1)));
        configurations.values.add(TherapeuticConfigurationTestData.visualSearchConfiguration(
                plans.plan.id(), UUID.fromString("40000000-0000-0000-0000-000000000003"), 3,
                NOW.plusSeconds(1)));
        var session = service.handle(command("effective", "visual-search")).session();
        assertEquals(2, session.configurationVersion());

        sessions.stored.clear();
        revisions.values.add(TherapyPlanRevision.create(new TherapyPlanRevisionId(UUID.randomUUID()),
                plans.plan.id(), 1, UUID.randomUUID(), "b".repeat(64),
                TherapyPlanRevisionKind.IMMEDIATE, NOW, NOW,
                plans.plan.doctorSubject(), null, 1, null, null, null, null));
        sessions.consumedSlots = 1;
        assertThrows(IllegalStateException.class,
                () -> service.handle(command("revised-quota", "visual-search")));
    }

    private StartTherapySessionCommand command(String key, String exercise) {
        return new StartTherapySessionCommand(plans.plan.clinicalPatientId(), plans.plan.id(),
                new ExerciseCode(exercise), new IdempotencyKey(key));
    }

    private static TherapeuticExerciseConfiguration fixationConfiguration(
            TherapyPlanId planId, long version, Instant effectiveFrom) {
        return TherapeuticExerciseConfiguration.create(new TherapeuticConfigurationId(UUID.randomUUID()),
                planId, TherapeuticExercise.FIXATION, version, 1, effectiveFrom,
                new CognitoSubject("doctor-subject"), effectiveFrom,
                new Fixation(FixationMode.SUSTAINED_TARGET_FIXATION, TargetPosition.CENTER,
                        1000, 3000, 70, 10, 1000, 1));
    }

    private static final class FakePlanRepository implements TherapyPlanRepository {
        private TherapyPlan plan;
        private final List<String> lockOrder;
        private FakePlanRepository(List<String> lockOrder) { this.lockOrder = lockOrder; }
        @Override public Optional<TherapyPlan> findById(TherapyPlanId id) { return plan.id().equals(id) ? Optional.of(plan) : Optional.empty(); }
        @Override public List<TherapyPlan> findByClinicalPatientId(ClinicalPatientId id) {
            return plan.clinicalPatientId().equals(id) ? List.of(plan) : List.of();
        }
        @Override public Optional<TherapyPlan> findByIdForUpdate(TherapyPlanId id) { lockOrder.add("plan-lock"); return findById(id); }
        @Override public void save(TherapyPlan plan) { this.plan = plan; }
    }

    private static final class FakeAssignments implements DoctorPatientAssignmentRepository {
        private final List<String> lockOrder;
        private final TherapyPlan plan;
        private boolean active = true;
        private FakeAssignments(List<String> lockOrder, TherapyPlan plan) { this.lockOrder = lockOrder; this.plan = plan; }
        @Override public Optional<DoctorPatientAssignment> findActiveByClinicalPatientIdAndDoctorSubject(
                ClinicalPatientId patient, CognitoSubject doctor) { return assignment(patient, doctor); }
        @Override public Optional<DoctorPatientAssignment> findActiveByClinicalPatientIdAndDoctorSubjectForUpdate(
                ClinicalPatientId patient, CognitoSubject doctor) { lockOrder.add("assignment-lock"); return assignment(patient, doctor); }
        private Optional<DoctorPatientAssignment> assignment(ClinicalPatientId patient, CognitoSubject doctor) {
            return active && plan.clinicalPatientId().equals(patient) && plan.doctorSubject().equals(doctor)
                    ? Optional.of(DoctorPatientAssignment.assign(new DoctorPatientAssignmentId(UUID.randomUUID()),
                            patient, doctor, Instant.EPOCH)) : Optional.empty();
        }
        @Override public void save(DoctorPatientAssignment assignment) {}
    }

    private static final class FakeConfigurations implements TherapeuticExerciseConfigurationRepository {
        private final List<TherapeuticExerciseConfiguration> values = new ArrayList<>();
        private TherapeuticExerciseConfiguration forced;
        @Override public OptionalLong findLatestVersion(TherapyPlanId plan, TherapeuticExercise exercise) { return OptionalLong.empty(); }
        @Override public Optional<TherapeuticExerciseConfiguration> findEffective(
                TherapyPlanId plan, TherapeuticExercise exercise, Instant at) {
            if (forced != null) return Optional.of(forced);
            return values.stream().filter(value -> value.therapyPlanId().equals(plan))
                    .filter(value -> value.exercise() == exercise)
                    .filter(value -> value.status() == TherapeuticConfigurationStatus.ACTIVE)
                    .filter(value -> !value.effectiveFrom().isAfter(at))
                    .max(Comparator.comparing(TherapeuticExerciseConfiguration::effectiveFrom)
                            .thenComparingLong(TherapeuticExerciseConfiguration::version));
        }
        @Override public void append(TherapeuticExerciseConfiguration configuration) { values.add(configuration); }
    }

    private static final class FakeSessionRepository implements TherapySessionRepository {
        private final List<TherapySession> stored = new ArrayList<>();
        private final List<String> lockOrder;
        private long consumedSlots;
        private long exposureSeconds;
        private final Map<ExerciseCode, Long> consumedExerciseSlots = new HashMap<>();
        private int saveCalls;
        private FakeSessionRepository(List<String> lockOrder) { this.lockOrder = lockOrder; }
        @Override public void acquirePatientStartLock(ClinicalPatientId patientId) { lockOrder.add("patient-lock"); }
        @Override public Optional<TherapySession> findByPatientAndIdempotencyKey(ClinicalPatientId patientId, IdempotencyKey key) {
            return stored.stream().filter(s -> s.clinicalPatientId().equals(patientId) && s.idempotencyKey().equals(key)).findFirst();
        }
        @Override public Optional<TherapySession> findActiveByPatient(ClinicalPatientId patientId) {
            return stored.stream().filter(s -> s.clinicalPatientId().equals(patientId) && s.status() == TherapySessionStatus.ACTIVE).findFirst();
        }
        @Override public Optional<TherapySession> findActiveByPlanForUpdate(TherapyPlanId planId) {
            return stored.stream().filter(s -> s.therapyPlanId().equals(planId) && s.status() == TherapySessionStatus.ACTIVE).findFirst();
        }
        @Override public Optional<TherapySession> findById(TherapySessionId sessionId) {
            return stored.stream().filter(s -> s.id().equals(sessionId)).findFirst();
        }
        @Override public Optional<TherapySession> findByIdForUpdate(TherapySessionId sessionId) {
            return stored.stream().filter(s -> s.id().equals(sessionId)).findFirst();
        }
        @Override public long countConsumedSlots(TherapyPlanId planId, LocalDate quotaDate) { return consumedSlots; }
        @Override public long countConsumedExerciseSlots(TherapyPlanId planId, LocalDate quotaDate, ExerciseCode exerciseCode) {
            return consumedExerciseSlots.getOrDefault(exerciseCode, 0L);
        }
        @Override public long sumExposureSeconds(TherapyPlanId planId, LocalDate quotaDate) { return exposureSeconds; }
        @Override public void save(TherapySession session) { saveCalls++; stored.add(session); }
    }

    private static final class FakeRevisionRepository implements TherapyPlanRevisionRepository {
        private final List<TherapyPlanRevision> values = new ArrayList<>();
        @Override public List<TherapyPlanRevision> findEffective(TherapyPlanId planId, Instant at) {
            return values.stream().filter(value -> !value.effectiveFrom().isAfter(at)).toList();
        }
        @Override public List<TherapyPlanRevision> findByCommandId(TherapyPlanId planId, UUID commandId) { return List.of(); }
        @Override public long findLatestSequence(TherapyPlanId planId) { return 0; }
        @Override public void appendAll(List<TherapyPlanRevision> revisions) {}
    }

    private static final class TherapySessionTestData {
        private static TherapyPlan plan() { return plan(30, 60); }
        private static TherapyPlan plan(int targetMinutes, Integer hardLimitMinutes) {
            return TherapyPlan.rehydrate(new TherapyPlanId(UUID.fromString("20000000-0000-0000-0000-000000000001")),
                    new ClinicalPatientId(UUID.fromString("30000000-0000-0000-0000-000000000001")),
                    new CognitoSubject("doctor-subject"), TherapyPlanStatus.ACTIVE, 7,
                    3, targetMinutes, hardLimitMinutes, 5, 15, 50,
                    Map.of(new ExerciseCode("visual-search"), 1, new ExerciseCode("fixation"), 2));
        }
    }
}
