package com.neurovision.rehab.clinical.infrastructure.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.neurovision.rehab.clinical.application.ports.out.integration.SessionReportSystematicExplorationPort.*;
import java.util.ArrayList;
import static com.neurovision.rehab.clinical.infrastructure.integration.VisualSearchReportJson.*;

/** Strict shared decoder for observed summaries and frozen-copy selection, never a calculator. */
final class SystematicExplorationReportJson {
    private SystematicExplorationReportJson() {}
    static Summary summary(JsonNode node) {
        if (node == null || !node.isObject() || node.size() != 7) throw new IllegalArgumentException();
        var rows = node.get("traversals");
        if (rows == null || !rows.isArray() || rows.size() > 1000) throw new IllegalArgumentException();
        var traversals = new ArrayList<Traversal>();
        for (var row : rows) {
            if (!row.isObject() || row.size() != 5) throw new IllegalArgumentException();
            traversals.add(new Traversal(text(row,"trialId"),Outcome.valueOf(text(row,"outcome")),
                    Math.toIntExact(number(row,"visitedRegionCount")),Math.toIntExact(number(row,"coveragePercent")),number(row,"elapsedMs")));
        }
        return new Summary(text(node,"calculationVersion"),uuid(node,"therapySessionId"),uuid(node,"configurationId"),
                number(node,"configurationVersion"),Origin.valueOf(text(node,"dataOrigin")),number(node,"reportedTrialCount"),traversals);
    }
}
