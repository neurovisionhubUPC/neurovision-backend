package com.neurovision.rehab.analytics.interfaces.rest.resource;

import com.neurovision.rehab.analytics.application.internal.queryservices.QueryGazePlotPageService;
import com.neurovision.rehab.analytics.application.internal.queryservices.QueryGazePlotPageService.Outcome;
import com.neurovision.rehab.analytics.application.internal.queryservices.QueryGazePlotPageService.Status;
import com.neurovision.rehab.analytics.domain.services.GazePlotPageAssembler;
import com.neurovision.rehab.analytics.interfaces.rest.transform.GazePlotPageRestAssembler;
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

@Path("/api/v1/analytics/sessions/{therapySessionId}/streams/{streamId}/gaze-plot")
@Produces(MediaType.APPLICATION_JSON)
@RequestScoped
public class GazePlotPageController {
    private final QueryGazePlotPageService queries;
    private final SecurityIdentity identity;

    public GazePlotPageController(QueryGazePlotPageService queries, SecurityIdentity identity) {
        this.queries = queries;
        this.identity = identity;
    }

    @GET
    @Blocking
    @RolesAllowed({"PATIENT", "DOCTOR"})
    public Uni<Response> read(@PathParam("therapySessionId") String rawSessionId,
            @PathParam("streamId") String rawStreamId, @QueryParam("pageSize") String rawPageSize,
            @QueryParam("cursor") String cursor) {
        final UUID sessionId;
        final UUID streamId;
        final int pageSize;
        try {
            sessionId = UUID.fromString(rawSessionId);
            streamId = UUID.fromString(rawStreamId);
            pageSize = parsePageSize(rawPageSize);
            if (cursor != null && (cursor.isBlank() || cursor.length() > 1024)) {
                throw new IllegalArgumentException("Invalid cursor");
            }
        } catch (RuntimeException invalid) {
            return response(400, "GAZE_PLOT_READ_INVALID_REQUEST");
        }
        var credential = identity.getCredential(AccessTokenCredential.class);
        if (credential == null || credential.getToken() == null || credential.getToken().isBlank()) {
            return response(401, "UNAUTHENTICATED");
        }
        return queries.query(sessionId, streamId, pageSize, cursor, credential.getToken())
                .map(GazePlotPageController::toResponse);
    }

    static int parsePageSize(String rawPageSize) {
        if (rawPageSize == null) return QueryGazePlotPageService.DEFAULT_PAGE_SIZE;
        if (!rawPageSize.matches("[0-9]{1,3}")) throw new IllegalArgumentException("Invalid page size");
        int pageSize = Integer.parseInt(rawPageSize);
        if (pageSize < 1 || pageSize > GazePlotPageAssembler.MAX_PAGE_SIZE) {
            throw new IllegalArgumentException("Invalid page size");
        }
        return pageSize;
    }

    static Response toResponse(Outcome outcome) {
        return switch (outcome.status()) {
            case OK -> Response.ok(GazePlotPageRestAssembler.toResponse(
                    outcome.page(), outcome.nextCursorToken())).build();
            case INVALID_REQUEST -> error(400, "GAZE_PLOT_READ_INVALID_REQUEST");
            case UNAUTHENTICATED -> error(401, "UNAUTHENTICATED");
            case FORBIDDEN -> error(403, "GAZE_PLOT_READ_FORBIDDEN");
            case NOT_FOUND -> error(404, "GAZE_PLOT_NOT_FOUND");
            case UNAVAILABLE -> error(503, "GAZE_PLOT_READ_UNAVAILABLE");
        };
    }

    private static Uni<Response> response(int status, String code) {
        return Uni.createFrom().item(error(status, code));
    }

    private static Response error(int status, String code) {
        return Response.status(status).entity(Map.of("code", code)).build();
    }
}
