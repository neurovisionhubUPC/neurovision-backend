package com.neurovision.rehab.clinical.domain.model.valueobjects;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class ClinicalPersistenceBoundariesTest {
    @Test
    void shouldAlignCognitoSubjectWithDatabaseLength() {
        assertEquals(128, new CognitoSubject("s".repeat(128)).value().length());
        assertThrows(IllegalArgumentException.class, () -> new CognitoSubject("s".repeat(129)));
    }

    @Test
    void shouldCanonicalizeAndBoundExerciseCode() {
        assertEquals("visual-search", new ExerciseCode(" VISUAL-SEARCH ").value());
        assertEquals(100, new ExerciseCode("e".repeat(100)).value().length());
        assertThrows(IllegalArgumentException.class, () -> new ExerciseCode("e".repeat(101)));
        assertThrows(IllegalArgumentException.class, () -> new ExerciseCode("visual search"));
    }

    @Test
    void shouldAlignIdempotencyKeyWithDatabaseLength() {
        assertEquals(128, new IdempotencyKey("k".repeat(128)).value().length());
        assertThrows(IllegalArgumentException.class, () -> new IdempotencyKey("k".repeat(129)));
    }
}
