package com.neurovision.rehab.analytics.application.ports.out.authorization;

import com.neurovision.rehab.analytics.domain.model.valueobjects.VisualSearchTrialContext;
import java.util.UUID;

/** Clinical-owned per-request permission and frozen policy; never an authorization cache. */
public interface VisualSearchTrialAuthorizationPort {
    Decision authorize(UUID therapySessionId, String originalPatientAccessToken);

    enum Permission { PERMITTED, DENIED, INVALID_TOKEN, UNAVAILABLE }

    /** PERMITTED allows checking an existing result; mayInsert additionally requires an active, valid session. */
    record Decision(Permission permission, VisualSearchTrialContext context, boolean mayInsert) {
        public Decision {
            if (permission == null || (permission == Permission.PERMITTED ? context == null : context != null || mayInsert))
                throw new IllegalArgumentException("Invalid Visual Search authorization decision");
        }
        @Override public String toString() { return "VisualSearchTrialAuthorization[redacted]"; }
    }
}
