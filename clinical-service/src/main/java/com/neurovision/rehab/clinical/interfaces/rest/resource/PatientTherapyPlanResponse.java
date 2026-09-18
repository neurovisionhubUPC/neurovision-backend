package com.neurovision.rehab.clinical.interfaces.rest.resource;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public record PatientTherapyPlanResponse(
        UUID therapyPlanId,
        String status,
        int dailySessionLimit,
        int dailyTargetDurationMinutes,
        Integer dailyHardSafetyLimitMinutes,
        int minimumValidDurationMinutes,
        int maximumSessionDurationMinutes,
        long minimumValidSampleCount,
        Map<String, Integer> dailyExercisePrescriptions,
        List<EffectiveConfiguration> effectiveConfigurations) {

    public record EffectiveConfiguration(UUID configurationId, String exerciseCode,
            long version, int parameterSchemaVersion, Instant effectiveFrom, JsonNode parameters) {}
}
