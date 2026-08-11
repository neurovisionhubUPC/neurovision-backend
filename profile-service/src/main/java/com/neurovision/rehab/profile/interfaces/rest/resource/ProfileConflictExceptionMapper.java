package com.neurovision.rehab.profile.interfaces.rest.resource;

import com.neurovision.rehab.profile.application.internal.commandservices.ProfileConflictException;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.ExceptionMapper;
import jakarta.ws.rs.ext.Provider;

@Provider
public class ProfileConflictExceptionMapper implements ExceptionMapper<ProfileConflictException> {

    @Override
    public Response toResponse(ProfileConflictException exception) {
        return Response.status(Response.Status.CONFLICT)
                .type(MediaType.APPLICATION_JSON)
                .entity(new ApiErrorResponse(exception.code(), exception.getMessage()))
                .build();
    }
}
