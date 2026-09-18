package com.neurovision.rehab.clinical.interfaces.rest.transform;

import com.fasterxml.jackson.databind.JsonNode;
import com.neurovision.rehab.clinical.application.internal.commandservices.AppendTherapeuticConfigurationCommand;
import com.neurovision.rehab.clinical.application.internal.commandservices.CreateTherapyPlanCommand;
import com.neurovision.rehab.clinical.domain.model.aggregates.TherapeuticExerciseConfiguration;
import com.neurovision.rehab.clinical.domain.model.aggregates.TherapyPlan;
import com.neurovision.rehab.clinical.domain.model.valueobjects.*;
import com.neurovision.rehab.clinical.domain.model.valueobjects.TherapeuticExerciseParameters.*;
import com.neurovision.rehab.clinical.interfaces.rest.resource.*;
import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;

public final class TherapyPlanRestAssembler {
    private TherapyPlanRestAssembler() {}

    public static CreateTherapyPlanCommand toCommand(
            UUID patientId, CognitoSubject doctor, CreateTherapyPlanRequest request) {
        Objects.requireNonNull(request, "Request body is required");
        if (request.dailyExercisePrescriptions() == null) {
            throw new IllegalArgumentException("Exercise prescriptions are required");
        }
        var prescriptions = new TreeMap<ExerciseCode, Integer>();
        request.dailyExercisePrescriptions().forEach((rawCode, quantity) -> {
            var code = TherapeuticExercise.from(new ExerciseCode(rawCode)).code();
            if (quantity == null || quantity < 1 || prescriptions.putIfAbsent(code, quantity) != null) {
                throw new IllegalArgumentException("Invalid or duplicate prescribed exercise");
            }
        });
        return new CreateTherapyPlanCommand(new ClinicalPatientId(patientId), doctor,
                request.dailySessionLimit(), request.dailyTargetDurationMinutes(),
                request.dailyHardSafetyLimitMinutes(), request.minimumValidDurationMinutes(),
                request.maximumSessionDurationMinutes(), request.minimumValidSampleCount(), prescriptions);
    }

    public static AppendTherapeuticConfigurationCommand toCommand(
            UUID patientId, UUID planId, CognitoSubject doctor,
            AppendTherapeuticConfigurationRequest request) {
        Objects.requireNonNull(request, "Request body is required");
        var exercise = TherapeuticExercise.from(new ExerciseCode(request.exerciseCode()));
        if (request.parameterSchemaVersion() != TherapeuticExerciseParameters.SCHEMA_VERSION) {
            throw new IllegalArgumentException("Unsupported parameter schema version");
        }
        if (request.effectiveFrom() == null) throw new IllegalArgumentException("Effective time is required");
        return new AppendTherapeuticConfigurationCommand(new TherapyPlanId(planId),
                new ClinicalPatientId(patientId), doctor, exercise, request.parameterSchemaVersion(),
                request.effectiveFrom(), parameters(exercise, request.parameters()));
    }

    public static TherapyPlanResponse toResponse(TherapyPlan plan) {
        var prescriptions = new TreeMap<String, Integer>();
        plan.dailyExercisePrescriptions().forEach((code, quantity) -> prescriptions.put(code.value(), quantity));
        return new TherapyPlanResponse(plan.id().value(), plan.clinicalPatientId().value(),
                plan.status().name(), plan.configurationVersion(), plan.dailySessionLimit(),
                plan.dailyTargetDurationMinutes(),
                plan.dailyHardSafetyLimitMinutes().isPresent() ? plan.dailyHardSafetyLimitMinutes().getAsInt() : null,
                plan.minimumValidDurationMinutes(), plan.maximumSessionDurationMinutes(),
                plan.minimumValidSampleCount(), Map.copyOf(prescriptions));
    }

    public static TherapeuticConfigurationResponse toResponse(TherapeuticExerciseConfiguration configuration) {
        return new TherapeuticConfigurationResponse(configuration.id().value(),
                configuration.therapyPlanId().value(), configuration.exercise().code().value(),
                configuration.version(), configuration.parameterSchemaVersion(),
                configuration.effectiveFrom(), configuration.status().name());
    }

    private static TherapeuticExerciseParameters parameters(TherapeuticExercise exercise, JsonNode json) {
        if (json == null || !json.isObject()) throw new IllegalArgumentException("Parameters must be a JSON object");
        return switch (exercise) {
            case VISUAL_SCANNING -> {
                exact(json, "mode", "affectedHemifield", "minimumFixationMs", "maximumStimulusDurationMs",
                        "eccentricityDegrees", "targetSizePx", "repetitions", "interStimulusIntervalMs", "difficultyLevel");
                yield new VisualScanning(enumValue(json, "mode", VisualScanningMode.class),
                        enumValue(json, "affectedHemifield", AffectedHemifield.class),
                        integer(json, "minimumFixationMs"), integer(json, "maximumStimulusDurationMs"),
                        integer(json, "eccentricityDegrees"), integer(json, "targetSizePx"),
                        integer(json, "repetitions"), integer(json, "interStimulusIntervalMs"),
                        integer(json, "difficultyLevel"));
            }
            case VISUAL_SEARCH -> {
                exact(json, "mode", "affectedHemifield", "targetCount", "distractorCount", "minimumFixationMs",
                        "maximumSearchTimeMs", "affectedHemifieldBias", "targetSizePx", "difficultyLevel");
                yield new VisualSearch(enumValue(json, "mode", VisualSearchMode.class),
                        enumValue(json, "affectedHemifield", AffectedHemifield.class),
                        integer(json, "targetCount"), integer(json, "distractorCount"),
                        integer(json, "minimumFixationMs"), integer(json, "maximumSearchTimeMs"),
                        decimal(json, "affectedHemifieldBias"), integer(json, "targetSizePx"),
                        integer(json, "difficultyLevel"));
            }
            case FIXATION -> {
                exact(json, "mode", "targetPosition", "minimumFixationMs", "maximumFixationMs",
                        "targetSizePx", "repetitions", "restBetweenRepetitionsMs", "difficultyLevel");
                yield new Fixation(enumValue(json, "mode", FixationMode.class),
                        enumValue(json, "targetPosition", TargetPosition.class),
                        integer(json, "minimumFixationMs"), integer(json, "maximumFixationMs"),
                        integer(json, "targetSizePx"), integer(json, "repetitions"),
                        integer(json, "restBetweenRepetitionsMs"), integer(json, "difficultyLevel"));
            }
        };
    }

    private static void exact(JsonNode json, String... fields) {
        Set<String> actual = new HashSet<>();
        json.fieldNames().forEachRemaining(actual::add);
        if (!actual.equals(Set.of(fields))) throw new IllegalArgumentException("Parameters contain missing or unknown fields");
    }

    private static int integer(JsonNode json, String field) {
        var value = json.get(field);
        if (value == null || !value.isIntegralNumber() || !value.canConvertToInt()) {
            throw new IllegalArgumentException(field + " must be an integer");
        }
        return value.intValue();
    }

    private static java.math.BigDecimal decimal(JsonNode json, String field) {
        var value = json.get(field);
        if (value == null || !value.isNumber()) throw new IllegalArgumentException(field + " must be a number");
        return value.decimalValue();
    }

    private static <E extends Enum<E>> E enumValue(JsonNode json, String field, Class<E> type) {
        var value = json.get(field);
        if (value == null || !value.isTextual()) throw new IllegalArgumentException(field + " must be a string");
        try {
            return Enum.valueOf(type, value.textValue());
        } catch (IllegalArgumentException invalid) {
            throw new IllegalArgumentException("Unsupported " + field, invalid);
        }
    }
}
