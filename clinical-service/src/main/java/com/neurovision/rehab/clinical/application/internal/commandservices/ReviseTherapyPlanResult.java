package com.neurovision.rehab.clinical.application.internal.commandservices;

import com.neurovision.rehab.clinical.domain.model.aggregates.TherapyPlanRevision;
import java.util.List;

public record ReviseTherapyPlanResult(boolean idempotentReplay, List<TherapyPlanRevision> revisions) {
    public ReviseTherapyPlanResult {
        revisions = List.copyOf(revisions);
    }
}
