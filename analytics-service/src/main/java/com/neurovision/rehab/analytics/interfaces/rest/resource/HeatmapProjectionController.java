package com.neurovision.rehab.analytics.interfaces.rest.resource;

import com.neurovision.rehab.analytics.application.internal.queryservices.QueryHeatmapProjectionService;
import com.neurovision.rehab.analytics.interfaces.rest.transform.HeatmapProjectionRestAssembler;
import io.quarkus.oidc.AccessTokenCredential;
import io.quarkus.security.identity.SecurityIdentity;
import io.smallrye.common.annotation.Blocking;
import io.smallrye.mutiny.Uni;
import jakarta.annotation.security.RolesAllowed;
import jakarta.enterprise.context.RequestScoped;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.util.Map;
import java.util.UUID;

@Path("/api/v1/analytics/sessions/{therapySessionId}/streams/{streamId}/heatmap")
@Produces(MediaType.APPLICATION_JSON)
@RequestScoped
public class HeatmapProjectionController {
    private final QueryHeatmapProjectionService queries;
    private final SecurityIdentity identity;

    public HeatmapProjectionController(QueryHeatmapProjectionService queries, SecurityIdentity identity) {
        this.queries = queries;
        this.identity = identity;
    }

    @GET
    @Blocking
    @RolesAllowed({"PATIENT", "DOCTOR"})
    public Uni<Response> read(@PathParam("therapySessionId") String rawSessionId,
            @PathParam("streamId") String rawStreamId) {
        final UUID sessionId;
        try {
            sessionId = UUID.fromString(rawSessionId);
        } catch (RuntimeException invalid) {
            return response(403, "HEATMAP_READ_FORBIDDEN");
        }
        final UUID streamId;
        try {
            streamId = UUID.fromString(rawStreamId);
        } catch (RuntimeException invalid) {
            return response(404, "HEATMAP_NOT_FOUND");
        }
        var credential = identity.getCredential(AccessTokenCredential.class);
        if (credential == null || credential.getToken() == null || credential.getToken().isBlank()) {
            return response(401, "UNAUTHENTICATED");
        }
        return queries.query(sessionId, streamId, credential.getToken()).map(HeatmapProjectionController::toResponse);
    }

    static Response toResponse(QueryHeatmapProjectionService.Outcome outcome) {
        return switch (outcome.status()) {
            case OK -> Response.ok(HeatmapProjectionRestAssembler.toResponse(outcome.projection())).build();
            case UNAUTHENTICATED -> error(401, "UNAUTHENTICATED");
            case FORBIDDEN -> error(403, "HEATMAP_READ_FORBIDDEN");
            case NOT_FOUND -> error(404, "HEATMAP_NOT_FOUND");
            case UNAVAILABLE -> error(503, "HEATMAP_READ_UNAVAILABLE");
        };
    }

    private static Uni<Response> response(int status, String code) {
        return Uni.createFrom().item(error(status, code));
    }

    private static Response error(int status, String code) {
        return Response.status(status).entity(Map.of("code", code)).build();
    }
}
