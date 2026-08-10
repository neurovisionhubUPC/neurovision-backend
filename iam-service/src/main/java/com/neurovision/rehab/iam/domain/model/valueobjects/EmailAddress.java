package com.neurovision.rehab.iam.domain.model.valueobjects;

import java.util.Locale;
import java.util.regex.Pattern;

public record EmailAddress(String value) {

    private static final int MAX_LENGTH = 254;
    private static final Pattern BASIC_EMAIL_PATTERN =
            Pattern.compile("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$");

    public EmailAddress {
        if (value == null) {
            throw new IllegalArgumentException("Email address must not be null");
        }

        value = value.trim().toLowerCase(Locale.ROOT);
        if (value.isEmpty() || value.length() > MAX_LENGTH || !BASIC_EMAIL_PATTERN.matcher(value).matches()) {
            throw new IllegalArgumentException("Email address is invalid");
        }
    }
}
