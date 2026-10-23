package com.neurovision.rehab.clinical.infrastructure.persistence.jpa;

import static org.junit.jupiter.api.Assertions.*;
import static com.neurovision.rehab.clinical.support.TherapeuticConfigurationTestData.visualSearchSnapshot;

import com.neurovision.rehab.clinical.application.internal.commandservices.PatientSessionProgressService;
import com.neurovision.rehab.clinical.application.internal.commandservices.SessionProgressConflictException;
import com.neurovision.rehab.clinical.application.ports.in.PatientSessionProgressUseCase;
import com.neurovision.rehab.clinical.application.ports.in.PatientSessionProgressUseCase.*;
import com.neurovision.rehab.clinical.application.ports.out.persistence.*;
import com.neurovision.rehab.clinical.domain.model.aggregates.*;
import com.neurovision.rehab.clinical.domain.model.valueobjects.*;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import java.sql.SQLException;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.*;

/**
 * Explicit opt-in only. Uses a fresh dedicated database and leaves synthetic evidence in it.
 * The separately authorized launcher owns container cleanup; this test never drops or cleans data.
 * Fixed clocks replace only time: repositories, JPA serialization, locks and transactions are real.
 * Select -Pclinical-session-progress-postgresql and set clinical.progress-test.jdbc-url to
 * jdbc:postgresql://127.0.0.1:PORT/nv_clinical_progress_test; there is no connection fallback.
 */
@QuarkusTest
@TestProfile(PatientSessionProgressPersistenceTest.IsolatedPostgres.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class PatientSessionProgressPersistenceTest {
    private static final Instant START = Instant.parse("2026-01-01T00:00:00Z");
    private static final List<String> LEGACY_TABLES = List.of("clinical_patients", "doctor_patient_assignments",
            "inbox_events", "therapy_plans", "therapy_plan_exercise_prescriptions", "therapy_sessions",
            "therapeutic_exercise_configurations", "therapy_plan_revisions", "therapy_plan_revision_exercises");
    private static final List<String> PROGRESS_TABLES = List.of("therapy_session_progress", "therapy_session_activity_receipts");
    @Inject DataSource dataSource;
    @Inject EntityManager entities;
    @Inject ClinicalPatientRepository patients;
    @Inject DoctorPatientAssignmentRepository assignments;
    @Inject TherapyPlanRepository plans;
    @Inject TherapeuticExerciseConfigurationRepository configurations;
    @Inject TherapySessionRepository sessions;
    @Inject SessionActivityRepository activity;
    @Inject PatientSessionProgressUseCase transactional;

    @BeforeAll
    void attestDedicatedFreshDatabaseBeforeAnyMigrationOrFixture() {
        try (var connection = dataSource.getConnection();
                var identity = connection.prepareStatement("SELECT current_database(), current_user");
                var tables = connection.prepareStatement("SELECT count(*) FROM pg_tables WHERE schemaname = 'public'")) {
            identity.setQueryTimeout(5); tables.setQueryTimeout(5);
            try (var rows = identity.executeQuery()) {
                assertTrue(rows.next(), "Dedicated PostgreSQL identity unavailable");
                assertTrue(IsolatedPostgres.DATABASE.equals(rows.getString(1))
                        && IsolatedPostgres.USER.equals(rows.getString(2)), "Refusing non-dedicated database or role");
            }
            try (var rows = tables.executeQuery()) {
                assertTrue(rows.next() && rows.getLong(1) == 0, "Refusing a non-fresh test database");
            }
        } catch (SQLException unavailable) {
            throw new AssertionError("Dedicated PostgreSQL attestation unavailable");
        }
    }

    @Test @Timeout(value = 180, unit = TimeUnit.SECONDS)
    void migratesLegacyDataAndVerifiesProgressAtomicityAndConcurrentLocalClosure() throws Exception {
        // Separate Flyway instances: target 8 must not silently become latest.
        migration("8").migrate();
        var legacy = fixture(null);
        var before = snapshot(LEGACY_TABLES);
        var oldHistory = history();
        assertEquals(8, oldHistory.size(), "Unexpected starting migration history");
        migration("9").migrate();
        assertTrue(before.equals(snapshot(LEGACY_TABLES)), "V9 changed legacy rows");
        var newHistory = history();
        assertEquals(9, newHistory.size());
        assertTrue(oldHistory.equals(newHistory.subList(0, 8)), "V9 rewrote prior migration history");
        assertTrue(newHistory.get(8).startsWith("9|") && newHistory.get(8).endsWith("|true"), "V9 is not successful");
        migration("9").migrate();
        assertTrue(newHistory.equals(history()), "Repeated migrate changed history");
        for (var table : PROGRESS_TABLES) assertEquals(0, count(table));
        assertRejectedWithoutChanges(() -> call(legacy, 0, service -> service.progress(legacy.subject(), legacy.session())),
                SessionProgressConflictException.class);
        assertSqlRejected("INSERT INTO therapy_session_progress (therapy_session_id, state) VALUES (?, '{}'::jsonb)",
                "P0001", legacy.session().value());
        assertSqlRejected("INSERT INTO therapy_session_activity_receipts (id, therapy_session_id, event_sequence, receipt) VALUES (?, ?, 0, '{}'::jsonb)",
                "P0001", UUID.randomUUID().toString(), legacy.session().value());
        assertTrue(before.equals(snapshot(LEGACY_TABLES)), "Legacy rejection changed existing data");
        assertEquals(2, number("SELECT count(*) FROM pg_constraint WHERE contype = 'f' AND conrelid IN "
                + "(CAST('therapy_session_progress' AS regclass), CAST('therapy_session_activity_receipts' AS regclass))"));

        verifiesTimePauseGapFrozenGoalAndCompletion();
        verifiesRepetitionAndAbandonment();
        verifiesRollbackAfterFlushedActivityAndCompletion();
        verifiesConcurrentDuplicateAndConflictingSequence();
        verifiesCompetingClosuresCannotOverwriteTheWinner();
        var remaining = snapshot(LEGACY_TABLES);
        for (var table : LEGACY_TABLES) assertTrue(remaining.get(table).containsAll(before.get(table)), "Original legacy rows were changed");
    }

    private void verifiesTimePauseGapFrozenGoalAndCompletion() {
        var fixture = fixture(new CompletionTarget(CompletionTarget.Criterion.ACTIVE_TIME, 30));
        // Appending a later source version must never replace the session's frozen goal.
        tx(() -> {
            configurations.append(configuration(fixture.plan(), 3, new CompletionTarget(CompletionTarget.Criterion.ACTIVE_TIME, 90)));
            return null;
        });
        assertEquals(30, transactional.progress(fixture.subject(), fixture.session()).target());
        var first = event(0, SessionActivityEvent.Type.ACTIVITY_STARTED);
        var firstAck = call(fixture, 0, service -> service.activity(fixture.subject(), fixture.session(), first));
        apply(fixture, 5, event(1, SessionActivityEvent.Type.HEARTBEAT));
        apply(fixture, 10, event(2, SessionActivityEvent.Type.PAUSED));
        apply(fixture, 100, event(3, SessionActivityEvent.Type.RESUMED));
        var interval = apply(fixture, 115, event(4, SessionActivityEvent.Type.HEARTBEAT));
        assertEquals(25000, interval.activeMilliseconds());
        var gap = apply(fixture, 131, event(5, SessionActivityEvent.Type.HEARTBEAT));
        assertEquals(25000, gap.activeMilliseconds()); assertEquals(SessionActivityProgress.Mode.RESUME_REQUIRED, gap.activityState());
        assertRejectedWithoutChanges(() -> call(fixture, 132, service -> service.complete(fixture.subject(), fixture.session(),
                new IdempotencyKey("time-finish"), Action.FINISHED)), SessionProgressConflictException.class);
        apply(fixture, 132, event(6, SessionActivityEvent.Type.RESUMED));
        var reached = apply(fixture, 137, event(7, SessionActivityEvent.Type.HEARTBEAT));
        assertTrue(reached.targetReached()); assertEquals("ACTIVE", reached.status());
        assertEquals(30000, reached.activeMilliseconds());
        var result = call(fixture, 137, service -> service.complete(fixture.subject(), fixture.session(), new IdempotencyKey("time-finish"), Action.FINISHED));
        assertEquals("COMPLETED", result.status()); assertEquals(8, receipts(fixture));
        var persisted = tx(() -> sessions.findById(fixture.session()).orElseThrow());
        assertEquals(30, persisted.activeDurationSeconds()); assertEquals(0, persisted.validSampleCount()); assertTrue(persisted.consumesDailyQuota());
        var closed = snapshot(allTables());
        assertTrue(firstAck.equals(call(fixture, 1000, service -> service.activity(fixture.subject(), fixture.session(), first))), "Activity replay changed acknowledgement");
        assertTrue(result.equals(call(fixture, 1000, service -> service.complete(fixture.subject(), fixture.session(), new IdempotencyKey("time-finish"), Action.FINISHED))), "Completion replay changed acknowledgement");
        assertTrue(closed.equals(snapshot(allTables())), "Replay changed persisted state");
        assertRejectedWithoutChanges(() -> apply(fixture, 138, event(8, SessionActivityEvent.Type.HEARTBEAT)), SessionProgressConflictException.class);
        assertRejectedWithoutChanges(() -> call(fixture, 138, service -> service.complete(fixture.subject(), fixture.session(), new IdempotencyKey("different"), Action.ABANDONED)), SessionProgressConflictException.class);
        assertRejectedWithoutChanges(() -> call(fixture, 138, service -> service.progress(new CognitoSubject("synthetic-foreign"), fixture.session())), SecurityException.class);
        assertSqlRejected("INSERT INTO therapy_session_activity_receipts (id, therapy_session_id, event_sequence, receipt) "
                + "SELECT ?, therapy_session_id, event_sequence, receipt FROM therapy_session_activity_receipts WHERE therapy_session_id = ? AND event_sequence = 0",
                "23505", UUID.randomUUID().toString(), fixture.session().value());
        assertSqlRejected("INSERT INTO therapy_session_activity_receipts (id, therapy_session_id, event_sequence, receipt) VALUES (?, ?, -1, '{}'::jsonb)",
                "23514", UUID.randomUUID().toString(), fixture.session().value());
        assertSqlRejected("UPDATE therapeutic_exercise_configurations SET version = 99 WHERE id = ?", "P0001", fixture.source().id().value());
        assertSqlRejected("UPDATE therapy_sessions SET configuration_version = 99 WHERE id = ?", "P0001", fixture.session().value());
        assertTrue(closed.equals(snapshot(allTables())), "Constraint rejection changed persisted state");
    }

    private void verifiesRepetitionAndAbandonment() {
        var repetition = fixture(new CompletionTarget(CompletionTarget.Criterion.REPETITIONS, 1));
        apply(repetition, 0, event(0, SessionActivityEvent.Type.ACTIVITY_STARTED));
        var interrupted = UUID.randomUUID();
        apply(repetition, 1, repetition(1, SessionActivityEvent.Type.REPETITION_STARTED, interrupted));
        apply(repetition, 2, event(2, SessionActivityEvent.Type.PAUSED));
        apply(repetition, 3, event(3, SessionActivityEvent.Type.RESUMED));
        assertRejectedWithoutChanges(() -> apply(repetition, 4, repetition(4, SessionActivityEvent.Type.REPETITION_FINISHED, interrupted)), SessionProgressConflictException.class);
        assertRejectedWithoutChanges(() -> apply(repetition, 4, repetition(4, SessionActivityEvent.Type.REPETITION_STARTED, interrupted)), SessionProgressConflictException.class);
        var fresh = UUID.randomUUID();
        apply(repetition, 4, repetition(4, SessionActivityEvent.Type.REPETITION_STARTED, fresh));
        var reached = apply(repetition, 5, repetition(5, SessionActivityEvent.Type.REPETITION_FINISHED, fresh));
        assertEquals(1, reached.completedRepetitions()); assertTrue(reached.targetReached());
        assertEquals("COMPLETED", call(repetition, 5, service -> service.complete(repetition.subject(), repetition.session(), new IdempotencyKey("repetitions-finish"), Action.FINISHED)).status());
        assertEquals(6, receipts(repetition));
        var abandoned = fixture(new CompletionTarget(CompletionTarget.Criterion.ACTIVE_TIME, 100));
        apply(abandoned, 0, event(0, SessionActivityEvent.Type.ACTIVITY_STARTED));
        apply(abandoned, 5, event(1, SessionActivityEvent.Type.HEARTBEAT));
        var result = call(abandoned, 6, service -> service.complete(abandoned.subject(), abandoned.session(), new IdempotencyKey("abandon"), Action.ABANDONED));
        assertEquals("ABANDONED", result.status()); assertEquals(5000, result.activeMilliseconds()); assertFalse(result.targetReached());
        var stored = tx(() -> sessions.findById(abandoned.session()).orElseThrow());
        assertEquals(5, stored.activeDurationSeconds()); assertEquals(0, stored.validSampleCount()); assertFalse(stored.consumesDailyQuota());
        var before = snapshot(allTables());
        assertTrue(result.equals(call(abandoned, 1000, service -> service.complete(abandoned.subject(), abandoned.session(), new IdempotencyKey("abandon"), Action.ABANDONED))), "Abandon replay changed acknowledgement");
        assertTrue(before.equals(snapshot(allTables())), "Abandon replay changed persisted state");
    }

    private void verifiesRollbackAfterFlushedActivityAndCompletion() {
        var fixture = fixture(new CompletionTarget(CompletionTarget.Criterion.ACTIVE_TIME, 10));
        var first = event(0, SessionActivityEvent.Type.ACTIVITY_STARTED);
        rollbackPreservesAllTables(() -> {
            service(0).activity(fixture.subject(), fixture.session(), first);
        });
        apply(fixture, 0, first); apply(fixture, 10, event(1, SessionActivityEvent.Type.HEARTBEAT));
        rollbackPreservesAllTables(() -> {
            service(10).complete(fixture.subject(), fixture.session(), new IdempotencyKey("rollback-finish"), Action.FINISHED);
        });
        assertEquals("ACTIVE", transactional.progress(fixture.subject(), fixture.session()).status());
        assertEquals("COMPLETED", call(fixture, 10, service -> service.complete(fixture.subject(), fixture.session(), new IdempotencyKey("rollback-finish"), Action.FINISHED)).status());
    }

    private void verifiesConcurrentDuplicateAndConflictingSequence() throws Exception {
        var duplicate = fixture(new CompletionTarget(CompletionTarget.Criterion.ACTIVE_TIME, 10));
        var first = event(0, SessionActivityEvent.Type.ACTIVITY_STARTED);
        var results = race(() -> service(0).activity(duplicate.subject(), duplicate.session(), first),
                () -> service(0).activity(duplicate.subject(), duplicate.session(), first));
        assertTrue(results.stream().allMatch(value -> !value.conflict()), "Duplicate race did not return two acknowledgements");
        assertTrue(results.get(0).view().equals(results.get(1).view()), "Duplicate race returned different acknowledgements");
        assertEquals(1, receipts(duplicate)); assertEquals(1, transactional.progress(duplicate.subject(), duplicate.session()).nextSequence());
        var conflict = fixture(new CompletionTarget(CompletionTarget.Criterion.ACTIVE_TIME, 10));
        var a = event(0, SessionActivityEvent.Type.ACTIVITY_STARTED); var b = event(0, SessionActivityEvent.Type.ACTIVITY_STARTED);
        var competing = race(() -> service(0).activity(conflict.subject(), conflict.session(), a),
                () -> service(0).activity(conflict.subject(), conflict.session(), b));
        assertEquals(1, competing.stream().filter(Outcome::conflict).count());
        assertEquals(1, receipts(conflict)); assertEquals(1, transactional.progress(conflict.subject(), conflict.session()).nextSequence());
    }

    private void verifiesCompetingClosuresCannotOverwriteTheWinner() throws Exception {
        var fixture = fixture(new CompletionTarget(CompletionTarget.Criterion.ACTIVE_TIME, 10));
        apply(fixture, 0, event(0, SessionActivityEvent.Type.ACTIVITY_STARTED));
        apply(fixture, 10, event(1, SessionActivityEvent.Type.HEARTBEAT));
        long version = number("SELECT version FROM therapy_sessions WHERE id = ?1", fixture.session().value());
        var results = race(() -> service(10).complete(fixture.subject(), fixture.session(), new IdempotencyKey("race-finish"), Action.FINISHED),
                () -> service(10).complete(fixture.subject(), fixture.session(), new IdempotencyKey("race-abandon"), Action.ABANDONED));
        assertEquals(1, results.stream().filter(Outcome::conflict).count());
        var winner = results.stream().filter(value -> !value.conflict()).findFirst().orElseThrow().view();
        assertTrue(winner.equals(transactional.progress(fixture.subject(), fixture.session())), "Losing closure overwrote winner");
        assertEquals(version + 1, number("SELECT version FROM therapy_sessions WHERE id = ?1", fixture.session().value()));
        assertEquals(1, number("SELECT count(*) FROM therapy_session_progress WHERE therapy_session_id = ?1 AND completion IS NOT NULL", fixture.session().value()));
        assertEquals(2, receipts(fixture));
    }

    private Flyway migration(String target) {
        return Flyway.configure().dataSource(dataSource).locations("db/migration")
                .target(MigrationVersion.fromVersion(target)).cleanDisabled(true).load();
    }
    private Fixture fixture(CompletionTarget goal) {
        return tx(() -> {
            var subject = new CognitoSubject("synthetic-progress-" + UUID.randomUUID());
            var patient = ClinicalPatient.registerInvited(new ClinicalPatientId(UUID.randomUUID()), subject, START.minusSeconds(60));
            patients.save(patient);
            var doctor = new CognitoSubject("synthetic-progress-doctor");
            assignments.save(DoctorPatientAssignment.assign(new DoctorPatientAssignmentId(UUID.randomUUID()), patient.id(), doctor, START.minusSeconds(60)));
            var plan = TherapyPlan.rehydrate(new TherapyPlanId(UUID.randomUUID()), patient.id(), doctor, TherapyPlanStatus.ACTIVE,
                    1, 3, 30, null, 1, 15, 50, Map.of(new ExerciseCode("visual-search"), 1));
            plans.save(plan);
            var source = configuration(plan, goal == null ? 1 : 2, goal); configurations.append(source);
            var session = TherapySession.start(new TherapySessionId(UUID.randomUUID()), plan, source.exercise().code(),
                    LocalDate.of(2026, 1, 1), TherapeuticConfigurationSnapshot.from(source), new IdempotencyKey("fixture-start"),
                    "a".repeat(64), START, START.plusSeconds(120));
            sessions.save(session);
            return new Fixture(subject, plan, source, session.id());
        });
    }
    private TherapeuticExerciseConfiguration configuration(TherapyPlan plan, long version, CompletionTarget goal) {
        var legacy = visualSearchSnapshot().parameters();
        var parameters = goal == null ? legacy : new TherapeuticExerciseParameters.Prescribed(legacy, goal);
        return TherapeuticExerciseConfiguration.create(new TherapeuticConfigurationId(UUID.randomUUID()), plan.id(),
                TherapeuticExercise.VISUAL_SEARCH, version, parameters.schemaVersion(), START.minusSeconds(30), plan.doctorSubject(), START.minusSeconds(30), parameters);
    }
    private PatientSessionProgressService service(long seconds) {
        return new PatientSessionProgressService(patients, sessions, activity, Clock.fixed(START.plusSeconds(seconds), ZoneOffset.UTC));
    }
    private <T> T call(Fixture fixture, long seconds, Function<PatientSessionProgressService, T> operation) {
        return tx(() -> operation.apply(service(seconds)));
    }
    private View apply(Fixture fixture, long seconds, SessionActivityEvent event) {
        return call(fixture, seconds, service -> service.activity(fixture.subject(), fixture.session(), event));
    }
    private SessionActivityEvent event(long sequence, SessionActivityEvent.Type type) {
        return new SessionActivityEvent(1, UUID.randomUUID(), sequence, type, null, null);
    }
    private SessionActivityEvent repetition(long sequence, SessionActivityEvent.Type type, UUID attempt) {
        return new SessionActivityEvent(1, UUID.randomUUID(), sequence, type, attempt, 1);
    }
    private <T> T tx(Callable<T> operation) { return QuarkusTransaction.requiringNew().timeout(10).call(operation); }
    private long receipts(Fixture fixture) { return number("SELECT count(*) FROM therapy_session_activity_receipts WHERE therapy_session_id = ?1", fixture.session().value()); }
    private long count(String table) { return number("SELECT count(*) FROM " + table); }
    private long number(String sql, Object... parameters) {
        return tx(() -> {
            var query = entities.createNativeQuery(sql).setHint("jakarta.persistence.query.timeout", 5000);
            for (int i = 0; i < parameters.length; i++) query.setParameter(i + 1, parameters[i]);
            return ((Number) query.getSingleResult()).longValue();
        });
    }
    private List<String> allTables() {
        var result = new ArrayList<>(LEGACY_TABLES); result.addAll(PROGRESS_TABLES); return List.copyOf(result);
    }
    private Map<String, List<String>> snapshot(List<String> tables) {
        return tx(() -> {
            var result = new LinkedHashMap<String, List<String>>();
            for (var table : tables) {
                assertTrue(allTables().contains(table), "Unknown snapshot table");
                List<?> rows = entities.createNativeQuery("SELECT CAST(to_jsonb(t) AS text) FROM " + table + " t ORDER BY CAST(to_jsonb(t) AS text)")
                        .setHint("jakarta.persistence.query.timeout", 5000).getResultList();
                result.put(table, rows.stream().map(Object::toString).toList());
            }
            return result;
        });
    }
    private List<String> history() {
        return tx(() -> {
            List<?> rows = entities.createNativeQuery("SELECT version || '|' || script || '|' || CAST(checksum AS text) || '|' || CAST(success AS text) FROM flyway_schema_history ORDER BY installed_rank")
                    .setHint("jakarta.persistence.query.timeout", 5000).getResultList();
            return rows.stream().map(Object::toString).toList();
        });
    }
    private void assertRejectedWithoutChanges(Runnable operation, Class<? extends RuntimeException> type) {
        var before = snapshot(allTables());
        assertThrows(type, operation::run);
        assertTrue(before.equals(snapshot(allTables())), "Rejected command changed persisted state");
    }
    private void rollbackPreservesAllTables(Runnable operation) {
        var before = snapshot(allTables()); var flushed = new AtomicBoolean();
        var failure = assertThrows(RuntimeException.class, () -> tx(() -> {
            operation.run(); entities.flush(); flushed.set(true); throw new SyntheticRollback();
        }));
        boolean marker = false;
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) if (cause instanceof SyntheticRollback) marker = true;
        assertTrue(flushed.get() && marker, "Synthetic post-flush rollback was not reached");
        assertTrue(before.equals(snapshot(allTables())), "Rollback left partial session, progress or receipt writes");
    }
    private void assertSqlRejected(String sql, String state, Object... parameters) {
        boolean rejected = false;
        try (var connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try (var query = connection.prepareStatement(sql)) {
                query.setQueryTimeout(5);
                for (int i = 0; i < parameters.length; i++) query.setObject(i + 1, parameters[i]);
                try { query.executeUpdate(); }
                catch (SQLException expected) { assertEquals(state, expected.getSQLState(), "Unexpected constraint category"); rejected = true; }
            } finally { connection.rollback(); }
        } catch (SQLException unavailable) { throw new AssertionError("Isolated constraint check unavailable"); }
        assertTrue(rejected, "Expected isolated PostgreSQL constraint rejection");
    }
    private List<Outcome> race(Callable<View> first, Callable<View> second) throws Exception {
        var ready = new CountDownLatch(2); var release = new CountDownLatch(1);
        var pool = Executors.newFixedThreadPool(2); var futures = new ArrayList<Future<Outcome>>();
        try {
            for (var operation : List.of(first, second)) futures.add(pool.submit(() -> {
                var pid = new AtomicInteger();
                try {
                    return tx(() -> {
                        pid.set(((Number) entities.createNativeQuery("SELECT pg_backend_pid()").getSingleResult()).intValue());
                        ready.countDown();
                        if (!release.await(5, TimeUnit.SECONDS)) throw new AssertionError("Concurrent transaction barrier expired");
                        return new Outcome(false, operation.call(), pid.get());
                    });
                } catch (SessionProgressConflictException expected) {
                    return new Outcome(true, null, pid.get());
                }
            }));
            assertTrue(ready.await(5, TimeUnit.SECONDS), "Two independent transactions did not become ready"); release.countDown();
            var results = List.of(futures.get(0).get(15, TimeUnit.SECONDS), futures.get(1).get(15, TimeUnit.SECONDS));
            assertTrue(results.get(0).pid() != results.get(1).pid(), "Concurrent operations shared one database transaction");
            return results;
        } finally {
            release.countDown(); futures.forEach(value -> value.cancel(true)); pool.shutdownNow();
            assertTrue(pool.awaitTermination(5, TimeUnit.SECONDS), "Concurrent test workers did not terminate");
        }
    }
    private record Fixture(CognitoSubject subject, TherapyPlan plan, TherapeuticExerciseConfiguration source, TherapySessionId session) { }
    private record Outcome(boolean conflict, View view, int pid) { }
    private static final class SyntheticRollback extends RuntimeException { }

    /** Never derives a target from .env, Compose defaults or ambient credentials. */
    public static class IsolatedPostgres implements QuarkusTestProfile {
        static final String DATABASE = "nv_clinical_progress_test";
        static final String USER = "clinical_progress_test";
        @Override public Map<String, String> getConfigOverrides() {
            if (!Boolean.getBoolean("clinical.progress-test.enabled")) throw new IllegalStateException("Explicit progress PostgreSQL Maven profile required");
            String url = System.getProperty("clinical.progress-test.jdbc-url", "");
            if (!url.matches("jdbc:postgresql://127[.]0[.]0[.]1:[0-9]{1,5}/" + DATABASE)) throw new IllegalStateException("Explicit isolated progress PostgreSQL URL required");
            int port = Integer.parseInt(url.substring(url.lastIndexOf(':') + 1, url.lastIndexOf('/')));
            if (port < 1 || port > 65535) throw new IllegalStateException("Invalid isolated PostgreSQL port");
            return Map.ofEntries(
                    Map.entry("quarkus.datasource.jdbc.url", url + "?connectTimeout=5&socketTimeout=15&options=-c%20TimeZone%3DUTC%20-c%20lock_timeout%3D5000%20-c%20statement_timeout%3D10000"),
                    Map.entry("quarkus.datasource.username", USER),
                    Map.entry("quarkus.datasource.password", "synthetic-progress-test-only"),
                    Map.entry("quarkus.datasource.jdbc.max-size", "6"),
                    Map.entry("quarkus.datasource.jdbc.acquisition-timeout", "5S"),
                    Map.entry("quarkus.devservices.enabled", "false"),
                    Map.entry("quarkus.datasource.devservices.enabled", "false"),
                    Map.entry("quarkus.kafka.devservices.enabled", "false"),
                    Map.entry("mp.messaging.incoming.patient-account-invited.enabled", "false"),
                    Map.entry("quarkus.oidc.enabled", "false"),
                    Map.entry("quarkus.oidc.devservices.enabled", "false"),
                    Map.entry("quarkus.http.test-port", "0"),
                    Map.entry("quarkus.hibernate-orm.schema-management.strategy", "none"),
                    Map.entry("quarkus.hibernate-orm.log.sql", "false"),
                    Map.entry("quarkus.flyway.migrate-at-start", "false"),
                    Map.entry("quarkus.flyway.validate-at-start", "false"),
                    Map.entry("quarkus.flyway.baseline-at-start", "false"),
                    Map.entry("quarkus.flyway.repair-at-start", "false"),
                    Map.entry("quarkus.flyway.clean-at-start", "false"),
                    Map.entry("quarkus.flyway.clean-disabled", "true"));
        }
    }
}
