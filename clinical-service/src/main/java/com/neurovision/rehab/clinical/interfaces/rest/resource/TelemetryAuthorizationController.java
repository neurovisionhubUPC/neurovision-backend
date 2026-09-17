package com.neurovision.rehab.clinical.interfaces.rest.resource;

import com.neurovision.rehab.clinical.application.internal.queryservices.AuthorizeTelemetryIngestionQueryService;
import com.neurovision.rehab.clinical.application.internal.queryservices.TelemetryIngestionDeniedException;
import com.neurovision.rehab.clinical.domain.model.valueobjects.TherapySessionId;
import io.quarkus.security.identity.SecurityIdentity;
import jakarta.annotation.security.RolesAllowed;
import jakarta.enterprise.context.RequestScoped;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import java.util.UUID;
import org.eclipse.microprofile.jwt.JsonWebToken;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.media.Content;
import org.eclipse.microprofile.openapi.annotations.media.Schema;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponses;

@Path("/api/internal/v1/therapy-sessions")
@Produces(MediaType.APPLICATION_JSON)
@RequestScoped
public class TelemetryAuthorizationController {
    private final AuthorizeTelemetryIngestionQueryService authorization;
    private final SecurityIdentity identity;

    public TelemetryAuthorizationController(AuthorizeTelemetryIngestionQueryService authorization,
            SecurityIdentity identity) {
        this.authorization = authorization;
        this.identity = identity;
    }

    @GET
    @Path("/{therapySessionId}/telemetry-authorization")
    @RolesAllowed("PATIENT")
    @Operation(summary = "Authorize telemetry ingestion for an active therapy session",
            description = "Internal Clinical query. The original Cognito patient access token is validated independently; network isolation depends on deployment routing.")
    @APIResponses({
        @APIResponse(responseCode = "200", description = "Ingestion permitted",
                content = @Content(mediaType = MediaType.APPLICATION_JSON,
                        schema = @Schema(implementation = TelemetryAuthorizationResponse.class))),
        @APIResponse(responseCode = "401", description = "Missing or invalid access token"),
        @APIResponse(responseCode = "403", description = "Ingestion is not permitted",
                content = @Content(mediaType = MediaType.APPLICATION_JSON,
                        schema = @Schema(implementation = TelemetryAuthorizationError.class)))
    })
    public TelemetryAuthorizationResponse authorize(@PathParam("therapySessionId") String rawSessionId) {
        if (!(identity.getPrincipal() instanceof JsonWebToken token)) {
            throw new TelemetryIngestionDeniedException();
        }
        final TherapySessionId sessionId;
        try {
            sessionId = new TherapySessionId(UUID.fromString(rawSessionId));
        } catch (IllegalArgumentException invalidId) {
            throw new TelemetryIngestionDeniedException();
        }
        var authorizedId = authorization.authorize(token.getSubject(), sessionId);
        return new TelemetryAuthorizationResponse(1, true, authorizedId.value());
    }
}
