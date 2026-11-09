package com.neurovision.rehab.analytics.interfaces.rest.transform;

import static org.junit.jupiter.api.Assertions.*;
import com.neurovision.rehab.analytics.domain.model.valueobjects.SustainedFixationTrialResult;
import com.neurovision.rehab.analytics.domain.model.valueobjects.VisualSearchTrialResult;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.junit.jupiter.api.Test;

class SustainedFixationTrialRequestDecoderTest {
    static final UUID SESSION = new UUID(0, 1);
    public static final String BODY = "{\"schemaVersion\":1,\"trialId\":\"trial-001\",\"outcome\":\"SUCCESS\","
            + "\"elapsedMs\":2250,\"insideTargetMs\":1000,\"longestContinuousInsideMs\":600,\"dataOrigin\":\"SYNTHETIC\"}";
    static SustainedFixationTrialResult decode(String body) { return SustainedFixationTrialRequestDecoder.decode(SESSION,
            new ByteArrayInputStream(body.getBytes(StandardCharsets.UTF_8))); }
    static void rejected(String body) {
        var failure = assertThrows(SustainedFixationTrialRequestDecoder.Rejected.class, () -> decode(body));
        assertEquals(400, failure.status());
        assertEquals("INVALID_RESULT", failure.getMessage());
        assertNull(failure.getCause());
    }

    @Test void preservesTheSevenFieldsAndAuthoritativeRouteSession() {
        var value = decode(BODY.replace("trial-001", " original/試行 : 1 "));
        assertEquals(SESSION, value.therapySessionId());
        assertEquals(" original/試行 : 1 ", value.trialId());
        assertEquals(1, value.schemaVersion());
        assertEquals(2250, value.elapsedMs());
        assertEquals(1000L, value.insideTargetMs());
        assertEquals(600L, value.longestContinuousInsideMs());
        assertEquals(VisualSearchTrialResult.Outcome.SUCCESS, value.outcome());
        assertEquals(VisualSearchTrialResult.DataOrigin.SYNTHETIC, value.dataOrigin());
        assertEquals(VisualSearchTrialResult.DataOrigin.PRODUCER_REPORTED,
                decode(BODY.replace("SYNTHETIC", "PRODUCER_REPORTED")).dataOrigin());
    }

    @Test void nonSuccessOutcomesStillRequireAllThreeDurations() {
        for (String outcome : List.of("TIMEOUT", "INTERRUPTED")) {
            var body = BODY.replace("SUCCESS", outcome);
            var result = decode(body);
            assertEquals(outcome, result.outcome().name());
            assertEquals(2250L, result.elapsedMs());
            assertEquals(1000L, result.insideTargetMs());
            assertEquals(600L, result.longestContinuousInsideMs());
            rejected(body.replace("\"longestContinuousInsideMs\":600", "\"longestContinuousInsideMs\":null"));
        }
    }

    @Test void rejectsMissingExtraDuplicateTrailingOrNonObjectInput() {
        for (String body : List.of("", "null", "[]", "{}", BODY+" {}", BODY+" true",
                BODY.replace("\"schemaVersion\":1,", ""), BODY.replace("\"longestContinuousInsideMs\":600,", ""),
                BODY.replace("{", "{\"therapySessionId\":\"not-client-selected\","),
                BODY.replace("{", "{\"schemaVersion\":1,"), BODY.replace("{", "{\"unexpected\":0,"),
                BODY.replace("elapsedMs", "localizationDurationMs"), BODY.replace("\"insideTargetMs\":1000,", ""))) rejected(body);
    }

    @Test void rejectsCoercionsOverflowInvalidEnumsAndDomainValues() {
        for (String body : List.of(BODY.replace("\"schemaVersion\":1", "\"schemaVersion\":\"1\""),
                BODY.replace("\"schemaVersion\":1", "\"schemaVersion\":2"),
                BODY.replace("2250", "2250.0"), BODY.replace("2250", "2.25e3"),
                BODY.replace("2250", "9223372036854775808"), BODY.replace("2250", "-1"),
                BODY.replace("2250", "true"), BODY.replace("600", "\"600\""),
                BODY.replace("600", "null"), BODY.replace("600", "3000"),
                BODY.replace("SUCCESS", "success"), BODY.replace("SYNTHETIC", "UNKNOWN"),
                BODY.replace("trial-001", " "), BODY.replace("\"trial-001\"", "1"),
                BODY.replace("1000", "599"), BODY.replace("1000", "2251"))) rejected(body);
    }

    @Test void enforcesEightKiBInclusiveAndReadsOnlyOneSentinelByte() {
        assertNotNull(decode(BODY + " ".repeat(8192 - BODY.length())));
        byte[] bytes = new byte[16000]; Arrays.fill(bytes, (byte)' ');
        var input = new ByteArrayInputStream(bytes);
        var failure = assertThrows(SustainedFixationTrialRequestDecoder.Rejected.class,
                () -> SustainedFixationTrialRequestDecoder.decode(SESSION, input));
        assertEquals(413, failure.status()); assertEquals("PAYLOAD_TOO_LARGE", failure.getMessage());
        assertEquals(bytes.length - 8193, input.available());
    }

    @Test void malformedUtf8OrBrokenInputFailsWithoutRawMessages() {
        var input = new ByteArrayInputStream(new byte[]{(byte)0xc3, (byte)0x28});
        assertEquals(400, assertThrows(SustainedFixationTrialRequestDecoder.Rejected.class,
                () -> SustainedFixationTrialRequestDecoder.decode(SESSION, input)).status());
        assertEquals("INVALID_RESULT", assertThrows(SustainedFixationTrialRequestDecoder.Rejected.class,
                () -> SustainedFixationTrialRequestDecoder.decode(SESSION, new InputStream() {
                    public int read() throws IOException { throw new IOException("private content"); }
                })).getMessage());
    }
    @Test void utf8LimitCountsBytesAndEscapedDuplicateNamesFailClosed() {
        String unicode = BODY.replace("trial-001", "trial-\u00e9");
        int byteCount = unicode.getBytes(StandardCharsets.UTF_8).length;
        assertNotNull(decode(unicode + " ".repeat(8192 - byteCount)));
        var tooLarge = assertThrows(SustainedFixationTrialRequestDecoder.Rejected.class,
                () -> decode(unicode + " ".repeat(8193 - byteCount)));
        assertEquals(413, tooLarge.status()); assertEquals("PAYLOAD_TOO_LARGE", tooLarge.getMessage());
        String escapedKey = "schema" + "\\u0056" + "ersion";
        rejected(BODY.substring(0, BODY.length()-1) + ",\"" + escapedKey + "\":1}");
    }
}
