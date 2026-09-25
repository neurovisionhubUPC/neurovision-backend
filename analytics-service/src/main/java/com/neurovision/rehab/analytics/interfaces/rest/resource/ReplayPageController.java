package com.neurovision.rehab.analytics.interfaces.rest.resource;

import com.neurovision.rehab.analytics.application.internal.queryservices.QueryReplayPageService;
import com.neurovision.rehab.analytics.application.internal.queryservices.QueryReplayPageService.Outcome;
import com.neurovision.rehab.analytics.application.internal.queryservices.QueryReplayPageService.Status;
import com.neurovision.rehab.analytics.domain.services.ReplayPageAssembler;
import com.neurovision.rehab.analytics.interfaces.rest.transform.ReplayPageRestAssembler;
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
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.util.Map;
import java.util.UUID;
import org.eclipse.microprofile.openapi.annotations.media.Content;
import org.eclipse.microprofile.openapi.annotations.media.Schema;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.parameters.Parameter;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponses;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;

@Path("/api/v1/analytics/sessions/{therapySessionId}/streams/{streamId}/replay")
@Produces(MediaType.APPLICATION_JSON)
@RequestScoped
@Tag(name = "Analytics queries", description = "Authorized, versioned reads of persisted Analytics projections")
public class ReplayPageController {
    private final QueryReplayPageService queries;
    private final SecurityIdentity identity;

    public ReplayPageController(QueryReplayPageService queries, SecurityIdentity identity) {
        this.queries = queries;
        this.identity = identity;
    }

    @GET
    @Blocking
    @RolesAllowed({"PATIENT", "DOCTOR"})
    @Operation(operationId = "getAnalyticsReplayPage", summary = "Read bounded independent Replay V1 lanes",
            description = "Requires a Cognito/OIDC PATIENT or DOCTOR role. Clinical authorizes the therapy session before MongoDB is queried. Gaze and gameplay use independent opaque cursors bound to the projection revision.")
    @APIResponses({
            @APIResponse(responseCode = "200", description = "Versioned Replay V1 lane pages and completeness metadata",
                    content = @Content(schema = @Schema(implementation = ReplayPageRestAssembler.ReplayResponse.class))),
            @APIResponse(responseCode = "400", description = "REPLAY_READ_INVALID_REQUEST"),
            @APIResponse(responseCode = "401", description = "UNAUTHENTICATED"),
            @APIResponse(responseCode = "403", description = "REPLAY_READ_FORBIDDEN"),
            @APIResponse(responseCode = "404", description = "REPLAY_NOT_FOUND"),
            @APIResponse(responseCode = "503", description = "REPLAY_READ_UNAVAILABLE")
    })
    public Uni<Response> read(@PathParam("therapySessionId") String rawSessionId,
            @PathParam("streamId") String rawStreamId,
            @Parameter(description = "Maximum samples per lane (1-500; default 100)")
            @QueryParam("pageSize") String rawPageSize,
            @Parameter(description = "Opaque gaze-lane continuation token") @QueryParam("gazeCursor") String gazeCursor,
            @Parameter(description = "Opaque gameplay-lane continuation token") @QueryParam("gameplayCursor") String gameplayCursor) {
        final UUID sessionId;
        final UUID streamId;
        final int pageSize;
        try {
            sessionId = UUID.fromString(rawSessionId);
            streamId = UUID.fromString(rawStreamId);
            pageSize = parsePageSize(rawPageSize);
            checkCursor(gazeCursor);
            checkCursor(gameplayCursor);
        } catch (RuntimeException invalid) { return response(400, "REPLAY_READ_INVALID_REQUEST"); }
        var credential = identity.getCredential(AccessTokenCredential.class);
        if (credential == null || credential.getToken() == null || credential.getToken().isBlank()) {
            return response(401, "UNAUTHENTICATED");
        }
        return queries.query(sessionId, streamId, pageSize, gazeCursor, gameplayCursor, credential.getToken())
                .map(ReplayPageController::toResponse);
    }

    static int parsePageSize(String raw) {
        if (raw == null) return QueryReplayPageService.DEFAULT_PAGE_SIZE;
        if (!raw.matches("[0-9]{1,3}")) throw new IllegalArgumentException("Invalid replay page size");
        int size = Integer.parseInt(raw);
        if (size < 1 || size > ReplayPageAssembler.MAX_PAGE_SIZE) throw new IllegalArgumentException("Invalid replay page size");
        return size;
    }

    private static void checkCursor(String cursor) {
        if (cursor != null && (cursor.isBlank() || cursor.length() > 1024)) throw new IllegalArgumentException("Invalid replay cursor");
    }

    static Response toResponse(Outcome outcome) {
        return switch (outcome.status()) {
            case OK -> Response.ok(ReplayPageRestAssembler.toResponse(outcome.page(), outcome.nextGazeCursor(),
                    outcome.nextGameplayCursor())).build();
            case INVALID_REQUEST -> error(400, "REPLAY_READ_INVALID_REQUEST");
            case UNAUTHENTICATED -> error(401, "UNAUTHENTICATED");
            case FORBIDDEN -> error(403, "REPLAY_READ_FORBIDDEN");
            case NOT_FOUND -> error(404, "REPLAY_NOT_FOUND");
            case UNAVAILABLE -> error(503, "REPLAY_READ_UNAVAILABLE");
        };
    }

    private static Uni<Response> response(int status, String code) { return Uni.createFrom().item(error(status, code)); }
    private static Response error(int status, String code) { return Response.status(status).entity(Map.of("code", code)).build(); }
}
