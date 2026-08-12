package com.neurovision.rehab.iam.application.internal.queryservices;

import com.neurovision.rehab.iam.domain.model.valueobjects.CognitoSubject;
import com.neurovision.rehab.iam.domain.model.valueobjects.InvitationStatus;
import java.util.Objects;

public record DoctorPatientInvitationsQuery(
        CognitoSubject doctorSubject, int page, int size, InvitationStatus status) {

    public DoctorPatientInvitationsQuery {
        Objects.requireNonNull(doctorSubject, "Doctor subject must not be null");
        if (page < 0) {
            throw new IllegalArgumentException("Page must not be negative");
        }
        if (size < 1 || size > 100) {
            throw new IllegalArgumentException("Page size must be between 1 and 100");
        }
    }
}
