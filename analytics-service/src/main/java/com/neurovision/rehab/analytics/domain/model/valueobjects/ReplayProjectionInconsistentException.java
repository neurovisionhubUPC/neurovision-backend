package com.neurovision.rehab.analytics.domain.model.valueobjects;

/** The admitted inbox and summary revision are not a self-consistent projection snapshot. */
public final class ReplayProjectionInconsistentException extends RuntimeException {
    public ReplayProjectionInconsistentException() { super("Replay inbox and summary projection are inconsistent"); }
}
