package com.neurovision.rehab.clinical.infrastructure.persistence.jpa;

import static org.junit.jupiter.api.Assertions.*;

import com.neurovision.rehab.clinical.application.internal.queryservices.DoctorTherapyPlanQueryService;
import com.neurovision.rehab.clinical.application.ports.out.persistence.*;
import com.neurovision.rehab.clinical.domain.model.aggregates.*;
import com.neurovision.rehab.clinical.domain.model.valueobjects.*;
import io.agroal.api.AgroalDataSource;
import io.quarkus.test.TestTransaction;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import java.sql.SQLException;
import java.time.*;
import java.util.*;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;

/** Real SQL proof only; HTTP, authentication and concurrent writers are outside this test. */
@QuarkusTest
@TestProfile(DoctorTherapyPlanReadPersistenceTest.IsolatedPostgres.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class DoctorTherapyPlanReadPersistenceTest {
    private static final Instant T = Instant.parse("2026-10-21T12:00:00Z");
    private static final CognitoSubject DOCTOR = new CognitoSubject("synthetic-plan-reader");
    private static final CognitoSubject OTHER = new CognitoSubject("synthetic-other-plan-owner");
    private static final ClinicalPatientId PATIENT = new ClinicalPatientId(new UUID(1, 1));
    private static final ClinicalPatientId ENDED = new ClinicalPatientId(new UUID(1, 2));
    private static final ClinicalPatientId EMPTY = new ClinicalPatientId(new UUID(1, 3));
    private static final Map<String, UUID> IDS = Map.of(
            "A", new UUID(0, 10), "B", new UUID(0, 20), "C", new UUID(0, 30),
            "D", new UUID(0, 40), "E", new UUID(0, 50), "F", new UUID(0, 60),
            "FOREIGN", new UUID(0, 15), "OTHER_PATIENT", new UUID(0, 25));
    private static final Map<String, TherapyPlanStatus> AT_T = Map.of(
            "A", TherapyPlanStatus.ACTIVE, "B", TherapyPlanStatus.SUSPENDED,
            "C", TherapyPlanStatus.CANCELLED, "D", TherapyPlanStatus.ACTIVE,
            "E", TherapyPlanStatus.SUSPENDED, "F", TherapyPlanStatus.CANCELLED);
    private static final List<String> OBSERVED_TABLES = List.of(
            "clinical_patients", "doctor_patient_assignments", "therapy_plans",
            "therapy_plan_exercise_prescriptions", "therapy_plan_revisions",
            "therapy_plan_revision_exercises", "therapy_sessions");

    @Inject AgroalDataSource dataSource;
    @Inject Flyway flyway;
    @Inject EntityManager entities;
    @Inject ClinicalPatientRepository patients;
    @Inject DoctorPatientAssignmentRepository assignments;
    @Inject TherapyPlanRepository plans;
    @Inject TherapyPlanRevisionRepository revisions;
    @Inject DoctorTherapyPlanReadRepository reads;

    @BeforeAll
    void verifyDedicatedDatabaseBeforeMigrating() throws SQLException {
        // Schema mutation is deliberately disabled at startup; attest the actual connection first.
        try (var connection = dataSource.getConnection();
                var statement = connection.prepareStatement("SELECT current_database(), current_user")) {
            statement.setQueryTimeout(5);
            try (var result = statement.executeQuery()) {
                assertTrue(result.next(), "Dedicated PostgreSQL identity is unavailable");
                assertTrue(IsolatedPostgres.DATABASE.equals(result.getString(1))
                                && IsolatedPostgres.USER.equals(result.getString(2)),
                        "Refusing a non-dedicated PostgreSQL database or role");
            }
        }
        flyway.migrate();
    }

    @Test
    @TestTransaction
    @Timeout(60)
    void filtersEffectiveStatusBeforeCountingAndPagingWithoutChangingStoredData() {
        requireEmptyDatabase();
        seed();
        entities.flush();
        entities.clear(); // Exercise persisted JPA mappings, not just objects left in the first-level cache.
        var before = snapshot();
        var query = queryAt(T);

        assertPage(query, PATIENT, null, 0, 2, 6, List.of("A", "B"), AT_T);
        assertPage(query, PATIENT, null, 1, 2, 6, List.of("C", "D"), AT_T);
        assertPage(query, PATIENT, null, 2, 2, 6, List.of("E", "F"), AT_T);
        assertPage(query, PATIENT, null, 3, 2, 6, List.of(), AT_T);

        // These are independent, hand-authored expectations, not a copy of the production SQL.
        var expected = Map.of("ACTIVE", List.of("A", "D"),
                "SUSPENDED", List.of("B", "E"), "CANCELLED", List.of("C", "F"));
        expected.forEach((status, aliases) -> {
            assertPage(query, PATIENT, status, 0, 2, 2, aliases, AT_T);
            assertPage(query, PATIENT, status, 1, 2, 2, List.of(), AT_T);
            assertPage(query, PATIENT, status, 0, 1, 2, List.of(aliases.get(0)), AT_T);
            assertPage(query, PATIENT, status, 1, 1, 2, List.of(aliases.get(1)), AT_T);
            assertPage(query, PATIENT, status, 2, 1, 2, List.of(), AT_T);
        });
        assertPage(query, PATIENT, null, 0, 2, 6, List.of("A", "B"), AT_T);
        assertPage(query, EMPTY, null, 0, 2, 0, List.of(), AT_T);
        assertThrows(SecurityException.class,
                () -> query.list(DOCTOR.value(), ENDED.value(), 0, 2, null));
        assertThrows(SecurityException.class,
                () -> query.list(DOCTOR.value(), new UUID(1, 99), 0, 2, null));

        var later = queryAt(T.plusSeconds(1));
        var atBoundary = new HashMap<>(AT_T);
        atBoundary.put("D", TherapyPlanStatus.SUSPENDED);
        assertPage(later, PATIENT, "ACTIVE", 0, 10, 1, List.of("A"), atBoundary);
        assertPage(later, PATIENT, "SUSPENDED", 0, 10, 3, List.of("B", "D", "E"), atBoundary);
        assertPage(later, PATIENT, "CANCELLED", 0, 10, 2, List.of("C", "F"), atBoundary);

        entities.flush();
        assertTrue(before.equals(snapshot()), "Plan reads changed persisted synthetic data");
        // TestTransaction rolls back all fixture writes, including append-only revisions.
    }

    private DoctorTherapyPlanQueryService queryAt(Instant instant) {
        return new DoctorTherapyPlanQueryService(reads, Clock.fixed(instant, ZoneOffset.UTC));
    }

    private void assertPage(DoctorTherapyPlanQueryService query, ClinicalPatientId patient,
            String status, int page, int size, long total, List<String> expected,
            Map<String, TherapyPlanStatus> states) {
        var result = query.list(DOCTOR.value(), patient.value(), page, size, status);
        assertTrue(result.clinicalPatientId().equals(patient.value()), "Wrong patient scope");
        assertEquals(page, result.page());
        assertEquals(size, result.size());
        assertEquals(total, result.total(), "Wrong filtered total");
        var actual = result.plans().stream().map(plan -> IDS.entrySet().stream()
                .filter(entry -> entry.getValue().equals(plan.id().value()))
                .map(Map.Entry::getKey).findFirst().orElse("UNEXPECTED_PLAN")).toList();
        assertEquals(expected, actual, "Wrong ordered plan aliases");
        for (int i = 0; i < expected.size(); i++) {
            var plan = result.plans().get(i);
            assertEquals(states.get(expected.get(i)), plan.status(), "Wrong effective state");
            assertTrue(plan.doctorSubject().equals(DOCTOR), "Wrong owner scope");
            assertEquals(4, plan.configurationVersion(), "Read must not manufacture a revision version");
            if (expected.get(i).equals("B")) assertEquals(7, plan.dailySessionLimit());
        }
    }

    private void seed() {
        for (var patient : List.of(PATIENT, ENDED, EMPTY)) {
            patients.save(ClinicalPatient.registerInvited(patient,
                    new CognitoSubject("synthetic-plan-patient-" + patient.value().getLeastSignificantBits()), T.minusSeconds(120)));
        }
        assignment(PATIENT, DOCTOR, 1, false);
        assignment(PATIENT, OTHER, 2, false);
        assignment(ENDED, DOCTOR, 3, true);
        assignment(EMPTY, DOCTOR, 4, false);
        for (var alias : List.of("A", "B", "C", "D")) plan(alias, PATIENT, DOCTOR, TherapyPlanStatus.ACTIVE);
        plan("E", PATIENT, DOCTOR, TherapyPlanStatus.SUSPENDED);
        plan("F", PATIENT, DOCTOR, TherapyPlanStatus.CANCELLED);
        plan("FOREIGN", PATIENT, OTHER, TherapyPlanStatus.ACTIVE);
        plan("OTHER_PATIENT", ENDED, DOCTOR, TherapyPlanStatus.ACTIVE);
        revisions.appendAll(List.of(
                revision("B", 1, TherapyPlanStatus.SUSPENDED, null, T.minusSeconds(20)),
                revision("B", 2, null, 7, T.minusSeconds(10)),
                revision("C", 1, TherapyPlanStatus.SUSPENDED, null, T),
                revision("C", 2, TherapyPlanStatus.CANCELLED, null, T),
                revision("D", 1, TherapyPlanStatus.SUSPENDED, null, T.plusSeconds(1))));
    }

    private void assignment(ClinicalPatientId patient, CognitoSubject doctor, long number, boolean ended) {
        var value = DoctorPatientAssignment.assign(new DoctorPatientAssignmentId(new UUID(2, number)),
                patient, doctor, T.minusSeconds(100));
        if (ended) value.end(T.minusSeconds(1));
        assignments.save(value);
    }

    private void plan(String alias, ClinicalPatientId patient, CognitoSubject doctor, TherapyPlanStatus status) {
        plans.save(TherapyPlan.rehydrate(new TherapyPlanId(IDS.get(alias)), patient, doctor, status,
                4, 2, 20, null, 1, 15, 10, Map.of(new ExerciseCode("fixation"), 1)));
    }

    private TherapyPlanRevision revision(String alias, long sequence, TherapyPlanStatus status,
            Integer limit, Instant effective) {
        long number = IDS.get(alias).getLeastSignificantBits() * 10 + sequence;
        return TherapyPlanRevision.create(new TherapyPlanRevisionId(new UUID(3, number)),
                new TherapyPlanId(IDS.get(alias)), sequence, new UUID(4, number), "a".repeat(64),
                TherapyPlanRevisionKind.IMMEDIATE, effective, T.minusSeconds(60), DOCTOR,
                status, limit, null, null, null, status == null ? null : "Synthetic test reason");
    }

    private void requireEmptyDatabase() {
        for (var table : OBSERVED_TABLES) {
            var count = (Number) entities.createNativeQuery("SELECT count(*) FROM " + table).getSingleResult();
            assertTrue(count.longValue() == 0, "Refusing nonempty synthetic test database");
        }
    }

    private Map<String, List<?>> snapshot() {
        var result = new LinkedHashMap<String, List<?>>();
        for (var table : OBSERVED_TABLES) {
            List<?> rows = entities.createNativeQuery("SELECT CAST(to_jsonb(t) AS text) FROM " + table
                    + " t ORDER BY CAST(to_jsonb(t) AS text)").getResultList();
            result.put(table, List.copyOf(rows));
        }
        return result;
    }

    /** Explicit opt-in connection; no ambient Compose URL, credentials or automatic containers. */
    public static class IsolatedPostgres implements QuarkusTestProfile {
        static final String DATABASE = "nv_clinical_plan_reads_test";
        static final String USER = "clinical_plan_reads_test";

        @Override public Map<String, String> getConfigOverrides() {
            String url = System.getProperty("clinical.plan-read-test.jdbc-url", "");
            if (!url.matches("jdbc:postgresql://127[.]0[.]0[.]1:[0-9]{1,5}/" + DATABASE))
                throw new IllegalStateException("Explicit isolated plan-read PostgreSQL URL is required");
            int port = Integer.parseInt(url.substring(url.lastIndexOf(':') + 1, url.lastIndexOf('/')));
            if (port < 1 || port > 65535) throw new IllegalStateException("Invalid isolated PostgreSQL port");
            return Map.ofEntries(
                    Map.entry("quarkus.datasource.jdbc.url", url + "?connectTimeout=5&socketTimeout=5"),
                    Map.entry("quarkus.datasource.username", USER),
                    Map.entry("quarkus.datasource.password", "synthetic-plan-read-test-only"),
                    Map.entry("quarkus.datasource.devservices.enabled", "false"),
                    Map.entry("quarkus.kafka.devservices.enabled", "false"),
                    Map.entry("quarkus.http.test-port", "0"),
                    Map.entry("mp.messaging.incoming.patient-account-invited.enabled", "false"),
                    Map.entry("quarkus.oidc.enabled", "false"),
                    Map.entry("quarkus.hibernate-orm.schema-management.strategy", "none"),
                    Map.entry("quarkus.flyway.migrate-at-start", "false"),
                    Map.entry("quarkus.flyway.clean-disabled", "true"),
                    Map.entry("quarkus.datasource.jdbc.acquisition-timeout", "5S"));
        }
    }
}
