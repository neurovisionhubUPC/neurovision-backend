package com.neurovision.rehab.clinical.interfaces.rest.resource;

import java.util.Map;
import java.util.UUID;

public record TherapyPlanResponse(
        UUID therapyPlanId,
        UUID clinicalPatientId,
        String status,
        long configurationVersion,
        int dailySessionLimit,
        int dailyTargetDurationMinutes,
        Integer dailyHardSafetyLimitMinutes,
        int minimumValidDurationMinutes,
        int maximumSessionDurationMinutes,
        long minimumValidSampleCount,
        Map<String, Integer> dailyExercisePrescriptions) {}
