package com.neurovision.rehab.clinical.infrastructure.persistence.jpa;

import static com.neurovision.rehab.clinical.support.TherapeuticConfigurationTestData.visualSearchSnapshot;
import static org.junit.jupiter.api.Assertions.*;

import com.neurovision.rehab.clinical.application.ports.in.PatientSessionProgressUseCase;
import com.neurovision.rehab.clinical.application.ports.out.persistence.*;
import com.neurovision.rehab.clinical.application.ports.out.persistence.SessionReportSnapshotRepository.*;
import com.neurovision.rehab.clinical.domain.model.aggregates.*;
import com.neurovision.rehab.clinical.domain.model.valueobjects.*;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.SQLException;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.*;

/** Opt-in existing-suite PostgreSQL proof. Leaves synthetic evidence; never cleans or provisions a database. */
@QuarkusTest
@TestProfile(SessionReportSnapshotPersistenceTest.IsolatedPostgres.class)
class SessionReportSnapshotPersistenceTest {
    private static final Instant T = Instant.parse("2026-01-01T00:00:00.123456Z");
    private static final CognitoSubject DOCTOR = new CognitoSubject("synthetic-snapshot-doctor");
    private static final List<String> LEGACY_TABLES = List.of("clinical_patients", "doctor_patient_assignments",
            "inbox_events", "therapy_plans", "therapy_plan_exercise_prescriptions", "therapy_sessions",
            "therapeutic_exercise_configurations", "therapy_plan_revisions", "therapy_plan_revision_exercises",
            "therapy_session_progress", "therapy_session_activity_receipts");
    private static final List<String> ALL_TABLES;
    static {
        var tables = new ArrayList<>(LEGACY_TABLES);
        tables.add("session_report_snapshots");
        ALL_TABLES = List.copyOf(tables);
    }
    @Inject DataSource dataSource;
    @Inject EntityManager entities;
    @Inject ClinicalPatientRepository patients;
    @Inject DoctorPatientAssignmentRepository assignments;
    @Inject TherapyPlanRepository plans;
    @Inject TherapeuticExerciseConfigurationRepository configurations;
    @Inject TherapySessionRepository sessions;
    @Inject SessionActivityRepository activity;
    // CDI proxy is essential: insertAuthorized owns its real REQUIRES_NEW transaction.
    @Inject SessionReportSnapshotRepository copies;

    @Test @Timeout(value = 120, unit = TimeUnit.SECONDS)
    void migratesV10AndVerifiesConcurrentImmutableCurrentlyAuthorizedCopies() throws Exception {
        attestFreshDedicatedDatabase();
        migration("9").migrate();
        var legacy = fixture(false);
        var other = fixture(true);
        var oldRows = rows(LEGACY_TABLES);
        var oldHash = hash(oldRows);
        var oldHistory = history();
        assertEquals(9, oldHistory.size(), "Unexpected starting migration history");

        migration("10").migrate();
        var migrated = rows(LEGACY_TABLES);
        assertTrue(oldRows.equals(migrated) && oldHash.equals(hash(migrated)), "V10 changed legacy data");
        var migratedHistory = history();
        assertEquals(10, migratedHistory.size());
        assertTrue(oldHistory.equals(migratedHistory.subList(0, 9)), "V10 changed prior migration history");
        assertTrue(tx(() -> ((Number) entities.createNativeQuery("SELECT count(*) FROM flyway_schema_history WHERE version = '10' AND success")
                .setHint("jakarta.persistence.query.timeout", 5000).getSingleResult()).longValue()) == 1,
                "V10 migration was not successful");
        assertEquals(0, countCopies());
        migration("10").migrate();
        assertTrue(migratedHistory.equals(history()), "Repeated V10 migration changed history");

        var a = candidate(legacy, 1);
        var b = candidate(legacy, 2);
        var raced = race(legacy, "synthetic-race-key", a, b);
        assertEquals(1, raced.stream().filter(WriteResult::created).count(), "Expected exactly one creator");
        var winner = raced.stream().filter(WriteResult::created).findFirst().orElseThrow().snapshot();
        assertTrue((winner.equals(a) || winner.equals(b)) && raced.stream().allMatch(r -> winner.equals(r.snapshot())),
                "Concurrent calls did not return one exact candidate including ID, timestamp and report bytes");
        assertEquals(1, countCopies());
        assertPresent(winner, byKey(DOCTOR.value(), legacy, "synthetic-race-key"));
        assertPresent(winner, byId(DOCTOR.value(), legacy, winner.id()));
        var original = rows(ALL_TABLES);
        var replay = copies.insertAuthorized(DOCTOR.value(), legacy.patient(), legacy.session(), "synthetic-race-key", candidate(legacy, 3));
        assertTrue(!replay.created() && winner.equals(replay.snapshot()), "Key replay replaced the winner");
        assertUnchanged(original, "Key replay changed stored data");

        var second = candidate(legacy, 4);
        var newKey = copies.insertAuthorized(DOCTOR.value(), legacy.patient(), legacy.session(), "synthetic-second-key", second);
        assertTrue(newKey.created() && second.equals(newKey.snapshot()), "New key did not create an independent exact copy");
        assertEquals(2, countCopies());
        assertPresent(winner, byKey(DOCTOR.value(), legacy, "synthetic-race-key"));
        assertPresent(second, byId(DOCTOR.value(), legacy, second.id()));
        var beforeReads = rows(ALL_TABLES);
        var missing = byId(DOCTOR.value(), legacy, UUID.randomUUID());
        assertTrue(missing.authorized() && missing.snapshot().isEmpty(), "Authorized missing copy was not distinguished");
        assertDenied(byId("synthetic-foreign-doctor", legacy, winner.id()));
        assertDenied(byKey("synthetic-foreign-doctor", legacy, "synthetic-race-key"));
        assertDenied(byId(DOCTOR.value(), new Fixture(other.patient(), legacy.session()), winner.id()));
        assertDenied(byKey(DOCTOR.value(), new Fixture(legacy.patient(), other.session()), "synthetic-race-key"));
        var otherScope = byId(DOCTOR.value(), other, winner.id());
        assertTrue(otherScope.authorized() && otherScope.snapshot().isEmpty(), "Snapshot escaped its authorized session scope");
        assertUnchanged(beforeReads, "Successful or denied scoped reads changed stored data");

        tx(() -> {
            var assignment = assignments.findActiveByClinicalPatientIdAndDoctorSubject(new ClinicalPatientId(legacy.patient()), DOCTOR)
                    .orElseThrow();
            assignment.end(T.plusSeconds(120));
            assignments.save(assignment);
            return null;
        });
        var revoked = rows(ALL_TABLES);
        assertDenied(byId(DOCTOR.value(), legacy, winner.id()));
        assertDenied(byKey(DOCTOR.value(), legacy, "synthetic-race-key"));
        assertThrows(SecurityException.class, () -> copies.insertAuthorized(DOCTOR.value(), legacy.patient(), legacy.session(),
                "synthetic-race-key", candidate(legacy, 5)));
        assertThrows(SecurityException.class, () -> copies.insertAuthorized(DOCTOR.value(), legacy.patient(), legacy.session(),
                "synthetic-revoked-new-key", candidate(legacy, 6)));
        assertUnchanged(revoked, "Revoked reads, replay or new write changed stored data");

        assertSqlMutationRejected("UPDATE session_report_snapshots SET report_json = '{\"synthetic\":\"changed\"}' WHERE id = ?", winner.id());
        assertSqlMutationRejected("DELETE FROM session_report_snapshots WHERE id = ?", winner.id());
        assertUnchanged(revoked, "Rejected UPDATE or DELETE changed stored data");
        assertTrue(migratedHistory.equals(history()), "Repository operations changed migration history");
    }

    private void attestFreshDedicatedDatabase() {
        try (var connection = dataSource.getConnection();
                var identity = connection.prepareStatement("SELECT current_database(), current_user, session_user");
                var relations = connection.prepareStatement("""
                        SELECT count(*) FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace
                        WHERE n.nspname NOT LIKE 'pg_%' AND n.nspname <> 'information_schema'
                        """)) {
            identity.setQueryTimeout(5);
            relations.setQueryTimeout(5);
            try (var result = identity.executeQuery()) {
                assertTrue(result.next() && IsolatedPostgres.DATABASE.equals(result.getString(1))
                        && IsolatedPostgres.USER.equals(result.getString(2))
                        && IsolatedPostgres.USER.equals(result.getString(3)), "Refusing non-dedicated database or role");
            }
            try (var result = relations.executeQuery()) {
                assertTrue(result.next() && result.getLong(1) == 0, "Refusing non-fresh database before migration");
            }
        } catch (SQLException unavailable) {
            throw new AssertionError("Dedicated PostgreSQL attestation unavailable");
        }
    }

    private Flyway migration(String target) {
        return Flyway.configure().dataSource(dataSource).locations("db/migration")
                .target(MigrationVersion.fromVersion(target)).cleanDisabled(true).load();
    }

    private Fixture fixture(boolean prescribed) {
        return tx(() -> {
            var patientId = new ClinicalPatientId(UUID.randomUUID());
            patients.save(ClinicalPatient.registerInvited(patientId,
                    new CognitoSubject("synthetic-snapshot-patient-" + UUID.randomUUID()), T.minusSeconds(100)));
            assignments.save(DoctorPatientAssignment.assign(new DoctorPatientAssignmentId(UUID.randomUUID()), patientId, DOCTOR, T.minusSeconds(90)));
            var plan = TherapyPlan.rehydrate(new TherapyPlanId(UUID.randomUUID()), patientId, DOCTOR, TherapyPlanStatus.ACTIVE,
                    1, 3, 30, null, 1, 15, 50, Map.of(TherapeuticExercise.VISUAL_SEARCH.code(), 1));
            plans.save(plan);
            var base = visualSearchSnapshot().parameters();
            var goal = new CompletionTarget(CompletionTarget.Criterion.ACTIVE_TIME, 1);
            var parameters = prescribed ? new TherapeuticExerciseParameters.Prescribed(base, goal) : base;
            var source = TherapeuticExerciseConfiguration.create(new TherapeuticConfigurationId(UUID.randomUUID()), plan.id(),
                    TherapeuticExercise.VISUAL_SEARCH, 1, parameters.schemaVersion(), T.minusSeconds(60), DOCTOR, T.minusSeconds(60), parameters);
            configurations.append(source);
            var id = new TherapySessionId(UUID.randomUUID());
            sessions.save(TherapySession.rehydrate(id, plan.id(), patientId, DOCTOR, source.exercise().code(),
                    TherapeuticConfigurationSnapshot.from(source), LocalDate.of(2026, 1, 1), new IdempotencyKey("synthetic-start"),
                    "a".repeat(64), 1, 15, 50, TherapySessionStatus.COMPLETED, 1, 0, true, T, null, T.plusSeconds(1), null));
            if (prescribed) {
                sessions.findByIdForUpdate(id).orElseThrow();
                var state = SessionActivityProgress.empty().accept(new SessionActivityEvent(1, UUID.randomUUID(), 0,
                        SessionActivityEvent.Type.ACTIVITY_STARTED, null, null), T);
                var event = new SessionActivityEvent(1, UUID.randomUUID(), 1, SessionActivityEvent.Type.HEARTBEAT, null, null);
                state = state.accept(event, T.plusSeconds(1));
                activity.save(id, state);
                activity.append(id, new SessionActivityRepository.Receipt(event, new PatientSessionProgressUseCase.View(id.value(),
                        "ACTIVE", goal.criterion(), goal.target(), state.activeMilliseconds(), state.completedRepetitions(), true,
                        state.nextSequence(), state.mode(), null)));
            }
            return new Fixture(patientId.value(), id.value());
        });
    }

    private Snapshot candidate(Fixture fixture, int sequence) {
        return new Snapshot(UUID.randomUUID(), fixture.patient(), fixture.session(), T.plusSeconds(sequence),
                "{\n  \"synthetic\": \"candidate-" + sequence + "\", \"schemaVersion\": 1\n}");
    }
    private List<WriteResult> race(Fixture fixture, String key, Snapshot first, Snapshot second) throws Exception {
        var ready = new CountDownLatch(2);
        var release = new CountDownLatch(1);
        var pool = Executors.newFixedThreadPool(2);
        var futures = new ArrayList<Future<WriteResult>>();
        try {
            for (var candidate : List.of(first, second)) futures.add(pool.submit(() -> {
                ready.countDown();
                if (!release.await(5, TimeUnit.SECONDS)) throw new AssertionError("Snapshot write barrier expired");
                // No outer transaction: the managed repository interceptor creates each independent write transaction.
                return copies.insertAuthorized(DOCTOR.value(), fixture.patient(), fixture.session(), key, candidate);
            }));
            assertTrue(ready.await(5, TimeUnit.SECONDS), "Concurrent snapshot writers did not become ready");
            release.countDown();
            return List.of(futures.get(0).get(20, TimeUnit.SECONDS), futures.get(1).get(20, TimeUnit.SECONDS));
        } finally {
            release.countDown();
            futures.forEach(future -> future.cancel(true));
            pool.shutdownNow();
            assertTrue(pool.awaitTermination(5, TimeUnit.SECONDS), "Snapshot writers did not terminate");
        }
    }
    private Observation byId(String doctor, Fixture fixture, UUID id) {
        return tx(() -> copies.observeById(doctor, fixture.patient(), fixture.session(), id));
    }
    private Observation byKey(String doctor, Fixture fixture, String key) {
        return tx(() -> copies.observeByKey(doctor, fixture.patient(), fixture.session(), key));
    }
    private void assertPresent(Snapshot expected, Observation actual) {
        assertTrue(actual.authorized() && actual.snapshot().filter(expected::equals).isPresent(), "Stored snapshot content or authorization differs");
    }
    private void assertDenied(Observation actual) {
        assertTrue(!actual.authorized() && actual.snapshot().isEmpty(), "Denied scope disclosed a snapshot");
    }
    private long countCopies() {
        return tx(() -> ((Number) entities.createNativeQuery("SELECT count(*) FROM session_report_snapshots")
                .setHint("jakarta.persistence.query.timeout", 5000).getSingleResult()).longValue());
    }
    private Map<String, List<String>> rows(List<String> tables) {
        return tx(() -> {
            var result = new LinkedHashMap<String, List<String>>();
            for (var table : tables) {
                assertTrue(ALL_TABLES.contains(table), "Unknown snapshot table");
                List<?> values = entities.createNativeQuery("SELECT CAST(to_jsonb(t) AS text) FROM " + table + " t ORDER BY CAST(to_jsonb(t) AS text)")
                        .setHint("jakarta.persistence.query.timeout", 5000).getResultList();
                result.put(table, values.stream().map(Object::toString).toList());
            }
            return result;
        });
    }
    private String hash(Map<String, List<String>> rows) throws Exception {
        var digest = MessageDigest.getInstance("SHA-256");
        for (var entry : rows.entrySet()) {
            digest.update(entry.getKey().getBytes(StandardCharsets.UTF_8));
            digest.update((byte) 0);
            for (var row : entry.getValue()) {
                digest.update(row.getBytes(StandardCharsets.UTF_8));
                digest.update((byte) 0);
            }
        }
        return HexFormat.of().formatHex(digest.digest());
    }
    private void assertUnchanged(Map<String, List<String>> before, String message) throws Exception {
        var after = rows(ALL_TABLES);
        assertTrue(before.equals(after) && hash(before).equals(hash(after)), message);
    }
    private List<String> history() {
        return tx(() -> {
            List<?> values = entities.createNativeQuery("SELECT CAST(to_jsonb(h) AS text) FROM flyway_schema_history h ORDER BY installed_rank")
                    .setHint("jakarta.persistence.query.timeout", 5000).getResultList();
            return values.stream().map(Object::toString).toList();
        });
    }
    private void assertSqlMutationRejected(String sql, UUID id) throws Exception {
        var before = rows(ALL_TABLES);
        boolean rejected = false;
        try (var connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try (var statement = connection.prepareStatement(sql)) {
                statement.setQueryTimeout(5);
                statement.setObject(1, id);
                try { statement.executeUpdate(); }
                catch (SQLException expected) {
                    assertEquals("P0001", expected.getSQLState(), "Unexpected immutable trigger rejection category");
                    rejected = true;
                }
            } finally { connection.rollback(); }
        } catch (SQLException unavailable) {
            throw new AssertionError("Isolated snapshot mutation check unavailable");
        }
        assertTrue(rejected, "Snapshot mutation was not rejected");
        assertUnchanged(before, "Rejected snapshot mutation changed data");
    }
    private <T> T tx(Callable<T> operation) { return QuarkusTransaction.requiringNew().timeout(10).call(operation); }
    private record Fixture(UUID patient, UUID session) { }

    public static class IsolatedPostgres implements QuarkusTestProfile {
        static final String DATABASE = "nv_clinical_report_snapshots_test";
        static final String USER = "clinical_report_snapshots_test";
        @Override public boolean disableGlobalTestResources() { return true; }
        @Override public Map<String, String> getConfigOverrides() {
            if (!Boolean.getBoolean("clinical.report-snapshots-test.profile-selected")
                    || !Boolean.getBoolean("clinical.report-snapshots-test.enabled"))
                throw new IllegalStateException("Explicit snapshots Maven profile and separate execution opt-in required");
            String url = System.getProperty("clinical.report-snapshots-test.jdbc-url", "");
            if (!url.matches("jdbc:postgresql://127[.]0[.]0[.]1:[0-9]{1,5}/" + DATABASE))
                throw new IllegalStateException("Explicit dedicated loopback snapshots PostgreSQL URL required");
            int port = Integer.parseInt(url.substring(url.lastIndexOf(':') + 1, url.lastIndexOf('/')));
            if (port < 1 || port > 65535) throw new IllegalStateException("Invalid dedicated PostgreSQL port");
            return Map.ofEntries(
                    Map.entry("quarkus.datasource.jdbc.url", url + "?connectTimeout=5&socketTimeout=15&options=-c%20TimeZone%3DUTC%20-c%20lock_timeout%3D5000%20-c%20statement_timeout%3D10000"),
                    Map.entry("quarkus.datasource.username", USER),
                    Map.entry("quarkus.datasource.password", "synthetic-report-snapshots-test-only"),
                    Map.entry("quarkus.datasource.jdbc.max-size", "4"),
                    Map.entry("quarkus.datasource.jdbc.acquisition-timeout", "5S"),
                    Map.entry("quarkus.transaction-manager.default-transaction-timeout", "10S"),
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
                    Map.entry("quarkus.flyway.clean-disabled", "true"),
                    Map.entry("quarkus.flyway.target", "10"));
        }
    }
}
