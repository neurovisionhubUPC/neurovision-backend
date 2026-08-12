package com.neurovision.rehab.iam.interfaces.rest.resource;

import com.neurovision.rehab.iam.application.internal.commandservices.InvitePatientCommandService;
import com.neurovision.rehab.iam.application.internal.queryservices.DoctorPatientInvitationsQuery;
import com.neurovision.rehab.iam.application.internal.queryservices.GetDoctorPatientInvitationsQueryService;
import com.neurovision.rehab.iam.domain.model.valueobjects.CognitoSubject;
import com.neurovision.rehab.iam.domain.model.valueobjects.InvitationStatus;
import com.neurovision.rehab.iam.interfaces.rest.transform.PatientInvitationRestAssembler;
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
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import io.quarkus.security.identity.SecurityIdentity;
import org.eclipse.microprofile.jwt.JsonWebToken;
import org.jboss.resteasy.reactive.RestResponse;

import static jakarta.ws.rs.core.Response.Status.CREATED;

@Path("/api/v1/patient-invitations")
@Consumes(MediaType.APPLICATION_JSON)
@Produces(MediaType.APPLICATION_JSON)
@RequestScoped
public class PatientInvitationResource {

    private final InvitePatientCommandService commandService;
    private final GetDoctorPatientInvitationsQueryService queryService;
    private final SecurityIdentity securityIdentity;

    @Inject
    public PatientInvitationResource(
            InvitePatientCommandService commandService,
            GetDoctorPatientInvitationsQueryService queryService,
            SecurityIdentity securityIdentity) {
        this.commandService = commandService;
        this.queryService = queryService;
        this.securityIdentity = securityIdentity;
    }

    @GET
    @RolesAllowed("DOCTOR")
    public PatientInvitationPageResponse listInvitations(
            @QueryParam("page") @DefaultValue("0") int page,
            @QueryParam("size") @DefaultValue("20") int size,
            @QueryParam("status") String status) {
        if (page < 0 || size < 1 || size > 100) {
            throw new BadRequestException("Invalid pagination parameters");
        }
        var result = queryService.handle(new DoctorPatientInvitationsQuery(
                new CognitoSubject(doctorSubject()), page, size, parseStatus(status)));
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
    public RestResponse<PatientInvitationResponse> invitePatient(
            @NotNull @Valid InvitePatientRequest request) {
        var command = PatientInvitationRestAssembler.toCommand(request, doctorSubject());
        var invitation = commandService.handle(command);
        return RestResponse.status(CREATED, PatientInvitationRestAssembler.toResponse(invitation));
    }

    private String doctorSubject() {
        if (securityIdentity.getPrincipal() instanceof JsonWebToken accessToken) {
            return accessToken.getSubject();
        }
        throw new IllegalStateException("Authenticated principal is not a JWT access token");
    }
}
