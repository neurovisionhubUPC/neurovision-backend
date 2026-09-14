package com.neurovision.rehab.clinical.domain.model.valueobjects;

import java.util.Locale;
import java.util.Objects;
import java.util.regex.Pattern;

public record ExerciseCode(String value) implements Comparable<ExerciseCode> {
    public static final int MAX_LENGTH = 100;
    private static final Pattern FORMAT = Pattern.compile("[a-z0-9][a-z0-9._-]{0,99}");

    public ExerciseCode {
        Objects.requireNonNull(value, "Exercise code must not be null");
        value = value.trim().toLowerCase(Locale.ROOT);
        if (!FORMAT.matcher(value).matches()) {
            throw new IllegalArgumentException(
                    "Exercise code must contain 1 to 100 lowercase alphanumeric, dot, underscore, or hyphen characters");
        }
    }

    @Override
    public int compareTo(ExerciseCode other) {
        return value.compareTo(other.value);
    }
}
