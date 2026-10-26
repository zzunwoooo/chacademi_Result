package school.chacademia.gimmick;

import java.lang.reflect.Method;
import java.util.logging.Logger;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.attribute.Attribute;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;

/**
 * ItemsAdder custom entities through reflection (no compile dependency), API 4.x:
 * CustomEntity.spawn(id, loc, frustumCulling, noBase, noHitbox), convert(id, entity, frustumCulling, noHitbox,
 * canBaseEntityBeDestroyed, hideBaseEntity), playAnimation, stopAnimation, teleport, destroy, isCustomEntity.
 * ItemsAdder has no scale API: we set the base entity's minecraft:scale attribute (check in game whether bones follow).
 */
final class ItemsAdderRenderer implements Renderer {
    private final Logger log;
    private Class<?> ce;
    private Method spawn, convert, isCustom, getEntity, play, stop, teleport, destroy;
    private boolean warned;

    ItemsAdderRenderer(Logger log) { this.log = log; }
    public String name() { return "ItemsAdder"; }

    public boolean available() {
        if (!Bukkit.getPluginManager().isPluginEnabled("ItemsAdder")) return false;
        if (ce != null) return true;
        try {
            ce = Class.forName("dev.lone.itemsadder.api.CustomEntity");
            spawn = ce.getMethod("spawn", String.class, Location.class, boolean.class, boolean.class, boolean.class);
            convert = ce.getMethod("convert", String.class, LivingEntity.class, boolean.class, boolean.class, boolean.class, boolean.class);
            isCustom = ce.getMethod("isCustomEntity", Entity.class);
            getEntity = ce.getMethod("getEntity");
            play = ce.getMethod("playAnimation", String.class);
            stop = ce.getMethod("stopAnimation");
            teleport = ce.getMethod("teleport", Location.class);
            destroy = ce.getMethod("destroy");
            return true;
        } catch (ReflectiveOperationException | LinkageError e) {
            ce = null;
            if (!warned) { warned = true; log.warning("ItemsAdder CustomEntity API 를 찾지 못했습니다: " + e); }
            return false;
        }
    }

    public Handle spawn(String modelId, Location loc, double scale) {
        if (!available()) return null;
        try {
            Object entity = spawn.invoke(null, modelId, loc, true, false, true);
            if (entity == null) return null;
            Entity base = (Entity) getEntity.invoke(entity);
            GimmickWorld.prepareBase(base);
            var h = new IaHandle(entity, base, true);
            h.scale(scale);
            return h;
        } catch (ReflectiveOperationException | RuntimeException e) {
            log.warning("ItemsAdder 모델 소환 실패 " + modelId + ": " + e);
            return null;
        }
    }

    public Handle attach(String modelId, LivingEntity host, double scale) {
        if (!available()) return null;
        try {
            Object entity = convert.invoke(null, modelId, host, true, false, false, true);
            if (entity == null) return null;
            var h = new IaHandle(entity, host, false);
            if (scale != 1) h.scale(scale);
            return h;
        } catch (ReflectiveOperationException | RuntimeException e) {
            log.warning("ItemsAdder 모델 씌우기 실패 " + modelId + ": " + e);
            return null;
        }
    }

    public boolean hasModel(Entity host) {
        if (!available()) return false;
        try { return (boolean) isCustom.invoke(null, host); } catch (ReflectiveOperationException | RuntimeException e) { return false; }
    }

    private final class IaHandle implements Handle {
        final Object entity; final Entity base; final boolean owned;
        IaHandle(Object entity, Entity base, boolean owned) { this.entity = entity; this.base = base; this.owned = owned; }
        public Entity base() { return base; }
        public boolean play(String animation) {
            try { return Boolean.TRUE.equals(play.invoke(entity, animation)); } catch (ReflectiveOperationException | RuntimeException e) { return false; }
        }
        public void stop() { try { stop.invoke(entity); } catch (ReflectiveOperationException | RuntimeException ignored) {} }
        public void teleport(Location loc) {
            try { teleport.invoke(entity, loc); } catch (ReflectiveOperationException | RuntimeException e) { base.teleport(loc); }
        }
        public void scale(double scale) {
            if (base instanceof LivingEntity le) {
                var a = le.getAttribute(Attribute.SCALE);
                if (a != null) a.setBaseValue(Math.clamp(scale, 0.0625, 16));
            }
        }
        public void remove() {
            try { destroy.invoke(entity); } catch (ReflectiveOperationException | RuntimeException ignored) {}
            if (owned && base.isValid()) base.remove();
        }
    }
}
