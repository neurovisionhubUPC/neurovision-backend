package com.neurovision.rehab.clinical.application.ports.out.authorization;

import com.neurovision.rehab.clinical.domain.model.valueobjects.CognitoSubject;

/** Requires the authenticated doctor to have an active profile and a syntactically valid Peru CMP. */
@FunctionalInterface
public interface DoctorSchedulingEligibilityPort {
    void requireEligible(CognitoSubject doctor);
}
