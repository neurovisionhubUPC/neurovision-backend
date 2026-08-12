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
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.media.Content;
import org.eclipse.microprofile.openapi.annotations.media.Schema;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponses;

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
    @Operation(
            summary = "Get current profile",
            description = "Returns the profile associated with the authenticated patient or doctor.")
    @APIResponses({
        @APIResponse(
                responseCode = "200",
                description = "Current profile returned",
                content = @Content(
                        mediaType = MediaType.APPLICATION_JSON,
                        schema = @Schema(implementation = ProfileResponse.class))),
        @APIResponse(responseCode = "401", description = "Missing or invalid access token"),
        @APIResponse(responseCode = "403", description = "Authenticated user is not a patient or doctor"),
        @APIResponse(
                responseCode = "404",
                description = "No profile exists for the authenticated user",
                content = @Content(
                        mediaType = MediaType.APPLICATION_JSON,
                        schema = @Schema(implementation = ApiErrorResponse.class)))
    })
    public ProfileResponse getCurrentProfile() {
        return ProfileRestAssembler.toResponse(profiles.get(authenticatedSubject()));
    }

    @PUT
    @RolesAllowed("PATIENT")
    @Operation(
            summary = "Complete current patient profile",
            description = "Completes or updates the authenticated patient's personal profile details.")
    @APIResponses({
        @APIResponse(
                responseCode = "200",
                description = "Patient profile completed",
                content = @Content(
                        mediaType = MediaType.APPLICATION_JSON,
                        schema = @Schema(implementation = ProfileResponse.class))),
        @APIResponse(responseCode = "400", description = "Invalid request payload"),
        @APIResponse(responseCode = "401", description = "Missing or invalid access token"),
        @APIResponse(responseCode = "403", description = "Authenticated user is not a patient"),
        @APIResponse(
                responseCode = "404",
                description = "No profile exists for the authenticated patient",
                content = @Content(
                        mediaType = MediaType.APPLICATION_JSON,
                        schema = @Schema(implementation = ApiErrorResponse.class))),
        @APIResponse(
                responseCode = "409",
                description = "The document number is already registered",
                content = @Content(
                        mediaType = MediaType.APPLICATION_JSON,
                        schema = @Schema(implementation = ApiErrorResponse.class)))
    })
    public ProfileResponse completeCurrentProfile(
            @NotNull @Valid CompleteProfileRequest request) {
        var command = ProfileRestAssembler.toCommand(request, authenticatedSubject());
        return ProfileRestAssembler.toResponse(profiles.complete(command));
    }

    @PUT
    @Path("/professional")
    @RolesAllowed("DOCTOR")
    @Operation(
            summary = "Onboard current doctor",
            description = "Completes the authenticated doctor's personal and professional profile details.")
    @APIResponses({
        @APIResponse(
                responseCode = "200",
                description = "Doctor profile onboarded",
                content = @Content(
                        mediaType = MediaType.APPLICATION_JSON,
                        schema = @Schema(implementation = ProfileResponse.class))),
        @APIResponse(responseCode = "400", description = "Invalid request payload"),
        @APIResponse(responseCode = "401", description = "Missing or invalid access token"),
        @APIResponse(responseCode = "403", description = "Authenticated user is not a doctor"),
        @APIResponse(
                responseCode = "409",
                description = "The profile type, document number, or medical license conflicts with existing data",
                content = @Content(
                        mediaType = MediaType.APPLICATION_JSON,
                        schema = @Schema(implementation = ApiErrorResponse.class)))
    })
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
