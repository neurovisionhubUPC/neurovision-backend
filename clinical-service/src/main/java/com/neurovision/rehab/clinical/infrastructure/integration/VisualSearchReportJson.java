package com.neurovision.rehab.clinical.infrastructure.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.neurovision.rehab.clinical.application.ports.out.integration.SessionReportVisualSearchPort.*;
import java.util.*;

/** Strict wire decoding shared with saved-copy selection inspection, never metric calculation. */
final class VisualSearchReportJson {
    private VisualSearchReportJson() {}
    static Summary summary(JsonNode node) {
        if (node == null || !node.isObject() || node.size() != 11) throw new IllegalArgumentException();
        var median = node.get("medianSearchDurationMs");
        if (median == null || (!median.isNull() && !median.isNumber())) throw new IllegalArgumentException();
        return new Summary(text(node,"calculationVersion"),uuid(node,"therapySessionId"),uuid(node,"configurationId"),
                number(node,"configurationVersion"),Origin.valueOf(text(node,"dataOrigin")),number(node,"reportedTrialCount"),
                number(node,"hitCount"),number(node,"omissionCount"),number(node,"interruptedCount"),number(node,"eligibleSearchCount"),
                median.isNull() ? null : median.decimalValue());
    }
    static String text(JsonNode node, String field) {
        var value = node.get(field);
        if (value == null || !value.isTextual()) throw new IllegalArgumentException();
        return value.textValue();
    }
    static UUID uuid(JsonNode node, String field) {
        String raw = text(node,field); var uuid = UUID.fromString(raw);
        if (!uuid.toString().equals(raw)) throw new IllegalArgumentException();
        return uuid;
    }
    static long number(JsonNode node, String field) {
        var value = node.get(field);
        if (value == null || !value.isIntegralNumber() || !value.canConvertToLong()) throw new IllegalArgumentException();
        return value.longValue();
    }
}
