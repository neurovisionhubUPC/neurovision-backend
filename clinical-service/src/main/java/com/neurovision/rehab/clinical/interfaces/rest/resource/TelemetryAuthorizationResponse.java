package com.neurovision.rehab.clinical.interfaces.rest.resource;

import java.util.UUID;

public record TelemetryAuthorizationResponse(int contractVersion, boolean permitted,
        UUID therapySessionId) {}
