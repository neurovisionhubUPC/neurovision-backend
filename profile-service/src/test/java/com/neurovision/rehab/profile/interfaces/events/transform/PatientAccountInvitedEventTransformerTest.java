package com.neurovision.rehab.profile.interfaces.events.transform;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class PatientAccountInvitedEventTransformerTest {

    private PatientAccountInvitedEventTransformer transformer;

    @BeforeEach
    void setUp() {
        transformer = new PatientAccountInvitedEventTransformer(
                new ObjectMapper().findAndRegisterModules());
    }

    @Test
    void shouldTransformTheExternalEventIntoAnApplicationCommand() {
        var eventId = UUID.fromString("bc560471-7dd4-4dc4-9249-b18d6545a174");
        var occurredAt = Instant.parse("2026-08-10T21:04:30Z");
        var payload = """
                {
                  "eventId": "bc560471-7dd4-4dc4-9249-b18d6545a174",
                  "invitationId": "d8300253-c3bd-4354-b2ff-0d2921576ea8",
                  "invitedByDoctor": "doctor-cognito-sub",
                  "patientSubject": "patient-cognito-sub",
                  "occurredAt": "2026-08-10T21:04:30Z"
                }
                """;

        var command = transformer.toCommand(payload);

        assertEquals(eventId, command.eventId());
        assertEquals("patient-cognito-sub", command.patientSubject());
        assertEquals(occurredAt, command.occurredAt());
    }

    @Test
    void shouldRejectMalformedJsonWithoutExposingItsContents() {
        var exception = assertThrows(
                InvalidPatientAccountInvitedEventException.class,
                () -> transformer.toCommand("{patient-email: secret@example.com}"));

        assertEquals("Invalid PatientAccountInvitedV1 payload", exception.getMessage());
    }

    @Test
    void shouldRejectAnIncompleteExternalContract() {
        var payload = """
                {
                  "eventId": "bc560471-7dd4-4dc4-9249-b18d6545a174",
                  "patientSubject": "patient-cognito-sub",
                  "occurredAt": "2026-08-10T21:04:30Z"
                }
                """;

        assertThrows(
                InvalidPatientAccountInvitedEventException.class,
                () -> transformer.toCommand(payload));
    }
}
