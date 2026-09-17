package com.neurovision.rehab.clinical.support;

import com.neurovision.rehab.clinical.domain.model.aggregates.TherapeuticExerciseConfiguration;
import com.neurovision.rehab.clinical.domain.model.valueobjects.*;
import com.neurovision.rehab.clinical.domain.model.valueobjects.TherapeuticExerciseParameters.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public final class TherapeuticConfigurationTestData {
    private TherapeuticConfigurationTestData() {}

    public static TherapeuticConfigurationSnapshot visualSearchSnapshot() {
        return TherapeuticConfigurationSnapshot.from(visualSearchConfiguration(
                new TherapyPlanId(UUID.fromString("20000000-0000-0000-0000-000000000001")),
                UUID.fromString("40000000-0000-0000-0000-000000000001"), 1,
                Instant.parse("2026-09-13T00:00:00Z")));
    }

    public static TherapeuticExerciseConfiguration visualSearchConfiguration(
            TherapyPlanId planId, UUID id, long version, Instant effectiveFrom) {
        return TherapeuticExerciseConfiguration.create(
                new TherapeuticConfigurationId(id), planId, TherapeuticExercise.VISUAL_SEARCH,
                version, TherapeuticExerciseParameters.SCHEMA_VERSION, effectiveFrom,
                new CognitoSubject("doctor-subject"), effectiveFrom,
                new VisualSearch(VisualSearchMode.TARGET_SEARCH_WITH_DISTRACTORS,
                        AffectedHemifield.LEFT, 15, 8, 700, 10_000,
                        new BigDecimal("0.7"), 70, 2));
    }

    public static TherapeuticConfigurationSnapshot fixationSnapshot() {
        return TherapeuticConfigurationSnapshot.from(fixationConfiguration(
                new TherapyPlanId(UUID.fromString("20000000-0000-0000-0000-000000000001")),
                UUID.fromString("40000000-0000-0000-0000-000000000002"), 1,
                Instant.parse("2026-09-13T00:00:00Z")));
    }

    public static TherapeuticExerciseConfiguration fixationConfiguration(
            TherapyPlanId planId, UUID id, long version, Instant effectiveFrom) {
        return TherapeuticExerciseConfiguration.create(
                new TherapeuticConfigurationId(id), planId, TherapeuticExercise.FIXATION,
                version, TherapeuticExerciseParameters.SCHEMA_VERSION, effectiveFrom,
                new CognitoSubject("doctor-subject"), effectiveFrom,
                new Fixation(FixationMode.SUSTAINED_TARGET_FIXATION, TargetPosition.CENTER,
                        1000, 3000, 70, 10, 1000, 1));
    }
}
