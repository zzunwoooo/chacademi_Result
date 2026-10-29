package school.chacademia.dungeon;

import io.lumine.mythic.api.adapters.AbstractEntity;
import io.lumine.mythic.api.adapters.AbstractLocation;
import io.lumine.mythic.api.config.MythicLineConfig;
import io.lumine.mythic.api.skills.ITargetedEntitySkill;
import io.lumine.mythic.api.skills.ITargetedLocationSkill;
import io.lumine.mythic.api.skills.SkillMetadata;
import io.lumine.mythic.api.skills.SkillResult;
import io.lumine.mythic.api.skills.ThreadSafetyLevel;
import io.lumine.mythic.bukkit.BukkitAdapter;
import io.lumine.mythic.bukkit.events.MythicMechanicLoadEvent;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Locale;
import java.util.logging.Logger;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.plugin.Plugin;

/**
 * MythicMobs 메커닉 chavfx — 빨간 예고 없이 PortableVFX 효과만 재생 (보스 모르바스: 영혼탄 명중·무덤·폭풍·병사 베기·페이즈 전환).
 * <pre>chavfx{id=ArchlichMorvath/grave;r=1.2;ticks=60;tint=FFFFFF} @origin|@self|@target</pre>
 * id 는 chatelegraph vfx= 와 같은 규칙 (소문자, "claude:" 를 붙임). r 이 있으면 팩의 areaRadius 기준으로 그 반경(m)에 맞춰 키우고,
 * 없으면 만든 크기 그대로. 방향 = 시전 몹이 보는 방향. 효과만 보여 줄 뿐 판정은 없다.
 * MagicMythic 의 ReactionVfx 와 같은 PortableVFX API(리플렉션)를 쓴다 — 배포된 MagicMythic jar 를 바꾸지 않으려고 여기 둔다.
 * MythicMobs 가 없으면 이 클래스는 불러오지 않는다 (DungeonPlugin 이 확인 후 등록).
 */
final class VfxMechanic implements Listener {
    private static final String SERVICE = "dev.portablevfx.paper.api.PortableVfxService";
    private static final String REQUEST = "dev.portablevfx.paper.api.EffectRequest";
    private final Logger log;
    private boolean resolved, warned;
    private Object service;
    private Method play, scaledPlay, effectBasis;
    private Constructor<?> request;

    VfxMechanic(Logger log) { this.log = log; }

    @EventHandler public void load(MythicMechanicLoadEvent event) {
        if (event.getMechanicName().equalsIgnoreCase("chavfx")) event.register(new Mechanic(event.getConfig()));
    }

    private final class Mechanic implements ITargetedEntitySkill, ITargetedLocationSkill {
        private final String id;
        private final double radius;
        private final int ticks, tint;

        Mechanic(MythicLineConfig config) {
            id = vfxId(config.getString(new String[]{"id", "vfx"}, ""));
            radius = config.getDouble(new String[]{"r", "radius"}, 0);
            ticks = Math.clamp(config.getInteger(new String[]{"ticks", "t"}, 60), 1, 400);
            int t;
            try { t = Integer.parseInt(config.getString("tint", "FFFFFF").replace("#", ""), 16) & 0xFFFFFF; } catch (RuntimeException e) { t = 0xFFFFFF; }
            tint = t;
        }

        @Override public ThreadSafetyLevel getThreadSafetyLevel() { return ThreadSafetyLevel.SYNC_ONLY; }

        @Override public SkillResult castAtEntity(SkillMetadata data, AbstractEntity target) {
            if (target == null) return SkillResult.INVALID_TARGET;
            return play(data, target.getBukkitEntity().getLocation());
        }

        @Override public SkillResult castAtLocation(SkillMetadata data, AbstractLocation location) {
            if (location == null) return SkillResult.INVALID_TARGET;
            return play(data, BukkitAdapter.adapt(location));
        }

        private SkillResult play(SkillMetadata data, Location at) {
            if (id.isEmpty() || at.getWorld() == null) return SkillResult.INVALID_TARGET;
            float yaw = 0;
            try { yaw = data.getCaster().getEntity().getBukkitEntity().getLocation().getYaw(); } catch (RuntimeException ignored) { }
            effect(id, at, radius, yaw, ticks, tint);
            return SkillResult.SUCCESS;
        }
    }

    static String vfxId(String raw) {
        if (raw == null || raw.isBlank()) return "";
        String id = raw.trim().toLowerCase(Locale.ROOT);
        if (!id.contains(":")) id = "claude:" + id;
        return id.matches("[a-z0-9_.-]+:[a-z0-9_./-]+") ? id : "";
    }

    /** radius &gt; 0: 팩의 물리 크기 입력(areaRadius m)으로 재생, 0: 만든 크기(변환 1). PortableVFX 가 없거나 한도를 넘으면 조용히 건너뜀. */
    void effect(String id, Location at, double radius, float yaw, int ticks, int tint) {
        if (!resolve()) return;
        try {
            Object req = request.newInstance(id, at.getWorld().getName(), at.getX(), at.getY(), at.getZ(), 1f, ticks, yaw, 0f, 0f, tint, 1f, 64d);
            if (radius > 0) {
                double angle = Math.toRadians(yaw);
                Object basis = effectBasis.invoke(null, -Math.sin(angle), 0d, Math.cos(angle));
                scaledPlay.invoke(service, req, basis, java.util.UUID.randomUUID().getLeastSignificantBits(), 0d, radius, -1d);
            } else play.invoke(service, req);
        } catch (InvocationTargetException e) {
            if (!(e.getCause() instanceof java.util.concurrent.RejectedExecutionException)) warn(e.getCause());
        } catch (ReflectiveOperationException | RuntimeException e) {
            warn(e);
        }
    }

    private boolean resolve() {
        if (resolved) return service != null;
        resolved = true;
        Plugin vfx = Bukkit.getPluginManager().getPlugin("PortableVFX");
        if (vfx == null || !vfx.isEnabled()) return false;
        try {
            ClassLoader loader = vfx.getClass().getClassLoader();
            Class<?> serviceType = Class.forName(SERVICE, true, loader);
            Class<?> requestType = Class.forName(REQUEST, true, loader);
            var reg = Bukkit.getServicesManager().getRegistration(serviceType);
            if (reg == null) return false;
            request = requestType.getConstructor(String.class, String.class, double.class, double.class, double.class,
                    float.class, int.class, float.class, float.class, float.class, int.class, float.class, double.class);
            play = serviceType.getMethod("play", requestType);
            Class<?> frame = Class.forName("dev.portablevfx.protocol.EffectBasis", true, loader);
            effectBasis = frame.getMethod("projectile", double.class, double.class, double.class);
            scaledPlay = serviceType.getMethod("startCast", requestType, frame, long.class, double.class, double.class, double.class);
            service = reg.getProvider();
            return true;
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            warn(e);
            service = null;
            return false;
        }
    }

    private void warn(Throwable e) {
        if (warned) return;
        warned = true;
        log.warning("chavfx 재생 실패 (이후 같은 경고는 생략): " + e.getClass().getSimpleName() + " " + e.getMessage());
    }
}
