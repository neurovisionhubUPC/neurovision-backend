package com.neurovision.rehab.clinical.application.internal.commandservices;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;

public final class StartTherapySessionRequestFingerprint {
    private StartTherapySessionRequestFingerprint() {}

    public static String compute(StartTherapySessionCommand command) {
        Objects.requireNonNull(command);
        String canonical = command.clinicalPatientId().value() + "\n"
                + command.therapyPlanId().value() + "\n" + command.exerciseCode().value();
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(canonical.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }
}
