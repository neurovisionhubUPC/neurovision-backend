package com.neurovision.rehab.profile.domain.model.valueobjects;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class CognitoSubjectTest {

    @Test
    void shouldNormalizeSurroundingWhitespace() {
        assertEquals("patient-subject", new CognitoSubject("  patient-subject  ").value());
    }

    @Test
    void shouldRejectBlankOrOversizedSubjects() {
        assertThrows(IllegalArgumentException.class, () -> new CognitoSubject(" "));
        assertThrows(IllegalArgumentException.class, () -> new CognitoSubject("x".repeat(129)));
    }
}
