package com.neurovision.rehab.analytics.interfaces.rest.transform;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.neurovision.rehab.analytics.domain.model.valueobjects.SystematicExplorationTrialResult;
import com.neurovision.rehab.analytics.domain.model.valueobjects.VisualSearchTrialResult;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/** Route-owned wire decoder. Never changes the shared Jackson mapper or domain identity policy. */
public final class SystematicExplorationTrialRequestDecoder {
    private static final int MAX_BYTES = 8192;
    private static final Set<String> FIELDS = Set.of("schemaVersion", "trialId", "outcome", "elapsedMs", "visits", "dataOrigin");
    private static final Set<String> VISIT_FIELDS = Set.of("quadrant", "timeToVisitMs", "confirmedDwellMs");
    private static final ObjectMapper JSON = new ObjectMapper()
            .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);

    private SystematicExplorationTrialRequestDecoder() { }

    public static SystematicExplorationTrialResult decode(UUID session, InputStream input) {
        try {
            if (input == null) throw new Rejected(400, "INVALID_RESULT");
            // One sentinel byte detects excess even without Content-Length. Upstream buffering is not bounded here.
            byte[] bytes = input.readNBytes(MAX_BYTES + 1);
            if (bytes.length > MAX_BYTES) throw new Rejected(413, "PAYLOAD_TOO_LARGE");
            String body = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
            JsonNode root = JSON.readTree(body);
            if (root == null || !root.isObject()) throw new IllegalArgumentException();
            var fields = new HashSet<String>(); root.fieldNames().forEachRemaining(fields::add);
            if (!FIELDS.equals(fields) || number(root.get("schemaVersion")) != 1) throw new IllegalArgumentException();
            JsonNode rawVisits = root.get("visits");
            if (!rawVisits.isArray() || rawVisits.size() > 4) throw new IllegalArgumentException();
            var visits = new ArrayList<SystematicExplorationTrialResult.Visit>(rawVisits.size());
            for (var visit : rawVisits) {
                if (!visit.isObject()) throw new IllegalArgumentException();
                var visitFields = new HashSet<String>(); visit.fieldNames().forEachRemaining(visitFields::add);
                if (!VISIT_FIELDS.equals(visitFields)) throw new IllegalArgumentException();
                visits.add(new SystematicExplorationTrialResult.Visit(
                        SystematicExplorationTrialResult.Quadrant.valueOf(text(visit.get("quadrant"))),
                        number(visit.get("timeToVisitMs")), number(visit.get("confirmedDwellMs"))));
            }
            // The approved service checks outcome timing against the authorized frozen policy.
            return new SystematicExplorationTrialResult(session, text(root.get("trialId")),
                    VisualSearchTrialResult.DataOrigin.valueOf(text(root.get("dataOrigin"))),
                    SystematicExplorationTrialResult.Outcome.valueOf(text(root.get("outcome"))),
                    number(root.get("elapsedMs")), visits);
        } catch (Rejected rejection) {
            throw rejection;
        } catch (Exception invalid) {
            throw new Rejected(400, "INVALID_RESULT");
        }
    }

    private static long number(JsonNode node) {
        if (node == null || !node.isIntegralNumber() || !node.canConvertToLong()) throw new IllegalArgumentException();
        return node.longValue();
    }
    private static String text(JsonNode node) {
        if (node == null || !node.isTextual()) throw new IllegalArgumentException();
        return node.textValue();
    }
    public static final class Rejected extends RuntimeException {
        private final int status;
        private Rejected(int status, String code) { super(code, null, false, false); this.status = status; }
        public int status() { return status; }
    }
}
