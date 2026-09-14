package com.neurovision.rehab.clinical.application.ports.out.persistence;

import com.neurovision.rehab.clinical.domain.model.aggregates.TherapyPlanRevision;
import com.neurovision.rehab.clinical.domain.model.valueobjects.TherapyPlanId;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface TherapyPlanRevisionRepository {
    List<TherapyPlanRevision> findEffective(TherapyPlanId planId, Instant effectiveAt);
    List<TherapyPlanRevision> findByCommandId(TherapyPlanId planId, UUID commandId);
    long findLatestSequence(TherapyPlanId planId);
    void appendAll(List<TherapyPlanRevision> revisions);
}
