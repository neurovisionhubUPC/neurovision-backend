package com.neurovision.rehab.analytics.application.ports.out.authorization;

import com.neurovision.rehab.analytics.domain.model.valueobjects.SustainedFixationTrialContext;
import java.util.UUID;

/** Clinical must verify the original PATIENT token, session ownership, active assignment, and frozen SUSTAINED_TARGET_FIXATION policy per request. */
public interface SustainedFixationTrialAuthorizationPort {
    Decision authorize(UUID therapySessionId, String originalPatientAccessToken);

    enum Permission { PERMITTED, DENIED, INVALID_TOKEN, UNAVAILABLE }

    /** PERMITTED allows checking an existing result; mayInsert additionally requires an active, unexpired session. */
    record Decision(Permission permission, SustainedFixationTrialContext context, boolean mayInsert) {
        public Decision {
            if (permission == null || (permission == Permission.PERMITTED ? context == null : context != null || mayInsert))
                throw new IllegalArgumentException("Invalid Sustained Fixation authorization decision");
        }
        @Override public String toString() { return "SustainedFixationTrialAuthorization[redacted]"; }
    }
}
