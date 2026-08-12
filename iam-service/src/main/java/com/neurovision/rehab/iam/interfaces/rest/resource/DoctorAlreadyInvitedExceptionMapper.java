package com.neurovision.rehab.iam.interfaces.rest.resource;

import com.neurovision.rehab.iam.application.internal.commandservices.DoctorAlreadyInvitedException;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.ExceptionMapper;
import jakarta.ws.rs.ext.Provider;

@Provider
public class DoctorAlreadyInvitedExceptionMapper
        implements ExceptionMapper<DoctorAlreadyInvitedException> {

    @Override
    public Response toResponse(DoctorAlreadyInvitedException exception) {
        return Response.status(Response.Status.CONFLICT)
                .type(MediaType.APPLICATION_JSON)
                .entity(new ApiErrorResponse("DOCTOR_ALREADY_INVITED", exception.getMessage()))
                .build();
    }
}
