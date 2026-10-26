package school.magiccodex.client;

import java.util.concurrent.atomic.AtomicLong;

/**
 * Process-wide totals across every {@link HudTextureCache} instance (A14 instrumentation only; no behaviour change).
 * Logged at debug level whenever the retained total reaches a new 16 MiB high-water step.
 */
final class HudTextureStats {
    static final long LOG_STEP = 16L * 1024 * 1024;
    private static final AtomicLong INSTANCES = new AtomicLong(), TEXTURES = new AtomicLong(), BYTES = new AtomicLong(),
            UPLOADS = new AtomicLong(), SYNC_LOADS = new AtomicLong(), SYNC_NANOS = new AtomicLong(), LOGGED_STEP = new AtomicLong();
    private HudTextureStats() { }
    record Snapshot(long instances, long textures, long bytes, long uploads, long syncLoads, long syncNanos) {
        @Override public String toString() {
            return "instances=" + instances + ", textures=" + textures + ", retained=" + (bytes / 1024) + "KiB, uploads=" + uploads
                    + ", syncLoads=" + syncLoads + ", syncLoadMs=" + (syncNanos / 1_000_000);
        }
    }
    static void opened() { INSTANCES.incrementAndGet(); }
    static void closed(long bytes, long textures) { INSTANCES.decrementAndGet(); BYTES.addAndGet(-bytes); TEXTURES.addAndGet(-textures); }
    static void uploaded(long bytes) {
        TEXTURES.incrementAndGet(); UPLOADS.incrementAndGet();
        long total = BYTES.addAndGet(bytes), step = total / LOG_STEP, logged = LOGGED_STEP.get();
        if (step > logged && LOGGED_STEP.compareAndSet(logged, step))
            org.slf4j.LoggerFactory.getLogger("magiccodex").debug("HUD textures (all caches): {}", snapshot());
    }
    static void evicted(long bytes) { TEXTURES.decrementAndGet(); BYTES.addAndGet(-bytes); }
    static void syncLoad(long nanos) { SYNC_LOADS.incrementAndGet(); SYNC_NANOS.addAndGet(Math.max(0, nanos)); }
    static Snapshot snapshot() {
        return new Snapshot(INSTANCES.get(), TEXTURES.get(), BYTES.get(), UPLOADS.get(), SYNC_LOADS.get(), SYNC_NANOS.get());
    }
}
