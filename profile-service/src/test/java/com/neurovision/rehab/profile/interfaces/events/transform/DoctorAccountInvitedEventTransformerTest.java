package com.neurovision.rehab.profile.interfaces.events.transform;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class DoctorAccountInvitedEventTransformerTest {

    private DoctorAccountInvitedEventTransformer transformer;

    @BeforeEach
    void setUp() {
        transformer = new DoctorAccountInvitedEventTransformer(
                new ObjectMapper().findAndRegisterModules());
    }

    @Test
    void shouldTransformTheExternalEventIntoAnApplicationCommand() {
        var eventId = UUID.fromString("8c560471-7dd4-4dc4-9249-b18d6545a174");
        var occurredAt = Instant.parse("2026-08-11T21:04:30Z");
        var payload = """
                {
                  "eventId": "8c560471-7dd4-4dc4-9249-b18d6545a174",
                  "invitationId": "d8300253-c3bd-4354-b2ff-0d2921576ea8",
                  "invitedByAdministrator": "admin-cognito-sub",
                  "doctorSubject": "doctor-cognito-sub",
                  "occurredAt": "2026-08-11T21:04:30Z"
                }
                """;

        var command = transformer.toCommand(payload);

        assertEquals(eventId, command.eventId());
        assertEquals("doctor-cognito-sub", command.doctorSubject());
        assertEquals(occurredAt, command.occurredAt());
    }

    @Test
    void shouldRejectMalformedJsonWithoutExposingItsContents() {
        var exception = assertThrows(
                InvalidDoctorAccountInvitedEventException.class,
                () -> transformer.toCommand("{doctor-email: secret@example.com}"));

        assertEquals("Invalid DoctorAccountInvitedV1 payload", exception.getMessage());
    }

    @Test
    void shouldRejectAnIncompleteExternalContract() {
        var payload = """
                {
                  "eventId": "8c560471-7dd4-4dc4-9249-b18d6545a174",
                  "doctorSubject": "doctor-cognito-sub",
                  "occurredAt": "2026-08-11T21:04:30Z"
                }
                """;

        assertThrows(
                InvalidDoctorAccountInvitedEventException.class,
                () -> transformer.toCommand(payload));
    }
}
