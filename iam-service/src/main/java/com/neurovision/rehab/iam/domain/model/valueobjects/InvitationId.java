package com.neurovision.rehab.iam.domain.model.valueobjects;

import java.util.Objects;
import java.util.UUID;

public record InvitationId(UUID value) {

    public InvitationId {
        Objects.requireNonNull(value, "Invitation ID must not be null");
    }
}
