package school.magiccodex.paper;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import java.io.File;
import java.util.*;
import org.bukkit.Bukkit;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.permissions.PermissionAttachment;
import org.bukkit.scheduler.BukkitTask;
import school.magiccodex.protocol.ManaProtocol;
import school.magiccodex.protocol.PreviewProtocol;

/**
 * /테스트 마법 자동시전 (오픈 전 메모 #28): 현재 활성 마법 전체를 정해진 간격(기본 5초)으로 하나씩 운영자 본인이 시전한다.
 *
 * <ul>
 *   <li>목록 = mana-spells.yml 의 enabled: true 항목 (서버가 실제로 시전을 받아 주는 마법, {@link ManaSpells#order}).
 *       순서·번호는 PortableVFX spell-catalog.yml 의 number(도감 번호)가 있으면 그 순서, 없으면 파일 순서.</li>
 *   <li>시전은 플레이어가 /마법 으로 쓴 것과 같은 {@link ManaBridge} 경로(MagicCastAttemptEvent·빗자루 제한·보호 구역·
 *       SpellRuntime/PortableVFX·발견 카운트·아티팩트 시전 효과 전부)를 탄다. 대상은 운영자 시선(앞의 몹/지점), SELF 마법은 본인.</li>
 *   <li>검수 중에는 마나·쿨타임·배움 권한만 우회한다: 시전 직전에 그 마법 쿨을 지우고 최대 마나를 잠깐 올려 가득 채우고,
 *       배움 권한을 잠깐 붙인다. 시전 직후 마나·최대 마나·쿨·권한을 원래대로 돌린다.</li>
 *   <li>스케줄러는 실행 중인 검수가 있을 때만 1초 주기 작업 하나 (운영자가 여럿이어도 하나).</li>
 * </ul>
 * config.yml (선택): spell-review.skip: [마법 ID…] — 검수에서 뺄 마법. 없으면 전체.
 */
final class SpellReviewRunner implements AutoCloseable {
    private static final String MODIFIER = "magiccodex:spell_review";
    private static final int DEFAULT_INTERVAL = 5;

    private final MagicCodexBridge plugin;
    private final ManaBridge bridge;
    private final Map<UUID, Run> runs = new LinkedHashMap<>();
    private BukkitTask task;

    record Entry(String id, String name, int number) {}

    private static final class Run {
        final List<Entry> list;
        int next, interval = DEFAULT_INTERVAL, countdown;
        boolean paused;
        final List<String> failed = new ArrayList<>();
        int ok;
        Run(List<Entry> list, int start) { this.list = list; this.next = start; this.countdown = 1; }
    }

    SpellReviewRunner(MagicCodexBridge plugin, ManaBridge bridge) {
        this.plugin = plugin;
        this.bridge = bridge;
    }

    // ------------------------------------------------------------------ 목록

    private Map<String, Integer> numbers = Map.of();
    private long numbersStamp = Long.MIN_VALUE;

    /** PortableVFX spell-catalog.yml 의 도감 번호. 파일이 바뀌었을 때만 다시 읽는다. */
    private Map<String, Integer> numbers() {
        var catalog = new File(plugin.getDataFolder().getParentFile(), "PortableVFX/spell-catalog.yml");
        long stamp = catalog.isFile() ? catalog.lastModified() : -1;
        if (stamp == numbersStamp) return numbers;
        var out = new HashMap<String, Integer>();
        if (stamp >= 0) {
            try {
                var root = YamlConfiguration.loadConfiguration(catalog).getConfigurationSection("spells");
                if (root != null) for (String id : root.getKeys(false)) {
                    int n = root.getInt(id + ".number", -1);
                    if (n >= 0) out.put(id, n);
                }
            } catch (RuntimeException ignored) { }
        }
        numbers = Map.copyOf(out);
        numbersStamp = stamp;
        return numbers;
    }

    /** 활성 마법 목록 (도감 번호 순). */
    private List<Entry> entries() {
        var numbers = numbers();
        var skip = new HashSet<>(plugin.getConfig().getStringList("spell-review.skip"));
        var names = bridge.registeredSpellCatalog();
        var out = new ArrayList<Entry>();
        for (String id : bridge.reviewOrder()) {
            if (skip.contains(id)) continue;
            var info = names.get(id);
            out.add(new Entry(id, info == null ? id : info.getOrDefault("name", id), numbers.getOrDefault(id, -1)));
        }
        // 도감 번호가 있는 것은 번호 순, 없는 것은 뒤에 파일 순서대로 (안정 정렬).
        out.sort(Comparator.comparingInt(e -> e.number() < 0 ? Integer.MAX_VALUE : e.number()));
        return out;
    }

    private static String label(Entry e, int index, int total) {
        return "[" + (index + 1) + "/" + total + "] " + (e.number() >= 0 ? e.number() + "번 " : "") + e.name() + " (" + e.id() + ")";
    }

    /** 시작점: 도감 번호, 목록 순번(도감 번호가 없을 때), 마법 ID, 마법 이름. */
    static int find(List<Entry> list, String input) throws CommandSyntaxException {
        String key = input.replace(" ", "");
        if (key.matches("\\d{1,4}")) {
            int n = Integer.parseInt(key);
            for (int i = 0; i < list.size(); i++) if (list.get(i).number() == n) return i;
            boolean numbered = list.stream().anyMatch(e -> e.number() >= 0);
            if (!numbered && n >= 1 && n <= list.size()) return n - 1;
            throw ChacaApi.error(n + "번 마법이 검수 목록에 없어요");
        }
        for (int i = 0; i < list.size(); i++) {
            var e = list.get(i);
            if (e.id().equals(key) || e.name().replace(" ", "").equals(key)) return i;
        }
        throw ChacaApi.error(input + " 마법이 검수 목록에 없어요");
    }

    // ------------------------------------------------------------------ 명령

    void register() {
        var root = Commands.literal("자동시전")
            .executes(this::status)
            .then(Commands.literal("시작").executes(ctx -> start(ctx, null))
                .then(Commands.argument("시작점", ChacaApi.word(this::startChoices)).executes(ctx -> start(ctx, ctx.getArgument("시작점", String.class)))))
            .then(Commands.literal("정지").executes(ctx -> stop(CodexCommands.self(ctx), true)))
            .then(Commands.literal("일시정지").executes(ctx -> pause(ctx, true)))
            .then(Commands.literal("재개").executes(ctx -> pause(ctx, false)))
            .then(Commands.literal("건너뛰기").executes(this::skip))
            .then(Commands.literal("간격").then(Commands.argument("초", IntegerArgumentType.integer(1, 60)).executes(this::interval)))
            .then(Commands.literal("상태").executes(this::status));
        ChacaApi.branch(plugin, ChacaApi.테스트, Commands.literal("마법").then(root),
            "활성 마법 전체를 5초 간격으로 하나씩 자동 시전 (마나·쿨 무시)", "마법 자동시전 시작");
    }

    private Collection<String> startChoices() {
        var out = new ArrayList<String>();
        for (var e : entries()) { out.add(e.id()); String n = e.name().replace(" ", ""); if (!n.isEmpty()) out.add(n); }
        return out;
    }

    private int start(CommandContext<CommandSourceStack> ctx, String from) throws CommandSyntaxException {
        Player p = CodexCommands.self(ctx);
        if (runs.containsKey(p.getUniqueId())) throw ChacaApi.error("이미 자동 시전 검수 중이에요. 먼저 '정지'해 주세요");
        var list = entries();
        if (list.isEmpty()) throw ChacaApi.error("검수할 활성 마법이 없어요");
        int index = from == null ? 0 : find(list, from);
        runs.put(p.getUniqueId(), new Run(list, index));
        ensureTask();
        ChacaApi.ok(p, "자동 시전 검수 시작: " + (list.size() - index) + "개, " + DEFAULT_INTERVAL + "초 간격");
        return Command.SINGLE_SUCCESS;
    }

    private int stop(Player p, boolean manual) throws CommandSyntaxException {
        var run = runs.remove(p.getUniqueId());
        if (run == null) { if (manual) throw ChacaApi.error("진행 중인 자동 시전 검수가 없어요"); return 0; }
        summary(p, run, manual ? "정지" : "완료");
        stopTaskIfIdle();
        return Command.SINGLE_SUCCESS;
    }

    private int pause(CommandContext<CommandSourceStack> ctx, boolean pause) throws CommandSyntaxException {
        Player p = CodexCommands.self(ctx);
        var run = running(p);
        if (run.paused == pause) throw ChacaApi.error(pause ? "이미 일시정지 중이에요" : "일시정지 중이 아니에요");
        run.paused = pause;
        if (!pause) run.countdown = Math.min(run.countdown, run.interval);
        ChacaApi.ok(p, pause ? "자동 시전 일시정지 (다음: " + nextLabel(run) + ")" : "자동 시전 재개");
        return Command.SINGLE_SUCCESS;
    }

    private int skip(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        Player p = CodexCommands.self(ctx);
        var run = running(p);
        String skipped = nextLabel(run);
        run.next++;
        if (run.next >= run.list.size()) { stop(p, false); return Command.SINGLE_SUCCESS; }
        ChacaApi.ok(p, "건너뜀: " + skipped);
        return Command.SINGLE_SUCCESS;
    }

    private int interval(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        Player p = CodexCommands.self(ctx);
        var run = running(p);
        run.interval = IntegerArgumentType.getInteger(ctx, "초");
        run.countdown = Math.min(run.countdown, run.interval);
        ChacaApi.ok(p, "자동 시전 간격: " + run.interval + "초");
        return Command.SINGLE_SUCCESS;
    }

    private int status(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        Player p = CodexCommands.self(ctx);
        var run = runs.get(p.getUniqueId());
        if (run == null) {
            ChacaApi.lines(p, List.of("── 자동 시전 검수 ──", " 진행 중이 아님 · 활성 마법 " + entries().size() + "개",
                " 시작 [도감번호|마법] · 정지 · 일시정지 · 재개 · 건너뛰기 · 간격 <초> · 상태"));
            return Command.SINGLE_SUCCESS;
        }
        ChacaApi.lines(p, List.of("── 자동 시전 검수 ──",
            " " + (run.paused ? "일시정지" : "진행 중") + " · 간격 " + run.interval + "초 · 다음 " + nextLabel(run),
            " 성공 " + run.ok + " · 실패 " + run.failed.size()));
        return Command.SINGLE_SUCCESS;
    }

    private Run running(Player p) throws CommandSyntaxException {
        var run = runs.get(p.getUniqueId());
        if (run == null) throw ChacaApi.error("진행 중인 자동 시전 검수가 없어요");
        return run;
    }

    private static String nextLabel(Run run) {
        return run.next < run.list.size() ? label(run.list.get(run.next), run.next, run.list.size()) : "(끝)";
    }

    // ------------------------------------------------------------------ 스케줄러 (실행 중일 때만 1초 주기 하나)

    private void ensureTask() {
        if (task == null) task = Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 20, 20);
    }

    private void stopTaskIfIdle() {
        if (runs.isEmpty() && task != null) { task.cancel(); task = null; }
    }

    private void tick() {
        for (var it = runs.entrySet().iterator(); it.hasNext(); ) {
            var e = it.next();
            Player p = Bukkit.getPlayer(e.getKey());
            if (p == null) { it.remove(); continue; } // 나가면 끝 (요약 없음)
            var run = e.getValue();
            if (run.paused || p.isDead()) continue;
            if (--run.countdown > 0) continue;
            run.countdown = run.interval;
            var entry = run.list.get(run.next);
            String label = label(entry, run.next, run.list.size());
            ChacaApi.ok(p, "시전: " + label);
            int status = castOnce(p, entry.id());
            if (status == ManaProtocol.OK) run.ok++;
            else {
                String why = status == ManaProtocol.UNAVAILABLE ? "지금은 쓸 수 없음(시전 거부)" : ManaBridge.message(status);
                run.failed.add(label + " — " + why);
                ChacaApi.lines(p, List.of("[자동 시전] 실패 " + label + " — " + why));
            }
            if (++run.next >= run.list.size()) { it.remove(); summary(p, run, "완료"); }
        }
        stopTaskIfIdle();
    }

    /** 마나·쿨타임·배움 권한만 잠깐 우회하고, 플레이어가 /마법 으로 쓴 것과 같은 경로로 시전한다. 끝나면 원래대로. */
    private int castOnce(Player p, String id) {
        UUID u = p.getUniqueId();
        var mana = bridge.mana;
        var before = mana.snapshot(u).orElse(null);
        var spell = bridge.reviewSpell(id);
        if (before == null || spell == null) return ManaProtocol.UNKNOWN;
        PermissionAttachment grant = null;
        try {
            mana.clearCooldown(u, id);
            if (spell.cost() > before.maximum()) mana.setModifier(u, MODIFIER, spell.cost() - before.maximum() + 1, 0);
            mana.setCurrent(u, mana.snapshot(u).map(s -> s.maximum()).orElse(before.maximum()));
            if (!p.hasPermission(spell.permission())) grant = p.addAttachment(plugin, spell.permission(), true);
            int status = bridge.castForReview(p, id);
            // 시전 효과음은 서버가 시전 성공 때 직접 재생 (#47 SpellSounds)
            return status;
        } catch (RuntimeException error) {
            plugin.getLogger().warning("자동 시전 검수 실패: " + id + " (" + error.getClass().getSimpleName() + ": " + error.getMessage() + ")");
            return ManaProtocol.FAILED;
        } finally {
            if (grant != null) try { p.removeAttachment(grant); } catch (RuntimeException ignored) { }
            try {
                mana.removeModifier(u, MODIFIER);
                mana.clearCooldown(u, id);
                double max = mana.snapshot(u).map(s -> s.maximum()).orElse(before.maximum());
                mana.setCurrent(u, Math.min(before.current(), max));
                plugin.markPlayerStateDirty(p); // 시전 중 저장된 값(가득 찬 마나)을 원래 값으로 다시 동기화
            } catch (IllegalArgumentException notLoaded) { /* 그 사이에 나감 */ }
        }
    }

    private void summary(Player p, Run run, String how) {
        var lines = new ArrayList<String>();
        lines.add("── 자동 시전 검수 " + how + " ──");
        lines.add(" 성공 " + run.ok + " · 실패 " + run.failed.size() + " · 남음 " + Math.max(0, run.list.size() - run.next));
        for (String f : run.failed) lines.add(" " + f);
        ChacaApi.lines(p, lines);
    }

    @Override public void close() {
        runs.clear();
        if (task != null) { task.cancel(); task = null; }
    }
}
