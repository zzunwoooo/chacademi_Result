package school.chacademia.gimmick;

import java.util.*;
import org.bukkit.Bukkit;

/**
 * 2026-10-24 owner: "설치된 블럭끼리 이어져 있으면 연결이 되어야" - no puzzle nodes, no puzzles.yml.
 * Every gimmick does what its kind does, and blocks that touch are wired like redstone:
 *
 *  input  (lever, plate, brazier, monument, seal, pot, valve, turbine, wheel, receiver, pillar, dial, statue)
 *         is on by its own rule (see {@link #selfOn}).
 *  conduit passes the signal on through its ports (models.yml `ports`, turned with the gimmick / 249 도관 회전).
 *  output (door, sluice, bridge, platform, chest, gate, vent) is wired to every input that touches it directly or through
 *         conduits, and opens when all of those inputs are on.
 *         seal doors, chests, light bridges and floating platforms stay open once opened (until /던전관리 초기화);
 *         the timed door opens for timed-door-seconds; sluice, vent and gate follow their inputs.
 *  mirror / prism only take part in 244 빛줄기 (lit while a beam passes through them).
 *
 * Blocks of a gimmick: its footprint columns from the anchor up through its height. Neighbours: the 6 sides.
 */
final class Signals {
    static final Set<String> INPUTS = Set.of("lever", "plate", "brazier", "monument", "seal", "pot", "valve", "turbine", "wheel",
        "receiver", "pillar", "dial", "statue");
    static final Set<String> OUTPUTS = Set.of("door", "sluice", "bridge", "platform", "chest", "gate", "vent");
    /** Kinds whose own on/off is saved (lever pulled, brazier lit, monument awakened, ice thawed, pot grown, door opened ...). */
    static final Set<String> LATCHED = Set.of("lever", "brazier", "monument", "seal", "pot", "door", "chest", "bridge", "platform");
    static final String TIMED_DOOR = "gm_timed_door";

    /** 정답 (rod mode): what each adjustable device has to show. */
    static final List<String> RECEIVER_ANSWERS = List.of("아무 색", "흰색", "빨강", "노랑", "초록", "파랑", "보라");
    static final List<String> PLATFORM_ANSWERS = List.of("앞으로 2칸", "앞으로 4칸", "앞으로 6칸", "앞으로 8칸", "위로 2칸", "위로 4칸", "위로 6칸", "위로 8칸");
    static boolean hasAnswer(String kind) { return Set.of("pillar", "dial", "statue", "receiver", "platform").contains(kind); }
    static int answerCount(String kind) {
        return switch (kind) { case "pillar", "dial", "statue" -> 4; case "receiver" -> RECEIVER_ANSWERS.size(); case "platform" -> PLATFORM_ANSWERS.size(); default -> 1; };
    }
    static int defaultAnswer(String kind, int facing) {
        return switch (kind) { case "pillar" -> 3; case "dial" -> 2; case "statue" -> Math.floorMod(facing + 2, 4); case "platform" -> 1; default -> 0; };
    }
    static String answerLabel(String kind, int answer) {
        return switch (kind) {
            case "pillar" -> "수위 " + answer;
            case "dial" -> "위치 " + answer;
            case "statue" -> DebugTool.FACING[Math.floorMod(answer, 4)] + " 보기";
            case "receiver" -> RECEIVER_ANSWERS.get(Math.floorMod(answer, RECEIVER_ANSWERS.size()));
            case "platform" -> PLATFORM_ANSWERS.get(Math.floorMod(answer, PLATFORM_ANSWERS.size()));
            default -> "-";
        };
    }

    /** Output decision (pure, unit tested): wired to at least one input and all of them on. */
    static boolean allOn(Collection<Boolean> inputs) { return !inputs.isEmpty() && !inputs.contains(Boolean.FALSE); }

    private final GimmickPlugin plugin;
    private long tick;
    private boolean dirty = true;
    private final Map<String, Long> pulseUntil = new HashMap<>();   // valve / turbine / plate weight
    private final Map<String, Long> timedUntil = new HashMap<>();   // timed door
    private final Set<String> timedArmed = new HashSet<>();         // timed door: inputs were all on last time
    private Set<String> standing = Set.of();
    private Set<String> water = Set.of();
    private Map<String, Set<String>> lit = Map.of();                 // receiver id -> beam colours
    private Set<String> beamLit = Set.of();                          // mirrors / prisms a beam passes
    private Map<String, List<String>> wiredInputs = Map.of();        // output id -> input ids
    private Map<String, List<String>> wiredOutputs = Map.of();       // input id -> output ids
    private Set<String> powered = Set.of();                          // conduits carrying a signal from an input that is on
    private final Set<String> shown = new HashSet<>();               // gimmicks currently shown "on"
    private boolean started;

    Signals(GimmickPlugin plugin) {
        this.plugin = plugin;
        Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            tick++;
            boolean expired = pulseUntil.values().removeIf(t -> t <= tick) | timedUntil.values().removeIf(t -> t <= tick);
            if (expired) dirty = true;
            if (dirty && tick % 2 == 0) recompute(true);
        }, 1, 1);
    }

    long now() { return tick; }
    void dirty() { dirty = true; }
    boolean on(Gimmick g) { return shown.contains(g.id); }
    boolean powered(Gimmick g) { return powered.contains(g.id); }
    List<String> inputsOf(Gimmick g) { return wiredInputs.getOrDefault(g.id, List.of()); }
    List<String> outputsOf(Gimmick g) { return wiredOutputs.getOrDefault(g.id, List.of()); }
    /**
     * Where an open teleport gate leads: the gate linked by 250 차원 표식, else the nearest other open gate wired to the same
     * inputs (two gates on one lever / one conduit line are a pair).
     */
    Gimmick partner(Gimmick gate) {
        Gimmick linked = plugin.devices().linked(gate);
        if (linked != null) return linked;
        if (!on(gate)) return null;
        var mine = new HashSet<>(inputsOf(gate));
        if (mine.isEmpty()) return null;
        Gimmick best = null; double bestD = Double.MAX_VALUE;
        for (Gimmick o : plugin.world().all()) {
            if (o == gate || o.attached() || !on(o) || !plugin.catalog().get(o.model).is("gate") || !o.world.equals(gate.world)) continue;
            if (!new HashSet<>(inputsOf(o)).equals(mine)) continue;
            double d = (o.x - gate.x) * (o.x - gate.x) + (o.z - gate.z) * (o.z - gate.z);
            if (d < bestD) { bestD = d; best = o; }
        }
        return best;
    }
    static String role(String kind) { return INPUTS.contains(kind) ? "입력" : OUTPUTS.contains(kind) ? "출력" : kind.equals("conduit") ? "도관" : "장치"; }

    // ------------------------------------------------------------------ inputs from the world
    /** Right click: a lever flips. @return true when something happened. */
    boolean interact(Gimmick g) {
        if (!plugin.catalog().get(g.model).is("lever")) return false;
        plugin.devices().remember(g);
        setLatched(g, !g.latched);
        return true;
    }

    /** A spell of `element` touched the gimmick. @return true when it changed something (the HIT is then consumed). */
    boolean magic(Gimmick g, String element) {
        var info = plugin.catalog().get(g.model);
        return switch (info.kind()) {
            case "brazier" -> element.equals("fire") ? latch(g, true) : element.equals("water") ? latch(g, false) : false;
            case "monument" -> element.equals(elementOf(g.model)) && latch(g, true);
            case "turbine" -> element.equals("wind") && pulse(g, plugin.getConfig().getInt("turbine-seconds", 10));
            default -> false;
        };
    }

    /** Gimmick spells: ignite extinguish thaw steam weight grow. */
    boolean action(Gimmick g, String action) {
        var info = plugin.catalog().get(g.model);
        return switch (action) {
            case "ignite" -> info.is("brazier") && latch(g, true);
            case "extinguish" -> info.is("brazier") && latch(g, false);
            case "thaw" -> info.is("seal") && latch(g, true);
            case "grow" -> info.is("pot") && latch(g, true);
            case "steam" -> info.is("valve") && pulse(g, plugin.getConfig().getInt("valve-seconds", 6));
            case "weight" -> info.is("plate") && pulse(g, plugin.getConfig().getInt("weight-seconds", 10));
            default -> false;
        };
    }

    void standing(Set<String> ids) { if (!ids.equals(standing)) { standing = Set.copyOf(ids); dirty = true; } }
    void water(Set<String> ids) { if (!ids.equals(water)) { water = Set.copyOf(ids); dirty = true; } }
    void light(Map<String, Set<String>> receivers, Set<String> passed) {
        if (receivers.equals(lit) && passed.equals(beamLit)) return;
        lit = Map.copyOf(receivers); beamLit = Set.copyOf(passed); dirty = true;
    }

    private boolean latch(Gimmick g, boolean on) {
        if (g.latched == on) return false;
        plugin.devices().remember(g);
        setLatched(g, on);
        return true;
    }
    void setLatched(Gimmick g, boolean on) { g.latched = on; plugin.world().markDirty(); dirty = true; }
    private boolean pulse(Gimmick g, int seconds) {
        plugin.devices().remember(g);
        pulseUntil.merge(g.id, tick + seconds * 20L, Math::max);
        dirty = true;
        return true;
    }

    /** /던전관리 초기화: back to the start (doors shut, ice frozen, levers up, platforms home). Device poses stay. */
    void reset(Gimmick g) {
        g.latched = false;
        pulseUntil.remove(g.id); timedUntil.remove(g.id); timedArmed.remove(g.id);
        plugin.devices().reset(g);
        plugin.world().markDirty();
        dirty = true;
    }
    void forget(Gimmick g) { pulseUntil.remove(g.id); timedUntil.remove(g.id); timedArmed.remove(g.id); shown.remove(g.id); dirty = true; }

    static String elementOf(String model) {
        for (String e : List.of("fire", "water", "wind", "earth", "light", "dark")) if (model.endsWith("_" + e)) return e;
        return "";
    }

    // ------------------------------------------------------------------ rules
    /** An input's own state. */
    boolean selfOn(Gimmick g) {
        var info = plugin.catalog().get(g.model);
        boolean pulsing = pulseUntil.getOrDefault(g.id, 0L) > tick;
        return switch (info.kind()) {
            case "lever", "brazier", "monument", "seal", "pot" -> g.latched;
            case "plate" -> standing.contains(g.id) || pulsing;
            case "valve", "turbine" -> pulsing;
            case "wheel" -> water.contains(g.id);
            case "receiver" -> {
                var colours = lit.getOrDefault(g.id, Set.of());
                int a = Math.floorMod(g.answer, RECEIVER_ANSWERS.size());
                yield a == 0 ? !colours.isEmpty() : colours.contains(Colors.ORDER.get(a - 1));
            }
            case "pillar" -> g.level == g.answer;
            case "dial" -> g.pos == g.answer;
            case "statue" -> g.facing == Math.floorMod(g.answer, 4);
            default -> false;
        };
    }

    private static final int[][] DIRS = {{1, 0, 0}, {-1, 0, 0}, {0, 1, 0}, {0, -1, 0}, {0, 0, 1}, {0, 0, -1}};

    /** World direction of a conduit port. */
    static int[] port(String name, int facing) {
        int[] f = Gimmick.forward(facing), r = Gimmick.right(facing);
        return switch (name) {
            case "front" -> new int[]{f[0], 0, f[1]}; case "back" -> new int[]{-f[0], 0, -f[1]};
            case "right" -> new int[]{r[0], 0, r[1]}; case "left" -> new int[]{-r[0], 0, -r[1]};
            case "up" -> new int[]{0, 1, 0}; case "down" -> new int[]{0, -1, 0};
            default -> null;
        };
    }

    /** Walk from one input through touching blocks and conduits: outputs reached + conduits on the way. */
    private void trace(Gimmick source, Set<String> outputs, Set<String> conduits) {
        var world = plugin.world();
        var visited = new HashSet<String>();
        var queue = new ArrayDeque<int[]>(); // x, y, z, dx, dy, dz (direction from the block back toward the sender)
        var info = plugin.catalog().get(source.model);
        int base = (int) Math.floor(source.y + 1e-6);
        for (int[] c : source.columns(info)) for (int l = 0; l < source.layers(info); l++)
            for (int[] d : DIRS) queue.add(new int[]{c[0] + d[0], base + l + d[1], c[1] + d[2], -d[0], -d[1], -d[2]});
        int guard = 0;
        while (!queue.isEmpty() && guard++ < 20000) {
            int[] q = queue.poll();
            Gimmick g = world.at(source.world, q[0], q[1], q[2]);
            if (g == null || g == source) continue;
            var gi = plugin.catalog().get(g.model);
            if (OUTPUTS.contains(gi.kind())) { outputs.add(g.id); continue; }
            if (!gi.is("conduit")) continue; // another input or a light device: the signal stops there
            boolean accepts = false;
            for (String p : gi.ports()) { int[] d = port(p, g.facing); if (d != null && d[0] == q[3] && d[1] == q[4] && d[2] == q[5]) { accepts = true; break; } }
            if (!accepts || !visited.add(g.id + ":" + q[3] + ":" + q[4] + ":" + q[5])) continue;
            conduits.add(g.id);
            for (String p : gi.ports()) {
                int[] d = port(p, g.facing);
                if (d == null || d[0] == q[3] && d[1] == q[4] && d[2] == q[5]) continue;
                queue.add(new int[]{q[0] + d[0], q[1] + d[1], q[2] + d[2], -d[0], -d[1], -d[2]});
            }
        }
    }

    /** Rebuild the wiring and every gimmick's state; `animate` = play transitions for what changed. */
    void recompute(boolean animate) {
        dirty = false;
        var world = plugin.world();
        var inputsOf = new HashMap<String, List<String>>();
        var outputsOf = new HashMap<String, List<String>>();
        var nextPowered = new HashSet<String>();
        var selfOn = new HashMap<String, Boolean>();
        for (Gimmick g : world.all()) {
            if (g.attached() || !INPUTS.contains(plugin.catalog().get(g.model).kind())) continue;
            boolean on = selfOn(g);
            selfOn.put(g.id, on);
            var outs = new LinkedHashSet<String>(); var conduits = new HashSet<String>();
            trace(g, outs, conduits);
            for (String o : outs) inputsOf.computeIfAbsent(o, k -> new ArrayList<>()).add(g.id);
            if (!outs.isEmpty()) outputsOf.put(g.id, List.copyOf(outs));
            if (on) nextPowered.addAll(conduits);
        }
        wiredInputs = inputsOf; wiredOutputs = outputsOf; powered = Set.copyOf(nextPowered);

        for (Gimmick g : world.all()) {
            if (g.attached()) continue;
            var info = plugin.catalog().get(g.model);
            String kind = info.kind();
            boolean want;
            if (INPUTS.contains(kind)) want = selfOn.getOrDefault(g.id, false);
            else if (kind.equals("conduit")) want = powered.contains(g.id);
            else if (kind.equals("mirror") || kind.equals("prism")) want = beamLit.contains(g.id);
            else if (OUTPUTS.contains(kind)) {
                var ins = inputsOf.getOrDefault(g.id, List.of());
                boolean all = allOn(ins.stream().map(i -> selfOn.getOrDefault(i, false)).toList());
                if (g.model.equals(TIMED_DOOR)) {
                    if (all && timedArmed.add(g.id)) timedUntil.put(g.id, tick + plugin.getConfig().getInt("timed-door-seconds", 8) * 20L);
                    if (!all) timedArmed.remove(g.id);
                    want = timedUntil.getOrDefault(g.id, 0L) > tick && started;
                } else if (LATCHED.contains(kind)) {
                    if (all && !g.latched) { g.latched = true; world.markDirty(); }
                    want = g.latched;
                } else want = all || kind.equals("gate") && plugin.devices().linked(g) != null;
            } else want = false;
            if (want != shown.contains(g.id)) {
                if (want) shown.add(g.id); else shown.remove(g.id);
                if (animate) world.display(g, want);
            }
        }
        started = true;
        if (plugin.debug()) plugin.getLogger().info("[신호] 출력 " + inputsOf.size() + "개 연결, 도관 " + powered.size() + "개 흐름");
    }
}
