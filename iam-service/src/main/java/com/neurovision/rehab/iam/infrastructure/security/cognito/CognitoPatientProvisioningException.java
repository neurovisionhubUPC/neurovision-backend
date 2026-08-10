package com.neurovision.rehab.iam.infrastructure.security.cognito;

public final class CognitoPatientProvisioningException extends RuntimeException {

    public CognitoPatientProvisioningException(Throwable cause) {
        super("Cognito could not complete patient provisioning", cause);
    }
}
