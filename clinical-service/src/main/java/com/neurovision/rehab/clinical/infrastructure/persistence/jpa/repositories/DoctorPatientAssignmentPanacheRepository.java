package com.neurovision.rehab.clinical.infrastructure.persistence.jpa.repositories;

import com.neurovision.rehab.clinical.infrastructure.persistence.jpa.entities.DoctorPatientAssignmentEntity;
import io.quarkus.hibernate.orm.panache.PanacheRepositoryBase;
import jakarta.enterprise.context.ApplicationScoped;
import java.util.UUID;

@ApplicationScoped
public class DoctorPatientAssignmentPanacheRepository
        implements PanacheRepositoryBase<DoctorPatientAssignmentEntity, UUID> {

    public DoctorPatientAssignmentEntity findActive(UUID patientId, String doctorSubject) {
        return find(
                        "clinicalPatientId = ?1 and doctorSubject = ?2 and status = 'ACTIVE'",
                        patientId,
                        doctorSubject)
                .firstResult();
    }
}
