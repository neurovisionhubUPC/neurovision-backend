package com.neurovision.rehab.clinical.infrastructure.persistence.jpa.repositories;

import com.neurovision.rehab.clinical.application.ports.out.authorization.HistoricalTelemetryReadAuthorizationPort;
import com.neurovision.rehab.clinical.domain.model.valueobjects.CognitoSubject;
import com.neurovision.rehab.clinical.domain.model.valueobjects.TherapySessionId;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;
import java.util.Objects;

@ApplicationScoped
public class JpaHistoricalTelemetryReadAuthorizationAdapter implements HistoricalTelemetryReadAuthorizationPort {
    private final EntityManager entityManager;

    public JpaHistoricalTelemetryReadAuthorizationAdapter(EntityManager entityManager) {
        this.entityManager = Objects.requireNonNull(entityManager);
    }

    @Override
    @Transactional(Transactional.TxType.SUPPORTS)
    public boolean permits(CognitoSubject subject, TherapySessionId sessionId, ReaderRole role) {
        // One statement snapshot sees the session, patient binding, and current doctor assignment.
        return !entityManager.createNativeQuery("""
                        SELECT 1 FROM therapy_sessions s
                        JOIN clinical_patients p ON p.id = s.clinical_patient_id
                        WHERE s.id = :sessionId
                          AND ((:role = 'PATIENT' AND p.patient_cognito_sub = :subject)
                            OR (:role = 'DOCTOR' AND s.doctor_cognito_sub = :subject
                                AND EXISTS (SELECT 1 FROM doctor_patient_assignments a
                                    WHERE a.clinical_patient_id = s.clinical_patient_id
                                      AND a.doctor_cognito_sub = :subject AND a.status = 'ACTIVE')))
                        """)
                .setParameter("sessionId", sessionId.value())
                .setParameter("role", role.name())
                .setParameter("subject", subject.value())
                .setMaxResults(1)
                .getResultList().isEmpty();
    }
}
