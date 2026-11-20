package com.neurovision.rehab.analytics.interfaces.rest.transform;

import static org.junit.jupiter.api.Assertions.*;
import com.neurovision.rehab.analytics.domain.model.valueobjects.SystematicExplorationTrialResult;
import com.neurovision.rehab.analytics.domain.model.valueobjects.SystematicExplorationTrialContext;
import com.neurovision.rehab.analytics.domain.services.SystematicExplorationTrialMetricsCalculator;
import com.neurovision.rehab.analytics.domain.model.valueobjects.VisualSearchTrialResult;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;
import org.junit.jupiter.api.DynamicTest;

class SystematicExplorationTrialRequestDecoderTest {
    static final UUID SESSION = new UUID(0, 1);
    static final String VISIT = "{\"quadrant\":\"TOP_LEFT\",\"timeToVisitMs\":1000,\"confirmedDwellMs\":600}";
    static final String BODY = "{\"schemaVersion\":1,\"trialId\":\"traversal-001\",\"outcome\":\"INTERRUPTED\","
            + "\"elapsedMs\":1500,\"visits\":[" + VISIT + "],\"dataOrigin\":\"SYNTHETIC\"}";
    static SystematicExplorationTrialResult decode(String body) {
        return SystematicExplorationTrialRequestDecoder.decode(SESSION,
                new ByteArrayInputStream(body.getBytes(StandardCharsets.UTF_8)));
    }
    static void rejected(String body) {
        var failure = assertThrows(SystematicExplorationTrialRequestDecoder.Rejected.class, () -> decode(body));
        assertEquals(400, failure.status()); assertEquals("INVALID_RESULT", failure.getMessage()); assertNull(failure.getCause());
    }

    @Test void preservesOpaqueIdentityRouteSessionAndExactVisitValues() {
        var result = decode(BODY.replace("traversal-001", " original/\u8a66\u884c : 1 "));
        assertEquals(SESSION, result.therapySessionId()); assertEquals(" original/\u8a66\u884c : 1 ", result.trialId());
        assertEquals(1500, result.elapsedMs()); assertEquals(SystematicExplorationTrialResult.Outcome.INTERRUPTED, result.outcome());
        assertEquals(List.of(new SystematicExplorationTrialResult.Visit(SystematicExplorationTrialResult.Quadrant.TOP_LEFT,1000,600)), result.visits());
        assertEquals(VisualSearchTrialResult.DataOrigin.SYNTHETIC, result.dataOrigin());
        assertEquals(VisualSearchTrialResult.DataOrigin.PRODUCER_REPORTED,
                decode(BODY.replace("SYNTHETIC","PRODUCER_REPORTED")).dataOrigin());
    }
    @Test void supportsAllOutcomesAndZeroToFourOrderedVisitsWithoutReinterpretingPolicy() {
        for (var outcome : SystematicExplorationTrialResult.Outcome.values()) {
            var result = decode(BODY.replace("INTERRUPTED",outcome.name()));
            assertEquals(outcome,result.outcome()); // Final frozen-policy checks remain in the existing service.
        }
        assertTrue(decode(BODY.replace(VISIT,"")).visits().isEmpty());
        var visits = new ArrayList<String>();
        for (var q : SystematicExplorationTrialResult.Quadrant.values()) visits.add(VISIT.replace("TOP_LEFT",q.name()));
        var result = decode(BODY.replace(VISIT,String.join(",",visits)));
        assertEquals(4,result.visits().size());
        assertThrows(UnsupportedOperationException.class, () -> result.visits().clear());
    }
    @Test void rejectsMissingExtraDuplicateTrailingAndNonObjectRoot() {
        for (String body : List.of("", "null", "[]", "{}", BODY+" {}", BODY+" true",
                BODY.replace("\"schemaVersion\":1,", ""), BODY.replace("\"visits\":["+VISIT+"],", ""),
                BODY.replace("{", "{\"unexpected\":0,"),
                BODY.replaceFirst("\\{", "{\"therapySessionId\":\"client-selected\","),
                BODY.substring(0,BODY.length()-1)+",\"schemaVersion\":1}",
                BODY.replace("elapsedMs","localizationDurationMs"))) rejected(body);
    }
    @Test void rejectsCoercionsOverflowInvalidEnumsAndBasicDomainValues() {
        for (String body : List.of(BODY.replace("\"schemaVersion\":1", "\"schemaVersion\":\"1\""),
                BODY.replace("\"schemaVersion\":1", "\"schemaVersion\":2"),
                BODY.replace("1500","1500.0"), BODY.replace("1500","1.5e3"),
                BODY.replace("1500","9223372036854775808"), BODY.replace("1500","-1"), BODY.replace("1500","true"),
                BODY.replace("600","\"600\""), BODY.replace("600","null"), BODY.replace("600","1001"),
                BODY.replace("INTERRUPTED","interrupted"), BODY.replace("SYNTHETIC","UNKNOWN"),
                BODY.replace("traversal-001"," "), BODY.replace("\"traversal-001\"","1"))) rejected(body);
    }
    @Test void rejectsUnknownMissingDuplicateUnorderedOrExcessiveVisitShapes() {
        for (String body : List.of(BODY.replace("["+VISIT+"]","null"),BODY.replace("["+VISIT+"]","{}"),
                BODY.replace(VISIT,"null"),BODY.replace("TOP_LEFT","BOTTOM_RIGHT"),BODY.replace("TOP_LEFT","UNKNOWN"),
                BODY.replace("\"timeToVisitMs\":1000,",""),BODY.replace("\"quadrant\":\"TOP_LEFT\",",""),
                BODY.replace("600}","600,\"extra\":1}"),BODY.replace("600}","600,\"confirmedDwellMs\":600}"),
                BODY.replace("1000","\"1000\""),BODY.replace(VISIT,VISIT+","+VISIT),
                BODY.replace(VISIT,String.join(",",Collections.nCopies(5,VISIT))))) rejected(body);
    }
    @Test void enforcesEightKiBInclusiveAndOnlyOneSentinelByte() {
        assertNotNull(decode(BODY+" ".repeat(8192-BODY.length())));
        byte[] bytes=new byte[16000];Arrays.fill(bytes,(byte)' ');var input=new ByteArrayInputStream(bytes);
        var failure=assertThrows(SystematicExplorationTrialRequestDecoder.Rejected.class,
                () -> SystematicExplorationTrialRequestDecoder.decode(SESSION,input));
        assertEquals(413,failure.status());assertEquals("PAYLOAD_TOO_LARGE",failure.getMessage());
        assertEquals(bytes.length-8193,input.available());
    }
    @Test void rejectsMalformedUtf8NullOrBrokenInputWithoutRawMessages() {
        for (InputStream input : Arrays.asList(null,new ByteArrayInputStream(new byte[]{(byte)0xc3,(byte)0x28}),
                new InputStream(){public int read() throws IOException {throw new IOException("private content");}})) {
            var failure=assertThrows(SystematicExplorationTrialRequestDecoder.Rejected.class,
                    () -> SystematicExplorationTrialRequestDecoder.decode(SESSION,input));
            assertEquals(400,failure.status());assertEquals("INVALID_RESULT",failure.getMessage());assertNull(failure.getCause());
        }
    }
    @Test void utf8ByteLimitsAndEscapedDuplicateNamesAreStrictAtBothLevels() {
        String unicode=BODY.replace("traversal-001","trial-\u00e9");int length=unicode.getBytes(StandardCharsets.UTF_8).length;
        assertNotNull(decode(unicode+" ".repeat(8192-length)));
        assertEquals(413,assertThrows(SystematicExplorationTrialRequestDecoder.Rejected.class,
                () -> decode(unicode+" ".repeat(8193-length))).status());
        String rootKey="schema"+"\\u0056"+"ersion";
        rejected(BODY.substring(0,BODY.length()-1)+",\""+rootKey+"\":1}");
        String visitKey="confirmed"+"\\u0044"+"wellMs";
        rejected(BODY.replace("600}","600,\""+visitKey+"\":600}"));
    }

    private static final UUID FRONTEND_CONFIGURATION = new UUID(0, 2);
    private static final SystematicExplorationTrialContext FRONTEND_POLICY =
            new SystematicExplorationTrialContext(SESSION, FRONTEND_CONFIGURATION, 1, 300, 2500);
    private record FrontendCase(String trialId, SystematicExplorationTrialResult.Outcome outcome,
            long elapsedMs, int visitedCount, String body) { }

    // Static payloads from frontend 80342a7, audit ADV11/ADV12/ADV13 (2026-11-20).
    // Only each envelope's payload is sent; the frontend checkout is not a runtime test dependency.
    private static final List<FrontendCase> FRONTEND_CASES = List.of(
            new FrontendCase("3cfbf689-584b-4be4-8153-49a24a322394", SystematicExplorationTrialResult.Outcome.COMPLETED,
                    1200, 4, """
                    {
                      "schemaVersion": 1,
                      "trialId": "3cfbf689-584b-4be4-8153-49a24a322394",
                      "dataOrigin": "PRODUCER_REPORTED",
                      "outcome": "COMPLETED",
                      "elapsedMs": 1200,
                      "visits": [
                        {
                          "quadrant": "TOP_LEFT",
                          "timeToVisitMs": 300,
                          "confirmedDwellMs": 300
                        },
                        {
                          "quadrant": "TOP_RIGHT",
                          "timeToVisitMs": 300,
                          "confirmedDwellMs": 300
                        },
                        {
                          "quadrant": "BOTTOM_LEFT",
                          "timeToVisitMs": 300,
                          "confirmedDwellMs": 300
                        },
                        {
                          "quadrant": "BOTTOM_RIGHT",
                          "timeToVisitMs": 300,
                          "confirmedDwellMs": 300
                        }
                      ]
                    }
                    """),
            new FrontendCase("b7dc15b0-0905-4f31-9b9f-13e9a2a6db47", SystematicExplorationTrialResult.Outcome.TIMED_OUT,
                    2500, 0, """
                    {
                      "schemaVersion": 1,
                      "trialId": "b7dc15b0-0905-4f31-9b9f-13e9a2a6db47",
                      "dataOrigin": "PRODUCER_REPORTED",
                      "outcome": "TIMED_OUT",
                      "elapsedMs": 2500,
                      "visits": []
                    }
                    """),
            new FrontendCase("88ec2df8-2075-4fb1-8d0b-4014daff2bc7", SystematicExplorationTrialResult.Outcome.TIMED_OUT,
                    2800, 1, """
                    {
                      "schemaVersion": 1,
                      "trialId": "88ec2df8-2075-4fb1-8d0b-4014daff2bc7",
                      "dataOrigin": "PRODUCER_REPORTED",
                      "outcome": "TIMED_OUT",
                      "elapsedMs": 2800,
                      "visits": [
                        {
                          "quadrant": "TOP_LEFT",
                          "timeToVisitMs": 300,
                          "confirmedDwellMs": 300
                        }
                      ]
                    }
                    """),
            new FrontendCase("21abb8ed-36fb-445b-b377-fdfcfd765609", SystematicExplorationTrialResult.Outcome.TIMED_OUT,
                    3100, 2, """
                    {
                      "schemaVersion": 1,
                      "trialId": "21abb8ed-36fb-445b-b377-fdfcfd765609",
                      "dataOrigin": "PRODUCER_REPORTED",
                      "outcome": "TIMED_OUT",
                      "elapsedMs": 3100,
                      "visits": [
                        {
                          "quadrant": "TOP_LEFT",
                          "timeToVisitMs": 300,
                          "confirmedDwellMs": 300
                        },
                        {
                          "quadrant": "TOP_RIGHT",
                          "timeToVisitMs": 300,
                          "confirmedDwellMs": 300
                        }
                      ]
                    }
                    """),
            new FrontendCase("b2d9acaa-2f56-40e8-a53f-bd11cae2ce12", SystematicExplorationTrialResult.Outcome.TIMED_OUT,
                    3400, 3, """
                    {
                      "schemaVersion": 1,
                      "trialId": "b2d9acaa-2f56-40e8-a53f-bd11cae2ce12",
                      "dataOrigin": "PRODUCER_REPORTED",
                      "outcome": "TIMED_OUT",
                      "elapsedMs": 3400,
                      "visits": [
                        {
                          "quadrant": "TOP_LEFT",
                          "timeToVisitMs": 300,
                          "confirmedDwellMs": 300
                        },
                        {
                          "quadrant": "TOP_RIGHT",
                          "timeToVisitMs": 300,
                          "confirmedDwellMs": 300
                        },
                        {
                          "quadrant": "BOTTOM_LEFT",
                          "timeToVisitMs": 300,
                          "confirmedDwellMs": 300
                        }
                      ]
                    }
                    """),
            new FrontendCase("215592c2-90c1-4f59-8b49-79f63ef3a32c", SystematicExplorationTrialResult.Outcome.INTERRUPTED,
                    230, 0, """
                    {
                      "schemaVersion": 1,
                      "trialId": "215592c2-90c1-4f59-8b49-79f63ef3a32c",
                      "dataOrigin": "PRODUCER_REPORTED",
                      "outcome": "INTERRUPTED",
                      "elapsedMs": 230,
                      "visits": []
                    }
                    """),
            new FrontendCase("1d41599c-e09b-4ae2-9ba3-db064c33678e", SystematicExplorationTrialResult.Outcome.INTERRUPTED,
                    530, 1, """
                    {
                      "schemaVersion": 1,
                      "trialId": "1d41599c-e09b-4ae2-9ba3-db064c33678e",
                      "dataOrigin": "PRODUCER_REPORTED",
                      "outcome": "INTERRUPTED",
                      "elapsedMs": 530,
                      "visits": [
                        {
                          "quadrant": "TOP_LEFT",
                          "timeToVisitMs": 300,
                          "confirmedDwellMs": 300
                        }
                      ]
                    }
                    """),
            new FrontendCase("10a07c32-b573-407d-b48d-830a2cef5fbc", SystematicExplorationTrialResult.Outcome.INTERRUPTED,
                    830, 2, """
                    {
                      "schemaVersion": 1,
                      "trialId": "10a07c32-b573-407d-b48d-830a2cef5fbc",
                      "dataOrigin": "PRODUCER_REPORTED",
                      "outcome": "INTERRUPTED",
                      "elapsedMs": 830,
                      "visits": [
                        {
                          "quadrant": "TOP_LEFT",
                          "timeToVisitMs": 300,
                          "confirmedDwellMs": 300
                        },
                        {
                          "quadrant": "TOP_RIGHT",
                          "timeToVisitMs": 300,
                          "confirmedDwellMs": 300
                        }
                      ]
                    }
                    """),
            new FrontendCase("5529b74c-de0c-4a9c-bb46-019c76592994", SystematicExplorationTrialResult.Outcome.INTERRUPTED,
                    1130, 3, """
                    {
                      "schemaVersion": 1,
                      "trialId": "5529b74c-de0c-4a9c-bb46-019c76592994",
                      "dataOrigin": "PRODUCER_REPORTED",
                      "outcome": "INTERRUPTED",
                      "elapsedMs": 1130,
                      "visits": [
                        {
                          "quadrant": "TOP_LEFT",
                          "timeToVisitMs": 300,
                          "confirmedDwellMs": 300
                        },
                        {
                          "quadrant": "TOP_RIGHT",
                          "timeToVisitMs": 300,
                          "confirmedDwellMs": 300
                        },
                        {
                          "quadrant": "BOTTOM_LEFT",
                          "timeToVisitMs": 300,
                          "confirmedDwellMs": 300
                        }
                      ]
                    }
                    """));

    @TestFactory List<DynamicTest> acceptsNineFrontendBodiesThroughFrozenPolicyAndCalculator() {
        var quadrants = List.of(SystematicExplorationTrialResult.Quadrant.TOP_LEFT,
                SystematicExplorationTrialResult.Quadrant.TOP_RIGHT,
                SystematicExplorationTrialResult.Quadrant.BOTTOM_LEFT,
                SystematicExplorationTrialResult.Quadrant.BOTTOM_RIGHT);
        return FRONTEND_CASES.stream().map(expected -> DynamicTest.dynamicTest(
                expected.outcome() + "-visits-" + expected.visitedCount(), () -> {
                    var result = decode(expected.body());
                    var visits = quadrants.subList(0, expected.visitedCount()).stream()
                            .map(q -> new SystematicExplorationTrialResult.Visit(q, 300, 300)).toList();
                    assertAll(
                            () -> assertEquals(SESSION, result.therapySessionId()),
                            () -> assertEquals(expected.trialId(), result.trialId()),
                            () -> assertEquals(VisualSearchTrialResult.DataOrigin.PRODUCER_REPORTED, result.dataOrigin()),
                            () -> assertEquals(expected.outcome(), result.outcome()),
                            () -> assertEquals(expected.elapsedMs(), result.elapsedMs()),
                            () -> assertEquals(visits, result.visits()));
                    assertDoesNotThrow(() -> FRONTEND_POLICY.validate(result));
                    var summary = new SystematicExplorationTrialMetricsCalculator().calculate(FRONTEND_POLICY,
                            VisualSearchTrialResult.DataOrigin.PRODUCER_REPORTED, List.of(result));
                    assertAll(
                            () -> assertEquals("systematic-exploration-trial-v1", summary.calculationVersion()),
                            () -> assertEquals(SESSION, summary.therapySessionId()),
                            () -> assertEquals(FRONTEND_CONFIGURATION, summary.configurationId()),
                            () -> assertEquals(1, summary.configurationVersion()),
                            () -> assertEquals(result.dataOrigin(), summary.dataOrigin()),
                            () -> assertEquals(1, summary.reportedTrialCount()),
                            () -> assertEquals(1, summary.traversals().size()));
                    var traversal = summary.traversals().getFirst();
                    assertAll(
                            () -> assertEquals(expected.trialId(), traversal.trialId()),
                            () -> assertEquals(expected.outcome(), traversal.outcome()),
                            () -> assertEquals(expected.visitedCount(), traversal.visitedRegionCount()),
                            () -> assertEquals(expected.visitedCount() * 25, traversal.coveragePercent()),
                            () -> assertEquals(expected.elapsedMs(), traversal.elapsedMs()));
                })).toList();
    }

    @TestFactory List<DynamicTest> rejectsWireValidBodiesThatViolateTheFrozenFrontendPolicy() {
        var complete = FRONTEND_CASES.getFirst().body();
        var timeout = FRONTEND_CASES.get(1).body();
        var invalidBodies = new LinkedHashMap<String, String>();
        invalidBodies.put("dwell-below-frozen-minimum", complete.replace("\"confirmedDwellMs\": 300", "\"confirmedDwellMs\": 299"));
        invalidBodies.put("timeout-one-millisecond-early", timeout.replace("\"elapsedMs\": 2500", "\"elapsedMs\": 2499"));
        invalidBodies.put("timeout-one-millisecond-late", timeout.replace("\"elapsedMs\": 2500", "\"elapsedMs\": 2501"));
        invalidBodies.put("interruption-at-deadline", timeout.replace("TIMED_OUT", "INTERRUPTED"));
        return invalidBodies.entrySet().stream().map(entry -> DynamicTest.dynamicTest(entry.getKey(), () -> {
            var result = assertDoesNotThrow(() -> decode(entry.getValue()));
            assertThrows(IllegalArgumentException.class, () -> FRONTEND_POLICY.validate(result));
            assertThrows(IllegalArgumentException.class, () -> new SystematicExplorationTrialMetricsCalculator()
                    .calculate(FRONTEND_POLICY, VisualSearchTrialResult.DataOrigin.PRODUCER_REPORTED, List.of(result)));
        })).toList();
    }
}
