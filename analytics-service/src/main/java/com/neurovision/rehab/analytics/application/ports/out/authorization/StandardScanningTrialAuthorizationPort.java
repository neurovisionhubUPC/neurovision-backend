package com.neurovision.rehab.analytics.application.ports.out.authorization;

import com.neurovision.rehab.analytics.domain.model.valueobjects.StandardScanningTrialContext;
import java.util.UUID;

/** Clinical must verify PATIENT ownership and frozen STANDARD_SCANNING policy on each request. */
public interface StandardScanningTrialAuthorizationPort {
    Decision authorize(UUID therapySessionId, String originalPatientAccessToken);

    enum Permission { PERMITTED, DENIED, INVALID_TOKEN, UNAVAILABLE }

    /** PERMITTED allows checking an existing result; mayInsert additionally requires an active, valid session. */
    record Decision(Permission permission, StandardScanningTrialContext context, boolean mayInsert) {
        public Decision {
            if (permission == null || (permission == Permission.PERMITTED ? context == null : context != null || mayInsert))
                throw new IllegalArgumentException("Invalid Standard Scanning authorization decision");
        }
        @Override public String toString() { return "StandardScanningTrialAuthorization[redacted]"; }
    }
}
