package com.neurovision.rehab.clinical.application.ports.out.integration;

import com.neurovision.rehab.clinical.application.ports.out.persistence.SessionReportSnapshotRepository.Snapshot;

/** Renders an already authorized frozen copy; never enriches or persists content. */
public interface SessionReportPdfRenderer {
    byte[] render(Snapshot snapshot);
}
