package com.neurovision.rehab.clinical.interfaces.rest.resource;

import com.neurovision.rehab.clinical.application.internal.queryservices.HistoricalTelemetryReadDeniedException;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.ExceptionMapper;
import jakarta.ws.rs.ext.Provider;

@Provider
public class HistoricalTelemetryReadDeniedExceptionMapper
        implements ExceptionMapper<HistoricalTelemetryReadDeniedException> {
    @Override
    public Response toResponse(HistoricalTelemetryReadDeniedException ignored) {
        return Response.status(Response.Status.FORBIDDEN)
                .type(MediaType.APPLICATION_JSON)
                .entity(new TelemetryAuthorizationError(
                        "TELEMETRY_READ_FORBIDDEN", "Historical telemetry read is not permitted"))
                .build();
    }
}
