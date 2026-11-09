package com.neurovision.rehab.analytics.application.ports.out.authorization;

import com.neurovision.rehab.analytics.domain.model.valueobjects.SystematicExplorationTrialContext;
import java.util.UUID;

/** Clinical must verify PATIENT ownership and frozen SYSTEMATIC_EXPLORATION policy on each request. */
public interface SystematicExplorationTrialAuthorizationPort {
    Decision authorize(UUID therapySessionId, String originalPatientAccessToken);

    enum Permission { PERMITTED, DENIED, INVALID_TOKEN, UNAVAILABLE }

    /** PERMITTED allows checking an existing result; mayInsert additionally requires an active, valid session. */
    record Decision(Permission permission, SystematicExplorationTrialContext context, boolean mayInsert) {
        public Decision {
            if (permission == null || (permission == Permission.PERMITTED ? context == null : context != null || mayInsert))
                throw new IllegalArgumentException("Invalid Systematic Exploration authorization decision");
        }
        @Override public String toString() { return "SystematicExplorationTrialAuthorization[redacted]"; }
    }
}
