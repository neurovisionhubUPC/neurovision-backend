package com.neurovision.rehab.iam.interfaces.rest.resource;

import com.neurovision.rehab.iam.application.internal.commandservices.InvitePatientCommandService;
import com.neurovision.rehab.iam.interfaces.rest.transform.PatientInvitationRestAssembler;
import jakarta.annotation.security.RolesAllowed;
import jakarta.enterprise.context.RequestScoped;
import jakarta.inject.Inject;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
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
    private final SecurityIdentity securityIdentity;

    @Inject
    public PatientInvitationResource(
            InvitePatientCommandService commandService, SecurityIdentity securityIdentity) {
        this.commandService = commandService;
        this.securityIdentity = securityIdentity;
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
