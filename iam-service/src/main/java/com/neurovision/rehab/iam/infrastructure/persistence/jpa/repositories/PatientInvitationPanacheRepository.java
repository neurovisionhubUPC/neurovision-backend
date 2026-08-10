package com.neurovision.rehab.iam.infrastructure.persistence.jpa.repositories;

import com.neurovision.rehab.iam.infrastructure.persistence.jpa.entities.PatientInvitationEntity;
import io.quarkus.hibernate.orm.panache.PanacheRepositoryBase;
import jakarta.enterprise.context.ApplicationScoped;
import java.util.UUID;

@ApplicationScoped
public class PatientInvitationPanacheRepository
        implements PanacheRepositoryBase<PatientInvitationEntity, UUID> {

    public boolean existsActiveByPatientEmail(String patientEmail) {
        return count(
                        "patientEmail = ?1 and status in ('PENDING', 'SENT')",
                        patientEmail)
                > 0;
    }
}
