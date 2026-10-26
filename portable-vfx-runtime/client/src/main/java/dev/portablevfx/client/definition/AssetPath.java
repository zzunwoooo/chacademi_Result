package dev.portablevfx.client.definition;

import java.util.ArrayDeque;
import java.util.Locale;
import java.util.Set;

/** Virtual resource paths only: never accesses filesystem or class loader. */
public final class AssetPath {
    private AssetPath() { }
    public static void validateResourcePath(String path) {
        if (path == null || path.length() > 240 || !path.startsWith("claude/") || !path.matches("[a-z0-9_./-]+"))
            throw new IllegalArgumentException("Invalid effect resource path");
        for (String part : path.split("/", -1))
            if (part.isEmpty() || part.equals(".") || part.equals("..")) throw new IllegalArgumentException("Unsafe path component");
        String ext = path.substring(path.lastIndexOf('.') + 1);
        if (!(path.endsWith(".vfx.json") || ext.equals("png") || ext.equals("glb"))) throw new IllegalArgumentException("Unsupported asset extension");
    }
    public static String dependency(String effectPath, String reference) {
        validateResourcePath(effectPath);
        if (reference == null || reference.isBlank() || reference.length() > 240)
            throw new IllegalArgumentException("Invalid dependency reference");
        String r = reference.replace('\\', '/').toLowerCase(Locale.ROOT);
        if (r.startsWith("/") || r.contains(":") || !r.matches("[a-z0-9_./-]+"))
            throw new IllegalArgumentException("Dependency must be a relative resource path");
        var parts = new ArrayDeque<String>();
        for (String p : effectPath.substring(0, effectPath.lastIndexOf('/')).split("/")) parts.add(p);
        for (String p : r.split("/", -1)) {
            if (p.isEmpty()) throw new IllegalArgumentException("Empty dependency component");
            if (p.equals(".")) continue;
            if (p.equals("..")) {
                if (parts.size() <= 1) throw new IllegalArgumentException("Dependency escapes backend resource directory");
                parts.removeLast();
            } else parts.add(p);
        }
        String result = String.join("/", parts);
        validateResourcePath(result);
        return result;
    }
}
