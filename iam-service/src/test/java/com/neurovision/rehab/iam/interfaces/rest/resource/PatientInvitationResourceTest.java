package com.neurovision.rehab.iam.interfaces.rest.resource;

import static io.restassured.RestAssured.given;
import static io.restassured.http.ContentType.JSON;
import static org.hamcrest.Matchers.equalTo;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.neurovision.rehab.iam.application.internal.commandservices.InvitePatientCommandService;
import com.neurovision.rehab.iam.application.internal.commandservices.PatientAlreadyInvitedException;
import com.neurovision.rehab.iam.application.internal.commandservices.PatientInvitationProvisioningException;
import com.neurovision.rehab.iam.domain.model.aggregates.PatientInvitation;
import com.neurovision.rehab.iam.domain.model.commands.InvitePatientCommand;
import com.neurovision.rehab.iam.domain.model.valueobjects.CognitoSubject;
import com.neurovision.rehab.iam.domain.model.valueobjects.EmailAddress;
import com.neurovision.rehab.iam.domain.model.valueobjects.InvitationId;
import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import io.quarkus.test.security.oidc.Claim;
import io.quarkus.test.security.oidc.OidcSecurity;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

@QuarkusTest
class PatientInvitationResourceTest {

    private static final String ENDPOINT = "/api/v1/patient-invitations";
    private static final String DOCTOR_SUBJECT = "doctor-cognito-sub";

    @InjectMock
    InvitePatientCommandService commandService;

    @BeforeEach
    void resetCommandService() {
        reset(commandService);
    }

    @Test
    @TestSecurity(user = "doctor", roles = "DOCTOR")
    @OidcSecurity(claims = @Claim(key = "sub", value = DOCTOR_SUBJECT))
    void shouldCreatePatientInvitationForDoctor() {
        when(commandService.handle(any())).thenReturn(sentInvitation());

        given()
                .contentType(JSON)
                .body("""
                        {"patientEmail":"Patient@Example.com"}
                        """)
                .when()
                .post(ENDPOINT)
                .then()
                .statusCode(201)
                .contentType(JSON)
                .body("invitationId", equalTo("11111111-1111-1111-1111-111111111111"))
                .body("patientEmail", equalTo("patient@example.com"))
                .body("patientSubject", equalTo("patient-cognito-sub"))
                .body("status", equalTo("SENT"));

        var command = ArgumentCaptor.forClass(InvitePatientCommand.class);
        verify(commandService).handle(command.capture());
        org.junit.jupiter.api.Assertions.assertEquals(
                "patient@example.com", command.getValue().patientEmail().value());
        org.junit.jupiter.api.Assertions.assertEquals(
                DOCTOR_SUBJECT, command.getValue().invitedByDoctor().value());
    }

    @Test
    void shouldRejectAnonymousRequests() {
        given()
                .contentType(JSON)
                .body("{\"patientEmail\":\"patient@example.com\"}")
                .when()
                .post(ENDPOINT)
                .then()
                .statusCode(401);

        verifyNoInteractions(commandService);
    }

    @Test
    @TestSecurity(user = "patient", roles = "PATIENT")
    @OidcSecurity(claims = @Claim(key = "sub", value = "patient-cognito-sub"))
    void shouldRejectPatientRole() {
        given()
                .contentType(JSON)
                .body("{\"patientEmail\":\"patient@example.com\"}")
                .when()
                .post(ENDPOINT)
                .then()
                .statusCode(403);

        verifyNoInteractions(commandService);
    }

    @Test
    @TestSecurity(user = "doctor", roles = "DOCTOR")
    @OidcSecurity(claims = @Claim(key = "sub", value = DOCTOR_SUBJECT))
    void shouldRejectInvalidEmail() {
        given()
                .contentType(JSON)
                .body("{\"patientEmail\":\"not-an-email\"}")
                .when()
                .post(ENDPOINT)
                .then()
                .statusCode(400);

        verifyNoInteractions(commandService);
    }

    @Test
    @TestSecurity(user = "doctor", roles = "DOCTOR")
    @OidcSecurity(claims = @Claim(key = "sub", value = DOCTOR_SUBJECT))
    void shouldRejectMissingRequestBody() {
        given()
                .contentType(JSON)
                .when()
                .post(ENDPOINT)
                .then()
                .statusCode(400);

        verifyNoInteractions(commandService);
    }

    @Test
    @TestSecurity(user = "doctor", roles = "DOCTOR")
    @OidcSecurity(claims = @Claim(key = "sub", value = DOCTOR_SUBJECT))
    void shouldReturnConflictWhenPatientHasActiveInvitation() {
        when(commandService.handle(any())).thenThrow(new PatientAlreadyInvitedException());

        given()
                .contentType(JSON)
                .body("{\"patientEmail\":\"patient@example.com\"}")
                .when()
                .post(ENDPOINT)
                .then()
                .statusCode(409)
                .body("code", equalTo("PATIENT_ALREADY_INVITED"));
    }

    @Test
    @TestSecurity(user = "doctor", roles = "DOCTOR")
    @OidcSecurity(claims = @Claim(key = "sub", value = DOCTOR_SUBJECT))
    void shouldReturnBadGatewayWhenCognitoProvisioningFails() {
        when(commandService.handle(any()))
                .thenThrow(new PatientInvitationProvisioningException(
                        new RuntimeException("Cognito unavailable")));

        given()
                .contentType(JSON)
                .body("{\"patientEmail\":\"patient@example.com\"}")
                .when()
                .post(ENDPOINT)
                .then()
                .statusCode(502)
                .body("code", equalTo("PATIENT_IDENTITY_PROVIDER_UNAVAILABLE"));
    }

    private static PatientInvitation sentInvitation() {
        var invitation = PatientInvitation.initiate(
                new InvitationId(UUID.fromString("11111111-1111-1111-1111-111111111111")),
                new CognitoSubject(DOCTOR_SUBJECT),
                new EmailAddress("patient@example.com"),
                Instant.parse("2026-08-10T15:00:00Z"));
        invitation.markSent(
                new CognitoSubject("patient-cognito-sub"),
                Instant.parse("2026-08-10T15:00:01Z"));
        return invitation;
    }
}
