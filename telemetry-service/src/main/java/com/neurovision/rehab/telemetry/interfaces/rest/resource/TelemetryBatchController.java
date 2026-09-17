package com.neurovision.rehab.telemetry.interfaces.rest.resource;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.neurovision.rehab.telemetry.application.internal.commandservices.IngestTelemetryBatchCommandService;
import com.neurovision.rehab.telemetry.interfaces.rest.transform.TelemetryBatchRestAssembler;
import io.quarkus.oidc.AccessTokenCredential;
import io.quarkus.security.identity.SecurityIdentity;
import io.opentelemetry.api.trace.Span;
import io.smallrye.common.annotation.Blocking;
import jakarta.annotation.security.RolesAllowed;
import jakarta.enterprise.context.RequestScoped;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.util.Map;

@Path("/api/v1/telemetry/batches")
@Consumes(MediaType.APPLICATION_JSON)
@Produces(MediaType.APPLICATION_JSON)
@RequestScoped
public class TelemetryBatchController {
    private final IngestTelemetryBatchCommandService ingestion;
    private final SecurityIdentity identity;
    private final ObjectMapper mapper;

    public TelemetryBatchController(IngestTelemetryBatchCommandService ingestion,
            SecurityIdentity identity, ObjectMapper mapper) {
        this.ingestion = ingestion;
        this.identity = identity;
        this.mapper = mapper;
    }

    @POST
    @Blocking
    @RolesAllowed("PATIENT")
    public Response ingest(String body) {
        var credential = identity.getCredential(AccessTokenCredential.class);
        if (credential == null || credential.getToken() == null) {
            return error(401, "UNAUTHENTICATED");
        }
        final TelemetryBatchV1 request;
        try {
            request = mapper.readerFor(TelemetryBatchV1.class)
                    .with(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                    .readValue(body);
        } catch (Exception invalidJson) {
            return error(400, "INVALID_BATCH");
        }
        var context = Span.current().getSpanContext();
        var traceId = context.isValid() ? context.getTraceId() : null;
        var outcome = ingestion.ingest(TelemetryBatchRestAssembler.toDomain(request), credential.getToken(), traceId);
        return switch (outcome.status()) {
            case ACCEPTED -> Response.status(202).entity(Map.of("code", "BROKER_ACCEPTED")).build();
            case INVALID_BATCH -> error(400, outcome.rejectionCategory().name());
            case UNAUTHENTICATED -> error(401, "UNAUTHENTICATED");
            case FORBIDDEN -> error(403, "TELEMETRY_INGESTION_FORBIDDEN");
            case UNAVAILABLE -> error(503, "TELEMETRY_INGESTION_UNAVAILABLE");
            case RATE_LIMITED -> error(429, "TELEMETRY_INGESTION_RATE_LIMITED");
        };
    }

    private static Response error(int status, String code) {
        return Response.status(status).entity(Map.of("code", code)).build();
    }
}
