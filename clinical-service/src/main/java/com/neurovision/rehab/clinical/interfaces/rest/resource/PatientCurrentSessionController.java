package com.neurovision.rehab.clinical.interfaces.rest.resource;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.neurovision.rehab.clinical.application.internal.queryservices.GetPatientCurrentSessionQueryService;
import com.neurovision.rehab.clinical.domain.model.valueobjects.CognitoSubject;
import com.neurovision.rehab.clinical.interfaces.rest.transform.PatientTherapyRestAssembler;
import io.quarkus.oidc.AccessTokenCredential;
import io.quarkus.security.identity.SecurityIdentity;
import jakarta.annotation.security.RolesAllowed;
import jakarta.enterprise.context.RequestScoped;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.util.Map;
import org.eclipse.microprofile.jwt.JsonWebToken;
import org.eclipse.microprofile.openapi.annotations.Operation;

@Path("/api/v1/me/therapy-sessions/current")
@Produces(MediaType.APPLICATION_JSON)
@RolesAllowed("PATIENT")
@RequestScoped
public class PatientCurrentSessionController {
    private final GetPatientCurrentSessionQueryService service;
    private final SecurityIdentity identity;
    private final ObjectMapper mapper;

    public PatientCurrentSessionController(GetPatientCurrentSessionQueryService service,
            SecurityIdentity identity, ObjectMapper mapper) {
        this.service = service; this.identity = identity; this.mapper = mapper;
    }

    @GET
    @Operation(summary = "Discover own current session", description = "Read-only NONE/ACTIVE/EXPIRED observation with frozen start payload. No renewal, expiry transition or progress credit. 401/403/503; resource responses use no-store.")
    public Response current() {
        try {
            if (identity == null || identity.isAnonymous()) return error(401, "UNAUTHENTICATED");
            if (!identity.hasRole("PATIENT") || identity.hasRole("DOCTOR") || identity.hasRole("ADMIN")
                    || !(identity.getPrincipal() instanceof JsonWebToken jwt)) return error(403, "FORBIDDEN");
            var credential = identity.getCredential(AccessTokenCredential.class);
            if (credential == null || credential.getToken() == null || credential.getToken().isBlank())
                return error(401, "UNAUTHENTICATED");
            final CognitoSubject subject;
            try {
                subject = new CognitoSubject(jwt.getSubject());
                if (!subject.value().equals(jwt.getSubject())) return error(403, "FORBIDDEN");
            } catch (IllegalArgumentException invalid) { return error(403, "FORBIDDEN"); }
            var view = service.handle(subject);
            var session = view.session() == null ? null : PatientTherapyRestAssembler.toResponse(view.session(), mapper);
            return Response.ok(new PatientCurrentSessionResponse(view.state().name(), view.evaluatedAt(), session),
                    MediaType.APPLICATION_JSON).header("Cache-Control", "no-store").build();
        } catch (SecurityException forbidden) { return error(403, "FORBIDDEN"); }
        catch (RuntimeException unavailable) { return error(503, "UNAVAILABLE"); }
    }

    private static Response error(int status, String code) {
        var response = Response.status(status).type(MediaType.APPLICATION_JSON)
                .entity(Map.of("code", "PATIENT_CURRENT_SESSION_" + code)).header("Cache-Control", "no-store");
        if (status == 401) response.header("WWW-Authenticate", "Bearer");
        return response.build();
    }
}
