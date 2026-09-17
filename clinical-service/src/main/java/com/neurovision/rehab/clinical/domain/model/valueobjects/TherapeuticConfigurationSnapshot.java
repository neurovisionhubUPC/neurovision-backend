package com.neurovision.rehab.clinical.domain.model.valueobjects;

import com.neurovision.rehab.clinical.domain.model.aggregates.TherapeuticExerciseConfiguration;
import java.util.Objects;

/** Exact immutable therapeutic configuration frozen into a session at admission. */
public record TherapeuticConfigurationSnapshot(
        TherapeuticConfigurationId configurationId,
        long configurationVersion,
        TherapeuticExercise exercise,
        int parameterSchemaVersion,
        TherapeuticExerciseParameters parameters) {

    public TherapeuticConfigurationSnapshot {
        Objects.requireNonNull(configurationId, "Configuration ID must not be null");
        if (configurationVersion < 1) {
            throw new IllegalArgumentException("Configuration version must be positive");
        }
        Objects.requireNonNull(exercise, "Therapeutic exercise must not be null");
        if (parameterSchemaVersion != TherapeuticExerciseParameters.SCHEMA_VERSION) {
            throw new IllegalArgumentException("Unsupported parameter schema version");
        }
        Objects.requireNonNull(parameters, "Configuration parameters must not be null");
        if (parameters.exercise() != exercise) {
            throw new IllegalArgumentException("Parameters do not match therapeutic exercise");
        }
    }

    public static TherapeuticConfigurationSnapshot from(
            TherapeuticExerciseConfiguration configuration) {
        Objects.requireNonNull(configuration, "Configuration must not be null");
        return new TherapeuticConfigurationSnapshot(
                configuration.id(),
                configuration.version(),
                configuration.exercise(),
                configuration.parameterSchemaVersion(),
                configuration.parameters());
    }
}
