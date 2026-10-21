package com.neurovision.rehab.clinical.application.ports.out.integration;

import java.util.*;

public interface AssignedPatientNamesPort {
    Map<UUID, Name> lookup(Map<UUID,String> authorizedTargets, String originalAccessToken);
    record Name(String status, String firstName, String lastName) {
        public Name {
            if ("AVAILABLE".equals(status)) {
                if (!valid(firstName) || !valid(lastName)) throw new IllegalArgumentException("Invalid name");
            } else if ((!"MISSING".equals(status) && !"PENDING_COMPLETION".equals(status)) || firstName != null || lastName != null) {
                throw new IllegalArgumentException("Invalid name status");
            }
        }
        private static boolean valid(String s) { return s != null && !s.isBlank() && s.length() <= 100; }
        @Override public String toString() { return "Name[redacted]"; }
    }
}
