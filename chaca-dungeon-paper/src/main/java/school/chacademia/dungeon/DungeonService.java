package school.chacademia.dungeon;

import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
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
 */
final class DungeonService {
    private final DungeonPlugin plugin;
    final Hooks hooks;
    final ItemsAdderItems items;
    final Records records;
    final Instances instances = new Instances();
    Map<String, Defs.Dungeon> dungeons = Map.of();

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

    DungeonService(DungeonPlugin plugin, Hooks hooks, ItemsAdderItems items, Records records) {
        this.plugin = plugin; this.hooks = hooks; this.items = items; this.records = records;
        this.doors = new Doors(plugin.getLogger(), new java.io.File(plugin.getDataFolder(), "doors-ledger.yml"));
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
            for (var o : instances.assign(d.id, now, plugin.offerSeconds() * 1000L, id -> Bukkit.getPlayer(id) != null, this::session)) {
                Player p = Bukkit.getPlayer(o.player());
                if (p == null) continue;
                ChacaLink.notify(p, "system", "dungeon:ready", "입장 준비 완료", d.name + "에 자리가 났어요. " + plugin.offerSeconds() + "초 안에 입장해 주세요.", 0);
                sendQueue(p);
            }
        }
        if (ticks % 2 == 0) for (Player p : Bukkit.getOnlinePlayers()) if (instances.queuedDungeon(p.getUniqueId()) != null) sendQueue(p);
        for (var run : List.copyOf(runs.values())) runTick(run, now);
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
        UUID id = p.getUniqueId();
        personal(d.id, id).thenCombine(best(d.id), (mine, best) -> new Object[]{mine, best}).whenComplete((pair, error) -> Bukkit.getScheduler().runTask(plugin, () -> {
            if (!p.isOnline()) return;
            var mine = error == null ? (Records.Personal) pair[0] : Records.Personal.NONE;
            var top = error == null ? (Holder) pair[1] : Holder.NONE;
            if (error != null) plugin.getLogger().warning("기록 읽기 실패: " + error);
            send(p, new DungeonProtocol.EntryMsg(entry(p, d, session, mine, top)));
        }));
    }

    private void refreshOpenEntries() {
        for (var e : List.copyOf(near.entrySet())) {
            Player p = Bukkit.getPlayer(e.getKey());
            var d = dungeons.get(e.getValue().dungeon());
            if (p != null && d != null && !runs.containsKey(p.getUniqueId())) openEntry(p, d, e.getValue().session());
        }
    }

    private DungeonProtocol.Entry entry(Player p, Defs.Dungeon d, int session, Records.Personal mine, Holder top) {
        UUID id = p.getUniqueId();
        int need = ticketNeed(d, mine);
        int button;
        String lock = "";
        String queuedIn = instances.queuedDungeon(id);
        var offer = instances.offer(id);
        if (runs.containsKey(id)) button = DungeonProtocol.BTN_IN_RUN;
        else if (!allowed(p, d)) { button = DungeonProtocol.BTN_LOCKED; lock = d.lockMessage; }
        else if (offer != null && offer.slot().dungeon.equals(d.id)) { button = DungeonProtocol.BTN_READY; session = offer.session(); }
        else if (d.id.equals(queuedIn)) button = DungeonProtocol.BTN_QUEUED;
        else if (queuedIn != null) { button = DungeonProtocol.BTN_LOCKED; lock = "다른 던전 대기열에 등록되어 있어요."; }
        else if (!instances.hasWaiting(d.id) && instances.firstFree(d.id) != null) button = DungeonProtocol.BTN_ENTER;
        else button = DungeonProtocol.BTN_QUEUE;
        boolean firstPending = mine.clears() == 0;
        // 입장창 보상: 묶음별(기본·첫 클리어·확률), 줄마다 아이템 하나(수량 표시). 빈 묶음은 보내지 않는다.
        var groups = new ArrayList<DungeonProtocol.RewardGroup>();
        addGroup(groups, DungeonProtocol.R_BASE, "기본 보상", false, d.baseRewards);
        addGroup(groups, DungeonProtocol.R_FIRST, "첫 클리어 보상", !firstPending, d.firstRewards);
        addGroup(groups, DungeonProtocol.R_RANDOM, "확률 보상", false, d.randomRewards);
        String ticketName = d.ticketItem.isBlank() ? "" : itemName(d.ticketItem);
        int usual = d.ticketItem.isBlank() ? 0 : d.ticketAmount;
        return new DungeonProtocol.Entry(session, d.name, d.subtitle, mine.clears() > 0, mine.bestMs(), top,
                instances.busy(d.id), instances.capacity(d.id), usual, usual == 0 ? 0 : countTickets(p, d.ticketItem), ticketName,
                usual > 0 && need == 0, button, lock, !d.naturalRegen, groups);
    }

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
        return d.freeUntilFirstClear && mine.clears() == 0 ? 0 : d.ticketAmount;
    }

    private java.util.concurrent.CompletableFuture<Records.Personal> personal(String dungeon, UUID id) {
        if (!records.ready()) return java.util.concurrent.CompletableFuture.completedFuture(Records.Personal.NONE);
        return records.personal(dungeon, id);
    }

    private java.util.concurrent.CompletableFuture<Holder> best(String dungeon) {
        Holder cached = bestCache.get(dungeon);
        if (cached != null || !records.ready()) return java.util.concurrent.CompletableFuture.completedFuture(cached == null ? Holder.NONE : cached);
        return records.serverBest(dungeon).thenApply(h -> { Bukkit.getScheduler().runTask(plugin, () -> bestCache.put(dungeon, h)); return h; });
    }

    // ------------------------------------------------------------ 버튼

    void action(Player p, DungeonProtocol.Action a) {
        switch (a.kind()) {
            case DungeonProtocol.C_ENTER -> enter(p, a.session());
            case DungeonProtocol.C_QUEUE_JOIN -> queueJoin(p, a.session());
            case DungeonProtocol.C_QUEUE_LEAVE -> queueLeave(p);
            case DungeonProtocol.C_EDIT -> plugin.editor().action(p, a.json());
            case DungeonProtocol.C_EXIT -> {
                Run run = runs.get(p.getUniqueId());
                if (run != null) finish(run, false, "중도 포기", false);
            }
            default -> { }
        }
    }

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
        if (runs.containsKey(id) || busy.contains(id)) return;
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
        if (busy.contains(id) || runs.containsKey(id)) return;
        var d = dungeonForSession(id, session);
        if (d == null) { expiredWindow(p); return; }
        if (!allowed(p, d)) { ChacaLink.notify(p, "warning", "dungeon:locked", d.name, d.lockMessage, 3000); return; }
        busy.add(id);
        personal(d.id, id).whenComplete((mine, error) -> Bukkit.getScheduler().runTask(plugin, () -> {
            try {
                enterChecked(p, d, session, error == null ? mine : Records.Personal.NONE);
            } finally {
                if (!runs.containsKey(id)) busy.remove(id);
            }
        }));
    }

    private void enterChecked(Player p, Defs.Dungeon d, int session, Records.Personal mine) {
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
        var def = d.slots.get(slot.no);
        Location start = def == null || def.start == null ? null : def.start.location();
        if (start == null) {
            plugin.getLogger().warning(d.id + " " + slot.no + "번 칸 시작 지점이 없음 → 이 칸을 고장으로 둠");
            slot.state = State.BROKEN; slot.broken = "시작 지점 없음"; slot.holder = null;
            ChacaLink.notify(p, "error", "dungeon:setup", "입장하지 못했어요", "던전 준비가 덜 되었어요. 운영진에게 알려 주세요.", 0);
            return;
        }
        if (need > 0 && !takeTickets(p, d.ticketItem, need)) {
            release(slot);
            ChacaLink.notify(p, "warning", "dungeon:ticket", "입장권이 부족해요", itemName(d.ticketItem) + " " + need + "장이 필요해요.", 3000);
            return;
        }
        var run = new Run(id, slot, need, d.ticketItem);
        slot.state = State.RESERVED; slot.holder = id; slot.run = run;
        runs.put(id, run);
        near.remove(id);
        send(p, new DungeonProtocol.CloseMsg());
        send(p, new DungeonProtocol.QueueMsg(new DungeonProtocol.Queue(DungeonProtocol.Q_NONE, "", 0, 0, 0, 0, 0, 0)));
        plugin.getLogger().info("입장 " + p.getName() + " → " + d.id + " " + slot.no + "번 칸 (입장권 " + need + ") run=" + run.id);
        ourTeleport.add(id);
        p.teleportAsync(start, PlayerTeleportEvent.TeleportCause.PLUGIN).whenComplete((ok, error) -> Bukkit.getScheduler().runTask(plugin, () -> {
            ourTeleport.remove(id);
            if (run.ended) return;
            if (error != null || !Boolean.TRUE.equals(ok) || !p.isOnline()) {
                plugin.getLogger().warning("입장 이동 실패 " + p.getName() + " run=" + run.id + " " + error);
                abort(run, "이동 실패");
                return;
            }
            arrive(run, p);
        }));
    }

    /** 이동 전·중 실패: 입장권을 돌려주고 판을 지운다 (기록·결과 없음). */
    private void abort(Run run, String why) {
        if (run.ended) return;
        run.ended = true;
        runs.remove(run.player);
        busy.remove(run.player);
        if (run.ticketsTaken > 0) {
            ItemStack back = items.create(run.ticketItem, run.ticketsTaken);
            if (back != null) {
                back.setAmount(run.ticketsTaken);
                hooks.give(run.player, List.of(back), "dungeon-refund:" + run.id, "던전 입장권 반환", "입장하지 못해 입장권을 돌려드려요.");
            } else plugin.getLogger().severe("입장권 반환 실패 (아이템 없음) player=" + run.player + " run=" + run.id + " " + run.ticketItem + " x" + run.ticketsTaken);
        }
        var d = dungeons.get(run.slot.dungeon);
        Player p = Bukkit.getPlayer(run.player);
        if (p != null) ChacaLink.notify(p, "error", "dungeon:abort", "입장하지 못했어요", why + " — 입장권은 돌려드렸어요.", 0);
        records.logEnd(run.id, run.slot.dungeon, run.player, run.slot.no, "abort", 0, now());
        Instances.toResetting(run.slot);
        resetLater(run.slot, d);
    }

    private void arrive(Run run, Player p) {
        run.arrived = true;
        run.arrivedAt = now();
        run.arrival = p.getLocation();
        run.slot.state = State.RUNNING;
        busy.remove(run.player);
        var d = dungeons.get(run.slot.dungeon);
        var def = d == null ? null : d.slots.get(run.slot.no);
        // 앞 판에서 남은 것 한 번 더 정리 (청크가 막 불러와진 뒤)
        if (def != null && def.region() != null) clean(p.getWorld(), def.region(), run);
        // 문: 판 시작 때 전부 닫음 (처음부터 닫힘)
        if (def != null) for (var door : def.doors.values()) doors.close(Doors.key(run.slot.dungeon, run.slot.no, door.name), p.getWorld(), door.box());
        sendDoors(run, p);
        syncRun(run, p);
    }

    // ------------------------------------------------------------ 진행

    private void runTick(Run run, long now) {
        Player p = Bukkit.getPlayer(run.player);
        if (p == null || run.ended || !run.arrived) return;
        var d = dungeons.get(run.slot.dungeon);
        var def = d == null ? null : d.slots.get(run.slot.no);
        if (d == null || def == null) { finish(run, false, "던전 설정이 바뀌었어요", false); return; }
        if (run.startedAt == 0) {
            boolean moved = run.arrival != null && run.arrival.getWorld() == p.getWorld() && p.getLocation().distance(run.arrival) >= plugin.startMoveDistance();
            if (moved || now - run.arrivedAt >= plugin.startFallbackSeconds() * 1000L) {
                run.startedAt = now;
                if (d.stages.isEmpty()) { finish(run, true, "", false); return; }
                beginStage(run, d, def, 0, p);
            }
            if (run.startedAt == 0) return;
        }
        // 기믹과 연결한 문: 그 기믹이 열리면 문도 열림
        for (var door : def.doors.values()) {
            if (door.gimmick.isBlank()) continue;
            String k = Doors.key(run.slot.dungeon, run.slot.no, door.name);
            if (doors.closed(k) && hooks.gimmickOpen(d.world, def.gimmicks.get(door.gimmick))) openDoor(run, p, door.name);
        }
        Location at = p.getLocation();
        for (var room : def.rooms.values()) {
            var box = room.box();
            if (box == null || run.roomsTriggered.contains(room.name) || !box.contains(at)) continue;
            triggerRoom(run, d, def, room, p);
        }
        if (run.stage >= 0 && run.stage < d.stages.size()) {
            var st = d.stages.get(run.stage);
            switch (st.type) {
                case "gimmick" -> { if (hooks.gimmickOpen(d.world, def.gimmicks.get(st.target))) completeStage(run, d, def, p); }
                case "reach" -> {
                    var point = def.points.get(st.target);
                    Location l = point == null ? null : point.location();
                    if (l != null && l.getWorld() == at.getWorld() && l.distance(at) <= st.radius) completeStage(run, d, def, p);
                }
                case "boss" -> {
                    if (run.boss != null) {
                        Entity e = Bukkit.getEntity(run.boss);
                        if (e == null || !e.isValid()) {
                            if (e == null || !e.isDead()) { mobOwner.remove(run.boss); run.mobs.remove(run.boss); run.boss = null; spawnBoss(run, def, st, p); }
                        }
                    }
                }
                default -> { }
            }
        }
        if (!run.ended && now - run.lastSync >= 5000) syncRun(run, p);
    }

    private void beginStage(Run run, Defs.Dungeon d, Defs.Slot def, int index, Player p) {
        run.stage = index;
        run.progress = 0;
        var st = d.stages.get(index);
        for (String c : st.startCommands) Bukkit.dispatchCommand(Bukkit.getConsoleSender(), c.replace("{player}", p.getName()));
        if (st.type.equals("boss")) spawnBoss(run, def, st, p);
        if (st.type.equals("room") && run.roomsCleared.contains(st.target)) { completeStage(run, d, def, p); return; }
        syncRun(run, p);
    }

    private void spawnBoss(Run run, Defs.Slot def, Defs.Stage st, Player p) {
        var boss = def.bosses.get(st.target);
        Location at = boss == null ? null : boss.at.location();
        if (at == null) { plugin.getLogger().warning(run.slot.dungeon + " " + run.slot.no + "번 칸 보스 '" + st.target + "' 자리가 없음"); return; }
        Entity e = hooks.spawn(boss.mob, at);
        if (e == null) return;
        track(run, e, "#boss");
        run.boss = e.getUniqueId();
    }

    private void triggerRoom(Run run, Defs.Dungeon d, Defs.Slot def, Defs.Room room, Player p) {
        run.roomsTriggered.add(room.name);
        int spawned = 0;
        World w = p.getWorld();
        for (var z : room.zones) {
            var spots = safeSpots(w, z.box(), z.count);
            for (Location l : spots) {
                Entity e = hooks.spawn(z.mob, l);
                if (e != null) { track(run, e, room.name); spawned++; }
            }
            if (spots.size() < z.count) plugin.getLogger().warning(run.slot.dungeon + " " + run.slot.no + "번 칸 방 " + room.name + ": 몹 구역에 안전한 땅이 모자라 "
                    + spots.size() + "/" + z.count + "마리만 소환");
        }
        run.roomTotals.put(room.name, spawned);
        if (spawned == 0) { roomCleared(run, d, def, room.name, p); return; }
        syncRun(run, p);
    }

    /** 상자 안에서 발밑이 단단하고 몸(2칸)이 들어갈 자리를 무작위로 count 곳 (같은 칸 겹침 허용 안 함). */
    static List<Location> safeSpots(World w, Defs.Box box, int count) {
        var out = new ArrayList<Location>();
        var rng = java.util.concurrent.ThreadLocalRandom.current();
        var used = new HashSet<Long>();
        int tries = count * 30;
        while (out.size() < count && tries-- > 0) {
            int x = rng.nextInt(box.minX(), box.maxX() + 1), z = rng.nextInt(box.minZ(), box.maxZ() + 1);
            // 상자 아래쪽부터 위로: 처음 만나는 "단단한 바닥 + 빈 두 칸"
            for (int y = box.minY(); y <= box.maxY(); y++) {
                var feet = w.getBlockAt(x, y, z);
                if (!feet.isPassable() || !feet.getRelative(org.bukkit.block.BlockFace.UP).isPassable() || feet.isLiquid()) continue;
                var floor = feet.getRelative(org.bukkit.block.BlockFace.DOWN);
                if (!floor.getType().isSolid()) continue;
                long k = ((long) x << 40) ^ ((long) y << 20) ^ z;
                if (used.add(k)) out.add(new Location(w, x + .5, y, z + .5, rng.nextFloat() * 360, 0));
                break;
            }
        }
        return out;
    }

    private void track(Run run, Entity e, String room) {
        e.setPersistent(false);
        if (e instanceof LivingEntity le) le.setRemoveWhenFarAway(false);
        e.addScoreboardTag("chaca_dungeon");
        run.mobs.put(e.getUniqueId(), room);
        mobOwner.put(e.getUniqueId(), run);
    }

    private void roomCleared(Run run, Defs.Dungeon d, Defs.Slot def, String room, Player p) {
        if (!run.roomsCleared.add(room)) return;
        if (room.equals(run.lockedRoom)) {
            run.lockedRoom = null;
            ChacaLink.notify(p, "system", "dungeon:room-clear", "봉인이 풀렸어요", "다음 구역으로 이동할 수 있어요.", 0);
        }
        var st = run.stage >= 0 && run.stage < d.stages.size() ? d.stages.get(run.stage) : null;
        if (st != null && st.type.equals("room") && st.target.equals(room)) completeStage(run, d, def, p);
        else syncRun(run, p);
    }

    /** 몹 죽음 (EntityDeathEvent, 아직 살아 있는 상태에서 이름을 읽는다). */
    void mobDied(Entity e, Player killer) {
        Run owner = mobOwner.remove(e.getUniqueId());
        String name = null;
        if (owner != null && !owner.ended) {
            String room = owner.mobs.remove(e.getUniqueId());
            Player p = Bukkit.getPlayer(owner.player);
            var d = dungeons.get(owner.slot.dungeon);
            var def = d == null ? null : d.slots.get(owner.slot.no);
            if (p != null && d != null && def != null) {
                name = hooks.mobName(e);
                countKill(owner, d, def, name, p);
                if (owner.ended) return;
                if ("#boss".equals(room) && e.getUniqueId().equals(owner.boss)) {
                    owner.boss = null;
                    var st = owner.stage >= 0 ? d.stages.get(owner.stage) : null;
                    if (st != null && st.type.equals("boss")) completeStage(owner, d, def, p);
                } else if (room != null && !room.isEmpty() && !room.startsWith("#") && owner.roomAlive(room) == 0) roomCleared(owner, d, def, room, p);
                else syncRun(owner, p);
            }
            return;
        }
        // 맵에 원래 있던 몹 (스포너 등): 그 칸 구역 안에서 죽었으면 그 칸의 판에 센다
        for (var run : runs.values()) {
            if (run.ended || run.startedAt == 0) continue;
            var d = dungeons.get(run.slot.dungeon);
            var def = d == null ? null : d.slots.get(run.slot.no);
            var region = def == null ? null : def.region();
            if (region == null || !d.world.equals(e.getWorld().getName()) || !region.contains(e.getLocation())) continue;
            Player p = Bukkit.getPlayer(run.player);
            if (p == null) return;
            if (name == null) name = hooks.mobName(e);
            countKill(run, d, def, name, p);
            return;
        }
    }

    private void countKill(Run run, Defs.Dungeon d, Defs.Slot def, String mob, Player p) {
        if (run.stage < 0 || run.stage >= d.stages.size()) return;
        var st = d.stages.get(run.stage);
        if (!st.type.equals("kill")) return;
        if (!st.target.equals("*") && !st.target.equalsIgnoreCase(mob)) return;
        run.progress++;
        if (run.progress >= st.count) completeStage(run, d, def, p);
        else syncRun(run, p);
    }

    private void completeStage(Run run, Defs.Dungeon d, Defs.Slot def, Player p) {
        if (run.ended) return;
        // 이 단계가 끝나면 열 문
        if (run.stage >= 0 && run.stage < d.stages.size()) for (String name : d.stages.get(run.stage).openDoors) openDoor(run, p, name);
        if (run.stage + 1 >= d.stages.size()) { finish(run, true, "", false); return; }
        ChacaLink.notify(p, "system", "dungeon:stage", "단계 완료", d.stages.get(run.stage).name, 0);
        beginStage(run, d, def, run.stage + 1, p);
    }

    /** 문 열기 (배리어 걷기) + 홀로그램 갱신. 이미 열렸으면 아무 일 없음. */
    private void openDoor(Run run, Player p, String name) {
        String k = Doors.key(run.slot.dungeon, run.slot.no, name);
        if (!doors.closed(k)) return;
        doors.open(k);
        p.playSound(p.getLocation(), org.bukkit.Sound.BLOCK_IRON_DOOR_OPEN, 0.8f, 0.7f);
        ChacaLink.notify(p, "system", "dungeon:door", "문이 열렸어요", name, 0);
        sendDoors(run, p);
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

    /**
     * 판 끝. 클리어면 기록·보상, 아니면 실패 결과만. 플레이어는 포탈 앞(exit)으로, 칸은 정리 뒤 다음 사람에게.
     * immediate = 플러그인 종료 중 (다음 틱이 없으니 바로 정리).
     */
    void finish(Run run, boolean cleared, String reason, boolean immediate) {
        if (run.ended) return;
        run.ended = true;
        run.cleared = cleared;
        long now = now();
        if (run.startedAt == 0) run.startedAt = cleared ? now : 0;
        run.endedAt = now;
        long timeMs = run.elapsed(now);
        runs.remove(run.player);
        busy.remove(run.player);
        var d = dungeons.get(run.slot.dungeon);
        Player p = Bukkit.getPlayer(run.player);
        Location exit = exitOf(d);
        if (p != null) {
            send(p, new DungeonProtocol.RunMsg(DungeonProtocol.Run.NONE));
            sendDoors(null, p);
            if (p.isDead()) respawnTo.put(run.player, exit);
            else if (d != null && p.getWorld().getName().equals(d.world)) {
                ourTeleport.add(run.player);
                try { p.teleport(exit, PlayerTeleportEvent.TeleportCause.PLUGIN); } finally { ourTeleport.remove(run.player); }
            }
        }
        for (UUID mob : run.mobs.keySet()) mobOwner.remove(mob);
        Slot slot = run.slot;
        Instances.toResetting(slot);
        if (immediate) reset(slot, d); else resetLater(slot, d);
        String dungeonName = d == null ? slot.dungeon : d.name;
        plugin.getLogger().info("던전 끝 " + run.player + " " + slot.dungeon + " " + slot.no + "번 칸 " + (cleared ? "클리어 " + timeMs + "ms" : "실패(" + reason + ")") + " run=" + run.id);
        if (!cleared) {
            records.logEnd(run.id, slot.dungeon, run.player, slot.no, reason, run.startedAt, now);
            if (p != null) result(p, new DungeonProtocol.Result(false, dungeonName, reason, timeMs, -1, Holder.NONE, false, false, false, "보상 없음", List.of()));
            return;
        }
        if (!records.ready()) { delivered(run, d, dungeonName, timeMs, null); return; }
        records.clear(run.id, slot.dungeon, run.player, slot.no, timeMs, run.startedAt, now)
                .whenComplete((outcome, error) -> Bukkit.getScheduler().runTask(plugin, () -> {
                    if (error != null) plugin.getLogger().severe("클리어 기록 실패 run=" + run.id + ": " + error);
                    delivered(run, d, dungeonName, timeMs, error == null ? outcome : null);
                }));
    }

    /** 클리어 보상·명령·결과창. outcome=null 이면 DB 를 못 써서 첫 클리어 여부를 모름 → 반복 보상만 (중복 방지는 run 이 한 번만 끝나는 것으로). */
    private void delivered(Run run, Defs.Dungeon d, String dungeonName, long timeMs, Records.Clear outcome) {
        if (outcome != null && outcome.duplicate()) { plugin.getLogger().warning("이미 처리한 클리어 run=" + run.id); return; }
        bestCache.remove(run.slot.dungeon);
        if (outcome != null) bestCache.put(run.slot.dungeon, outcome.best());
        boolean first = outcome != null && outcome.firstClear();
        // 기본(매번) + 첫 클리어(처음 한 번, 기록 DB 를 못 쓰면 모르므로 빼고) + 확률(줄마다 따로 뽑기)
        var rewards = new ArrayList<ItemStack>();
        int won = 0;
        if (d != null) {
            rewards.addAll(build(d.baseRewards));
            if (first) rewards.addAll(build(d.firstRewards));
            var rng = java.util.concurrent.ThreadLocalRandom.current();
            for (var r : d.randomRewards)
                if (rng.nextDouble(100) < r.chance()) { var got = build(List.of(r)); if (!got.isEmpty()) { rewards.addAll(got); won++; } }
        }
        String note = rewards.isEmpty() ? (d != null && !d.randomRewards.isEmpty() ? "이번에는 확률 보상이 나오지 않았어요" : "설정된 보상 없음") : switch (hooks.give(run.player, rewards, "dungeon-clear:" + run.id, dungeonName + " 클리어 보상",
                "던전 클리어 보상이에요.")) {
            case "INVENTORY" -> "가방에 넣었어요";
            case "MAIL" -> "가방이 가득 차서 일부를 우편함으로 보냈어요";
            case "DROPPED" -> "가방이 가득 차서 일부를 발밑에 떨어뜨렸어요";
            default -> "지급하지 못했어요 (운영진에게 알려 주세요)";
        };
        if (won > 0) note = "확률 보상 " + won + "개 당첨 · " + note;
        if (outcome == null) note += " · 기록 저장 실패";
        String account = Bukkit.getOfflinePlayer(run.player).getName();
        if (d != null && account != null)
            for (String c : d.clearCommands) {
                try { Bukkit.dispatchCommand(Bukkit.getConsoleSender(), c.replace("{player}", account)); }
                catch (RuntimeException e) { plugin.getLogger().warning("클리어 명령 실패 '" + c + "': " + e); }
            }
        var given = new ArrayList<byte[]>();
        for (ItemStack it : rewards) given.add(it.serializeAsBytes());
        Player p = Bukkit.getPlayer(run.player);
        var res = new DungeonProtocol.Result(true, dungeonName, "", timeMs, outcome == null ? -1 : outcome.previousBestMs(),
                outcome == null ? Holder.NONE : outcome.best(), outcome != null && outcome.newPersonal(), outcome != null && outcome.newServer(),
                first, note, given);
        if (p != null) result(p, res);
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
        if (plugin.getConfig().getBoolean("cancel-queue-on-quit", true)) {
            Slot freed = instances.leave(id);
            if (freed != null) release(freed);
        }
        Run run = runs.get(id);
        if (run != null) {
            if (!run.arrived) abort(run, "접속 종료");
            else finish(run, false, "접속 종료", false);
        }
        pendingResult.remove(id);
    }

    void join(Player p) {
        sendPortals(p);
        var d = dungeonOfWorld(p.getWorld());
        if (d != null && !runs.containsKey(p.getUniqueId()) && !p.hasPermission("chacademi.dungeon.build")) {
            Location exit = exitOf(d);
            Bukkit.getScheduler().runTaskLater(plugin, () -> {
                if (p.isOnline() && !runs.containsKey(p.getUniqueId()) && p.getWorld().getName().equals(d.world)) {
                    ourTeleport.add(p.getUniqueId());
                    try { p.teleport(exit, PlayerTeleportEvent.TeleportCause.PLUGIN); } finally { ourTeleport.remove(p.getUniqueId()); }
                }
            }, 2L);
        }
    }

    void died(Player p) {
        Run run = runs.get(p.getUniqueId());
        if (run != null) finish(run, false, "사망", false);
    }

    Location respawn(Player p) {
        return respawnTo.remove(p.getUniqueId());
    }

    void respawned(Player p) {
        var r = pendingResult.remove(p.getUniqueId());
        if (r != null) Bukkit.getScheduler().runTaskLater(plugin, () -> { if (p.isOnline()) send(p, new DungeonProtocol.ResultMsg(r)); }, 10L);
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

    /** 순간이동: 우리 것이 아니면 봉인 방 탈출을 막고, 던전 월드를 벗어나면 실패 처리. true = 취소. */
    boolean teleport(Player p, Location to) {
        UUID id = p.getUniqueId();
        if (ourTeleport.contains(id)) return false;
        Run run = runs.get(id);
        if (run == null) return false;
        var d = dungeons.get(run.slot.dungeon);
        if (d == null) return false;
        if (to.getWorld() != null && to.getWorld().getName().equals(d.world)) return blockMove(p, to);
        if (!run.arrived) return false;
        Bukkit.getScheduler().runTask(plugin, () -> finish(run, false, "던전 이탈", false));
        return false;
    }

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
        send(p, new DungeonProtocol.PortalsMsg(list));
    }

    void syncRun(Run run, Player p) {
        run.lastSync = now();
        var d = dungeons.get(run.slot.dungeon);
        if (d == null) return;
        var def = d.slots.get(run.slot.no);
        String stageName = "", goal = "도전 준비 중 — 움직이면 시작해요";
        int cur = 0, req = 0;
        if (run.stage >= 0 && run.stage < d.stages.size()) {
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
        send(p, new DungeonProtocol.RunMsg(new DungeonProtocol.Run(true, d.name, run.elapsed(run.lastSync), run.startedAt > 0, false,
                Math.max(1, run.stage + 1), d.stages.size(), stageName, goal, cur, req, run.lockedRoom != null)));
    }

    /** 플러그인 종료: 진행 중인 판은 전부 실패, 플레이어는 포탈 앞으로, 칸 정리. */
    void shutdown() {
        for (var run : List.copyOf(runs.values())) {
            if (!run.arrived) { run.ended = true; runs.remove(run.player); }
            else finish(run, false, "서버 정비", true);
        }
        for (var d : dungeons.values()) for (var slot : instances.slots(d.id)) if (slot.state != State.FREE) { Instances.toResetting(slot); reset(slot, d); }
    }

    // ------------------------------------------------------------ 운영 (명령)

    List<String> status() {
        var out = new ArrayList<String>();
        for (var d : dungeons.values()) {
            out.add(d.name + " (" + d.id + ") 사용 " + instances.busy(d.id) + "/" + instances.capacity(d.id) + " · 대기 " + instances.queued(d.id));
            for (var s : instances.slots(d.id)) {
                String who = s.holder == null ? "" : " " + Bukkit.getOfflinePlayer(s.holder).getName();
                String extra = s.state == State.BROKEN ? " (" + s.broken + ")" : "";
                if (s.run != null && s.run.startedAt > 0) extra += " " + (s.run.elapsed(now()) / 1000) + "초, " + (s.run.stage + 1) + "단계";
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
        finish(run, false, "운영진 퇴장", false);
        return true;
    }

    Map<String, Integer> busyCounts() {
        var out = new LinkedHashMap<String, Integer>();
        for (var d : dungeons.values()) out.put(d.id, instances.busy(d.id));
        return out;
    }
}
