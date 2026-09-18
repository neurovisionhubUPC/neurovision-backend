package com.neurovision.rehab.clinical.interfaces.rest.resource;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

public record PatientTherapySessionResponse(
        UUID therapySessionId, UUID therapyPlanId, String exerciseCode, String status,
        LocalDate quotaDate, UUID configurationId, long configurationVersion,
        int parameterSchemaVersion, JsonNode parameters,
        int minimumValidDurationMinutes, int maximumSessionDurationMinutes,
        long minimumValidSampleCount, Instant startedAt, Instant leaseExpiresAt) {}
