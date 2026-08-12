package com.neurovision.rehab.iam.infrastructure.security.cognito;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.neurovision.rehab.iam.domain.model.valueobjects.EmailAddress;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.cognitoidentityprovider.CognitoIdentityProviderClient;
import software.amazon.awssdk.services.cognitoidentityprovider.model.AdminAddUserToGroupRequest;
import software.amazon.awssdk.services.cognitoidentityprovider.model.AdminAddUserToGroupResponse;
import software.amazon.awssdk.services.cognitoidentityprovider.model.AdminCreateUserRequest;
import software.amazon.awssdk.services.cognitoidentityprovider.model.AdminCreateUserResponse;
import software.amazon.awssdk.services.cognitoidentityprovider.model.AdminDeleteUserRequest;
import software.amazon.awssdk.services.cognitoidentityprovider.model.AdminDeleteUserResponse;
import software.amazon.awssdk.services.cognitoidentityprovider.model.AttributeType;
import software.amazon.awssdk.services.cognitoidentityprovider.model.DeliveryMediumType;
import software.amazon.awssdk.services.cognitoidentityprovider.model.MessageActionType;
import software.amazon.awssdk.services.cognitoidentityprovider.model.UserType;

class CognitoDoctorIdentityProviderTest {

    private static final String USER_POOL_ID = "us-east-1_example";
    private static final String GENERATED_USERNAME = "doctor-generated-username";
    private static final String DOCTOR_SUBJECT = "doctor-cognito-subject";
    private static final EmailAddress DOCTOR_EMAIL = new EmailAddress("doctor@example.com");

    private CognitoIdentityProviderClient cognitoClient;
    private CognitoDoctorIdentityProvider identityProvider;

    @BeforeEach
    void setUp() {
        cognitoClient = mock(CognitoIdentityProviderClient.class);
        identityProvider = new CognitoDoctorIdentityProvider(cognitoClient, USER_POOL_ID);
    }

    @Test
    void shouldCreateGroupAndNotifyTheDoctorInOrder() {
        when(cognitoClient.adminCreateUser(any(AdminCreateUserRequest.class)))
                .thenReturn(createdUserResponse(), AdminCreateUserResponse.builder().build());
        when(cognitoClient.adminAddUserToGroup(any(AdminAddUserToGroupRequest.class)))
                .thenReturn(AdminAddUserToGroupResponse.builder().build());

        var result = identityProvider.inviteDoctor(DOCTOR_EMAIL);

        assertEquals(DOCTOR_SUBJECT, result.doctorSubject().value());
        var ordered = inOrder(cognitoClient);
        ordered.verify(cognitoClient).adminCreateUser(
                org.mockito.ArgumentMatchers.<AdminCreateUserRequest>argThat(this::isSuppressedCreation));
        ordered.verify(cognitoClient).adminAddUserToGroup(
                org.mockito.ArgumentMatchers.<AdminAddUserToGroupRequest>argThat(this::isDoctorGroupAssignment));
        ordered.verify(cognitoClient).adminCreateUser(
                org.mockito.ArgumentMatchers.<AdminCreateUserRequest>argThat(this::isEmailResend));
        verify(cognitoClient, never()).adminDeleteUser(any(AdminDeleteUserRequest.class));
    }

    @Test
    void shouldDeleteTheCreatedAccountWhenGroupAssignmentFails() {
        var groupFailure = new RuntimeException("Group assignment failed");
        when(cognitoClient.adminCreateUser(any(AdminCreateUserRequest.class))).thenReturn(createdUserResponse());
        when(cognitoClient.adminAddUserToGroup(any(AdminAddUserToGroupRequest.class))).thenThrow(groupFailure);
        when(cognitoClient.adminDeleteUser(any(AdminDeleteUserRequest.class)))
                .thenReturn(AdminDeleteUserResponse.builder().build());

        var error = assertThrows(
                CognitoDoctorProvisioningException.class,
                () -> identityProvider.inviteDoctor(DOCTOR_EMAIL));

        assertSame(groupFailure, error.getCause());
        verify(cognitoClient).adminDeleteUser(
                org.mockito.ArgumentMatchers.<AdminDeleteUserRequest>argThat(this::deletesGeneratedUsername));
    }

    @Test
    void shouldPreserveTheOriginalFailureWhenCompensationAlsoFails() {
        var resendFailure = new RuntimeException("Invitation delivery failed");
        var cleanupFailure = new RuntimeException("Cleanup failed");
        when(cognitoClient.adminCreateUser(any(AdminCreateUserRequest.class)))
                .thenReturn(createdUserResponse())
                .thenThrow(resendFailure);
        when(cognitoClient.adminAddUserToGroup(any(AdminAddUserToGroupRequest.class)))
                .thenReturn(AdminAddUserToGroupResponse.builder().build());
        when(cognitoClient.adminDeleteUser(any(AdminDeleteUserRequest.class))).thenThrow(cleanupFailure);

        var error = assertThrows(
                CognitoDoctorProvisioningException.class,
                () -> identityProvider.inviteDoctor(DOCTOR_EMAIL));

        assertSame(resendFailure, error.getCause());
        assertEquals(List.of(cleanupFailure), List.of(error.getCause().getSuppressed()));
    }

    private AdminCreateUserResponse createdUserResponse() {
        return AdminCreateUserResponse.builder()
                .user(UserType.builder()
                        .username(GENERATED_USERNAME)
                        .attributes(
                                AttributeType.builder().name("sub").value(DOCTOR_SUBJECT).build(),
                                AttributeType.builder().name("email").value(DOCTOR_EMAIL.value()).build())
                        .build())
                .build();
    }

    private boolean isSuppressedCreation(AdminCreateUserRequest request) {
        return USER_POOL_ID.equals(request.userPoolId())
                && DOCTOR_EMAIL.value().equals(request.username())
                && request.messageAction() == MessageActionType.SUPPRESS
                && request.userAttributes().stream()
                        .anyMatch(attribute -> "email".equals(attribute.name())
                                && DOCTOR_EMAIL.value().equals(attribute.value()));
    }

    private boolean isDoctorGroupAssignment(AdminAddUserToGroupRequest request) {
        return USER_POOL_ID.equals(request.userPoolId())
                && GENERATED_USERNAME.equals(request.username())
                && "DOCTOR".equals(request.groupName());
    }

    private boolean isEmailResend(AdminCreateUserRequest request) {
        return USER_POOL_ID.equals(request.userPoolId())
                && DOCTOR_EMAIL.value().equals(request.username())
                && request.messageAction() == MessageActionType.RESEND
                && request.desiredDeliveryMediums().equals(List.of(DeliveryMediumType.EMAIL));
    }

    private boolean deletesGeneratedUsername(AdminDeleteUserRequest request) {
        return USER_POOL_ID.equals(request.userPoolId()) && GENERATED_USERNAME.equals(request.username());
    }
}
