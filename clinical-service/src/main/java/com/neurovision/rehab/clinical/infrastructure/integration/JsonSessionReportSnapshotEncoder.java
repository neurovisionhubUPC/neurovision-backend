package com.neurovision.rehab.clinical.infrastructure.integration;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.*;
import com.neurovision.rehab.clinical.application.internal.queryservices.SessionReportV1;
import com.neurovision.rehab.clinical.application.ports.out.integration.SessionReportSnapshotEncoder;
import com.neurovision.rehab.clinical.application.ports.out.integration.SessionReportVisualSearchPort.Origin;
import com.neurovision.rehab.clinical.interfaces.rest.transform.SessionReportRestAssembler;
import java.util.*;

/** Frozen outward whitelist codec. Replay inspects saved evidence only, never current Analytics. */
public final class JsonSessionReportSnapshotEncoder implements SessionReportSnapshotEncoder {
    private final ObjectMapper mapper;
    private final ObjectReader reader;
    public JsonSessionReportSnapshotEncoder(ObjectMapper mapper) {
        this.mapper = Objects.requireNonNull(mapper);
        this.reader = mapper.reader().with(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
                .with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).with(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS);
    }
    public String encode(SessionReportV1 report) {
        return SessionReportRestAssembler.toResponse(report,mapper).representation().toString();
    }
    public Origin visualSearchSelection(String savedReportJson, UUID expectedSession) {
        try {
            JsonNode root = reader.readTree(savedReportJson);
            if (root == null || !root.isObject()) throw new IllegalArgumentException();
            if (!root.has("visualSearch")) return null;
            var summary = VisualSearchReportJson.summary(root.get("visualSearch"));
            var execution = root.path("execution"); var configuration = root.path("configuration");
            if (!Objects.requireNonNull(expectedSession).equals(summary.therapySessionId())
                    || !"TARGET_SEARCH_WITH_DISTRACTORS".equals(VisualSearchReportJson.text(root,"mode"))
                    || !summary.therapySessionId().equals(VisualSearchReportJson.uuid(execution,"therapySessionId"))
                    || !summary.configurationId().equals(VisualSearchReportJson.uuid(configuration,"configurationId"))
                    || summary.configurationVersion() != VisualSearchReportJson.number(configuration,"configurationVersion"))
                throw new IllegalArgumentException();
            return summary.dataOrigin();
        } catch (Exception unavailable) { throw new IllegalStateException("Session report snapshot unavailable"); }
    }
}
