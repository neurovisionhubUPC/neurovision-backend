package com.neurovision.rehab.clinical.application.ports.out.integration;

import com.neurovision.rehab.clinical.application.internal.queryservices.SessionReportV1;

/** Encodes only the existing outward report whitelist, never the internal report record. */
@FunctionalInterface
public interface SessionReportSnapshotEncoder {
    String encode(SessionReportV1 report);
}
