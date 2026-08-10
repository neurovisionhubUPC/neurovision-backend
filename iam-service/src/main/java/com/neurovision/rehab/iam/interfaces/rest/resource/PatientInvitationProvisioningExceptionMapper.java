package com.neurovision.rehab.iam.interfaces.rest.resource;

import com.neurovision.rehab.iam.application.internal.commandservices.PatientInvitationProvisioningException;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.ExceptionMapper;
import jakarta.ws.rs.ext.Provider;

@Provider
public class PatientInvitationProvisioningExceptionMapper
        implements ExceptionMapper<PatientInvitationProvisioningException> {

    @Override
    public Response toResponse(PatientInvitationProvisioningException exception) {
        return Response.status(Response.Status.BAD_GATEWAY)
                .type(MediaType.APPLICATION_JSON)
                .entity(new ApiErrorResponse(
                        "PATIENT_IDENTITY_PROVIDER_UNAVAILABLE",
                        "Patient identity could not be provisioned"))
                .build();
    }
}
