package com.neurovision.rehab.clinical.infrastructure.persistence.jpa.repositories;

import com.neurovision.rehab.clinical.application.ports.out.authorization.VisualSearchAuthorizationSnapshotRepository;
import com.neurovision.rehab.clinical.domain.model.valueobjects.*;
import com.neurovision.rehab.clinical.infrastructure.persistence.json.TherapeuticConfigurationJsonCodec;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.*;
import java.util.*;

@ApplicationScoped
public class JpaVisualSearchAuthorizationSnapshotRepository implements VisualSearchAuthorizationSnapshotRepository {
    private final EntityManager entities;
    private final TherapeuticConfigurationJsonCodec codec;
    public JpaVisualSearchAuthorizationSnapshotRepository(EntityManager entities, TherapeuticConfigurationJsonCodec codec) {
        this.entities = Objects.requireNonNull(entities); this.codec = Objects.requireNonNull(codec);
    }

    @Override @Transactional(Transactional.TxType.SUPPORTS)
    public Optional<Snapshot> findBySubjectAndSession(CognitoSubject subject, TherapySessionId sessionId) {
        Objects.requireNonNull(subject); Objects.requireNonNull(sessionId);
        try {
            // One statement observes the subject binding, active assignment, state and frozen session fields.
            var rows = entities.createNativeQuery("""
                    SELECT s.id, s.status, s.lease_expires_at, s.therapeutic_configuration_id,
                           s.configuration_version, s.therapeutic_exercise, s.parameter_schema_version,
                           s.configuration_parameters::text
                    FROM therapy_sessions s
                    JOIN clinical_patients p ON p.id = s.clinical_patient_id
                    WHERE s.id = :sessionId AND p.patient_cognito_sub = :patientSubject
                      AND EXISTS (
                        SELECT 1 FROM doctor_patient_assignments a
                        WHERE a.clinical_patient_id = s.clinical_patient_id
                          AND a.doctor_cognito_sub = s.doctor_cognito_sub AND a.status = 'ACTIVE'
                      )
                    """).setParameter("sessionId", sessionId.value()).setParameter("patientSubject", subject.value())
                    .setMaxResults(2).getResultList();
            if (rows.isEmpty()) return Optional.empty();
            if (rows.size() != 1 || !(rows.getFirst() instanceof Object[] row) || row.length != 8) throw unavailable();
            var exercise = TherapeuticExercise.valueOf((String) row[5]);
            int schema = Math.toIntExact(integer(row[6]));
            var frozen = new TherapeuticConfigurationSnapshot(new TherapeuticConfigurationId((UUID) row[3]),
                    integer(row[4]), exercise, schema, codec.decode(exercise, schema, (String) row[7]));
            return Optional.of(new Snapshot(new TherapySessionId((UUID) row[0]), TherapySessionStatus.valueOf((String) row[1]),
                    instant(row[2]), frozen));
        } catch (RuntimeException unavailable) { throw unavailable(); }
    }

    private static long integer(Object value) {
        if (!(value instanceof Number number)) throw unavailable();
        return new BigDecimal(number.toString()).longValueExact();
    }
    private static Instant instant(Object value) {
        if (value == null) return null;
        if (value instanceof Instant instant) return instant;
        if (value instanceof OffsetDateTime time) return time.toInstant();
        if (value instanceof Timestamp time) return time.toInstant();
        throw unavailable();
    }
    private static IllegalStateException unavailable() { return new IllegalStateException("Visual Search authorization snapshot unavailable"); }
}
