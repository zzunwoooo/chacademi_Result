package school.magiccodex.client;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

/**
 * Camera + aiming preferences (client only, config/magiccodex/aim.properties).
 * Every value is clamped so a hand-edited file can never break the camera.
 */
public final class AimSettings {
    /** Over-the-shoulder third person (replaces Shoulder Surfing Reloaded). F5 front view stays vanilla. */
    public boolean shoulder = true;
    /** 1 = camera over the right shoulder (character on the left of the screen), -1 = left shoulder. */
    public int side = 1;
    /** Blocks behind the eyes (vanilla third person is 4). */
    public float distance = 5f;
    /** Blocks to the side of the eyes. */
    public float sideOffset = DEFAULT_SIDE;
    /** Blocks above the eyes. */
    public float height = 1f;
    /** Magic mode in third person: freeze the view and aim with a separate cursor. */
    public boolean aimCursor = false; // Legacy config value is intentionally ignored.
    /** Aim cursor speed multiplier (1 = the cursor moves as far as the view would have turned). */
    public float cursorSpeed = 1f;
    /** LoL "smart cast": cast at the cursor on key press instead of showing the preview first. */
    public boolean smartCast = false;

    /** Default shoulder offset; {@link #LEGACY_DEFAULT_SIDE} was the shipped default before aim-version 2. */
    public static final float DEFAULT_SIDE = 1f, LEGACY_DEFAULT_SIDE = 1.5f;
    /** Written on every save. Files without it were saved before the 1.5 -> 1.0 default change. */
    static final int VERSION = 2;
    /** Set by {@link #load} when an unversioned file still held the old default; {@link #get} then records the version once. */
    boolean migrated;

    public static final float MIN_DISTANCE = 1.5f, MAX_DISTANCE = 6f, MAX_SIDE = 2f, MIN_SPEED = .2f, MAX_SPEED = 3f;

    private static AimSettings current;
    private static Path path;

    public static AimSettings get() {
        if (current == null) {
            try { path = net.fabricmc.loader.api.FabricLoader.getInstance().getConfigDir().resolve("magiccodex/aim.properties"); }
            catch (RuntimeException | LinkageError noLoader) { path = null; }
            current = path == null ? new AimSettings() : load(path);
            if (current.migrated && path != null && current.persist(path)) current.migrated = false;
        }
        return current;
    }

    public static AimSettings load(Path file) {
        var s = new AimSettings();
        if (file == null || !Files.isRegularFile(file)) return s;
        var p = new Properties();
        try (Reader in = Files.newBufferedReader(file, StandardCharsets.UTF_8)) { p.load(in); }
        catch (IOException | IllegalArgumentException error) { return s; }
        s.shoulder = bool(p, "shoulder", s.shoulder);
        s.side = "left".equalsIgnoreCase(p.getProperty("side", "right").trim()) ? -1 : 1;
        s.distance = num(p, "distance", s.distance, MIN_DISTANCE, MAX_DISTANCE);
        s.sideOffset = num(p, "side-offset", s.sideOffset, 0, MAX_SIDE);
        // One-time migration: an unversioned file still holding exactly the old default gets the new default.
        if (p.getProperty("aim-version") == null && s.sideOffset == LEGACY_DEFAULT_SIDE) { s.sideOffset = DEFAULT_SIDE; s.migrated = true; }
        s.height = num(p, "height", s.height, -.5f, 1.5f);
        s.aimCursor = false;
        s.cursorSpeed = num(p, "cursor-speed", s.cursorSpeed, MIN_SPEED, MAX_SPEED);
        s.smartCast = bool(p, "smart-cast", s.smartCast);
        return s;
    }

    public void save() { if(path!=null) save(path); }
    public AimSettings copy(){var c=new AimSettings();c.shoulder=shoulder;c.side=side;c.distance=distance;c.sideOffset=sideOffset;c.height=height;c.smartCast=smartCast;c.cursorSpeed=cursorSpeed;return c;}
    public boolean commit(){if(path==null||!persist(path))return false;current=copy();return true;}
    public void save(Path destination){persist(destination);}
    public boolean persist(Path destination) {
        var p = new Properties();
        // Preserve unrelated user properties, including future extensions.
        if(Files.isRegularFile(destination)) {
            try(Reader in=Files.newBufferedReader(destination,StandardCharsets.UTF_8)){p.load(in);}
            catch(IOException | IllegalArgumentException error){
                org.slf4j.LoggerFactory.getLogger("magiccodex").warn("aim settings preserved: cannot read existing file",error);return false;
            }
        }
        p.setProperty("shoulder", Boolean.toString(shoulder));
        p.setProperty("side", side < 0 ? "left" : "right");
        p.setProperty("distance", Float.toString(distance));
        p.setProperty("side-offset", Float.toString(sideOffset));
        p.setProperty("height", Float.toString(height));
        p.setProperty("aim-cursor", "false");
        p.setProperty("cursor-speed", Float.toString(cursorSpeed));
        p.setProperty("smart-cast", Boolean.toString(smartCast));
        p.setProperty("aim-version", Integer.toString(VERSION));
        try {
            Path target=destination.toAbsolutePath(); Files.createDirectories(target.getParent());
            Path temp=Files.createTempFile(target.getParent(),"aim-",".tmp");
            try {
                try(Writer out=Files.newBufferedWriter(temp,StandardCharsets.UTF_8)){p.store(out,"Chacademi camera / aim (client only)");}
                Files.move(temp,target,java.nio.file.StandardCopyOption.ATOMIC_MOVE,java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            } finally {Files.deleteIfExists(temp);}
            return true;
        } catch (IOException error) {
            org.slf4j.LoggerFactory.getLogger("magiccodex").warn("aim settings save failed", error);return false;
        }
    }

    private static boolean bool(Properties p, String key, boolean fallback) {
        String v = p.getProperty(key);
        return v == null ? fallback : Boolean.parseBoolean(v.trim());
    }
    private static float num(Properties p, String key, float fallback, float min, float max) {
        try {
            String v = p.getProperty(key);
            float f = v == null ? fallback : Float.parseFloat(v.trim());
            return Float.isFinite(f) ? Math.clamp(f, min, max) : fallback;
        } catch (NumberFormatException bad) { return fallback; }
    }
}
