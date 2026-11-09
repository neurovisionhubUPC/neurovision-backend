package com.neurovision.rehab.analytics.application.ports.out.authorization;

import com.neurovision.rehab.analytics.domain.model.valueobjects.SustainedFixationTrialContext;
import java.util.UUID;

/** Fresh Clinical-owned DOCTOR authorship and active patient assignment, not PATIENT admission. */
public interface SustainedFixationTrialSummaryAuthorizationPort {
    /**
     * Forward the original DOCTOR token. Require session authorship and active patient assignment.
     * Return the frozen policy even after session closure when the doctor is still authorized.
     */
    Decision authorize(UUID therapySessionId, String originalDoctorAccessToken);

    enum Permission { PERMITTED, DENIED, INVALID_TOKEN, UNAVAILABLE }

    record Decision(Permission permission, SustainedFixationTrialContext context) {
        public Decision {
            if (permission == null || (permission == Permission.PERMITTED ? context == null : context != null))
                throw new IllegalArgumentException("Invalid Sustained Fixation summary authorization");
        }
        @Override public String toString() { return "SustainedFixationSummaryAuthorization[redacted]"; }
    }
}
