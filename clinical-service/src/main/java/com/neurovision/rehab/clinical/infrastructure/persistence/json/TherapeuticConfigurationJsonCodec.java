package com.neurovision.rehab.clinical.infrastructure.persistence.json;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.neurovision.rehab.clinical.domain.model.valueobjects.TherapeuticExercise;
import com.neurovision.rehab.clinical.domain.model.valueobjects.TherapeuticExerciseParameters;
import com.neurovision.rehab.clinical.domain.model.valueobjects.TherapeuticExerciseParameters.*;
import jakarta.enterprise.context.ApplicationScoped;
import java.math.BigDecimal;
import java.util.HashSet;
import java.util.Set;

@ApplicationScoped
public class TherapeuticConfigurationJsonCodec {
    private final ObjectMapper mapper;

    public TherapeuticConfigurationJsonCodec(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    public String encode(TherapeuticExerciseParameters parameters) {
        ObjectNode json = mapper.createObjectNode();
        switch (parameters) {
            case VisualScanning p -> {
                json.put("mode", p.mode().name());
                json.put("affectedHemifield", p.affectedHemifield().name());
                json.put("minimumFixationMs", p.minimumFixationMs());
                json.put("maximumStimulusDurationMs", p.maximumStimulusDurationMs());
                json.put("eccentricityDegrees", p.eccentricityDegrees());
                json.put("targetSizePx", p.targetSizePx());
                json.put("repetitions", p.repetitions());
                json.put("interStimulusIntervalMs", p.interStimulusIntervalMs());
                json.put("difficultyLevel", p.difficultyLevel());
            }
            case VisualSearch p -> {
                json.put("mode", p.mode().name());
                json.put("affectedHemifield", p.affectedHemifield().name());
                json.put("targetCount", p.targetCount());
                json.put("distractorCount", p.distractorCount());
                json.put("minimumFixationMs", p.minimumFixationMs());
                json.put("maximumSearchTimeMs", p.maximumSearchTimeMs());
                json.put("affectedHemifieldBias", p.affectedHemifieldBias());
                json.put("targetSizePx", p.targetSizePx());
                json.put("difficultyLevel", p.difficultyLevel());
            }
            case Fixation p -> {
                json.put("mode", p.mode().name());
                json.put("targetPosition", p.targetPosition().name());
                json.put("minimumFixationMs", p.minimumFixationMs());
                json.put("maximumFixationMs", p.maximumFixationMs());
                json.put("targetSizePx", p.targetSizePx());
                json.put("repetitions", p.repetitions());
                json.put("restBetweenRepetitionsMs", p.restBetweenRepetitionsMs());
                json.put("difficultyLevel", p.difficultyLevel());
            }
        }
        try {
            return mapper.writeValueAsString(json);
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("Could not encode therapeutic parameters", exception);
        }
    }

    public TherapeuticExerciseParameters decode(
            TherapeuticExercise exercise, int parameterSchemaVersion, String value) {
        if (parameterSchemaVersion != TherapeuticExerciseParameters.SCHEMA_VERSION) {
            throw new IllegalArgumentException("Unsupported parameter schema version");
        }
        final JsonNode json;
        try {
            json = mapper.readTree(value);
        } catch (JsonProcessingException | RuntimeException exception) {
            throw new IllegalArgumentException("Invalid therapeutic parameter JSON", exception);
        }
        if (json == null || !json.isObject()) throw new IllegalArgumentException("Parameters must be a JSON object");
        return switch (exercise) {
            case VISUAL_SCANNING -> decodeScanning(json);
            case VISUAL_SEARCH -> decodeSearch(json);
            case FIXATION -> decodeFixation(json);
        };
    }

    private static VisualScanning decodeScanning(JsonNode json) {
        requireExactly(json, "mode", "affectedHemifield", "minimumFixationMs",
                "maximumStimulusDurationMs", "eccentricityDegrees", "targetSizePx",
                "repetitions", "interStimulusIntervalMs", "difficultyLevel");
        return new VisualScanning(enumValue(json, "mode", VisualScanningMode.class),
                enumValue(json, "affectedHemifield", AffectedHemifield.class),
                integer(json, "minimumFixationMs"), integer(json, "maximumStimulusDurationMs"),
                integer(json, "eccentricityDegrees"), integer(json, "targetSizePx"),
                integer(json, "repetitions"), integer(json, "interStimulusIntervalMs"),
                integer(json, "difficultyLevel"));
    }

    private static VisualSearch decodeSearch(JsonNode json) {
        requireExactly(json, "mode", "affectedHemifield", "targetCount", "distractorCount",
                "minimumFixationMs", "maximumSearchTimeMs", "affectedHemifieldBias",
                "targetSizePx", "difficultyLevel");
        return new VisualSearch(enumValue(json, "mode", VisualSearchMode.class),
                enumValue(json, "affectedHemifield", AffectedHemifield.class),
                integer(json, "targetCount"), integer(json, "distractorCount"),
                integer(json, "minimumFixationMs"), integer(json, "maximumSearchTimeMs"),
                decimal(json, "affectedHemifieldBias"), integer(json, "targetSizePx"),
                integer(json, "difficultyLevel"));
    }

    private static Fixation decodeFixation(JsonNode json) {
        requireExactly(json, "mode", "targetPosition", "minimumFixationMs", "maximumFixationMs",
                "targetSizePx", "repetitions", "restBetweenRepetitionsMs", "difficultyLevel");
        return new Fixation(enumValue(json, "mode", FixationMode.class),
                enumValue(json, "targetPosition", TargetPosition.class),
                integer(json, "minimumFixationMs"), integer(json, "maximumFixationMs"),
                integer(json, "targetSizePx"), integer(json, "repetitions"),
                integer(json, "restBetweenRepetitionsMs"), integer(json, "difficultyLevel"));
    }

    private static void requireExactly(JsonNode json, String... fields) {
        Set<String> expected = Set.of(fields);
        Set<String> actual = new HashSet<>();
        json.fieldNames().forEachRemaining(actual::add);
        if (!actual.equals(expected)) throw new IllegalArgumentException("Parameters contain missing or unknown fields");
    }

    private static int integer(JsonNode json, String field) {
        JsonNode node = json.get(field);
        if (node == null || !node.isIntegralNumber() || !node.canConvertToInt()) {
            throw new IllegalArgumentException(field + " must be an integer");
        }
        return node.intValue();
    }

    private static BigDecimal decimal(JsonNode json, String field) {
        JsonNode node = json.get(field);
        if (node == null || !node.isNumber()) throw new IllegalArgumentException(field + " must be a finite number");
        try {
            BigDecimal value = node.decimalValue();
            if (value == null) throw new NumberFormatException();
            return value;
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException(field + " must be a finite number", exception);
        }
    }

    private static <E extends Enum<E>> E enumValue(JsonNode json, String field, Class<E> type) {
        JsonNode node = json.get(field);
        if (node == null || !node.isTextual()) throw new IllegalArgumentException(field + " must be a string");
        try {
            return Enum.valueOf(type, node.textValue());
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("Unsupported " + field, exception);
        }
    }
}
