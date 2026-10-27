package kr.chacademy.npc.npc;

import kr.chacademy.npc.ChacaNpcPlugin;
import kr.chacademy.npc.config.PlaceRepository;
import kr.chacademy.npc.config.Settings;
import kr.chacademy.npc.core.CharacterSheet;
import kr.chacademy.npc.core.ScheduleEntry;
import net.citizensnpcs.api.npc.NPC;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerTeleportEvent;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.Map;
import java.util.Random;

/**
 * 일과표 이동 + 배회. 1초마다 돈다.
 * 보는 사람이 있으면 중간 지점을 거쳐 걸어가고, 없으면 순간이동한다.
 */
public final class MovementController {

    private enum Mode { IDLE, WAITING, WALKING }

    private static final class State {
        int slot = Integer.MIN_VALUE;
        Mode mode = Mode.IDLE;
        long departAt;
        PlaceRepository.Place target;
        final Deque<Location> route = new ArrayDeque<>();
        long legStartedAt;
        long nextWanderAt;
        long nextWarmCheckAt;
        /** 이번 구간에서 길찾기가 실패해 직선 이동으로 바꿨는지. */
        boolean straight;
        long nextSpreadAt;
    }

    /** 다른 NPC 와 이 거리(칸) 안이면 겹친 것으로 보고 자리를 다시 고른다 (2026-10-27). */
    private static final double CROWD_RADIUS = 1.0;
    /** 서 있는 NPC 의 겹침 검사 간격 (NPC마다, 서버 부하 방지). */
    private static final long SPREAD_CHECK_MS = 20_000L;

    /** 이 거리(칸) 안에 플레이어가 있는 NPC만 버튼 대답을 미리 만든다 (아무도 없는 곳의 NPC에 AI 비용을 쓰지 않는다). */
    private static final double WARMUP_RADIUS = 48;
    private static final long WARMUP_CHECK_MS = 30_000L;

    private final ChacaNpcPlugin plugin;
    private final Map<String, State> states = new HashMap<>();
    private final Random random = new Random();

    public MovementController(ChacaNpcPlugin plugin) {
        this.plugin = plugin;
    }

    public void tick() {
        Settings st = plugin.settings();
        long now = System.currentTimeMillis();
        for (String charId : plugin.npcs().linkedCharacters()) {
            CharacterSheet c = plugin.characters().get(charId);
            NPC npc = plugin.npcs().npcFor(charId);
            if (c == null || npc == null) {
                continue;
            }
            State s = states.computeIfAbsent(charId, k -> new State());
            if (plugin.dialogue().isTalkingWith(npc.getId())) {
                if (npc.isSpawned() && npc.getNavigator().isNavigating()) {
                    npc.getNavigator().cancelNavigation();
                    if (s.mode == Mode.WALKING) {
                        s.mode = Mode.WAITING; // 대화 끝나면 다시 출발
                        s.departAt = now + 2000;
                    }
                }
                continue;
            }
            if (!c.hasSchedule()) {
                continue;
            }
            int slot = plugin.npcs().currentSlot(c);
            if (slot >= 0 && now >= s.nextWarmCheckAt) {
                // 근처에 플레이어가 있을 때만 버튼 대답을 채운다 (같은 NPC·칸은 DialogueService 가 10분에 한 번만 실제로 만든다)
                s.nextWarmCheckAt = now + WARMUP_CHECK_MS;
                if (playerNear(currentLocation(npc), WARMUP_RADIUS)) {
                    plugin.dialogue().warmup(c, slot);
                }
            }
            if (slot != s.slot) {
                boolean first = s.slot == Integer.MIN_VALUE;
                s.slot = slot;
                ScheduleEntry e = c.schedule().get(slot);
                s.target = plugin.places().get(e.place());
                if (s.target == null) {
                    plugin.getLogger().warning("[ChacaNPC] " + c.id() + " 일과표의 장소 '" + e.place() + "'가 places.yml에 없습니다");
                    s.mode = Mode.IDLE;
                    continue;
                }
                s.mode = Mode.WAITING;
                s.departAt = first ? now : now + random.nextInt(Math.max(1, st.staggerSeconds * 1000));
            }
            switch (s.mode) {
                case WAITING -> {
                    if (now >= s.departAt) {
                        depart(npc, s, st, now);
                    }
                }
                case WALKING -> walk(npc, s, st, now);
                case IDLE -> {
                    if (!spread(npc, s, st, now)) {
                        wander(npc, s, st, now);
                    }
                }
                default -> {
                }
            }
        }
    }

    private void depart(NPC npc, State s, Settings st, long now) {
        Location dest = freeSpot(npc, s.target.location, Math.max(2.5, s.target.radius));
        Location cur = currentLocation(npc);
        s.nextSpreadAt = now + SPREAD_CHECK_MS;
        if (cur == null || !npc.isSpawned() || cur.getWorld() != dest.getWorld()
                || (!playerNear(cur, st.walkRadius) && !playerNear(dest, st.walkRadius))) {
            teleport(npc, dest);
            s.mode = Mode.IDLE;
            s.nextWanderAt = now + 5000;
            return;
        }
        s.route.clear();
        // 지금 위치에서 가장 가까운 중간 지점부터 시작
        int startIdx = 0;
        double best = Double.MAX_VALUE;
        int i = 0;
        for (Location w : s.target.waypoints) {
            if (w.getWorld() == cur.getWorld()) {
                double d = w.distanceSquared(cur);
                if (d < best) {
                    best = d;
                    startIdx = i;
                }
            }
            i++;
        }
        for (int j = startIdx; j < s.target.waypoints.size(); j++) {
            s.route.add(s.target.waypoints.get(j));
        }
        s.route.add(dest);
        s.mode = Mode.WALKING;
        nextLeg(npc, s, st, now);
    }

    private void nextLeg(NPC npc, State s, Settings st, long now) {
        Location next = s.route.peekFirst();
        if (next == null) {
            s.mode = Mode.IDLE;
            s.nextWanderAt = now + 3000;
            return;
        }
        Location cur = currentLocation(npc);
        double leg = cur != null && cur.getWorld() == next.getWorld() ? cur.distance(next) : 0;
        npc.getNavigator().getLocalParameters().range((float) Math.max(Math.max(32, st.walkRadius + 16), leg + 16));
        npc.getNavigator().getLocalParameters().speedModifier(st.walkSpeed);
        npc.getNavigator().setTarget(next);
        s.legStartedAt = now;
        s.straight = false;
    }

    private void walk(NPC npc, State s, Settings st, long now) {
        Location cur = currentLocation(npc);
        Location next = s.route.peekFirst();
        if (cur == null || next == null || !npc.isSpawned()) {
            if (s.target != null) {
                teleport(npc, s.target.location);
            }
            s.mode = Mode.IDLE;
            return;
        }
        boolean arrived = cur.getWorld() == next.getWorld() && cur.distanceSquared(next) < 2.5 * 2.5;
        if (arrived || !npc.getNavigator().isNavigating()) {
            if (!arrived && (cur.getWorld() != next.getWorld() || cur.distanceSquared(next) > 4 * 4)) {
                // 길을 못 찾음: 보는 사람이 있으면 직선으로 걸어 본다 (2026-10-27: 눈앞에서 순간이동하던 문제). 그래도 안 되면 / 아무도 없으면 순간이동.
                if (!s.straight && cur.getWorld() == next.getWorld() && (playerNear(cur, st.walkRadius) || playerNear(next, st.walkRadius))) {
                    s.straight = true;
                    npc.getNavigator().getLocalParameters().speedModifier(st.walkSpeed);
                    npc.getNavigator().setStraightLineTarget(next);
                    return;
                }
                teleport(npc, next);
            }
            s.route.pollFirst();
            nextLeg(npc, s, st, now);
            return;
        }
        if (now - s.legStartedAt > st.walkGiveUpSeconds * 1000L) {
            npc.getNavigator().cancelNavigation();
            teleport(npc, s.target.location);
            s.route.clear();
            s.mode = Mode.IDLE;
            s.nextWanderAt = now + 5000;
        }
    }

    private void wander(NPC npc, State s, Settings st, long now) {
        if (s.target == null || s.target.radius < 1.5 || !npc.isSpawned() || now < s.nextWanderAt) {
            return;
        }
        s.nextWanderAt = now + (st.wanderMinSeconds + random.nextInt(Math.max(1, st.wanderMaxSeconds - st.wanderMinSeconds + 1))) * 1000L;
        Location center = s.target.location;
        if (!playerNear(center, st.wanderPlayerRadius) || npc.getNavigator().isNavigating()) {
            return;
        }
        double angle = random.nextDouble() * Math.PI * 2;
        double dist = random.nextDouble() * s.target.radius;
        Location spot = center.clone().add(Math.cos(angle) * dist, 0, Math.sin(angle) * dist);
        Location ground = findGround(spot);
        if (ground != null && !crowded(npc, ground)) {
            npc.getNavigator().getLocalParameters().speedModifier(st.walkSpeed * 0.8f);
            npc.getNavigator().setTarget(ground);
        }
    }

    /** 원래 높이 근처(위 3칸~아래 3칸)에서 서 있을 수 있는 자리. 실내 지붕 위로 가지 않게 한다. */
    private static Location findGround(Location spot) {
        World w = spot.getWorld();
        if (w == null) {
            return null;
        }
        int x = spot.getBlockX();
        int z = spot.getBlockZ();
        int baseY = spot.getBlockY();
        if (!w.isChunkLoaded(x >> 4, z >> 4)) {
            return null;
        }
        for (int dy = 0; dy <= 6; dy++) {
            int y = baseY + (dy % 2 == 0 ? dy / 2 : -(dy + 1) / 2);
            Block below = w.getBlockAt(x, y - 1, z);
            Block feet = w.getBlockAt(x, y, z);
            Block head = w.getBlockAt(x, y + 1, z);
            if (below.getType().isSolid() && feet.isPassable() && head.isPassable() && !feet.isLiquid()) {
                return new Location(w, x + 0.5, y, z + 0.5);
            }
        }
        return null;
    }

    /**
     * 서 있는 NPC 가 다른 NPC 와 1칸 안에 겹쳐 있으면 근처 빈자리로 옮긴다 (NPC마다 20초에 한 번만 검사).
     * @return true = 옮기기 시작함 (이번 차례 배회는 건너뜀)
     */
    private boolean spread(NPC npc, State s, Settings st, long now) {
        if (now < s.nextSpreadAt || !npc.isSpawned() || npc.getNavigator().isNavigating()) {
            return false;
        }
        s.nextSpreadAt = now + SPREAD_CHECK_MS + random.nextInt(5000);
        Location cur = currentLocation(npc);
        if (cur == null || !crowded(npc, cur)) {
            return false;
        }
        double radius = s.target == null ? 2.5 : Math.max(2.5, s.target.radius);
        Location center = s.target != null && s.target.location.getWorld() == cur.getWorld() ? s.target.location : cur;
        Location spot = freeSpot(npc, center, radius);
        if (spot == center || crowded(npc, spot)) {
            return false;
        }
        if (playerNear(cur, st.walkRadius)) {
            npc.getNavigator().getLocalParameters().speedModifier(st.walkSpeed * 0.8f);
            npc.getNavigator().setTarget(spot);
        } else {
            teleport(npc, spot);
        }
        return true;
    }

    /** base 에 다른 NPC 가 없으면 base, 있으면 반경 안에서 빈자리를 8번까지 찾는다 (못 찾으면 base). */
    private Location freeSpot(NPC self, Location base, double radius) {
        if (!crowded(self, base)) {
            return base;
        }
        for (int i = 0; i < 8; i++) {
            double angle = random.nextDouble() * Math.PI * 2;
            double dist = 1.5 + random.nextDouble() * Math.max(0.5, radius - 1.5);
            Location ground = findGround(base.clone().add(Math.cos(angle) * dist, 0, Math.sin(angle) * dist));
            if (ground != null && !crowded(self, ground)) {
                ground.setYaw(base.getYaw());
                ground.setPitch(base.getPitch());
                return ground;
            }
        }
        return base;
    }

    /** self 가 아닌 연결된 NPC 가 loc 에서 1칸 안에 있는지. */
    private boolean crowded(NPC self, Location loc) {
        if (loc == null || loc.getWorld() == null) {
            return false;
        }
        double r2 = CROWD_RADIUS * CROWD_RADIUS;
        for (String charId : plugin.npcs().linkedCharacters()) {
            NPC other = plugin.npcs().npcFor(charId);
            if (other == null || other.getId() == self.getId()) {
                continue;
            }
            Location o = currentLocation(other);
            if (o != null && o.getWorld() == loc.getWorld() && o.distanceSquared(loc) <= r2) {
                return true;
            }
        }
        return false;
    }

    /**
     * 순간이동. 도착 청크가 안 불러와져 있으면 엔티티를 그대로 옮기지 않고 그 자리에 다시 소환한다
     * (2026-10-27: 안 불러온 청크로 옮기면 Citizens 저장 위치가 옛 자리로 남아 /npc tp·길잡이 별이 옛 자리를 가리켰다).
     */
    private void teleport(NPC npc, Location dest) {
        World w = dest.getWorld();
        boolean loaded = w != null && w.isChunkLoaded(dest.getBlockX() >> 4, dest.getBlockZ() >> 4);
        if (npc.isSpawned() && loaded) {
            npc.teleport(dest, PlayerTeleportEvent.TeleportCause.PLUGIN);
            return;
        }
        if (npc.isSpawned()) {
            npc.despawn(net.citizensnpcs.api.event.DespawnReason.PENDING_RESPAWN);
        }
        npc.spawn(dest);
    }

    private static Location currentLocation(NPC npc) {
        if (npc.isSpawned() && npc.getEntity() != null) {
            return npc.getEntity().getLocation();
        }
        return npc.getStoredLocation();
    }

    private static boolean playerNear(Location loc, double radius) {
        if (loc == null || loc.getWorld() == null) {
            return false;
        }
        double r2 = radius * radius;
        for (Player p : loc.getWorld().getPlayers()) {
            if (p.getLocation().distanceSquared(loc) <= r2) {
                return true;
            }
        }
        return false;
    }

    /** /서버관리 새로고침 NPC 후 다시 계산하도록. */
    public void reset() {
        states.clear();
    }
}
