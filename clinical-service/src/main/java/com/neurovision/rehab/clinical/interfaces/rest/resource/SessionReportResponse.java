package com.neurovision.rehab.clinical.interfaces.rest.resource;

import com.fasterxml.jackson.annotation.JsonValue;
import com.fasterxml.jackson.databind.JsonNode;
import io.quarkus.runtime.annotations.RegisterForReflection;
import java.util.Objects;

/** Interface-owned, defensively copied JSON snapshot; never holds an application model. */
@RegisterForReflection
public final class SessionReportResponse {
    private final JsonNode representation;

    public SessionReportResponse(JsonNode representation) {
        this.representation = Objects.requireNonNull(representation).deepCopy();
    }

    @JsonValue
    public JsonNode representation() { return representation.deepCopy(); }

    @Override public String toString() { return "SessionReportResponse[redacted]"; }
}
