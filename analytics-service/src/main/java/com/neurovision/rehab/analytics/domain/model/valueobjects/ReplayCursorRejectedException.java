package com.neurovision.rehab.analytics.domain.model.valueobjects;

/** A signed replay continuation no longer describes the current projection. */
public final class ReplayCursorRejectedException extends RuntimeException {
    public ReplayCursorRejectedException() { super("Replay cursor is stale or mismatched"); }
}
