package com.neurovision.rehab.clinical.infrastructure.persistence.jpa.repositories;

import com.neurovision.rehab.clinical.application.ports.out.persistence.DoctorPatientAssignmentRepository;
import com.neurovision.rehab.clinical.domain.model.aggregates.DoctorPatientAssignment;
import com.neurovision.rehab.clinical.domain.model.valueobjects.ClinicalPatientId;
import com.neurovision.rehab.clinical.domain.model.valueobjects.CognitoSubject;
import com.neurovision.rehab.clinical.infrastructure.persistence.jpa.entities.DoctorPatientAssignmentEntity;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.Transactional;
import java.util.Objects;
import java.util.Optional;

@ApplicationScoped
public class JpaDoctorPatientAssignmentRepository implements DoctorPatientAssignmentRepository {

    private final DoctorPatientAssignmentPanacheRepository entities;

    public JpaDoctorPatientAssignmentRepository(DoctorPatientAssignmentPanacheRepository entities) {
        this.entities = Objects.requireNonNull(entities);
    }

    @Override
    @Transactional(Transactional.TxType.SUPPORTS)
    public Optional<DoctorPatientAssignment> findActiveByClinicalPatientIdAndDoctorSubject(
            ClinicalPatientId clinicalPatientId, CognitoSubject doctorSubject) {
        Objects.requireNonNull(clinicalPatientId, "Clinical patient ID must not be null");
        Objects.requireNonNull(doctorSubject, "Doctor subject must not be null");
        return Optional.ofNullable(
                        entities.findActive(clinicalPatientId.value(), doctorSubject.value()))
                .map(DoctorPatientAssignmentEntity::toDomain);
    }

    @Override
    @Transactional(Transactional.TxType.REQUIRED)
    public void save(DoctorPatientAssignment assignment) {
        Objects.requireNonNull(assignment, "Doctor-patient assignment must not be null");
        entities.findByIdOptional(assignment.id().value()).ifPresentOrElse(
                entity -> entity.updateFrom(assignment),
                () -> entities.persist(DoctorPatientAssignmentEntity.fromDomain(assignment)));
        entities.flush();
    }
}
