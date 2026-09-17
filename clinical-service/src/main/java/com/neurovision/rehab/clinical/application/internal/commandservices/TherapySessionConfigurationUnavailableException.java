package com.neurovision.rehab.clinical.application.internal.commandservices;

/** Generic denial used when no server-owned configuration is eligible for session admission. */
public final class TherapySessionConfigurationUnavailableException extends IllegalStateException {
    public TherapySessionConfigurationUnavailableException() {
        super("No eligible therapeutic configuration is available");
    }
}
