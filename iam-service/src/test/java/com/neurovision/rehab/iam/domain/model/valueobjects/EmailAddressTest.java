package com.neurovision.rehab.iam.domain.model.valueobjects;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class EmailAddressTest {

    @Test
    void shouldNormalizeAValidEmailAddress() {
        var email = new EmailAddress("  Patient.Test+rehab@Example.COM  ");

        assertEquals("patient.test+rehab@example.com", email.value());
    }

    @Test
    void shouldRejectInvalidEmailAddresses() {
        assertThrows(IllegalArgumentException.class, () -> new EmailAddress(null));
        assertThrows(IllegalArgumentException.class, () -> new EmailAddress(""));
        assertThrows(IllegalArgumentException.class, () -> new EmailAddress("patient.example.com"));
        assertThrows(IllegalArgumentException.class, () -> new EmailAddress("patient @example.com"));
        assertThrows(IllegalArgumentException.class, () -> new EmailAddress("patient@example"));
    }
}
