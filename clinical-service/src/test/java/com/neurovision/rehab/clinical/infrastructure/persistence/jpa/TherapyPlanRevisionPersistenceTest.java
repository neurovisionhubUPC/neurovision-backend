package com.neurovision.rehab.clinical.infrastructure.persistence.jpa;

import static org.junit.jupiter.api.Assertions.*;

import com.neurovision.rehab.clinical.application.internal.commandservices.*;
import com.neurovision.rehab.clinical.application.ports.out.persistence.*;
import com.neurovision.rehab.clinical.domain.model.aggregates.*;
import com.neurovision.rehab.clinical.domain.model.valueobjects.*;
import com.neurovision.rehab.clinical.infrastructure.persistence.jpa.transaction.TransactionalTherapyPlanReviser;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;

@QuarkusTest
class TherapyPlanRevisionPersistenceTest {
    @Inject EntityManager entityManager;
    @Inject ClinicalPatientRepository patients;
    @Inject DoctorPatientAssignmentRepository assignments;
    @Inject TherapyPlanRepository plans;
    @Inject TherapyPlanRevisionRepository revisions;
    @Inject TherapySessionRepository sessions;
    @Inject TransactionalTherapyPlanReviser reviser;

    private final ClinicalPatientId patientId = new ClinicalPatientId(UUID.fromString("31000000-0000-0000-0000-000000000001"));
    private final TherapyPlanId planId = new TherapyPlanId(UUID.fromString("32000000-0000-0000-0000-000000000002"));
    private final CognitoSubject doctor = new CognitoSubject("phase-2b-doctor");

    @BeforeEach
    void setUp() {
        cleanDatabase();
        Instant createdAt = Instant.parse("2026-09-13T12:00:00Z");
        patients.save(ClinicalPatient.registerInvited(patientId, new CognitoSubject("phase-2b-patient"), createdAt));
        assignments.save(DoctorPatientAssignment.assign(new DoctorPatientAssignmentId(UUID.randomUUID()),
                patientId, doctor, createdAt));
        plans.save(basePlan());
    }

    @AfterEach
    void tearDown() { cleanDatabase(); }

    @Test
    void persistsLinkedMixedRevisionsAndResolvesTheirEffectiveDates() {
        UUID commandId = UUID.randomUUID();
        var result = reviser.handle(new ReviseTherapyPlanCommand(commandId, planId, patientId, doctor,
                2, 40, null, Map.of(new ExerciseCode("fixation"), 1), null, null));

        assertEquals(2, result.revisions().size());
        assertEquals(2L, countRows("therapy_plan_revisions"));
        assertEquals(1L, countRows("therapy_plan_revision_exercises"));
        assertEquals(4L, schemaVersion());
        assertThrows(RuntimeException.class, () -> QuarkusTransaction.requiringNew().run(() ->
                entityManager.createNativeQuery("UPDATE therapy_plan_revisions SET sequence = 99 WHERE command_id = :id")
                        .setParameter("id", commandId).executeUpdate()));
        assertThrows(RuntimeException.class, () -> QuarkusTransaction.requiringNew().run(() ->
                entityManager.createNativeQuery(
                                "DELETE FROM therapy_plan_revision_exercises WHERE therapy_plan_revision_id IN "
                                        + "(SELECT id FROM therapy_plan_revisions WHERE command_id = :id)")
                        .setParameter("id", commandId).executeUpdate()));
        assertThrows(RuntimeException.class, () -> QuarkusTransaction.requiringNew().run(() ->
                entityManager.createNativeQuery("DELETE FROM therapy_plan_revisions WHERE command_id = :id")
                        .setParameter("id", commandId).executeUpdate()));
        assertAll(() -> assertEquals(2L, countRows("therapy_plan_revisions")),
                () -> assertEquals(1L, countRows("therapy_plan_revision_exercises")));

        var replay = reviser.handle(new ReviseTherapyPlanCommand(commandId, planId, patientId, doctor,
                2, 40, null, Map.of(new ExerciseCode("fixation"), 1), null, null));
        assertTrue(replay.idempotentReplay());
        assertEquals(2L, countRows("therapy_plan_revisions"));
    }

    @Test
    void competingRevisionCommandsSerializeSequences() throws Exception {
        var executor = Executors.newFixedThreadPool(2);
        var start = new CountDownLatch(1);
        try {
            Future<List<Long>> first = executor.submit(() -> reviseAfter(start, 2));
            Future<List<Long>> second = executor.submit(() -> reviseAfter(start, 1));
            start.countDown();
            var sequences = new HashSet<Long>();
            sequences.addAll(first.get(10, TimeUnit.SECONDS));
            sequences.addAll(second.get(10, TimeUnit.SECONDS));
            assertEquals(Set.of(1L, 2L), sequences);
            assertEquals(2L, countRows("therapy_plan_revisions"));
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void competingDeferredCommandsSerializeAndHighestSequenceWinsAtTargetBoundary() throws Exception {
        var executor = Executors.newFixedThreadPool(2);
        var start = new CountDownLatch(1);
        try {
            Future<List<TherapyPlanRevision>> first = executor.submit(() -> deferredAfter(start, 40));
            Future<List<TherapyPlanRevision>> second = executor.submit(() -> deferredAfter(start, 50));
            start.countDown();
            var persisted = new ArrayList<TherapyPlanRevision>();
            persisted.addAll(first.get(10, TimeUnit.SECONDS));
            persisted.addAll(second.get(10, TimeUnit.SECONDS));

            var ordered = persisted.stream().sorted(Comparator.comparingLong(TherapyPlanRevision::sequence)).toList();
            assertEquals(List.of(1L, 2L), ordered.stream().map(TherapyPlanRevision::sequence).toList());
            var target = ordered.get(0).effectiveFrom();
            var effective = TherapyPlanRevision.applyEligible(basePlan(),
                    QuarkusTransaction.requiringNew().call(() -> revisions.findEffective(planId, target)), target);
            assertEquals(ordered.get(1).dailyTargetDurationMinutes().orElseThrow(),
                    effective.dailyTargetDurationMinutes());
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void competingAssignmentRevocationMakesRevisionFailClosed() throws Exception {
        var executor = Executors.newFixedThreadPool(2);
        var assignmentLocked = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        try {
            Future<?> revoke = executor.submit(() -> QuarkusTransaction.requiringNew().run(() -> {
                var assignment = assignments.findActiveByClinicalPatientIdAndDoctorSubjectForUpdate(patientId, doctor)
                        .orElseThrow();
                assignmentLocked.countDown();
                await(release);
                assignment.end(Instant.now());
                assignments.save(assignment);
            }));
            assertTrue(assignmentLocked.await(5, TimeUnit.SECONDS));
            Future<?> change = executor.submit(() -> reviser.handle(new ReviseTherapyPlanCommand(
                    UUID.randomUUID(), planId, patientId, doctor, 2, null, null, null, null, null)));
            release.countDown();
            revoke.get(10, TimeUnit.SECONDS);
            var failure = assertThrows(ExecutionException.class, () -> change.get(10, TimeUnit.SECONDS));
            assertInstanceOf(SecurityException.class, failure.getCause());
            assertEquals(0L, countRows("therapy_plan_revisions"));
        } finally {
            release.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    void safetyReductionCancelsActiveSessionInTheSameTransaction() {
        Instant started = Instant.now().minusSeconds(30);
        var session = TherapySession.start(new TherapySessionId(UUID.randomUUID()), basePlan(),
                new ExerciseCode("fixation"), started.atZone(ZoneId.of("America/Lima")).toLocalDate(),
                new IdempotencyKey("phase-2b-active"), "c".repeat(64), started, started.plusSeconds(120));
        QuarkusTransaction.requiringNew().run(() -> sessions.save(session));

        reviser.handle(new ReviseTherapyPlanCommand(UUID.randomUUID(), planId, patientId, doctor,
                null, null, 45, null, null, "Safety ceiling reduced"));

        var restored = QuarkusTransaction.requiringNew().call(() ->
                sessions.findByIdForUpdate(session.id()).orElseThrow());
        assertEquals(TherapySessionStatus.CANCELLED, restored.status());
        assertEquals("Safety ceiling reduced", restored.terminationReason().orElseThrow());
        assertEquals(1L, countRows("therapy_plan_revisions"));
    }

    private List<Long> reviseAfter(CountDownLatch start, int newLimit) {
        await(start);
        return reviser.handle(new ReviseTherapyPlanCommand(UUID.randomUUID(), planId, patientId, doctor,
                newLimit, null, null, null, null, null)).revisions().stream()
                .map(TherapyPlanRevision::sequence).toList();
    }

    private List<TherapyPlanRevision> deferredAfter(CountDownLatch start, int targetMinutes) {
        await(start);
        return reviser.handle(new ReviseTherapyPlanCommand(UUID.randomUUID(), planId, patientId, doctor,
                null, targetMinutes, null, null, null, null)).revisions();
    }

    private TherapyPlan basePlan() {
        return TherapyPlan.rehydrate(planId, patientId, doctor, TherapyPlanStatus.ACTIVE,
                1, 3, 30, 60, 5, 15, 50,
                Map.of(new ExerciseCode("visual-search"), 1, new ExerciseCode("fixation"), 2));
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("Test coordination timed out");
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(exception);
        }
    }

    private long countRows(String table) {
        return QuarkusTransaction.requiringNew().call(() -> ((Number) entityManager
                .createNativeQuery("SELECT count(*) FROM " + table).getSingleResult()).longValue());
    }

    private long schemaVersion() {
        return QuarkusTransaction.requiringNew().call(() -> ((Number) entityManager.createNativeQuery(
                "SELECT max(installed_rank) FROM flyway_schema_history WHERE success").getSingleResult()).longValue());
    }

    private void cleanDatabase() {
        QuarkusTransaction.requiringNew().run(() -> {
            // TRUNCATE is deliberately test-only: row-level append-only triggers reject DELETE.
            entityManager.createNativeQuery(
                    "TRUNCATE TABLE therapy_plan_revision_exercises, therapy_plan_revisions")
                    .executeUpdate();
            entityManager.createNativeQuery("DELETE FROM therapeutic_exercise_configurations").executeUpdate();
            entityManager.createNativeQuery("DELETE FROM therapy_sessions").executeUpdate();
            entityManager.createNativeQuery("DELETE FROM therapy_plan_exercise_prescriptions").executeUpdate();
            entityManager.createNativeQuery("DELETE FROM therapy_plans").executeUpdate();
            entityManager.createNativeQuery("DELETE FROM inbox_events").executeUpdate();
            entityManager.createNativeQuery("DELETE FROM doctor_patient_assignments").executeUpdate();
            entityManager.createNativeQuery("DELETE FROM clinical_patients").executeUpdate();
        });
    }
}
