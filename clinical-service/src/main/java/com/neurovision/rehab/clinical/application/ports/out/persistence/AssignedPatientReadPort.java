package com.neurovision.rehab.clinical.application.ports.out.persistence;

import java.time.Instant;
import java.util.*;

/** All reads must include the exact doctor and ACTIVE assignment predicate. */
public interface AssignedPatientReadPort {
    Page list(String doctorSubject, int offset, int size);
    Optional<Patient> detail(String doctorSubject, UUID patientId);
    Map<UUID, String> resolve(String doctorSubject, List<UUID> patientIds);
    record Patient(UUID id, String status, Instant createdAt, Instant updatedAt, String diagnosis, List<String> sequelae) {
        public Patient { Objects.requireNonNull(id); Objects.requireNonNull(status); Objects.requireNonNull(createdAt); Objects.requireNonNull(updatedAt); sequelae = List.copyOf(sequelae); }
        @Override public String toString() { return "Patient[redacted]"; }
    }
    record Page(List<Patient> patients, long total) {
        public Page { patients = List.copyOf(patients); if (total < 0) throw new IllegalArgumentException("Invalid total"); }
    }
}
