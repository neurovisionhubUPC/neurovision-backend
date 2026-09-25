package com.neurovision.rehab.analytics.application.internal.queryservices;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.neurovision.rehab.analytics.domain.model.valueobjects.AnalyticsProjectionIdentity;
import com.neurovision.rehab.analytics.domain.model.valueobjects.ReplayCursor;
import com.neurovision.rehab.analytics.domain.model.valueobjects.ReplayLane;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ReplayCursorCodecTest {
    private static final String KEY = "test-only-replay-cursor-signing-key-32-bytes-minimum";
    private static final AnalyticsProjectionIdentity IDENTITY = new AnalyticsProjectionIdentity(
            UUID.fromString("00000000-0000-0000-0000-000000000001"),
            UUID.fromString("00000000-0000-0000-0000-000000000002"), "baseline-v1");

    @Test
    void bindsPurposeIdentityLaneAndContinuityState() {
        var codec = new ReplayCursorCodec(KEY);
        var cursor = new ReplayCursor(ReplayCursor.CURRENT_VERSION, IDENTITY, ReplayLane.GAZE,
                12, 8, 2, 8, 1, Instant.parse("2026-01-01T00:00:00Z"));
        String token = codec.encode(cursor);
        assertEquals(cursor, codec.decode(token, IDENTITY, ReplayLane.GAZE));
        assertThrows(IllegalArgumentException.class, () -> codec.decode(token, IDENTITY, ReplayLane.GAMEPLAY));
        var otherIdentity = new AnalyticsProjectionIdentity(IDENTITY.therapySessionId(), UUID.randomUUID(), "baseline-v1");
        assertThrows(IllegalArgumentException.class, () -> codec.decode(token, otherIdentity, ReplayLane.GAZE));
        char replacement = token.charAt(token.length() - 1) == 'A' ? 'B' : 'A';
        String tampered = token.substring(0, token.length() - 1) + replacement;
        assertThrows(IllegalArgumentException.class, () -> codec.decode(tampered, IDENTITY, ReplayLane.GAZE));
    }

    @Test
    void rejectsShortKeysAndOversizedTokens() {
        assertThrows(IllegalArgumentException.class, () -> new ReplayCursorCodec("too-short"));
        var codec = new ReplayCursorCodec(KEY);
        assertThrows(IllegalArgumentException.class, () -> codec.decode("x".repeat(1025), IDENTITY, ReplayLane.GAZE));
    }
}
