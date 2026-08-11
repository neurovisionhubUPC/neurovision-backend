package com.neurovision.rehab.clinical.application.internal.commandservices;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class RegisterInvitedClinicalPatientCommandTest {

    @Test
    void shouldNormalizeSubjects() {
        var command = new RegisterInvitedClinicalPatientCommand(
                UUID.fromString("112e0134-5d78-47a4-965b-7572a54c47f4"),
                " 7458e448-6051-7081-2745-186f682d626c ",
                " 84d84438-f031-70ea-fd6e-3b4011bbb46e ",
                Instant.parse("2026-08-11T04:29:12Z"));

        assertEquals("7458e448-6051-7081-2745-186f682d626c", command.doctorSubject());
        assertEquals("84d84438-f031-70ea-fd6e-3b4011bbb46e", command.patientSubject());
    }

    @Test
    void shouldRejectMissingEventData() {
        var occurredAt = Instant.parse("2026-08-11T04:29:12Z");
        var eventId = UUID.fromString("112e0134-5d78-47a4-965b-7572a54c47f4");

        assertThrows(
                NullPointerException.class,
                () -> new RegisterInvitedClinicalPatientCommand(
                        null, "doctor-sub", "patient-sub", occurredAt));
        assertThrows(
                IllegalArgumentException.class,
                () -> new RegisterInvitedClinicalPatientCommand(
                        eventId, " ", "patient-sub", occurredAt));
        assertThrows(
                IllegalArgumentException.class,
                () -> new RegisterInvitedClinicalPatientCommand(
                        eventId, "doctor-sub", " ", occurredAt));
        assertThrows(
                NullPointerException.class,
                () -> new RegisterInvitedClinicalPatientCommand(
                        eventId, "doctor-sub", "patient-sub", null));
    }
}
