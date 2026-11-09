package com.neurovision.rehab.clinical.interfaces.rest.resource;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.neurovision.rehab.clinical.application.internal.queryservices.GetPatientTherapySessionQueryService;
import com.neurovision.rehab.clinical.application.ports.out.persistence.DoctorTherapySessionReadRepository.Detail;
import com.neurovision.rehab.clinical.application.ports.out.persistence.DoctorTherapySessionReadRepository.TechnicalProgress.Availability;
import com.neurovision.rehab.clinical.domain.model.valueobjects.CognitoSubject;
import com.neurovision.rehab.clinical.domain.model.valueobjects.TherapySessionId;
import io.quarkus.oidc.AccessTokenCredential;
import io.quarkus.security.identity.SecurityIdentity;
import jakarta.annotation.security.RolesAllowed;
import jakarta.enterprise.context.RequestScoped;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.eclipse.microprofile.jwt.JsonWebToken;
import org.eclipse.microprofile.openapi.annotations.Operation;

@Path("/api/v1/me/therapy-sessions/{therapySessionId}")
@Produces(MediaType.APPLICATION_JSON)
@RolesAllowed("PATIENT")
@RequestScoped
public class PatientTherapySessionDetailController {
    private final GetPatientTherapySessionQueryService service;
    private final SecurityIdentity identity;
    private final ObjectMapper mapper;

    public PatientTherapySessionDetailController(GetPatientTherapySessionQueryService service,
            SecurityIdentity identity, ObjectMapper mapper) {
        this.service = service; this.identity = identity; this.mapper = mapper;
    }

    @GET
    @Operation(summary = "Read own session detail", description = "Own session and current ACTIVE assignment only. Frozen configuration and optional technical progress; no writes, locks, renewal or peer queries. 400/401/403/503; no-store.")
    public Response detail(@PathParam("therapySessionId") String rawId) {
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
            final TherapySessionId id;
            try {
                var value = UUID.fromString(rawId);
                if (!value.toString().equals(rawId)) return error(400, "INVALID_REQUEST");
                id = new TherapySessionId(value);
            } catch (IllegalArgumentException | NullPointerException invalid) { return error(400, "INVALID_REQUEST"); }
            return Response.ok(response(service.handle(subject, id)), MediaType.APPLICATION_JSON)
                    .header("Cache-Control", "no-store").build();
        } catch (SecurityException forbidden) { return error(403, "FORBIDDEN"); }
        catch (RuntimeException unavailable) { return error(503, "UNAVAILABLE"); }
    }

    private PatientTherapySessionDetailResponse response(Detail detail) {
        var s = detail.session(); var limits = detail.sessionLimits(); var p = detail.technicalProgress();
        var progress = p.availability() == Availability.NOT_APPLICABLE ? null
                : new PatientTherapySessionDetailResponse.TechnicalProgress(p.criterion().name(), p.target(),
                        p.activeMilliseconds(), p.completedRepetitions(), p.targetReached());
        return new PatientTherapySessionDetailResponse(s.therapySessionId(), s.therapyPlanId(), s.exerciseCode(),
                s.status(), s.startedAt(), s.endedAt(), s.activeDurationSeconds(), s.quotaDate(),
                s.configurationId(), s.configurationVersion(), s.parameterSchemaVersion(),
                Objects.requireNonNull(mapper.valueToTree(detail.configurationParameters())),
                new PatientTherapySessionDetailResponse.SessionLimits(limits.minimumValidDurationMinutes(),
                        limits.maximumSessionDurationMinutes(), limits.minimumValidSampleCount()), progress);
    }

    private static Response error(int status, String code) {
        var response = Response.status(status).type(MediaType.APPLICATION_JSON)
                .entity(Map.of("code", "PATIENT_SESSION_DETAIL_" + code)).header("Cache-Control", "no-store");
        if (status == 401) response.header("WWW-Authenticate", "Bearer");
        return response.build();
    }
}
