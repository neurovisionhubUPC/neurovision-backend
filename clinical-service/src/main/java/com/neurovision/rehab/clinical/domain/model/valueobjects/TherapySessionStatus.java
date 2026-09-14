package com.neurovision.rehab.clinical.domain.model.valueobjects;

public enum TherapySessionStatus {
    ACTIVE,
    COMPLETED,
    CANCELLED,
    ABANDONED,
    FAILED_TECHNICAL;

    public boolean isTerminal() {
        return this != ACTIVE;
    }
}
