package com.neurovision.rehab.clinical.infrastructure.persistence.jpa.repositories;

import com.neurovision.rehab.clinical.application.ports.out.persistence.ClinicalPatientRepository;
import com.neurovision.rehab.clinical.domain.model.aggregates.ClinicalPatient;
import com.neurovision.rehab.clinical.domain.model.valueobjects.CognitoSubject;
import com.neurovision.rehab.clinical.infrastructure.persistence.jpa.entities.ClinicalPatientEntity;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.Transactional;
import java.util.Objects;
import java.util.Optional;

@ApplicationScoped
public class JpaClinicalPatientRepository implements ClinicalPatientRepository {

    private final ClinicalPatientPanacheRepository entities;

    public JpaClinicalPatientRepository(ClinicalPatientPanacheRepository entities) {
        this.entities = Objects.requireNonNull(entities);
    }

    @Override
    @Transactional(Transactional.TxType.SUPPORTS)
    public Optional<ClinicalPatient> findByPatientSubject(CognitoSubject patientSubject) {
        Objects.requireNonNull(patientSubject, "Patient subject must not be null");
        return Optional.ofNullable(entities.findByPatientSubject(patientSubject.value()))
                .map(ClinicalPatientEntity::toDomain);
    }

    @Override
    @Transactional(Transactional.TxType.REQUIRED)
    public void save(ClinicalPatient patient) {
        Objects.requireNonNull(patient, "Clinical patient must not be null");
        entities.findByIdOptional(patient.id().value()).ifPresentOrElse(
                entity -> entity.updateFrom(patient),
                () -> entities.persist(ClinicalPatientEntity.fromDomain(patient)));
        entities.flush();
    }
}
