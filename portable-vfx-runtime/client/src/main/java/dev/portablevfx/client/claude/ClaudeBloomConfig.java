package dev.portablevfx.client.claude;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

/** Global effect-only bloom gain. Authored cores, tint, threshold and scatter are untouched. */
public final class ClaudeBloomConfig {
    public static final double DEFAULT_STRENGTH = 0.40;
    /** Shipped default before config version 2; files still at exactly this value without a version key migrate once. */
    static final double LEGACY_DEFAULT_STRENGTH = 0.65;
    static final int VERSION = 2;
    private static final org.slf4j.Logger LOG = org.slf4j.LoggerFactory.getLogger("PortableVFX");
    private static volatile double strength = DEFAULT_STRENGTH;
    private static volatile Path file;
    private ClaudeBloomConfig() { }
    public static double strength() { return strength; }
    public static double intensity(double authoredIntensity) { return authoredIntensity * strength; }
    /** Read once at client startup; missing files are created with the balanced default. */
    public static void load(Path path) throws IOException {
        strength = DEFAULT_STRENGTH;
        file = path;
        if (!Files.exists(path)) { write(path, DEFAULT_STRENGTH); return; }
        Properties properties = new Properties();
        try {
            try (var input = Files.newBufferedReader(path)) {
                properties.load(input);
            }
            // Close the source before the migration atomically replaces it (required on Windows).
            String raw = properties.getProperty("strength");
            double parsed = raw == null ? DEFAULT_STRENGTH : parse(raw);
            boolean unversioned = properties.getProperty("version") == null;
            // One-time migration: an unversioned file still holding the old shipped default gets the new default.
            if (unversioned && raw != null && parsed == LEGACY_DEFAULT_STRENGTH) parsed = DEFAULT_STRENGTH;
            strength = parsed;
            if (unversioned) {
                try { write(path, parsed); }
                catch (IOException error) { LOG.warn("Could not record PortableVFX bloom settings version; will retry next start", error); }
            }
        }
        catch (IllegalArgumentException error) { throw new IOException("Invalid PortableVFX bloom strength; expected a finite number (out-of-range values clamp to 0..1)", error); }
    }
    /** Settings-screen entry: applies immediately (read per frame by the post pass) and persists with the version key. */
    public static void save(double value) throws IOException {
        if (!Double.isFinite(value)) throw new IOException("Invalid PortableVFX bloom strength");
        double clamped = Math.max(0, Math.min(1, value));
        Path target = file;
        if (target == null) throw new IOException("PortableVFX bloom settings were not loaded");
        write(target, clamped);
        strength = clamped;
    }
    private static void write(Path path, double value) throws IOException {
        Path target = path.toAbsolutePath();
        Files.createDirectories(target.getParent());
        Path temp = Files.createTempFile(target.getParent(), "bloom-", ".tmp");
        try {
            Files.writeString(temp, "# Effect-only bloom multiplier; 0=off, 0.4=balanced, 1=original. Also adjustable in Settings > Skill effects.\nversion=" + VERSION + "\nstrength=" + value + "\n");
            Files.move(temp, target, java.nio.file.StandardCopyOption.ATOMIC_MOVE, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        } finally { Files.deleteIfExists(temp); }
    }
    /** Finite out-of-range values clamp to [0,1] with a warning; NaN/Infinity/garbage reject (caller keeps the default). */
    static double parse(String value) {
        double parsed = Double.parseDouble(value.trim());
        if (!Double.isFinite(parsed)) throw new IllegalArgumentException("strength must be finite");
        double clamped = Math.max(0, Math.min(1, parsed));
        if (clamped != parsed) LOG.warn("PortableVFX bloom strength {} is outside 0..1; clamped to {}", parsed, clamped);
        return clamped;
    }
}
