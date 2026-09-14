package com.neurovision.rehab.clinical.domain.model.valueobjects;

import java.math.BigDecimal;
import java.util.Objects;

/**
 * Server-owned technical parameters for compensatory functional adaptation and monitoring.
 * Schema-v1 bounds are provisional and pending OftalmoSalud clinical validation; they do not
 * establish visual-field recovery or proven clinical efficacy.
 */
public sealed interface TherapeuticExerciseParameters
        permits TherapeuticExerciseParameters.VisualScanning,
                TherapeuticExerciseParameters.VisualSearch,
                TherapeuticExerciseParameters.Fixation {

    int SCHEMA_VERSION = 1;

    TherapeuticExercise exercise();

    enum VisualScanningMode { STANDARD_SCANNING, SYSTEMATIC_EXPLORATION, COMPENSATORY_SACCADE }
    enum VisualSearchMode { TARGET_SEARCH_WITH_DISTRACTORS }
    enum FixationMode { SUSTAINED_TARGET_FIXATION }
    enum AffectedHemifield { LEFT, RIGHT }
    enum TargetPosition { CENTER, LEFT, RIGHT, RANDOM }

    record VisualScanning(
            VisualScanningMode mode,
            AffectedHemifield affectedHemifield,
            int minimumFixationMs,
            int maximumStimulusDurationMs,
            int eccentricityDegrees,
            int targetSizePx,
            int repetitions,
            int interStimulusIntervalMs,
            int difficultyLevel) implements TherapeuticExerciseParameters {
        public VisualScanning {
            Objects.requireNonNull(mode, "Visual scanning mode must not be null");
            Objects.requireNonNull(affectedHemifield, "Affected hemifield must not be null");
            range(minimumFixationMs, 300, 2000, "minimumFixationMs");
            range(maximumStimulusDurationMs, 2000, 10000, "maximumStimulusDurationMs");
            range(eccentricityDegrees, 5, 30, "eccentricityDegrees");
            range(targetSizePx, 30, 150, "targetSizePx");
            range(repetitions, 5, 50, "repetitions");
            range(interStimulusIntervalMs, 500, 3000, "interStimulusIntervalMs");
            range(difficultyLevel, 1, 5, "difficultyLevel");
        }
        @Override public TherapeuticExercise exercise() { return TherapeuticExercise.VISUAL_SCANNING; }
    }

    record VisualSearch(
            VisualSearchMode mode,
            AffectedHemifield affectedHemifield,
            int targetCount,
            int distractorCount,
            int minimumFixationMs,
            int maximumSearchTimeMs,
            BigDecimal affectedHemifieldBias,
            int targetSizePx,
            int difficultyLevel) implements TherapeuticExerciseParameters {
        public VisualSearch {
            Objects.requireNonNull(mode, "Visual search mode must not be null");
            Objects.requireNonNull(affectedHemifield, "Affected hemifield must not be null");
            range(targetCount, 5, 40, "targetCount");
            range(distractorCount, 0, 20, "distractorCount");
            range(minimumFixationMs, 300, 2000, "minimumFixationMs");
            range(maximumSearchTimeMs, 3000, 20000, "maximumSearchTimeMs");
            affectedHemifieldBias = finiteDecimal(affectedHemifieldBias, "affectedHemifieldBias");
            if (affectedHemifieldBias.compareTo(new BigDecimal("0.5")) < 0
                    || affectedHemifieldBias.compareTo(new BigDecimal("1.0")) > 0) {
                throw new IllegalArgumentException("affectedHemifieldBias must be between 0.5 and 1.0");
            }
            range(targetSizePx, 30, 150, "targetSizePx");
            range(difficultyLevel, 1, 5, "difficultyLevel");
        }
        @Override public TherapeuticExercise exercise() { return TherapeuticExercise.VISUAL_SEARCH; }
    }

    record Fixation(
            FixationMode mode,
            TargetPosition targetPosition,
            int minimumFixationMs,
            int maximumFixationMs,
            int targetSizePx,
            int repetitions,
            int restBetweenRepetitionsMs,
            int difficultyLevel) implements TherapeuticExerciseParameters {
        public Fixation {
            Objects.requireNonNull(mode, "Fixation mode must not be null");
            Objects.requireNonNull(targetPosition, "Target position must not be null");
            range(minimumFixationMs, 300, 3000, "minimumFixationMs");
            range(maximumFixationMs, 500, 5000, "maximumFixationMs");
            if (maximumFixationMs < minimumFixationMs) {
                throw new IllegalArgumentException("maximumFixationMs must not be lower than minimumFixationMs");
            }
            range(targetSizePx, 30, 150, "targetSizePx");
            range(repetitions, 5, 30, "repetitions");
            range(restBetweenRepetitionsMs, 500, 3000, "restBetweenRepetitionsMs");
            range(difficultyLevel, 1, 5, "difficultyLevel");
        }
        @Override public TherapeuticExercise exercise() { return TherapeuticExercise.FIXATION; }
    }

    private static void range(int value, int minimum, int maximum, String field) {
        if (value < minimum || value > maximum) {
            throw new IllegalArgumentException(field + " must be between " + minimum + " and " + maximum);
        }
    }

    private static BigDecimal finiteDecimal(BigDecimal value, String field) {
        Objects.requireNonNull(value, field + " must not be null");
        return value.stripTrailingZeros();
    }
}
