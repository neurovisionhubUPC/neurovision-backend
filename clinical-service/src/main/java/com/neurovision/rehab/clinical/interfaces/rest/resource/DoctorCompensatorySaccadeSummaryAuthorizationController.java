package com.neurovision.rehab.clinical.interfaces.rest.resource;

import com.neurovision.rehab.clinical.application.internal.queryservices.AuthorizeDoctorCompensatorySaccadeSummaryQueryService;
import com.neurovision.rehab.clinical.domain.model.valueobjects.TherapySessionId;
import io.quarkus.oidc.AccessTokenCredential;
import io.quarkus.security.identity.SecurityIdentity;
import jakarta.annotation.security.RolesAllowed;
import jakarta.enterprise.context.RequestScoped;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.*;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.eclipse.microprofile.jwt.JsonWebToken;
import org.eclipse.microprofile.openapi.annotations.Operation;

@Path("/api/internal/v1/therapy-sessions/{therapySessionId}/compensatory-saccade-summary-authorization")
@Produces(MediaType.APPLICATION_JSON)
@RolesAllowed("DOCTOR")
@RequestScoped
public class DoctorCompensatorySaccadeSummaryAuthorizationController {
    private final AuthorizeDoctorCompensatorySaccadeSummaryQueryService service;
    private final SecurityIdentity identity;
    public DoctorCompensatorySaccadeSummaryAuthorizationController(AuthorizeDoctorCompensatorySaccadeSummaryQueryService service, SecurityIdentity identity) {
        this.service = service; this.identity = identity;
    }

    @GET
    @Operation(summary = "Authorize own Compensatory Saccade summary read", description = "Original DOCTOR access token; session authorship, current active patient assignment and frozen policy. Closed sessions are readable; no state change or token issuance.")
    public Response authorize(@PathParam("therapySessionId") String rawSessionId) {
        try {
            if (identity == null || identity.isAnonymous()) return error(401, "UNAUTHENTICATED");
            if (!Set.of("DOCTOR").equals(identity.getRoles()) || !(identity.getPrincipal() instanceof JsonWebToken jwt))
                return error(403, "FORBIDDEN");
            var credential = identity.getCredential(AccessTokenCredential.class);
            if (credential == null || credential.getToken() == null || credential.getToken().isBlank()) return error(401, "UNAUTHENTICATED");
            final UUID id;
            try { id = UUID.fromString(rawSessionId); if (!id.toString().equals(rawSessionId)) throw new IllegalArgumentException(); }
            catch (RuntimeException invalid) { return error(400, "INVALID_REQUEST"); }
            var permit = service.authorize(jwt.getSubject(), new TherapySessionId(id));
            return Response.ok(new Payload(1, true, permit.therapySessionId(), permit.configurationId(),
                    permit.configurationVersion(), permit.minimumFixationMs(), permit.maximumStimulusDurationMs()))
                    .header("Cache-Control", "no-store").build();
        } catch (SecurityException forbidden) { return error(403, "FORBIDDEN"); }
        catch (RuntimeException unavailable) { return error(503, "UNAVAILABLE"); }
    }

    private static Response error(int status, String code) {
        var response = Response.status(status).entity(Map.of("code", "COMPENSATORY_SACCADE_SUMMARY_AUTHORIZATION_" + code))
                .header("Cache-Control", "no-store");
        if (status == 401) response.header("WWW-Authenticate", "Bearer");
        return response.build();
    }
    @io.quarkus.runtime.annotations.RegisterForReflection
    public record Payload(int contractVersion, boolean permitted, UUID therapySessionId, UUID configurationId,
            long configurationVersion, int minimumFixationMs, int maximumStimulusDurationMs) {
        @Override public String toString() { return "DoctorCompensatorySaccadeSummaryAuthorizationResponse[redacted]"; }
    }
}
