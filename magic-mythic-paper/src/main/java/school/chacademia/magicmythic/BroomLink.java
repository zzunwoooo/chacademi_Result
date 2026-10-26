package school.chacademia.magicmythic;

import java.lang.reflect.Method;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

/**
 * 빗자루 비행(ChacademyMotion 플러그인) 연결. 컴파일 의존 없이 리플렉션으로 부른다.
 * ChacademyMotion 이 없거나 꺼져 있으면 모든 메서드가 "빗자루 없음"으로 동작하고,
 * 비행 마법은 예전처럼 바닐라 비행(10초)으로 처리된다.
 *
 * ChacademyMotionPlugin 쪽 공개 메서드:
 *   boolean broomFlight(Player, int practiceTicks)  - 타고 있으면 내리고, 아니면 탄다. 모드가 없으면 false
 *   boolean isRidingBroom(Player)
 *   void stopBroom(Player)
 *   double[] broomVelocity(Player)                   - 비행 속도 {x,y,z} 블록/틱, 안 타면 null (0.3.0-codex.3 이후)
 *   boolean broomSpeedBuff(Player, float, int, boolean)   - 최고 속도 × mult (ticks), 궤적 숨김 (…-nova.1 이후, 마법 375)
 *   boolean broomBalanceBuff(Player, float, int, boolean) - 균형 감소 × mult (ticks), 균형 가득 (…-nova.1 이후, 마법 380)
 */
final class BroomLink {
    private static Plugin cachedPlugin;
    private static Method flight, riding, stop, velocity, speedBuff, balanceBuff;

    private BroomLink() {}

    private static Plugin plugin() {
        Plugin p = Bukkit.getPluginManager().getPlugin("ChacademyMotion");
        if (p == null || !p.isEnabled()) return null;
        if (p != cachedPlugin) {
            try {
                flight = p.getClass().getMethod("broomFlight", Player.class, int.class);
                riding = p.getClass().getMethod("isRidingBroom", Player.class);
                stop = p.getClass().getMethod("stopBroom", Player.class);
                try {
                    velocity = p.getClass().getMethod("broomVelocity", Player.class);
                } catch (NoSuchMethodException older) {
                    velocity = null; // 예전 ChacademyMotion: 투사체 보정만 꺼짐
                }
                speedBuff = optional(p, "broomSpeedBuff", Player.class, float.class, int.class, boolean.class);
                balanceBuff = optional(p, "broomBalanceBuff", Player.class, float.class, int.class, boolean.class);
                cachedPlugin = p;
            } catch (ReflectiveOperationException | LinkageError e) {
                cachedPlugin = null;
                return null;
            }
        }
        return p;
    }

    /** 비행 마법. true = 빗자루로 처리함(탔거나 내렸음), false = 빗자루 비행 불가(모드 없음 등) → 예전 비행으로 */
    static Boolean flight(Player player, int practiceTicks) {
        Plugin p = plugin();
        if (p == null || !player.getListeningPluginChannels().contains("chacademy:broom_ride")) return null;
        try {
            return Boolean.TRUE.equals(flight.invoke(p, player, practiceTicks));
        } catch (ReflectiveOperationException | RuntimeException e) {
            return false;
        }
    }

    static boolean riding(Player player) {
        Plugin p = plugin();
        if (p == null) return false;
        try {
            return Boolean.TRUE.equals(riding.invoke(p, player));
        } catch (ReflectiveOperationException | RuntimeException e) {
            return false;
        }
    }

    static void stop(Player player) {
        Plugin p = plugin();
        if (p == null) return;
        try {
            stop.invoke(p, player);
        } catch (ReflectiveOperationException | RuntimeException ignored) {
        }
    }

    /** 빗자루 비행 속도 (블록/틱). 타고 있지 않거나 ChacademyMotion 이 없으면 null. */
    static org.bukkit.util.Vector velocity(Player player) {
        Plugin p = plugin();
        if (p == null || velocity == null) return null;
        try {
            if (velocity.invoke(p, player) instanceof double[] v && v.length == 3) return new org.bukkit.util.Vector(v[0], v[1], v[2]);
        } catch (ReflectiveOperationException | RuntimeException ignored) {
        }
        return null;
    }

    private static Method optional(Plugin p, String name, Class<?>... types) {
        try {
            return p.getClass().getMethod(name, types);
        } catch (NoSuchMethodException older) {
            return null; // 예전 ChacademyMotion: 이 기능만 꺼짐
        }
    }

    /**
     * 시간제 최고 속도 버프 (마법 375 불꽃 추진): ticks 동안 최고 속도 × mult, hideTrail 이면 빗자루 궤적을 숨김.
     * true = 적용됨. 타고 있지 않음·모드/플러그인이 오래됨 → false.
     */
    static boolean speedBuff(Player player, float mult, int ticks, boolean hideTrail) {
        Plugin p = plugin();
        if (p == null || speedBuff == null) return false;
        try {
            return Boolean.TRUE.equals(speedBuff.invoke(p, player, mult, ticks, hideTrail));
        } catch (ReflectiveOperationException | RuntimeException e) {
            return false;
        }
    }

    /** 시간제 균형 버프 (마법 380 순풍): refill 이면 균형 가득, ticks 동안 균형 감소 × drainMult. true = 적용됨. */
    static boolean balanceBuff(Player player, float drainMult, int ticks, boolean refill) {
        Plugin p = plugin();
        if (p == null || balanceBuff == null) return false;
        try {
            return Boolean.TRUE.equals(balanceBuff.invoke(p, player, drainMult, ticks, refill));
        } catch (ReflectiveOperationException | RuntimeException e) {
            return false;
        }
    }
}
