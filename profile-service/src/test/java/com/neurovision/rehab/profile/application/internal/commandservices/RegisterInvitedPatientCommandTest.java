package com.neurovision.rehab.profile.application.internal.commandservices;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class RegisterInvitedPatientCommandTest {

    private static final UUID EVENT_ID =
            UUID.fromString("bc560471-7dd4-4dc4-9249-b18d6545a174");
    private static final Instant OCCURRED_AT = Instant.parse("2026-08-10T21:04:30Z");

    @Test
    void shouldNormalizeThePatientSubject() {
        var command = new RegisterInvitedPatientCommand(EVENT_ID, "  patient-sub  ", OCCURRED_AT);

        assertEquals("patient-sub", command.patientSubject());
    }

    @Test
    void shouldRejectMissingEventData() {
        assertThrows(
                NullPointerException.class,
                () -> new RegisterInvitedPatientCommand(null, "patient-sub", OCCURRED_AT));
        assertThrows(
                IllegalArgumentException.class,
                () -> new RegisterInvitedPatientCommand(EVENT_ID, " ", OCCURRED_AT));
        assertThrows(
                NullPointerException.class,
                () -> new RegisterInvitedPatientCommand(EVENT_ID, "patient-sub", null));
    }
}
