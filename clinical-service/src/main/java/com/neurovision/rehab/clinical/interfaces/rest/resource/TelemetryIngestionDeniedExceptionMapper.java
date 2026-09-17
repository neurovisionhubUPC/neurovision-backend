package com.neurovision.rehab.clinical.interfaces.rest.resource;

import com.neurovision.rehab.clinical.application.internal.queryservices.TelemetryIngestionDeniedException;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.ExceptionMapper;
import jakarta.ws.rs.ext.Provider;

@Provider
public class TelemetryIngestionDeniedExceptionMapper
        implements ExceptionMapper<TelemetryIngestionDeniedException> {
    @Override
    public Response toResponse(TelemetryIngestionDeniedException ignored) {
        return Response.status(Response.Status.FORBIDDEN)
                .type(MediaType.APPLICATION_JSON)
                .entity(new TelemetryAuthorizationError(
                        "TELEMETRY_INGESTION_FORBIDDEN", "Telemetry ingestion is not permitted"))
                .build();
    }
}
