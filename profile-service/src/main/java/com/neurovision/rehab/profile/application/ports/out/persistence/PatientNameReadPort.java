package com.neurovision.rehab.profile.application.ports.out.persistence;

import java.util.*;

public interface PatientNameReadPort {
    Map<String,Entry> names(Set<String> authorizedSubjects);
    record Entry(String type, String status, String firstName, String lastName) {
        @Override public String toString() { return "Entry[redacted]"; }
    }
}
