package school.chacademia.magicmythic;

import io.lumine.mythic.api.config.MythicLineConfig;
import java.util.*;
import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

/** Reads mechanic options as plain strings so a typo becomes a logged default instead of a load failure. */
final class Params {
    private final Map<String, String> values = new HashMap<>();
    private final MythicLineConfig config;

    Params(MythicLineConfig config) { this.config = config; }
    /** Test/fixed constructor. */
    Params(Map<String, String> values) { this.config = null; this.values.putAll(values); }

    String s(String key, String def) {
        if (config == null) return values.getOrDefault(key, def);
        String v = config.getString(key, def);
        return v == null ? def : v.trim();
    }
    double d(String key, double def) {
        String v = s(key, null);
        if (v == null || v.isBlank()) return def;
        try { double x = Double.parseDouble(v); return Double.isFinite(x) ? x : def; }
        catch (NumberFormatException e) { return def; }
    }
    int i(String key, int def) { return (int) Math.round(d(key, def)); }
    boolean b(String key, boolean def) {
        String v = s(key, null);
        if (v == null || v.isBlank()) return def;
        return v.equalsIgnoreCase("true") || v.equals("1") || v.equalsIgnoreCase("yes");
    }

    /** "SLOWNESS:60:1,WEAKNESS:100:0" -> effects (ticks, amplifier). Unknown names are skipped. */
    List<PotionEffect> effects(String key) { return parseEffects(s(key, "")); }

    static List<PotionEffect> parseEffects(String raw) {
        if (raw == null || raw.isBlank()) return List.of();
        var out = new ArrayList<PotionEffect>();
        for (String part : raw.split("[,+]")) {
            String[] f = part.trim().split(":");
            if (f.length == 0 || f[0].isBlank()) continue;
            PotionEffectType type = effectType(f[0]);
            if (type == null) continue;
            int ticks = f.length > 1 ? parseInt(f[1], 60) : 60;
            int amp = f.length > 2 ? parseInt(f[2], 0) : 0;
            out.add(new PotionEffect(type, Math.clamp(ticks, 1, 20 * 60 * 10), Math.clamp(amp, 0, 255)));
        }
        return out;
    }
    static PotionEffectType effectType(String name) {
        String key = name.trim().toLowerCase(Locale.ROOT);
        if (key.startsWith("minecraft:")) key = key.substring(10);
        return Registry.EFFECT.get(NamespacedKey.minecraft(key));
    }
    private static int parseInt(String s, int def) {
        try { return Integer.parseInt(s.trim()); } catch (NumberFormatException e) { return def; }
    }
}
