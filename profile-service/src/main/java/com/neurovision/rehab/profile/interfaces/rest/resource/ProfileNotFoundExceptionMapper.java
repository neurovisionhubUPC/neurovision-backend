package com.neurovision.rehab.profile.interfaces.rest.resource;

import com.neurovision.rehab.profile.application.internal.commandservices.ProfileNotFoundException;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.ExceptionMapper;
import jakarta.ws.rs.ext.Provider;

@Provider
public class ProfileNotFoundExceptionMapper implements ExceptionMapper<ProfileNotFoundException> {

    @Override
    public Response toResponse(ProfileNotFoundException exception) {
        return Response.status(Response.Status.NOT_FOUND)
                .type(MediaType.APPLICATION_JSON)
                .entity(new ApiErrorResponse("PROFILE_NOT_FOUND", exception.getMessage()))
                .build();
    }
}
