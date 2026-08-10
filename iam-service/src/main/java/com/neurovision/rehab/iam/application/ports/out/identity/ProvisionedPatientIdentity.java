package com.neurovision.rehab.iam.application.ports.out.identity;

import com.neurovision.rehab.iam.domain.model.valueobjects.CognitoSubject;
import java.util.Objects;

public record ProvisionedPatientIdentity(CognitoSubject patientSubject) {

    public ProvisionedPatientIdentity {
        Objects.requireNonNull(patientSubject, "Patient subject must not be null");
    }
}
