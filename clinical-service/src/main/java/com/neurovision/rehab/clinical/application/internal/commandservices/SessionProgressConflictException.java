package com.neurovision.rehab.clinical.application.internal.commandservices;

/** Only known domain conflicts map to HTTP 409; storage/runtime failures remain unavailable. */
public final class SessionProgressConflictException extends RuntimeException {
    public SessionProgressConflictException() { super("Session progress conflict"); }
}
