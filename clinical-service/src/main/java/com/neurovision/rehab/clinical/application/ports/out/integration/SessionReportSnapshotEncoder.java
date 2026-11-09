package com.neurovision.rehab.clinical.application.ports.out.integration;

import com.neurovision.rehab.clinical.application.internal.queryservices.SessionReportV1;

/** Encodes only the existing outward report whitelist, never the internal report record. */
@FunctionalInterface
public interface SessionReportSnapshotEncoder {
    String encode(SessionReportV1 report);

    /** Strictly reads the saved selection; absent legacy section means null, unreadable means unavailable.
     * Encoding-only implementations must fail closed rather than guess at replay selection.
     */
    default SessionReportStandardScanningPort.Origin standardScanningSelection(String savedReportJson, java.util.UUID expectedSession) {
        throw new IllegalStateException("Session report snapshot unavailable");
    }
    default SessionReportSustainedFixationPort.Origin sustainedFixationSelection(String savedReportJson, java.util.UUID expectedSession) {
        throw new IllegalStateException("Session report snapshot unavailable");
    }
    default SessionReportCompensatorySaccadePort.Origin compensatorySaccadeSelection(String savedReportJson, java.util.UUID expectedSession) {
        throw new IllegalStateException("Session report snapshot unavailable");
    }
    default SessionReportSystematicExplorationPort.Origin systematicExplorationSelection(String savedReportJson, java.util.UUID expectedSession) {
        throw new IllegalStateException("Session report snapshot unavailable");
    }
    default SessionReportVisualSearchPort.Origin visualSearchSelection(String savedReportJson, java.util.UUID expectedSession) {
        throw new IllegalStateException("Session report snapshot unavailable");
    }
}
