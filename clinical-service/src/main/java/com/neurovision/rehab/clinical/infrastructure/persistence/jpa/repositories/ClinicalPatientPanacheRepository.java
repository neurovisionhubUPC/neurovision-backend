package com.neurovision.rehab.clinical.infrastructure.persistence.jpa.repositories;

import com.neurovision.rehab.clinical.infrastructure.persistence.jpa.entities.ClinicalPatientEntity;
import io.quarkus.hibernate.orm.panache.PanacheRepositoryBase;
import jakarta.enterprise.context.ApplicationScoped;
import java.util.UUID;

@ApplicationScoped
public class ClinicalPatientPanacheRepository
        implements PanacheRepositoryBase<ClinicalPatientEntity, UUID> {

    public ClinicalPatientEntity findByPatientSubject(String patientSubject) {
        return find("patientSubject", patientSubject).firstResult();
    }
}
