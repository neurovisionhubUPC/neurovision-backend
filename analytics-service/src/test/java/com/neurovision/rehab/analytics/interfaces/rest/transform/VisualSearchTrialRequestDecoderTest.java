package com.neurovision.rehab.analytics.interfaces.rest.transform;

import static org.junit.jupiter.api.Assertions.*;
import com.neurovision.rehab.analytics.domain.model.valueobjects.VisualSearchTrialResult;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.junit.jupiter.api.Test;

class VisualSearchTrialRequestDecoderTest {
    static final UUID SESSION = new UUID(0, 1);
    public static final String BODY = "{\"schemaVersion\":1,\"trialId\":\"trial-001\",\"outcome\":\"SUCCESS\","
            + "\"searchDurationMs\":2250,\"confirmedDwellMs\":600,\"dataOrigin\":\"SYNTHETIC\"}";
    static VisualSearchTrialResult decode(String body) { return VisualSearchTrialRequestDecoder.decode(SESSION,
            new ByteArrayInputStream(body.getBytes(StandardCharsets.UTF_8))); }
    static void rejected(String body) {
        var failure = assertThrows(VisualSearchTrialRequestDecoder.Rejected.class, () -> decode(body));
        assertEquals(400, failure.status());
        assertEquals("INVALID_RESULT", failure.getMessage());
        assertNull(failure.getCause());
    }

    @Test void preservesTheSixFieldsAndAuthoritativeRouteSession() {
        var value = decode(BODY.replace("trial-001", " original/試行 : 1 "));
        assertEquals(SESSION, value.therapySessionId());
        assertEquals(" original/試行 : 1 ", value.trialId());
        assertEquals(1, value.schemaVersion());
        assertEquals(2250, value.searchDurationMs());
        assertEquals(600L, value.confirmedDwellMs());
        assertEquals(VisualSearchTrialResult.Outcome.SUCCESS, value.outcome());
        assertEquals(VisualSearchTrialResult.DataOrigin.SYNTHETIC, value.dataOrigin());
        assertEquals(VisualSearchTrialResult.DataOrigin.PRODUCER_REPORTED,
                decode(BODY.replace("SYNTHETIC", "PRODUCER_REPORTED")).dataOrigin());
    }

    @Test void supportsTimeoutAndInterruptedWithExplicitNullDwell() {
        for (String outcome : List.of("TIMEOUT", "INTERRUPTED")) {
            var result = decode(BODY.replace("SUCCESS", outcome).replace("\"confirmedDwellMs\":600", "\"confirmedDwellMs\":null"));
            assertEquals(outcome, result.outcome().name()); assertNull(result.confirmedDwellMs());
        }
    }

    @Test void rejectsMissingExtraDuplicateTrailingOrNonObjectInput() {
        for (String body : List.of("", "null", "[]", "{}", BODY+" {}", BODY+" true",
                BODY.replace("\"schemaVersion\":1,", ""), BODY.replace("\"confirmedDwellMs\":600,", ""),
                BODY.replace("{", "{\"therapySessionId\":\"not-client-selected\","),
                BODY.replace("{", "{\"schemaVersion\":1,"), BODY.replace("{", "{\"unexpected\":0,"))) rejected(body);
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
                BODY.replace("SUCCESS", "TIMEOUT"))) rejected(body);
    }

    @Test void enforcesEightKiBInclusiveAndReadsOnlyOneSentinelByte() {
        assertNotNull(decode(BODY + " ".repeat(8192 - BODY.length())));
        byte[] bytes = new byte[16000]; Arrays.fill(bytes, (byte)' ');
        var input = new ByteArrayInputStream(bytes);
        var failure = assertThrows(VisualSearchTrialRequestDecoder.Rejected.class,
                () -> VisualSearchTrialRequestDecoder.decode(SESSION, input));
        assertEquals(413, failure.status()); assertEquals("PAYLOAD_TOO_LARGE", failure.getMessage());
        assertEquals(bytes.length - 8193, input.available());
    }

    @Test void malformedUtf8OrBrokenInputFailsWithoutRawMessages() {
        var input = new ByteArrayInputStream(new byte[]{(byte)0xc3, (byte)0x28});
        assertEquals(400, assertThrows(VisualSearchTrialRequestDecoder.Rejected.class,
                () -> VisualSearchTrialRequestDecoder.decode(SESSION, input)).status());
        assertEquals("INVALID_RESULT", assertThrows(VisualSearchTrialRequestDecoder.Rejected.class,
                () -> VisualSearchTrialRequestDecoder.decode(SESSION, new InputStream() {
                    public int read() throws IOException { throw new IOException("private content"); }
                })).getMessage());
    }
}
