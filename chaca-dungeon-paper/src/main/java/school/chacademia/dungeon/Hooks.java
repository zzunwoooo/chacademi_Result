package school.chacademia.dungeon;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.logging.Logger;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;

/**
 * 다른 플러그인 연결 (전부 리플렉션: 배포 jar 를 바꾸지 않는다, 없으면 조용히 대체 동작).
 * <ul>
 *   <li>보상 전달: MagicCodexBridge RewardDelivery.give — 가방 먼저, 넘치면 우편 (source 키로 한 번만).</li>
 *   <li>닉네임: MagicCodexBridge.nicknameOf(OfflinePlayer).</li>
 *   <li>기믹: ChacaGimmick 의 설치 목록·열림 상태·초기화 (/던전관리 초기화 와 같은 동작을 칸 구역에만).</li>
 * </ul>
 */
final class Hooks {
    private final Logger log;
    Hooks(Logger log) { this.log = log; }

    // ------------------------------------------------------------ 보상

    /** 결과: INVENTORY(가방) · MAIL(일부/전부 우편) · DROPPED(발밑) · NONE(못 줌). 메인 스레드. */
    String give(UUID owner, List<ItemStack> items, String source, String title, String body) {
        if (items.isEmpty()) return "INVENTORY";
        Plugin bridge = Bukkit.getPluginManager().getPlugin("MagicCodexBridge");
        if (bridge != null && bridge.isEnabled()) {
            try {
                Class<?> type = Class.forName("school.magiccodex.paper.RewardDelivery", true, bridge.getClass().getClassLoader());
                Method get = type.getDeclaredMethod("get");
                get.setAccessible(true);
                Object delivery = get.invoke(null);
                if (delivery != null) {
                    Method give = type.getDeclaredMethod("give", UUID.class, List.class, String.class, String.class, String.class, Consumer.class);
                    give.setAccessible(true);
                    Consumer<Boolean> mailed = ok -> { if (!ok) log.warning("보상 우편 저장 실패 player=" + owner + " source=" + source); };
                    return String.valueOf(give.invoke(delivery, owner, items, source, title, body, mailed));
                }
            } catch (ReflectiveOperationException | LinkageError | RuntimeException e) {
                log.warning("보상 전달(RewardDelivery) 연결 실패, 직접 지급으로 대신함: " + e);
            }
        }
        // 대체: 가방에 넣고 남는 것은 발밑 (우편이 없을 때만)
        Player p = Bukkit.getPlayer(owner);
        if (p == null) { log.severe("보상 지급 실패 (오프라인·보상 전달 없음) player=" + owner + " source=" + source + " items=" + items); return "NONE"; }
        var left = p.getInventory().addItem(items.stream().map(ItemStack::clone).toArray(ItemStack[]::new));
        left.values().forEach(i -> p.getWorld().dropItemNaturally(p.getLocation(), i));
        return left.isEmpty() ? "INVENTORY" : "DROPPED";
    }

    String nickname(UUID id) {
        var offline = Bukkit.getOfflinePlayer(id);
        String account = offline.getName() == null ? "" : offline.getName();
        Plugin bridge = Bukkit.getPluginManager().getPlugin("MagicCodexBridge");
        if (bridge == null || !bridge.isEnabled()) return account;
        try {
            Object n = bridge.getClass().getMethod("nicknameOf", org.bukkit.OfflinePlayer.class).invoke(bridge, offline);
            return n instanceof String s && !s.isBlank() ? s : account;
        } catch (ReflectiveOperationException | LinkageError | RuntimeException e) { return account; }
    }

    // ------------------------------------------------------------ 기믹

    private Plugin gimmickPlugin() {
        Plugin p = Bukkit.getPluginManager().getPlugin("ChacaGimmick");
        return p != null && p.isEnabled() ? p : null;
    }

    private Method worldMethod, signalsMethod, allMethod, resetMethod, attachedMethod;
    private Field fWorld, fBx, fBy, fBz, fLatched, fModel;

    private boolean resolveGimmick(Plugin plugin) throws ReflectiveOperationException {
        if (worldMethod != null) return true;
        Class<?> pc = plugin.getClass();
        worldMethod = access(pc.getDeclaredMethod("world"));
        signalsMethod = access(pc.getDeclaredMethod("signals"));
        ClassLoader cl = pc.getClassLoader();
        Class<?> gw = Class.forName("school.chacademia.gimmick.GimmickWorld", true, cl);
        Class<?> g = Class.forName("school.chacademia.gimmick.Gimmick", true, cl);
        Class<?> sig = Class.forName("school.chacademia.gimmick.Signals", true, cl);
        allMethod = access(gw.getDeclaredMethod("all"));
        resetMethod = access(sig.getDeclaredMethod("reset", g));
        attachedMethod = access(g.getDeclaredMethod("attached"));
        fWorld = access(g.getDeclaredField("world")); fModel = access(g.getDeclaredField("model"));
        fBx = access(g.getDeclaredField("bx")); fBy = access(g.getDeclaredField("by")); fBz = access(g.getDeclaredField("bz"));
        fLatched = access(g.getDeclaredField("latched"));
        return true;
    }

    private static <T extends java.lang.reflect.AccessibleObject> T access(T member) { member.setAccessible(true); return member; }

    private Collection<?> gimmicks(Plugin plugin) throws ReflectiveOperationException {
        resolveGimmick(plugin);
        return (Collection<?>) allMethod.invoke(worldMethod.invoke(plugin));
    }

    /** 구역 안 기믹을 처음 상태로. 돌려준 수 = 초기화한 기믹 수. 기믹 플러그인이 없으면 0. 실패하면 예외. */
    int resetGimmicks(World world, Defs.Box box) throws ReflectiveOperationException {
        Plugin plugin = gimmickPlugin();
        if (plugin == null) return 0;
        Object signals = signalsMethod == null ? null : signalsMethod.invoke(plugin);
        int n = 0;
        for (Object g : new ArrayList<>(gimmicks(plugin))) {
            if ((boolean) attachedMethod.invoke(g) || !world.getName().equals(fWorld.get(g))) continue;
            if (!box.contains(fBx.getInt(g) + .5, fBy.getInt(g) + .5, fBz.getInt(g) + .5)) continue;
            if (signals == null) signals = signalsMethod.invoke(plugin);
            resetMethod.invoke(signals, g);
            n++;
        }
        return n;
    }

    /** 이 블록에 앵커가 있는 기믹이 열려(켜져) 있는지. 없으면 false. */
    boolean gimmickOpen(String world, int[] block) {
        Plugin plugin = gimmickPlugin();
        if (plugin == null || block == null) return false;
        try {
            for (Object g : gimmicks(plugin))
                if (world.equals(fWorld.get(g)) && fBx.getInt(g) == block[0] && fBy.getInt(g) == block[1] && fBz.getInt(g) == block[2])
                    return fLatched.getBoolean(g);
        } catch (ReflectiveOperationException | RuntimeException e) {
            log.warning("기믹 상태 확인 실패: " + e);
        }
        return false;
    }

    /** 이 위치에서 가장 가까운 기믹 (반경 안): {앵커 x,y,z} 와 모델 이름. 없으면 null. */
    record Found(int[] block, String model, double distance) {}

    Found nearestGimmick(Location at, double radius) {
        Plugin plugin = gimmickPlugin();
        if (plugin == null) return null;
        Found best = null;
        try {
            for (Object g : gimmicks(plugin)) {
                if ((boolean) attachedMethod.invoke(g) || !at.getWorld().getName().equals(fWorld.get(g))) continue;
                int[] b = {fBx.getInt(g), fBy.getInt(g), fBz.getInt(g)};
                double d = at.distance(new Location(at.getWorld(), b[0] + .5, b[1] + .5, b[2] + .5));
                if (d <= radius && (best == null || d < best.distance())) best = new Found(b, String.valueOf(fModel.get(g)), d);
            }
        } catch (ReflectiveOperationException | RuntimeException e) {
            log.warning("기믹 찾기 실패: " + e);
        }
        return best;
    }

    // ------------------------------------------------------------ 몹 (MythicMobs 가 없으면 바닐라 이름만)

    boolean mythicReady() { return Bukkit.getPluginManager().isPluginEnabled("MythicMobs"); }

    /** mob = MythicMobs 내부 이름, 또는 minecraft:zombie 같은 바닐라 이름. 실패하면 null. */
    Entity spawn(String mob, Location at) {
        if (mob.startsWith("minecraft:")) {
            try {
                var type = org.bukkit.entity.EntityType.valueOf(mob.substring(10).toUpperCase(java.util.Locale.ROOT));
                return at.getWorld().spawnEntity(at, type);
            } catch (IllegalArgumentException e) { log.warning("모르는 바닐라 몹: " + mob); return null; }
        }
        if (!mythicReady()) { log.warning("MythicMobs 가 없어 소환 못 함: " + mob); return null; }
        return Mythic.spawn(mob, at, log);
    }

    /** 죽는(아직 살아 있는) 엔티티의 몹 이름: MythicMobs 내부 이름, 아니면 minecraft:타입. */
    String mobName(Entity e) {
        if (mythicReady()) {
            String n = Mythic.name(e);
            if (n != null) return n;
        }
        return e.getType().getKey().toString();
    }

    /** MythicMobs API 는 이 안에서만 (플러그인이 없으면 이 클래스를 안 불러온다). */
    private static final class Mythic {
        static Entity spawn(String mob, Location at, Logger log) {
            try {
                var active = io.lumine.mythic.bukkit.MythicBukkit.inst().getMobManager().spawnMob(mob, at);
                return active == null ? null : active.getEntity().getBukkitEntity();
            } catch (RuntimeException | LinkageError e) { log.warning("MythicMobs 소환 실패 " + mob + ": " + e); return null; }
        }
        static String name(Entity e) {
            try {
                return io.lumine.mythic.bukkit.MythicBukkit.inst().getMobManager().getActiveMob(e.getUniqueId())
                        .map(m -> m.getType().getInternalName()).orElse(null);
            } catch (RuntimeException | LinkageError ex) { return null; }
        }
    }
}
