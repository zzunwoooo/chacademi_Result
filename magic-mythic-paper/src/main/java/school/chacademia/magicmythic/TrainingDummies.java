package school.chacademia.magicmythic;

import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import io.lumine.mythic.api.mobs.entities.SpawnReason;
import io.lumine.mythic.bukkit.BukkitAdapter;
import io.lumine.mythic.bukkit.MythicBukkit;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import java.io.File;
import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.*;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.attribute.Attribute;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.*;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityTargetEvent;
import org.bukkit.event.world.WorldUnloadEvent;
import org.bukkit.scheduler.BukkitTask;
import school.magiccodex.protocol.TrainingDummyProtocol;
import school.magiccodex.protocol.TrainingDummyProtocol.Kind;

/**
 * 오픈 전 #107 마법 연습장 훈련 허수아비 (2026-10-25, 2026-10-26 개선).
 * 운영자가 서 있는 자리에 "죽지 않는 허수아비"를 세운다.
 *  - 몸: MythicMobs 몹 {@code CA_TrainingDummy}(HUSK + ModelEngine 모델 training_dummy). 미몹이 없으면 예전처럼 바닐라 허스크.
 *  - 무적이 아니다: 피해·원소 표식·반응·디버프(기절·둔화 …)가 보통 몹과 똑같이 들어가고, 체력만 다시 채운다(한 방에 죽을 피해는 1 남김).
 *  - 맞으면 모델이 뒤로 휘청(hit, 큰 피해면 hit_heavy) — MythicMobs 스킬의 state 메커닉.
 *  - 머리 위 표시는 MagicCodex 클라가 그린다({@link TrainingDummyProtocol}): 누적(10초 안 맞으면 0) + 떠오르는 피해 숫자.
 *    서버는 맞을 때만 근처 플레이어에게 작은 패킷 하나를 보낸다(텍스트 엔티티 갱신 없음).
 *  - 위치 목록은 plugins/ChAcademiaMagicMythic/training-dummies.yml. 엔티티는 저장하지 않고(persistent=false) 청크가 로드돼 있으면 5초마다 확인해 다시 세운다.
 *  - 넉백·끌어오기로 밀려도 제자리로 돌아온다.
 * 서버 부하: 위치 수만큼(보통 몇 개) 5초 간격 확인 + 맞을 때만 계산·전송. 매 틱 순회 없음.
 */
final class TrainingDummies implements Listener, AutoCloseable {
    static final String TAG = "chacademia_training_dummy";
    /** 다른 플러그인이 이미 제외하는 표시(교화·퀘스트 처치·야생 몹 상한·기믹)를 같이 단다. */
    static final String PET_TAG = "chacademia_pet";
    static final String HIT_SKILL = "CA_TrainingDummy_Hit", HEAVY_SKILL = "CA_TrainingDummy_HitHeavy";
    /** hit 애니를 너무 자주 다시 틀지 않는 간격 (큰 피해는 언제나 튼다). */
    static final long ANIM_GAP_MS = 250;

    record Spot(String world, double x, double y, double z, float yaw) {
        String line() { return world + ";" + x + ";" + y + ";" + z + ";" + yaw; }
        static Spot parse(String s) {
            String[] f = s.split(";");
            if (f.length != 5) return null;
            try { return new Spot(f[0], Double.parseDouble(f[1]), Double.parseDouble(f[2]), Double.parseDouble(f[3]), Float.parseFloat(f[4])); }
            catch (NumberFormatException e) { return null; }
        }
        Location at(World w) { return new Location(w, x, y, z, yaw, 0); }
    }

    /** 연속으로 맞은 묶음: 처음 맞은 때, 마지막으로 맞은 때, 누적 피해. */
    record Streak(long start, long last, double total) {
        static final Streak NONE = new Streak(0, 0, 0);
        boolean empty() { return total <= 0; }
        /** Pure: 마지막 타에서 resetMs 넘게 지났으면 새로 센다. */
        Streak add(long now, double amount, long resetMs) {
            if (empty() || now - last > resetMs) return new Streak(now, now, amount);
            return new Streak(start, now, total + amount);
        }
        Streak expire(long now, long resetMs) { return !empty() && now - last > resetMs ? NONE : this; }
        /** 초당 피해: 처음 타부터 마지막 타까지(최소 1초로 나눔). */
        double dps() { return empty() ? 0 : total / Math.max(1.0, (last - start) / 1000.0); }
    }

    /** Pure: 숫자 모양. 반응 피해 > 출처 없는 지속 피해 > 큰 피해 > 보통. */
    static Kind kind(boolean reaction, boolean byEntity, double amount, double heavy) {
        if (reaction) return Kind.REACTION;
        if (!byEntity) return Kind.DOT;
        return amount >= heavy ? Kind.HEAVY : Kind.NORMAL;
    }

    private final class Live {
        final Spot spot; UUID body; int bodyId; boolean modelled; double top;
        Streak all = Streak.NONE; final Map<UUID, Streak> byPlayer = new HashMap<>();
        UUID lastAttacker; long lastAnim;
        Live(Spot spot) { this.spot = spot; }
    }

    private final MagicMythicPlugin plugin;
    private final File file;
    private final List<Live> live = new ArrayList<>();
    private final Map<UUID, Live> byBody = new HashMap<>();
    private final Map<UUID, Double> pendingFinal = new HashMap<>();
    private final BukkitTask task;
    private boolean warnedMob;

    TrainingDummies(MagicMythicPlugin plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "training-dummies.yml");
        load();
        Bukkit.getMessenger().registerOutgoingPluginChannel(plugin, TrainingDummyProtocol.CHANNEL);
        task = Bukkit.getScheduler().runTaskTimer(plugin, this::check, 40, 100);
    }

    private long resetMs() { return Math.round(Math.clamp(plugin.getConfig().getDouble("training-dummy.reset-seconds", 10), 1, 120) * 1000); }
    private double heavy() { return Math.max(0.1, plugin.getConfig().getDouble("training-dummy.heavy-damage", 20)); }
    private double range() { return Math.clamp(plugin.getConfig().getDouble("training-dummy.show-range", 32), 8, 96); }

    // ------------------------------------------------------------------ 저장

    private void load() {
        if (!file.isFile()) return;
        for (String s : YamlConfiguration.loadConfiguration(file).getStringList("spots")) {
            Spot spot = Spot.parse(s);
            if (spot != null) live.add(new Live(spot));
        }
    }

    private boolean save() {
        var yaml = new YamlConfiguration();
        var lines = new ArrayList<String>();
        for (Live l : live) lines.add(l.spot.line());
        yaml.set("spots", lines);
        File tmp = new File(file.getParentFile(), file.getName() + ".tmp");
        try {
            file.getParentFile().mkdirs();
            yaml.save(tmp);
            try { Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE); }
            catch (AtomicMoveNotSupportedException ex) { Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING); }
            return true;
        } catch (IOException ex) {
            plugin.getLogger().warning("training-dummies.yml 저장 실패: " + ex.getMessage());
            return false;
        }
    }

    // ------------------------------------------------------------------ 세우기·유지

    /** 5초마다: 청크가 로드된 자리에 허수아비가 없으면 세우고, 밀려났으면 되돌린다. 10초 안 맞은 누적은 지운다. */
    private void check() {
        long now = System.currentTimeMillis(), reset = resetMs();
        for (Live l : live) {
            World w = Bukkit.getWorld(l.spot.world());
            if (w == null || !w.isChunkLoaded(((int) Math.floor(l.spot.x())) >> 4, ((int) Math.floor(l.spot.z())) >> 4)) continue;
            Entity body = l.body == null ? null : Bukkit.getEntity(l.body);
            if (!(body instanceof LivingEntity le) || !le.isValid() || le.isDead()) { spawn(l, w); continue; }
            home(l, le);
            l.all = l.all.expire(now, reset);
            if (l.all.empty()) { l.byPlayer.clear(); l.lastAttacker = null; }
        }
    }

    private void spawn(Live l, World w) {
        despawn(l);
        Location at = l.spot.at(w);
        LivingEntity body = mythic(at);
        l.modelled = body != null;
        if (body == null) body = w.spawn(at, Husk.class);
        prepare(body, !l.modelled);
        l.top = l.modelled ? plugin.getConfig().getDouble("training-dummy.model-height", 2.35) : body.getHeight() + 0.1;
        l.body = body.getUniqueId(); l.bodyId = body.getEntityId();
        byBody.put(l.body, l);
    }

    /** MythicMobs 몹(ModelEngine 모델)으로 소환. 미몹이 없거나 실패하면 null → 바닐라 허스크. */
    private LivingEntity mythic(Location at) {
        String id = plugin.getConfig().getString("training-dummy.mythic-mob", "CA_TrainingDummy");
        if (id == null || id.isBlank()) return null;
        try {
            var type = MythicBukkit.inst().getMobManager().getMythicMob(id);
            if (type.isEmpty()) {
                if (!warnedMob) { warnedMob = true; plugin.getLogger().warning("허수아비 미몹 " + id + " 이 없어 바닐라 허스크로 세웁니다 (MythicMobs/Mobs 확인)"); }
                return null;
            }
            var mob = type.get().spawn(BukkitAdapter.adapt(at), 1, SpawnReason.OTHER);
            if (mob == null) return null;
            Entity e = mob.getEntity().getBukkitEntity();
            if (e instanceof LivingEntity le) return le;
            e.remove();
        } catch (RuntimeException | LinkageError e) {
            if (!warnedMob) { warnedMob = true; plugin.getLogger().warning("허수아비 미몹 소환 실패 (" + id + "): " + e); }
        }
        return null;
    }

    private static void prepare(LivingEntity h, boolean showName) {
        h.setAI(false); h.setSilent(true); h.setPersistent(false); h.setRemoveWhenFarAway(false);
        h.setCanPickupItems(false);
        if (h instanceof Zombie z) z.setShouldBurnInDay(false);
        h.addScoreboardTag(TAG); h.addScoreboardTag(PET_TAG);
        // 모델이 있으면 이름은 숨긴다(머리 위는 클라 표시 몫). 바닐라 허스크일 때만 이름을 띄운다.
        h.customName(Component.text("훈련 허수아비", NamedTextColor.GOLD)); h.setCustomNameVisible(showName);
        if (h.getEquipment() != null) h.getEquipment().clear();
        var hp = h.getAttribute(Attribute.MAX_HEALTH); if (hp != null) hp.setBaseValue(1024);
        var armor = h.getAttribute(Attribute.ARMOR); if (armor != null) armor.setBaseValue(0);
        var kb = h.getAttribute(Attribute.KNOCKBACK_RESISTANCE); if (kb != null) kb.setBaseValue(1);
        h.setHealth(Targets.maxHealth(h));
    }

    private void despawn(Live l) {
        if (l.body != null) { byBody.remove(l.body); Entity e = Bukkit.getEntity(l.body); if (e != null) e.remove(); }
        l.body = null; l.bodyId = 0; l.all = Streak.NONE; l.byPlayer.clear(); l.lastAttacker = null;
    }

    private void home(Live l, LivingEntity body) {
        Location at = l.spot.at(body.getWorld());
        if (body.getLocation().distanceSquared(at) > 0.09) { body.setVelocity(new org.bukkit.util.Vector()); body.teleport(at); }
    }

    // ------------------------------------------------------------------ 피해

    /** 한 방에 죽는 피해는 1을 남긴다(무적 아님: 피해·반응·디버프는 그대로 처리됨). 원래 피해는 기록용으로 기억. */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void lethal(EntityDamageEvent e) {
        if (!(e.getEntity() instanceof LivingEntity le) || !byBody.containsKey(le.getUniqueId())) return;
        double fin = e.getFinalDamage();
        pendingFinal.put(le.getUniqueId(), fin);
        if (e.getCause() == EntityDamageEvent.DamageCause.VOID || e.getCause() == EntityDamageEvent.DamageCause.KILL) return;
        if (fin >= le.getHealth() - 1) e.setDamage(Math.max(0, le.getHealth() - 1));
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void damaged(EntityDamageEvent e) {
        if (!(e.getEntity() instanceof LivingEntity le)) return;
        Live l = byBody.get(le.getUniqueId());
        if (l == null) return;
        Double original = pendingFinal.remove(le.getUniqueId());
        double amount = original != null ? original : e.getFinalDamage();
        if (amount <= 0) return;
        long now = System.currentTimeMillis(), reset = resetMs();
        boolean byEntity = e instanceof EntityDamageByEntityEvent;
        Player attacker = byEntity ? PartyFriendlyFire.attacker(((EntityDamageByEntityEvent) e).getDamager()) : null;
        // 독·화상 터짐처럼 출처 없이 들어오는 피해는 직전(10초 안)에 때린 사람 몫으로 센다.
        if (attacker == null && l.lastAttacker != null && now - l.all.last() <= reset) attacker = Bukkit.getPlayer(l.lastAttacker);
        l.all = l.all.add(now, amount, reset);
        Kind kind = kind(plugin.reactions() != null && plugin.reactions().applying(), byEntity, amount, heavy());
        send(l, le, amount, kind);
        animate(l, le, amount >= heavy(), now);
        if (attacker != null) {
            l.lastAttacker = attacker.getUniqueId();
            Streak own = l.byPlayer.getOrDefault(attacker.getUniqueId(), Streak.NONE).add(now, amount, reset);
            l.byPlayer.put(attacker.getUniqueId(), own);
            // 예전 알림 카드: 머리 위 표시로 충분해 기본 끔 (training-dummy.notify-card: true 면 다시 켬)
            if (plugin.getConfig().getBoolean("training-dummy.notify-card", false))
                ChacaLink.notify(attacker, "system", "magic-mythic:training-dummy", "허수아비 피해 " + TrainingDummyProtocol.format(amount),
                    "누적 " + TrainingDummyProtocol.format(own.total()) + " · 초당 " + TrainingDummyProtocol.format(own.dps()), 700);
        }
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (!le.isValid() || le.isDead()) return;
            le.setHealth(Targets.maxHealth(le));
            home(l, le);
        }, 10);
    }

    /** 근처(기본 32블록) MagicCodex 클라에게만, 맞을 때 한 번. */
    private void send(Live l, LivingEntity body, double amount, Kind kind) {
        Location at = l.spot.at(body.getWorld());
        byte[] bytes;
        try {
            bytes = TrainingDummyProtocol.encode(new TrainingDummyProtocol.Hit(l.bodyId, at.getX(), at.getY() + l.top, at.getZ(),
                (float) amount, kind, (float) l.all.total(), (float) l.all.dps(), (int) (resetMs() / 50)));
        } catch (IllegalArgumentException ex) { return; }
        double r2 = range() * range();
        for (Player p : body.getWorld().getPlayers()) {
            if (p.getLocation().distanceSquared(at) > r2) continue;
            if (!p.getListeningPluginChannels().contains(TrainingDummyProtocol.CHANNEL)) continue;
            p.sendPluginMessage(plugin, TrainingDummyProtocol.CHANNEL, bytes);
        }
    }

    /** 모델이 있을 때만: 큰 피해는 hit_heavy, 보통은 hit (0.25초 안에 다시 맞으면 이어서 흔들리게 두고 다시 틀지 않음). */
    private void animate(Live l, LivingEntity body, boolean big, long now) {
        if (!l.modelled || (!big && now - l.lastAnim < ANIM_GAP_MS)) return;
        l.lastAnim = now;
        try { MythicBukkit.inst().getAPIHelper().castSkill(body, big ? HEAVY_SKILL : HIT_SKILL); }
        catch (RuntimeException | LinkageError ex) { l.modelled = false; plugin.getLogger().warning("허수아비 맞는 모션 실패: " + ex); }
    }

    /** AI 를 꺼 두었지만 혹시 다른 플러그인이 켜도 아무도 노리지 않는다. */
    @EventHandler(ignoreCancelled = true)
    public void target(EntityTargetEvent e) { if (byBody.containsKey(e.getEntity().getUniqueId())) e.setCancelled(true); }

    @EventHandler public void worldUnload(WorldUnloadEvent e) {
        for (Live l : live) if (l.spot.world().equals(e.getWorld().getName())) despawn(l);
    }

    // ------------------------------------------------------------------ 명령 (/컨텐츠관리 연습장 허수아비 …)

    LiteralArgumentBuilder<CommandSourceStack> command() {
        return Commands.literal("연습장").then(Commands.literal("허수아비")
            .then(Commands.literal("설치").executes(ctx -> {
                Player p = player(ctx.getSource());
                Location at = p.getLocation();
                Spot spot = new Spot(at.getWorld().getName(), at.getBlockX() + 0.5, at.getY(), at.getBlockZ() + 0.5, Math.round(at.getYaw() / 45f) * 45f);
                for (Live l : live) if (l.spot.world().equals(spot.world()) && Math.abs(l.spot.x() - spot.x()) < 0.6 && Math.abs(l.spot.z() - spot.z()) < 0.6 && Math.abs(l.spot.y() - spot.y()) < 2)
                    throw ChacaLink.error("여기에는 이미 허수아비가 있어요");
                Live l = new Live(spot);
                live.add(l);
                if (!save()) { live.remove(l); throw ChacaLink.error("training-dummies.yml 을 저장하지 못했어요 (콘솔 확인)"); }
                spawn(l, at.getWorld());
                ChacaLink.ok(ctx.getSource().getSender(), "훈련 허수아비를 세웠어요 (" + live.size() + "개" + (l.modelled ? "" : ", 모델 없음 → 허스크") + ")");
                return 1;
            }))
            .then(Commands.literal("제거").executes(ctx -> {
                Player p = player(ctx.getSource());
                Live best = null; double bd = 9;
                for (Live l : live) {
                    if (!l.spot.world().equals(p.getWorld().getName())) continue;
                    double d = l.spot.at(p.getWorld()).distanceSquared(p.getLocation());
                    if (d < bd) { bd = d; best = l; }
                }
                if (best == null) throw ChacaLink.error("3칸 안에 허수아비가 없어요");
                live.remove(best);
                save();
                despawn(best);
                ChacaLink.ok(ctx.getSource().getSender(), "훈련 허수아비를 치웠어요 (남은 " + live.size() + "개)");
                return 1;
            }))
            .then(Commands.literal("목록").executes(ctx -> {
                var lines = new ArrayList<String>();
                lines.add("── 훈련 허수아비 " + live.size() + "개 ──");
                for (Live l : live) lines.add(String.format(" %s %.1f, %.1f, %.1f%s", l.spot.world(), l.spot.x(), l.spot.y(), l.spot.z(),
                    l.body == null ? " (청크 안 열림)" : l.modelled ? " (모델)" : " (허스크)"));
                ChacaLink.lines(ctx.getSource().getSender(), lines);
                return 1;
            })));
    }

    private static Player player(CommandSourceStack src) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        if (src.getSender() instanceof Player p) return p;
        throw ChacaLink.error("게임 안에서만 쓸 수 있어요");
    }

    int count() { return live.size(); }

    @Override public void close() {
        task.cancel();
        for (Live l : live) despawn(l);
        byBody.clear(); pendingFinal.clear();
        Bukkit.getMessenger().unregisterOutgoingPluginChannel(plugin, TrainingDummyProtocol.CHANNEL);
    }
}
