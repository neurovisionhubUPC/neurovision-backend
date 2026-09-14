package com.neurovision.rehab.clinical.infrastructure.persistence.jpa;

import static com.neurovision.rehab.clinical.domain.model.valueobjects.TherapeuticExerciseParameters.*;
import static org.junit.jupiter.api.Assertions.*;

import com.neurovision.rehab.clinical.application.internal.commandservices.AppendTherapeuticConfigurationCommand;
import com.neurovision.rehab.clinical.application.internal.commandservices.TherapeuticConfigurationVersionConflictException;
import com.neurovision.rehab.clinical.application.ports.out.persistence.*;
import com.neurovision.rehab.clinical.domain.model.aggregates.*;
import com.neurovision.rehab.clinical.domain.model.valueobjects.*;
import com.neurovision.rehab.clinical.infrastructure.persistence.jpa.repositories.TherapeuticExerciseConfigurationPanacheRepository;
import com.neurovision.rehab.clinical.infrastructure.persistence.jpa.transaction.TransactionalTherapeuticConfigurationAppender;
import com.neurovision.rehab.clinical.infrastructure.persistence.json.TherapeuticConfigurationJsonCodec;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import java.time.Instant;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.*;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

@QuarkusTest
class TherapeuticExerciseConfigurationPersistenceTest {
    @Inject EntityManager entityManager;
    @Inject ClinicalPatientRepository patients;
    @Inject DoctorPatientAssignmentRepository assignments;
    @Inject TherapyPlanRepository plans;
    @Inject TherapeuticExerciseConfigurationRepository configurations;
    @Inject TherapeuticExerciseConfigurationPanacheRepository configurationEntities;
    @Inject TherapeuticConfigurationJsonCodec codec;
    @Inject TransactionalTherapeuticConfigurationAppender appender;

    private final ClinicalPatientId patientId = new ClinicalPatientId(UUID.fromString("10000000-0000-0000-0000-000000000001"));
    private final TherapyPlanId planId = new TherapyPlanId(UUID.fromString("20000000-0000-0000-0000-000000000002"));
    private final CognitoSubject doctor = new CognitoSubject("phase-2a-doctor");

    @BeforeEach
    void setUp() {
        cleanDatabase();
        Instant createdAt = Instant.parse("2026-09-13T12:00:00Z");
        patients.save(ClinicalPatient.registerInvited(patientId, new CognitoSubject("phase-2a-patient"), createdAt));
        assignments.save(DoctorPatientAssignment.assign(new DoctorPatientAssignmentId(UUID.randomUUID()),
                patientId, doctor, createdAt));
        plans.save(TherapyPlan.rehydrate(planId, patientId, doctor, TherapyPlanStatus.ACTIVE,
                1, 2, 30, null, 5, 15, 50,
                Map.of(TherapeuticExercise.FIXATION.code(), 1,
                        TherapeuticExercise.VISUAL_SEARCH.code(), 1)));
    }

    @AfterEach
    void tearDown() {
        cleanDatabase();
    }

    @Test
    void appliesV3AndRoundTripsCanonicalJsonbThroughHibernate() {
        var parameters = new VisualSearch(VisualSearchMode.TARGET_SEARCH_WITH_DISTRACTORS,
                AffectedHemifield.RIGHT, 12, 5, 600, 7000, new java.math.BigDecimal("0.75"), 60, 3);
        var created = appender.handle(new AppendTherapeuticConfigurationCommand(
                planId, patientId, doctor, TherapeuticExercise.VISUAL_SEARCH, 1,
                Instant.now().plusSeconds(60), parameters));

        var restored = QuarkusTransaction.requiringNew().call(() -> configurationEntities.findById(created.id().value())
                .toDomain(codec));
        String storedJson = QuarkusTransaction.requiringNew().call(() -> (String) entityManager.createNativeQuery(
                        "SELECT parameters::text FROM therapeutic_exercise_configurations WHERE id = :id")
                .setParameter("id", created.id().value()).getSingleResult());

        assertEquals(parameters, restored.parameters());
        assertEquals(created.version(), restored.version());
        assertEquals(codec.encode(parameters), codec.encode(restored.parameters()));
        assertTrue(storedJson.contains("\"affectedHemifieldBias\": 0.75")
                || storedJson.contains("\"affectedHemifieldBias\":0.75"));
        assertTrue(flywayMigrationInstalled("3"));
    }

    @Test
    void databaseRejectsASecondImmutableVersionWithSamePlanAndExerciseVersion() {
        var parameters = new Fixation(FixationMode.SUSTAINED_TARGET_FIXATION,
                TargetPosition.CENTER, 500, 1000, 50, 10, 800, 2);
        Instant now = Instant.now();
        var first = TherapeuticExerciseConfiguration.create(new TherapeuticConfigurationId(UUID.randomUUID()),
                planId, TherapeuticExercise.FIXATION, 1, 1, now, doctor, now, parameters);
        QuarkusTransaction.requiringNew().run(() -> configurations.append(first));

        var duplicateVersion = TherapeuticExerciseConfiguration.create(
                new TherapeuticConfigurationId(UUID.randomUUID()), planId, TherapeuticExercise.FIXATION,
                1, 1, now, doctor, now, parameters);
        assertThrows(TherapeuticConfigurationVersionConflictException.class,
                () -> QuarkusTransaction.requiringNew().run(() -> configurations.append(duplicateVersion)));
        assertEquals(1L, countRows("therapeutic_exercise_configurations"));
    }

    @Test
    void concurrentAppendsSerializeVersionAllocationOnThePlanLock() throws Exception {
        var executor = Executors.newFixedThreadPool(2);
        var start = new CountDownLatch(1);
        try {
            Callable<Long> append = () -> {
                assertTrue(start.await(5, TimeUnit.SECONDS));
                return appender.handle(fixationCommand()).version();
            };
            Future<Long> first = executor.submit(append);
            Future<Long> second = executor.submit(append);
            start.countDown();

            assertEquals(Set.of(1L, 2L), Set.of(first.get(10, TimeUnit.SECONDS), second.get(10, TimeUnit.SECONDS)));
            assertEquals(2L, countRows("therapeutic_exercise_configurations"));
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void appendWaitsForAssignmentLockAndFailsClosedAfterConcurrentRevocation() throws Exception {
        var executor = Executors.newFixedThreadPool(2);
        var assignmentLocked = new CountDownLatch(1);
        var allowRevocation = new CountDownLatch(1);
        try {
            Future<?> revoke = executor.submit(() -> QuarkusTransaction.requiringNew().run(() -> {
                var assignment = assignments.findActiveByClinicalPatientIdAndDoctorSubjectForUpdate(patientId, doctor)
                        .orElseThrow();
                assignmentLocked.countDown();
                await(allowRevocation);
                assignment.end(Instant.now());
                assignments.save(assignment);
            }));
            assertTrue(assignmentLocked.await(5, TimeUnit.SECONDS));
            Future<?> append = executor.submit(() -> appender.handle(fixationCommand()));
            allowRevocation.countDown();
            revoke.get(10, TimeUnit.SECONDS);

            var failure = assertThrows(ExecutionException.class, () -> append.get(10, TimeUnit.SECONDS));
            assertInstanceOf(SecurityException.class, failure.getCause());
            assertEquals(0L, countRows("therapeutic_exercise_configurations"));
        } finally {
            allowRevocation.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    void appenderDefinesTheUseCaseTransactionBoundary() throws Exception {
        var method = TransactionalTherapeuticConfigurationAppender.class.getMethod(
                "handle", AppendTherapeuticConfigurationCommand.class);
        assertNotNull(method.getAnnotation(jakarta.transaction.Transactional.class));
    }

    private AppendTherapeuticConfigurationCommand fixationCommand() {
        return new AppendTherapeuticConfigurationCommand(planId, patientId, doctor,
                TherapeuticExercise.FIXATION, 1, Instant.now().plusSeconds(60),
                new Fixation(FixationMode.SUSTAINED_TARGET_FIXATION,
                        TargetPosition.CENTER, 500, 1000, 50, 10, 800, 2));
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("Timed out waiting for test coordination");
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while coordinating test transactions", exception);
        }
    }

    private boolean flywayMigrationInstalled(String version) {
        return QuarkusTransaction.requiringNew().call(() -> ((Number) entityManager.createNativeQuery(
                        "SELECT count(*) FROM flyway_schema_history WHERE version = :version AND success")
                .setParameter("version", version).getSingleResult()).longValue() == 1L);
    }

    private long countRows(String table) {
        return QuarkusTransaction.requiringNew().call(() -> ((Number) entityManager
                .createNativeQuery("SELECT count(*) FROM " + table).getSingleResult()).longValue());
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
