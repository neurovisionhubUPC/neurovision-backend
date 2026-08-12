package com.neurovision.rehab.iam.application.ports.out.identity;

import com.neurovision.rehab.iam.domain.model.valueobjects.EmailAddress;

public interface DoctorIdentityProvider {

    ProvisionedDoctorIdentity inviteDoctor(EmailAddress doctorEmail);
}
