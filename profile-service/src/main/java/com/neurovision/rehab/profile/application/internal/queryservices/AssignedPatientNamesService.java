package com.neurovision.rehab.profile.application.internal.queryservices;

import com.neurovision.rehab.profile.application.ports.out.authorization.AssignedPatientNameAuthorizationPort;
import com.neurovision.rehab.profile.application.ports.out.persistence.PatientNameReadPort;
import java.util.*;
import static com.neurovision.rehab.profile.application.internal.queryservices.PatientNameFailure.Kind.*;

public final class AssignedPatientNamesService {
    private final AssignedPatientNameAuthorizationPort authorization;
    private final PatientNameReadPort profiles;
    public AssignedPatientNamesService(AssignedPatientNameAuthorizationPort authorization, PatientNameReadPort profiles) {
        this.authorization = Objects.requireNonNull(authorization); this.profiles = Objects.requireNonNull(profiles);
    }
    public Map<UUID,Name> lookup(Map<UUID,String> targets, String token) {
        var requested = ids(targets == null ? null : new ArrayList<>(targets.keySet()));
        if (targets.values().stream().anyMatch(s -> s == null || s.isBlank() || s.length() > 128 || !s.equals(s.trim())) || new HashSet<>(targets.values()).size() != targets.size()) throw new PatientNameFailure(INVALID_REQUEST);
        var claimed = Map.copyOf(targets);
        if (token == null || token.isBlank()) throw new PatientNameFailure(UNAUTHENTICATED);
        try {
            var subjects = authorization.resolve(claimed, token);
            if (subjects == null || !subjects.equals(claimed)) throw new PatientNameFailure(UNAVAILABLE);
            var unique = new HashSet<String>();
            for (String subject : subjects.values()) {
                if (subject == null || subject.isBlank() || subject.length() > 128 || !subject.equals(subject.trim()) || !unique.add(subject)) throw new PatientNameFailure(UNAVAILABLE);
            }
            var found = profiles.names(Set.copyOf(unique));
            if (found == null || !unique.containsAll(found.keySet())) throw new PatientNameFailure(UNAVAILABLE);
            var result = new LinkedHashMap<UUID,Name>();
            for (UUID id : requested) {
                var p = found.get(subjects.get(id));
                if (p == null) result.put(id, new Name("MISSING", null, null));
                else if (!"PATIENT".equals(p.type())) throw new PatientNameFailure(UNAVAILABLE);
                else if ("PENDING_COMPLETION".equals(p.status()) && p.firstName() == null && p.lastName() == null) result.put(id, new Name("PENDING_COMPLETION", null, null));
                else if ("ACTIVE".equals(p.status()) && valid(p.firstName()) && valid(p.lastName())) result.put(id, new Name("AVAILABLE", p.firstName(), p.lastName()));
                else throw new PatientNameFailure(UNAVAILABLE);
            }
            return Collections.unmodifiableMap(result);
        } catch (PatientNameFailure known) { throw known; }
        catch (RuntimeException unavailable) { throw new PatientNameFailure(UNAVAILABLE); }
    }
    public static List<UUID> ids(List<UUID> ids) {
        if (ids == null || ids.isEmpty() || ids.size() > 100 || ids.stream().anyMatch(Objects::isNull) || new HashSet<>(ids).size() != ids.size()) throw new PatientNameFailure(INVALID_REQUEST);
        return List.copyOf(ids);
    }
    private static boolean valid(String s) { return s != null && !s.isBlank() && s.length() <= 100; }
    public record Name(String status, String firstName, String lastName) {
        @Override public String toString() { return "Name[redacted]"; }
    }
}
