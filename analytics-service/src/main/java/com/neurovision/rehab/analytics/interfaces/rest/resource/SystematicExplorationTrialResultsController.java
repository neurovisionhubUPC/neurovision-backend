package com.neurovision.rehab.analytics.interfaces.rest.resource;

import com.neurovision.rehab.analytics.application.internal.commandservices.ReceiveSystematicExplorationTrialResultService;
import com.neurovision.rehab.analytics.interfaces.rest.transform.SystematicExplorationTrialRequestDecoder;
import io.quarkus.oidc.AccessTokenCredential;
import io.quarkus.security.identity.SecurityIdentity;
import io.smallrye.common.annotation.Blocking;
import io.smallrye.mutiny.Uni;
import jakarta.annotation.security.RolesAllowed;
import jakarta.enterprise.context.RequestScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.*;
import java.io.InputStream;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.eclipse.microprofile.openapi.annotations.Operation;

@Path("/api/v1/analytics/sessions/{therapySessionId}/systematic-exploration/trial-results")
@Consumes(MediaType.APPLICATION_JSON)
@Produces(MediaType.APPLICATION_JSON)
@RequestScoped
public class SystematicExplorationTrialResultsController {
    private final ReceiveSystematicExplorationTrialResultService service;
    private final SecurityIdentity identity;

    @Inject
    public SystematicExplorationTrialResultsController(ReceiveSystematicExplorationTrialResultService service, SecurityIdentity identity) {
        this.service = service; this.identity = identity;
    }

    @POST @Blocking @RolesAllowed("PATIENT")
    @Operation(operationId = "receiveSystematicExplorationTrialResult", summary = "Store one immutable Systematic Exploration trial result",
            description = "PATIENT only; same-token Clinical authorization and frozen configuration on every request. Six-field JSON body, at most 8192 UTF-8 bytes. New attempts require an active unexpired session; identical stored attempts may be confirmed after closure. 201 created, 200 already stored, 400 invalid result, 401 invalid token, 403 denied, 409 conflict, 413 too large, 415 unsupported media, 503 unavailable or uncertain write. No automatic write retry, no-store; producer-reported ordered visits are not independent gaze verification.")
    public Uni<Response> receive(@PathParam("therapySessionId") String rawSession, InputStream body) {
        if (identity == null || identity.isAnonymous()) return item(401, "INVALID_TOKEN");
        if (!Set.of("PATIENT").equals(identity.getRoles())) return item(403, "DENIED");
        var credential = identity.getCredential(AccessTokenCredential.class);
        if (credential == null || credential.getToken() == null || credential.getToken().isBlank())
            return item(401, "INVALID_TOKEN");
        final UUID session;
        try {
            session = UUID.fromString(rawSession);
            if (!session.toString().equalsIgnoreCase(rawSession)) throw new IllegalArgumentException();
        } catch (RuntimeException invalid) { return item(400, "INVALID_RESULT"); }
        final com.neurovision.rehab.analytics.domain.model.valueobjects.SystematicExplorationTrialResult result;
        try { result = SystematicExplorationTrialRequestDecoder.decode(session, body); }
        catch (SystematicExplorationTrialRequestDecoder.Rejected invalid) { return item(invalid.status(), invalid.getMessage()); }
        return service.receive(result, credential.getToken()).map(status -> response(switch (status) {
            case CREATED -> 201;
            case ALREADY_STORED -> 200;
            case CONFLICT -> 409;
            case INVALID_RESULT -> 400;
            case INVALID_TOKEN -> 401;
            case DENIED -> 403;
            case UNAVAILABLE, WRITE_OUTCOME_UNKNOWN -> 503;
        }, status.name()));
    }

    private static Uni<Response> item(int status, String value) { return Uni.createFrom().item(response(status, value)); }
    private static Response response(int status, String value) {
        var response = Response.status(status).entity(Map.of("status", value)).header("Cache-Control", "no-store");
        if (status == 401) response.header("WWW-Authenticate", "Bearer");
        return response.build();
    }
}
