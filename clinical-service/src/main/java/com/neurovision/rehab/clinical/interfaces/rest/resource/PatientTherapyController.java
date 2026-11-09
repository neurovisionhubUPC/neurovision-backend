package com.neurovision.rehab.clinical.interfaces.rest.resource;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.neurovision.rehab.clinical.application.internal.commandservices.TherapySessionStartConflictException;
import com.neurovision.rehab.clinical.application.internal.commandservices.StartTherapySessionResult;
import com.neurovision.rehab.clinical.application.ports.in.PatientTherapyPlanQueryUseCase;
import com.neurovision.rehab.clinical.application.ports.in.PatientTherapySessionStartUseCase;
import com.neurovision.rehab.clinical.domain.model.valueobjects.*;
import com.neurovision.rehab.clinical.interfaces.rest.transform.PatientTherapyRestAssembler;
import io.quarkus.security.identity.SecurityIdentity;
import jakarta.annotation.security.RolesAllowed;
import jakarta.enterprise.context.RequestScoped;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.util.List;
import java.util.UUID;
import org.eclipse.microprofile.jwt.JsonWebToken;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponses;
import org.jboss.resteasy.reactive.RestResponse;

@Path("/api/v1/me/therapy-plans")
@Consumes(MediaType.APPLICATION_JSON)
@Produces(MediaType.APPLICATION_JSON)
@RequestScoped
public class PatientTherapyController {
    private final PatientTherapyPlanQueryUseCase plans;
    private final PatientTherapySessionStartUseCase starter;
    private final SecurityIdentity identity;
    private final ObjectMapper mapper;

    public PatientTherapyController(PatientTherapyPlanQueryUseCase plans,
            PatientTherapySessionStartUseCase starter, SecurityIdentity identity, ObjectMapper mapper) {
        this.plans = plans;
        this.starter = starter;
        this.identity = identity;
        this.mapper = mapper;
    }

    @GET
    @RolesAllowed("PATIENT")
    @Operation(summary = "List current assigned plans with informational daily availability")
    @APIResponses({
        @APIResponse(responseCode = "200", description = "Current assigned plans; admission is checked at start"),
        @APIResponse(responseCode = "401", description = "Missing or invalid access token"),
        @APIResponse(responseCode = "403", description = "Patient identity is not permitted"),
        @APIResponse(responseCode = "503", description = "Daily availability could not be read safely")
    })
    public List<PatientTherapyPlanResponse> list() {
        var subject = patientSubject();
        try {
            return plans.handle(subject).stream()
                    .map(view -> PatientTherapyRestAssembler.toResponse(view, mapper)).toList();
        } catch (SecurityException denied) {
            throw denied();
        } catch (RuntimeException unavailable) {
            throw new WebApplicationException(Response.status(503).type(MediaType.APPLICATION_JSON)
                    .entity(java.util.Map.of("code", "PATIENT_THERAPY_AVAILABILITY_UNAVAILABLE")).build());
        }
    }

    @POST
    @Path("/{therapyPlanId}/sessions")
    @RolesAllowed("PATIENT")
    @Operation(summary = "Start a prescribed therapeutic exercise idempotently")
    @APIResponses({
        @APIResponse(responseCode = "201", description = "Session created"),
        @APIResponse(responseCode = "200", description = "Same idempotent request replayed"),
        @APIResponse(responseCode = "400", description = "Invalid request or idempotency key"),
        @APIResponse(responseCode = "401", description = "Missing or invalid access token"),
        @APIResponse(responseCode = "403", description = "Plan is unknown or not accessible"),
        @APIResponse(responseCode = "409", description = "Admission or idempotency conflict")
    })
    public RestResponse<PatientTherapySessionResponse> start(
            @PathParam("therapyPlanId") String rawPlanId,
            @HeaderParam("Idempotency-Key") String rawKey,
            StartPatientTherapySessionRequest request) {
        var subject = patientSubject();
        try {
            if (request == null) throw new IllegalArgumentException("Request body is required");
            var planId = new TherapyPlanId(UUID.fromString(rawPlanId));
            var exercise = TherapeuticExercise.from(new ExerciseCode(request.exerciseCode())).code();
            var result = starter.handle(subject, planId, exercise, new IdempotencyKey(rawKey));
            return RestResponse.status(result.outcome()
                            == StartTherapySessionResult.Outcome.CREATED
                            ? Response.Status.CREATED : Response.Status.OK,
                    PatientTherapyRestAssembler.toResponse(result.session(), mapper));
        } catch (SecurityException denied) {
            throw denied();
        } catch (IllegalStateException | TherapySessionStartConflictException conflict) {
            throw new WebApplicationException("Therapy session cannot be started", 409);
        } catch (IllegalArgumentException | NullPointerException invalid) {
            throw new BadRequestException("Invalid therapy request");
        }
    }

    private CognitoSubject patientSubject() {
        if (!identity.hasRole("PATIENT") || identity.hasRole("DOCTOR")
                || !(identity.getPrincipal() instanceof JsonWebToken token)) throw denied();
        try {
            return new CognitoSubject(token.getSubject());
        } catch (IllegalArgumentException invalid) {
            throw denied();
        }
    }

    private static ForbiddenException denied() {
        return new ForbiddenException("Therapy operation is not permitted");
    }
}
