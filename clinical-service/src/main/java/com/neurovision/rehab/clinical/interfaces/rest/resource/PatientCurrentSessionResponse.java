package com.neurovision.rehab.clinical.interfaces.rest.resource;

import io.quarkus.runtime.annotations.RegisterForReflection;
import java.time.Instant;

@RegisterForReflection
public record PatientCurrentSessionResponse(String state, Instant evaluatedAt, PatientTherapySessionResponse session) {
    @Override public String toString() { return "PatientCurrentSessionResponse[redacted]"; }
}
