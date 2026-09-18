package com.neurovision.rehab.clinical.interfaces.rest.resource;

import java.util.Map;

public record CreateTherapyPlanRequest(
        int dailySessionLimit,
        int dailyTargetDurationMinutes,
        Integer dailyHardSafetyLimitMinutes,
        int minimumValidDurationMinutes,
        int maximumSessionDurationMinutes,
        long minimumValidSampleCount,
        Map<String, Integer> dailyExercisePrescriptions) {}
