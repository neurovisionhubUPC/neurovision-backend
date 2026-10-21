package com.neurovision.rehab.clinical.domain.model.valueobjects;

import java.util.List;
import java.util.Objects;
import java.util.HashSet;
import java.util.ArrayList;

/** Clinician-entered context, never an automated diagnosis or eligibility decision. */
public record RehabilitationContext(
        Hemifield affectedHemifield, String previousDiagnosis, List<String> sequelae,
        String observations) {
    public RehabilitationContext {
        sequelae = List.copyOf(Objects.requireNonNull(sequelae));
    }

    /** Preserve stored legacy text exactly; submission limits apply only to replaced fields. */
    public static RehabilitationContext rehydrate(Hemifield side, String diagnosis,
            List<String> sequelae, String observations) {
        return new RehabilitationContext(side, diagnosis, sequelae, observations);
    }

    public RehabilitationContext apply(Patch patch) {
        Objects.requireNonNull(patch);
        return new RehabilitationContext(
                patch.affectedHemifield().supplied() ? patch.affectedHemifield().value() : affectedHemifield,
                patch.previousDiagnosis().supplied() ? text(patch.previousDiagnosis().value(), 500) : previousDiagnosis,
                patch.sequelae().supplied() ? submittedSequelae(patch.sequelae().value()) : sequelae,
                patch.observations().supplied() ? text(patch.observations().value(), 2000) : observations);
    }

    private static List<String> submittedSequelae(List<String> values) {
        if (values == null) return List.of();
        var normalized = new ArrayList<String>();
        var unique = new HashSet<String>();
        int total = 0;
        for (String value : values) {
            String entry = text(value, 1000);
            if (entry == null || !unique.add(entry)) {
                throw new IllegalArgumentException("Sequela entries must be nonblank and distinct");
            }
            total += entry.codePointCount(0, entry.length());
            if (total > 1000) throw new IllegalArgumentException("Sequela text exceeds its limit");
            normalized.add(entry);
        }
        return List.copyOf(normalized);
    }

    private static String text(String value, int maximum) {
        if (value == null) return null;
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (Character.isHighSurrogate(c)) {
                if (++i >= value.length() || !Character.isLowSurrogate(value.charAt(i))) {
                    throw new IllegalArgumentException("Invalid Unicode text");
                }
            } else if (Character.isLowSurrogate(c)
                    || (Character.isISOControl(c) && c != '\n' && c != '\r' && c != '\t')) {
                throw new IllegalArgumentException("Invalid context text");
            }
        }
        String normalized = value.strip();
        if (normalized.codePointCount(0, normalized.length()) > maximum) {
            throw new IllegalArgumentException("Context text exceeds its limit");
        }
        return normalized.isBlank() ? null : normalized;
    }

    public enum Hemifield { LEFT, RIGHT }

    public record Change<T>(boolean supplied, T value) {
        public static <T> Change<T> absent() { return new Change<>(false, null); }
        public static <T> Change<T> set(T value) { return new Change<>(true, value); }
        @Override public String toString() { return "Change[redacted]"; }
    }

    public record Patch(Change<Hemifield> affectedHemifield, Change<String> previousDiagnosis,
            Change<List<String>> sequelae, Change<String> observations) {
        public Patch {
            Objects.requireNonNull(affectedHemifield);
            Objects.requireNonNull(previousDiagnosis);
            Objects.requireNonNull(sequelae);
            Objects.requireNonNull(observations);
            if (!affectedHemifield.supplied() && !previousDiagnosis.supplied()
                    && !sequelae.supplied() && !observations.supplied()) {
                throw new IllegalArgumentException("At least one context field is required");
            }
            if (sequelae.supplied() && sequelae.value() != null) {
                sequelae = Change.set(List.copyOf(sequelae.value()));
            }
        }
        @Override public String toString() { return "Patch[redacted]"; }
    }

    @Override public String toString() { return "RehabilitationContext[redacted]"; }
}
