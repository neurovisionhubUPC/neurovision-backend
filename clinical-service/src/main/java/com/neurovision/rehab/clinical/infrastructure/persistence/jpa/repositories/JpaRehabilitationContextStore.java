package com.neurovision.rehab.clinical.infrastructure.persistence.jpa.repositories;

import com.neurovision.rehab.clinical.application.internal.commandservices.RehabilitationContextFailure;
import com.neurovision.rehab.clinical.application.ports.out.persistence.DoctorPatientAssignmentRepository;
import com.neurovision.rehab.clinical.application.ports.out.persistence.RehabilitationContextStore;
import com.neurovision.rehab.clinical.domain.model.valueobjects.ClinicalPatientId;
import com.neurovision.rehab.clinical.domain.model.valueobjects.CognitoSubject;
import com.neurovision.rehab.clinical.domain.model.valueobjects.ClinicalPatientStatus;
import com.neurovision.rehab.clinical.domain.model.valueobjects.RehabilitationContext;
import com.neurovision.rehab.clinical.infrastructure.persistence.jpa.entities.ClinicalPatientEntity;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import jakarta.transaction.Transactional;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

@ApplicationScoped
public class JpaRehabilitationContextStore implements RehabilitationContextStore {
    private final EntityManager em;
    private final DoctorPatientAssignmentRepository assignments;

    public JpaRehabilitationContextStore(EntityManager em, DoctorPatientAssignmentRepository assignments) {
        this.em = Objects.requireNonNull(em);
        this.assignments = Objects.requireNonNull(assignments);
    }

    @Override
    @Transactional(Transactional.TxType.REQUIRED)
    public Optional<Snapshot> readAssigned(UUID patient, String doctor) {
        var rows = em.createQuery("select p from ClinicalPatientEntity p where p.id=:id and"
                        + JpaAssignedPatientReadAdapter.SCOPE, ClinicalPatientEntity.class)
                .setParameter("id", patient).setParameter("doctor", doctor).setMaxResults(1).getResultList();
        return rows.stream().findFirst().map(this::snapshot);
    }

    @Override
    @Transactional(Transactional.TxType.REQUIRED)
    public Optional<Snapshot> replaceAssigned(UUID patient, String doctor, long expectedVersion,
            RehabilitationContext context, Instant changedAt) {
        // Keep the active assignment locked through the patient update and flush.
        if (assignments.findActiveByClinicalPatientIdAndDoctorSubjectForUpdate(
                new ClinicalPatientId(patient), new CognitoSubject(doctor)).isEmpty()) return Optional.empty();
        var entity = em.find(ClinicalPatientEntity.class, patient, LockModeType.PESSIMISTIC_WRITE);
        if (entity == null) return Optional.empty();
        if (entity.version() != expectedVersion
                || entity.toDomain().status() != ClinicalPatientStatus.ACTIVE
                || changedAt.isBefore(entity.toDomain().updatedAt())) {
            throw new RehabilitationContextFailure(RehabilitationContextFailure.Kind.CONFLICT);
        }
        if (entity.replaceRehabilitationContext(context, changedAt)) em.flush();
        return Optional.of(snapshot(entity));
    }

    private Snapshot snapshot(ClinicalPatientEntity entity) {
        var patient = entity.toDomain();
        return new Snapshot(patient.id().value(), entity.rehabilitationContext(), entity.version(), patient.updatedAt());
    }
}
