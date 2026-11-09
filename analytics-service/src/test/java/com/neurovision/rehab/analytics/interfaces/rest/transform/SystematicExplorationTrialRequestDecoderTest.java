package com.neurovision.rehab.analytics.interfaces.rest.transform;

import static org.junit.jupiter.api.Assertions.*;
import com.neurovision.rehab.analytics.domain.model.valueobjects.SystematicExplorationTrialResult;
import com.neurovision.rehab.analytics.domain.model.valueobjects.VisualSearchTrialResult;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.junit.jupiter.api.Test;

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
}
