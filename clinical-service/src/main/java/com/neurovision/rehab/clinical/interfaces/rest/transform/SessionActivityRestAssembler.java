package com.neurovision.rehab.clinical.interfaces.rest.transform;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.*;
import com.neurovision.rehab.clinical.application.ports.in.PatientSessionProgressUseCase.Action;
import com.neurovision.rehab.clinical.domain.model.valueobjects.SessionActivityEvent;
import java.util.*;

public final class SessionActivityRestAssembler {
    private SessionActivityRestAssembler() { }
    private static JsonNode object(String body, ObjectMapper mapper) {
        if (body == null || body.length() > 4096) throw new IllegalArgumentException();
        try {
            var json = mapper.reader().with(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
                    .with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).readTree(body);
            if (json == null || !json.isObject()) throw new IllegalArgumentException();
            return json;
        } catch (java.io.IOException invalid) { throw new IllegalArgumentException(); }
    }
    private static void fields(JsonNode node, String... names) {
        var actual = new HashSet<String>(); node.fieldNames().forEachRemaining(actual::add);
        if (!actual.equals(Set.of(names))) throw new IllegalArgumentException();
    }
    public static UUID uuid(String text) {
        if (text == null) throw new IllegalArgumentException();
        var value = UUID.fromString(text);
        if (!value.toString().equals(text)) throw new IllegalArgumentException();
        return value;
    }
    private static String text(JsonNode json, String field) {
        if (!json.path(field).isTextual()) throw new IllegalArgumentException();
        return json.path(field).textValue();
    }
    private static long integer(JsonNode json, String field) {
        var value = json.path(field);
        if (!value.isIntegralNumber() || !value.canConvertToLong()) throw new IllegalArgumentException();
        return value.longValue();
    }
    public static SessionActivityEvent event(String body, ObjectMapper mapper) {
        var json = object(body, mapper);
        var type = SessionActivityEvent.Type.valueOf(text(json, "type"));
        boolean repeated = type == SessionActivityEvent.Type.REPETITION_STARTED || type == SessionActivityEvent.Type.REPETITION_FINISHED;
        if (repeated) fields(json, "schemaVersion", "eventId", "sequence", "type", "repetitionId", "repetitionIndex");
        else fields(json, "schemaVersion", "eventId", "sequence", "type");
        long version = integer(json, "schemaVersion");
        if (version != 1) throw new IllegalArgumentException();
        long repetition = repeated ? integer(json, "repetitionIndex") : 0;
        if (repeated && (repetition < 1 || repetition > Integer.MAX_VALUE)) throw new IllegalArgumentException();
        return new SessionActivityEvent(1, uuid(text(json, "eventId")), integer(json, "sequence"), type,
                repeated ? uuid(text(json, "repetitionId")) : null, repeated ? (int) repetition : null);
    }
    public static Action action(String body, ObjectMapper mapper) {
        var json = object(body, mapper); fields(json, "action");
        return Action.valueOf(text(json, "action"));
    }
}
