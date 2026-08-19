package io.github.mcmodsync;

/** Progress events emitted while a publisher project is materialized. */
@FunctionalInterface
interface PublisherProgress {
    enum Stage {
        PREPARE,
        HASH_AND_PLATFORM,
        COPY_HOSTED,
        WRITE_MANIFEST,
        BUILD_CLOUD_BUNDLE,
        COMPLETE
    }

    record Update(Stage stage, int completed, int total, String detail) {
        public Update {
            if (stage == null) throw new IllegalArgumentException("stage is required");
            if (completed < 0 || total < 0 || completed > total) {
                throw new IllegalArgumentException("invalid publisher progress counters");
            }
            detail = detail == null ? "" : detail;
        }
    }

    PublisherProgress NONE = update -> { };

    void accept(Update update);

    default void update(Stage stage, int completed, int total, String detail) {
        accept(new Update(stage, completed, total, detail));
    }
}
