package com.neurovision.rehab.iam.application.ports.out.persistence;

import com.neurovision.rehab.iam.application.ports.out.messaging.PatientAccountInvitedV1;
import com.neurovision.rehab.iam.domain.model.aggregates.PatientInvitation;
import com.neurovision.rehab.iam.domain.model.valueobjects.EmailAddress;
import com.neurovision.rehab.iam.domain.model.valueobjects.InvitationId;
import java.util.List;
import java.util.Optional;

public interface PatientInvitationRepository {

    boolean existsActiveByPatientEmail(EmailAddress patientEmail);

    Optional<PatientInvitation> findById(InvitationId invitationId);

    void save(PatientInvitation invitation);

    void saveWithOutboxEvents(PatientInvitation invitation, List<PatientAccountInvitedV1> events);
}
