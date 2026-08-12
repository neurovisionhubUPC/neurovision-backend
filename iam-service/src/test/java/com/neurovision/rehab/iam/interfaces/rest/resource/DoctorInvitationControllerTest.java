package com.neurovision.rehab.iam.interfaces.rest.resource;

import static io.restassured.RestAssured.given;
import static io.restassured.http.ContentType.JSON;
import static org.hamcrest.Matchers.equalTo;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.neurovision.rehab.iam.application.internal.commandservices.ActivateDoctorInvitationCommandService;
import com.neurovision.rehab.iam.application.internal.commandservices.DoctorAlreadyInvitedException;
import com.neurovision.rehab.iam.application.internal.commandservices.DoctorInvitationNotFoundException;
import com.neurovision.rehab.iam.application.internal.commandservices.DoctorInvitationProvisioningException;
import com.neurovision.rehab.iam.application.internal.commandservices.InviteDoctorCommandService;
import com.neurovision.rehab.iam.domain.model.aggregates.DoctorInvitation;
import com.neurovision.rehab.iam.domain.model.commands.ActivateDoctorInvitationCommand;
import com.neurovision.rehab.iam.domain.model.commands.InviteDoctorCommand;
import com.neurovision.rehab.iam.domain.model.valueobjects.CognitoSubject;
import com.neurovision.rehab.iam.domain.model.valueobjects.EmailAddress;
import com.neurovision.rehab.iam.domain.model.valueobjects.InvitationId;
import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import io.quarkus.test.security.oidc.Claim;
import io.quarkus.test.security.oidc.OidcSecurity;
import jakarta.json.Json;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

@QuarkusTest
class DoctorInvitationControllerTest {

    private static final String ENDPOINT = "/api/v1/doctor-invitations";
    private static final String ADMIN_SUBJECT = "admin-cognito-sub";
    private static final String DOCTOR_SUBJECT = "doctor-cognito-sub";

    @InjectMock
    InviteDoctorCommandService invitationService;

    @InjectMock
    ActivateDoctorInvitationCommandService activationService;

    @BeforeEach
    void resetServices() {
        reset(invitationService, activationService);
    }

    @Test
    @TestSecurity(user = "admin", roles = "ADMIN")
    @OidcSecurity(claims = @Claim(key = "sub", value = ADMIN_SUBJECT))
    void shouldCreateDoctorInvitationForAdministrator() {
        when(invitationService.handle(any())).thenReturn(invitedInvitation());

        given()
                .contentType(JSON)
                .body("""
                        {"doctorEmail":"Doctor@Example.com"}
                        """)
                .when()
                .post(ENDPOINT)
                .then()
                .statusCode(201)
                .contentType(JSON)
                .body("invitationId", equalTo("71111111-1111-1111-1111-111111111111"))
                .body("doctorEmail", equalTo("doctor@example.com"))
                .body("doctorSubject", equalTo(DOCTOR_SUBJECT))
                .body("status", equalTo("INVITED"));

        var command = ArgumentCaptor.forClass(InviteDoctorCommand.class);
        verify(invitationService).handle(command.capture());
        org.junit.jupiter.api.Assertions.assertEquals(
                "doctor@example.com", command.getValue().doctorEmail().value());
        org.junit.jupiter.api.Assertions.assertEquals(
                ADMIN_SUBJECT, command.getValue().invitedByAdministrator().value());
    }

    @Test
    @TestSecurity(user = "doctor", roles = "DOCTOR")
    @OidcSecurity(claims = {
        @Claim(key = "sub", value = DOCTOR_SUBJECT),
        @Claim(key = "auth_time", value = "1786460400")
    })
    void shouldActivateInvitationFromVerifiedDoctorAuthentication() {
        when(activationService.handle(any())).thenReturn(activatedInvitation());

        given()
                .when()
                .put(ENDPOINT + "/me/activation")
                .then()
                .statusCode(200)
                .body("status", equalTo("ACTIVATED"))
                .body("activatedAt", equalTo("2026-08-11T15:00:00Z"));

        var command = ArgumentCaptor.forClass(ActivateDoctorInvitationCommand.class);
        verify(activationService).handle(command.capture());
        org.junit.jupiter.api.Assertions.assertEquals(
                DOCTOR_SUBJECT, command.getValue().doctorSubject().value());
        org.junit.jupiter.api.Assertions.assertEquals(
                Instant.parse("2026-08-11T15:00:00Z"), command.getValue().authenticatedAt());
    }

    @Test
    void shouldReadNativeJsonNumberAuthenticationTime() {
        org.junit.jupiter.api.Assertions.assertEquals(
                Instant.parse("2026-08-11T15:00:00Z"),
                DoctorInvitationController.toAuthenticationTime(Json.createValue(1786460400L)));
    }

    @Test
    @TestSecurity(user = "doctor", roles = "DOCTOR")
    @OidcSecurity(claims = {
        @Claim(key = "sub", value = DOCTOR_SUBJECT),
        @Claim(key = "auth_time", value = "1786460400")
    })
    void shouldReturnNotFoundWhenDoctorWasNotInvited() {
        when(activationService.handle(any())).thenThrow(new DoctorInvitationNotFoundException());

        given()
                .when()
                .put(ENDPOINT + "/me/activation")
                .then()
                .statusCode(404)
                .body("code", equalTo("DOCTOR_INVITATION_NOT_FOUND"));
    }

    @Test
    void shouldRejectAnonymousInvitationRequests() {
        given()
                .contentType(JSON)
                .body("{\"doctorEmail\":\"doctor@example.com\"}")
                .when()
                .post(ENDPOINT)
                .then()
                .statusCode(401);

        verifyNoInteractions(invitationService);
    }

    @Test
    @TestSecurity(user = "doctor", roles = "DOCTOR")
    @OidcSecurity(claims = @Claim(key = "sub", value = DOCTOR_SUBJECT))
    void shouldForbidDoctorFromInvitingAnotherDoctor() {
        given()
                .contentType(JSON)
                .body("{\"doctorEmail\":\"doctor@example.com\"}")
                .when()
                .post(ENDPOINT)
                .then()
                .statusCode(403);

        verifyNoInteractions(invitationService);
    }

    @Test
    @TestSecurity(user = "admin", roles = "ADMIN")
    @OidcSecurity(claims = {
        @Claim(key = "sub", value = ADMIN_SUBJECT),
        @Claim(key = "auth_time", value = "1786460400")
    })
    void shouldForbidAdministratorFromActivatingDoctorInvitation() {
        given()
                .when()
                .put(ENDPOINT + "/me/activation")
                .then()
                .statusCode(403);

        verifyNoInteractions(activationService);
    }

    @Test
    @TestSecurity(user = "admin", roles = "ADMIN")
    @OidcSecurity(claims = @Claim(key = "sub", value = ADMIN_SUBJECT))
    void shouldRejectInvalidDoctorEmail() {
        given()
                .contentType(JSON)
                .body("{\"doctorEmail\":\"not-an-email\"}")
                .when()
                .post(ENDPOINT)
                .then()
                .statusCode(400);

        verifyNoInteractions(invitationService);
    }

    @Test
    @TestSecurity(user = "admin", roles = "ADMIN")
    @OidcSecurity(claims = @Claim(key = "sub", value = ADMIN_SUBJECT))
    void shouldReturnConflictWhenDoctorHasActiveInvitation() {
        when(invitationService.handle(any())).thenThrow(new DoctorAlreadyInvitedException());

        given()
                .contentType(JSON)
                .body("{\"doctorEmail\":\"doctor@example.com\"}")
                .when()
                .post(ENDPOINT)
                .then()
                .statusCode(409)
                .body("code", equalTo("DOCTOR_ALREADY_INVITED"));
    }

    @Test
    @TestSecurity(user = "admin", roles = "ADMIN")
    @OidcSecurity(claims = @Claim(key = "sub", value = ADMIN_SUBJECT))
    void shouldReturnBadGatewayWhenCognitoProvisioningFails() {
        when(invitationService.handle(any()))
                .thenThrow(new DoctorInvitationProvisioningException(
                        new RuntimeException("Cognito unavailable")));

        given()
                .contentType(JSON)
                .body("{\"doctorEmail\":\"doctor@example.com\"}")
                .when()
                .post(ENDPOINT)
                .then()
                .statusCode(502)
                .body("code", equalTo("DOCTOR_IDENTITY_PROVIDER_UNAVAILABLE"));
    }

    private static DoctorInvitation invitedInvitation() {
        var invitation = DoctorInvitation.initiate(
                new InvitationId(UUID.fromString("71111111-1111-1111-1111-111111111111")),
                new CognitoSubject(ADMIN_SUBJECT),
                new EmailAddress("doctor@example.com"),
                Instant.parse("2026-08-10T15:00:00Z"));
        invitation.markInvited(
                new CognitoSubject(DOCTOR_SUBJECT), Instant.parse("2026-08-10T15:00:01Z"));
        return invitation;
    }

    private static DoctorInvitation activatedInvitation() {
        var invitation = invitedInvitation();
        invitation.markActivated(Instant.parse("2026-08-11T15:00:00Z"));
        return invitation;
    }
}
