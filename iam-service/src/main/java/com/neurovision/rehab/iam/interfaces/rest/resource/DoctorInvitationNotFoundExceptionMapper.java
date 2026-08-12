package com.neurovision.rehab.iam.interfaces.rest.resource;

import com.neurovision.rehab.iam.application.internal.commandservices.DoctorInvitationNotFoundException;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.ExceptionMapper;
import jakarta.ws.rs.ext.Provider;

@Provider
public class DoctorInvitationNotFoundExceptionMapper
        implements ExceptionMapper<DoctorInvitationNotFoundException> {

    @Override
    public Response toResponse(DoctorInvitationNotFoundException exception) {
        return Response.status(Response.Status.NOT_FOUND)
                .type(MediaType.APPLICATION_JSON)
                .entity(new ApiErrorResponse("DOCTOR_INVITATION_NOT_FOUND", exception.getMessage()))
                .build();
    }
}
