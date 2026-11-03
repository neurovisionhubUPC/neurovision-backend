package com.neurovision.rehab.clinical.application.internal.commandservices;

/** The persisted scoped key already belongs to a different explicit/omitted selection. */
public final class SessionReportSnapshotSelectionConflictException extends RuntimeException {
    public SessionReportSnapshotSelectionConflictException() {
        super("Session report snapshot selection conflict",null,false,false);
    }
}
