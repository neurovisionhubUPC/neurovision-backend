package com.neurovision.rehab.clinical.interfaces.rest.resource;

import com.neurovision.rehab.clinical.domain.model.valueobjects.CognitoSubject;
import com.neurovision.rehab.clinical.application.internal.commandservices.DoctorSchedulingUnavailableException;
import com.neurovision.rehab.clinical.application.ports.in.TherapeuticConfigurationAppendUseCase;
import com.neurovision.rehab.clinical.application.ports.in.TherapyPlanCreationUseCase;
import com.neurovision.rehab.clinical.interfaces.rest.transform.TherapyPlanRestAssembler;
import io.quarkus.security.identity.SecurityIdentity;
import jakarta.annotation.security.RolesAllowed;
import jakarta.enterprise.context.RequestScoped;
import jakarta.ws.rs.BadRequestException;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.ForbiddenException;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.util.NoSuchElementException;
import java.util.UUID;
import org.eclipse.microprofile.jwt.JsonWebToken;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponses;
import org.jboss.resteasy.reactive.RestResponse;

@Path("/api/v1/clinical-patients/{clinicalPatientId}/therapy-plans")
@Consumes(MediaType.APPLICATION_JSON)
@Produces(MediaType.APPLICATION_JSON)
@RequestScoped
public class DoctorTherapyPlanController {
    private final TherapyPlanCreationUseCase creator;
    private final TherapeuticConfigurationAppendUseCase appender;
    private final SecurityIdentity identity;

    public DoctorTherapyPlanController(TherapyPlanCreationUseCase creator,
            TherapeuticConfigurationAppendUseCase appender, SecurityIdentity identity) {
        this.creator = creator;
        this.appender = appender;
        this.identity = identity;
    }

    @POST
    @RolesAllowed("DOCTOR")
    @Operation(summary = "Create a therapy plan for an assigned patient")
    @APIResponses({
        @APIResponse(responseCode = "201", description = "Plan created"),
        @APIResponse(responseCode = "400", description = "Invalid prescription"),
        @APIResponse(responseCode = "401", description = "Missing or invalid access token"),
        @APIResponse(responseCode = "403", description = "Assignment and active doctor Profile with valid CMP are required"),
        @APIResponse(responseCode = "503", description = "Profile eligibility is unavailable")
    })
    public RestResponse<TherapyPlanResponse> create(@PathParam("clinicalPatientId") String rawPatientId,
            CreateTherapyPlanRequest request) {
        var doctor = doctorSubject();
        try {
            var command = TherapyPlanRestAssembler.toCommand(UUID.fromString(rawPatientId), doctor, request);
            return RestResponse.status(Response.Status.CREATED,
                    TherapyPlanRestAssembler.toResponse(creator.handle(command)));
        } catch (DoctorSchedulingUnavailableException unavailable) {
            throw new WebApplicationException("Doctor scheduling eligibility is unavailable", 503);
        } catch (SecurityException | NoSuchElementException denied) {
            throw new ForbiddenException("Therapy operation is not permitted");
        } catch (IllegalArgumentException | NullPointerException invalid) {
            throw new BadRequestException("Invalid therapy request");
        }
    }

    @POST
    @Path("/{therapyPlanId}/configurations")
    @RolesAllowed("DOCTOR")
    @Operation(summary = "Append an immutable therapeutic exercise configuration")
    @APIResponses({
        @APIResponse(responseCode = "201", description = "Configuration version created"),
        @APIResponse(responseCode = "400", description = "Invalid configuration"),
        @APIResponse(responseCode = "401", description = "Missing or invalid access token"),
        @APIResponse(responseCode = "403", description = "Plan is unknown or not accessible"),
        @APIResponse(responseCode = "409", description = "Plan is not active or exercise is not prescribed"),
        @APIResponse(responseCode = "503", description = "Profile eligibility is unavailable")
    })
    public RestResponse<TherapeuticConfigurationResponse> append(
            @PathParam("clinicalPatientId") String rawPatientId,
            @PathParam("therapyPlanId") String rawPlanId,
            AppendTherapeuticConfigurationRequest request) {
        var doctor = doctorSubject();
        try {
            var command = TherapyPlanRestAssembler.toCommand(UUID.fromString(rawPatientId),
                    UUID.fromString(rawPlanId), doctor, request);
            return RestResponse.status(Response.Status.CREATED,
                    TherapyPlanRestAssembler.toResponse(appender.handle(command)));
        } catch (DoctorSchedulingUnavailableException unavailable) {
            throw new WebApplicationException("Doctor scheduling eligibility is unavailable", 503);
        } catch (SecurityException | NoSuchElementException denied) {
            throw new ForbiddenException("Therapy operation is not permitted");
        } catch (IllegalStateException conflict) {
            throw new WebApplicationException("Therapy plan cannot accept this configuration", 409);
        } catch (IllegalArgumentException | NullPointerException invalid) {
            throw new BadRequestException("Invalid therapy request");
        }
    }

    private CognitoSubject doctorSubject() {
        if (!identity.hasRole("DOCTOR") || identity.hasRole("PATIENT")
                || !(identity.getPrincipal() instanceof JsonWebToken token)) {
            throw new ForbiddenException("Therapy operation is not permitted");
        }
        try {
            return new CognitoSubject(token.getSubject());
        } catch (IllegalArgumentException invalidSubject) {
            throw new ForbiddenException("Therapy operation is not permitted");
        }
    }
}
