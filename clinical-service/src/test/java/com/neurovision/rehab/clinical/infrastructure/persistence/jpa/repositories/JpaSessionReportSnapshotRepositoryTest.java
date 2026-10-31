package com.neurovision.rehab.clinical.infrastructure.persistence.jpa.repositories;

import com.neurovision.rehab.clinical.application.ports.out.persistence.SessionReportSnapshotRepository.*;
import jakarta.persistence.*;
import jakarta.transaction.Transactional;
import java.lang.reflect.Proxy;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class JpaSessionReportSnapshotRepositoryTest {
    final UUID patient = new UUID(0, 1), session = new UUID(0, 2), id = new UUID(0, 3);
    final Instant time = Instant.parse("2026-10-01T00:03:00Z");
    final Snapshot copy = new Snapshot(id, patient, session, time, "{\"schemaVersion\":1}");
    final List<String> sql = new ArrayList<>();
    final List<Map<Object, Object>> parameters = new ArrayList<>();
    final Deque<List<?>> results = new ArrayDeque<>();
    int mutations, inserted = 1;
    boolean failWrite;
    List<Object[]> observation(boolean allowed, Snapshot snapshot) {
        return Collections.singletonList(snapshot == null ? new Object[]{allowed, null, null, null, null, null}
                : new Object[]{allowed, snapshot.id(), snapshot.clinicalPatientId(), snapshot.therapySessionId(), snapshot.createdAt(), snapshot.reportJson()});
    }
    JpaSessionReportSnapshotRepository repository() {
        var em = (EntityManager) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{EntityManager.class}, (p, m, a) -> {
            if (!m.getName().equals("createNativeQuery")) throw new AssertionError(m.getName());
            sql.add((String) a[0]); var params = new HashMap<Object, Object>(); parameters.add(params);
            return Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{Query.class}, (q, method, args) -> switch (method.getName()) {
                case "setParameter" -> { params.put(args[0], args[1]); yield q; }
                case "getResultList" -> results.removeFirst();
                case "executeUpdate" -> { mutations++; if (failWrite) throw new IllegalStateException("private database"); yield inserted; }
                default -> throw new AssertionError(method.getName());
            });
        });
        return new JpaSessionReportSnapshotRepository(em);
    }
    @Test void singleObservationBindsAllScopesAndReturnsOnlyAuthorizedStoredData() {
        var repo = repository(); results.add(observation(true, copy));
        assertEquals(copy, repo.observeByKey("synthetic-doctor", patient, session, "key").snapshot().orElseThrow());
        assertEquals(Map.of("doctor", "synthetic-doctor", "patient", patient, "session", session, "lookup", "key"), parameters.getFirst());
        for (String part : List.of("s.id = :session", "s.clinical_patient_id = :patient", "s.doctor_cognito_sub = :doctor",
                "a.status = 'ACTIVE'", "a.doctor_cognito_sub = s.doctor_cognito_sub", "a.allowed AND", "r.doctor_cognito_sub = :doctor",
                "r.therapy_session_id = :session", "r.clinical_patient_id = :patient", "r.idempotency_key = :lookup"))
            assertTrue(sql.getFirst().contains(part), part);
        results.add(observation(true, null)); assertTrue(repo.observeById("synthetic-doctor", patient, session, id).snapshot().isEmpty());
        assertTrue(sql.getLast().contains("r.id = :lookup")); assertEquals(id, parameters.getLast().get("lookup"));
        results.add(observation(false, null)); assertFalse(repo.observeById("synthetic-doctor", patient, session, id).authorized());
        assertEquals(3, sql.size()); assertEquals(0, mutations);
    }
    @Test void malformedOrDeniedDataNeverBecomesAnAuthorizedCopy() {
        var repo = repository();
        for (List<?> rows : List.<List<?>>of(List.of(), observation(false, copy), Collections.singletonList(new Object[]{true}),
                Collections.singletonList(new Object[]{true, null, patient, null, null, null}),
                List.of(observation(true, copy).getFirst(), observation(true, copy).getFirst()))) {
            results.add(rows); assertThrows(IllegalStateException.class, () -> repo.observeById("doctor", patient, session, id));
        }
    }
    @Test void writeLocksAndRevalidatesThenReturnsPersistedWinnerWithNoUpdate() {
        var repo = repository(); results.add(List.of(1)); results.add(observation(true, null)); results.add(observation(true, copy));
        var result = repo.insertAuthorized("doctor", patient, session, "key", copy);
        assertTrue(result.created()); assertEquals(copy, result.snapshot()); assertEquals(1, mutations);
        assertEquals(4, sql.size());
        assertTrue(sql.get(0).contains("AND s.status = 'COMPLETED' FOR UPDATE OF a, s"));
        assertTrue(sql.get(1).contains("r.idempotency_key = :lookup"));
        assertTrue(sql.get(2).contains("ON CONFLICT (doctor_cognito_sub, therapy_session_id, idempotency_key) DO NOTHING"));
        assertEquals("key", parameters.get(2).get("key")); assertEquals(copy.reportJson(), parameters.get(2).get("report"));
        assertFalse(sql.get(2).contains("DO UPDATE"));
    }
    @Test void previousWinnerAndConflictWinnerAreReturnedNotTheLosingCandidate() {
        var candidate = new Snapshot(UUID.randomUUID(), patient, session, time, "{\"new\":true}");
        var repo = repository(); results.add(List.of(1)); results.add(observation(true, copy));
        var previous = repo.insertAuthorized("doctor", patient, session, "key", candidate);
        assertFalse(previous.created()); assertEquals(copy, previous.snapshot()); assertEquals(0, mutations);
        results.add(List.of(1)); results.add(observation(true, null)); results.add(observation(true, copy)); inserted = 0;
        var conflict = repo.insertAuthorized("doctor", patient, session, "key", candidate);
        assertFalse(conflict.created()); assertEquals(copy, conflict.snapshot()); assertEquals(1, mutations);
    }
    @Test void revokedAssignmentPreventsInsertAndWriteUncertaintyIsNotRetried() {
        var repo = repository(); results.add(List.of());
        assertThrows(SecurityException.class, () -> repo.insertAuthorized("doctor", patient, session, "key", copy)); assertEquals(0, mutations);
        results.add(List.of(1)); results.add(observation(true, null)); failWrite = true;
        assertThrows(IllegalStateException.class, () -> repo.insertAuthorized("doctor", patient, session, "key", copy)); assertEquals(1, mutations);
        assertTrue(results.isEmpty());
    }
    @Test void transactionIsOnlyOnShortWriteAndMigrationIsAdditiveUniqueAndImmutable() throws Exception {
        var type = JpaSessionReportSnapshotRepository.class;
        var write = type.getMethod("insertAuthorized", String.class, UUID.class, UUID.class, String.class, Snapshot.class);
        assertEquals(Transactional.TxType.REQUIRES_NEW, write.getAnnotation(Transactional.class).value());
        assertNull(type.getAnnotation(Transactional.class));
        assertNull(type.getMethod("observeByKey", String.class, UUID.class, UUID.class, String.class).getAnnotation(Transactional.class));
        var migration = Files.readString(Path.of("src/main/resources/db/migration/V10__create_session_report_snapshots.sql"));
        for (String part : List.of("CREATE TABLE session_report_snapshots", "UNIQUE (doctor_cognito_sub, therapy_session_id, idempotency_key)",
                "BEFORE UPDATE OR DELETE", "RAISE EXCEPTION", "REFERENCES therapy_sessions(id)", "report_json TEXT NOT NULL"))
            assertTrue(migration.contains(part), part);
        assertFalse(migration.contains("ALTER TABLE")); assertFalse(migration.contains("DELETE FROM"));
    }
}
