package com.neurovision.rehab.profile.interfaces.rest.resource;

import com.neurovision.rehab.profile.infrastructure.persistence.jpa.transaction.TransactionalCurrentProfileService;
import com.neurovision.rehab.profile.interfaces.rest.transform.ProfileRestAssembler;
import io.quarkus.security.identity.SecurityIdentity;
import jakarta.annotation.security.RolesAllowed;
import jakarta.enterprise.context.RequestScoped;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import org.eclipse.microprofile.jwt.JsonWebToken;

@Path("/api/v1/profile")
@Consumes(MediaType.APPLICATION_JSON)
@Produces(MediaType.APPLICATION_JSON)
@RequestScoped
public class ProfileController {

    private final TransactionalCurrentProfileService profiles;
    private final SecurityIdentity securityIdentity;

    public ProfileController(
            TransactionalCurrentProfileService profiles, SecurityIdentity securityIdentity) {
        this.profiles = profiles;
        this.securityIdentity = securityIdentity;
    }

    @GET
    @RolesAllowed({"PATIENT", "DOCTOR"})
    public ProfileResponse getCurrentProfile() {
        return ProfileRestAssembler.toResponse(profiles.get(authenticatedSubject()));
    }

    @PUT
    @RolesAllowed("PATIENT")
    public ProfileResponse completeCurrentProfile(
            @NotNull @Valid CompleteProfileRequest request) {
        var command = ProfileRestAssembler.toCommand(request, authenticatedSubject());
        return ProfileRestAssembler.toResponse(profiles.complete(command));
    }

    @PUT
    @Path("/professional")
    @RolesAllowed("DOCTOR")
    public ProfileResponse onboardDoctor(@NotNull @Valid OnboardDoctorRequest request) {
        var command = ProfileRestAssembler.toCommand(request, authenticatedSubject());
        return ProfileRestAssembler.toResponse(profiles.onboardDoctor(command));
    }

    private String authenticatedSubject() {
        if (securityIdentity.getPrincipal() instanceof JsonWebToken accessToken) {
            return accessToken.getSubject();
        }
        throw new IllegalStateException("Authenticated principal is not a JWT access token");
    }
}
