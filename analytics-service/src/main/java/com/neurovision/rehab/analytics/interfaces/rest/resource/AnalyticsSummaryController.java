package com.neurovision.rehab.analytics.interfaces.rest.resource;

import com.neurovision.rehab.analytics.application.internal.queryservices.QueryAnalyticsSummaryService;
import com.neurovision.rehab.analytics.interfaces.rest.transform.AnalyticsSummaryRestAssembler;
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
import org.eclipse.microprofile.openapi.annotations.media.Content;
import org.eclipse.microprofile.openapi.annotations.media.Schema;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponses;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;

@Path("/api/v1/analytics/sessions/{therapySessionId}/streams/{streamId}/summary")
@Produces(MediaType.APPLICATION_JSON)
@RequestScoped
@Tag(name = "Analytics queries", description = "Authorized, versioned reads of persisted Analytics projections")
public class AnalyticsSummaryController {
    private final QueryAnalyticsSummaryService queries;
    private final SecurityIdentity identity;

    public AnalyticsSummaryController(QueryAnalyticsSummaryService queries, SecurityIdentity identity) {
        this.queries = queries;
        this.identity = identity;
    }

    @GET
    @Blocking
    @RolesAllowed({"PATIENT", "DOCTOR"})
    @Operation(operationId = "getAnalyticsSummary", summary = "Read the baseline summary projection",
            description = "Requires a Cognito/OIDC PATIENT or DOCTOR role. Clinical authorizes the therapy session before MongoDB is queried. The response reports its contract, calculation version, revision, and source completeness.")
    @APIResponses({
            @APIResponse(responseCode = "200", description = "Persisted baseline summary projection",
                    content = @Content(schema = @Schema(implementation = AnalyticsSummaryResponse.class))),
            @APIResponse(responseCode = "401", description = "UNAUTHENTICATED"),
            @APIResponse(responseCode = "403", description = "SUMMARY_READ_FORBIDDEN"),
            @APIResponse(responseCode = "404", description = "SUMMARY_NOT_FOUND"),
            @APIResponse(responseCode = "503", description = "SUMMARY_READ_UNAVAILABLE")
    })
    public Uni<Response> read(@PathParam("therapySessionId") String rawSessionId,
            @PathParam("streamId") String rawStreamId) {
        final UUID sessionId;
        try {
            sessionId = UUID.fromString(rawSessionId);
        } catch (RuntimeException invalid) {
            return response(403, "SUMMARY_READ_FORBIDDEN");
        }
        final UUID streamId;
        try {
            streamId = UUID.fromString(rawStreamId);
        } catch (RuntimeException invalid) {
            return response(404, "SUMMARY_NOT_FOUND");
        }
        var credential = identity.getCredential(AccessTokenCredential.class);
        if (credential == null || credential.getToken() == null || credential.getToken().isBlank()) {
            return response(401, "UNAUTHENTICATED");
        }
        return queries.query(sessionId, streamId, credential.getToken()).map(AnalyticsSummaryController::toResponse);
    }

    static Response toResponse(QueryAnalyticsSummaryService.Outcome outcome) {
        return switch (outcome.status()) {
            case OK -> Response.ok(AnalyticsSummaryRestAssembler.toResponse(outcome.projection())).build();
            case UNAUTHENTICATED -> error(401, "UNAUTHENTICATED");
            case FORBIDDEN -> error(403, "SUMMARY_READ_FORBIDDEN");
            case NOT_FOUND -> error(404, "SUMMARY_NOT_FOUND");
            case UNAVAILABLE -> error(503, "SUMMARY_READ_UNAVAILABLE");
        };
    }

    private static Uni<Response> response(int status, String code) {
        return Uni.createFrom().item(error(status, code));
    }

    private static Response error(int status, String code) {
        return Response.status(status).entity(Map.of("code", code)).build();
    }
}
