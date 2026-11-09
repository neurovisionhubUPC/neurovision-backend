package com.neurovision.rehab.clinical.infrastructure.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.neurovision.rehab.clinical.application.ports.out.integration.SessionReportSustainedFixationPort.*;
import static com.neurovision.rehab.clinical.infrastructure.integration.VisualSearchReportJson.*;

/** Strict wire decoding shared with saved-copy selection inspection, never metric calculation. */
final class SustainedFixationReportJson {
    private SustainedFixationReportJson() {}
    static Summary summary(JsonNode node) {
        if (node == null || !node.isObject() || node.size() != 8) throw new IllegalArgumentException();
        return new Summary(text(node,"calculationVersion"),uuid(node,"therapySessionId"),uuid(node,"configurationId"),
                number(node,"configurationVersion"),Origin.valueOf(text(node,"dataOrigin")),number(node,"reportedTrialCount"),
                number(node,"totalInsideTargetMs"),number(node,"completedTargetCount"));
    }
}
