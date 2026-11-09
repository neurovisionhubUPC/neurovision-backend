package com.neurovision.rehab.clinical.interfaces.rest.resource;

import com.fasterxml.jackson.databind.JsonNode;
import io.quarkus.runtime.annotations.RegisterForReflection;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

@RegisterForReflection
public record PatientTherapySessionDetailResponse(UUID therapySessionId, UUID therapyPlanId,
        String exerciseCode, String status, Instant startedAt, Instant endedAt, long activeDurationSeconds,
        LocalDate quotaDate, UUID configurationId, long configurationVersion, int parameterSchemaVersion,
        JsonNode parameters, SessionLimits sessionLimits, TechnicalProgress technicalProgress) {
    @RegisterForReflection
    public record SessionLimits(int minimumValidDurationMinutes, int maximumSessionDurationMinutes,
            long minimumValidSampleCount) {}
    @RegisterForReflection
    public record TechnicalProgress(String criterion, long target, long activeMilliseconds,
            long completedRepetitions, boolean targetReached) {}
    @Override public String toString() { return "PatientTherapySessionDetailResponse[redacted]"; }
}
