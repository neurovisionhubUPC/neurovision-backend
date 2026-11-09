package com.neurovision.rehab.clinical.infrastructure.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.neurovision.rehab.clinical.application.ports.out.integration.SessionReportStandardScanningPort.*;
import java.util.*;
import static com.neurovision.rehab.clinical.infrastructure.integration.VisualSearchReportJson.*;

/** Strict wire decoding shared with saved-copy selection inspection, never metric calculation. */
final class StandardScanningReportJson {
    private StandardScanningReportJson() {}
    static Summary summary(JsonNode node) {
        if (node == null || !node.isObject() || node.size() != 11) throw new IllegalArgumentException();
        var median = node.get("medianLocalizationDurationMs");
        if (median == null || (!median.isNull() && !median.isNumber())) throw new IllegalArgumentException();
        return new Summary(text(node,"calculationVersion"),uuid(node,"therapySessionId"),uuid(node,"configurationId"),
                number(node,"configurationVersion"),Origin.valueOf(text(node,"dataOrigin")),number(node,"reportedTrialCount"),
                number(node,"detectedTargetCount"),number(node,"omissionCount"),number(node,"interruptedCount"),number(node,"eligibleLocalizationCount"),
                median.isNull() ? null : median.decimalValue());
    }
}
