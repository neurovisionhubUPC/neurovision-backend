package com.neurovision.rehab.iam.application.ports.out.persistence;

import com.neurovision.rehab.iam.domain.model.aggregates.PatientInvitation;
import com.neurovision.rehab.iam.domain.model.valueobjects.EmailAddress;

public interface PatientInvitationRepository {

    boolean existsActiveByPatientEmail(EmailAddress patientEmail);

    void save(PatientInvitation invitation);
}
