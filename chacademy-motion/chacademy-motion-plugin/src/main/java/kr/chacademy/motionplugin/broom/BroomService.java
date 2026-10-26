package kr.chacademy.motionplugin.broom;

import io.papermc.paper.event.player.PlayerTrackEntityEvent;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerGameModeChangeEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRegisterChannelEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.plugin.messaging.PluginMessageListener;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitTask;
import org.jetbrains.annotations.NotNull;

import java.io.File;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 빗자루 비행 (서버 쪽).
 *
 * <p>움직임·균형·낙마 판정은 전부 모드(플레이어 PC)가 한다. 서버는
 * <ol>
 *   <li>탈 수 있는지 확인하고 조작 수치를 한 번 보낸다 ({@link BroomCodec#CHANNEL_RIDE})</li>
 *   <li>주변 유저에게 "누가 어떤 빗자루를 탔다"를 한 번 알린다 ({@link BroomCodec#CHANNEL_STATE})</li>
 *   <li>탄 사람만 틱마다 가볍게 검사한다: 낙하 거리 초기화, 속도 상한, 시간 제한, 숙련도 기록</li>
 * </ol>
 * 탄 사람이 없으면 틱 작업도 돌지 않는다.
 */
public final class BroomService implements Listener, PluginMessageListener {

    private final JavaPlugin plugin;
    private final BroomStore store;
    private final Map<String, BroomType> brooms = new LinkedHashMap<>();
    private final Map<UUID, Session> sessions = new HashMap<>();
    private final Map<UUID, PendingCatch> catches = new HashMap<>();
    private final Map<UUID, Long> lastRoll = new HashMap<>();
    private BukkitTask task;
    private long tick;

    // brooms.yml 설정
    private String practiceBroom = "broom_1";
    private long masteryFullSeconds = 3 * 3600;
    private int boostCooldown = 120;
    private int rollCooldown = 60;
    private int catchWindow = 40;
    private Set<String> blockedWorlds = Set.of();
    private boolean debug;

    private static final class Session {
        final UUID id;
        final BroomType type;
        final boolean prevAllowFlight;
        int remaining; // 0 = 제한 없음
        final boolean limited;
        final SpeedGuard guard = new SpeedGuard();
        final CarrierVelocity carrier = new CarrierVelocity();
        Location last;
        int secondTicks;
        /** 마법 발견 v2: 아직 통계로 안 보낸 비행 거리 (블록) */
        double flown;
        /** 큐디치 매달림 중 (늦게 시야에 들어온 사람에게도 매달린 자세를 보여 주려고 기억) */
        boolean hanging;
        /** 속도 검사 여유 배율 (아티팩트: 폭풍 날개 최고 속도, 빗자루 끈·별의 잔향 가속) */
        float guardMul = 1f;
        /** 속도 버프 (BUFF_SPEED): 이 틱까지 속도 검사 상한 × speedMult (모드 쪽 최고 속도 버프와 같은 배수) */
        double speedMult = 1;
        long speedUntil;

        Session(UUID id, BroomType type, boolean prevAllowFlight, int limitTicks) {
            this.id = id;
            this.type = type;
            this.prevAllowFlight = prevAllowFlight;
            this.remaining = limitTicks;
            this.limited = limitTicks > 0;
        }
    }

    /** 아티팩트 빗자루 효과 (BroomCodec.PERK_*). MagicCodexBridge 가 장착이 바뀔 때 알려 준다. 다음 탑승부터 적용. */
    private final Map<UUID, Integer> perks = new HashMap<>();
    /** 빗자루 쿠션: 공중에서 빗자루를 떠난 시각 (ms). 이 뒤 10초 안의 첫 낙하 피해를 줄인다. */
    private final Map<UUID, Long> leftInAir = new HashMap<>();
    static final long CUSHION_WINDOW_MS = 10_000;
    static final double CUSHION_MULT = 0.7;
    static final float STORM_SPEED = 1.15f;

    private record PendingCatch(BroomType type, int remaining, boolean prevAllowFlight, long until) {}

    public BroomService(JavaPlugin plugin) {
        this.plugin = plugin;
        this.store = new BroomStore(plugin);
        reload();
    }

    // ------------------------------------------------------------------ 설정

    /** brooms.yml 형식 버전. 기본 파일의 config-version 과 같아야 한다. */
    static final int CONFIG_VERSION = 2;

    public void reload() {
        File file = new File(plugin.getDataFolder(), "brooms.yml");
        if (!file.exists()) plugin.saveResource("brooms.yml", false);
        var yml = YamlConfiguration.loadConfiguration(file);
        if (yml.getInt("config-version", 1) < CONFIG_VERSION) yml = upgrade(file, yml);
        brooms.clear();
        double climbPower = yml.getDouble("climb-power", 0.055);
        var section = yml.getConfigurationSection("brooms");
        if (section != null) {
            for (String id : section.getKeys(false)) {
                var s = section.getConfigurationSection(id);
                if (s != null && id.matches("[a-z0-9_]{1,40}")) brooms.put(id, BroomType.read(id, s, climbPower));
            }
        }
        practiceBroom = yml.getString("practice-broom", "broom_1");
        masteryFullSeconds = Math.max(60, Math.round(yml.getDouble("mastery-full-hours", 3) * 3600));
        boostCooldown = Math.max(20, yml.getInt("boost-cooldown-ticks", 120));
        rollCooldown = Math.max(20, yml.getInt("roll-cooldown-ticks", 60));
        catchWindow = Math.max(10, yml.getInt("catch-window-ticks", 40));
        blockedWorlds = new HashSet<>(yml.getStringList("blocked-worlds"));
        debug = yml.getBoolean("debug", false);
    }

    /**
     * 2026-10-16: 30종 개편으로 빗자루 번호·능력치가 전부 바뀌었다. 예전 파일은 brooms.yml.v&lt;N&gt;.bak 으로 남기고
     * 새 기본 파일을 쓰되, 빗자루 목록(brooms) 밖의 서버 설정(연습용 빗자루 제외)은 예전 값을 옮겨 온다.
     */
    private YamlConfiguration upgrade(File file, YamlConfiguration old) {
        int oldVersion = old.getInt("config-version", 1);
        File backup = new File(plugin.getDataFolder(), "brooms.yml.v" + oldVersion + ".bak");
        for (int i = 2; backup.exists(); i++) backup = new File(plugin.getDataFolder(), "brooms.yml.v" + oldVersion + ".bak" + i);
        try {
            java.nio.file.Files.move(file.toPath(), backup.toPath());
        } catch (java.io.IOException e) {
            plugin.getLogger().warning("brooms.yml 이 예전 버전인데 백업으로 옮기지 못했습니다(" + e + "). 새 파일로 직접 바꿔 주세요.");
            return old;
        }
        plugin.saveResource("brooms.yml", false);
        var fresh = YamlConfiguration.loadConfiguration(file);
        for (String key : new String[]{"climb-power", "mastery-full-hours", "boost-cooldown-ticks", "roll-cooldown-ticks", "catch-window-ticks", "blocked-worlds", "debug"}) {
            if (old.contains(key)) fresh.set(key, old.get(key));
        }
        try {
            fresh.save(file);
        } catch (java.io.IOException e) {
            plugin.getLogger().warning("brooms.yml 에 예전 서버 설정을 옮겨 적지 못했습니다(" + e + "). 기본값으로 동작합니다.");
            return YamlConfiguration.loadConfiguration(file);
        }
        plugin.getLogger().warning("brooms.yml 을 새 버전(" + CONFIG_VERSION + ", 빗자루 30종)으로 바꿨습니다. 예전 파일: " + backup.getName()
                + " (빗자루 목록·연습용 빗자루는 새 기본값, 나머지 서버 설정은 예전 값 유지)");
        return fresh;
    }

    public Map<String, BroomType> brooms() {
        return brooms;
    }

    public void start() {
        var messenger = plugin.getServer().getMessenger();
        messenger.registerOutgoingPluginChannel(plugin, BroomCodec.CHANNEL_RIDE);
        messenger.registerOutgoingPluginChannel(plugin, BroomCodec.CHANNEL_STATE);
        messenger.registerOutgoingPluginChannel(plugin, BroomCodec.CHANNEL_LIST);
        messenger.registerOutgoingPluginChannel(plugin, BroomCodec.CHANNEL_FX);
        messenger.registerOutgoingPluginChannel(plugin, BroomCodec.CHANNEL_PERK);
        messenger.registerOutgoingPluginChannel(plugin, BroomCodec.CHANNEL_BUFF);
        messenger.registerIncomingPluginChannel(plugin, BroomCodec.CHANNEL_EVENT, this);
        messenger.registerIncomingPluginChannel(plugin, BroomCodec.CHANNEL_REQUEST, this);
        plugin.getServer().getPluginManager().registerEvents(this, plugin);
        // 기록은 5분마다 저장 (바뀐 게 있을 때만)
        Bukkit.getScheduler().runTaskTimer(plugin, store::saveIfDirty, 6000, 6000);
    }

    public void shutdown() {
        for (UUID id : new ArrayList<>(sessions.keySet())) stop(id, BroomCodec.STATE_NORMAL, false);
        store.saveIfDirty();
    }

    // ------------------------------------------------------------------ 공개 API

    /** 모드가 깔려 있어서 빗자루 비행을 할 수 있는 클라이언트인지. */
    /** 타고 있는 사람의 모드에 비행 효과를 보낸다 (BroomCodec.FX_*). 타고 있지 않거나 모드가 없으면 false. */
    /** 큐디치 매달림 모드인 플레이어 (낙마 대신 매달림). */
    private final java.util.Set<UUID> hangMode = new java.util.HashSet<>();

    /**
     * 큐디치(오너 2026-10-20): 켜면 균형이 0이 돼도 떨어지지 않고 그 자리에서 한 팔로 매달렸다가, 회복되면 다시 올라탄다.
     * 타고 있지 않아도 모드에 알려 둔다. 모드가 없으면 false.
     */
    public boolean hangMode(Player p, boolean on) {
        if (on) hangMode.add(p.getUniqueId()); else hangMode.remove(p.getUniqueId());
        if (!on) {
            var s = sessions.get(p.getUniqueId());
            if (s != null && s.hanging) { s.hanging = false; broadcast(p, s.type.id(), BroomCodec.STATE_CLIMB, false); }
        }
        if (!p.getListeningPluginChannels().contains(BroomCodec.CHANNEL_FX)) return false;
        p.sendPluginMessage(plugin, BroomCodec.CHANNEL_FX, new byte[]{on ? BroomCodec.FX_HANG_MODE_ON : BroomCodec.FX_HANG_MODE_OFF});
        return true;
    }

    /**
     * 아티팩트 빗자루 효과 (BroomCodec.PERK_* 비트 묶음). MagicCodexBridge 가 리플렉션으로 부른다.
     * 속도 수치(폭풍 날개)는 다음 탑승부터, 모드 쪽 효과는 타는 중이면 바로 알려 준다.
     */
    public void setPerks(Player p, int mask) {
        Integer old = mask == 0 ? perks.remove(p.getUniqueId()) : perks.put(p.getUniqueId(), mask);
        if ((old == null ? 0 : old) != mask && isRiding(p)) sendPerks(p, mask);
    }

    public int perks(Player p) {
        return perks.getOrDefault(p.getUniqueId(), 0);
    }

    private void sendPerks(Player p, int mask) {
        if (p.getListeningPluginChannels().contains(BroomCodec.CHANNEL_PERK)) {
            p.sendPluginMessage(plugin, BroomCodec.CHANNEL_PERK, BroomCodec.encodePerks(mask));
        }
    }

    public boolean fx(Player p, byte kind) {
        if (!isRiding(p) || !p.getListeningPluginChannels().contains(BroomCodec.CHANNEL_FX)) return false;
        p.sendPluginMessage(plugin, BroomCodec.CHANNEL_FX, new byte[]{kind});
        return true;
    }

    /**
     * 시간제 비행 버프 (마법 375 불꽃 추진: 최고 속도 ×1.2 + 궤적 숨김, 380 순풍: 균형 감소 ×0.7).
     * kind = BroomCodec.BUFF_*. 타고 있지 않거나 모드가 이 채널을 모르면 false.
     */
    public boolean buff(Player p, byte kind, int ticks, float value) {
        var s = sessions.get(p.getUniqueId());
        if (s == null || !p.getListeningPluginChannels().contains(BroomCodec.CHANNEL_BUFF)) return false;
        if (!Float.isFinite(value)) return false;
        ticks = Math.max(0, Math.min(ticks, 20 * 60));
        if (kind == BroomCodec.BUFF_SPEED) value = Math.max(0.5f, Math.min(value, 2f));
        else if (kind == BroomCodec.BUFF_BALANCE_DRAIN) value = Math.max(0f, Math.min(value, 2f));
        else if (kind != BroomCodec.BUFF_HIDE_TRAIL) return false;
        byte[] payload = BroomCodec.encodeBuff(new BroomCodec.Buff(p.getUniqueId(), kind, ticks, value));
        if (kind == BroomCodec.BUFF_SPEED) {
            s.speedMult = Math.max(1, value);
            s.speedUntil = tick + ticks + 40; // 끝난 뒤 감속하는 동안의 여유
        } else if (kind == BroomCodec.BUFF_HIDE_TRAIL) {
            for (Player viewer : p.getTrackedBy()) {
                if (!viewer.equals(p) && viewer.getListeningPluginChannels().contains(BroomCodec.CHANNEL_BUFF)) {
                    viewer.sendPluginMessage(plugin, BroomCodec.CHANNEL_BUFF, payload);
                }
            }
        }
        p.sendPluginMessage(plugin, BroomCodec.CHANNEL_BUFF, payload);
        return true;
    }

    public boolean hasMod(Player p) {
        return p.getListeningPluginChannels().contains(BroomCodec.CHANNEL_RIDE);
    }

    public boolean isRiding(Player p) {
        return sessions.containsKey(p.getUniqueId());
    }

    /** 빗자루 보유 권한: chacademy.broom.own.<ID> (또는 chacademy.broom.own.*). OP 는 전부 보유. */
    public boolean owns(Player p, BroomType type) {
        return p.isOp() || p.hasPermission("chacademy.broom.own." + type.id()) || p.hasPermission("chacademy.broom.own.*");
    }

    /** 가진 빗자루 목록 (brooms.yml 순서 = 기본 → 전설). */
    public java.util.List<BroomType> ownedBrooms(Player p) {
        var list = new ArrayList<BroomType>();
        for (var t : brooms.values()) if (owns(p, t)) list.add(t);
        return list;
    }

    /**
     * 비행 마법에 쓸 빗자루. 장착한 빗자루를 아직 가지고 있으면 그것,
     * 장착이 없거나 권한이 빠졌으면 가진 것 중 가장 좋은 것(목록 맨 뒤), 하나도 없으면 null (연습용 빗자루).
     */
    public BroomType ownedBroom(Player p) {
        BroomType eq = brooms.get(store.broom(p.getUniqueId()));
        if (eq != null && owns(p, eq)) return eq;
        var owned = ownedBrooms(p);
        return owned.isEmpty() ? null : owned.get(owned.size() - 1);
    }

    /** 장착. 가지고 있지 않으면 false. 타는 중이면 바로 갈아탐. */
    public boolean equip(Player p, BroomType type) {
        if (!owns(p, type)) return false;
        store.setBroom(p.getUniqueId(), type.id());
        if (isRiding(p)) {
            var s = sessions.get(p.getUniqueId());
            if (s != null && !s.type.id().equals(type.id()) && !s.limited) {
                stop(p.getUniqueId(), BroomCodec.STATE_NORMAL, false);
                start(p, type, 0);
            }
        }
        sendList(p);
        return true;
    }

    /** 장비창용 목록을 보냄 (모드가 있을 때만). */
    public void sendList(Player p) {
        if (!p.getListeningPluginChannels().contains(BroomCodec.CHANNEL_LIST)) return;
        var eq = ownedBroom(p);
        var owned = new ArrayList<BroomCodec.Owned>();
        for (var t : ownedBrooms(p)) {
            owned.add(new BroomCodec.Owned(t.id(), t.name(), t.grade(), t.maxSpeed(), t.accel(), t.turn(), t.brake(), t.stability(), t.climb()));
        }
        var msg = new BroomCodec.ListMsg(eq == null ? "" : eq.id(), mastery(p.getUniqueId()), owned);
        p.sendPluginMessage(plugin, BroomCodec.CHANNEL_LIST, BroomCodec.encodeList(msg));
    }

    public float mastery(UUID id) {
        return BroomMastery.of(store.seconds(id), masteryFullSeconds);
    }

    /**
     * 비행 마법에서 부르는 진입점. 타고 있으면 내리고, 아니면 탄다.
     * 빗자루를 가지고 있으면 시간 제한 없이, 없으면 연습용 빗자루로 practiceTicks 동안.
     *
     * @return 처리했으면 true. 모드가 없는 등 빗자루 비행을 못 하면 false (호출한 쪽이 예전 비행으로 대신 처리)
     */
    public boolean flightSpell(Player p, int practiceTicks) {
        if (isRiding(p)) return false; // A spell never dismounts; the rebound sneak input owns dismount.
        if (!hasMod(p)) return false;
        BroomType owned = ownedBroom(p);
        if (owned != null) return start(p, owned, 0);
        BroomType practice = brooms.get(practiceBroom);
        if (practice == null && !brooms.isEmpty()) practice = brooms.values().iterator().next();
        return practice != null && start(p, practice, Math.max(20, practiceTicks));
    }

    /** 빗자루 타기 시작. limitTicks 0 = 제한 없음. */
    public boolean start(Player p, BroomType type, int limitTicks) {
        if (!hasMod(p) || isRiding(p) || (limitTicks <= 0 && !owns(p, type))) return false;
        if (p.isDead() || p.getGameMode() == GameMode.SPECTATOR || p.isInsideVehicle() || p.isGliding()) return false;
        if (blockedWorlds.contains(p.getWorld().getName())) {
            kr.chacademy.motionplugin.ChacaLink.notify(p, "warning", "broom-blocked-world", "이곳에서는 빗자루를 탈 수 없습니다", "", 3000);
            return false;
        }
        if (!p.hasPermission("chacademy.broom.use")) return false;
        var event = new BroomStartEvent(p, type, limitTicks);
        Bukkit.getPluginManager().callEvent(event);
        if (event.isCancelled()) return false;
        return begin(p, type, limitTicks, p.getAllowFlight());
    }

    private boolean begin(Player p, BroomType type, int limitTicks, boolean prevAllowFlight) {
        var s = new Session(p.getUniqueId(), type, prevAllowFlight, limitTicks);
        int mask = perks(p);
        float speedMul = (mask & BroomCodec.PERK_STORM) != 0 ? STORM_SPEED : 1f;
        s.guardMul = speedMul * ((mask & (BroomCodec.PERK_CORD | BroomCodec.PERK_STAR_ECHO)) != 0 ? 1.1f : 1f);
        s.last = p.getLocation();
        sessions.put(p.getUniqueId(), s);
        catches.remove(p.getUniqueId());
        // 모드가 abilities.flying 을 켜서 서버의 "비행 금지" 판정·낙하 피해 누적을 막는다.
        // 서버가 이 값을 받아들이려면 allowFlight 가 먼저 켜져 있어야 한다.
        p.setAllowFlight(true);
        p.setFallDistance(0);
        leftInAir.remove(p.getUniqueId());
        sendPerks(p, mask); // ride 보다 먼저 (모드는 효과를 틱마다 읽으므로 순서가 바뀌어도 괜찮음)
        var ride = new BroomCodec.Ride(type.id(), type.maxSpeed() * speedMul, type.accel(), type.turn(), type.brake(),
                type.stability(), type.climb(), mastery(p.getUniqueId()), limitTicks, boostCooldown, rollCooldown);
        p.sendPluginMessage(plugin, BroomCodec.CHANNEL_RIDE, BroomCodec.encodeRide(ride));
        broadcast(p, type.id(), BroomCodec.STATE_START, false);
        ensureTask();
        if (debug) plugin.getLogger().info(p.getName() + " 빗자루 탑승: " + type.id() + (limitTicks > 0 ? " (" + limitTicks + "틱)" : ""));
        return true;
    }

    /** 빗자루에서 내림. reason 은 BroomCodec.STATE_* */
    public void stop(UUID id, byte reason, boolean quitting) {
        var s = sessions.remove(id);
        if (s == null) return;
        Player p = Bukkit.getPlayer(id);
        if (p == null) return;
        // 본인 포함 전원에게 "내렸다" 알림 (본인 모드는 이걸 받고 비행을 끝냄)
        broadcast(p, "", reason, true);
        if (!quitting) {
            if (!p.isOnGround() && !p.isDead() && (perks(p) & BroomCodec.PERK_CUSHION) != 0) leftInAir.put(id, System.currentTimeMillis());
            p.setFlying(false);
            if (p.getGameMode() == GameMode.SURVIVAL || p.getGameMode() == GameMode.ADVENTURE) {
                p.setAllowFlight(s.prevAllowFlight);
            }
            if (reason == BroomCodec.STATE_TIMEOUT) {
                p.addPotionEffect(new PotionEffect(PotionEffectType.SLOW_FALLING, 100, 0));
                kr.chacademy.motionplugin.ChacaLink.notify(p, "warning", "broom-practice-timeout", "연습용 빗자루의 마력이 다했습니다", "", 3000);
            }
            if (reason == BroomCodec.STATE_FALL) {
                catches.put(id, new PendingCatch(s.type, s.remaining, s.prevAllowFlight, tick + catchWindow));
                kr.chacademy.motionplugin.ChacaLink.stat(id, "빗자루낙마", 1); // 서버 통계 + 마법 발견 v2 (순풍)
            }
        }
        if (debug) plugin.getLogger().info(p.getName() + " 빗자루 하차 (reason " + reason + ")");
    }

    public void stop(Player p) {
        stop(p.getUniqueId(), BroomCodec.STATE_NORMAL, false);
    }

    // ------------------------------------------------------------------ 틱 검사 (탄 사람만)

    private void ensureTask() {
        if (task == null) task = Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 1, 1);
    }

    private void tick() {
        tick++;
        if (!catches.isEmpty()) catches.values().removeIf(c -> c.until() < tick);
        if (sessions.isEmpty()) {
            if (catches.isEmpty() && task != null) {
                task.cancel();
                task = null;
            }
            return;
        }
        for (var s : new ArrayList<>(sessions.values())) {
            Player p = Bukkit.getPlayer(s.id);
            if (p == null || !p.isOnline()) {
                sessions.remove(s.id);
                continue;
            }
            if (p.isDead() || p.getGameMode() == GameMode.SPECTATOR || p.isInsideVehicle()
                    || blockedWorlds.contains(p.getWorld().getName()) || !p.hasPermission("chacademy.broom.use") || (!s.limited && !owns(p, s.type))) {
                stop(s.id, BroomCodec.STATE_NORMAL, false);
                sendList(p);
                continue;
            }
            p.setFallDistance(0);
            if (!p.getAllowFlight()) p.setAllowFlight(true);

            Location now = p.getLocation();
            if (s.last != null && s.last.getWorld() == now.getWorld()) {
                s.carrier.sample(now.getX() - s.last.getX(), now.getY() - s.last.getY(), now.getZ() - s.last.getZ());
                double cap = s.type.maxSpeed() * s.guardMul * (s.speedUntil >= tick ? s.speedMult : 1) * 1.45 + 0.6; // 부스트·급강하·구르기 여유 (아티팩트 여유 + 속도 버프)
                double step = s.last.distance(now);
                if (step < 10) s.flown += step;
                if (s.guard.tick(step, cap)) {
                    plugin.getLogger().warning(p.getName() + " 빗자루 속도 상한 초과로 비행 종료 (" + s.type.id() + ")");
                    stop(s.id, BroomCodec.STATE_NORMAL, false);
                sendList(p);
                    continue;
                }
            } else {
                s.guard.reset();
                s.carrier.reset();
            }
            s.last = now;

            if (++s.secondTicks >= 20) {
                s.secondTicks = 0;
                store.addSecond(s.id);
                int whole = (int) s.flown;
                if (whole > 0) { s.flown -= whole; kr.chacademy.motionplugin.ChacaLink.stat(s.id, "빗자루비행", whole); } // 서버 통계 + 마법 발견 v2 (빗자루 차원이동·불꽃 추진)
            }
            if (s.limited && --s.remaining <= 0) {
                stop(s.id, BroomCodec.STATE_TIMEOUT, false);
            }
        }
    }

    // ------------------------------------------------------------------ 모드 → 서버

    @Override
    public void onPluginMessageReceived(@NotNull String channel, @NotNull Player player, byte @NotNull [] message) {
        if (BroomCodec.CHANNEL_REQUEST.equals(channel)) {
            onRequest(player, message);
            return;
        }
        if (!BroomCodec.CHANNEL_EVENT.equals(channel)) return;
        BroomCodec.Event e;
        try {
            e = BroomCodec.decodeEvent(message);
        } catch (BroomCodec.MalformedException ex) {
            if (debug) plugin.getLogger().warning(player.getName() + " 잘못된 빗자루 패킷: " + ex.getMessage());
            return;
        }
        UUID id = player.getUniqueId();
        switch (e.type()) {
            case BroomCodec.EVT_DISMOUNT -> stop(id, BroomCodec.STATE_NORMAL, false);
            case BroomCodec.EVT_FALL -> {
                if (isRiding(player)) stop(id, BroomCodec.STATE_FALL, false);
            }
            case BroomCodec.EVT_CATCH -> {
                var c = catches.remove(id);
                if (c == null || c.until() < tick || isRiding(player) || player.isDead() || player.isOnGround() || player.isInsideVehicle() || player.isGliding() || player.getGameMode() == GameMode.SPECTATOR || !player.hasPermission("chacademy.broom.use") || blockedWorlds.contains(player.getWorld().getName()) || (c.remaining() <= 0 && !owns(player, c.type()))) return;
                begin(player, c.type(), c.remaining(), c.prevAllowFlight());
            }
            case BroomCodec.EVT_HANG, BroomCodec.EVT_CLIMB -> {
                var s = sessions.get(id);
                if (s == null) return;
                boolean hang = e.type() == BroomCodec.EVT_HANG;
                if (hang && !hangMode.contains(id)) return;
                if (s.hanging == hang) return;
                s.hanging = hang;
                // 다른 사람 화면에서도 매달림 / 다시 올라타기 모션이 보이도록
                broadcast(player, s.type.id(), hang ? BroomCodec.STATE_HANG : BroomCodec.STATE_CLIMB, false);
            }
            case BroomCodec.EVT_ROLL_LEFT, BroomCodec.EVT_ROLL_RIGHT -> {
                if (!isRiding(player)) return;
                Long last = lastRoll.get(id);
                if (last != null && tick - last < rollCooldown - 10) return;
                lastRoll.put(id, tick);
                // 구르는 순간 짧은 무적 (회피)
                player.setNoDamageTicks(Math.max(player.getNoDamageTicks(), 10));
                byte r = e.type() == BroomCodec.EVT_ROLL_LEFT ? BroomCodec.STATE_ROLL_LEFT : BroomCodec.STATE_ROLL_RIGHT;
                broadcast(player, sessions.get(id).type.id(), r, false);
            }
            default -> {
                // BOOST, CRASH 는 지금은 기록만 (마나 소모 등은 나중에 연결)
                if (debug) plugin.getLogger().info(player.getName() + " 빗자루 이벤트 " + e.type() + " " + e.value());
            }
        }
    }

    private final Map<UUID, Long> lastRequest = new HashMap<>();

    private void onRequest(Player player, byte[] message) {
        BroomCodec.Request r;
        try {
            r = BroomCodec.decodeRequest(message);
        } catch (BroomCodec.MalformedException ex) {
            return;
        }
        long now = System.currentTimeMillis();
        Long last = lastRequest.get(player.getUniqueId());
        if (last != null && now - last < 150) return; // 연타 방지
        lastRequest.put(player.getUniqueId(), now);
        if (r.kind() == BroomCodec.REQ_EQUIP) {
            var type = brooms.get(r.broom());
            if (type == null || !equip(player, type)) sendList(player);
        } else {
            sendList(player);
        }
    }

    /** 모드가 채널을 등록하면(접속 직후) 장비창 목록을 한 번 보냄. */
    @EventHandler
    public void onRegister(PlayerRegisterChannelEvent e) {
        if (BroomCodec.CHANNEL_LIST.equals(e.getChannel())) {
            Bukkit.getScheduler().runTaskLater(plugin, () -> {
                if (e.getPlayer().isOnline()) sendList(e.getPlayer());
            }, 20);
        }
    }

    // ------------------------------------------------------------------ 전파

    private void broadcast(Player rider, String broom, byte reason, boolean includeSelf) {
        byte[] payload = BroomCodec.encodeState(new BroomCodec.State(rider.getUniqueId(), broom, reason));
        Collection<Player> viewers = rider.getTrackedBy();
        for (Player viewer : viewers) {
            if (!viewer.equals(rider)) viewer.sendPluginMessage(plugin, BroomCodec.CHANNEL_STATE, payload);
        }
        // 시작할 때 본인은 ride 패킷으로 이미 알고 있으므로, 시작이 아닐 때만 본인에게도 보냄
        if (includeSelf || reason != BroomCodec.STATE_START) {
            rider.sendPluginMessage(plugin, BroomCodec.CHANNEL_STATE, payload);
        }
    }

    // ------------------------------------------------------------------ 이벤트

    /** 나중에 시야에 들어온 유저에게도 "이 사람 빗자루 탐"을 알려준다. */
    @EventHandler
    public void onTrack(PlayerTrackEntityEvent e) {
        if (!(e.getEntity() instanceof Player rider)) return;
        var s = sessions.get(rider.getUniqueId());
        if (s == null) return;
        Player viewer = e.getPlayer();
        byte[] payload = BroomCodec.encodeState(new BroomCodec.State(rider.getUniqueId(), s.type.id(), s.hanging ? BroomCodec.STATE_HANG : BroomCodec.STATE_NORMAL));
        // 엔티티 생성 패킷보다 늦게 도착하도록 다음 틱에 보냄 (모드는 UUID 로 기억하므로 순서가 바뀌어도 괜찮음)
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (viewer.isOnline() && sessions.containsKey(rider.getUniqueId())) {
                viewer.sendPluginMessage(plugin, BroomCodec.CHANNEL_STATE, payload);
            }
        });
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent e) {
        UUID id = e.getPlayer().getUniqueId();
        if (sessions.containsKey(id)) {
            var s = sessions.get(id);
            stop(id, BroomCodec.STATE_NORMAL, false);
            // 다음 접속 때 비행 권한이 남지 않게 되돌려 둠
            if (e.getPlayer().getGameMode() == GameMode.SURVIVAL || e.getPlayer().getGameMode() == GameMode.ADVENTURE) {
                e.getPlayer().setFlying(false);
                e.getPlayer().setAllowFlight(s.prevAllowFlight);
            }
        }
        catches.remove(id);
        hangMode.remove(id);
        perks.remove(id);
        leftInAir.remove(id);
        lastRoll.remove(id);
        lastRequest.remove(id);
        store.saveIfDirty();
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onDeath(PlayerDeathEvent e) {
        stop(e.getPlayer().getUniqueId(), BroomCodec.STATE_NORMAL, false);
        catches.remove(e.getPlayer().getUniqueId());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onGameMode(PlayerGameModeChangeEvent e) {
        if (e.getNewGameMode() == GameMode.SPECTATOR) stop(e.getPlayer().getUniqueId(), BroomCodec.STATE_NORMAL, false);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onTeleport(PlayerTeleportEvent e) {
        var s = sessions.get(e.getPlayer().getUniqueId());
        if (s != null) {
            s.guard.reset();
            s.carrier.reset();
            s.last = null;
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onWorld(PlayerChangedWorldEvent e) {
        catches.remove(e.getPlayer().getUniqueId());
        var s = sessions.get(e.getPlayer().getUniqueId());
        if (s != null) {
            s.guard.reset();
            s.carrier.reset();
            s.last = null;
        }
    }

    /**
     * 빗자루 비행 속도 {x, y, z} (블록/틱, 서버가 받은 위치로 추정). 타고 있지 않으면 null.
     * 투사체 마법이 속도 상속·지연 보정에 쓴다.
     */
    public double[] velocity(Player p) {
        var s = sessions.get(p.getUniqueId());
        return s == null ? null : s.carrier.get();
    }

    /** 타고 있는 동안 낙하 피해는 없다 (내리거나 떨어진 뒤의 낙하만 피해). */
    @EventHandler(ignoreCancelled = true)
    public void onFall(EntityDamageEvent e) {
        if (e.getCause() == EntityDamageEvent.DamageCause.FALL && e.getEntity() instanceof Player p) {
            if (isRiding(p)) {
                e.setCancelled(true);
                return;
            }
            // 빗자루 쿠션: 공중에서 빗자루를 떠난 뒤 첫 낙하 피해 -30%
            Long left = leftInAir.remove(p.getUniqueId());
            if (left != null && System.currentTimeMillis() - left <= CUSHION_WINDOW_MS && (perks(p) & BroomCodec.PERK_CUSHION) != 0) {
                e.setDamage(e.getDamage() * CUSHION_MULT);
            }
        }
    }

    // ------------------------------------------------------------------ 명령어에서 쓰는 것

    BroomStore store() {
        return store;
    }

    long masteryFullSeconds() {
        return masteryFullSeconds;
    }

    String practiceBroomId() {
        return practiceBroom;
    }

    BroomType ridingType(Player p) {
        var s = sessions.get(p.getUniqueId());
        return s == null ? null : s.type;
    }

    int ridingCount() {
        return sessions.size();
    }
}
