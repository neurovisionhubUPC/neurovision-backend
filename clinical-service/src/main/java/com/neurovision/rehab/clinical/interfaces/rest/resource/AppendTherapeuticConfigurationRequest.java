package com.neurovision.rehab.clinical.interfaces.rest.resource;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;

public record AppendTherapeuticConfigurationRequest(
        String exerciseCode,
        int parameterSchemaVersion,
        Instant effectiveFrom,
        JsonNode parameters) {}
