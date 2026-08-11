package com.neurovision.rehab.profile.domain.model.valueobjects;

import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class ProfileIdTest {

    @Test
    void shouldRejectANullIdentifier() {
        assertThrows(NullPointerException.class, () -> new ProfileId(null));
    }
}
