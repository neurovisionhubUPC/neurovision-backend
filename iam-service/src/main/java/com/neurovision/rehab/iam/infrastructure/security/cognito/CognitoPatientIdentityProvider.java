package com.neurovision.rehab.iam.infrastructure.security.cognito;

import com.neurovision.rehab.iam.application.ports.out.identity.PatientIdentityProvider;
import com.neurovision.rehab.iam.application.ports.out.identity.ProvisionedPatientIdentity;
import com.neurovision.rehab.iam.domain.model.valueobjects.CognitoSubject;
import com.neurovision.rehab.iam.domain.model.valueobjects.EmailAddress;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.Objects;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import software.amazon.awssdk.services.cognitoidentityprovider.CognitoIdentityProviderClient;
import software.amazon.awssdk.services.cognitoidentityprovider.model.AdminAddUserToGroupRequest;
import software.amazon.awssdk.services.cognitoidentityprovider.model.AdminCreateUserRequest;
import software.amazon.awssdk.services.cognitoidentityprovider.model.AdminCreateUserResponse;
import software.amazon.awssdk.services.cognitoidentityprovider.model.AdminDeleteUserRequest;
import software.amazon.awssdk.services.cognitoidentityprovider.model.AttributeType;
import software.amazon.awssdk.services.cognitoidentityprovider.model.DeliveryMediumType;
import software.amazon.awssdk.services.cognitoidentityprovider.model.MessageActionType;

@ApplicationScoped
public class CognitoPatientIdentityProvider implements PatientIdentityProvider {

    private static final String PATIENT_GROUP = "PATIENT";
    private static final String SUBJECT_ATTRIBUTE = "sub";

    private final CognitoIdentityProviderClient cognitoClient;
    private final String userPoolId;

    @Inject
    public CognitoPatientIdentityProvider(
            CognitoIdentityProviderClient cognitoClient,
            @ConfigProperty(name = "neurovision.cognito.user-pool-id") String userPoolId) {
        this.cognitoClient = Objects.requireNonNull(cognitoClient, "Cognito client must not be null");
        if (userPoolId == null || userPoolId.isBlank()) {
            throw new IllegalArgumentException("Cognito User Pool ID must not be blank");
        }
        this.userPoolId = userPoolId.trim();
    }

    @Override
    public ProvisionedPatientIdentity invitePatient(EmailAddress patientEmail) {
        Objects.requireNonNull(patientEmail, "Patient email must not be null");
        String createdUsername = null;

        try {
            var createdUser = createWithoutNotification(patientEmail);
            createdUsername = requireUsername(createdUser);
            var patientSubject = requireSubject(createdUser);

            assignPatientGroup(createdUsername);
            sendInvitation(createdUsername);
            return new ProvisionedPatientIdentity(patientSubject);
        } catch (RuntimeException failure) {
            compensateCreatedAccount(createdUsername, failure);
            throw new CognitoPatientProvisioningException(failure);
        }
    }

    private AdminCreateUserResponse createWithoutNotification(EmailAddress patientEmail) {
        var request = AdminCreateUserRequest.builder()
                .userPoolId(userPoolId)
                .username(patientEmail.value())
                .userAttributes(AttributeType.builder()
                        .name("email")
                        .value(patientEmail.value())
                        .build())
                .messageAction(MessageActionType.SUPPRESS)
                .build();
        return cognitoClient.adminCreateUser(request);
    }

    private void assignPatientGroup(String username) {
        var request = AdminAddUserToGroupRequest.builder()
                .userPoolId(userPoolId)
                .username(username)
                .groupName(PATIENT_GROUP)
                .build();
        cognitoClient.adminAddUserToGroup(request);
    }

    private void sendInvitation(String username) {
        var request = AdminCreateUserRequest.builder()
                .userPoolId(userPoolId)
                .username(username)
                .messageAction(MessageActionType.RESEND)
                .desiredDeliveryMediums(DeliveryMediumType.EMAIL)
                .build();
        cognitoClient.adminCreateUser(request);
    }

    private void compensateCreatedAccount(String username, RuntimeException originalFailure) {
        if (username == null) {
            return;
        }

        try {
            cognitoClient.adminDeleteUser(AdminDeleteUserRequest.builder()
                    .userPoolId(userPoolId)
                    .username(username)
                    .build());
        } catch (RuntimeException cleanupFailure) {
            originalFailure.addSuppressed(cleanupFailure);
        }
    }

    private String requireUsername(AdminCreateUserResponse response) {
        if (response == null || response.user() == null || response.user().username() == null
                || response.user().username().isBlank()) {
            throw new IllegalStateException("Cognito create-user response did not contain a username");
        }
        return response.user().username();
    }

    private CognitoSubject requireSubject(AdminCreateUserResponse response) {
        return response.user().attributes().stream()
                .filter(attribute -> SUBJECT_ATTRIBUTE.equals(attribute.name()))
                .map(AttributeType::value)
                .filter(value -> value != null && !value.isBlank())
                .findFirst()
                .map(CognitoSubject::new)
                .orElseThrow(() -> new IllegalStateException(
                        "Cognito create-user response did not contain a subject"));
    }
}
