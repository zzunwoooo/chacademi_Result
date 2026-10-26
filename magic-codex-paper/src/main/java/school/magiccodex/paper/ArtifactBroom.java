package school.magiccodex.paper;

import java.lang.reflect.Method;
import java.util.*;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

/**
 * 빗자루 아티팩트 (2026-10-26, 보류 29종 중 그룹 B): 빗자루 끈 · 빗자루 쿠션 · 바람개비 장식 · 별의 잔향 · 폭풍 날개.
 *
 * 빗자루 물리는 클라 모드(chacademy-motion)가 계산한다. 여기서는 장착 상태를 비트 묶음으로 만들어
 * ChacademyMotion 플러그인 {@code setBroomPerks(Player,int)} 에 리플렉션으로 알려 줄 뿐이다 (BroomCastGuard 와 같은 방식).
 * 플러그인이 탈 때 모드에 broom_perk 패킷을 보내고, 폭풍 날개 최고 속도·빗자루 쿠션 낙하 피해는 플러그인이 직접 처리한다.
 * 장착을 바꾸면 다음 탑승부터 확실히 적용된다 (모드 쪽 효과는 타는 중에도 바로).
 *
 * 폭풍 날개의 "비행 중 스킬 가속 +20" 은 여기서 1초마다 타는 중인지 보고 timed 버프(1.6초)로 준다.
 * 1초에 한 번 · 장착한 사람만 · 바뀐 경우(또는 30초마다 다시)만 리플렉션 호출 → 서버 부담 없음.
 */
final class ArtifactBroom {
    // ChacademyMotion BroomCodec.PERK_* 와 같은 값
    static final int PERK_CORD = 1, PERK_CUSHION = 1 << 1, PERK_PINWHEEL = 1 << 2, PERK_STAR_ECHO = 1 << 3, PERK_STORM = 1 << 4;
    static final long RESEND_MS = 30_000;
    static final double STORM_HASTE = 20;

    private final ArtifactEffects fx;
    private record Sent(int mask, long at) {}
    private final Map<UUID, Sent> sent = new HashMap<>();
    private Plugin motion;
    private Method setPerks, riding;
    private boolean warned;

    ArtifactBroom(ArtifactEffects fx) { this.fx = fx; }

    static int mask(java.util.function.Predicate<String> has) {
        int m = 0;
        if (has.test("broom_cord")) m |= PERK_CORD;
        if (has.test("broom_cushion")) m |= PERK_CUSHION;
        if (has.test("pinwheel_charm")) m |= PERK_PINWHEEL;
        if (has.test("star_echo")) m |= PERK_STAR_ECHO;
        if (has.test("storm_wings")) m |= PERK_STORM;
        return m;
    }

    /** 1초마다 (ArtifactEffects.tick, apply 전). */
    void second(Player p, long now) {
        int m = mask(id -> fx.has(p, id));
        Sent last = sent.get(p.getUniqueId());
        if (last == null ? m != 0 : last.mask() != m || (m != 0 && now - last.at() >= RESEND_MS)) {
            // 플러그인이 다시 켜지면 기억이 지워지므로 30초마다 다시 알려 둔다
            if (push(p, m)) sent.put(p.getUniqueId(), new Sent(m, now));
        }
        if ((m & PERK_STORM) != 0 && riding(p)) fx.run(p).timed.put("storm_wings", new ArtifactEffects.Timed(now + 1_600, 0, STORM_HASTE));
    }

    private boolean connect() {
        var plugin = Bukkit.getPluginManager().getPlugin("ChacademyMotion");
        if (plugin == null || !plugin.isEnabled()) { motion = null; setPerks = null; riding = null; return false; }
        if (plugin == motion) return setPerks != null;
        motion = plugin;
        try { riding = plugin.getClass().getMethod("isRidingBroom", Player.class); } catch (ReflectiveOperationException | LinkageError e) { riding = null; }
        try { setPerks = plugin.getClass().getMethod("setBroomPerks", Player.class, int.class); }
        catch (ReflectiveOperationException | LinkageError e) {
            setPerks = null;
            if (!warned) { warned = true; fx.plugin.getLogger().warning("빗자루 아티팩트 연결 안 됨 (ChacademyMotion 에 setBroomPerks 없음): 플러그인을 새 버전으로 배포하세요"); }
        }
        return setPerks != null;
    }

    private boolean push(Player p, int mask) {
        if (!connect()) return false;
        try { setPerks.invoke(motion, p, mask); return true; }
        catch (ReflectiveOperationException | RuntimeException e) { fx.plugin.getLogger().warning("빗자루 아티팩트 전달 실패: " + e); return false; }
    }

    boolean riding(Player p) {
        connect();
        if (riding == null || motion == null) return false;
        try { return Boolean.TRUE.equals(riding.invoke(motion, p)); }
        catch (ReflectiveOperationException | RuntimeException e) { return false; }
    }

    void quit(UUID id) { sent.remove(id); }
}
