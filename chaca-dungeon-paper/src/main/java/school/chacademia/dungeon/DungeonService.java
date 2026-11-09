package school.chacademia.dungeon;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.ExperienceOrb;
import org.bukkit.entity.Item;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Monster;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import school.chacademia.dungeon.Instances.Run;
import school.chacademia.dungeon.Instances.Slot;
import school.chacademia.dungeon.Instances.State;
import school.chacademia.dungeon.protocol.DungeonProtocol;
import school.chacademia.dungeon.protocol.DungeonProtocol.Holder;

/**
 * 던전 진행 전부 (메인 스레드). 판정은 모두 서버가 한다: 클라이언트는 세션 번호로 "이 창의 버튼을 눌렀다" 만 보낸다.
 *
 * <p>도전 시작 시점: 시작 지점에 도착한 뒤 처음 움직였을 때 (start-move-distance), 움직이지 않으면 start-fallback-seconds 뒤.
 * → 이동·청크 로딩 시간은 기록에 들어가지 않는다. 기록 = 마지막 단계 완료 시각 − 시작 시각 (서버 시계).
 *
 * <p>끝나는 경우: 클리어 · 나가기(포기) · 사망 · 접속 종료(서버 이동 포함) · 던전 월드를 다른 방법으로 벗어남 · 플러그인 종료.
 * 클리어만 기록·보상. 나머지는 실패 (환불 없음). 끝나면 포탈 앞(exit)으로 보내고, 칸은 몹·떨어진 아이템·기믹을 정리한 뒤에만 다음 사람에게 간다.
 *
 * <p>[기능:파티던전] 2026-10-31 오너:
 * <ul>
 *   <li>마지막 보스를 잡으면 기본·확률 보상을 보스 자리에 떨어뜨리고(파티당 한 벌), 첫 클리어 보상은 사람마다 우편함으로.
 *       출구 포탈(칸 지점 "출구", 없으면 보스 자리)이 열리고 exit-seconds(30) 뒤 남은 사람을 모두 마을(포탈 앞)로.</li>
 *   <li>칸 구역 밖에 leave-grace-seconds(5) 넘게 있으면 판에서 빠진다 (1인 = 실패, 파티 = 그 사람만 마을로).</li>
 *   <li>3인 파티 던전 (party-size): 파티장이 입장 → 파티원 모두 수락(accept-seconds) → 같은 칸으로. 1인 입장 불가.
 *       쓰러지면 관전(남은 사람이 있으면), 모두 쓰러지면 실패. 나가거나 끊긴 사람만 마을로, 남은 사람은 계속.
 *       파티장이 나가기 = 모두 실패, 파티원이 나가기 = 그 사람만. 핑은 파티원끼리.</li>
 * </ul>
 */
final class DungeonService {
    private final DungeonPlugin plugin;
    final Hooks hooks;
    final ItemsAdderItems items;
    final Records records;
    final Instances instances = new Instances();
    Map<String, Defs.Dungeon> dungeons = Map.of();

    /** 사람 → 그 사람이 들어 있는 판 (파티면 여러 사람이 같은 판). */
    private final Map<UUID, Run> runs = new HashMap<>();
    private final Map<UUID, Run> mobOwner = new HashMap<>();
    private final Set<UUID> busy = new HashSet<>();
    /** 포탈 가까이에서 입장창을 받은 사람: 던전 id + 세션 번호. 멀어지면 지운다 (다시 다가가면 새로 열림). */
    private record Near(String dungeon, int session) {}
    private final Map<UUID, Near> near = new HashMap<>();
    private final Set<UUID> ourTeleport = new HashSet<>();
    private final Map<UUID, Location> respawnTo = new HashMap<>();
    private final Map<UUID, DungeonProtocol.Result> pendingResult = new HashMap<>();
    private final Map<String, Holder> bestCache = new HashMap<>();
    final Doors doors;
    private final AtomicInteger sessions = new AtomicInteger((int) (System.nanoTime() & 0x3FFFFFFF));
    private int ticks;
    // [기능:파티던전 시작]
    /** 파티 입장 수락 (세션 번호 → 수락 창). state: 0 기다림 · 1 수락 · 2 거절. */
    private static final class AcceptCheck {
        final int session; final String dungeon; final UUID leader; final List<UUID> members;
        final Map<UUID, Integer> state = new LinkedHashMap<>(), need = new HashMap<>();
        final long deadline;
        boolean hard; // [기능:던전난이도] 파티장이 고른 난이도
        AcceptCheck(int session, String dungeon, UUID leader, List<UUID> members, long deadline) {
            this.session = session; this.dungeon = dungeon; this.leader = leader; this.members = List.copyOf(members); this.deadline = deadline;
        }
    }
    private final Map<Integer, AcceptCheck> accepts = new HashMap<>();
    private final Map<UUID, AcceptCheck> acceptOf = new HashMap<>();
    private final Map<UUID, ArrayDeque<Long>> pingTimes = new HashMap<>();
    /** 관전으로 바꾸기 전 게임 모드 (플레이어 데이터에 남겨 튕겨도 다음 접속 때 돌려놓는다). */
    private final NamespacedKey spectateKey;
    /** MagicCodexBridge 교화: 보스를 잡으면 30초 봉인, 이 표시가 붙는다 (TamingBridge graceKey). */
    private static final NamespacedKey TAMING_GRACE = new NamespacedKey("magiccodexbridge", "taming_grace");
    // [기능:파티던전 끝]
    // [기능:던전난이도 시작]
    /** 입장창에서 고른 난이도 (true = 하드). 없으면 이지. 서버가 꺼지면 잊는다. */
    private final Map<UUID, Boolean> hardPick = new HashMap<>();
    private static final NamespacedKey EASY_HEALTH = new NamespacedKey("chacadungeon", "easy_health");

    /** 이 사람이 지금 보는 난이도: 파티 던전에서 파티원이면 파티장이 고른 것. */
    private boolean pickedHard(UUID id, Defs.Dungeon d) {
        if (d.partySize > 1) {
            var party = PartyLink.party(id);
            if (party.isPresent()) return hardPick.getOrDefault(party.get().leader(), false);
        }
        return hardPick.getOrDefault(id, false);
    }

    /** 입장창 전환 버튼. 파티 던전은 파티장만 바꾼다. */
    private void difficultyPressed(Player p, int session, String value) {
        UUID id = p.getUniqueId();
        var d = dungeonForSession(id, session);
        if (d == null) { expiredWindow(p); return; }
        if (runs.containsKey(id) || busy.contains(id) || acceptOf.containsKey(id)) return;
        if (d.partySize > 1) {
            var party = PartyLink.party(id);
            if (party.isPresent() && !party.get().leader().equals(id)) {
                ChacaLink.notify(p, "warning", "dungeon:difficulty-leader", d.name, "난이도는 파티장이 정할 수 있어요.", 3000);
                return;
            }
        }
        hardPick.put(id, value.equals("hard"));
        if (d.partySize > 1) refreshOpenEntries(); // 파티원 창도 같이 바뀌게
        else openEntry(p, d, session);
    }

    /** 이지 판 몹·보스의 최대 체력 배율 (소환 직후). */
    private void scaleHealth(Run run, Entity e) {
        var d = dungeons.get(run.slot.dungeon);
        if (d == null || !(e instanceof LivingEntity le)) return;
        double m = d.health(run.hard);
        if (Math.abs(m - 1) < 1e-6) return;
        var a = le.getAttribute(org.bukkit.attribute.Attribute.MAX_HEALTH);
        if (a == null) return;
        a.removeModifier(EASY_HEALTH);
        a.addModifier(new org.bukkit.attribute.AttributeModifier(EASY_HEALTH, m - 1, org.bukkit.attribute.AttributeModifier.Operation.MULTIPLY_SCALAR_1));
        le.setHealth(Math.max(1, a.getValue()));
    }

    /** 판 안 플레이어가 받는 몹 피해 배율 (평타·투사체·보스 패턴). 판 밖·플레이어끼리는 1. */
    double damageMultiplier(Player victim, Entity damager) {
        Run run = runs.get(victim.getUniqueId());
        if (run == null || run.ended) return 1;
        Entity src = damager instanceof org.bukkit.entity.Projectile pr && pr.getShooter() instanceof Entity sh ? sh : damager;
        if (src == null || src instanceof Player) return 1;
        var d = dungeons.get(run.slot.dungeon);
        return d == null ? 1 : d.damage(run.hard);
    }
    // [기능:던전난이도 끝]

    DungeonService(DungeonPlugin plugin, Hooks hooks, ItemsAdderItems items, Records records) {
        this.plugin = plugin; this.hooks = hooks; this.items = items; this.records = records;
        this.doors = new Doors(plugin.getLogger(), new java.io.File(plugin.getDataFolder(), "doors-ledger.yml"));
        this.spectateKey = new NamespacedKey(plugin, "spectate_mode");
    }

    private int session() { return sessions.incrementAndGet() & 0x7FFFFFFF; }
    private long now() { return System.currentTimeMillis(); }

    void apply(Map<String, Defs.Dungeon> next) {
        dungeons = next;
        for (var d : next.values()) instances.ensure(d.id, d.instances);
        bestCache.clear();
        for (Player p : Bukkit.getOnlinePlayers()) sendPortals(p);
    }

    Defs.Dungeon dungeonOfWorld(World w) {
        for (var d : dungeons.values()) if (d.world.equals(w.getName())) return d;
        return null;
    }

    Run run(UUID player) { return runs.get(player); }

    // [기능:파티던전 시작]
    private Set<Run> liveRuns() { return new LinkedHashSet<>(runs.values()); }

    private List<Player> online(Run run) {
        var out = new ArrayList<Player>();
        for (UUID id : run.members) { Player p = Bukkit.getPlayer(id); if (p != null) out.add(p); }
        return out;
    }

    /** 판을 대표하는 사람 (단계 명령 {player} 기준): 살아 있는 파티장 → 살아 있는 아무나 → 접속한 아무나. */
    private Player anchor(Run run) {
        Player leader = Bukkit.getPlayer(run.leader);
        if (leader != null && !run.dead.contains(run.leader) && !leader.isDead()) return leader;
        for (UUID id : run.members) { Player p = Bukkit.getPlayer(id); if (p != null && !run.dead.contains(id) && !p.isDead()) return p; }
        for (UUID id : run.members) { Player p = Bukkit.getPlayer(id); if (p != null) return p; }
        return null;
    }

    private List<Player> alivePlayers(Run run) {
        var out = new ArrayList<Player>();
        for (UUID id : run.members) { Player p = Bukkit.getPlayer(id); if (p != null && !run.dead.contains(id) && !p.isDead()) out.add(p); }
        return out;
    }

    private void notifyRun(Run run, String kind, String key, String title, String body) {
        for (Player p : online(run)) ChacaLink.notify(p, kind, key, title, body, 0);
    }

    private void syncAll(Run run) { for (Player p : online(run)) syncRun(run, p); }

    private void sendDoorsAll(Run run) { for (Player p : online(run)) sendDoors(run, p); }

    private String nick(UUID id) { String n = hooks.nickname(id); return n == null || n.isBlank() ? "플레이어" : n; }
    // [기능:파티던전 끝]

    // ------------------------------------------------------------ 주기 (10틱)

    void tick() {
        ticks++;
        long now = now();
        for (Player p : Bukkit.getOnlinePlayers()) portalCheck(p);
        // 응답 없는 안내 → 칸 정리, 대기 취소
        for (var o : instances.expired(now)) {
            release(o.slot());
            Player p = Bukkit.getPlayer(o.player());
            if (p != null) {
                ChacaLink.notify(p, "warning", "dungeon:offer-expired", "입장 대기가 취소되었어요", "입장 안내에 " + plugin.offerSeconds() + "초 동안 응답하지 않았어요.", 0);
                sendQueue(p);
            }
        }
        for (var d : dungeons.values()) {
            if (d.partySize > 1) continue; // [기능:파티던전] 파티 던전은 대기열 없음
            for (var o : instances.assign(d.id, now, plugin.offerSeconds() * 1000L, id -> Bukkit.getPlayer(id) != null, this::session)) {
                Player p = Bukkit.getPlayer(o.player());
                if (p == null) continue;
                ChacaLink.notify(p, "system", "dungeon:ready", "입장 준비 완료", d.name + "에 자리가 났어요. " + plugin.offerSeconds() + "초 안에 입장해 주세요.", 0);
                sendQueue(p);
            }
        }
        if (ticks % 2 == 0) for (Player p : Bukkit.getOnlinePlayers()) if (instances.queuedDungeon(p.getUniqueId()) != null) sendQueue(p);
        acceptTick(now); // [기능:파티던전]
        for (var run : liveRuns()) runTick(run, now);
        if (ticks % 4 == 0) refreshOpenEntries();
    }

    // ------------------------------------------------------------ 포탈 → 입장창

    private void portalCheck(Player p) {
        UUID id = p.getUniqueId();
        var current = near.get(id);
        for (var d : dungeons.values()) {
            if (d.portal == null || !p.getWorld().getName().equals(d.portal.world())) continue;
            Location at = p.getLocation();
            double dx = at.getX() - d.portal.x(), dz = at.getZ() - d.portal.z(), dy = at.getY() - d.portal.y();
            double dist = Math.sqrt(dx * dx + dz * dz);
            boolean inside = dist <= d.portalRadius && Math.abs(dy) < 4;
            if (current != null && current.dungeon().equals(d.id)) {
                if (dist > d.portalRadius * plugin.rearmFactor() || Math.abs(dy) > 6) near.remove(id);
                return;
            }
            if (inside && !runs.containsKey(id) && !p.isDead()) {
                var n = new Near(d.id, session());
                near.put(id, n);
                openEntry(p, d, n.session());
                return;
            }
        }
        if (current != null && dungeons.get(current.dungeon()) == null) near.remove(id);
    }

    /** 입장창 (처음 열기와 갱신 모두 같은 메시지: 같은 세션 번호면 클라이언트는 열린 창만 고친다). */
    void openEntry(Player p, Defs.Dungeon d, int session) {
        if (!clientReady(p)) {
            ChacaLink.notify(p, "warning", "dungeon:client", d.name, "던전 화면이 없는 실행기예요. 실행기를 다시 켜서 업데이트해 주세요.", 60_000);
            return;
        }
        // [기능:탑연결 시작] 외부 던전: 기록·버튼은 그 플러그인에서
        if (!d.external.isBlank()) { send(p, new DungeonProtocol.EntryMsg(externalEntry(p, d, session))); return; }
        // [기능:탑연결 끝]
        UUID id = p.getUniqueId();
        boolean hard = pickedHard(id, d); // [기능:던전난이도] 고른 난이도의 기록·보상
        personal(d, hard, id).thenCombine(best(d.recordKey(hard)), (mine, best) -> new Object[]{mine, best}).whenComplete((pair, error) -> Bukkit.getScheduler().runTask(plugin, () -> {
            if (!p.isOnline()) return;
            var mine = error == null ? (Records.Personal) pair[0] : Records.Personal.NONE;
            var top = error == null ? (Holder) pair[1] : Holder.NONE;
            if (error != null) plugin.getLogger().warning("기록 읽기 실패: " + error);
            send(p, new DungeonProtocol.EntryMsg(entry(p, d, session, mine, top, hard)));
        }));
    }

    private void refreshOpenEntries() {
        for (var e : List.copyOf(near.entrySet())) {
            Player p = Bukkit.getPlayer(e.getKey());
            var d = dungeons.get(e.getValue().dungeon());
            if (p != null && d != null && !runs.containsKey(p.getUniqueId())) openEntry(p, d, e.getValue().session());
        }
    }

    private DungeonProtocol.Entry entry(Player p, Defs.Dungeon d, int session, Records.Personal mine, Holder top, boolean hard) {
        UUID id = p.getUniqueId();
        int need = ticketNeed(d, mine);
        int button;
        String lock = "";
        String queuedIn = instances.queuedDungeon(id);
        var offer = instances.offer(id);
        // [기능:파티던전 시작]
        var party = PartyLink.party(id);
        int partyCount = party.map(x -> x.members().size()).orElse(1);
        boolean leader = party.map(x -> x.leader().equals(id)).orElse(true);
        if (d.partySize > 1) {
            if (runs.containsKey(id)) button = DungeonProtocol.BTN_IN_RUN;
            else if (!allowed(p, d)) { button = DungeonProtocol.BTN_LOCKED; lock = d.lockMessage; }
            else if (acceptOf.containsKey(id)) button = DungeonProtocol.BTN_ACCEPTING;
            else if (queuedIn != null) { button = DungeonProtocol.BTN_LOCKED; lock = "다른 던전 대기열에 등록되어 있어요."; }
            else if (partyCount != d.partySize) { button = DungeonProtocol.BTN_LOCKED; lock = "파티원 " + d.partySize + "명이 함께해야 입장할 수 있어요"; }
            else if (!leader) { button = DungeonProtocol.BTN_LOCKED; lock = "파티장이 입장을 시작할 수 있어요"; }
            else if (instances.firstFree(d.id) == null) { button = DungeonProtocol.BTN_LOCKED; lock = "빈 자리가 없어요 · 잠시 뒤 다시 시도해 주세요"; }
            else button = DungeonProtocol.BTN_ENTER;
        } else
        // [기능:파티던전 끝]
        if (runs.containsKey(id)) button = DungeonProtocol.BTN_IN_RUN;
        else if (!allowed(p, d)) { button = DungeonProtocol.BTN_LOCKED; lock = d.lockMessage; }
        else if (offer != null && offer.slot().dungeon.equals(d.id)) { button = DungeonProtocol.BTN_READY; session = offer.session(); }
        else if (d.id.equals(queuedIn)) button = DungeonProtocol.BTN_QUEUED;
        else if (queuedIn != null) { button = DungeonProtocol.BTN_LOCKED; lock = "다른 던전 대기열에 등록되어 있어요."; }
        else if (acceptOf.containsKey(id)) { button = DungeonProtocol.BTN_LOCKED; lock = "파티 입장 수락 중이에요."; }
        else if (!instances.hasWaiting(d.id) && instances.firstFree(d.id) != null) button = DungeonProtocol.BTN_ENTER;
        else button = DungeonProtocol.BTN_QUEUE;
        boolean firstPending = !mine.everCleared(); // [기능:던전난이도] 첫 클리어는 난이도 상관없이 한 번
        // 입장창 보상: 묶음별(기본·첫 클리어·확률), 줄마다 아이템 하나(수량 표시). 빈 묶음은 보내지 않는다.
        var groups = new ArrayList<DungeonProtocol.RewardGroup>();
        addGroup(groups, DungeonProtocol.R_BASE, "기본 보상", false, d.base(hard));
        addGroup(groups, DungeonProtocol.R_FIRST, "첫 클리어 보상", !firstPending, d.first(hard));
        addGroup(groups, DungeonProtocol.R_RANDOM, "확률 보상", false, d.random(hard));
        String ticketName = d.ticketItem.isBlank() ? "" : itemName(d.ticketItem);
        int usual = d.ticketItem.isBlank() ? 0 : d.ticketAmount;
        return new DungeonProtocol.Entry(session, d.name, d.subtitle, mine.clears() > 0, mine.bestMs(), top,
                instances.busy(d.id), instances.capacity(d.id), usual, usual == 0 ? 0 : countTickets(p, d.ticketItem), ticketName,
                usual > 0 && need == 0, button, lock, !d.naturalRegen, groups,
                d.partySize, d.partySize > 1 ? partyCount : 1, d.partySize <= 1 || leader, d.art,
                hard ? DungeonProtocol.D_HARD : DungeonProtocol.D_EASY); // [기능:던전난이도]
    }

    // [기능:탑연결 시작]
    private DungeonProtocol.Entry externalEntry(Player p, Defs.Dungeon d, int session) {
        var info = TowerLink.info(d.external, p);
        var groups = new ArrayList<DungeonProtocol.RewardGroup>();
        addGroup(groups, DungeonProtocol.R_BASE, "기본 보상", false, d.baseRewards);
        addGroup(groups, DungeonProtocol.R_FIRST, "첫 도달 보상", false, d.firstRewards);
        addGroup(groups, DungeonProtocol.R_RANDOM, "확률 보상", false, d.randomRewards);
        String ticketName = d.ticketItem.isBlank() ? "" : itemName(d.ticketItem);
        int usual = d.ticketItem.isBlank() ? 0 : d.ticketAmount;
        String sub = d.subtitle + (info.line().isBlank() ? "" : (d.subtitle.isBlank() ? "" : " · ") + info.line());
        return new DungeonProtocol.Entry(session, d.name, sub, false, -1, Holder.NONE, info.active(), info.max(), usual,
                usual == 0 ? 0 : countTickets(p, d.ticketItem), ticketName, false, info.button(), info.lock(), !d.naturalRegen, groups,
                1, 1, true, d.art, DungeonProtocol.D_NONE);
    }

    /** 외부 던전 입장·대기 버튼: 그 플러그인에 넘기고 입장창을 닫는다 (탑이 순서·입장권을 직접 처리). */
    private boolean externalPressed(Player p, int session, boolean enter) {
        var d = dungeonForSession(p.getUniqueId(), session);
        if (d == null || d.external.isBlank()) return false;
        if (!allowed(p, d)) { ChacaLink.notify(p, "warning", "dungeon:locked", d.name, d.lockMessage, 3000); return true; }
        TowerLink.enter(d.external, p);
        Bukkit.getScheduler().runTaskLater(plugin, () -> { if (p.isOnline() && near.containsKey(p.getUniqueId())) openEntry(p, d, session); }, 10L);
        return true;
    }
    private void externalLeave(Player p) {
        var n = near.get(p.getUniqueId());
        var d = n == null ? null : dungeons.get(n.dungeon());
        if (d != null && !d.external.isBlank()) TowerLink.leaveQueue(d.external, p);
    }
    // [기능:탑연결 끝]

    private void addGroup(List<DungeonProtocol.RewardGroup> out, int kind, String label, boolean done, List<Defs.Reward> list) {
        var items = new ArrayList<byte[]>();
        var chances = new ArrayList<Integer>();
        for (var r : list) {
            ItemStack it = preview(r);
            if (it == null) continue;
            items.add(it.serializeAsBytes());
            if (kind == DungeonProtocol.R_RANDOM) chances.add((int) Math.round(r.chance() * 100));
        }
        if (!items.isEmpty()) out.add(new DungeonProtocol.RewardGroup(kind, label, done, items, chances));
    }

    /** 입장창에 보일 아이템 하나 (수량 = 그 줄 전체, 99 까지). */
    private ItemStack preview(Defs.Reward r) {
        var stacks = build(List.of(r));
        if (stacks.isEmpty()) return null;
        ItemStack one = stacks.get(0).clone();
        int total = stacks.stream().mapToInt(ItemStack::getAmount).sum();
        one.setAmount(Math.min(99, Math.max(1, total)));
        return one;
    }

    private String itemName(String id) {
        ItemStack it = items.create(id, 1);
        if (it != null && it.hasItemMeta() && it.getItemMeta().hasDisplayName())
            return net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText().serialize(it.getItemMeta().displayName());
        return "던전 입장권";
    }

    private boolean allowed(Player p, Defs.Dungeon d) { return d.permission.isBlank() || p.hasPermission(d.permission); }

    private int ticketNeed(Defs.Dungeon d, Records.Personal mine) {
        if (d.ticketItem.isBlank() || d.ticketAmount <= 0) return 0;
        return d.freeUntilFirstClear && !mine.everCleared() ? 0 : d.ticketAmount; // [기능:던전난이도] 면제도 난이도 상관없이 한 번
    }

    private CompletableFuture<Records.Personal> personal(Defs.Dungeon d, boolean hard, UUID id) {
        if (!records.ready()) return CompletableFuture.completedFuture(Records.Personal.NONE);
        return records.personal(d.recordKey(hard), d.recordKey(!hard), id); // [기능:던전난이도]
    }

    private CompletableFuture<Holder> best(String dungeon) {
        Holder cached = bestCache.get(dungeon);
        if (cached != null || !records.ready()) return CompletableFuture.completedFuture(cached == null ? Holder.NONE : cached);
        return records.serverBest(dungeon).thenApply(h -> { Bukkit.getScheduler().runTask(plugin, () -> bestCache.put(dungeon, h)); return h; });
    }

    // ------------------------------------------------------------ 버튼

    void action(Player p, DungeonProtocol.Action a) {
        switch (a.kind()) {
            case DungeonProtocol.C_ENTER -> { if (!externalPressed(p, a.session(), true)) enter(p, a.session()); } // [기능:탑연결]
            case DungeonProtocol.C_QUEUE_JOIN -> { if (!externalPressed(p, a.session(), false)) queueJoin(p, a.session()); } // [기능:탑연결]
            case DungeonProtocol.C_QUEUE_LEAVE -> { externalLeave(p); queueLeave(p); } // [기능:탑연결]
            case DungeonProtocol.C_EDIT -> plugin.editor().action(p, a.json());
            case DungeonProtocol.C_EXIT -> exitPressed(p);
            // [기능:파티던전 시작]
            case DungeonProtocol.C_ACCEPT -> acceptAnswer(p, a.session(), true);
            case DungeonProtocol.C_REJECT -> acceptAnswer(p, a.session(), false);
            case DungeonProtocol.C_PING -> ping(p, a.json());
            // [기능:파티던전 끝]
            case DungeonProtocol.C_DIFFICULTY -> difficultyPressed(p, a.session(), a.json()); // [기능:던전난이도]
            default -> { }
        }
    }

    // [기능:파티던전 시작]
    /** 던전 메뉴 "나가기": 1인 = 포기, 파티장 = 모두 포기, 파티원 = 그 사람만 마을로. 출구가 열린 뒤면 그냥 나감. */
    private void exitPressed(Player p) {
        UUID id = p.getUniqueId();
        Run run = runs.get(id);
        if (run == null) return;
        if (run.exitAt > 0) { removeMember(run, id, "클리어", true); return; }
        if (!run.party) { finish(run, false, "중도 포기", false); return; }
        if (id.equals(run.leader)) {
            notifyRun(run, "warning", "dungeon:party-quit", "파티장이 던전을 포기했어요", "모두 마을로 돌아가요.");
            finish(run, false, "파티장 포기", false);
        } else removeMember(run, id, "파티에서 나감", true);
    }
    // [기능:파티던전 끝]

    /** 세션 번호 → 던전 (지금 열린 입장창이나 받은 입장 안내 것만). 클라이언트가 보낸 던전 ID 는 없다. */
    private Defs.Dungeon dungeonForSession(UUID id, int session) {
        var o = instances.offer(id);
        if (o != null && o.session() == session) return dungeons.get(o.slot().dungeon);
        var n = near.get(id);
        if (n != null && n.session() == session) return dungeons.get(n.dungeon());
        return null;
    }

    private void queueJoin(Player p, int session) {
        UUID id = p.getUniqueId();
        var d = dungeonForSession(id, session);
        if (d == null) { expiredWindow(p); return; }
        if (runs.containsKey(id) || busy.contains(id) || acceptOf.containsKey(id)) return;
        if (d.partySize > 1) { ChacaLink.notify(p, "warning", "dungeon:party-only", d.name, "파티 던전은 대기열이 없어요. 빈 자리가 나면 파티장이 입장해 주세요.", 3000); return; }
        if (!allowed(p, d)) { ChacaLink.notify(p, "warning", "dungeon:locked", d.name, d.lockMessage, 3000); return; }
        // 대기 등록 때는 걷지 않고 개수만 확인 (첫 클리어 전 무료 설정이면 여기서는 막지 않는다; 실제 판정·차감은 입장 때)
        if (!d.ticketItem.isBlank() && d.ticketAmount > 0 && !d.freeUntilFirstClear && countTickets(p, d.ticketItem) < d.ticketAmount) {
            ChacaLink.notify(p, "warning", "dungeon:ticket", "입장권이 부족해요", itemName(d.ticketItem) + " " + d.ticketAmount + "장이 필요해요.", 3000);
            return;
        }
        if (!instances.join(d.id, id)) {
            ChacaLink.notify(p, "warning", "dungeon:queued", "이미 대기 중이에요", "대기는 한 곳만 등록할 수 있어요.", 3000);
            sendQueue(p);
            return;
        }
        sendQueue(p);
        openEntry(p, d, session);
    }

    private void queueLeave(Player p) {
        Slot freed = instances.leave(p.getUniqueId());
        if (freed != null) release(freed);
        send(p, new DungeonProtocol.QueueMsg(new DungeonProtocol.Queue(DungeonProtocol.Q_NONE, "", 0, 0, 0, 0, 0, 0)));
        var n = near.get(p.getUniqueId());
        var d = n == null ? null : dungeons.get(n.dungeon());
        if (d != null) openEntry(p, d, n.session());
    }

    void sendQueue(Player p) {
        UUID id = p.getUniqueId();
        var offer = instances.offer(id);
        String dungeon = instances.queuedDungeon(id);
        if (dungeon == null) {
            send(p, new DungeonProtocol.QueueMsg(new DungeonProtocol.Queue(DungeonProtocol.Q_NONE, "", 0, 0, 0, 0, 0, 0)));
            return;
        }
        var d = dungeons.get(dungeon);
        String name = d == null ? dungeon : d.name;
        if (offer != null)
            send(p, new DungeonProtocol.QueueMsg(new DungeonProtocol.Queue(DungeonProtocol.Q_READY, name, 0, instances.queued(dungeon),
                    instances.busy(dungeon), instances.capacity(dungeon), Math.max(0, offer.expiresAt() - now()), offer.session())));
        else
            send(p, new DungeonProtocol.QueueMsg(new DungeonProtocol.Queue(DungeonProtocol.Q_WAITING, name, instances.position(dungeon, id),
                    instances.queued(dungeon), instances.busy(dungeon), instances.capacity(dungeon), 0, 0)));
    }

    private void expiredWindow(Player p) {
        ChacaLink.notify(p, "warning", "dungeon:expired", "창이 오래되었어요", "포탈에 다시 다가가 주세요.", 3000);
        send(p, new DungeonProtocol.CloseMsg());
    }

    /**
     * 입장. 버튼을 연타해도 busy 표시 때문에 한 번만 진행되고, 입장권은 칸을 잡은 뒤 같은 틱에 한 번만 걷는다.
     * 실패(이동 실패·접속 끊김)하면 걷은 입장권을 돌려주고 칸을 정리한다.
     */
    private void enter(Player p, int session) {
        UUID id = p.getUniqueId();
        if (busy.contains(id) || runs.containsKey(id) || acceptOf.containsKey(id)) return;
        var d = dungeonForSession(id, session);
        if (d == null) { expiredWindow(p); return; }
        if (!allowed(p, d)) { ChacaLink.notify(p, "warning", "dungeon:locked", d.name, d.lockMessage, 3000); return; }
        if (d.partySize > 1) { startParty(p, d); return; } // [기능:파티던전]
        busy.add(id);
        boolean hard = pickedHard(id, d); // [기능:던전난이도]
        personal(d, hard, id).whenComplete((mine, error) -> Bukkit.getScheduler().runTask(plugin, () -> {
            try {
                enterChecked(p, d, session, error == null ? mine : Records.Personal.NONE, hard);
            } finally {
                if (!runs.containsKey(id)) busy.remove(id);
            }
        }));
    }

    private void enterChecked(Player p, Defs.Dungeon d, int session, Records.Personal mine, boolean hard) {
        UUID id = p.getUniqueId();
        if (!p.isOnline() || p.isDead() || runs.containsKey(id)) return;
        // 다시 확인: 세션·칸·입장권·설정 (화면에 보인 빈자리를 믿지 않는다)
        if (dungeonForSession(id, session) != d) { expiredWindow(p); return; }
        int need = ticketNeed(d, mine);
        if (need > 0 && countTickets(p, d.ticketItem) < need) {
            ChacaLink.notify(p, "warning", "dungeon:ticket", "입장권이 부족해요", itemName(d.ticketItem) + " " + need + "장이 필요해요.", 3000);
            return;
        }
        var offer = instances.offer(id);
        Slot slot;
        if (offer != null && offer.session() == session) { slot = offer.slot(); instances.consumeOffer(id); }
        else {
            if (instances.queuedDungeon(id) != null) { ChacaLink.notify(p, "warning", "dungeon:queued", "대기 중이에요", "차례가 오면 입장 안내가 와요.", 3000); return; }
            slot = instances.reserveDirect(d.id, id);
            if (slot == null) {
                ChacaLink.notify(p, "warning", "dungeon:full", "빈 자리가 없어요", "대기열에 등록해 주세요.", 3000);
                openEntry(p, d, session);
                return;
            }
        }
        Location start = startOf(d, slot);
        if (start == null) { brokenSlot(d, slot, List.of(p)); return; }
        if (need > 0 && !takeTickets(p, d.ticketItem, need)) {
            release(slot);
            ChacaLink.notify(p, "warning", "dungeon:ticket", "입장권이 부족해요", itemName(d.ticketItem) + " " + need + "장이 필요해요.", 3000);
            return;
        }
        var run = new Run(id, slot, need, d.ticketItem);
        run.hard = hard; // [기능:던전난이도]
        slot.state = State.RESERVED; slot.holder = id; slot.run = run;
        runs.put(id, run);
        near.remove(id);
        send(p, new DungeonProtocol.CloseMsg());
        send(p, new DungeonProtocol.QueueMsg(new DungeonProtocol.Queue(DungeonProtocol.Q_NONE, "", 0, 0, 0, 0, 0, 0)));
        plugin.getLogger().info("입장 " + p.getName() + " → " + d.id + (hard ? " 하드" : " 이지") + " " + slot.no + "번 칸 (입장권 " + need + ") run=" + run.id);
        travel(run, p, start);
    }

    private Location startOf(Defs.Dungeon d, Slot slot) {
        var def = d.slots.get(slot.no);
        return def == null || def.start == null ? null : def.start.location();
    }

    private void brokenSlot(Defs.Dungeon d, Slot slot, List<Player> who) {
        plugin.getLogger().warning(d.id + " " + slot.no + "번 칸 시작 지점이 없음 → 이 칸을 고장으로 둠");
        slot.state = State.BROKEN; slot.broken = "시작 지점 없음"; slot.holder = null;
        for (Player p : who) ChacaLink.notify(p, "error", "dungeon:setup", "입장하지 못했어요", "던전 준비가 덜 되었어요. 운영진에게 알려 주세요.", 0);
    }

    /** 시작 지점으로 보내기. 도착하면 land, 실패하면 그 사람만 빼고 입장권을 돌려준다. */
    private void travel(Run run, Player p, Location start) {
        UUID id = p.getUniqueId();
        ourTeleport.add(id);
        p.teleportAsync(start, PlayerTeleportEvent.TeleportCause.PLUGIN).whenComplete((ok, error) -> Bukkit.getScheduler().runTask(plugin, () -> {
            ourTeleport.remove(id);
            if (run.ended || !run.has(id)) return;
            if (error != null || !Boolean.TRUE.equals(ok) || !p.isOnline()) {
                plugin.getLogger().warning("입장 이동 실패 " + p.getName() + " run=" + run.id + " " + error);
                abortMember(run, id, "이동 실패");
                return;
            }
            land(run, p);
        }));
    }

    /** 이동 전·중 실패한 사람: 입장권을 돌려주고 판에서 뺀다 (기록·결과 없음). 아무도 안 남으면 판을 지운다. */
    private void abortMember(Run run, UUID id, String why) {
        if (run.ended || !run.members.remove(id)) return;
        runs.remove(id);
        busy.remove(id);
        run.landed.remove(id);
        int taken = run.tickets.getOrDefault(id, 0);
        if (taken > 0) {
            ItemStack back = items.create(run.ticketItem, taken);
            if (back != null) {
                back.setAmount(taken);
                hooks.give(id, List.of(back), "dungeon-refund:" + run.id + ":" + id, "던전 입장권 반환", "입장하지 못해 입장권을 돌려드려요.");
            } else plugin.getLogger().severe("입장권 반환 실패 (아이템 없음) player=" + id + " run=" + run.id + " " + run.ticketItem + " x" + taken);
        }
        Player p = Bukkit.getPlayer(id);
        if (p != null) ChacaLink.notify(p, "error", "dungeon:abort", "입장하지 못했어요", why + (taken > 0 ? " — 입장권은 돌려드렸어요." : ""), 0);
        if (run.members.isEmpty()) {
            run.ended = true;
            records.logEnd(run.id, run.slot.dungeon, run.player, run.slot.no, "abort", 0, now());
            Instances.toResetting(run.slot);
            resetLater(run.slot, dungeons.get(run.slot.dungeon));
            return;
        }
        if (id.equals(run.leader)) run.leader = run.members.iterator().next();
        if (run.arrived) { notifyRun(run, "warning", "dungeon:member-left", nick(id) + "님이 들어오지 못했어요", "남은 파티원끼리 진행해요."); syncAll(run); }
        else if (run.landed.containsAll(run.members)) arrive(run);
    }

    /** 한 사람 도착. 모두 도착하면 판 시작 준비 (arrive). */
    private void land(Run run, Player p) {
        run.landed.add(p.getUniqueId());
        busy.remove(p.getUniqueId());
        if (run.arrived) { syncRun(run, p); sendDoors(run, p); return; }
        if (run.landed.containsAll(run.members)) arrive(run);
    }

    private void arrive(Run run) {
        run.arrived = true;
        run.arrivedAt = now();
        var d = dungeons.get(run.slot.dungeon);
        Location start = d == null ? null : startOf(d, run.slot);
        Player any = anchor(run);
        run.arrival = start != null ? start : any == null ? null : any.getLocation();
        run.slot.state = State.RUNNING;
        var def = d == null ? null : d.slots.get(run.slot.no);
        World w = d == null ? null : Bukkit.getWorld(d.world);
        // 앞 판에서 남은 것 한 번 더 정리 (청크가 막 불러와진 뒤)
        if (def != null && w != null && def.region() != null) clean(w, def.region(), run);
        // 문: 판 시작 때 전부 닫음 (처음부터 닫힘)
        if (def != null && w != null) for (var door : def.doors.values()) doors.close(Doors.key(run.slot.dungeon, run.slot.no, door.name), w, door.box());
        sendDoorsAll(run);
        syncAll(run);
        if (run.party && d != null) notifyRun(run, "system", "dungeon:party-start", d.name, "파티원 " + run.members.size() + "명이 함께 도전해요. Ctrl 로 핑을 찍을 수 있어요.");
    }

    // ------------------------------------------------------------ 진행

    private void runTick(Run run, long now) {
        if (run.ended || !run.arrived) return;
        var d = dungeons.get(run.slot.dungeon);
        var def = d == null ? null : d.slots.get(run.slot.no);
        if (d == null || def == null) { finish(run, false, "던전 설정이 바뀌었어요", false); return; }
        if (run.exitAt > 0) { exitTick(run, now); return; } // [기능:파티던전]
        if (anchor(run) == null) return;
        regionTick(run, d, def, now); // [기능:파티던전]
        if (run.ended) return;
        var alive = alivePlayers(run);
        if (run.startedAt == 0) {
            boolean moved = false;
            for (Player m : alive)
                if (run.arrival != null && run.arrival.getWorld() == m.getWorld() && m.getLocation().distance(run.arrival) >= plugin.startMoveDistance()) moved = true;
            if (moved || now - run.arrivedAt >= plugin.startFallbackSeconds() * 1000L) {
                run.startedAt = now;
                if (d.stages.isEmpty()) { cleared(run, d, def); return; }
                beginStage(run, d, def, 0);
            }
            if (run.startedAt == 0 || run.ended || run.exitAt > 0) return;
        }
        // 기믹과 연결한 문: 그 기믹이 열리면 문도 열림
        for (var door : def.doors.values()) {
            if (door.gimmick.isBlank()) continue;
            String k = Doors.key(run.slot.dungeon, run.slot.no, door.name);
            if (doors.closed(k) && hooks.gimmickOpen(d.world, def.gimmicks.get(door.gimmick))) openDoor(run, door.name, true);
        }
        for (var room : def.rooms.values()) {
            var box = room.box();
            if (box == null || run.roomsTriggered.contains(room.name)) continue;
            for (Player m : alive) if (box.contains(m.getLocation())) { triggerRoom(run, d, def, room, m.getWorld()); break; }
            if (run.ended || run.exitAt > 0) return;
        }
        if (run.stage >= 0 && run.stage < d.stages.size()) {
            var st = d.stages.get(run.stage);
            switch (st.type) {
                case "gimmick" -> { if (hooks.gimmickOpen(d.world, def.gimmicks.get(st.target))) completeStage(run, d, def); }
                case "reach" -> {
                    var point = def.points.get(st.target);
                    Location l = point == null ? null : point.location();
                    if (l != null) for (Player m : alive)
                        if (l.getWorld() == m.getWorld() && l.distance(m.getLocation()) <= st.radius) { completeStage(run, d, def); break; }
                }
                case "boss" -> {
                    if (run.boss != null) {
                        Entity e = Bukkit.getEntity(run.boss);
                        // [기능:파티던전] 교화 봉인(체력 1·무적, 30초) 중인 보스: 교화에 성공하면 몹이 그냥 사라지므로 처치로 친다
                        if (e != null && e.isValid() && e.getPersistentDataContainer().has(TAMING_GRACE)) { run.bossSealed = true; run.bossFell = e.getLocation(); }
                        else if (e == null || !e.isValid()) {
                            if (run.bossSealed) {
                                plugin.getLogger().info("보스가 교화되어 처치로 처리 run=" + run.id);
                                mobOwner.remove(run.boss); run.mobs.remove(run.boss); run.boss = null;
                                completeStage(run, d, def);
                            } else if (e == null || !e.isDead()) { mobOwner.remove(run.boss); run.mobs.remove(run.boss); run.boss = null; spawnBoss(run, def, st); }
                        }
                    }
                }
                default -> { }
            }
        }
        if (!run.ended && run.exitAt == 0 && now - run.lastSync >= 5000) syncAll(run);
    }

    // [기능:파티던전 시작]
    /**
     * 칸 구역(region) 밖: 관전 중이면 살아 있는 파티원 곁으로 되돌림, 아니면 leave-grace-seconds 동안 경고 뒤 판에서 뺀다.
     * 구역이 안 정해진 칸은 검사하지 않는다. 다른 월드로 간 것은 순간이동 이벤트가 처리한다.
     */
    private void regionTick(Run run, Defs.Dungeon d, Defs.Slot def, long now) {
        var region = def.region();
        if (region == null) return;
        long grace = plugin.leaveGraceSeconds() * 1000L;
        for (UUID id : List.copyOf(run.members)) {
            if (run.ended) return;
            Player p = Bukkit.getPlayer(id);
            if (p == null || p.isDead() || !run.landed.contains(id) || !p.getWorld().getName().equals(d.world)) continue;
            if (region.contains(p.getLocation())) {
                if (run.outSince.remove(id) != null) syncRun(run, p);
                continue;
            }
            if (run.dead.contains(id)) {
                Player to = anchor(run);
                Location back = to != null && to != p && region.contains(to.getLocation()) ? to.getLocation() : run.arrival;
                if (back != null) teleportOurs(p, back);
                continue;
            }
            Long since = run.outSince.get(id);
            if (since == null) {
                run.outSince.put(id, now);
                syncRun(run, p);
                ChacaLink.notify(p, "warning", "dungeon:out", "던전 구역을 벗어났어요", plugin.leaveGraceSeconds() + "초 안에 돌아와 주세요.", 0);
                continue;
            }
            if (now - since >= grace) {
                plugin.getLogger().info("던전 구역 이탈 " + p.getName() + " run=" + run.id);
                memberOut(run, id, "던전 구역 이탈", true);
            }
        }
    }

    /** 판에서 한 사람이 빠져야 할 때: 1인(또는 마지막 한 명)이면 판 실패, 파티면 그 사람만. */
    private void memberOut(Run run, UUID id, String reason, boolean teleport) {
        if (!run.party || run.members.size() <= 1) finish(run, false, reason, false);
        else removeMember(run, id, reason, teleport);
    }

    /**
     * 판에서 한 사람 빼기 (파티원 나가기·끊김·구역 이탈·출구 통과). 관전 모드를 되돌리고 마을(포탈 앞)로, 결과창을 보여 준다.
     * 출구가 열린 뒤(클리어)면 클리어 결과창. 아무도 안 남으면 판을 닫는다.
     */
    void removeMember(Run run, UUID id, String reason, boolean teleport) {
        if (run.ended || !run.members.remove(id)) return;
        boolean wasDead = run.dead.remove(id);
        run.outSince.remove(id);
        runs.remove(id);
        busy.remove(id);
        var d = dungeons.get(run.slot.dungeon);
        Player p = Bukkit.getPlayer(id);
        if (p != null) {
            restoreMode(p);
            send(p, new DungeonProtocol.RunMsg(DungeonProtocol.Run.NONE));
            sendDoors(null, p);
            sendPortals(p);
            if (teleport || wasDead) toExit(p, d);
            if (run.exitAt > 0) { var r = run.results.remove(id); if (r != null) result(p, r); }
            else result(p, new DungeonProtocol.Result(false, d == null ? run.slot.dungeon : d.name, reason, run.elapsed(now()), -1, Holder.NONE, false, false, false, "보상 없음", List.of()));
        }
        plugin.getLogger().info("던전 판에서 빠짐 " + id + " (" + reason + ") run=" + run.id + " 남은 " + run.members.size());
        if (run.exitAt == 0) records.logEnd(memberRun(run, id), run.slot.dungeon, id, run.slot.no, reason, run.startedAt, now());
        if (run.members.isEmpty()) {
            if (run.exitAt > 0) closeCleared(run);
            else finish(run, false, reason, false);
            return;
        }
        if (id.equals(run.leader)) {
            run.leader = run.members.iterator().next();
            if (run.exitAt == 0) notifyRun(run, "system", "dungeon:leader", "파티장이 바뀌었어요", nick(run.leader) + "님이 새 파티장이에요.");
        }
        if (run.exitAt == 0) {
            notifyRun(run, "warning", "dungeon:member-left", nick(id) + "님이 파티에서 빠졌어요", reason + " · 남은 파티원끼리 계속해요.");
            if (run.alive() == 0) { finish(run, false, "파티 전멸", false); return; }
        }
        syncAll(run);
    }

    /** 장부용 판 번호: 1인 = 판 번호, 파티 = 판 번호 + 사람 (사람마다 따로 기록). */
    private UUID memberRun(Run run, UUID member) {
        return run.party ? UUID.nameUUIDFromBytes((run.id + ":" + member).getBytes(java.nio.charset.StandardCharsets.UTF_8)) : run.id;
    }

    /** 출구가 열린 동안: 출구 포탈에 들어간 사람부터 마을로, 시간이 다 되면 모두. */
    private void exitTick(Run run, long now) {
        double r = plugin.exitRadius();
        for (UUID id : List.copyOf(run.members)) {
            if (run.ended) return;
            Player p = Bukkit.getPlayer(id);
            if (p == null || p.isDead() || run.exitPoint == null || p.getWorld() != run.exitPoint.getWorld()) continue;
            Location at = p.getLocation();
            double dx = at.getX() - run.exitPoint.getX(), dz = at.getZ() - run.exitPoint.getZ();
            if (dx * dx + dz * dz <= r * r && Math.abs(at.getY() - run.exitPoint.getY()) <= 3) removeMember(run, id, "클리어", true);
        }
        if (run.ended) return;
        if (now >= run.exitAt) {
            for (UUID id : List.copyOf(run.members)) removeMember(run, id, "클리어", true);
            if (!run.ended) closeCleared(run);
            return;
        }
        if (now - run.lastSync >= 5000) syncAll(run);
    }

    /** 클리어한 판 닫기 (모두 나간 뒤): 몹 주인 표시 지우고 칸 정리 (남은 보상 아이템도 이때 치워짐). */
    private void closeCleared(Run run) {
        if (run.ended) return;
        run.ended = true;
        for (UUID id : List.copyOf(run.members)) { runs.remove(id); busy.remove(id); }
        for (UUID mob : run.mobs.keySet()) mobOwner.remove(mob);
        Slot slot = run.slot;
        Instances.toResetting(slot);
        resetLater(slot, dungeons.get(slot.dungeon));
    }

    private void teleportOurs(Player p, Location to) {
        ourTeleport.add(p.getUniqueId());
        try { p.teleport(to, PlayerTeleportEvent.TeleportCause.PLUGIN); } finally { ourTeleport.remove(p.getUniqueId()); }
    }

    /** 마을(포탈 앞)로. 죽어 있으면 부활 자리로 잡아 둔다. 던전 월드 밖이면 그대로 둔다. */
    private void toExit(Player p, Defs.Dungeon d) {
        Location exit = exitOf(d);
        if (p.isDead()) { respawnTo.put(p.getUniqueId(), exit); return; }
        if (d != null && p.getWorld().getName().equals(d.world)) teleportOurs(p, exit);
    }

    /** 관전으로 (이전 모드는 플레이어 데이터에 적어 둔다: 튕겨도 다음 접속 때 돌려놓음). */
    private void spectate(Player p) {
        var pdc = p.getPersistentDataContainer();
        if (!pdc.has(spectateKey, PersistentDataType.STRING))
            pdc.set(spectateKey, PersistentDataType.STRING, (p.getGameMode() == GameMode.SPECTATOR ? GameMode.SURVIVAL : p.getGameMode()).name());
        p.setGameMode(GameMode.SPECTATOR);
    }

    /** 관전 전 모드로 되돌림 (관전 표시가 없으면 아무 일 없음). */
    void restoreMode(Player p) {
        var pdc = p.getPersistentDataContainer();
        String before = pdc.get(spectateKey, PersistentDataType.STRING);
        if (before == null) return;
        pdc.remove(spectateKey);
        GameMode mode;
        try { mode = GameMode.valueOf(before); } catch (IllegalArgumentException e) { mode = GameMode.SURVIVAL; }
        if (p.getGameMode() == GameMode.SPECTATOR) p.setGameMode(mode);
    }
    // [기능:파티던전 끝]

    private void beginStage(Run run, Defs.Dungeon d, Defs.Slot def, int index) {
        run.stage = index;
        run.progress = 0;
        var st = d.stages.get(index);
        Player p = anchor(run);
        if (p != null) for (String c : st.startCommands) Bukkit.dispatchCommand(Bukkit.getConsoleSender(), c.replace("{player}", p.getName()));
        if (st.type.equals("boss")) spawnBoss(run, def, st);
        if (st.type.equals("room") && run.roomsCleared.contains(st.target)) { completeStage(run, d, def); return; }
        syncAll(run);
    }

    private void spawnBoss(Run run, Defs.Slot def, Defs.Stage st) {
        var boss = def.bosses.get(st.target);
        Location at = boss == null ? null : boss.at.location();
        if (at == null) { plugin.getLogger().warning(run.slot.dungeon + " " + run.slot.no + "번 칸 보스 '" + st.target + "' 자리가 없음"); return; }
        // [기능:파티던전] 이로치 보스: boss-shiny-chance 확률로 "<몹>_shiny" (그런 미몹이 있을 때만)
        String mob = boss.mob;
        if (java.util.concurrent.ThreadLocalRandom.current().nextDouble() < plugin.bossShinyChance() && hooks.mobNames().contains(mob + "_shiny")) {
            mob = mob + "_shiny";
            plugin.getLogger().info("이로치 보스 등장 " + mob + " run=" + run.id);
            notifyRun(run, "system", "dungeon:shiny", "이로치 보스가 나타났어요!", "평소와 다른 빛을 띠고 있어요.");
        }
        Entity e = hooks.spawn(mob, at);
        if (e == null) return;
        track(run, e, "#boss");
        run.boss = e.getUniqueId();
    }

    private void triggerRoom(Run run, Defs.Dungeon d, Defs.Slot def, Defs.Room room, World w) {
        run.roomsTriggered.add(room.name);
        int spawned = 0;
        for (var z : room.zones) {
            var spots = safeSpots(w, z.box(), z.count);
            // [기능:던전안전소환] 몹 구역을 잘못 잡아 안전한 자리가 모자라면 방 전체에서 마저 찾는다 (그래도 없으면 덜 소환)
            var roomBox = room.box();
            if (spots.size() < z.count && roomBox != null) spots.addAll(safeSpots(w, roomBox, z.count - spots.size()));
            for (Location l : spots) {
                Entity e = hooks.spawn(z.mob, l);
                if (e != null) { track(run, e, room.name); spawned++; }
            }
            if (spots.size() < z.count) plugin.getLogger().warning(run.slot.dungeon + " " + run.slot.no + "번 칸 방 " + room.name + ": 몹 구역에 안전한 땅이 모자라 "
                    + spots.size() + "/" + z.count + "마리만 소환");
        }
        run.roomTotals.put(room.name, spawned);
        if (spawned == 0) { roomCleared(run, d, def, room.name); return; }
        syncAll(run);
    }

    /**
     * 상자 안에서 발밑이 단단하고 몸이 들어갈 자리를 무작위로 count 곳 (같은 칸 겹침 허용 안 함).
     * [기능:던전안전소환] 2026-10-31 오너: 벽에 끼어 소환 → 가운데 칸뿐 아니라 둘레 8칸도 발·머리·머리 위(3칸 높이)가 비어 있어야 하고,
     * 둘레 바닥도 꺼져 있지 않아야 한다 (큰 모델 몹이 벽·천장에 박히지 않게).
     */
    static List<Location> safeSpots(World w, Defs.Box box, int count) {
        var out = new ArrayList<Location>();
        var rng = java.util.concurrent.ThreadLocalRandom.current();
        var used = new HashSet<Long>();
        int tries = count * 40;
        while (out.size() < count && tries-- > 0) {
            int x = rng.nextInt(box.minX(), box.maxX() + 1), z = rng.nextInt(box.minZ(), box.maxZ() + 1);
            // 상자 아래쪽부터 위로: 처음 만나는 "단단한 바닥 + 주변까지 빈 세 칸"
            for (int y = box.minY(); y <= box.maxY(); y++) {
                var feet = w.getBlockAt(x, y, z);
                if (!feet.isPassable() || !feet.getRelative(org.bukkit.block.BlockFace.UP).isPassable() || feet.isLiquid()) continue;
                var floor = feet.getRelative(org.bukkit.block.BlockFace.DOWN);
                if (!floor.getType().isSolid()) continue;
                if (!roomy(w, x, y, z)) continue;
                long k = ((long) x << 40) ^ ((long) y << 20) ^ z;
                if (used.add(k)) out.add(new Location(w, x + .5, y, z + .5, rng.nextFloat() * 360, 0));
                break;
            }
        }
        return out;
    }

    // [기능:던전안전소환 시작]
    /** (x,y,z) 둘레 3×3 기둥의 y·y+1·y+2 가 모두 지나갈 수 있고(물·용암 아님), 둘레 바닥 아래가 낭떠러지(2칸 이상 빔)가 아님. */
    private static boolean roomy(World w, int x, int y, int z) {
        for (int dx = -1; dx <= 1; dx++)
            for (int dz = -1; dz <= 1; dz++) {
                for (int dy = 0; dy <= 2; dy++) {
                    var b = w.getBlockAt(x + dx, y + dy, z + dz);
                    if (!b.isPassable() || b.isLiquid()) return false;
                }
                if (!w.getBlockAt(x + dx, y - 1, z + dz).getType().isSolid() && !w.getBlockAt(x + dx, y - 2, z + dz).getType().isSolid()) return false;
            }
        return true;
    }
    // [기능:던전안전소환 끝]

    private void track(Run run, Entity e, String room) {
        e.setPersistent(false);
        if (e instanceof LivingEntity le) le.setRemoveWhenFarAway(false);
        e.addScoreboardTag("chaca_dungeon");
        run.mobs.put(e.getUniqueId(), room);
        mobOwner.put(e.getUniqueId(), run);
        scaleHealth(run, e); // [기능:던전난이도]
    }

    private void roomCleared(Run run, Defs.Dungeon d, Defs.Slot def, String room) {
        if (!run.roomsCleared.add(room)) return;
        if (room.equals(run.lockedRoom)) {
            run.lockedRoom = null;
            notifyRun(run, "system", "dungeon:room-clear", "봉인이 풀렸어요", "다음 구역으로 이동할 수 있어요.");
        }
        var st = run.stage >= 0 && run.stage < d.stages.size() ? d.stages.get(run.stage) : null;
        if (st != null && st.type.equals("room") && st.target.equals(room)) completeStage(run, d, def);
        else syncAll(run);
    }

    /** 몹 죽음 (EntityDeathEvent, 아직 살아 있는 상태에서 이름을 읽는다). */
    void mobDied(Entity e, Player killer) {
        Run owner = mobOwner.remove(e.getUniqueId());
        String name = null;
        if (owner != null && !owner.ended) {
            String room = owner.mobs.remove(e.getUniqueId());
            if (owner.exitAt > 0) return; // [기능:파티던전] 클리어 뒤 남은 몹
            var d = dungeons.get(owner.slot.dungeon);
            var def = d == null ? null : d.slots.get(owner.slot.no);
            if (d != null && def != null) {
                name = hooks.mobName(e);
                countKill(owner, d, def, name);
                if (owner.ended || owner.exitAt > 0) return;
                if ("#boss".equals(room) && e.getUniqueId().equals(owner.boss)) {
                    owner.boss = null;
                    owner.bossFell = e.getLocation(); // [기능:파티던전] 보상 떨어뜨릴 자리
                    var st = owner.stage >= 0 ? d.stages.get(owner.stage) : null;
                    if (st != null && st.type.equals("boss")) completeStage(owner, d, def);
                } else if (room != null && !room.isEmpty() && !room.startsWith("#") && owner.roomAlive(room) == 0) roomCleared(owner, d, def, room);
                else syncAll(owner);
            }
            return;
        }
        // 맵에 원래 있던 몹 (스포너 등): 그 칸 구역 안에서 죽었으면 그 칸의 판에 센다
        for (var run : liveRuns()) {
            if (run.ended || run.startedAt == 0 || run.exitAt > 0) continue;
            var d = dungeons.get(run.slot.dungeon);
            var def = d == null ? null : d.slots.get(run.slot.no);
            var region = def == null ? null : def.region();
            if (region == null || !d.world.equals(e.getWorld().getName()) || !region.contains(e.getLocation())) continue;
            if (name == null) name = hooks.mobName(e);
            countKill(run, d, def, name);
            return;
        }
    }

    private void countKill(Run run, Defs.Dungeon d, Defs.Slot def, String mob) {
        if (run.stage < 0 || run.stage >= d.stages.size()) return;
        var st = d.stages.get(run.stage);
        if (!st.type.equals("kill")) return;
        if (!st.target.equals("*") && !st.target.equalsIgnoreCase(mob)) return;
        run.progress++;
        if (run.progress >= st.count) completeStage(run, d, def);
        else syncAll(run);
    }

    private void completeStage(Run run, Defs.Dungeon d, Defs.Slot def) {
        if (run.ended || run.exitAt > 0) return;
        // 이 단계가 끝나면 열 문
        if (run.stage >= 0 && run.stage < d.stages.size()) for (String name : d.stages.get(run.stage).openDoors) openDoor(run, name, true);
        if (run.stage + 1 >= d.stages.size()) { cleared(run, d, def); return; }
        notifyRun(run, "system", "dungeon:stage", "단계 완료", d.stages.get(run.stage).name);
        beginStage(run, d, def, run.stage + 1);
    }

    /** 문 열기 (배리어 걷기) + 홀로그램 갱신. 이미 열렸으면 아무 일 없음. */
    private void openDoor(Run run, String name, boolean announce) {
        String k = Doors.key(run.slot.dungeon, run.slot.no, name);
        if (!doors.closed(k)) return;
        doors.open(k);
        for (Player p : online(run)) {
            if (announce) {
                p.playSound(p.getLocation(), org.bukkit.Sound.BLOCK_IRON_DOOR_OPEN, 0.8f, 0.7f);
                ChacaLink.notify(p, "system", "dungeon:door", "문이 열렸어요", name, 0);
            }
            sendDoors(run, p);
        }
    }

    /** 이 판의 닫힌 문 상자 → 클라이언트 홀로그램 (JSON). run=null 이면 비움. */
    void sendDoors(Run run, Player p) {
        var json = new com.google.gson.JsonObject();
        var list = new com.google.gson.JsonArray();
        var d = run == null ? null : dungeons.get(run.slot.dungeon);
        var def = d == null ? null : d.slots.get(run.slot.no);
        World w = d == null ? null : Bukkit.getWorld(d.world);
        if (def != null && w != null) {
            json.addProperty("dim", w.getKey().toString());
            for (var door : def.doors.values()) {
                if (!doors.closed(Doors.key(run.slot.dungeon, run.slot.no, door.name))) continue;
                list.add(Editor.box(door.box(), door.name));
            }
        }
        json.add("doors", list);
        send(p, new DungeonProtocol.JsonMsg(DungeonProtocol.K_DOORS, json.toString()));
    }

    // ------------------------------------------------------------ 끝

    // [기능:파티던전 시작]
    /**
     * 마지막 단계 완료 = 클리어. 기록 시각을 여기서 멈추고, 기본·확률 보상은 보스 자리에 한 벌 떨어뜨리고(파티당),
     * 첫 클리어 보상은 사람마다 우편함으로. 출구 포탈을 열고 exit-seconds 뒤 남은 사람을 모두 내보낸다.
     * 기록은 사람마다 따로 (파티원 모두 같은 시간). 쓰러져 관전 중이던 사람도 클리어로 친다 (되살려 살아 있는 사람 곁으로).
     */
    private void cleared(Run run, Defs.Dungeon d, Defs.Slot def) {
        if (run.ended || run.exitAt > 0) return;
        long now = now();
        if (run.startedAt == 0) run.startedAt = now;
        run.cleared = true;
        run.endedAt = now;
        long timeMs = run.elapsed(now);
        run.exitAt = now + plugin.exitSeconds() * 1000L;
        run.outSince.clear();
        Player any = anchor(run);
        Location fell = run.bossFell != null ? run.bossFell : any != null ? any.getLocation() : run.arrival;
        var exitPoint = def.points.containsKey("출구") ? def.points.get("출구") : def.points.get("exit");
        Location exitAt = exitPoint == null ? null : exitPoint.location();
        run.exitPoint = exitAt != null ? exitAt : fell;
        // 관전 중이던 사람 되살리기
        for (UUID id : List.copyOf(run.dead)) {
            Player p = Bukkit.getPlayer(id);
            if (p == null) continue;
            restoreMode(p);
            if (!p.isDead() && any != null && any != p) teleportOurs(p, any.getLocation());
        }
        run.dead.clear();
        // 남은 문은 모두 열기 (출구까지 걸어갈 수 있게)
        for (var door : def.doors.values()) openDoor(run, door.name, false);
        // 기본 + 확률 보상 → 보스 자리 (파티당 한 벌)
        var drops = new ArrayList<ItemStack>(build(d.base(run.hard))); // [기능:던전난이도] 난이도별 보상
        int won = 0;
        var rng = java.util.concurrent.ThreadLocalRandom.current();
        for (var r : d.random(run.hard))
            if (rng.nextDouble(100) < r.chance()) { var got = build(List.of(r)); if (!got.isEmpty()) { drops.addAll(got); won++; } }
        if (fell != null && fell.getWorld() != null) {
            Location at = fell.clone().add(0, 0.6, 0);
            for (ItemStack it : drops) {
                Item item = fell.getWorld().dropItem(at, it.clone());
                item.setPickupDelay(10);
                item.setUnlimitedLifetime(true);
                item.setCanMobPickup(false);
                item.setVelocity(new org.bukkit.util.Vector(rng.nextDouble(-0.12, 0.12), 0.25, rng.nextDouble(-0.12, 0.12)));
            }
        } else if (!drops.isEmpty()) {
            // 떨어뜨릴 자리를 모름: 파티장에게 지급 (가방·우편)
            hooks.give(run.leader, drops, "dungeon-clear:" + run.id, d.name + " 클리어 보상", "던전 클리어 보상이에요.");
        }
        final int wonCount = won;
        var droppedBytes = new ArrayList<byte[]>();
        for (ItemStack it : drops) droppedBytes.add(it.serializeAsBytes());
        notifyRun(run, "system", "dungeon:clear", "보스를 처치했어요!",
                (drops.isEmpty() ? "" : "보상이 보스 자리에 떨어졌어요 · ") + "출구 포탈로 나가거나 " + plugin.exitSeconds() + "초 뒤 마을로 돌아가요.");
        for (Player p : online(run)) { sendPortals(p); syncRun(run, p); }
        plugin.getLogger().info("던전 클리어 " + run.slot.dungeon + " " + run.slot.no + "번 칸 " + timeMs + "ms " + run.members.size() + "명 run=" + run.id);
        bestCache.remove(d.recordKey(run.hard));
        for (UUID member : List.copyOf(run.members)) {
            if (!records.ready()) { delivered(run, d, member, timeMs, null, droppedBytes, wonCount); continue; }
            records.clear(memberRun(run, member), d.recordKey(run.hard), d.recordKey(!run.hard), member, run.slot.no, timeMs, run.startedAt, now) // [기능:던전난이도]
                    .whenComplete((outcome, error) -> Bukkit.getScheduler().runTask(plugin, () -> {
                        if (error != null) plugin.getLogger().severe("클리어 기록 실패 run=" + run.id + " player=" + member + ": " + error);
                        delivered(run, d, member, timeMs, error == null ? outcome : null, droppedBytes, wonCount);
                    }));
        }
    }

    /** 한 사람의 클리어 처리: 첫 클리어 보상(우편함)·클리어 명령·결과창(나갈 때). outcome=null 이면 DB 를 못 써서 첫 클리어를 모름. */
    private void delivered(Run run, Defs.Dungeon d, UUID member, long timeMs, Records.Clear outcome, List<byte[]> dropped, int won) {
        if (outcome != null && outcome.duplicate()) { plugin.getLogger().warning("이미 처리한 클리어 run=" + run.id + " player=" + member); return; }
        if (outcome != null) bestCache.put(d.recordKey(run.hard), outcome.best());
        boolean first = outcome != null && outcome.firstClear();
        String note = dropped.isEmpty() ? (d.random(run.hard).isEmpty() && d.base(run.hard).isEmpty() ? "설정된 기본 보상 없음" : "이번에는 떨어진 보상이 없어요")
                : "보상은 보스가 쓰러진 자리에 떨어졌어요" + (won > 0 ? " (확률 보상 " + won + "개 당첨)" : "");
        var given = new ArrayList<byte[]>(dropped);
        if (first) {
            var firstItems = build(d.first(run.hard)); // [기능:던전난이도]
            if (!firstItems.isEmpty()) {
                for (ItemStack it : firstItems) given.add(it.serializeAsBytes());
                note += " · 첫 클리어 보상은 우편함으로 보냈어요";
                String key = "dungeon-first:" + memberRun(run, member);
                String title = d.name + " 첫 클리어 보상";
                PartyLink.mail(key, member, title, "던전을 처음 클리어한 보상이에요.", firstItems).whenComplete((id, error) -> {
                    if (error == null) return;
                    Bukkit.getScheduler().runTask(plugin, () -> {
                        plugin.getLogger().warning("첫 클리어 보상 우편 실패 → 보상 전달로 대신 player=" + member + " run=" + run.id + ": " + error);
                        hooks.give(member, firstItems, key, title, "던전을 처음 클리어한 보상이에요.");
                    });
                });
            }
        }
        if (outcome == null) note += " · 기록 저장 실패";
        String account = Bukkit.getOfflinePlayer(member).getName();
        if (account != null)
            for (String c : d.clearCommands) {
                try { Bukkit.dispatchCommand(Bukkit.getConsoleSender(), c.replace("{player}", account)); }
                catch (RuntimeException e) { plugin.getLogger().warning("클리어 명령 실패 '" + c + "': " + e); }
            }
        var res = new DungeonProtocol.Result(true, d.name, "", timeMs, outcome == null ? -1 : outcome.previousBestMs(),
                outcome == null ? Holder.NONE : outcome.best(), outcome != null && outcome.newPersonal(), outcome != null && outcome.newServer(),
                first, note, given);
        Player p = Bukkit.getPlayer(member);
        if (run.has(member) && !run.ended) run.results.put(member, res); // 나갈 때 보여 줌
        else if (p != null) result(p, res);                              // 이미 나갔음
    }
    // [기능:파티던전 끝]

    /**
     * 판 실패로 끝 (포기·사망·전멸·끊김·이탈·운영). 모두 포탈 앞(exit)으로, 칸은 정리 뒤 다음 사람에게.
     * cleared=true 는 예전 호출 호환용: 클리어는 cleared() 가 처리한다. 출구가 열린 뒤 불리면 결과 그대로 내보내고 정리.
     * immediate = 플러그인 종료 중 (다음 틱이 없으니 바로 정리).
     */
    void finish(Run run, boolean cleared, String reason, boolean immediate) {
        if (run.ended) return;
        if (cleared) {
            var d = dungeons.get(run.slot.dungeon);
            var def = d == null ? null : d.slots.get(run.slot.no);
            if (d != null && def != null) { cleared(run, d, def); return; }
        }
        run.ended = true;
        long now = now();
        boolean wasCleared = run.exitAt > 0;
        if (!wasCleared) run.endedAt = now;
        long timeMs = run.elapsed(now);
        var d = dungeons.get(run.slot.dungeon);
        String dungeonName = d == null ? run.slot.dungeon : d.name;
        for (UUID id : List.copyOf(run.members)) {
            runs.remove(id);
            busy.remove(id);
            Player p = Bukkit.getPlayer(id);
            if (p == null) continue;
            restoreMode(p);
            send(p, new DungeonProtocol.RunMsg(DungeonProtocol.Run.NONE));
            sendDoors(null, p);
            sendPortals(p);
            toExit(p, d);
            if (wasCleared) { var r = run.results.remove(id); if (r != null) result(p, r); continue; }
            result(p, new DungeonProtocol.Result(false, dungeonName, reason, timeMs, -1, Holder.NONE, false, false, false, "보상 없음", List.of()));
        }
        for (UUID mob : run.mobs.keySet()) mobOwner.remove(mob);
        Slot slot = run.slot;
        Instances.toResetting(slot);
        if (immediate) reset(slot, d); else resetLater(slot, d);
        plugin.getLogger().info("던전 끝 " + run.leader + " " + slot.dungeon + " " + slot.no + "번 칸 " + (wasCleared ? "클리어 뒤 정리" : "실패(" + reason + ")") + " run=" + run.id);
        if (!wasCleared) for (UUID id : run.members) records.logEnd(memberRun(run, id), slot.dungeon, id, slot.no, reason, run.startedAt, now);
    }

    private void result(Player p, DungeonProtocol.Result r) {
        if (p.isDead()) { pendingResult.put(p.getUniqueId(), r); return; }
        send(p, new DungeonProtocol.ResultMsg(r));
    }

    Location exitOf(Defs.Dungeon d) {
        Location l = d == null || d.exit == null ? null : d.exit.location();
        if (l == null && d != null && d.portal != null) l = d.portal.location();
        return l != null ? l : Bukkit.getWorlds().get(0).getSpawnLocation();
    }

    private void release(Slot slot) {
        Instances.toResetting(slot);
        resetLater(slot, dungeons.get(slot.dungeon));
    }

    private void resetLater(Slot slot, Defs.Dungeon d) {
        Bukkit.getScheduler().runTask(plugin, () -> reset(slot, d));
    }

    /** 칸 정리: 소환 몹·떨어진 아이템·경험치·투사체·남은 적대 몹 제거 + 구역 안 기믹 초기화. 성공해야 FREE. */
    void reset(Slot slot, Defs.Dungeon d) {
        if (slot.state != State.RESETTING && slot.state != State.BROKEN) return;
        try {
            var def = d == null ? null : d.slots.get(slot.no);
            World w = d == null ? null : Bukkit.getWorld(d.world);
            if (def != null && w != null && def.region() != null) {
                clean(w, def.region(), null);
                hooks.resetGimmicks(w, def.region());
            }
            doors.openSlot(slot.dungeon, slot.no);
            slot.state = State.FREE;
            slot.broken = "";
        } catch (Exception e) {
            slot.state = State.BROKEN;
            slot.broken = e.toString();
            plugin.getLogger().severe(slot.dungeon + " " + slot.no + "번 칸 정리 실패 → 배정 안 함 (/던전관리 칸초기화): " + e);
        }
    }

    private void clean(World w, Defs.Box box, Run keep) {
        for (Entity e : w.getNearbyEntities(box.bounds())) {
            if (e instanceof Player) continue;
            if (keep != null && keep.mobs.containsKey(e.getUniqueId())) continue;
            boolean ours = e.getScoreboardTags().contains("chaca_dungeon");
            boolean loose = e instanceof Item || e instanceof ExperienceOrb || e instanceof Projectile;
            boolean hostile = e instanceof Monster && !e.hasMetadata("NPC");
            if (ours || loose || hostile) {
                mobOwner.remove(e.getUniqueId());
                e.remove();
            }
        }
    }

    // ------------------------------------------------------------ 이벤트 (DungeonListener 가 부름)

    void quit(Player p) {
        UUID id = p.getUniqueId();
        near.remove(id);
        pingTimes.remove(id);
        if (plugin.getConfig().getBoolean("cancel-queue-on-quit", true)) {
            Slot freed = instances.leave(id);
            if (freed != null) release(freed);
        }
        var a = acceptOf.get(id); // [기능:파티던전]
        if (a != null) cancelAccept(a, nick(id) + "님이 접속을 끊었어요");
        Run run = runs.get(id);
        if (run != null) {
            restoreMode(p);
            if (!run.landed.contains(id) && !run.arrived) abortMember(run, id, "접속 종료");
            else if (run.exitAt > 0) removeMember(run, id, "클리어", true);
            else memberOut(run, id, "접속 종료", true);
        }
        restoreMode(p);
        pendingResult.remove(id);
    }

    void join(Player p) {
        restoreMode(p); // [기능:파티던전] 관전 중 튕긴 사람
        sendPortals(p);
        var d = dungeonOfWorld(p.getWorld());
        if (d != null && !runs.containsKey(p.getUniqueId()) && !p.hasPermission("chacademi.dungeon.build")) {
            Location exit = exitOf(d);
            Bukkit.getScheduler().runTaskLater(plugin, () -> {
                if (p.isOnline() && !runs.containsKey(p.getUniqueId()) && p.getWorld().getName().equals(d.world)) teleportOurs(p, exit);
            }, 2L);
        }
    }

    void died(Player p) {
        UUID id = p.getUniqueId();
        Run run = runs.get(id);
        if (run == null) return;
        var d = dungeons.get(run.slot.dungeon);
        // [기능:파티던전 시작]
        if (run.exitAt > 0) { removeMember(run, id, "클리어", true); return; }
        if (!run.party || run.members.size() <= 1) { finish(run, false, "사망", false); return; }
        run.dead.add(id);
        run.outSince.remove(id);
        if (run.alive() == 0) { notifyRun(run, "warning", "dungeon:wipe", "파티가 모두 쓰러졌어요", "도전에 실패했어요."); finish(run, false, "파티 전멸", false); return; }
        Player to = anchor(run);
        respawnTo.put(id, to != null && to != p ? to.getLocation() : run.arrival != null ? run.arrival : exitOf(d));
        notifyRun(run, "warning", "dungeon:down", nick(id) + "님이 쓰러졌어요", "관전으로 바뀌어요 · 남은 파티원 " + run.alive() + "명");
        syncAll(run);
        // [기능:파티던전 끝]
    }

    Location respawn(Player p) {
        return respawnTo.remove(p.getUniqueId());
    }

    void respawned(Player p) {
        var r = pendingResult.remove(p.getUniqueId());
        if (r != null) Bukkit.getScheduler().runTaskLater(plugin, () -> { if (p.isOnline()) send(p, new DungeonProtocol.ResultMsg(r)); }, 10L);
        // [기능:파티던전] 쓰러진 파티원: 부활하자마자 관전으로
        Run run = runs.get(p.getUniqueId());
        if (run != null && run.dead.contains(p.getUniqueId()) && !run.ended && run.exitAt == 0)
            Bukkit.getScheduler().runTask(plugin, () -> {
                if (p.isOnline() && run.dead.contains(p.getUniqueId()) && !run.ended && run.exitAt == 0) { spectate(p); syncRun(run, p); }
            });
    }

    /** true = 이 이동을 막아야 함. */
    boolean blockMove(Player p, Location to) {
        Run run = runs.get(p.getUniqueId());
        if (run == null || run.lockedRoom == null) return false;
        var d = dungeons.get(run.slot.dungeon);
        var def = d == null ? null : d.slots.get(run.slot.no);
        var room = def == null ? null : def.rooms.get(run.lockedRoom);
        var box = room == null ? null : room.box();
        if (box == null || box.contains(to)) return false;
        ChacaLink.notify(p, "warning", "dungeon:locked-room", "출구가 봉인되어 있어요", "이 방의 몬스터를 모두 처치해 주세요.", 4000);
        return true;
    }

    /** 순간이동: 우리 것이 아니면 봉인 방 탈출을 막고, 던전 월드를 벗어나면 판에서 뺀다. true = 취소. */
    boolean teleport(Player p, Location to) {
        UUID id = p.getUniqueId();
        if (ourTeleport.contains(id)) return false;
        Run run = runs.get(id);
        if (run == null) return false;
        var d = dungeons.get(run.slot.dungeon);
        if (d == null) return false;
        if (to.getWorld() != null && to.getWorld().getName().equals(d.world)) {
            // [기능:파티던전] 관전자는 칸 구역 밖으로 순간이동 못 함
            if (run.dead.contains(id)) {
                var def = d.slots.get(run.slot.no);
                var region = def == null ? null : def.region();
                if (region != null && !region.contains(to)) return true;
            }
            return blockMove(p, to);
        }
        if (run.dead.contains(id)) return true; // [기능:파티던전] 관전자가 다른 월드로 (관전 메뉴) → 막음
        if (!run.landed.contains(id)) return false;
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (!run.has(id) || run.ended) return;
            if (run.exitAt > 0) removeMember(run, id, "클리어", false);
            else memberOut(run, id, "던전 이탈", false);
        });
        return false;
    }

    // [기능:파티던전 시작]
    // ------------------------------------------------------------ 파티 입장 (수락)

    /** 파티장이 "파티 입장": 인원·서버·권한·입장권·빈 칸 확인 → 모두에게 수락 창. */
    private void startParty(Player leader, Defs.Dungeon d) {
        UUID id = leader.getUniqueId();
        var party = PartyLink.party(id);
        if (party.isEmpty() || party.get().members().size() != d.partySize) {
            ChacaLink.notify(leader, "warning", "dungeon:party-size", d.name, "파티원 " + d.partySize + "명이 함께해야 입장할 수 있어요.", 3000);
            return;
        }
        if (!party.get().leader().equals(id)) { ChacaLink.notify(leader, "warning", "dungeon:party-leader", d.name, "파티장만 입장을 시작할 수 있어요.", 3000); return; }
        var members = new ArrayList<Player>();
        for (UUID m : party.get().members()) {
            Player mp = Bukkit.getPlayer(m);
            String who = nick(m);
            if (mp == null) { ChacaLink.notify(leader, "warning", "dungeon:party-away", d.name, who + "님이 같은 서버에 없어요.", 3000); return; }
            if (runs.containsKey(m) || busy.contains(m) || acceptOf.containsKey(m) || mp.isDead()) { ChacaLink.notify(leader, "warning", "dungeon:party-busy", d.name, who + "님은 지금 입장할 수 없어요.", 3000); return; }
            if (instances.queuedDungeon(m) != null) { ChacaLink.notify(leader, "warning", "dungeon:party-queued", d.name, who + "님이 다른 던전 대기 중이에요.", 3000); return; }
            if (!allowed(mp, d)) { ChacaLink.notify(leader, "warning", "dungeon:party-locked", d.name, who + "님은 아직 들어갈 수 없어요. " + d.lockMessage, 3000); return; }
            members.add(mp);
        }
        if (instances.firstFree(d.id) == null) { ChacaLink.notify(leader, "warning", "dungeon:full", "빈 자리가 없어요", "잠시 뒤 다시 시도해 주세요.", 3000); return; }
        busy.add(id);
        boolean hard = hardPick.getOrDefault(id, false); // [기능:던전난이도] 파티장이 고른 난이도
        var futures = new ArrayList<CompletableFuture<Records.Personal>>();
        for (Player mp : members) futures.add(personal(d, hard, mp.getUniqueId()).exceptionally(e -> Records.Personal.NONE));
        CompletableFuture.allOf(futures.toArray(CompletableFuture[]::new)).whenComplete((ok, error) -> Bukkit.getScheduler().runTask(plugin, () -> {
            busy.remove(id);
            if (!leader.isOnline() || runs.containsKey(id) || acceptOf.containsKey(id)) return;
            var a = new AcceptCheck(session(), d.id, id, party.get().members(), now() + plugin.acceptSeconds() * 1000L);
            a.hard = hard; // [기능:던전난이도]
            for (int i = 0; i < members.size(); i++) {
                Player mp = members.get(i);
                int need = ticketNeed(d, futures.get(i).join());
                if (!mp.isOnline() || acceptOf.containsKey(mp.getUniqueId()) || runs.containsKey(mp.getUniqueId())) {
                    ChacaLink.notify(leader, "warning", "dungeon:party-busy", d.name, nick(mp.getUniqueId()) + "님은 지금 입장할 수 없어요.", 3000);
                    return;
                }
                if (need > 0 && countTickets(mp, d.ticketItem) < need) {
                    ChacaLink.notify(leader, "warning", "dungeon:party-ticket", "입장권이 부족해요", nick(mp.getUniqueId()) + "님의 " + itemName(d.ticketItem) + "이(가) 부족해요.", 3000);
                    if (mp != leader) ChacaLink.notify(mp, "warning", "dungeon:ticket", "입장권이 부족해요", itemName(d.ticketItem) + " " + need + "장이 필요해요.", 3000);
                    return;
                }
                a.need.put(mp.getUniqueId(), need);
                a.state.put(mp.getUniqueId(), mp == leader ? 1 : 0);
            }
            accepts.put(a.session, a);
            for (UUID m : a.members) acceptOf.put(m, a);
            plugin.getLogger().info("파티 입장 수락 시작 " + leader.getName() + " → " + d.id + " " + a.members);
            broadcastAccept(a, 0);
            for (Player mp : members) {
                var n = near.get(mp.getUniqueId());
                if (n != null && n.dungeon().equals(d.id)) openEntry(mp, d, n.session());
            }
        }));
    }

    private void broadcastAccept(AcceptCheck a, int state) {
        var d = dungeons.get(a.dungeon);
        var list = new ArrayList<DungeonProtocol.AcceptMember>();
        for (UUID m : a.members) list.add(new DungeonProtocol.AcceptMember(m, nick(m), a.state.getOrDefault(m, 0)));
        var msg = new DungeonProtocol.AcceptMsg(new DungeonProtocol.Accept(a.session, d == null ? a.dungeon : d.name, state, Math.max(0, a.deadline - now()), list));
        for (UUID m : a.members) { Player p = Bukkit.getPlayer(m); if (p != null) send(p, msg); }
    }

    private void acceptAnswer(Player p, int session, boolean yes) {
        UUID id = p.getUniqueId();
        var a = accepts.get(session);
        if (a == null || !a.members.contains(id) || acceptOf.get(id) != a) return;
        if (!yes) { a.state.put(id, 2); cancelAccept(a, nick(id) + "님이 거절했어요"); return; }
        a.state.put(id, 1);
        broadcastAccept(a, 0);
        for (UUID m : a.members) if (a.state.getOrDefault(m, 0) != 1) return;
        launchParty(a);
    }

    private void acceptTick(long now) {
        for (var a : List.copyOf(accepts.values())) if (now >= a.deadline) cancelAccept(a, "시간 안에 모두 수락하지 않았어요");
    }

    private void endAccept(AcceptCheck a) {
        accepts.remove(a.session);
        for (UUID m : a.members) acceptOf.remove(m, a);
    }

    private void cancelAccept(AcceptCheck a, String why) {
        if (accepts.get(a.session) != a) return;
        endAccept(a);
        broadcastAccept(a, 2);
        var d = dungeons.get(a.dungeon);
        for (UUID m : a.members) {
            Player p = Bukkit.getPlayer(m);
            if (p == null) continue;
            ChacaLink.notify(p, "warning", "dungeon:party-cancel", "파티 입장이 취소됐어요", why, 0);
            var n = near.get(m);
            if (d != null && n != null && n.dungeon().equals(d.id)) openEntry(p, d, n.session());
        }
        plugin.getLogger().info("파티 입장 취소 " + a.dungeon + " (" + why + ")");
    }

    /** 모두 수락 → 다시 확인(접속·파티·입장권·빈 칸) → 입장권 걷고 같은 칸으로. 한 명이라도 안 되면 걷은 것 돌려주고 취소. */
    private void launchParty(AcceptCheck a) {
        var d = dungeons.get(a.dungeon);
        if (d == null) { cancelAccept(a, "던전 설정이 바뀌었어요"); return; }
        var party = PartyLink.party(a.leader);
        if (party.isEmpty() || !new HashSet<>(party.get().members()).equals(new HashSet<>(a.members))) { cancelAccept(a, "파티 구성이 바뀌었어요"); return; }
        var players = new ArrayList<Player>();
        for (UUID m : a.members) {
            Player p = Bukkit.getPlayer(m);
            if (p == null || p.isDead() || runs.containsKey(m)) { cancelAccept(a, nick(m) + "님이 입장할 수 없어요"); return; }
            int need = a.need.getOrDefault(m, 0);
            if (need > 0 && countTickets(p, d.ticketItem) < need) { cancelAccept(a, nick(m) + "님의 입장권이 부족해요"); return; }
            players.add(p);
        }
        Slot slot = instances.firstFree(d.id);
        if (slot == null) { cancelAccept(a, "빈 자리가 없어요"); return; }
        Location start = startOf(d, slot);
        if (start == null) { endAccept(a); broadcastAccept(a, 2); brokenSlot(d, slot, players); return; }
        // 입장권: 한 사람씩 걷고, 실패하면 이미 걷은 것을 돌려준다
        var taken = new LinkedHashMap<UUID, Integer>();
        for (Player p : players) {
            int need = a.need.getOrDefault(p.getUniqueId(), 0);
            if (need > 0 && !takeTickets(p, d.ticketItem, need)) {
                for (var e : taken.entrySet()) {
                    if (e.getValue() <= 0) continue;
                    ItemStack back = items.create(d.ticketItem, e.getValue());
                    if (back != null) { back.setAmount(e.getValue()); hooks.give(e.getKey(), List.of(back), "dungeon-refund:" + a.session + ":" + e.getKey(), "던전 입장권 반환", "파티 입장이 취소돼 입장권을 돌려드려요."); }
                }
                cancelAccept(a, nick(p.getUniqueId()) + "님의 입장권이 부족해요");
                return;
            }
            taken.put(p.getUniqueId(), need);
        }
        endAccept(a);
        broadcastAccept(a, 1);
        var run = new Run(a.leader, slot, taken.getOrDefault(a.leader, 0), d.ticketItem);
        run.party = true;
        run.hard = a.hard; // [기능:던전난이도]
        for (Player p : players) { run.members.add(p.getUniqueId()); run.tickets.put(p.getUniqueId(), taken.getOrDefault(p.getUniqueId(), 0)); }
        slot.state = State.RESERVED; slot.holder = a.leader; slot.run = run;
        for (Player p : players) {
            UUID m = p.getUniqueId();
            runs.put(m, run);
            busy.add(m);
            near.remove(m);
            send(p, new DungeonProtocol.CloseMsg());
        }
        plugin.getLogger().info("파티 입장 " + a.members + " → " + d.id + (a.hard ? " 하드" : " 이지") + " " + slot.no + "번 칸 run=" + run.id);
        for (Player p : players) travel(run, p, start);
    }

    // ------------------------------------------------------------ 핑 (파티원끼리, 큐디치와 같은 3번/5초)

    private void ping(Player p, String json) {
        UUID id = p.getUniqueId();
        Run run = runs.get(id);
        if (run == null || !run.party || run.ended || !run.arrived) return;
        long now = now();
        var q = pingTimes.computeIfAbsent(id, k -> new ArrayDeque<>());
        while (!q.isEmpty() && now - q.peekFirst() >= 5000) q.pollFirst();
        if (q.size() >= 3) return;
        int kind; float x, y, z; UUID target = null;
        try {
            var o = com.google.gson.JsonParser.parseString(json).getAsJsonObject();
            kind = o.get("k").getAsInt();
            x = o.get("x").getAsFloat(); y = o.get("y").getAsFloat(); z = o.get("z").getAsFloat();
            if (o.has("t") && !o.get("t").isJsonNull()) target = UUID.fromString(o.get("t").getAsString());
        } catch (RuntimeException bad) { return; }
        if (kind < 0 || kind > DungeonProtocol.PING_MAX || !Float.isFinite(x) || !Float.isFinite(y) || !Float.isFinite(z)) return;
        Location at = p.getLocation();
        if (Math.abs(x - at.getX()) > 256 || Math.abs(y - at.getY()) > 256 || Math.abs(z - at.getZ()) > 256) return;
        q.addLast(now);
        var msg = new DungeonProtocol.PingMsg(new DungeonProtocol.Ping(kind, id, nick(id), x, y, z, target));
        for (Player m : online(run)) send(m, msg);
    }
    // [기능:파티던전 끝]

    // ------------------------------------------------------------ 입장권·보상 아이템

    int countTickets(Player p, String id) {
        int n = 0;
        for (ItemStack it : p.getInventory().getStorageContents())
            if (it != null && id.equals(items.idOf(it))) n += it.getAmount();
        return n;
    }

    /** need 장을 걷는다. 모자라면 아무것도 걷지 않고 false. */
    private boolean takeTickets(Player p, String id, int need) {
        if (countTickets(p, id) < need) return false;
        var inv = p.getInventory();
        ItemStack[] contents = inv.getStorageContents();
        int left = need;
        for (int i = 0; i < contents.length && left > 0; i++) {
            ItemStack it = contents[i];
            if (it == null || !id.equals(items.idOf(it))) continue;
            int take = Math.min(left, it.getAmount());
            left -= take;
            if (take == it.getAmount()) contents[i] = null; else it.setAmount(it.getAmount() - take);
        }
        inv.setStorageContents(contents);
        try { p.saveData(); } catch (RuntimeException e) { plugin.getLogger().warning("입장권 차감 저장 실패 " + p.getName() + ": " + e); }
        return true;
    }

    List<ItemStack> build(List<Defs.Reward> rewards) {
        var out = new ArrayList<ItemStack>();
        for (var r : rewards) {
            ItemStack base = null;
            try {
                if (!r.data().isBlank()) base = ItemStack.deserializeBytes(Base64.getDecoder().decode(r.data()));
                else if (r.item().startsWith("minecraft:")) {
                    Material m = Material.matchMaterial(r.item());
                    if (m != null && m.isItem()) base = new ItemStack(m);
                } else base = items.create(r.item(), 1);
            } catch (RuntimeException e) { plugin.getLogger().warning("보상 아이템 읽기 실패 " + r + ": " + e); }
            if (base == null) { plugin.getLogger().warning("보상 아이템 없음: " + r.item()); continue; }
            int amount = r.amount() * Math.max(1, base.getAmount());
            int max = Math.max(1, base.getMaxStackSize());
            while (amount > 0) {
                ItemStack it = base.clone();
                it.setAmount(Math.min(max, amount));
                amount -= it.getAmount();
                out.add(it);
            }
        }
        return out;
    }

    // ------------------------------------------------------------ 보내기

    boolean clientReady(Player p) { return p.getListeningPluginChannels().contains(DungeonProtocol.S2C); }

    void send(Player p, DungeonProtocol.ToClient m) {
        if (!clientReady(p)) return;
        p.sendPluginMessage(plugin, DungeonProtocol.S2C, DungeonProtocol.encode(m));
    }

    /** 클라 모드가 던전 채널을 등록함 (접속 직후): 포탈 홀로그램·진행 중인 판·대기 상태를 다시 보낸다. */
    void clientJoined(Player p) {
        sendPortals(p);
        Run run = runs.get(p.getUniqueId());
        if (run != null && run.arrived) { syncRun(run, p); sendDoors(run, p); }
        if (instances.queuedDungeon(p.getUniqueId()) != null) sendQueue(p);
    }

    void sendPortals(Player p) {
        var list = new ArrayList<DungeonProtocol.Portal>();
        for (var d : dungeons.values()) {
            if (d.portal == null) continue;
            World w = Bukkit.getWorld(d.portal.world());
            if (w == null) continue;
            list.add(new DungeonProtocol.Portal(w.getKey().toString(), d.portal.x(), d.portal.y(), d.portal.z(), (float) d.portalRadius,
                    d.portalLabel.isBlank() ? d.name : d.portalLabel));
        }
        // [기능:파티던전] 보스를 잡은 판의 출구 포탈 (그 판 사람에게만)
        Run run = runs.get(p.getUniqueId());
        if (run != null && run.exitAt > 0 && run.exitPoint != null && run.exitPoint.getWorld() != null)
            list.add(new DungeonProtocol.Portal(run.exitPoint.getWorld().getKey().toString(), run.exitPoint.getX(), run.exitPoint.getY(), run.exitPoint.getZ(),
                    (float) plugin.exitRadius(), "출구"));
        send(p, new DungeonProtocol.PortalsMsg(list));
    }

    void syncRun(Run run, Player p) {
        run.lastSync = now();
        var d = dungeons.get(run.slot.dungeon);
        if (d == null) return;
        String stageName = "", goal = "도전 준비 중 — 움직이면 시작해요";
        int cur = 0, req = 0;
        if (run.exitAt > 0) { stageName = "클리어"; goal = "출구 포탈로 나가세요"; cur = 1; req = 1; }
        else if (run.stage >= 0 && run.stage < d.stages.size()) {
            var st = d.stages.get(run.stage);
            stageName = st.name;
            goal = st.goal;
            switch (st.type) {
                case "kill" -> { cur = run.progress; req = st.count; }
                case "room" -> {
                    if (run.roomsTriggered.contains(st.target)) {
                        int total = run.roomTotals.getOrDefault(st.target, 0);
                        req = total;
                        cur = Math.max(0, total - run.roomAlive(st.target));
                    }
                }
                default -> { cur = 0; req = 1; }
            }
        }
        UUID id = p.getUniqueId();
        long now = run.lastSync;
        Long out = run.outSince.get(id);
        long warn = out == null ? -1 : Math.max(0, plugin.leaveGraceSeconds() * 1000L - (now - out));
        long exit = run.exitAt > 0 ? Math.max(0, run.exitAt - now) : -1;
        send(p, new DungeonProtocol.RunMsg(new DungeonProtocol.Run(true, d.name + (run.hard ? " · 하드" : " · 이지"), run.elapsed(now), run.startedAt > 0, run.exitAt > 0, // [기능:던전난이도] HUD 이름에 난이도
                Math.max(1, Math.min(Math.max(1, d.stages.size()), run.stage + 1)), d.stages.size(), stageName, goal, cur, req, run.lockedRoom != null,
                run.party, id.equals(run.leader), run.dead.contains(id), run.alive(), run.members.size(), exit, warn)));
    }

    /** 플러그인 종료: 진행 중인 판은 전부 실패(클리어한 판은 결과 그대로), 플레이어는 포탈 앞으로, 칸 정리. */
    void shutdown() {
        for (var a : List.copyOf(accepts.values())) cancelAccept(a, "서버 정비");
        for (var run : liveRuns()) {
            if (!run.arrived) {
                for (UUID id : List.copyOf(run.members)) abortMember(run, id, "서버 정비");
                continue;
            }
            finish(run, false, "서버 정비", true);
        }
        for (Player p : Bukkit.getOnlinePlayers()) restoreMode(p);
        for (var d : dungeons.values()) for (var slot : instances.slots(d.id)) if (slot.state != State.FREE) { Instances.toResetting(slot); reset(slot, d); }
    }

    // ------------------------------------------------------------ 운영 (명령)

    List<String> status() {
        var out = new ArrayList<String>();
        for (var d : dungeons.values()) {
            out.add(d.name + " (" + d.id + ")" + (d.partySize > 1 ? " " + d.partySize + "인 파티" : "") + " 사용 " + instances.busy(d.id) + "/" + instances.capacity(d.id) + " · 대기 " + instances.queued(d.id));
            for (var s : instances.slots(d.id)) {
                String who = "";
                if (s.run != null) {
                    var names = new ArrayList<String>();
                    for (UUID m : s.run.members) names.add(Bukkit.getOfflinePlayer(m).getName() + (s.run.dead.contains(m) ? "(관전)" : ""));
                    who = " " + String.join(",", names);
                } else if (s.holder != null) who = " " + Bukkit.getOfflinePlayer(s.holder).getName();
                String extra = s.state == State.BROKEN ? " (" + s.broken + ")" : "";
                if (s.run != null && s.run.exitAt > 0) extra += " 클리어 · 출구 열림";
                else if (s.run != null && s.run.startedAt > 0) extra += " " + (s.run.elapsed(now()) / 1000) + "초, " + (s.run.stage + 1) + "단계";
                out.add("  " + s.no + "번 칸: " + s.state + who + extra);
            }
        }
        return out;
    }

    boolean forceReset(String dungeon, int no) {
        var slot = instances.slot(dungeon, no);
        if (slot == null) return false;
        if (slot.run != null) finish(slot.run, false, "운영진 정리", true);
        else { Instances.toResetting(slot); reset(slot, dungeons.get(dungeon)); }
        return slot.state == State.FREE;
    }

    boolean kick(UUID player) {
        Run run = runs.get(player);
        if (run == null) return false;
        if (run.exitAt > 0) removeMember(run, player, "클리어", true);
        else memberOut(run, player, "운영진 퇴장", true);
        return true;
    }

    Map<String, Integer> busyCounts() {
        var out = new LinkedHashMap<String, Integer>();
        for (var d : dungeons.values()) out.put(d.id, instances.busy(d.id));
        return out;
    }
}
