package com.neurovision.rehab.clinical.interfaces.rest.resource;

import java.time.Instant;
import java.util.UUID;

public record TherapeuticConfigurationResponse(
        UUID configurationId,
        UUID therapyPlanId,
        String exerciseCode,
        long version,
        int parameterSchemaVersion,
        Instant effectiveFrom,
        String status) {}
