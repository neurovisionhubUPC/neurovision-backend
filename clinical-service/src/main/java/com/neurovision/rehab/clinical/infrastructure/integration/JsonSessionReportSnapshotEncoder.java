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
            JsonNode root = selectedRoot(savedReportJson);
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
    public com.neurovision.rehab.clinical.application.ports.out.integration.SessionReportStandardScanningPort.Origin standardScanningSelection(String savedReportJson, UUID expectedSession) {
        try {
            JsonNode root = selectedRoot(savedReportJson);
            if (!root.has("standardScanning")) return null;
            var summary = StandardScanningReportJson.summary(root.get("standardScanning"));
            var execution = root.path("execution"); var configuration = root.path("configuration");
            if (!Objects.requireNonNull(expectedSession).equals(summary.therapySessionId())
                    || !"STANDARD_SCANNING".equals(VisualSearchReportJson.text(root,"mode"))
                    || !summary.therapySessionId().equals(VisualSearchReportJson.uuid(execution,"therapySessionId"))
                    || !summary.configurationId().equals(VisualSearchReportJson.uuid(configuration,"configurationId"))
                    || summary.configurationVersion() != VisualSearchReportJson.number(configuration,"configurationVersion"))
                throw new IllegalArgumentException();
            return summary.dataOrigin();
        } catch (Exception unavailable) { throw new IllegalStateException("Session report snapshot unavailable"); }
    }
    public com.neurovision.rehab.clinical.application.ports.out.integration.SessionReportSustainedFixationPort.Origin sustainedFixationSelection(String savedReportJson, UUID expectedSession) {
        try {
            JsonNode root = selectedRoot(savedReportJson);
            if (!root.has("sustainedFixation")) return null;
            var summary = SustainedFixationReportJson.summary(root.get("sustainedFixation"));
            var execution = root.path("execution"); var configuration = root.path("configuration");
            if (!Objects.requireNonNull(expectedSession).equals(summary.therapySessionId())
                    || !"SUSTAINED_TARGET_FIXATION".equals(VisualSearchReportJson.text(root,"mode"))
                    || !summary.therapySessionId().equals(VisualSearchReportJson.uuid(execution,"therapySessionId"))
                    || !summary.configurationId().equals(VisualSearchReportJson.uuid(configuration,"configurationId"))
                    || summary.configurationVersion() != VisualSearchReportJson.number(configuration,"configurationVersion"))
                throw new IllegalArgumentException();
            return summary.dataOrigin();
        } catch (Exception unavailable) { throw new IllegalStateException("Session report snapshot unavailable"); }
    }

    public com.neurovision.rehab.clinical.application.ports.out.integration.SessionReportCompensatorySaccadePort.Origin compensatorySaccadeSelection(String savedReportJson, UUID expectedSession) {
        try {
            JsonNode root = selectedRoot(savedReportJson);
            if (!root.has("compensatorySaccade")) return null;
            var summary = CompensatorySaccadeReportJson.summary(root.get("compensatorySaccade"));
            var execution = root.path("execution"); var configuration = root.path("configuration");
            if (!Objects.requireNonNull(expectedSession).equals(summary.therapySessionId())
                    || !"COMPENSATORY_SACCADE".equals(VisualSearchReportJson.text(root,"mode"))
                    || !summary.therapySessionId().equals(VisualSearchReportJson.uuid(execution,"therapySessionId"))
                    || !summary.configurationId().equals(VisualSearchReportJson.uuid(configuration,"configurationId"))
                    || summary.configurationVersion() != VisualSearchReportJson.number(configuration,"configurationVersion"))
                throw new IllegalArgumentException();
            return summary.dataOrigin();
        } catch (Exception unavailable) { throw new IllegalStateException("Session report snapshot unavailable"); }
    }

    public com.neurovision.rehab.clinical.application.ports.out.integration.SessionReportSystematicExplorationPort.Origin systematicExplorationSelection(String savedReportJson, UUID expectedSession) {
        try {
            JsonNode root = selectedRoot(savedReportJson);
            if (!root.has("systematicExploration")) return null;
            var summary = SystematicExplorationReportJson.summary(root.get("systematicExploration"));
            var execution = root.path("execution"); var configuration = root.path("configuration");
            if (!Objects.requireNonNull(expectedSession).equals(summary.therapySessionId())
                    || !"SYSTEMATIC_EXPLORATION".equals(VisualSearchReportJson.text(root,"mode"))
                    || !summary.therapySessionId().equals(VisualSearchReportJson.uuid(execution,"therapySessionId"))
                    || !summary.configurationId().equals(VisualSearchReportJson.uuid(configuration,"configurationId"))
                    || summary.configurationVersion() != VisualSearchReportJson.number(configuration,"configurationVersion"))
                throw new IllegalArgumentException();
            return summary.dataOrigin();
        } catch (Exception unavailable) { throw new IllegalStateException("Session report snapshot unavailable"); }
    }

    private JsonNode selectedRoot(String savedReportJson) throws java.io.IOException {
        JsonNode root = reader.readTree(savedReportJson);
        if (root == null || !root.isObject()) throw new IllegalArgumentException();
        int sections = (root.has("visualSearch") ? 1 : 0) + (root.has("standardScanning") ? 1 : 0)
                + (root.has("sustainedFixation") ? 1 : 0) + (root.has("compensatorySaccade") ? 1 : 0) + (root.has("systematicExploration") ? 1 : 0);
        if (sections > 1) throw new IllegalArgumentException();
        return root;
    }
}
