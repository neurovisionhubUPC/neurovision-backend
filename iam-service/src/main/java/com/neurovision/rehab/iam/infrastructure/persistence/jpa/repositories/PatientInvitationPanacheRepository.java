package com.neurovision.rehab.iam.infrastructure.persistence.jpa.repositories;

import com.neurovision.rehab.iam.infrastructure.persistence.jpa.entities.PatientInvitationEntity;
import io.quarkus.hibernate.orm.panache.PanacheRepositoryBase;
import io.quarkus.panache.common.Page;
import io.quarkus.panache.common.Sort;
import jakarta.enterprise.context.ApplicationScoped;
import java.util.UUID;

@ApplicationScoped
public class PatientInvitationPanacheRepository
        implements PanacheRepositoryBase<PatientInvitationEntity, UUID> {

    public boolean existsActiveByPatientEmail(String patientEmail) {
        return count(
                        "patientEmail = ?1 and status in ('PENDING', 'INVITED', 'ACTIVATED')",
                        patientEmail)
                > 0;
    }

    public io.quarkus.hibernate.orm.panache.PanacheQuery<PatientInvitationEntity> findByDoctor(
            String doctorSubject, String status, int page, int size) {
        var sort = Sort.descending("createdAt").and("id");
        var query = status == null
                ? find("doctorCognitoSub", sort, doctorSubject)
                : find("doctorCognitoSub = ?1 and status = ?2", sort, doctorSubject, status);
        return query.page(Page.of(page, size));
    }
}
