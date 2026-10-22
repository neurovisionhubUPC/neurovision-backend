package com.neurovision.rehab.clinical.interfaces.rest.transform;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.*;
import com.neurovision.rehab.clinical.application.internal.commandservices.*;
import com.neurovision.rehab.clinical.domain.model.valueobjects.*;
import com.neurovision.rehab.clinical.interfaces.rest.resource.TherapyPlanRevisionResponse;
import java.util.*;

public final class TherapyPlanRevisionRestAssembler {
    private static final Set<String> FIELDS = Set.of("commandId", "dailySessionLimit",
            "dailyTargetDurationMinutes", "dailyHardSafetyLimitMinutes", "dailyExercisePrescriptions",
            "status", "clinicalReason");

    private TherapyPlanRevisionRestAssembler() {}

    public static ReviseTherapyPlanCommand toCommand(
            String patient, String plan, CognitoSubject doctor, String body, ObjectMapper mapper) {
        if (body == null || body.length() > 16384) throw new IllegalArgumentException();
        final JsonNode json;
        try {
            json = mapper.reader().with(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
                    .with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).readTree(body);
        } catch (JsonProcessingException invalid) {
            throw new IllegalArgumentException("Invalid revision request");
        }
        if (json == null || !json.isObject()) throw new IllegalArgumentException();
        json.fieldNames().forEachRemaining(name -> {
            if (!FIELDS.contains(name) || json.get(name).isNull()) throw new IllegalArgumentException();
        });
        if (!json.path("commandId").isTextual()) throw new IllegalArgumentException();
        TherapyPlanStatus status = null;
        if (json.has("status")) {
            if (!json.get("status").isTextual()) throw new IllegalArgumentException();
            status = TherapyPlanStatus.valueOf(json.get("status").textValue());
            if (status == TherapyPlanStatus.ACTIVE) throw new IllegalArgumentException();
        }
        String reason = null;
        if (json.has("clinicalReason")) {
            if (!json.get("clinicalReason").isTextual()) throw new IllegalArgumentException();
            reason = json.get("clinicalReason").textValue().trim();
            if (reason.isEmpty() || reason.length() > 500) throw new IllegalArgumentException();
        }
        if (status != null && reason == null) throw new IllegalArgumentException();
        Map<ExerciseCode, Integer> prescriptions = null;
        if (json.has("dailyExercisePrescriptions")) {
            var value = json.get("dailyExercisePrescriptions");
            if (!value.isObject() || value.isEmpty() || value.size() > 3) throw new IllegalArgumentException();
            prescriptions = new TreeMap<>();
            var fields = value.fields();
            while (fields.hasNext()) {
                var field = fields.next();
                var code = TherapeuticExercise.from(new ExerciseCode(field.getKey())).code();
                if (prescriptions.putIfAbsent(code, positive(field.getValue())) != null) {
                    throw new IllegalArgumentException("Duplicate prescribed exercise");
                }
            }
        }
        return new ReviseTherapyPlanCommand(uuid(json.get("commandId").textValue()),
                new TherapyPlanId(uuid(plan)), new ClinicalPatientId(uuid(patient)), doctor,
                optional(json, "dailySessionLimit"), optional(json, "dailyTargetDurationMinutes"),
                optional(json, "dailyHardSafetyLimitMinutes"), prescriptions, status, reason);
    }

    private static UUID uuid(String value) {
        if (value == null) throw new IllegalArgumentException();
        var id = UUID.fromString(value);
        if (!id.toString().equals(value)) throw new IllegalArgumentException();
        return id;
    }

    private static Integer optional(JsonNode json, String name) {
        return json.has(name) ? positive(json.get(name)) : null;
    }

    private static int positive(JsonNode value) {
        if (!value.isIntegralNumber() || !value.canConvertToInt() || value.intValue() < 1) {
            throw new IllegalArgumentException();
        }
        return value.intValue();
    }

    public static TherapyPlanRevisionResponse.Result toResponse(
            ReviseTherapyPlanCommand command, ReviseTherapyPlanResult result) {
        var revisions = result.revisions().stream().map(revision -> {
            Map<String, Integer> prescriptions = null;
            if (revision.dailyExercisePrescriptions().isPresent()) {
                prescriptions = new TreeMap<>();
                for (var entry : revision.dailyExercisePrescriptions().get().entrySet()) {
                    prescriptions.put(entry.getKey().value(), entry.getValue());
                }
            }
            return new TherapyPlanRevisionResponse.Revision(revision.id().value(), revision.sequence(),
                    revision.kind().name(), revision.effectiveFrom(), new TherapyPlanRevisionResponse.Changes(
                            revision.dailySessionLimit().isPresent() ? revision.dailySessionLimit().getAsInt() : null,
                            revision.dailyTargetDurationMinutes().isPresent() ? revision.dailyTargetDurationMinutes().getAsInt() : null,
                            revision.dailyHardSafetyLimitMinutes().isPresent() ? revision.dailyHardSafetyLimitMinutes().getAsInt() : null,
                            prescriptions, revision.status().map(Enum::name).orElse(null), revision.clinicalReason().orElse(null)));
        }).toList();
        return new TherapyPlanRevisionResponse.Result(command.commandId(), command.therapyPlanId().value(),
                result.idempotentReplay(), revisions);
    }
}
