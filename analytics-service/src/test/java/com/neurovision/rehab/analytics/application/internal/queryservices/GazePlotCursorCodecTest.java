package com.neurovision.rehab.analytics.application.internal.queryservices;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.neurovision.rehab.analytics.domain.model.valueobjects.AnalyticsProjectionIdentity;
import com.neurovision.rehab.analytics.domain.model.valueobjects.GazePlotCursor;
import java.time.Instant;
import java.util.Base64;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class GazePlotCursorCodecTest {
    private static final String KEY = "synthetic-test-key-for-gaze-plot-cursor-signing-only";
    private static final AnalyticsProjectionIdentity IDENTITY = new AnalyticsProjectionIdentity(
            UUID.fromString("11111111-1111-1111-1111-111111111111"),
            UUID.fromString("22222222-2222-2222-2222-222222222222"), "baseline-v1");

    @Test
    void signsAndRoundTripsEveryCursorPositionAndContinuityField() {
        var cursor = new GazePlotCursor(2, IDENTITY, 8, 13, 2, 13, 1,
                Instant.parse("2026-09-24T10:00:00.250Z"));
        var codec = new GazePlotCursorCodec(KEY);

        String token = codec.encode(cursor);

        assertEquals(cursor, codec.decode(token, IDENTITY));
        assertThrows(IllegalArgumentException.class,
                () -> codec.decode(token, new AnalyticsProjectionIdentity(
                        IDENTITY.therapySessionId(), UUID.randomUUID(), IDENTITY.calculationVersion())));
    }

    @Test
    void rejectsTamperedResumeIndexPredecessorEvidenceAndSignature() {
        var cursor = new GazePlotCursor(2, IDENTITY, 8, 13, 2, 13, 1,
                Instant.parse("2026-09-24T10:00:00.250Z"));
        var codec = new GazePlotCursorCodec(KEY);
        String token = codec.encode(cursor);
        String[] parts = token.split("\\.", -1);
        String[] fields = new String(Base64.getUrlDecoder().decode(parts[0]), java.nio.charset.StandardCharsets.UTF_8)
                .split("\\|", -1);

        fields[4] = "9";
        assertThrows(IllegalArgumentException.class, () -> codec.decode(replacePayload(parts, fields), IDENTITY));
        fields[4] = "8";
        fields[5] = "14";
        assertThrows(IllegalArgumentException.class, () -> codec.decode(replacePayload(parts, fields), IDENTITY));
        fields[5] = "13";
        fields[6] = "0";
        assertThrows(IllegalArgumentException.class, () -> codec.decode(replacePayload(parts, fields), IDENTITY));
        fields[6] = "2";
        fields[7] = "12";
        assertThrows(IllegalArgumentException.class, () -> codec.decode(replacePayload(parts, fields), IDENTITY));
        fields[7] = "13";
        fields[8] = "0";
        assertThrows(IllegalArgumentException.class, () -> codec.decode(replacePayload(parts, fields), IDENTITY));
        fields[8] = "1";
        fields[9] = "2026-09-24T10:00:01Z";
        assertThrows(IllegalArgumentException.class, () -> codec.decode(replacePayload(parts, fields), IDENTITY));
        assertThrows(IllegalArgumentException.class,
                () -> codec.decode(parts[0] + "." + (parts[1].charAt(0) == 'A' ? "B" : "A")
                        + parts[1].substring(1), IDENTITY));
    }

    @Test
    void refusesToStartWithoutAProductionStrengthKey() {
        assertThrows(IllegalArgumentException.class, () -> new GazePlotCursorCodec("too-short"));
    }

    private static String replacePayload(String[] tokenParts, String[] fields) {
        String payload = String.join("|", fields);
        String encoded = Base64.getUrlEncoder().withoutPadding()
                .encodeToString(payload.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        return encoded + "." + tokenParts[1];
    }
}
