package com.neurovision.rehab.iam.interfaces.rest.resource;

import static jakarta.ws.rs.core.Response.Status.CREATED;

import com.neurovision.rehab.iam.application.internal.commandservices.ActivatePatientInvitationCommandService;
import com.neurovision.rehab.iam.application.internal.commandservices.InvitePatientCommandService;
import com.neurovision.rehab.iam.application.internal.commandservices.PatientInvitationNotFoundException;
import com.neurovision.rehab.iam.application.internal.queryservices.DoctorPatientInvitationsQuery;
import com.neurovision.rehab.iam.application.internal.queryservices.GetDoctorPatientInvitationsQueryService;
import com.neurovision.rehab.iam.domain.model.commands.ActivatePatientInvitationCommand;
import com.neurovision.rehab.iam.domain.model.valueobjects.CognitoSubject;
import com.neurovision.rehab.iam.domain.model.valueobjects.InvitationStatus;
import com.neurovision.rehab.iam.interfaces.rest.transform.PatientInvitationRestAssembler;
import io.quarkus.security.identity.SecurityIdentity;
import jakarta.annotation.security.RolesAllowed;
import jakarta.enterprise.context.RequestScoped;
import jakarta.inject.Inject;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.BadRequestException;
import jakarta.ws.rs.DefaultValue;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import java.time.Instant;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.media.Content;
import org.eclipse.microprofile.openapi.annotations.media.Schema;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponses;
import org.eclipse.microprofile.jwt.JsonWebToken;
import org.jboss.resteasy.reactive.RestResponse;

@Path("/api/v1/patient-invitations")
@Consumes(MediaType.APPLICATION_JSON)
@Produces(MediaType.APPLICATION_JSON)
@RequestScoped
public class PatientInvitationController {

    private final InvitePatientCommandService commandService;
    private final ActivatePatientInvitationCommandService activationService;
    private final GetDoctorPatientInvitationsQueryService queryService;
    private final SecurityIdentity securityIdentity;

    @Inject
    public PatientInvitationController(
            InvitePatientCommandService commandService,
            ActivatePatientInvitationCommandService activationService,
            GetDoctorPatientInvitationsQueryService queryService,
            SecurityIdentity securityIdentity) {
        this.commandService = commandService;
        this.activationService = activationService;
        this.queryService = queryService;
        this.securityIdentity = securityIdentity;
    }

    @GET
    @RolesAllowed("DOCTOR")
    @Operation(
            summary = "List patient invitations",
            description = "Returns a paginated list of invitations created by the authenticated doctor, "
                    + "optionally filtered by invitation status.")
    @APIResponses({
        @APIResponse(
                responseCode = "200",
                description = "Patient invitations returned",
                content = @Content(
                        mediaType = MediaType.APPLICATION_JSON,
                        schema = @Schema(implementation = PatientInvitationPageResponse.class))),
        @APIResponse(responseCode = "400", description = "Invalid pagination parameters or status filter"),
        @APIResponse(responseCode = "401", description = "Missing or invalid access token"),
        @APIResponse(responseCode = "403", description = "Authenticated user is not a doctor")
    })
    public PatientInvitationPageResponse listInvitations(
            @QueryParam("page") @DefaultValue("0") int page,
            @QueryParam("size") @DefaultValue("20") int size,
            @QueryParam("status") String status) {
        if (page < 0 || size < 1 || size > 100) {
            throw new BadRequestException("Invalid pagination parameters");
        }
        var result = queryService.handle(new DoctorPatientInvitationsQuery(
                new CognitoSubject(authenticatedSubject()), page, size, parseStatus(status)));
        return new PatientInvitationPageResponse(
                result.items().stream().map(PatientInvitationRestAssembler::toResponse).toList(),
                result.page(),
                result.size(),
                result.totalElements(),
                result.totalPages());
    }

    private static InvitationStatus parseStatus(String status) {
        if (status == null || status.isBlank()) {
            return null;
        }
        try {
            return InvitationStatus.valueOf(status.trim().toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException exception) {
            throw new BadRequestException("Unknown invitation status", exception);
        }
    }

    @POST
    @RolesAllowed("DOCTOR")
    @Operation(
            summary = "Invite patient",
            description = "Creates a patient invitation and provisions the patient's Cognito identity.")
    @APIResponses({
        @APIResponse(
                responseCode = "201",
                description = "Patient invitation created and sent",
                content = @Content(
                        mediaType = MediaType.APPLICATION_JSON,
                        schema = @Schema(implementation = PatientInvitationResponse.class))),
        @APIResponse(responseCode = "400", description = "Invalid request payload"),
        @APIResponse(responseCode = "401", description = "Missing or invalid access token"),
        @APIResponse(responseCode = "403", description = "Authenticated user is not a doctor"),
        @APIResponse(
                responseCode = "409",
                description = "An active invitation already exists for the patient email",
                content = @Content(
                        mediaType = MediaType.APPLICATION_JSON,
                        schema = @Schema(implementation = ApiErrorResponse.class))),
        @APIResponse(
                responseCode = "502",
                description = "Cognito could not provision the patient identity",
                content = @Content(
                        mediaType = MediaType.APPLICATION_JSON,
                        schema = @Schema(implementation = ApiErrorResponse.class)))
    })
    public RestResponse<PatientInvitationResponse> invitePatient(
            @NotNull @Valid InvitePatientRequest request) {
        var command = PatientInvitationRestAssembler.toCommand(request, authenticatedSubject());
        var invitation = commandService.handle(command);
        return RestResponse.status(CREATED, PatientInvitationRestAssembler.toResponse(invitation));
    }

    @PUT
    @Path("/me/activation")
    @RolesAllowed("PATIENT")
    @Operation(
            summary = "Activate authenticated patient invitation",
            description = "Marks the invitation as activated after Cognito confirms the patient's authentication.")
    @APIResponses({
        @APIResponse(
                responseCode = "200",
                description = "Patient invitation activated",
                content = @Content(
                        mediaType = MediaType.APPLICATION_JSON,
                        schema = @Schema(implementation = PatientInvitationResponse.class))),
        @APIResponse(responseCode = "401", description = "Missing or invalid access token"),
        @APIResponse(responseCode = "403", description = "Authenticated user is not a patient"),
        @APIResponse(
                responseCode = "404",
                description = "No invitation exists for the authenticated patient",
                content = @Content(
                        mediaType = MediaType.APPLICATION_JSON,
                        schema = @Schema(implementation = ApiErrorResponse.class)))
    })
    public RestResponse<Object> activateAuthenticatedPatient() {
        try {
            var invitation = activationService.handle(new ActivatePatientInvitationCommand(
                    new CognitoSubject(authenticatedSubject()), authenticationTime()));
            return RestResponse.ok(PatientInvitationRestAssembler.toResponse(invitation));
        } catch (PatientInvitationNotFoundException exception) {
            return RestResponse.status(
                    jakarta.ws.rs.core.Response.Status.NOT_FOUND,
                    new ApiErrorResponse("PATIENT_INVITATION_NOT_FOUND", exception.getMessage()));
        }
    }

    private String authenticatedSubject() {
        if (securityIdentity.getPrincipal() instanceof JsonWebToken accessToken) {
            return accessToken.getSubject();
        }
        throw new IllegalStateException("Authenticated principal is not a JWT access token");
    }

    private Instant authenticationTime() {
        if (!(securityIdentity.getPrincipal() instanceof JsonWebToken accessToken)) {
            throw new IllegalStateException("Authenticated principal is not a JWT access token");
        }
        Object claim = accessToken.getClaim("auth_time");
        return toAuthenticationTime(claim);
    }

    static Instant toAuthenticationTime(Object claim) {
        if (claim instanceof Number number) {
            return Instant.ofEpochSecond(number.longValue());
        }
        if (claim != null) {
            try {
                return Instant.ofEpochSecond(Long.parseLong(claim.toString()));
            } catch (NumberFormatException exception) {
                throw new IllegalStateException("JWT auth_time claim is not a Unix timestamp", exception);
            }
        }
        throw new IllegalStateException("JWT access token does not contain auth_time");
    }
}
