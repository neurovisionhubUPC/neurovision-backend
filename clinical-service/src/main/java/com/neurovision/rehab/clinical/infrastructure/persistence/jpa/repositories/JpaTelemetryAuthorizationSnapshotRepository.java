package com.neurovision.rehab.clinical.infrastructure.persistence.jpa.repositories;

import com.neurovision.rehab.clinical.application.ports.out.authorization.TelemetryAuthorizationFacts;
import com.neurovision.rehab.clinical.application.ports.out.authorization.TelemetryAuthorizationSnapshotRepository;
import com.neurovision.rehab.clinical.domain.model.valueobjects.CognitoSubject;
import com.neurovision.rehab.clinical.domain.model.valueobjects.TherapySessionId;
import com.neurovision.rehab.clinical.domain.model.valueobjects.TherapySessionStatus;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Objects;
import java.util.Optional;

@ApplicationScoped
public class JpaTelemetryAuthorizationSnapshotRepository
        implements TelemetryAuthorizationSnapshotRepository {
    private final EntityManager entityManager;

    public JpaTelemetryAuthorizationSnapshotRepository(EntityManager entityManager) {
        this.entityManager = Objects.requireNonNull(entityManager);
    }

    @Override
    @Transactional(Transactional.TxType.SUPPORTS)
    public Optional<TelemetryAuthorizationFacts> findBySubjectAndSession(
            CognitoSubject patientSubject, TherapySessionId sessionId) {
        Objects.requireNonNull(patientSubject);
        Objects.requireNonNull(sessionId);
        // One PostgreSQL SELECT observes session, patient binding, and current assignment
        // under one statement snapshot; no session or assignment write lock is acquired.
        return entityManager.createNativeQuery("""
                        SELECT s.status, s.lease_expires_at
                        FROM therapy_sessions s
                        JOIN clinical_patients p ON p.id = s.clinical_patient_id
                        WHERE s.id = :sessionId
                          AND p.patient_cognito_sub = :patientSubject
                          AND EXISTS (
                              SELECT 1 FROM doctor_patient_assignments a
                              WHERE a.clinical_patient_id = s.clinical_patient_id
                                AND a.doctor_cognito_sub = s.doctor_cognito_sub
                                AND a.status = 'ACTIVE'
                          )
                        """)
                .setParameter("sessionId", sessionId.value())
                .setParameter("patientSubject", patientSubject.value())
                .getResultStream()
                .map(result -> {
                    Object[] row = (Object[]) result;
                    return new TelemetryAuthorizationFacts(
                            TherapySessionStatus.valueOf((String) row[0]), instant(row[1]));
                })
                .findFirst();
    }

    private static Instant instant(Object value) {
        if (value == null) return null;
        if (value instanceof Instant instant) return instant;
        if (value instanceof OffsetDateTime dateTime) return dateTime.toInstant();
        if (value instanceof Timestamp timestamp) return timestamp.toInstant();
        throw new IllegalStateException("Unsupported session lease timestamp type");
    }
}
