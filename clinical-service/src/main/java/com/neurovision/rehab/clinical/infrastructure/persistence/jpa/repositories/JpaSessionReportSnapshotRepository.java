package com.neurovision.rehab.clinical.infrastructure.persistence.jpa.repositories;

import com.neurovision.rehab.clinical.application.ports.out.persistence.SessionReportSnapshotRepository;
import com.neurovision.rehab.clinical.application.ports.out.persistence.SessionReportSnapshotListRepository;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;
import jakarta.transaction.Transactional;
import java.time.*;
import java.util.*;

@ApplicationScoped
public class JpaSessionReportSnapshotRepository implements SessionReportSnapshotRepository, SessionReportSnapshotListRepository {
    private final EntityManager entities;
    public JpaSessionReportSnapshotRepository(EntityManager entities) { this.entities = Objects.requireNonNull(entities); }

    private static final String AUTHORIZED = """
            SELECT 1 FROM therapy_sessions s JOIN doctor_patient_assignments a
              ON a.clinical_patient_id = s.clinical_patient_id AND a.doctor_cognito_sub = s.doctor_cognito_sub
            WHERE s.id = :session AND s.clinical_patient_id = :patient AND s.doctor_cognito_sub = :doctor
              AND a.status = 'ACTIVE'
            """;

    @Override public SessionReportSnapshotListRepository.ListObservation observeList(String doctor, UUID patient, UUID session,
            int offset, int limit) {
        if (offset < 0 || limit < 1 || limit > 100) throw new IllegalArgumentException("Invalid snapshot page");
        var query = scope(entities.createNativeQuery("WITH authorized AS (SELECT EXISTS (" + AUTHORIZED + """
                ) AS allowed), filtered AS (
                  SELECT r.id, r.created_at FROM session_report_snapshots r
                  WHERE r.clinical_patient_id = :patient AND r.therapy_session_id = :session
                    AND r.doctor_cognito_sub = :doctor AND (SELECT allowed FROM authorized)
                ), page AS (
                  SELECT id, created_at FROM filtered ORDER BY created_at DESC, id DESC LIMIT :limit OFFSET :offset
                )
                SELECT a.allowed, (SELECT count(*) FROM filtered), p.id, p.created_at
                FROM authorized a LEFT JOIN page p ON true ORDER BY p.created_at DESC, p.id DESC
                """), doctor, patient, session).setParameter("offset", offset).setParameter("limit", limit);
        @SuppressWarnings("unchecked") List<Object[]> rows = query.getResultList();
        if (rows.isEmpty() || rows.size() > limit || rows.getFirst().length != 4
                || !(rows.getFirst()[0] instanceof Boolean) || !(rows.getFirst()[1] instanceof Number)) throw unavailable();
        boolean allowed = (Boolean) rows.getFirst()[0]; long total = ((Number) rows.getFirst()[1]).longValue();
        var summaries = new ArrayList<SessionReportSnapshotListRepository.Summary>();
        for (var row : rows) {
            if (row.length != 4 || !Objects.equals(row[0], allowed) || !(row[1] instanceof Number count)
                    || count.longValue() != total) throw unavailable();
            if (row[2] == null) {
                if (rows.size() != 1 || row[3] != null) throw unavailable();
            } else summaries.add(new SessionReportSnapshotListRepository.Summary((UUID) row[2], instant(row[3])));
        }
        return new SessionReportSnapshotListRepository.ListObservation(allowed, summaries, total);
    }

    @Override public Observation observeByKey(String doctor, UUID patient, UUID session, String key) {
        return observe(doctor, patient, session, "r.idempotency_key = :lookup", key);
    }
    @Override public Observation observeById(String doctor, UUID patient, UUID session, UUID id) {
        return observe(doctor, patient, session, "r.id = :lookup", id);
    }

    private Observation observe(String doctor, UUID patient, UUID session, String predicate, Object lookup) {
        var query = scope(entities.createNativeQuery("WITH authorized AS (SELECT EXISTS (" + AUTHORIZED + """
                ) AS allowed)
                SELECT a.allowed, r.id, r.clinical_patient_id, r.therapy_session_id, r.created_at, r.report_json
                FROM authorized a LEFT JOIN session_report_snapshots r
                  ON a.allowed AND r.clinical_patient_id = :patient AND r.therapy_session_id = :session
                  AND r.doctor_cognito_sub = :doctor AND
                """ + predicate + " LIMIT 2"), doctor, patient, session).setParameter("lookup", lookup);
        @SuppressWarnings("unchecked") List<Object[]> rows = query.getResultList();
        if (rows.size() != 1 || rows.getFirst().length != 6 || !(rows.getFirst()[0] instanceof Boolean)) throw unavailable();
        var row = rows.getFirst();
        boolean allowed = (Boolean) row[0];
        if (row[1] == null) {
            for (int i = 2; i < row.length; i++) if (row[i] != null) throw unavailable();
            return new Observation(allowed, Optional.empty());
        }
        if (!allowed) throw unavailable();
        return new Observation(true, Optional.of(snapshot(Arrays.copyOfRange(row, 1, 6))));
    }

    @Override @Transactional(Transactional.TxType.REQUIRES_NEW)
    public WriteResult insertAuthorized(String doctor, UUID patient, UUID session, String key, Snapshot candidate) {
        if (!patient.equals(candidate.clinicalPatientId()) || !session.equals(candidate.therapySessionId())) throw unavailable();
        // Lock the current assignment and authored completed session, then re-read the scoped key.
        // Competing first requests may compose, but serialize here; revocation updates use the same row lock.
        var locked = scope(entities.createNativeQuery(AUTHORIZED + " AND s.status = 'COMPLETED' FOR UPDATE OF a, s"), doctor, patient, session)
                .getResultList();
        if (locked.isEmpty()) throw new SecurityException("Session report snapshot access denied");
        if (locked.size() != 1) throw unavailable();
        var previous = observeByKey(doctor, patient, session, key);
        if (!previous.authorized()) throw new SecurityException("Session report snapshot access denied");
        if (previous.snapshot().isPresent()) return new WriteResult(false, previous.snapshot().get());
        int inserted = scope(entities.createNativeQuery("""
                INSERT INTO session_report_snapshots
                  (id, clinical_patient_id, therapy_session_id, doctor_cognito_sub, idempotency_key, created_at, report_json)
                VALUES (:id, :patient, :session, :doctor, :key, :created, :report)
                ON CONFLICT (doctor_cognito_sub, therapy_session_id, idempotency_key) DO NOTHING
                """), doctor, patient, session).setParameter("id", candidate.id()).setParameter("key", key)
                .setParameter("created", candidate.createdAt()).setParameter("report", candidate.reportJson()).executeUpdate();
        if (inserted != 0 && inserted != 1) throw unavailable();
        var winner = observeByKey(doctor, patient, session, key);
        if (!winner.authorized() || winner.snapshot().isEmpty()) throw unavailable();
        return new WriteResult(inserted == 1, winner.snapshot().get());
    }

    private static Query scope(Query query, String doctor, UUID patient, UUID session) {
        return query.setParameter("doctor", doctor).setParameter("patient", patient).setParameter("session", session);
    }
    private static Snapshot snapshot(Object[] row) {
        return new Snapshot((UUID) row[0], (UUID) row[1], (UUID) row[2], instant(row[3]), (String) row[4]);
    }
    private static Instant instant(Object value) {
        if (value instanceof Instant instant) return instant;
        if (value instanceof OffsetDateTime offset) return offset.toInstant();
        if (value instanceof java.sql.Timestamp timestamp) return timestamp.toInstant();
        throw unavailable();
    }
    private static IllegalStateException unavailable() { return new IllegalStateException("Session report snapshot unavailable"); }
}
