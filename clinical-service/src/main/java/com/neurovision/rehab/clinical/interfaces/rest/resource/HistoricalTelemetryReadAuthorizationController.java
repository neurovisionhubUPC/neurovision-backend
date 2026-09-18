package com.neurovision.rehab.clinical.interfaces.rest.resource;

import com.neurovision.rehab.clinical.application.internal.queryservices.AuthorizeHistoricalTelemetryReadQueryService;
import com.neurovision.rehab.clinical.application.internal.queryservices.HistoricalTelemetryReadDeniedException;
import com.neurovision.rehab.clinical.application.ports.out.authorization.HistoricalTelemetryReadAuthorizationPort.ReaderRole;
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

@Path("/api/internal/v1/therapy-sessions")
@Produces(MediaType.APPLICATION_JSON)
@RequestScoped
public class HistoricalTelemetryReadAuthorizationController {
    private final AuthorizeHistoricalTelemetryReadQueryService authorization;
    private final SecurityIdentity identity;

    public HistoricalTelemetryReadAuthorizationController(AuthorizeHistoricalTelemetryReadQueryService authorization,
            SecurityIdentity identity) {
        this.authorization = authorization;
        this.identity = identity;
    }

    @GET
    @Path("/{therapySessionId}/telemetry-read-authorization")
    @RolesAllowed({"PATIENT", "DOCTOR"})
    public TelemetryAuthorizationResponse authorize(@PathParam("therapySessionId") String rawSessionId) {
        if (!(identity.getPrincipal() instanceof JsonWebToken token)) {
            throw new HistoricalTelemetryReadDeniedException();
        }
        final TherapySessionId sessionId;
        try {
            sessionId = new TherapySessionId(UUID.fromString(rawSessionId));
        } catch (IllegalArgumentException invalid) {
            throw new HistoricalTelemetryReadDeniedException();
        }
        // Dual-role tokens are ambiguous and must not select the more permissive path.
        boolean patient = identity.hasRole("PATIENT");
        boolean doctor = identity.hasRole("DOCTOR");
        if (patient == doctor) throw new HistoricalTelemetryReadDeniedException();
        var role = patient ? ReaderRole.PATIENT : ReaderRole.DOCTOR;
        var authorized = authorization.authorize(token.getSubject(), sessionId, role);
        return new TelemetryAuthorizationResponse(1, true, authorized.value());
    }
}
