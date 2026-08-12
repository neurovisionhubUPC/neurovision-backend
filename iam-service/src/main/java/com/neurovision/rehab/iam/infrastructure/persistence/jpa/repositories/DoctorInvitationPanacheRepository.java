package com.neurovision.rehab.iam.infrastructure.persistence.jpa.repositories;

import com.neurovision.rehab.iam.infrastructure.persistence.jpa.entities.DoctorInvitationEntity;
import io.quarkus.hibernate.orm.panache.PanacheRepositoryBase;
import jakarta.enterprise.context.ApplicationScoped;
import java.util.Optional;
import java.util.UUID;

@ApplicationScoped
public class DoctorInvitationPanacheRepository
        implements PanacheRepositoryBase<DoctorInvitationEntity, UUID> {

    public boolean existsActiveByDoctorEmail(String doctorEmail) {
        return count(
                        "doctorEmail = ?1 and status in ('PENDING', 'INVITED', 'ACTIVATED')",
                        doctorEmail)
                > 0;
    }

    public Optional<DoctorInvitationEntity> findByDoctorSubject(String doctorSubject) {
        return find("doctorCognitoSub", doctorSubject).firstResultOptional();
    }
}
