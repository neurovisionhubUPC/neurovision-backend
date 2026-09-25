package com.neurovision.rehab.analytics.application.internal.queryservices;

import com.neurovision.rehab.analytics.domain.model.valueobjects.AnalyticsProjectionIdentity;
import com.neurovision.rehab.analytics.domain.model.valueobjects.ReplayCursor;
import com.neurovision.rehab.analytics.domain.model.valueobjects.ReplayLane;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import io.quarkus.runtime.Startup;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.Objects;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/** Signs Replay V1 cursors under a purpose tag, separate from gaze-plot cursors. */
@ApplicationScoped
@Startup
public class ReplayCursorCodec {
    private static final int MAX_CURSOR_LENGTH = 1024;
    private static final String PURPOSE = "analytics-replay-v1";
    private final byte[] signingKey;

    @Inject
    public ReplayCursorCodec(@ConfigProperty(name = "analytics.gaze-plot.cursor-signing-key", defaultValue = "") String key) {
        byte[] bytes = Objects.requireNonNull(key).getBytes(StandardCharsets.UTF_8);
        if (bytes.length < 32) throw new IllegalArgumentException("Analytics replay cursor key must contain at least 32 bytes");
        signingKey = bytes.clone();
    }

    public String encode(ReplayCursor cursor) {
        Objects.requireNonNull(cursor);
        var id = cursor.identity();
        String value = String.join("|", PURPOSE, Integer.toString(cursor.version()), id.therapySessionId().toString(),
                id.streamId().toString(), id.calculationVersion(), cursor.lane().name(),
                Long.toString(cursor.projectionRevision()), Long.toString(cursor.resumeSequence()),
                Integer.toString(cursor.resumeSampleIndex()), Long.toString(cursor.previousSampleSequence()),
                Integer.toString(cursor.previousSampleIndex()), cursor.previousEffectiveTime() == null ? ""
                        : cursor.previousEffectiveTime().toString());
        byte[] payload = value.getBytes(StandardCharsets.UTF_8);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(payload) + "."
                + Base64.getUrlEncoder().withoutPadding().encodeToString(sign(payload));
        if (token.length() > MAX_CURSOR_LENGTH) throw new IllegalArgumentException("Invalid replay cursor");
        return token;
    }

    public ReplayCursor decode(String token, AnalyticsProjectionIdentity requestedIdentity, ReplayLane lane) {
        if (token == null || token.isBlank() || token.length() > MAX_CURSOR_LENGTH) throw new IllegalArgumentException("Invalid replay cursor");
        String[] parts = token.split("\\.", -1);
        if (parts.length != 2) throw new IllegalArgumentException("Invalid replay cursor");
        byte[] payload = Base64.getUrlDecoder().decode(parts[0]);
        if (!MessageDigest.isEqual(sign(payload), Base64.getUrlDecoder().decode(parts[1]))) throw new IllegalArgumentException("Invalid replay cursor");
        String[] f = new String(payload, StandardCharsets.UTF_8).split("\\|", -1);
        if (f.length != 12 || !PURPOSE.equals(f[0])) throw new IllegalArgumentException("Invalid replay cursor");
        var identity = new AnalyticsProjectionIdentity(java.util.UUID.fromString(f[2]), java.util.UUID.fromString(f[3]), f[4]);
        var actualLane = ReplayLane.valueOf(f[5]);
        if (Integer.parseInt(f[1]) != ReplayCursor.CURRENT_VERSION || !requestedIdentity.equals(identity) || lane != actualLane) {
            throw new IllegalArgumentException("Invalid replay cursor");
        }
        var previousTime = f[11].isEmpty() ? null : java.time.Instant.parse(f[11]);
        return new ReplayCursor(Integer.parseInt(f[1]), identity, actualLane, Long.parseLong(f[6]),
                Long.parseLong(f[7]), Integer.parseInt(f[8]), Long.parseLong(f[9]), Integer.parseInt(f[10]), previousTime);
    }

    private byte[] sign(byte[] payload) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(signingKey, "HmacSHA256"));
            return mac.doFinal(payload);
        } catch (GeneralSecurityException unavailable) {
            throw new IllegalStateException("Replay cursor signing is unavailable", unavailable);
        }
    }
}
