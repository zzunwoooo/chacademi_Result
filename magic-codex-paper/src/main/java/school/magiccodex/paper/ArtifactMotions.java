package school.magiccodex.paper;

import io.papermc.paper.event.player.AsyncChatEvent;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.*;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.*;
import org.bukkit.entity.*;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.*;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;
import org.bukkit.util.Transformation;
import org.joml.Quaternionf;
import org.joml.Vector3f;

/**
 * 모션 아티팩트 9종 (2026-10-26, 오너 "꺼져 있던 아티팩트 전부 만들자"). 장착했을 때만 동작한다.
 * <ul>
 *   <li>dancing_ribbon 춤추는 리본: 맞을 때 10% 춤 (효과 없음)</li>
 *   <li>tap_dance_shoes 탭댄스 구두: 맞을 때 20% 1초 탭댄스, 그동안 받는 피해 -30%</li>
 *   <li>waving_glove 인사 장갑: 다른 플레이어를 바라보고 웅크리면 손 흔들기</li>
 *   <li>shy_mask 수줍은 가면: NPC(Citizens)를 우클릭해 말을 걸 때 고개 숙여 인사</li>
 *   <li>laughing_mask 웃음 가면: 채팅에 ㅋㅋ 를 치면 웃기</li>
 *   <li>sleepy_pillow 졸린 베개: 2분 가만히 있으면 앉아서 꾸벅꾸벅 (반복, 움직이면 끝)</li>
 *   <li>turtle_shell_backpack 거북이 등껍질 배낭: 웅크린 채 가만히 있으면 등껍질 속에 숨기 (반복 + 등에 거북 등딱지 ItemDisplay 하나, 움직이거나 일어서면 끝)</li>
 *   <li>stretch_headband 기지개 밴드: 처음 접속할 때 기지개 (학교↔야생 서버 이동 도착 때는 안 함)</li>
 *   <li>jack_in_the_box 깜짝 상자: 쓰러진 자리에 용수철 인형 VFX (PortableVFX claude:jackinthebox/pop)</li>
 * </ul>
 * 모션은 ChacademyMotion 플러그인 playMotion 을 리플렉션으로 부른다 (모드가 없는 유저에게는 아무것도 안 보임).
 * 반복 모션은 모드가 isLoop 로 계속 재생하고, 끝낼 때 서버가 예약 이름 "stop" 을 보낸다 (MotionPlayer.STOP).
 * 서버 부담: 0.5초마다 접속자 목록만 훑고(해당 아티팩트 장착자만 위치 비교), 나머지는 이벤트에서 맵 조회 한 번.
 */
final class ArtifactMotions implements Listener {
    static final String DANCE = "artifact_dance", WAVE = "artifact_wave", DOZE = "artifact_doze", BOW = "artifact_bow",
            LAUGH = "artifact_laugh", SHELL = "artifact_shell_hide", STRETCH = "artifact_stretch", TAP = "artifact_tap_dance";
    static final String STOP = "stop";
    /** 기지개 밴드: 접속 중 표시 (runtime r: 키라 서버 이동 때 따라온다). 이게 남아 있으면 서버 이동 도착으로 본다. */
    static final String ONLINE_KEY = "stretch_online";
    static final long DOZE_AFTER_MS = 120_000, SHELL_AFTER_MS = 1_200;
    /** 등껍질 표시 위치 (웅크린 몸 기준, 게임에서 보고 조정). */
    static final double SHELL_UP = 0.95, SHELL_BACK = 0.28;
    static final float SHELL_SCALE = 1.5f, SHELL_TILT_DEG = 35f;

    private final ArtifactEffects fx;
    private final MagicCodexBridge plugin;
    /** 장착자의 마지막 위치·시선과 그 뒤로 가만히 있기 시작한 시각. */
    private final Map<UUID, Still> still = new HashMap<>();
    /** 반복 모션 재생 중 (졸기 / 등껍질). */
    private final Map<UUID, Pose> posing = new HashMap<>();
    private final Map<UUID, Long> sneakAt = new HashMap<>();
    private final Map<UUID, Long> tapUntil = new HashMap<>();
    private final Set<String> warned = new HashSet<>();
    /** 기지개 밴드: 이번 접속의 도착 판단을 끝낸 유저. 그 전에는 접속 표시를 새로 쓰지 않는다 (이전 서버 표시를 읽어야 하므로). */
    private final Set<UUID> arrived = new HashSet<>();

    private static final class Still { World world; double x, y, z; float yaw, pitch; long since; }
    private record Pose(String motion, Location at, ItemDisplay shell) {}

    ArtifactMotions(ArtifactEffects fx) {
        this.fx = fx; this.plugin = fx.plugin;
        Bukkit.getPluginManager().registerEvents(this, plugin);
        Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 10, 10);
        for (Player p : Bukkit.getOnlinePlayers()) arrived.add(p.getUniqueId());   // 플러그인 재시작: 이미 접속 중인 유저는 기지개 없음
    }

    // ------------------------------------------------------------------ 0.5초마다: 가만히 있기 판정

    private void tick() {
        long now = System.currentTimeMillis();
        for (Player p : Bukkit.getOnlinePlayers()) {
            UUID id = p.getUniqueId();
            if (arrived.contains(id) && fx.has(p, "stretch_headband")) {
                var ready = fx.run(p).ready;
                if (ready.getOrDefault(ONLINE_KEY, 0L) < now + 30_000) ready.put(ONLINE_KEY, now + 45_000);
            }
            boolean pillow = fx.has(p, "sleepy_pillow"), turtle = fx.has(p, "turtle_shell_backpack");
            Pose pose = posing.get(id);
            if (!pillow && !turtle) { still.remove(id); if (pose != null) end(p); continue; }
            Location l = p.getLocation();
            Still s = still.get(id);
            boolean moved = s == null || s.world != l.getWorld() || sq(s.x - l.getX()) + sq(s.y - l.getY()) + sq(s.z - l.getZ()) > 0.0025;
            boolean looked = s != null && (Math.abs(s.pitch - l.getPitch()) > 15 || Math.abs(wrap(s.yaw - l.getYaw())) > 15);
            if (s == null) { s = new Still(); still.put(id, s); }
            if (moved || (looked && pose == null)) {
                s.world = l.getWorld(); s.x = l.getX(); s.y = l.getY(); s.z = l.getZ(); s.since = now;
            }
            s.yaw = l.getYaw(); s.pitch = l.getPitch();
            if (pose != null) {
                // 순간이동·밀림 등 PlayerMoveEvent 로 안 잡히는 이동, 일어섬, 아티팩트 해제
                boolean keep = calm(p) && pose.at().getWorld() == l.getWorld() && pose.at().distanceSquared(l) <= 0.01
                        && (pose.motion().equals(DOZE) ? pillow : turtle && p.isSneaking());
                if (!keep) end(p);
                continue;
            }
            if (!calm(p)) continue;
            long quiet = now - s.since;
            if (turtle && p.isSneaking() && quiet >= SHELL_AFTER_MS && now - sneakAt.getOrDefault(id, 0L) >= SHELL_AFTER_MS) begin(p, SHELL);
            else if (pillow && !p.isSneaking() && quiet >= DOZE_AFTER_MS) begin(p, DOZE);
        }
    }

    private static double sq(double v) { return v * v; }
    private static float wrap(float d) { d %= 360; if (d > 180) d -= 360; if (d < -180) d += 360; return d; }

    /** 앉거나 숨을 수 있는 상태: 땅 위에 서 있고 탈것·활공·수영·관전이 아님. */
    private static boolean calm(Player p) {
        return p.isOnline() && !p.isDead() && p.isOnGround() && !p.isInsideVehicle() && !p.isGliding() && !p.isSwimming()
                && !p.isFlying() && p.getGameMode() != GameMode.SPECTATOR;
    }

    private void begin(Player p, String motion) {
        if (!motion(p, motion)) return;
        Location at = p.getLocation();
        ItemDisplay shell = motion.equals(SHELL) ? spawnShell(p) : null;
        posing.put(p.getUniqueId(), new Pose(motion, at, shell));
        if (motion.equals(DOZE)) fx.bubble(p, "Zzz…", 60);
    }

    /** 반복 모션을 끝낸다 (모드에 stop, 등껍질 제거). 다시 2분을 세도록 정지 시각도 초기화. */
    private void end(Player p) {
        Pose pose = posing.remove(p.getUniqueId());
        if (pose == null) return;
        if (pose.shell() != null && pose.shell().isValid()) pose.shell().remove();
        var s = still.get(p.getUniqueId());
        if (s != null) s.since = System.currentTimeMillis();
        if (p.isOnline()) motion(p, STOP);
    }

    /** 웅크린 등 위에 거북 등딱지 (거북 등딱지 아이템을 등 기울기에 맞춰 눕힘). 저장 안 되는 엔티티 하나. */
    private ItemDisplay spawnShell(Player p) {
        float yaw = p.getLocation().getYaw();   // 가만히 웅크린 뒤라 몸 방향 ≈ 시선 방향
        double rad = Math.toRadians(yaw);
        Location at = p.getLocation().add(Math.sin(rad) * SHELL_BACK, SHELL_UP, -Math.cos(rad) * SHELL_BACK); // 뒤쪽 = 시선 반대
        at.setYaw(yaw); at.setPitch(0);
        try {
            return p.getWorld().spawn(at, ItemDisplay.class, d -> {
                d.setItemStack(new ItemStack(Material.TURTLE_HELMET));
                d.setItemDisplayTransform(ItemDisplay.ItemDisplayTransform.FIXED);
                d.setTransformation(new Transformation(new Vector3f(), new Quaternionf().rotateX((float) Math.toRadians(SHELL_TILT_DEG)),
                        new Vector3f(SHELL_SCALE, SHELL_SCALE, SHELL_SCALE), new Quaternionf()));
                d.setPersistent(false);
            });
        } catch (RuntimeException e) { warnOnce("shell", "거북이 등껍질 표시 실패: " + e); return null; }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void move(PlayerMoveEvent e) {
        if (posing.isEmpty()) return;
        Pose pose = posing.get(e.getPlayer().getUniqueId());
        if (pose == null) return;
        Location from = e.getFrom(), to = e.getTo();
        if (from.getWorld() != to.getWorld() || from.distanceSquared(to) > 0.0004) end(e.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void sneak(PlayerToggleSneakEvent e) {
        Player p = e.getPlayer();
        if (e.isSneaking()) {
            if (fx.has(p, "turtle_shell_backpack")) sneakAt.put(p.getUniqueId(), System.currentTimeMillis());
            if (fx.has(p, "waving_glove")) waveAt(p);
        } else {
            sneakAt.remove(p.getUniqueId());
            Pose pose = posing.get(p.getUniqueId());
            if (pose != null && pose.motion().equals(SHELL)) end(p);
        }
    }

    // ------------------------------------------------------------------ 인사 장갑

    /** 시선 12° 안, 16블록 안에 보이는 다른 플레이어가 있으면 손 흔들기. */
    private void waveAt(Player p) {
        Location eye = p.getEyeLocation();
        var look = eye.getDirection();
        double cos = Math.cos(Math.toRadians(12));
        for (Player o : p.getWorld().getPlayers()) {
            if (o == p || o.getGameMode() == GameMode.SPECTATOR || !p.canSee(o)) continue;
            var to = o.getLocation().add(0, o.getHeight() * 0.6, 0).toVector().subtract(eye.toVector());
            double d2 = to.lengthSquared();
            if (d2 > 256 || d2 < 0.25) continue;
            if (to.normalize().dot(look) < cos || !p.hasLineOfSight(o)) continue;
            if (fx.ready(p, "waving_glove", 3_000)) motion(p, WAVE);
            return;
        }
    }

    // ------------------------------------------------------------------ 수줍은 가면

    /**
     * NPC 대화 시작. ChacaNPC·Bridge 고정 대화 모두 Citizens NPC 우클릭에서 열리고(NpcManager → NpcSocialFacade.openStory /
     * DialogueBridge Citizens 훅), Citizens NPC 엔티티에는 "NPC" 메타데이터가 붙는다. Citizens 가 이벤트를 취소할 수 있어 취소 여부와 무관하게 본다.
     */
    @EventHandler(priority = EventPriority.MONITOR)
    public void npcClick(PlayerInteractEntityEvent e) {
        if (e.getHand() != EquipmentSlot.HAND || !e.getRightClicked().hasMetadata("NPC")) return;
        Player p = e.getPlayer();
        if (fx.has(p, "shy_mask") && fx.ready(p, "shy_mask", 8_000)) motion(p, BOW);
    }

    // ------------------------------------------------------------------ 웃음 가면

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void chat(AsyncChatEvent e) {
        String text = PlainTextComponentSerializer.plainText().serialize(e.message());
        if (!text.contains("ㅋㅋ")) return;
        Player p = e.getPlayer();
        // 비동기 채팅 스레드 → 장착 확인·재생은 메인 스레드에서
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (p.isOnline() && fx.has(p, "laughing_mask") && fx.ready(p, "laughing_mask", 3_000)) motion(p, LAUGH);
        });
    }

    // ------------------------------------------------------------------ 기지개 밴드

    @EventHandler
    public void join(PlayerJoinEvent e) { waitForState(e.getPlayer(), 0); }

    /** 공유 상태(장착·runtime)를 다 불러온 뒤 1.5초 있다가 판단. 최대 20초 기다림. */
    private void waitForState(Player p, int tries) {
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (!p.isOnline()) return;
            if (!plugin.playerStateReady(p)) { if (tries < 20) waitForState(p, tries + 1); else arrived.add(p.getUniqueId()); return; }
            Bukkit.getScheduler().runTaskLater(plugin, () -> stretchOnArrival(p), 30);
        }, 20);
    }

    private void stretchOnArrival(Player p) {
        if (!p.isOnline()) return;
        arrived.add(p.getUniqueId());
        if (!fx.has(p, "stretch_headband")) return;
        long now = System.currentTimeMillis();
        var ready = fx.run(p).ready;
        boolean switched = ready.getOrDefault(ONLINE_KEY, 0L) > now;   // 45초 안에 다른 서버(또는 이 서버)에 있었음
        ready.put(ONLINE_KEY, now + 45_000);
        if (!switched && fx.ready(p, "stretch_headband", 10_000)) motion(p, STRETCH);
    }

    // ------------------------------------------------------------------ 맞을 때: 춤추는 리본 / 탭댄스 구두

    /** 탭댄스 중 받는 피해 -30% (ArtifactEffects.hurt 와 같은 단계, 곱연산이라 순서 무관). */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void hurt(EntityDamageEvent e) {
        if (tapUntil.isEmpty() || !(e.getEntity() instanceof Player p)) return;
        Long until = tapUntil.get(p.getUniqueId());
        if (until == null) return;
        if (until < System.currentTimeMillis()) { tapUntil.remove(p.getUniqueId()); return; }
        e.setDamage(e.getDamage() * 0.7);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void hurtAfter(EntityDamageEvent e) {
        if (!(e.getEntity() instanceof Player p) || !fx.wearsAny(p)) return;
        Pose pose = posing.get(p.getUniqueId());
        if (pose != null && pose.motion().equals(DOZE)) end(p);            // 맞으면 잠이 깸
        if (e.getFinalDamage() <= 0 || p.getHealth() - e.getFinalDamage() <= 0) return;
        long now = System.currentTimeMillis();
        if (fx.has(p, "tap_dance_shoes") && tapUntil.getOrDefault(p.getUniqueId(), 0L) < now
                && fx.random.nextDouble() < 0.20 && fx.ready(p, "tap_dance_shoes", 1_500)) {
            tapUntil.put(p.getUniqueId(), now + 1_000);
            motion(p, TAP);
            p.getWorld().playSound(p.getLocation(), Sound.BLOCK_NOTE_BLOCK_HAT, 0.8f, 1.6f);
            Bukkit.getScheduler().runTaskLater(plugin, () -> { if (p.isOnline()) p.getWorld().playSound(p.getLocation(), Sound.BLOCK_NOTE_BLOCK_HAT, 0.8f, 1.9f); }, 5);
            ArtifactEffects.notice(p, "tap_dance_shoes", "탭댄스 구두", "1초간 받는 피해 -30%");
        }
        if (fx.has(p, "dancing_ribbon") && fx.random.nextDouble() < 0.10 && fx.ready(p, "dancing_ribbon", 3_000)) motion(p, DANCE);
    }

    // ------------------------------------------------------------------ 깜짝 상자

    @EventHandler(priority = EventPriority.MONITOR)
    public void died(PlayerDeathEvent e) {
        Player p = e.getEntity();
        Pose pose = posing.remove(p.getUniqueId());
        if (pose != null && pose.shell() != null && pose.shell().isValid()) pose.shell().remove();
        if (!fx.has(p, "jack_in_the_box") || !fx.ready(p, "jack_in_the_box", 5_000)) return;
        Location at = p.getLocation();
        if (at.getWorld() == null) return;
        vfx("jackinthebox", "pop", at, 52);
        World w = at.getWorld();
        Bukkit.getScheduler().runTaskLater(plugin, () -> {          // 뚜껑이 열리는 0.25초
            w.playSound(at, Sound.ENTITY_CHICKEN_EGG, 1f, 1.5f);
            w.playSound(at, Sound.ENTITY_SLIME_JUMP, 0.9f, 1.7f);
        }, 5);
        Bukkit.getScheduler().runTaskLater(plugin, () -> w.playSound(at, Sound.ENTITY_ITEM_PICKUP, 0.8f, 0.6f), 36);   // 펑
    }

    @EventHandler
    public void quit(PlayerQuitEvent e) {
        UUID id = e.getPlayer().getUniqueId();
        Pose pose = posing.remove(id);
        if (pose != null && pose.shell() != null && pose.shell().isValid()) pose.shell().remove();
        still.remove(id); sneakAt.remove(id); tapUntil.remove(id); arrived.remove(id);
    }

    void close() {
        for (Pose pose : posing.values()) if (pose.shell() != null && pose.shell().isValid()) pose.shell().remove();
        posing.clear();
    }

    // ------------------------------------------------------------------ ChacademyMotion (리플렉션)

    private Plugin motionOwner; private Method playMotion;

    /** 본인 화면 포함 주변에 모션 재생. 플러그인이 없거나 실패하면 false. */
    private boolean motion(Player p, String name) {
        Plugin m = Bukkit.getPluginManager().getPlugin("ChacademyMotion");
        if (m == null || !m.isEnabled()) return false;
        try {
            if (m != motionOwner) { playMotion = m.getClass().getMethod("playMotion", Player.class, String.class, boolean.class); motionOwner = m; }
            playMotion.invoke(m, p, name, true);
            return true;
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            Throwable c = e instanceof InvocationTargetException i && i.getCause() != null ? i.getCause() : e;
            warnOnce("motion", "아티팩트 모션 재생 실패 (ChacademyMotion 버전 확인): " + c);
            return false;
        }
    }

    // ------------------------------------------------------------------ PortableVFX (리플렉션, kyudichi Hooks 와 같은 방식)

    private Plugin vfxOwner; private Object vfxService; private Method vfxPlay; private Constructor<?> vfxRequest;

    private void vfx(String pack, String system, Location at, int ticks) {
        Plugin pv = Bukkit.getPluginManager().getPlugin("PortableVFX");
        if (pv == null || !pv.isEnabled() || at.getWorld() == null) return;
        try {
            if (pv != vfxOwner) {
                ClassLoader cl = pv.getClass().getClassLoader();
                Class<?> svc = Class.forName("dev.portablevfx.paper.api.PortableVfxService", true, cl);
                Class<?> req = Class.forName("dev.portablevfx.paper.api.EffectRequest", true, cl);
                var reg = Bukkit.getServicesManager().getRegistration(svc);
                if (reg == null) return;
                vfxRequest = req.getConstructor(String.class, String.class, double.class, double.class, double.class,
                        float.class, int.class, float.class, float.class, float.class, int.class, float.class, double.class);
                vfxPlay = svc.getMethod("play", req);
                vfxService = reg.getProvider();
                vfxOwner = pv;
            }
            Object r = vfxRequest.newInstance("claude:" + pack + "/" + system, at.getWorld().getName(), at.getX(), at.getY(), at.getZ(),
                    1f, ticks, 0f, 0f, 0f, 0xFFFFFF, 1f, 64.0);
            vfxPlay.invoke(vfxService, r);
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            Throwable c = e instanceof InvocationTargetException i && i.getCause() != null ? i.getCause() : e;
            if (!(c instanceof java.util.concurrent.RejectedExecutionException)) warnOnce("vfx", "깜짝 상자 VFX 재생 실패 (" + pack + "/" + system + "): " + c);
        }
    }

    private void warnOnce(String key, String message) { if (warned.add(key)) plugin.getLogger().warning(message); }
}
