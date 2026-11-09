package com.neurovision.rehab.analytics.application.ports.out.authorization;

import com.neurovision.rehab.analytics.domain.model.valueobjects.CompensatorySaccadeTrialContext;
import java.util.UUID;

/** Clinical must verify PATIENT ownership and frozen COMPENSATORY_SACCADE policy on each request. */
public interface CompensatorySaccadeTrialAuthorizationPort {
    Decision authorize(UUID therapySessionId, String originalPatientAccessToken);

    enum Permission { PERMITTED, DENIED, INVALID_TOKEN, UNAVAILABLE }

    /** PERMITTED allows checking an existing result; mayInsert additionally requires an active, valid session. */
    record Decision(Permission permission, CompensatorySaccadeTrialContext context, boolean mayInsert) {
        public Decision {
            if (permission == null || (permission == Permission.PERMITTED ? context == null : context != null || mayInsert))
                throw new IllegalArgumentException("Invalid Compensatory Saccade authorization decision");
        }
        @Override public String toString() { return "CompensatorySaccadeTrialAuthorization[redacted]"; }
    }
}
