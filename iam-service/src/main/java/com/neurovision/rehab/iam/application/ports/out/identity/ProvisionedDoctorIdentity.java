package com.neurovision.rehab.iam.application.ports.out.identity;

import com.neurovision.rehab.iam.domain.model.valueobjects.CognitoSubject;
import java.util.Objects;

public record ProvisionedDoctorIdentity(CognitoSubject doctorSubject) {

    public ProvisionedDoctorIdentity {
        Objects.requireNonNull(doctorSubject, "Doctor subject must not be null");
    }
}
