package com.neurovision.rehab.analytics.application.ports.out.authorization;

import com.neurovision.rehab.analytics.domain.model.valueobjects.StandardScanningTrialContext;
import java.util.UUID;

/** Fresh Clinical-owned DOCTOR authorship and active patient assignment, not PATIENT admission. */
public interface StandardScanningTrialSummaryAuthorizationPort {
    /** Forward the original DOCTOR token; return the session's frozen policy even after session closure. */
    Decision authorize(UUID therapySessionId, String originalDoctorAccessToken);

    enum Permission { PERMITTED, DENIED, INVALID_TOKEN, UNAVAILABLE }

    record Decision(Permission permission, StandardScanningTrialContext context) {
        public Decision {
            if (permission == null || (permission == Permission.PERMITTED ? context == null : context != null))
                throw new IllegalArgumentException("Invalid Standard Scanning summary authorization");
        }
        @Override public String toString() { return "StandardScanningSummaryAuthorization[redacted]"; }
    }
}
