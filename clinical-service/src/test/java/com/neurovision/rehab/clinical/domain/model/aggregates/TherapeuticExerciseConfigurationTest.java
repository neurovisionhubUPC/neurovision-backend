package com.neurovision.rehab.clinical.domain.model.aggregates;

import static com.neurovision.rehab.clinical.domain.model.valueobjects.TherapeuticExerciseParameters.*;
import static org.junit.jupiter.api.Assertions.*;

import com.neurovision.rehab.clinical.domain.model.valueobjects.*;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class TherapeuticExerciseConfigurationTest {
    @Test
    void keepsImmutableIdentityVersionAndTypedParameters() {
        var created = Instant.parse("2026-09-14T00:00:00Z");
        var parameters = new Fixation(FixationMode.SUSTAINED_TARGET_FIXATION,
                TargetPosition.CENTER, 500, 1000, 50, 10, 800, 2);
        var configuration = TherapeuticExerciseConfiguration.create(
                new TherapeuticConfigurationId(UUID.randomUUID()), new TherapyPlanId(UUID.randomUUID()),
                TherapeuticExercise.FIXATION, 2, 1, created.plusSeconds(60),
                new CognitoSubject("doctor"), created, parameters);
        assertAll(() -> assertEquals(2, configuration.version()),
                () -> assertSame(parameters, configuration.parameters()),
                () -> assertEquals(TherapeuticConfigurationStatus.ACTIVE, configuration.status()));
    }

    @Test
    void rejectsUnsupportedSchemaMismatchedExerciseAndRetroactiveEffectiveTime() {
        var now = Instant.parse("2026-09-14T00:00:00Z");
        var parameters = new Fixation(FixationMode.SUSTAINED_TARGET_FIXATION,
                TargetPosition.CENTER, 500, 1000, 50, 10, 800, 2);
        assertThrows(IllegalArgumentException.class, () -> create(0, TherapeuticExercise.FIXATION, parameters, now, now));
        assertThrows(IllegalArgumentException.class, () -> create(1, TherapeuticExercise.VISUAL_SEARCH, parameters, now, now));
        assertThrows(IllegalArgumentException.class, () -> create(1, TherapeuticExercise.FIXATION, parameters, now.minusSeconds(1), now));
    }

    private static TherapeuticExerciseConfiguration create(int schema, TherapeuticExercise exercise,
            TherapeuticExerciseParameters parameters, Instant effective, Instant created) {
        return TherapeuticExerciseConfiguration.create(new TherapeuticConfigurationId(UUID.randomUUID()),
                new TherapyPlanId(UUID.randomUUID()), exercise, 1, schema, effective,
                new CognitoSubject("doctor"), created, parameters);
    }
}
