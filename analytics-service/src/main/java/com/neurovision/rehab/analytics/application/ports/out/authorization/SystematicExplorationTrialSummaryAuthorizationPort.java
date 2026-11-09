package com.neurovision.rehab.analytics.application.ports.out.authorization;

import com.neurovision.rehab.analytics.domain.model.valueobjects.SystematicExplorationTrialContext;
import java.util.UUID;

/** Fresh Clinical-owned DOCTOR authorship and active patient assignment, not PATIENT admission. */
public interface SystematicExplorationTrialSummaryAuthorizationPort {
    /** Forward the original DOCTOR token; return the session's frozen policy even after session closure. */
    Decision authorize(UUID therapySessionId, String originalDoctorAccessToken);

    enum Permission { PERMITTED, DENIED, INVALID_TOKEN, UNAVAILABLE }

    record Decision(Permission permission, SystematicExplorationTrialContext context) {
        public Decision {
            if (permission == null || (permission == Permission.PERMITTED ? context == null : context != null))
                throw new IllegalArgumentException("Invalid Systematic Exploration summary authorization");
        }
        @Override public String toString() { return "SystematicExplorationSummaryAuthorization[redacted]"; }
    }
}
