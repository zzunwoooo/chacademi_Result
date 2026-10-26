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
    }

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
                case IDLE -> wander(npc, s, st, now);
                default -> {
                }
            }
        }
    }

    private void depart(NPC npc, State s, Settings st, long now) {
        Location dest = s.target.location;
        Location cur = currentLocation(npc);
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
        npc.getNavigator().getLocalParameters().range((float) Math.max(32, st.walkRadius + 16));
        npc.getNavigator().getLocalParameters().speedModifier(st.walkSpeed);
        npc.getNavigator().setTarget(next);
        s.legStartedAt = now;
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
                // 길을 못 찾음 → 다음 지점으로 순간이동
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
        if (ground != null) {
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

    private void teleport(NPC npc, Location dest) {
        if (npc.isSpawned()) {
            npc.teleport(dest, PlayerTeleportEvent.TeleportCause.PLUGIN);
        } else {
            npc.spawn(dest);
        }
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
