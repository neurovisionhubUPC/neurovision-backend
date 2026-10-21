package com.neurovision.rehab.clinical.application.internal.queryservices;

import com.neurovision.rehab.clinical.application.ports.out.persistence.AssignedPatientReadPort;
import com.neurovision.rehab.clinical.application.ports.out.integration.AssignedPatientNamesPort;
import com.neurovision.rehab.clinical.domain.model.valueobjects.CognitoSubject;
import java.util.*;
import java.util.function.Supplier;
import static com.neurovision.rehab.clinical.application.internal.queryservices.PatientReadFailure.Kind.*;

public final class AssignedPatientQueryService {
    private final AssignedPatientReadPort reads;
    private final AssignedPatientNamesPort names;
    public AssignedPatientQueryService(AssignedPatientReadPort reads, AssignedPatientNamesPort names) {
        this.reads = Objects.requireNonNull(reads); this.names = Objects.requireNonNull(names);
    }
    public PageView list(String doctor, String token, int page, int size) {
        doctor(doctor); token(token);
        if (page < 0 || size < 1 || size > 100 || (long)page * size > Integer.MAX_VALUE) throw new PatientReadFailure(INVALID_REQUEST);
        return safe(() -> {
            var result = reads.list(doctor, page * size, size);
            if (result.patients().size() > size) throw new PatientReadFailure(UNAVAILABLE);
            var ids = result.patients().stream().map(AssignedPatientReadPort.Patient::id).toList();
            if (new HashSet<>(ids).size() != ids.size()) throw new PatientReadFailure(UNAVAILABLE);
            var resolved = ids.isEmpty() ? Map.<UUID,AssignedPatientNamesPort.Name>of() : names.lookup(authorizeNames(doctor, ids), token);
            if (resolved == null || !resolved.keySet().equals(new HashSet<>(ids))) throw new PatientReadFailure(UNAVAILABLE);
            var rows = result.patients().stream().map(p -> new View(p, Objects.requireNonNull(resolved.get(p.id())))).toList();
            return new PageView(rows, page, size, result.total());
        });
    }
    public View detail(String doctor, String token, UUID id) {
        doctor(doctor); token(token); if (id == null) throw new PatientReadFailure(INVALID_REQUEST);
        return safe(() -> {
            var patient = reads.detail(doctor, id).orElseThrow(() -> new PatientReadFailure(FORBIDDEN));
            if (!id.equals(patient.id())) throw new PatientReadFailure(UNAVAILABLE);
            var resolved = names.lookup(authorizeNames(doctor, List.of(id)), token);
            if (resolved == null || !resolved.keySet().equals(Set.of(id))) throw new PatientReadFailure(UNAVAILABLE);
            return new View(patient, Objects.requireNonNull(resolved.get(id)));
        });
    }
    /** Authorization-only callback: never calls Profile or enriches a patient read. */
    public Map<UUID,String> authorizeNames(String doctor, List<UUID> ids) {
        doctor(doctor); var requested = ids(ids);
        return safe(() -> {
            var found = reads.resolve(doctor, requested);
            if (found == null || !found.keySet().equals(new HashSet<>(requested))) throw new PatientReadFailure(FORBIDDEN);
            var subjects = new HashSet<String>();
            for (var sub : found.values()) {
                if (!new CognitoSubject(sub).value().equals(sub) || !subjects.add(sub)) throw new PatientReadFailure(UNAVAILABLE);
            }
            return Map.copyOf(found);
        });
    }
    public static List<UUID> ids(List<UUID> ids) {
        if (ids == null || ids.isEmpty() || ids.size() > 100 || ids.stream().anyMatch(Objects::isNull) || new HashSet<>(ids).size() != ids.size()) throw new PatientReadFailure(INVALID_REQUEST);
        return List.copyOf(ids);
    }
    private static void doctor(String value) {
        try { if (!new CognitoSubject(value).value().equals(value)) throw new IllegalArgumentException(); }
        catch (RuntimeException invalid) { throw new PatientReadFailure(FORBIDDEN); }
    }
    private static void token(String value) { if (value == null || value.isBlank()) throw new PatientReadFailure(UNAUTHENTICATED); }
    private static <T> T safe(Supplier<T> action) {
        try { return action.get(); } catch (PatientReadFailure known) { throw known; }
        catch (RuntimeException unavailable) { throw new PatientReadFailure(UNAVAILABLE); }
    }
    public record View(AssignedPatientReadPort.Patient patient, AssignedPatientNamesPort.Name name) {}
    public record PageView(List<View> patients, int page, int size, long total) {}
}
