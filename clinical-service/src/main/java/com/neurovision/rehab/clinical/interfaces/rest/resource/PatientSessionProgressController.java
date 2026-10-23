package com.neurovision.rehab.clinical.interfaces.rest.resource;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.neurovision.rehab.clinical.application.internal.commandservices.SessionProgressConflictException;
import com.neurovision.rehab.clinical.application.ports.in.PatientSessionProgressUseCase;
import com.neurovision.rehab.clinical.domain.model.valueobjects.*;
import com.neurovision.rehab.clinical.interfaces.rest.transform.SessionActivityRestAssembler;
import io.quarkus.oidc.AccessTokenCredential;
import io.quarkus.security.identity.SecurityIdentity;
import jakarta.annotation.security.RolesAllowed;
import jakarta.enterprise.context.RequestScoped;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.*;
import java.util.Map;
import java.util.function.Supplier;
import org.eclipse.microprofile.jwt.JsonWebToken;
import org.eclipse.microprofile.openapi.annotations.Operation;

@Path("/api/v1/me/therapy-sessions/{therapySessionId}")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@RolesAllowed("PATIENT")
@RequestScoped
public class PatientSessionProgressController {
    private final PatientSessionProgressUseCase service;
    private final SecurityIdentity identity;
    private final ObjectMapper mapper;
    public PatientSessionProgressController(PatientSessionProgressUseCase service, SecurityIdentity identity, ObjectMapper mapper) {
        this.service = service; this.identity = identity; this.mapper = mapper;
    }
    private CognitoSubject patient() {
        if (identity == null || identity.isAnonymous()) throw new NotAuthorizedException("Bearer");
        if (!identity.hasRole("PATIENT") || identity.hasRole("DOCTOR") || identity.hasRole("ADMIN")
                || !(identity.getPrincipal() instanceof JsonWebToken jwt)) throw new SecurityException();
        var credential = identity.getCredential(AccessTokenCredential.class);
        if (credential == null || credential.getToken() == null || credential.getToken().isBlank()) {
            throw new NotAuthorizedException("Bearer");
        }
        try { return new CognitoSubject(jwt.getSubject()); }
        catch (RuntimeException invalid) { throw new SecurityException(); }
    }
    private Response response(Supplier<Object> operation) {
        try { return Response.ok(operation.get()).header("Cache-Control", "no-store").build(); }
        catch (NotAuthorizedException unauthenticated) { return error(401, "UNAUTHENTICATED"); }
        catch (SecurityException forbidden) { return error(403, "FORBIDDEN"); }
        catch (SessionProgressConflictException conflict) { return error(409, "CONFLICT"); }
        catch (BadRequestException invalid) { return error(400, "INVALID_REQUEST"); }
        catch (RuntimeException unavailable) { return error(503, "UNAVAILABLE"); }
    }
    private static <T> T request(Supplier<T> parse) {
        try { return parse.get(); }
        catch (IllegalArgumentException | NullPointerException invalid) { throw new BadRequestException(); }
    }
    private static Response error(int status, String code) {
        return Response.status(status).entity(Map.of("code", "SESSION_PROGRESS_" + code)).header("Cache-Control", "no-store").build();
    }
    @POST @Path("/activity-events")
    @Operation(summary = "Record ordered patient activity", description = "Schema 1 events, original acknowledgement on exact replay; server time only. 400/401/403/409/503, no-store.")
    public Response activity(@PathParam("therapySessionId") String id, String body) {
        return response(() -> {
            var subject = patient();
            return service.activity(subject, request(() -> new TherapySessionId(SessionActivityRestAssembler.uuid(id))),
                    request(() -> SessionActivityRestAssembler.event(body, mapper)));
        });
    }
    @GET @Path("/progress")
    @Operation(summary = "Read own technical progress", description = "No credit, lease renewal, expiry mutation or Telemetry persistence claim.")
    public Response progress(@PathParam("therapySessionId") String id) {
        return response(() -> { var subject = patient(); return service.progress(subject, request(() -> new TherapySessionId(SessionActivityRestAssembler.uuid(id)))); });
    }
    @PUT @Path("/completion")
    @Operation(summary = "Finish or abandon own session idempotently", description = "Idempotency-Key required. FINISHED requires the frozen technical target; ABANDONED retains partial progress. Local completion only, no telemetry/report guarantee.")
    public Response complete(@PathParam("therapySessionId") String id, @HeaderParam("Idempotency-Key") String key, String body) {
        return response(() -> {
            var subject = patient();
            return service.complete(subject, request(() -> new TherapySessionId(SessionActivityRestAssembler.uuid(id))),
                    request(() -> new IdempotencyKey(key)), request(() -> SessionActivityRestAssembler.action(body, mapper)));
        });
    }
}
