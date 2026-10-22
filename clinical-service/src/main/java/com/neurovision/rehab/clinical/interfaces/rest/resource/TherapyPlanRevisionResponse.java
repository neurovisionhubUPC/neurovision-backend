package com.neurovision.rehab.clinical.interfaces.rest.resource;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.quarkus.runtime.annotations.RegisterForReflection;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Explicit response whitelist; never expose the revision's identity-provider subject or fingerprint. */
@RegisterForReflection(targets = {TherapyPlanRevisionResponse.Result.class,
        TherapyPlanRevisionResponse.Revision.class, TherapyPlanRevisionResponse.Changes.class})
public final class TherapyPlanRevisionResponse {
    private TherapyPlanRevisionResponse() {}

    public record Result(UUID commandId, UUID therapyPlanId, boolean idempotentReplay, List<Revision> revisions) {}
    public record Revision(UUID revisionId, long sequence, String kind, Instant effectiveFrom, Changes changes) {}
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Changes(Integer dailySessionLimit, Integer dailyTargetDurationMinutes,
            Integer dailyHardSafetyLimitMinutes, Map<String, Integer> dailyExercisePrescriptions,
            String status, String clinicalReason) {}
}
