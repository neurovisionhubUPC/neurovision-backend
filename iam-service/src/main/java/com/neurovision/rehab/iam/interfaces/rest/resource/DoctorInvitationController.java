package com.neurovision.rehab.iam.interfaces.rest.resource;

import static jakarta.ws.rs.core.Response.Status.CREATED;

import com.neurovision.rehab.iam.application.internal.commandservices.ActivateDoctorInvitationCommandService;
import com.neurovision.rehab.iam.application.internal.commandservices.InviteDoctorCommandService;
import com.neurovision.rehab.iam.domain.model.commands.ActivateDoctorInvitationCommand;
import com.neurovision.rehab.iam.domain.model.valueobjects.CognitoSubject;
import com.neurovision.rehab.iam.interfaces.rest.transform.DoctorInvitationRestAssembler;
import io.quarkus.security.identity.SecurityIdentity;
import jakarta.annotation.security.RolesAllowed;
import jakarta.enterprise.context.RequestScoped;
import jakarta.inject.Inject;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import java.time.Instant;
import org.eclipse.microprofile.jwt.JsonWebToken;
import org.jboss.resteasy.reactive.RestResponse;

@Path("/api/v1/doctor-invitations")
@Consumes(MediaType.APPLICATION_JSON)
@Produces(MediaType.APPLICATION_JSON)
@RequestScoped
public class DoctorInvitationController {

    private final InviteDoctorCommandService invitationService;
    private final ActivateDoctorInvitationCommandService activationService;
    private final SecurityIdentity securityIdentity;

    @Inject
    public DoctorInvitationController(
            InviteDoctorCommandService invitationService,
            ActivateDoctorInvitationCommandService activationService,
            SecurityIdentity securityIdentity) {
        this.invitationService = invitationService;
        this.activationService = activationService;
        this.securityIdentity = securityIdentity;
    }

    @POST
    @RolesAllowed("ADMIN")
    public RestResponse<DoctorInvitationResponse> inviteDoctor(
            @NotNull @Valid InviteDoctorRequest request) {
        var command = DoctorInvitationRestAssembler.toCommand(request, authenticatedSubject());
        var invitation = invitationService.handle(command);
        return RestResponse.status(CREATED, DoctorInvitationRestAssembler.toResponse(invitation));
    }

    @PUT
    @Path("/me/activation")
    @RolesAllowed("DOCTOR")
    public DoctorInvitationResponse activateAuthenticatedDoctor() {
        var command = new ActivateDoctorInvitationCommand(
                new CognitoSubject(authenticatedSubject()), authenticationTime());
        return DoctorInvitationRestAssembler.toResponse(activationService.handle(command));
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
        return toAuthenticationTime(accessToken.getClaim("auth_time"));
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
