package com.neurovision.rehab.clinical.application.internal.queryservices;

import com.neurovision.rehab.clinical.application.ports.out.persistence.SessionReportSnapshotListRepository;
import com.neurovision.rehab.clinical.application.ports.out.persistence.SessionReportSnapshotListRepository.Summary;
import com.neurovision.rehab.clinical.domain.model.valueobjects.CognitoSubject;
import java.util.*;

public final class ListSessionReportSnapshotsService {
    private final SessionReportSnapshotListRepository reads;
    public ListSessionReportSnapshotsService(SessionReportSnapshotListRepository reads) { this.reads = Objects.requireNonNull(reads); }
    public Page list(String doctor, UUID patient, UUID session, int page, int size) {
        try {
            if (!new CognitoSubject(doctor).value().equals(doctor) || patient == null || session == null
                    || page < 0 || size < 1 || size > 100 || (long) page * size > Integer.MAX_VALUE)
                throw new IllegalArgumentException();
        } catch (RuntimeException invalid) { throw new IllegalArgumentException("Invalid snapshot list request"); }
        try {
            var observed = Objects.requireNonNull(reads.observeList(doctor, patient, session, page * size, size));
            if (!observed.authorized()) throw new SecurityException();
            int expected = (int) Math.min(size, Math.max(0, observed.total() - (long) page * size));
            if (observed.snapshots().size() != expected) throw new IllegalStateException();
            // PostgreSQL UUID order is unsigned byte order, not Java UUID.compareTo's signed-long order.
            Comparator<Summary> order = Comparator.comparing(Summary::createdAt)
                    .thenComparing(value -> value.reportSnapshotId().toString()).reversed();
            var ids = new HashSet<UUID>(); Summary previous = null;
            for (var value : observed.snapshots()) {
                if (!ids.add(value.reportSnapshotId()) || (previous != null && order.compare(previous, value) >= 0))
                    throw new IllegalStateException();
                previous = value;
            }
            return new Page(patient, session, observed.snapshots(), page, size, observed.total());
        } catch (SecurityException denied) { throw new SecurityException("Session report snapshot list denied"); }
        catch (RuntimeException unavailable) { throw new IllegalStateException("Session report snapshot list unavailable"); }
    }
    public record Page(UUID clinicalPatientId, UUID therapySessionId, List<Summary> snapshots, int page, int size, long total) {
        public Page { snapshots = List.copyOf(snapshots); }
        @Override public String toString() { return "ReportSnapshotListPage[redacted]"; }
    }
}
