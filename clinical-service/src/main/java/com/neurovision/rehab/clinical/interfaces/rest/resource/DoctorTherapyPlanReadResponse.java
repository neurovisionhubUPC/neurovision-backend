package com.neurovision.rehab.clinical.interfaces.rest.resource;

import com.fasterxml.jackson.databind.JsonNode;
import io.quarkus.runtime.annotations.RegisterForReflection;
import java.time.Instant;
import java.util.*;

/** Public read representations deliberately omit identity-provider subjects. */
@RegisterForReflection(targets = {
        DoctorTherapyPlanReadResponse.Summary.class, DoctorTherapyPlanReadResponse.Page.class,
        DoctorTherapyPlanReadResponse.Configuration.class, DoctorTherapyPlanReadResponse.Detail.class
})
public final class DoctorTherapyPlanReadResponse {
    private DoctorTherapyPlanReadResponse() {}
    public record Summary(UUID therapyPlanId, String status, long configurationVersion) {}
    public record Page(UUID clinicalPatientId, List<Summary> plans, int page, int size, long total) {}
    public record Configuration(UUID configurationId, UUID therapyPlanId, String exerciseCode, long version,
            int parameterSchemaVersion, Instant effectiveFrom, String status, JsonNode parameters) {}
    public record Detail(UUID therapyPlanId, UUID clinicalPatientId, String status, long configurationVersion,
            int dailySessionLimit, int dailyTargetDurationMinutes, Integer dailyHardSafetyLimitMinutes,
            int minimumValidDurationMinutes, int maximumSessionDurationMinutes, long minimumValidSampleCount,
            Map<String,Integer> dailyExercisePrescriptions, List<Configuration> configurations) {}
}
