package com.neurovision.rehab.iam.infrastructure.security.cognito;

public class CognitoDoctorProvisioningException extends RuntimeException {

    public CognitoDoctorProvisioningException(Throwable cause) {
        super("Cognito doctor provisioning failed", cause);
    }
}
