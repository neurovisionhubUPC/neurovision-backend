package com.neurovision.rehab.clinical.infrastructure.persistence.jpa;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.neurovision.rehab.clinical.application.internal.commandservices.*;
import com.neurovision.rehab.clinical.application.ports.out.persistence.*;
import com.neurovision.rehab.clinical.application.ports.out.authorization.TelemetryAuthorizationSnapshotRepository;
import com.neurovision.rehab.clinical.application.internal.queryservices.AuthorizeTelemetryIngestionQueryService;
import com.neurovision.rehab.clinical.application.internal.queryservices.TelemetryIngestionDeniedException;
import com.neurovision.rehab.clinical.domain.model.aggregates.*;
import com.neurovision.rehab.clinical.domain.model.valueobjects.*;
import com.neurovision.rehab.clinical.infrastructure.persistence.jpa.transaction.TransactionalTherapySessionStarter;
import com.neurovision.rehab.clinical.infrastructure.persistence.jpa.transaction.TransactionalTherapySessionTransitioner;
import com.neurovision.rehab.clinical.support.TherapeuticConfigurationTestData;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;

@QuarkusTest
class TherapySessionConfigurationBindingPersistenceTest {
    private static final TherapyPlanId PLAN_ID =
            new TherapyPlanId(UUID.fromString("52000000-0000-0000-0000-000000000001"));
    private static final ClinicalPatientId PATIENT_ID =
            new ClinicalPatientId(UUID.fromString("53000000-0000-0000-0000-000000000001"));
    private static final CognitoSubject DOCTOR = new CognitoSubject("doctor-subject");
    private static final UUID CONFIGURATION_V1 =
            UUID.fromString("54000000-0000-0000-0000-000000000001");
    private static final UUID CONFIGURATION_V2 =
            UUID.fromString("54000000-0000-0000-0000-000000000002");

    @Inject EntityManager entityManager;
    @Inject DataSource dataSource;
    @Inject ClinicalPatientRepository patients;
    @Inject DoctorPatientAssignmentRepository assignments;
    @Inject TherapyPlanRepository plans;
    @Inject TherapeuticExerciseConfigurationRepository configurations;
    @Inject TherapySessionRepository sessions;
    @Inject TransactionalTherapySessionStarter starter;
    @Inject TransactionalTherapySessionTransitioner transitioner;
    @Inject TelemetryAuthorizationSnapshotRepository authorizationSnapshots;
    @Inject AuthorizeTelemetryIngestionQueryService telemetryAuthorization;

    @BeforeEach
    void setUp() {
        cleanDatabase();
        Instant createdAt = Instant.now().minusSeconds(7200);
        patients.save(ClinicalPatient.registerInvited(PATIENT_ID,
                new CognitoSubject("phase-2c-patient"), createdAt));
        assignments.save(DoctorPatientAssignment.assign(new DoctorPatientAssignmentId(UUID.randomUUID()),
                PATIENT_ID, DOCTOR, createdAt));
        plans.save(plan());
        QuarkusTransaction.requiringNew().run(() -> configurations.append(
                TherapeuticConfigurationTestData.visualSearchConfiguration(
                        PLAN_ID, CONFIGURATION_V1, 1, createdAt.plusSeconds(60))));
    }

    @AfterEach
    void tearDown() {
        cleanDatabase();
    }

    @Test
    void oneSnapshotRejectsDeterministicSplitReadInterleaving() {
        var sessionId = starter.handle(command("split-read-regression")).session().id();
        var subject = new CognitoSubject("phase-2c-patient");
        assertEquals(TherapySessionStatus.ACTIVE,
                authorizationSnapshots.findBySubjectAndSession(subject, sessionId).orElseThrow().status());
        assertTrue(authorizationSnapshots.findBySubjectAndSession(
                new CognitoSubject("other-patient"), sessionId).isEmpty());
        assertTrue(authorizationSnapshots.findBySubjectAndSession(
                subject, new TherapySessionId(UUID.randomUUID())).isEmpty());
        var assignment = assignments.findActiveByClinicalPatientIdAndDoctorSubject(PATIENT_ID, DOCTOR)
                .orElseThrow();
        assignment.end(Instant.now());
        assignments.save(assignment);

        // The old implementation could retain this first read while later statements changed.
        var staleSession = sessions.findById(sessionId).orElseThrow();
        assertEquals(TherapySessionStatus.ACTIVE, staleSession.status());
        assertTrue(authorizationSnapshots.findBySubjectAndSession(subject, sessionId).isEmpty());

        transitioner.handle(new TransitionTherapySessionCommand(
                sessionId, TransitionTherapySessionCommand.Action.CANCEL,
                "interleaving-regression", Instant.now()));
        assignments.save(DoctorPatientAssignment.assign(new DoctorPatientAssignmentId(UUID.randomUUID()),
                PATIENT_ID, DOCTOR, Instant.now()));

        // Old split reads now see ACTIVE from before the transition and an ACTIVE assignment
        // from after it, although those predicates never overlapped in committed state.
        boolean legacySplitReadWouldPermit = staleSession.status() == TherapySessionStatus.ACTIVE
                && assignments.findActiveByClinicalPatientIdAndDoctorSubject(PATIENT_ID, DOCTOR).isPresent();
        assertTrue(legacySplitReadWouldPermit);
        var atomicFacts = authorizationSnapshots.findBySubjectAndSession(subject, sessionId).orElseThrow();
        assertEquals(TherapySessionStatus.CANCELLED, atomicFacts.status());
        assertThrows(TelemetryIngestionDeniedException.class,
                () -> telemetryAuthorization.authorize("phase-2c-patient", sessionId));
    }

    @Test
    void readsPersistedSessionByIdWithoutAWriteTransaction() {
        var created = starter.handle(command("telemetry-read"));

        var found = sessions.findById(created.session().id()).orElseThrow();
        assertEquals(created.session().id(), found.id());
        assertEquals(PATIENT_ID, found.clinicalPatientId());
        assertEquals(TherapySessionStatus.ACTIVE, found.status());
        assertTrue(sessions.findById(new TherapySessionId(UUID.randomUUID())).isEmpty());
    }

    @Test
    void persistsExactCanonicalSnapshotAndKeepsItAfterLaterConfigurationAppend() throws Exception {
        var created = starter.handle(command("binding-1"));

        Object[] row = QuarkusTransaction.requiringNew().call(() -> (Object[]) entityManager
                .createNativeQuery("""
                        SELECT therapeutic_configuration_id, configuration_version,
                               therapeutic_exercise, parameter_schema_version,
                               configuration_parameters::text
                        FROM therapy_sessions
                        WHERE id = :id
                        """)
                .setParameter("id", created.session().id().value())
                .getSingleResult());
        assertEquals(CONFIGURATION_V1, row[0]);
        assertEquals(1L, ((Number) row[1]).longValue());
        assertEquals("VISUAL_SEARCH", row[2]);
        assertEquals(1, ((Number) row[3]).intValue());
        var mapper = new ObjectMapper();
        assertEquals(mapper.readTree("""
                {"mode":"TARGET_SEARCH_WITH_DISTRACTORS","affectedHemifield":"LEFT",\
                "targetCount":15,"distractorCount":8,"minimumFixationMs":700,\
                "maximumSearchTimeMs":10000,"affectedHemifieldBias":0.7,\
                "targetSizePx":70,"difficultyLevel":2}"""), mapper.readTree(row[4].toString()));

        QuarkusTransaction.requiringNew().run(() -> configurations.append(
                TherapeuticConfigurationTestData.visualSearchConfiguration(
                        PLAN_ID, CONFIGURATION_V2, 2, Instant.now())));
        var replay = starter.handle(command("binding-1"));

        assertEquals(StartTherapySessionResult.Outcome.IDEMPOTENT_REPLAY, replay.outcome());
        assertEquals(new TherapeuticConfigurationId(CONFIGURATION_V1),
                replay.session().therapeuticConfigurationId());
        assertEquals(1, replay.session().configurationVersion());
    }

    @Test
    void databaseEnforcesBindingAndConfigurationAppendOnlyRulesAndFlywayV6() {
        assertEquals("6", QuarkusTransaction.requiringNew().call(() -> entityManager
                .createNativeQuery("SELECT max(version) FROM flyway_schema_history WHERE success")
                .getSingleResult().toString()));

        assertThrows(RuntimeException.class, () -> QuarkusTransaction.requiringNew().run(() ->
                entityManager.createNativeQuery("""
                                UPDATE therapeutic_exercise_configurations
                                SET version = 2
                                WHERE id = :id
                                """)
                        .setParameter("id", CONFIGURATION_V1).executeUpdate()));
        assertThrows(RuntimeException.class, () -> QuarkusTransaction.requiringNew().run(() ->
                entityManager.createNativeQuery(
                                "DELETE FROM therapeutic_exercise_configurations WHERE id = :id")
                        .setParameter("id", CONFIGURATION_V1).executeUpdate()));
    }

    @Test
    void databaseRejectsSnapshotInjectionAndDirectSessionProvenanceMutation() {
        var created = starter.handle(command("provenance-guard"));

        assertThrows(RuntimeException.class, () -> QuarkusTransaction.requiringNew().run(() ->
                entityManager.createNativeQuery("""
                                UPDATE therapy_sessions
                                SET configuration_parameters = configuration_parameters
                                    || '{"difficultyLevel":5}'::jsonb
                                WHERE id = :id
                                """)
                        .setParameter("id", created.session().id().value()).executeUpdate()));
        assertThrows(RuntimeException.class, () -> QuarkusTransaction.requiringNew().run(() ->
                entityManager.createNativeQuery("""
                                UPDATE therapy_sessions
                                SET request_fingerprint = repeat('0', 64)
                                WHERE id = :id
                                """)
                        .setParameter("id", created.session().id().value()).executeUpdate()));
        assertThrows(RuntimeException.class, () -> QuarkusTransaction.requiringNew().run(() ->
                entityManager.createNativeQuery("""
                                UPDATE therapy_sessions
                                SET id = :replacementId
                                WHERE id = :id
                                """)
                        .setParameter("replacementId", UUID.randomUUID())
                        .setParameter("id", created.session().id().value()).executeUpdate()));
        assertThrows(RuntimeException.class, () -> QuarkusTransaction.requiringNew().run(() ->
                entityManager.createNativeQuery("""
                                INSERT INTO therapy_sessions (
                                    id, therapy_plan_id, clinical_patient_id, doctor_cognito_sub,
                                    exercise_code, configuration_version, quota_date,
                                    idempotency_key, request_fingerprint,
                                    minimum_valid_duration_minutes, maximum_session_duration_minutes,
                                    minimum_valid_sample_count, status, active_duration_seconds,
                                    valid_sample_count, consumes_daily_quota, started_at,
                                    lease_expires_at, ended_at, termination_reason, version,
                                    therapeutic_configuration_id, therapeutic_exercise,
                                    parameter_schema_version, configuration_parameters
                                )
                                SELECT :newId, therapy_plan_id, clinical_patient_id, doctor_cognito_sub,
                                    exercise_code, configuration_version, quota_date,
                                    'snapshot-injection', repeat('1', 64),
                                    minimum_valid_duration_minutes, maximum_session_duration_minutes,
                                    minimum_valid_sample_count, 'COMPLETED', active_duration_seconds,
                                    valid_sample_count, TRUE, started_at,
                                    NULL, started_at, NULL, 0,
                                    therapeutic_configuration_id, therapeutic_exercise,
                                    parameter_schema_version,
                                    configuration_parameters || '{"difficultyLevel":5}'::jsonb
                                FROM therapy_sessions WHERE id = :sourceId
                                """)
                        .setParameter("newId", UUID.randomUUID())
                        .setParameter("sourceId", created.session().id().value()).executeUpdate()));
    }

    @Test
    void historicalV4SessionRetrySurvivesV5AndV6Upgrade() throws Exception {
        String schema = "phase2c_upgrade_" + UUID.randomUUID().toString().replace("-", "");
        try {
            Flyway.configure().dataSource(dataSource).schemas(schema).defaultSchema(schema)
                    .locations("classpath:db/migration").target("4").load().migrate();
            UUID sessionId = UUID.randomUUID();
            UUID patientId = UUID.randomUUID();
            UUID planId = UUID.randomUUID();
            UUID configurationId = UUID.randomUUID();
            Instant createdAt = Instant.parse("2026-01-15T14:00:00Z");
            String idempotencyKey = "historical-v4-retry";
            String legacyFingerprint = legacyFingerprint(patientId, planId, "visual-search", 7);

            try (var connection = dataSource.getConnection(); var statement = connection.createStatement()) {
                connection.setAutoCommit(false);
                statement.execute("SET LOCAL search_path TO " + schema);
                statement.execute("""
                        INSERT INTO clinical_patients
                            (id, patient_cognito_sub, status, stroke_diagnosis, created_at, updated_at)
                        VALUES ('%s', 'historical-patient', 'ACTIVE', 'post-stroke hemianopia', '%s', '%s');
                        INSERT INTO therapy_plans
                            (id, clinical_patient_id, doctor_cognito_sub, status, configuration_version,
                             daily_session_limit, daily_target_duration_minutes,
                             daily_hard_safety_limit_minutes, minimum_valid_duration_minutes,
                             maximum_session_duration_minutes, minimum_valid_sample_count)
                        VALUES ('%s', '%s', 'historical-doctor', 'ACTIVE', 7, 3, 30, 60, 5, 15, 50);
                        INSERT INTO therapy_plan_exercise_prescriptions
                            (therapy_plan_id, exercise_code, daily_quantity)
                        VALUES ('%s', 'visual-search', 3);
                        INSERT INTO therapeutic_exercise_configurations
                            (id, therapy_plan_id, exercise_code, version, parameter_schema_version,
                             effective_from, created_by_doctor_subject, created_at, status, parameters)
                        VALUES ('%s', '%s', 'VISUAL_SEARCH', 1, 1, '%s', 'historical-doctor',
                                '%s', 'ACTIVE', '{"mode":"TARGET_SEARCH_WITH_DISTRACTORS",\
                                "affectedHemifield":"LEFT","targetCount":15,"distractorCount":8,\
                                "minimumFixationMs":700,"maximumSearchTimeMs":10000,\
                                "affectedHemifieldBias":0.7,"targetSizePx":70,"difficultyLevel":2}'::jsonb);
                        INSERT INTO therapy_sessions
                            (id, therapy_plan_id, clinical_patient_id, doctor_cognito_sub, exercise_code,
                             configuration_version, quota_date, idempotency_key, request_fingerprint,
                             minimum_valid_duration_minutes, maximum_session_duration_minutes,
                             minimum_valid_sample_count, status, active_duration_seconds,
                             valid_sample_count, consumes_daily_quota, started_at, lease_expires_at)
                        VALUES ('%s', '%s', '%s', 'historical-doctor', 'visual-search', 7,
                                '2026-01-15', '%s', '%s', 5, 15, 50, 'ACTIVE', 0, 0, TRUE,
                                '%s', '%s');
                        """.formatted(
                        patientId, createdAt, createdAt, planId, patientId, planId,
                        configurationId, planId, createdAt, createdAt,
                        sessionId, planId, patientId, idempotencyKey, legacyFingerprint,
                        createdAt.plusSeconds(120), createdAt.plusSeconds(1020)));
                connection.commit();
            }

            Flyway.configure().dataSource(dataSource).schemas(schema).defaultSchema(schema)
                    .locations("classpath:db/migration").target("5").load().migrate();
            Flyway.configure().dataSource(dataSource).schemas(schema).defaultSchema(schema)
                    .locations("classpath:db/migration").target("6").load().migrate();
            Flyway.configure().dataSource(dataSource).schemas(schema).defaultSchema(schema)
                    .locations("classpath:db/migration").load().validate();

            var retryCommand = new StartTherapySessionCommand(
                    new ClinicalPatientId(patientId), new TherapyPlanId(planId),
                    new ExerciseCode("visual-search"), new IdempotencyKey(idempotencyKey));
            String expectedFingerprint = StartTherapySessionRequestFingerprint.compute(retryCommand);
            var replay = QuarkusTransaction.requiringNew().call(() -> {
                entityManager.createNativeQuery("SET LOCAL search_path TO " + schema).executeUpdate();
                return starter.handle(retryCommand);
            });
            assertEquals(StartTherapySessionResult.Outcome.IDEMPOTENT_REPLAY, replay.outcome());
            assertEquals(sessionId, replay.session().id().value());
            assertEquals(new TherapeuticConfigurationId(configurationId),
                    replay.session().therapeuticConfigurationId());
            assertEquals(1L, replay.session().configurationVersion());
            assertEquals(TherapeuticConfigurationTestData.visualSearchConfiguration(
                            new TherapyPlanId(planId), configurationId, 1, createdAt)
                            .parameters(), replay.session().configurationParameters());
            try (var connection = dataSource.getConnection(); var statement = connection.createStatement()) {
                connection.setAutoCommit(false);
                statement.execute("SET LOCAL search_path TO " + schema);
                try (var result = statement.executeQuery("""
                        SELECT id, request_fingerprint, therapeutic_configuration_id,
                               configuration_version, configuration_parameters
                        FROM therapy_sessions
                        WHERE clinical_patient_id = '%s' AND idempotency_key = '%s'
                        """.formatted(patientId, idempotencyKey))) {
                    assertTrue(result.next());
                    assertEquals(sessionId, result.getObject("id", UUID.class));
                    assertEquals(expectedFingerprint, result.getString("request_fingerprint"));
                    assertNotEquals(legacyFingerprint, result.getString("request_fingerprint"));
                    assertEquals(configurationId,
                            result.getObject("therapeutic_configuration_id", UUID.class));
                    assertEquals(1L, result.getLong("configuration_version"));
                    assertEquals(new ObjectMapper().readTree("""
                                    {"mode":"TARGET_SEARCH_WITH_DISTRACTORS",\
                                    "affectedHemifield":"LEFT","targetCount":15,"distractorCount":8,\
                                    "minimumFixationMs":700,"maximumSearchTimeMs":10000,\
                                    "affectedHemifieldBias":0.7,"targetSizePx":70,"difficultyLevel":2}
                                    """),
                            new ObjectMapper().readTree(result.getString("configuration_parameters")));
                    assertFalse(result.next());
                }
                connection.commit();
            }
        } finally {
            try (var connection = dataSource.getConnection(); var statement = connection.createStatement()) {
                statement.execute("DROP SCHEMA IF EXISTS " + schema + " CASCADE");
            }
        }
    }

    @Test
    void concurrentAdmissionsSerializeAndCreateOnlyOneActiveFrozenSession() throws Exception {
        var executor = Executors.newFixedThreadPool(2);
        var ready = new CountDownLatch(2);
        var start = new CountDownLatch(1);
        try {
            List<Future<Object>> futures = List.of(
                    executor.submit(() -> attempt(ready, start, "concurrent-a")),
                    executor.submit(() -> attempt(ready, start, "concurrent-b")));
            assertTrue(ready.await(10, TimeUnit.SECONDS));
            start.countDown();

            var results = futures.stream().map(future -> {
                try {
                    return future.get(30, TimeUnit.SECONDS);
                } catch (Exception exception) {
                    throw new AssertionError(exception);
                }
            }).toList();
            assertEquals(1, results.stream().filter(StartTherapySessionResult.class::isInstance).count());
            assertEquals(1, results.stream().filter(TherapySessionStartConflictException.class::isInstance).count());
            assertEquals(1L, countRows("therapy_sessions"));
        } finally {
            executor.shutdownNow();
        }
    }

    private Object attempt(CountDownLatch ready, CountDownLatch start, String key) {
        ready.countDown();
        try {
            assertTrue(start.await(10, TimeUnit.SECONDS));
            return starter.handle(command(key));
        } catch (TherapySessionStartConflictException expected) {
            return expected;
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(interrupted);
        }
    }

    private StartTherapySessionCommand command(String key) {
        return new StartTherapySessionCommand(
                PATIENT_ID, PLAN_ID, new ExerciseCode("visual-search"), new IdempotencyKey(key));
    }

    private String legacyFingerprint(UUID patientId, UUID planId, String exerciseCode,
            long requestedConfigurationVersion) throws Exception {
        String canonical = patientId + "\n" + planId + "\n" + exerciseCode + "\n"
                + requestedConfigurationVersion;
        return HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
                .digest(canonical.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
    }

    private TherapyPlan plan() {
        return TherapyPlan.rehydrate(PLAN_ID, PATIENT_ID, DOCTOR, TherapyPlanStatus.ACTIVE,
                1, 3, 30, 60, 5, 15, 50,
                Map.of(new ExerciseCode("visual-search"), 3));
    }

    private long countRows(String table) {
        return QuarkusTransaction.requiringNew().call(() -> ((Number) entityManager
                .createNativeQuery("SELECT count(*) FROM " + table).getSingleResult()).longValue());
    }

    private void cleanDatabase() {
        QuarkusTransaction.requiringNew().run(() -> {
            entityManager.createNativeQuery(
                            "TRUNCATE TABLE therapy_sessions, therapeutic_exercise_configurations, "
                                    + "therapy_plan_revision_exercises, therapy_plan_revisions")
                    .executeUpdate();
            entityManager.createNativeQuery("DELETE FROM therapy_plan_exercise_prescriptions").executeUpdate();
            entityManager.createNativeQuery("DELETE FROM therapy_plans").executeUpdate();
            entityManager.createNativeQuery("DELETE FROM inbox_events").executeUpdate();
            entityManager.createNativeQuery("DELETE FROM doctor_patient_assignments").executeUpdate();
            entityManager.createNativeQuery("DELETE FROM clinical_patients").executeUpdate();
        });
    }
}
