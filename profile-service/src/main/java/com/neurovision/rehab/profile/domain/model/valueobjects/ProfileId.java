package com.neurovision.rehab.profile.domain.model.valueobjects;

import java.util.Objects;
import java.util.UUID;

public record ProfileId(UUID value) {

    public ProfileId {
        Objects.requireNonNull(value, "Profile ID must not be null");
    }
}
