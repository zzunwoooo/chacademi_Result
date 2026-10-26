package school.chacademia.gimmick;

import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.*;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.plugin.Plugin;

/**
 * PortableVFX effects for the gimmick spells, played at the gimmick (claude:<pack>/<system>, impact role, scale 1).
 * Reflection only (PortableVFX is a soft dependency); same calls as ChAcademiaMagicMythic ReactionVfx / GameplayVisual.
 * play(request) with yaw and an RGB tint; start() returns the handle so long effects (beam segments, melting) can be stopped early; null = not shown (no PortableVFX,
 * missing pack, budget exhausted). Gameplay never waits for an effect.
 */
final class GimmickVfx {
    private final GimmickPlugin plugin;
    private boolean resolved, warned;
    private Object service;
    private Constructor<?> request;
    private Method play, stop;

    GimmickVfx(GimmickPlugin plugin) { this.plugin = plugin; }
    void reload() { resolved = false; service = null; }

    /** @param yaw Minecraft yaw of the effect's +Z  @param rgb 0xFFFFFF = authored colours */
    UUID start(String id, Location at, float yaw, int ticks, int rgb) {
        if (id == null || at == null || at.getWorld() == null || !plugin.getConfig().getBoolean("vfx.enabled", true) || !resolve()) return null;
        try {
            double range = plugin.getConfig().getDouble("vfx.range", 48);
            Object req = request.newInstance(id.toLowerCase(Locale.ROOT), at.getWorld().getName(), at.getX(), at.getY(), at.getZ(),
                1f, Math.clamp(ticks, 1, 20 * 60), yaw, 0f, 0f, rgb & 0xFFFFFF, 1f, range);
            Object result = play.invoke(service, req);
            return result == null ? null : (UUID) result.getClass().getMethod("handle").invoke(result);
        } catch (InvocationTargetException e) {
            if (!(e.getCause() instanceof java.util.concurrent.RejectedExecutionException)) warn(e.getCause());
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) { warn(e); }
        return null;
    }
    void play(String id, Location at, float yaw, int ticks) { start(id, at, yaw, ticks, 0xFFFFFF); }

    void stop(UUID handle) {
        if (handle == null || service == null) return;
        try { stop.invoke(service, handle); } catch (ReflectiveOperationException | RuntimeException | LinkageError ignored) {}
    }

    private boolean resolve() {
        if (resolved) return service != null;
        resolved = true;
        Plugin vfx = Bukkit.getPluginManager().getPlugin("PortableVFX");
        if (vfx == null || !vfx.isEnabled()) return false;
        try {
            ClassLoader loader = vfx.getClass().getClassLoader();
            Class<?> api = Class.forName("dev.portablevfx.paper.api.PortableVfxService", true, loader);
            Class<?> req = Class.forName("dev.portablevfx.paper.api.EffectRequest", true, loader);
            var reg = Bukkit.getServicesManager().getRegistration(api);
            if (reg == null) return false;
            request = req.getConstructor(String.class, String.class, double.class, double.class, double.class,
                float.class, int.class, float.class, float.class, float.class, int.class, float.class, double.class);
            play = api.getMethod("play", req);
            stop = api.getMethod("stop", UUID.class);
            service = reg.getProvider();
            return true;
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) { warn(e); service = null; return false; }
    }

    private void warn(Throwable e) {
        if (warned) return;
        warned = true;
        plugin.getLogger().warning("기믹 VFX 재생 실패 (이후 같은 경고는 생략): " + e.getClass().getSimpleName() + " " + e.getMessage());
    }
}
