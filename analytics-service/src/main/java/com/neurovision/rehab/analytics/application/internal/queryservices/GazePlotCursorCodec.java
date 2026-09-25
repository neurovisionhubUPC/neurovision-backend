package com.neurovision.rehab.analytics.application.internal.queryservices;

import com.neurovision.rehab.analytics.domain.model.valueobjects.AnalyticsProjectionIdentity;
import com.neurovision.rehab.analytics.domain.model.valueobjects.GazePlotCursor;
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

/** Authenticates client-held cursors without exposing the server signing key. */
@ApplicationScoped
@Startup
public class GazePlotCursorCodec {
    private static final int MAX_CURSOR_LENGTH = 1024;
    private static final int MIN_KEY_BYTES = 32;
    private static final String HMAC_ALGORITHM = "HmacSHA256";
    private final byte[] signingKey;

    @Inject
    public GazePlotCursorCodec(@ConfigProperty(name = "analytics.gaze-plot.cursor-signing-key",
            defaultValue = "") String signingKey) {
        byte[] key = Objects.requireNonNull(signingKey, "signingKey").getBytes(StandardCharsets.UTF_8);
        if (key.length < MIN_KEY_BYTES) {
            throw new IllegalArgumentException("ANALYTICS_GAZE_PLOT_CURSOR_SIGNING_KEY must be configured with at least 32 bytes");
        }
        this.signingKey = key.clone();
    }

    public String encode(GazePlotCursor cursor) {
        Objects.requireNonNull(cursor, "cursor");
        var identity = cursor.identity();
        String value = String.join("|", Integer.toString(cursor.cursorVersion()),
                identity.therapySessionId().toString(), identity.streamId().toString(),
                identity.calculationVersion(), Long.toString(cursor.projectionRevision()),
                Long.toString(cursor.resumeSequence()), Integer.toString(cursor.resumeGazeSampleIndex()),
                Long.toString(cursor.previousPointSequence()), Integer.toString(cursor.previousPointGazeSampleIndex()),
                cursor.previousEffectiveTime() == null ? "" : cursor.previousEffectiveTime().toString());
        byte[] payload = value.getBytes(StandardCharsets.UTF_8);
        String encodedPayload = Base64.getUrlEncoder().withoutPadding().encodeToString(payload);
        String encodedSignature = Base64.getUrlEncoder().withoutPadding().encodeToString(sign(payload));
        String token = encodedPayload + "." + encodedSignature;
        if (token.length() > MAX_CURSOR_LENGTH) throw new IllegalArgumentException("Invalid gaze-plot cursor");
        return token;
    }

    public GazePlotCursor decode(String token, AnalyticsProjectionIdentity requestedIdentity) {
        if (token == null || token.isBlank() || token.length() > MAX_CURSOR_LENGTH) {
            throw new IllegalArgumentException("Invalid gaze-plot cursor");
        }
        String[] parts = token.split("\\.", -1);
        if (parts.length != 2) throw new IllegalArgumentException("Invalid gaze-plot cursor");
        byte[] payload = Base64.getUrlDecoder().decode(parts[0]);
        byte[] suppliedSignature = Base64.getUrlDecoder().decode(parts[1]);
        if (!MessageDigest.isEqual(sign(payload), suppliedSignature)) {
            throw new IllegalArgumentException("Invalid gaze-plot cursor");
        }
        String[] fields = new String(payload, StandardCharsets.UTF_8).split("\\|", -1);
        if (fields.length != 10) throw new IllegalArgumentException("Invalid gaze-plot cursor");
        var identity = new AnalyticsProjectionIdentity(java.util.UUID.fromString(fields[1]),
                java.util.UUID.fromString(fields[2]), fields[3]);
        if (Integer.parseInt(fields[0]) != GazePlotCursor.CURRENT_VERSION
                || !requestedIdentity.equals(identity)) {
            throw new IllegalArgumentException("Invalid gaze-plot cursor");
        }
        var previousTime = fields[9].isEmpty() ? null : java.time.Instant.parse(fields[9]);
        return new GazePlotCursor(Integer.parseInt(fields[0]), identity, Long.parseLong(fields[4]),
                Long.parseLong(fields[5]), Integer.parseInt(fields[6]), Long.parseLong(fields[7]),
                Integer.parseInt(fields[8]), previousTime);
    }

    private byte[] sign(byte[] payload) {
        try {
            Mac mac = Mac.getInstance(HMAC_ALGORITHM);
            mac.init(new SecretKeySpec(signingKey, HMAC_ALGORITHM));
            return mac.doFinal(payload);
        } catch (GeneralSecurityException unavailable) {
            throw new IllegalStateException("Gaze cursor signing is unavailable", unavailable);
        }
    }
}
