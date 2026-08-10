package com.neurovision.rehab.iam.interfaces.rest.resource;

import com.neurovision.rehab.iam.application.internal.commandservices.PatientAlreadyInvitedException;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.ExceptionMapper;
import jakarta.ws.rs.ext.Provider;

@Provider
public class PatientAlreadyInvitedExceptionMapper
        implements ExceptionMapper<PatientAlreadyInvitedException> {

    @Override
    public Response toResponse(PatientAlreadyInvitedException exception) {
        return Response.status(Response.Status.CONFLICT)
                .type(MediaType.APPLICATION_JSON)
                .entity(new ApiErrorResponse("PATIENT_ALREADY_INVITED", exception.getMessage()))
                .build();
    }
}
