package com.neurovision.rehab.iam.infrastructure.security.cognito;

import com.neurovision.rehab.iam.application.ports.out.identity.DoctorIdentityProvider;
import com.neurovision.rehab.iam.application.ports.out.identity.ProvisionedDoctorIdentity;
import com.neurovision.rehab.iam.domain.model.valueobjects.CognitoSubject;
import com.neurovision.rehab.iam.domain.model.valueobjects.EmailAddress;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.Objects;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;
import software.amazon.awssdk.awscore.exception.AwsServiceException;
import software.amazon.awssdk.services.cognitoidentityprovider.CognitoIdentityProviderClient;
import software.amazon.awssdk.services.cognitoidentityprovider.model.AdminAddUserToGroupRequest;
import software.amazon.awssdk.services.cognitoidentityprovider.model.AdminCreateUserRequest;
import software.amazon.awssdk.services.cognitoidentityprovider.model.AdminCreateUserResponse;
import software.amazon.awssdk.services.cognitoidentityprovider.model.AdminDeleteUserRequest;
import software.amazon.awssdk.services.cognitoidentityprovider.model.AttributeType;
import software.amazon.awssdk.services.cognitoidentityprovider.model.DeliveryMediumType;
import software.amazon.awssdk.services.cognitoidentityprovider.model.MessageActionType;

@ApplicationScoped
public class CognitoDoctorIdentityProvider implements DoctorIdentityProvider {

    private static final Logger LOG = Logger.getLogger(CognitoDoctorIdentityProvider.class);
    private static final String DOCTOR_GROUP = "DOCTOR";
    private static final String SUBJECT_ATTRIBUTE = "sub";

    private final CognitoIdentityProviderClient cognitoClient;
    private final String userPoolId;

    @Inject
    public CognitoDoctorIdentityProvider(
            CognitoIdentityProviderClient cognitoClient,
            @ConfigProperty(name = "neurovision.cognito.user-pool-id") String userPoolId) {
        this.cognitoClient = Objects.requireNonNull(cognitoClient, "Cognito client must not be null");
        if (userPoolId == null || userPoolId.isBlank()) {
            throw new IllegalArgumentException("Cognito User Pool ID must not be blank");
        }
        this.userPoolId = userPoolId.trim();
    }

    @Override
    public ProvisionedDoctorIdentity inviteDoctor(EmailAddress doctorEmail) {
        Objects.requireNonNull(doctorEmail, "Doctor email must not be null");
        String createdUsername = null;

        try {
            var createdUser = createWithoutNotification(doctorEmail);
            createdUsername = requireUsername(createdUser);
            var doctorSubject = requireSubject(createdUser);

            assignDoctorGroup(createdUsername);
            sendInvitation(doctorEmail);
            return new ProvisionedDoctorIdentity(doctorSubject);
        } catch (RuntimeException failure) {
            logProvisioningFailure(failure);
            compensateCreatedAccount(createdUsername, failure);
            throw new CognitoDoctorProvisioningException(failure);
        }
    }

    private AdminCreateUserResponse createWithoutNotification(EmailAddress doctorEmail) {
        var request = AdminCreateUserRequest.builder()
                .userPoolId(userPoolId)
                .username(doctorEmail.value())
                .userAttributes(AttributeType.builder()
                        .name("email")
                        .value(doctorEmail.value())
                        .build())
                .messageAction(MessageActionType.SUPPRESS)
                .build();
        return cognitoClient.adminCreateUser(request);
    }

    private void assignDoctorGroup(String username) {
        cognitoClient.adminAddUserToGroup(AdminAddUserToGroupRequest.builder()
                .userPoolId(userPoolId)
                .username(username)
                .groupName(DOCTOR_GROUP)
                .build());
    }

    private void sendInvitation(EmailAddress doctorEmail) {
        cognitoClient.adminCreateUser(AdminCreateUserRequest.builder()
                .userPoolId(userPoolId)
                .username(doctorEmail.value())
                .messageAction(MessageActionType.RESEND)
                .desiredDeliveryMediums(DeliveryMediumType.EMAIL)
                .build());
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

    private void logProvisioningFailure(RuntimeException failure) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof AwsServiceException awsFailure) {
                LOG.errorf(
                        "Cognito doctor provisioning failed [errorCode=%s, statusCode=%d, requestId=%s]",
                        awsFailure.awsErrorDetails().errorCode(),
                        awsFailure.statusCode(),
                        awsFailure.requestId());
                return;
            }
        }
        LOG.errorf(
                "Cognito doctor provisioning failed [exceptionType=%s]",
                failure.getClass().getName());
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
