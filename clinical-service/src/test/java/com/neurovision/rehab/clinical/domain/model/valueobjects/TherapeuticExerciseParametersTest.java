package com.neurovision.rehab.clinical.domain.model.valueobjects;

import static com.neurovision.rehab.clinical.domain.model.valueobjects.TherapeuticExerciseParameters.*;
import static org.junit.jupiter.api.Assertions.*;

import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

class TherapeuticExerciseParametersTest {
    @Test
    void catalogAndModesAreClosedToTheConfirmedMvp() {
        assertArrayEquals(new TherapeuticExercise[] {TherapeuticExercise.VISUAL_SCANNING,
                TherapeuticExercise.VISUAL_SEARCH, TherapeuticExercise.FIXATION}, TherapeuticExercise.values());
        assertArrayEquals(new VisualScanningMode[] {VisualScanningMode.STANDARD_SCANNING,
                VisualScanningMode.SYSTEMATIC_EXPLORATION, VisualScanningMode.COMPENSATORY_SACCADE},
                VisualScanningMode.values());
        assertArrayEquals(new VisualSearchMode[] {VisualSearchMode.TARGET_SEARCH_WITH_DISTRACTORS}, VisualSearchMode.values());
        assertArrayEquals(new FixationMode[] {FixationMode.SUSTAINED_TARGET_FIXATION}, FixationMode.values());
    }

    @Test
    void acceptsEveryVisualScanningBoundary() {
        assertDoesNotThrow(() -> new VisualScanning(VisualScanningMode.STANDARD_SCANNING,
                AffectedHemifield.LEFT, 300, 2000, 5, 30, 5, 500, 1));
        assertDoesNotThrow(() -> new VisualScanning(VisualScanningMode.COMPENSATORY_SACCADE,
                AffectedHemifield.RIGHT, 2000, 10000, 30, 150, 50, 3000, 5));
    }

    @Test
    void rejectsEveryVisualScanningRangeOutsideItsBoundary() {
        assertScanningInvalid(299, 2000, 5, 30, 5, 500, 1);
        assertScanningInvalid(2001, 2000, 5, 30, 5, 500, 1);
        assertScanningInvalid(300, 1999, 5, 30, 5, 500, 1);
        assertScanningInvalid(300, 10001, 5, 30, 5, 500, 1);
        assertScanningInvalid(300, 2000, 4, 30, 5, 500, 1);
        assertScanningInvalid(300, 2000, 31, 30, 5, 500, 1);
        assertScanningInvalid(300, 2000, 5, 29, 5, 500, 1);
        assertScanningInvalid(300, 2000, 5, 151, 5, 500, 1);
        assertScanningInvalid(300, 2000, 5, 30, 4, 500, 1);
        assertScanningInvalid(300, 2000, 5, 30, 51, 500, 1);
        assertScanningInvalid(300, 2000, 5, 30, 5, 499, 1);
        assertScanningInvalid(300, 2000, 5, 30, 5, 3001, 1);
        assertScanningInvalid(300, 2000, 5, 30, 5, 500, 0);
        assertScanningInvalid(300, 2000, 5, 30, 5, 500, 6);
    }

    @Test
    void acceptsAndRejectsEveryVisualSearchBoundary() {
        assertDoesNotThrow(() -> new VisualSearch(VisualSearchMode.TARGET_SEARCH_WITH_DISTRACTORS,
                AffectedHemifield.LEFT, 5, 0, 300, 3000, new BigDecimal("0.5"), 30, 1));
        assertDoesNotThrow(() -> new VisualSearch(VisualSearchMode.TARGET_SEARCH_WITH_DISTRACTORS,
                AffectedHemifield.RIGHT, 40, 20, 2000, 20000, new BigDecimal("1.0"), 150, 5));
        assertSearchInvalid(4, 0, 300, 3000, "0.5", 30, 1);
        assertSearchInvalid(41, 0, 300, 3000, "0.5", 30, 1);
        assertSearchInvalid(5, -1, 300, 3000, "0.5", 30, 1);
        assertSearchInvalid(5, 21, 300, 3000, "0.5", 30, 1);
        assertSearchInvalid(5, 0, 299, 3000, "0.5", 30, 1);
        assertSearchInvalid(5, 0, 2001, 3000, "0.5", 30, 1);
        assertSearchInvalid(5, 0, 300, 2999, "0.5", 30, 1);
        assertSearchInvalid(5, 0, 300, 20001, "0.5", 30, 1);
        assertSearchInvalid(5, 0, 300, 3000, "0.499", 30, 1);
        assertSearchInvalid(5, 0, 300, 3000, "1.001", 30, 1);
        assertSearchInvalid(5, 0, 300, 3000, "0.5", 29, 1);
        assertSearchInvalid(5, 0, 300, 3000, "0.5", 151, 1);
        assertSearchInvalid(5, 0, 300, 3000, "0.5", 30, 0);
        assertSearchInvalid(5, 0, 300, 3000, "0.5", 30, 6);
    }

    @Test
    void acceptsAndRejectsEveryFixationBoundaryIncludingCrossFieldRule() {
        assertDoesNotThrow(() -> new Fixation(FixationMode.SUSTAINED_TARGET_FIXATION,
                TargetPosition.CENTER, 300, 500, 30, 5, 500, 1));
        assertDoesNotThrow(() -> new Fixation(FixationMode.SUSTAINED_TARGET_FIXATION,
                TargetPosition.RANDOM, 3000, 5000, 150, 30, 3000, 5));
        assertFixationInvalid(299, 500, 30, 5, 500, 1);
        assertFixationInvalid(3001, 5000, 30, 5, 500, 1);
        assertFixationInvalid(300, 499, 30, 5, 500, 1);
        assertFixationInvalid(300, 5001, 30, 5, 500, 1);
        assertFixationInvalid(1000, 999, 30, 5, 500, 1);
        assertFixationInvalid(300, 500, 29, 5, 500, 1);
        assertFixationInvalid(300, 500, 151, 5, 500, 1);
        assertFixationInvalid(300, 500, 30, 4, 500, 1);
        assertFixationInvalid(300, 500, 30, 31, 500, 1);
        assertFixationInvalid(300, 500, 30, 5, 499, 1);
        assertFixationInvalid(300, 500, 30, 5, 3001, 1);
        assertFixationInvalid(300, 500, 30, 5, 500, 0);
        assertFixationInvalid(300, 500, 30, 5, 500, 6);
    }

    private static void assertScanningInvalid(int a, int b, int c, int d, int e, int f, int g) {
        assertThrows(IllegalArgumentException.class, () -> new VisualScanning(
                VisualScanningMode.STANDARD_SCANNING, AffectedHemifield.LEFT, a, b, c, d, e, f, g));
    }
    private static void assertSearchInvalid(int a, int b, int c, int d, String e, int f, int g) {
        assertThrows(IllegalArgumentException.class, () -> new VisualSearch(
                VisualSearchMode.TARGET_SEARCH_WITH_DISTRACTORS, AffectedHemifield.LEFT,
                a, b, c, d, new BigDecimal(e), f, g));
    }
    private static void assertFixationInvalid(int a, int b, int c, int d, int e, int f) {
        assertThrows(IllegalArgumentException.class, () -> new Fixation(
                FixationMode.SUSTAINED_TARGET_FIXATION, TargetPosition.LEFT, a, b, c, d, e, f));
    }
}
