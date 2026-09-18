package com.neurovision.rehab.telemetry.interfaces.rest.resource;

import com.neurovision.rehab.telemetry.application.internal.queryservices.QueryHistoricalTelemetrySamplesService;
import com.neurovision.rehab.telemetry.interfaces.rest.transform.TelemetrySamplesRestAssembler;
import io.quarkus.oidc.AccessTokenCredential;
import io.quarkus.security.identity.SecurityIdentity;
import io.smallrye.common.annotation.Blocking;
import jakarta.annotation.security.RolesAllowed;
import jakarta.enterprise.context.RequestScoped;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

@Path("/api/v1/telemetry/sessions/{therapySessionId}/samples")
@Produces(MediaType.APPLICATION_JSON)
@RequestScoped
public class TelemetrySamplesController {
    private final QueryHistoricalTelemetrySamplesService queries;
    private final SecurityIdentity identity;

    public TelemetrySamplesController(QueryHistoricalTelemetrySamplesService queries,
            SecurityIdentity identity) {
        this.queries = queries;
        this.identity = identity;
    }

    @GET
    @Blocking
    @RolesAllowed({"PATIENT", "DOCTOR"})
    public Response read(@PathParam("therapySessionId") String rawSessionId,
            @QueryParam("streamId") String rawStreamId,
            @QueryParam("from") String rawFrom, @QueryParam("to") String rawTo,
            @QueryParam("limit") Integer rawLimit, @QueryParam("cursor") String cursor) {
        // Never query or reveal whether a malformed session identifier exists.
        final UUID sessionId;
        try {
            sessionId = UUID.fromString(rawSessionId);
        } catch (IllegalArgumentException invalid) {
            return error(403, "TELEMETRY_READ_FORBIDDEN");
        }
        final UUID streamId;
        final Instant from;
        final Instant to;
        try {
            streamId = UUID.fromString(rawStreamId);
            from = Instant.parse(rawFrom);
            to = Instant.parse(rawTo);
        } catch (RuntimeException invalid) {
            return error(400, "INVALID_TELEMETRY_QUERY");
        }
        var credential = identity.getCredential(AccessTokenCredential.class);
        if (credential == null || credential.getToken() == null)
            return error(401, "UNAUTHENTICATED");
        var outcome = queries.query(sessionId, streamId, from, to,
                rawLimit == null ? 100 : rawLimit, cursor, credential.getToken());
        return switch (outcome.status()) {
            case OK -> Response.ok(new TelemetrySamplesPageV1(1,
                    outcome.samples().stream().map(TelemetrySamplesRestAssembler::toResponse).toList(),
                    outcome.nextCursor())).build();
            case INVALID_REQUEST -> error(400, "INVALID_TELEMETRY_QUERY");
            case UNAUTHENTICATED -> error(401, "UNAUTHENTICATED");
            case FORBIDDEN -> error(403, "TELEMETRY_READ_FORBIDDEN");
            case UNAVAILABLE -> error(503, "TELEMETRY_READ_UNAVAILABLE");
        };
    }

    private static Response error(int status, String code) {
        return Response.status(status).entity(Map.of("code", code)).build();
    }
}
