package com.neurovision.rehab.clinical.infrastructure.persistence.jpa.repositories;

import com.neurovision.rehab.clinical.application.ports.out.authorization.DoctorVisualSearchSummarySnapshotRepository;
import com.neurovision.rehab.clinical.domain.model.valueobjects.*;
import com.neurovision.rehab.clinical.infrastructure.persistence.json.TherapeuticConfigurationJsonCodec;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;
import java.math.BigDecimal;
import java.util.*;

@ApplicationScoped
public class JpaDoctorVisualSearchSummarySnapshotRepository implements DoctorVisualSearchSummarySnapshotRepository {
    private final EntityManager entities;
    private final TherapeuticConfigurationJsonCodec codec;
    public JpaDoctorVisualSearchSummarySnapshotRepository(EntityManager entities, TherapeuticConfigurationJsonCodec codec) {
        this.entities = Objects.requireNonNull(entities); this.codec = Objects.requireNonNull(codec);
    }

    @Override @Transactional(Transactional.TxType.SUPPORTS)
    public Optional<Snapshot> findByDoctorAndSession(CognitoSubject doctor, TherapySessionId sessionId) {
        Objects.requireNonNull(doctor); Objects.requireNonNull(sessionId);
        try {
            // One statement binds the caller to the session author and current assignment, not the live plan.
            var rows = entities.createNativeQuery("""
                    SELECT s.id, s.therapeutic_configuration_id, s.configuration_version,
                           s.therapeutic_exercise, s.parameter_schema_version, s.configuration_parameters::text
                    FROM therapy_sessions s
                    JOIN clinical_patients p ON p.id = s.clinical_patient_id
                    WHERE s.id = :sessionId AND s.doctor_cognito_sub = :doctorSubject
                      AND EXISTS (
                        SELECT 1 FROM doctor_patient_assignments a
                        WHERE a.clinical_patient_id = s.clinical_patient_id
                          AND a.doctor_cognito_sub = :doctorSubject AND a.status = 'ACTIVE'
                      )
                    """).setParameter("sessionId", sessionId.value()).setParameter("doctorSubject", doctor.value())
                    .setMaxResults(2).getResultList();
            if (rows.isEmpty()) return Optional.empty();
            if (rows.size() != 1 || !(rows.getFirst() instanceof Object[] row) || row.length != 6
                    || !sessionId.value().equals(row[0])) throw unavailable();
            var exercise = TherapeuticExercise.valueOf((String) row[3]);
            int schema = Math.toIntExact(integer(row[4]));
            var frozen = new TherapeuticConfigurationSnapshot(new TherapeuticConfigurationId((UUID) row[1]),
                    integer(row[2]), exercise, schema, codec.decode(exercise, schema, (String) row[5]));
            return Optional.of(new Snapshot(sessionId, frozen));
        } catch (RuntimeException unavailable) { throw unavailable(); }
    }

    private static long integer(Object value) {
        if (!(value instanceof Number number)) throw unavailable();
        return new BigDecimal(number.toString()).longValueExact();
    }
    private static IllegalStateException unavailable() { return new IllegalStateException("Visual Search summary snapshot unavailable"); }
}
