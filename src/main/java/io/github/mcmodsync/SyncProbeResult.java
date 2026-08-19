package io.github.mcmodsync;

record SyncProbeResult(Status status, int estimatedCommitFiles, long estimatedCommitBytes) {
    SyncProbeResult(Status status) {
        this(status, 0, 0L);
    }

    SyncProbeResult {
        estimatedCommitFiles = Math.max(estimatedCommitFiles, 0);
        estimatedCommitBytes = Math.max(estimatedCommitBytes, 0L);
    }

    enum Status {
        UP_TO_DATE,
        CHANGES_REQUIRED,
        SKIPPED_OFFLINE
    }
}
