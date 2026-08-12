package com.neurovision.rehab.iam.application.ports.out.persistence;

import com.neurovision.rehab.iam.domain.model.aggregates.DoctorInvitation;
import com.neurovision.rehab.iam.domain.model.valueobjects.CognitoSubject;
import com.neurovision.rehab.iam.domain.model.valueobjects.EmailAddress;
import com.neurovision.rehab.iam.domain.model.valueobjects.InvitationId;
import java.util.Optional;

public interface DoctorInvitationRepository {

    boolean existsActiveByDoctorEmail(EmailAddress doctorEmail);

    Optional<DoctorInvitation> findById(InvitationId invitationId);

    Optional<DoctorInvitation> findByDoctorSubject(CognitoSubject doctorSubject);

    void save(DoctorInvitation invitation);
}
