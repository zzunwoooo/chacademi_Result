package kr.chacademy.storyplugin;

import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import kr.chacademy.storyplugin.QuestDefs.Cond;
import kr.chacademy.storyplugin.QuestDefs.CondType;
import kr.chacademy.storyplugin.QuestDefs.Step;
import kr.chacademy.storyplugin.QuestDefs.TrigType;
import kr.chacademy.storyplugin.QuestDefs.Trigger;
import kr.chacademy.storyplugin.QuestProgress.P;
import kr.chacademy.storyplugin.QuestProgress.Phase;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.Cancellable;
import org.bukkit.event.Event;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerItemBreakEvent;
import org.bukkit.event.player.PlayerItemConsumeEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.Plugin;

import java.io.File;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Level;

/**
 * 스토리 진행 단계 (조건 → 호출 → 다음 대화). 플러그인 안의 별도 부품. 모든 상태는 메인 스레드에서만.
 * <ul>
 *   <li>단계 정의: steps.yml (명령어로 고치면 바로 저장, 직접 고친 뒤 /서버관리 새로고침 스토리)</li>
 *   <li>진행: quest-progress.yml (이 서버 로컬 — 진행 기준 서버는 학교 서버 하나)</li>
 *   <li>조건: 아이템 보유 / 제출(호출 때 걷음) / 처치 / 블록 상호작용 / 대화완료 / 신호(다른 플러그인·Skript 가 /대화관리 신호 로 알림)</li>
 *   <li>호출: 자동(지연) / Citizens NPC 클릭(AI 대화보다 먼저) / 위치 포탈 / 컷신 시청 완료 / 관리자 강제 진행</li>
 *   <li>중복 방지: 호출 후 대화를 기다리는 동안(PLAYING)은 다시 호출되지 않고, 완료 신호는 기다리던 대화·컷신과 같을 때만 단계를 넘긴다</li>
 *   <li>위치 포탈 대화를 중간에 닫으면: 포탈 밖으로 한 번 나갔다가 다시 들어와야 다시 열린다 (그 자리에 서 있어도 저절로 다시 열리지 않음)</li>
 *   <li>HUD 패킷: 단계 패킷(단계가 바뀔 때·접속 때) + 갱신 패킷(진행도·준비 상태가 바뀔 때만, 같은 틱은 하나로)</li>
 * </ul>
 */
final class QuestEngine implements Listener {
    private static final long TICK_CHECK = 10L;
    private static final long PICK_MS = 120_000L;
    private static final long PLAYING_TIMEOUT_MS = 30 * 60_000L;
    /** 컷신 요청이 실패·취소된 뒤 컷신만 다시 요청하는 횟수. 넘으면 건너뛰고 다음 단계로 (대화부터 다시 반복하지 않음). */
    static final int CUTSCENE_RETRIES = 3;
    /** 아이템 조건: 인벤토리 변경 이벤트가 없어도 이 간격마다 한 번은 다시 센다 (명령·다른 플러그인이 넣은 아이템). */
    static final long ITEM_RESCAN_MS = 5_000L;

    private final StoryPlugin plugin;
    private QuestDefs defs = new QuestDefs();
    private QuestProgress prog = new QuestProgress();
    private File defsFile, progFile;
    private StoryStore progStore, defsStore;
    private boolean ready, dirty, flushScheduled, sendScheduled;
    private final List<Runnable> afterDurable = new ArrayList<>();

    private final Map<UUID, Integer> sessions = new HashMap<>();
    private final Map<UUID, String> sentKey = new HashMap<>();
    private final Set<UUID> needStep = new HashSet<>(), needUpdate = new HashSet<>();
    private final Map<UUID, Boolean> readyCache = new HashMap<>();
    private final Map<UUID, Long> readySince = new HashMap<>(), retryAt = new HashMap<>(), hintAt = new HashMap<>(),
            clickGate = new HashMap<>(), playingSince = new HashMap<>();
    private final Map<UUID, Pick> picks = new HashMap<>();
    private final Set<UUID> previewing = new HashSet<>();
    /** 제출 아이템을 걷는 중 (진행 저장이 끝나기를 기다림). */
    private final Set<UUID> submitting = new HashSet<>();
    /** 컷신 실패 횟수 (단계 회차별). */
    private record CutFail(int run, int count) {}
    private final Map<UUID, CutFail> cutFails = new HashMap<>();
    /** 아이템 조건 개수 캐시 (주기 확인용). 인벤토리 변경 이벤트가 오면 지운다. */
    private record ItemScan(long at, Map<String, Integer> counts) {}
    private final Map<UUID, ItemScan> itemScans = new HashMap<>();

    /** random-pools.yml: 랜덤제출 후보 목록 이름 → 아이템 대상들 ("이름:독시 날개" 처럼 제출 대상과 같은 형식). */
    private Map<String, List<String>> pools = Map.of();
    /** 값 조건: "유저|자리표시자" → (읽은 때, 값). 0.5초 주기 확인마다 PlaceholderAPI 를 부르지 않게 2초 동안 재사용. 메모리 전용. */
    private record ValueRead(long at, long value) {}
    private final Map<String, ValueRead> values = new HashMap<>();
    static final long VALUE_CACHE_MS = 2_000L;
    /** 위치 포탈 대화를 중간에 닫은 유저: 포탈 밖으로 나가야 다시 호출된다 (메모리 전용 — 단계 변경·재접속·초기화 때 지움). */
    private final Set<UUID> mustLeave = new HashSet<>();
    /** 장 공개 제한 (config.yml chapter-release). */
    private ChapterGate gate = ChapterGate.defaults();
    /** 잠긴 장의 단계에서 멈춰 있는 접속자 (장이 열리면 바로 HUD·시작명령을 다시 보내려고). 메모리 전용. */
    private final Set<UUID> heldByGate = new HashSet<>();
    /** 길잡이 별이 켜져 있는 접속자 → 켠 단계 session (다시 시전하면 끈다). 단계가 바뀌거나 목표 10블록 안이면 지운다. 메모리 전용. */
    private final Map<UUID, Integer> guiding = new HashMap<>();
    /**
     * 길잡이 별이 NPC 를 가리키는 접속자 → 따라가는 NPC (2026-10-26 메모 #93). 일과표로 돌아다니는 ChacaNPC 등 움직이는 NPC 의
     * 지금 위치를 {@link #FOLLOW_TICKS} 마다 확인해, {@link #FOLLOW_MOVE} 블록 넘게 움직였거나 상태가 바뀐 사람에게만 새 위치를 보낸다.
     * 목표가 NPC 인 사람만 들어간다 (포탈·목표 블록은 고정이라 안 들어감). 새 클라(GUIDE_TARGET 채널)만. 메모리 전용.
     */
    private static final class Follow {
        final int session, npc;
        /** true = 스토리 단계가 아니라 MagicCodex 의뢰의 NPC 목표 (의뢰가 끝나면 끈다). */
        final boolean quest;
        String world = "", entity = "";
        double x, y, z;
        int state = -1;
        boolean moving;
        /** 지난 확인 때 위치 (이동 중 판단용, 보낸 위치와 따로). */
        Location checked;
        Follow(int session, int npc, boolean quest) { this.session = session; this.npc = npc; this.quest = quest; }
    }
    private final Map<UUID, Follow> follows = new HashMap<>();
    /** 움직이는 NPC 위치 확인 주기 (2초). 매 틱 아님, 따라가는 사람이 없으면 바로 끝. */
    static final long FOLLOW_TICKS = 40L;
    /** 이만큼(블록) 넘게 움직였을 때만 새 위치를 보낸다. */
    static final double FOLLOW_MOVE = 1.5;
    /** 2초 사이 이만큼 넘게 움직이면 "이동 중" (Citizens 길찾기 중이 아니어도). */
    static final double FOLLOW_MOVING = 0.3;

    private Class<?> npcIface, registryIface;
    private Object registry;
    private Method npcId, npcEntity, npcStored, npcName, registryById;
    /** Citizens NPC.getNavigator() / Navigator.isNavigating() — 없으면 위치 변화로만 이동 중을 판단한다. */
    private Method npcNavigator, navigatorNavigating;
    private boolean citizens;

    /** cond = -1 → NPC 지정, 그 밖 = 상호작용 조건 번호에 블록 추가. */
    private record Pick(String step, int cond, long expires) {}

    QuestEngine(StoryPlugin plugin) { this.plugin = plugin; }

    // ================================================================ 켜기 / 끄기 / 새로고침

    void enable() {
        defsFile = new File(plugin.getDataFolder(), "steps.yml");
        progFile = new File(plugin.getDataFolder(), "quest-progress.yml");
        if (!defsFile.isFile()) plugin.saveResource("steps.yml", false);
        loadDefs(null);
        loadGate();
        loadProgress();
        progStore = new StoryStore(text -> StorySafety.atomicWrite(progFile.toPath(), text), StoryStore.newWorker(),
                r -> Bukkit.getScheduler().runTask(plugin, r), plugin.getLogger());
        defsStore = new StoryStore(text -> StorySafety.atomicWrite(defsFile.toPath(), text), StoryStore.newWorker(),
                r -> Bukkit.getScheduler().runTask(plugin, r), plugin.getLogger());
        ready = true;
        for (String out : List.of(QuestCodec.STEP, QuestCodec.UPDATE, QuestCodec.GUIDE, QuestCodec.POINTS, QuestCodec.GUIDE_TARGET, BUNGEE)) plugin.getServer().getMessenger().registerOutgoingPluginChannel(plugin, out);
        plugin.getServer().getPluginManager().registerEvents(this, plugin);
        Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 40L, TICK_CHECK);
        Bukkit.getScheduler().runTaskTimer(plugin, this::followTick, 40L, FOLLOW_TICKS);
        Bukkit.getScheduler().runTask(plugin, this::hookCitizens);
        Bukkit.getScheduler().runTask(plugin, this::hookProgressEvents);
        for (Player p : Bukkit.getOnlinePlayers()) needStep.add(p.getUniqueId());
        scheduleSend();
    }

    void disable() {
        if (ready && progStore != null) {
            Map<String, Object> snap = prog.snapshot();
            progStore.flushNow(() -> render(snap));
        }
        ready = false;
    }

    String serverId() {
        String s = plugin.getConfig().getString("quest-server-id", "school");
        return s == null || s.isBlank() ? "school" : s.strip();
    }

    // ================================================================ 단계의 서버 (2026-10-26 오너: 1장은 tutorial → 배 → school)

    /** Velocity 의 BungeeCord 호환 채널 (Connect = 그 서버로 보내기). */
    static final String BUNGEE = "BungeeCord";
    /** 서버이동을 마지막으로 보낸 때 (같은 사람에게 10초에 한 번). */
    private final Map<UUID, Long> transferAt = new HashMap<>();

    /** 이 단계가 다른 서버(steps.yml 단계의 서버:) 것인지. 서버가 비어 있으면 어느 서버에서나 진행. */
    boolean elsewhere(Step s) {
        return s != null && !s.server.isEmpty() && !s.server.equals(serverId());
    }

    /**
     * 서버이동 단계: 그 단계의 서버로 보낸다 (예: 튜토리얼 서버에서 배 컷신을 보고 ch1-8 에 들어오면 school 로).
     * 진행 기록은 공유 저장소에서 같은 단계로 이어진다. 서버이동이 아닌 단계는 아무것도 하지 않는다 (그 서버에 가면 이어짐).
     */
    private void sendToStepServer(Player p, Step s) {
        if (s == null || !s.transfer || s.server.isEmpty() || p == null || !p.isOnline()) return;
        long now = System.currentTimeMillis();
        Long last = transferAt.get(p.getUniqueId());
        if (last != null && now - last < 10_000) return;
        transferAt.put(p.getUniqueId(), now);
        try {
            java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream();
            java.io.DataOutputStream data = new java.io.DataOutputStream(bytes);
            data.writeUTF("Connect");
            data.writeUTF(s.server);
            p.sendPluginMessage(plugin, BUNGEE, bytes.toByteArray());
            plugin.getLogger().info("[스토리 단계] " + p.getName() + ": " + s.id + " 은(는) " + s.server + " 서버 단계 — 그 서버로 보냅니다");
            ChacaLink.notify(p, "system", "story-transfer", "다음 이야기로 이동해요", "잠시만 기다려 주세요", 5000);
        } catch (java.io.IOException | RuntimeException ex) {
            plugin.getLogger().log(Level.WARNING, "[스토리 단계] " + p.getName() + ": " + s.server + " 서버로 보내지 못했습니다", ex);
        }
    }

    /** /서버관리 새로고침 스토리: steps.yml 다시 읽기 (진행 기록은 그대로). */
    void reload() {
        loadDefs(null);
        loadGate();
        for (Player p : Bukkit.getOnlinePlayers()) {
            needStep.add(p.getUniqueId());
            P st = prog.players.get(p.getUniqueId());
            if (st != null) resumeFinished(p, st);
        }
        scheduleSend();
    }

    private void loadDefs(CommandSender tell) {
        List<String> warnings = new ArrayList<>();
        try {
            Map<?, ?> root = defsFile.isFile() ? StoryPlugin.readYaml(defsFile) : Map.of();
            defs = QuestDefs.parse(root, warnings);
        } catch (Exception e) {
            plugin.getLogger().log(Level.SEVERE, "steps.yml 을 읽지 못했습니다 — 예전 단계 정의를 그대로 씁니다", e);
            return;
        }
        for (String w : warnings) plugin.getLogger().warning("[스토리 단계] " + w);
        plugin.getLogger().info("[스토리 단계] " + defs.steps.size() + "개 (시작 " + (defs.startStep.isEmpty() ? "-" : defs.startStep) + ")");
        loadPools();
    }

    /** random-pools.yml (없으면 빈 목록): pools: { 이름: [대상, …] }. 대상 형식은 제출과 같다. */
    private void loadPools() {
        File f = new File(plugin.getDataFolder(), "random-pools.yml");
        Map<String, List<String>> next = new HashMap<>();
        if (f.isFile()) {
            try {
                Map<?, ?> root = StoryPlugin.readYaml(f);
                if (root.get("pools") instanceof Map<?, ?> all) for (var e : all.entrySet()) {
                    if (!(e.getValue() instanceof List<?> l)) continue;
                    List<String> items = new ArrayList<>();
                    for (Object o : l) if (o != null && !String.valueOf(o).isBlank() && items.size() < 1000) items.add(String.valueOf(o).strip());
                    if (!items.isEmpty()) next.put(String.valueOf(e.getKey()).strip(), List.copyOf(items));
                }
            } catch (Exception e) {
                plugin.getLogger().log(Level.SEVERE, "random-pools.yml 을 읽지 못했습니다 — 랜덤제출 후보는 예전 것을 그대로 씁니다", e);
                return;
            }
        }
        pools = next;
        if (!next.isEmpty()) plugin.getLogger().info("[스토리 단계] 랜덤제출 후보 " + next.size() + "개 목록 (" + next.entrySet().stream().map(e -> e.getKey() + " " + e.getValue().size()).collect(java.util.stream.Collectors.joining(", ")) + ")");
    }

    /**
     * 랜덤제출 조건마다 아이템 하나를 뽑는다 (이미 뽑힌 조건은 그대로). 같은 단계의 랜덤제출끼리는 겹치지 않게 (후보가 모자라면 겹침 허용).
     * @return 새로 뽑은 것이 있으면 true (저장 필요)
     */
    private boolean assignPicks(P st, Step s) {
        if (s == null) return false;
        boolean changed = false;
        var rnd = java.util.concurrent.ThreadLocalRandom.current();
        for (int i = 0; i < s.conds.size(); i++) {
            Cond c = s.conds.get(i);
            if (c.type() != CondType.RANDOM || st.picks.containsKey(i)) continue;
            List<String> pool = pools.get(c.target());
            if (pool == null || pool.isEmpty()) continue;
            List<String> free = new ArrayList<>(pool);
            free.removeAll(st.picks.values());
            List<String> from = free.isEmpty() ? pool : free;
            st.picks.put(i, from.get(rnd.nextInt(from.size())));
            changed = true;
        }
        return changed;
    }

    /** 조건의 실제 대상: 랜덤제출은 이 유저에게 뽑힌 아이템 (없으면 빈 값 = 채울 수 없음). */
    private static String targetOf(P st, int i, Cond c) {
        return c.type() == CondType.RANDOM ? (st == null ? "" : st.picks.getOrDefault(i, "")) : c.target();
    }

    /** 값 조건: PlaceholderAPI 값을 숫자로 (숫자가 아니거나 못 읽으면 0). */
    private long valueOf(Player p, String placeholder) {
        if (p == null) return 0;
        String key = p.getUniqueId() + "|" + placeholder;
        long now = System.currentTimeMillis();
        ValueRead old = values.get(key);
        if (old != null && now - old.at() < VALUE_CACHE_MS) return old.value();
        long v = 0;
        MagicCodexLink codex = plugin.codex();
        String raw = codex == null ? null : codex.placeholder(p, placeholder);
        if (raw != null && !raw.equals(placeholder)) {
            String digits = org.bukkit.ChatColor.stripColor(raw).replaceAll("[^0-9-]", "");
            try { if (!digits.isEmpty()) v = Long.parseLong(digits); } catch (NumberFormatException ignored) { }
        }
        if (values.size() > 4000) values.clear();
        values.put(key, new ValueRead(now, v));
        return v;
    }

    // ================================================================ 장 공개 제한

    static final java.time.ZoneId KST = java.time.ZoneId.of("Asia/Seoul");

    /** config.yml chapter-release 읽기. 없으면 2장까지 공개·3장부터 잠금. */
    private void loadGate() {
        var c = plugin.getConfig();
        List<String> warnings = new ArrayList<>();
        Map<Integer, ChapterGate.Release> chapters = new HashMap<>();
        var sec = c.getConfigurationSection("chapter-release.chapters");
        if (sec != null) for (String key : sec.getKeys(false)) {
            int n;
            try { n = Integer.parseInt(key.strip()); } catch (NumberFormatException e) { warnings.add("chapter-release.chapters 의 키는 장 번호(숫자)여야 함: " + key); continue; }
            chapters.put(n, new ChapterGate.Release(sec.getBoolean(key + ".open", false), ChapterGate.parseTime(sec.getString(key + ".open-at", ""), KST, warnings, n)));
        }
        gate = new ChapterGate(c.getInt("chapter-release.open-through", 2), chapters,
                c.getString("chapter-release.locked-title", ChapterGate.DEFAULT_TITLE), c.getString("chapter-release.locked-body", ChapterGate.DEFAULT_BODY));
        for (String w : warnings) plugin.getLogger().warning("[스토리 장 공개] " + w);
        plugin.getLogger().info("[스토리 장 공개] " + gate.openThrough + "장까지 공개" + (chapters.isEmpty() ? "" : " · 설정된 장 " + chapters.keySet()));
    }

    /** 이 단계가 아직 공개되지 않은 장인지. */
    boolean chapterLocked(Step s) {
        return s != null && gate.locked(ChapterGate.chapterOf(s.id, s.chapter), System.currentTimeMillis());
    }

    /** 대화·컷신 ID 가 공개 전인 장의 것인지 ("ch3-1", "ch3_intro" 처럼 ch<번호> 로 시작하는 ID 만 판단). /대화관리 재생 이 쓴다. */
    boolean idLocked(String id) {
        return gate.locked(ChapterGate.chapterOf(id, null), System.currentTimeMillis());
    }

    String lockedTitle() { return gate.title; }

    private void tellLocked(Player p, long cooldownMs) {
        ChacaLink.notify(p, "system", "story-chapter-locked", gate.title, gate.body, cooldownMs);
    }

    private void loadProgress() {
        prog = new QuestProgress();
        if (!progFile.isFile()) return;
        try {
            List<String> notes = new ArrayList<>();
            prog = QuestProgress.fromMap(StoryPlugin.readYaml(progFile), notes);
            notes.forEach(n -> plugin.getLogger().info("[스토리 단계] " + n));
        } catch (Exception e) {
            throw new IllegalStateException("quest-progress.yml 을 읽을 수 없어 기존 파일을 지키기 위해 멈춥니다", e);
        }
    }

    private static String render(Map<String, Object> snap) {
        YamlConfiguration y = new YamlConfiguration();
        y.options().pathSeparator('\u0001');
        y.options().setHeader(List.of("스토리 단계 진행 기록 (서버가 켜져 있을 때 직접 고치지 마세요). 진행 기준 서버 = 이 서버."));
        y.set("format", snap.get("format"));
        y.createSection("players", (Map<?, ?>) snap.get("players"));
        return y.saveToString();
    }

    private void saveSoon(Runnable after) {
        dirty = true;
        if (after != null) afterDurable.add(after);
        if (flushScheduled || !ready) return;
        flushScheduled = true;
        Bukkit.getScheduler().runTask(plugin, this::flush);
    }

    private void flush() {
        flushScheduled = false;
        if (!ready || (!dirty && afterDurable.isEmpty())) return;
        dirty = false;
        Map<String, Object> snap = prog.snapshot();
        List<Runnable> cb = new ArrayList<>(afterDurable);
        afterDurable.clear();
        progStore.submit(() -> render(snap), cb);
    }

    private void saveDefs() {
        Map<String, Object> map = defs.toMap();
        defsStore.submit(() -> {
            YamlConfiguration y = new YamlConfiguration();
            y.options().pathSeparator('\u0001');
            y.options().setHeader(List.of(
                    "차카데미 스토리 진행 단계. 명령어(/대화관리 단계 ...)로 고치면 이 파일이 바로 저장됩니다.",
                    "직접 고친 뒤에는 /서버관리 새로고침 스토리. 키 설명은 플러그인 README 참고."));
            for (var e : map.entrySet()) {
                if (e.getValue() instanceof Map<?, ?> m) y.createSection(e.getKey(), m);
                else y.set(e.getKey(), e.getValue());
            }
            return y.saveToString();
        }, List.of());
    }

    // ================================================================ 다른 부품이 부르는 곳 (StoryPlugin)

    /** 이 대화를 스토리 단계가 띄우는 중이면, 서버 끝 명령이 없어도 완료 신호를 받을 세션이 필요하다. */
    boolean wantsSession(UUID id, String dialogue) {
        P st = prog.players.get(id);
        if (st != null && st.phase == Phase.PLAYING && st.waiting.equals("d:" + dialogue)) return true;
        Step s = st == null ? null : defs.get(st.step);
        if (s != null) for (Cond c : s.conds) if (c.type() == CondType.DIALOGUE && QuestDefs.dialogueMatches(c.target(), dialogue)) return true;
        return dialogue.equals(defs.startAfterDialogue);
    }

    /** 서버가 확인한 대화 완료 (서버 흐름 대화의 DIALOGUE_END 효과 / 로컬 대화 세션 finish 두 경로 모두 여기로). */
    void dialogueFinished(Player p, String dialogue) {
        if (!ready || dialogue == null) return;
        UUID uuid = p.getUniqueId();
        P st = prog.players.get(uuid);
        if ((st == null || !st.started) && !defs.startStep.isEmpty() && dialogue.equals(defs.startAfterDialogue)) {
            enter(p, prog.of(uuid), defs.startStep, true);
            return;
        }
        if (st == null || st.step.isEmpty()) return;
        Step s = defs.get(st.step);
        if (s == null) return;
        if (st.phase == Phase.PLAYING && st.waiting.equals("d:" + dialogue)) {
            if (!s.cutscene.isEmpty()) {
                st.waiting = "c:" + s.cutscene;
                playingSince.put(uuid, System.currentTimeMillis());
                saveSoon(null);
                plugin.playCutscene(p, s.cutscene, StoryCodec.MODE_AUTO);
            } else {
                advance(p, st, s);
            }
            return;
        }
        boolean changed = false;
        for (int i = 0; i < s.conds.size(); i++) {
            Cond c = s.conds.get(i);
            if (c.type() == CondType.DIALOGUE && QuestDefs.dialogueMatches(c.target(), dialogue) && st.counts.getOrDefault(i, 0) < c.required()) {
                st.counts.put(i, c.required());
                changed = true;
            }
        }
        if (changed) { saveSoon(null); needUpdate.add(uuid); scheduleSend(); }
    }

    /** 대화가 중간에 닫힘 (중지·대기열 비우기·세션 취소). 기다리던 대화면 다시 호출할 수 있게 되돌린다. */
    void dialogueAborted(UUID uuid, String dialogue) {
        P st = prog.players.get(uuid);
        if (st != null && st.phase == Phase.ACTIVE) restartLater(uuid, st);
        if (st == null || st.phase != Phase.PLAYING) return;
        if (dialogue != null && !st.waiting.equals("d:" + dialogue)) return;
        if (!st.waiting.startsWith("d:")) return;
        backToActive(uuid, st);
        // 위치 포탈이면 포탈 안에 서 있어도 바로 다시 열지 않는다 (나갔다가 다시 들어오면 열림)
        Step s = defs.get(st.step);
        Player p = Bukkit.getPlayer(uuid);
        if (s != null && s.trigger.type == TrigType.LOCATION && p != null && inside(p, s.trigger)) mustLeave.add(uuid);
    }

    private final Map<UUID, Long> restartAt = new HashMap<>();

    /** 시작명령이 띄운 대화(예: 분류 6문항)를 중간에 닫음: 조건을 아직 못 채웠으면 5초 뒤 시작명령을 한 번 더 (30초에 한 번까지). */
    private void restartLater(UUID uuid, P st) {
        Step s = defs.get(st.step);
        if (s == null || s.startCommands.isEmpty()) return;
        long now = System.currentTimeMillis();
        if (now - restartAt.getOrDefault(uuid, 0L) < 30_000) return;
        restartAt.put(uuid, now);
        int run = st.run;
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            Player p = Bukkit.getPlayer(uuid);
            if (p == null || st.run != run || isReady(p, st, s)) return;
            runStartCommands(p, st, run, false);
        }, 100L);
    }

    /** 서버가 재생시킨 컷신이 끝남 (StoryPlugin 의 CUTSCENE_END 효과). */
    void cutsceneFinished(Player p, String cutscene) {
        if (!ready) return;
        P st = prog.players.get(p.getUniqueId());
        if (st == null || st.step.isEmpty()) return;
        Step s = defs.get(st.step);
        if (s == null) return;
        if (st.phase == Phase.PLAYING && st.waiting.equals("c:" + cutscene)) {
            advance(p, st, s);
            return;
        }
        if (st.phase == Phase.ACTIVE && s.trigger.type == TrigType.CUTSCENE && s.trigger.cutscene.equals(cutscene) && isReady(p, st, s)) {
            fire(p, st, s, "컷신");
        }
    }

    /** 다른 플러그인·Skript 의 신호 (/대화관리 신호 &lt;닉네임&gt; &lt;키&gt; [개수]). 지금 단계에 같은 신호 조건이 있을 때만 센다. */
    boolean signal(Player p, String key, int amount) {
        if (!ready || p == null || key == null) return false;
        P st = prog.players.get(p.getUniqueId());
        Step s = st == null ? null : defs.get(st.step);
        if (s == null || st.phase != Phase.ACTIVE) return false;
        boolean changed = false;
        for (int i = 0; i < s.conds.size(); i++) {
            Cond c = s.conds.get(i);
            if (c.type() != CondType.SIGNAL || !c.target().equals(key)) continue;
            int cur = st.counts.getOrDefault(i, 0);
            if (cur >= c.required()) continue;
            st.counts.put(i, Math.min(c.required(), cur + Math.max(1, amount)));
            changed = true;
        }
        if (changed) { saveSoon(null); needUpdate.add(p.getUniqueId()); scheduleSend(); }
        return changed;
    }

    // ================================================================ 단계 진행

    private void enter(Player p, P st, String stepId, boolean tell) {
        UUID uuid = p.getUniqueId();
        st.enter(stepId);
        assignPicks(st, defs.get(stepId));
        mustLeave.remove(uuid);
        guiding.remove(uuid);
        follows.remove(uuid);
        heldByGate.remove(uuid);   // 잠긴 장 → 열린 단계로 옮겨지면 tick 이 시작명령을 한 번 더 돌리지 않게
        readySince.remove(uuid);
        readyCache.remove(uuid);
        retryAt.remove(uuid);
        playingSince.remove(uuid);
        saveSoon(null);
        needStep.add(uuid);
        scheduleSend();
        Step s = defs.get(stepId);
        if (chapterLocked(s)) {
            // 공개 전인 장: 목표 알림·시작명령 없이 멈춘다 (장이 열리면 tick 이 이어 준다)
            heldByGate.add(uuid);
            if (tell) tellLocked(p, 0);
            return;
        }
        if (elsewhere(s)) {
            // 다른 서버의 단계: 여기서는 알림·시작명령 없이 (서버이동 단계면 그 서버로 보냄)
            Bukkit.getScheduler().runTask(plugin, () -> sendToStepServer(p, s));
            return;
        }
        if (tell && s != null && !s.description.isBlank()) ChacaLink.notify(p, "system", "story-step-" + st.run, "새 목표", s.description, 0);
        if (s != null && !s.startCommands.isEmpty()) {
            int run = st.run;
            // 다음 틱: 앞 대화의 끝 명령·세션 정리가 끝난 뒤
            Bukkit.getScheduler().runTask(plugin, () -> runStartCommands(p, st, run, false));
        }
    }

    /**
     * 단계의 시작명령 (steps.yml, 서버 설정). 그 단계·회차 그대로이고 기다리는 중이 아닐 때만.
     * 시작명령-한번 단계는 "이 회차에 실행함"(startedRun)을 먼저 디스크에 저장한 뒤 실행하고, 같은 회차에는 다시 실행하지 않는다
     * (재접속·대화 닫기·서버 재시작 모두). manual = 관리자 /유저관리 스토리진행 다시 (한번 표시를 무시하고 실행).
     */
    private void runStartCommands(Player p, P st, int run, boolean manual) {
        if (!ready || !p.isOnline() || st.run != run || st.phase != Phase.ACTIVE) return;
        Step s = defs.get(st.step);
        if (s == null) return;
        if (chapterLocked(s)) { heldByGate.add(p.getUniqueId()); return; }
        if (elsewhere(s)) { sendToStepServer(p, s); return; }   // 시작명령은 그 단계의 서버에서만
        if (!manual && s.startOnce()) {
            if (st.startedRun == run) return;
            st.startedRun = run;
            UUID uuid = p.getUniqueId();
            saveSoon(() -> {
                Player now = Bukkit.getPlayer(uuid);
                if (st.run != run || !st.step.equals(s.id)) return;
                if (now == null || !now.isOnline() || st.phase != Phase.ACTIVE) {
                    // 아직 아무것도 실행하지 않았다: 표시를 되돌려 다음 접속 때 실행
                    if (st.startedRun == run) { st.startedRun = 0; saveSoon(null); }
                    return;
                }
                dispatchStartCommands(now, s);
            });
            return;
        }
        dispatchStartCommands(p, s);
    }

    private void dispatchStartCommands(Player p, Step s) {
        for (String raw : s.startCommands) {
            String c = raw.replace("{player}", p.getName()).replace("{uuid}", p.getUniqueId().toString());
            if (StoryPlugin.legacyCommand(c)) { plugin.getLogger().warning("[스토리 단계] " + s.id + " 시작명령의 예전(Skript) 명령은 실행하지 않습니다: " + c); continue; }
            try { Bukkit.dispatchCommand(Bukkit.getConsoleSender(), c); }
            catch (RuntimeException ex) { plugin.getLogger().log(Level.SEVERE, "[스토리 단계] 시작명령 실행 실패: " + c, ex); }
        }
    }

    /**
     * 스토리가 끝난 유저(지금 단계 없음)의 마지막 단계에 다음 단계가 나중에 생겼으면 거기서 이어 준다 (오너 2026-10-28:
     * ch1-27 의 다음이 비어 있던 동안 끝낸 유저 약 148명). 이미 끝낸 단계로는 되돌아가지 않는다. 접속·새로고침·단계 편집 때.
     */
    private boolean resumeFinished(Player p, P st) {
        if (!st.step.isEmpty() || !st.started || st.history.isEmpty()) return false;
        Step last = defs.get(st.history.get(0));
        if (last == null || last.next.isEmpty() || defs.get(last.next) == null || st.history.contains(last.next)) return false;
        plugin.getLogger().info("[스토리 단계] " + p.getName() + ": 끝낸 단계 " + last.id + " 에 다음 단계 " + last.next + " 이(가) 생겨 이어서 진행합니다");
        enter(p, st, last.next, true);
        return true;
    }

    private void advance(Player p, P st, Step s) {
        st.finished(s.id);
        String next = s.next.isEmpty() || defs.get(s.next) == null ? "" : s.next;
        if (!s.next.isEmpty() && next.isEmpty()) plugin.getLogger().warning("[스토리 단계] " + s.id + " 의 다음 단계 " + s.next + " 이(가) 없어 스토리를 여기서 멈춥니다");
        var detail = new java.util.LinkedHashMap<String, String>(); detail.put("단계", s.id); detail.put("다음", next); detail.put("회차", String.valueOf(st.run));
        ChacaLink.stat(p.getUniqueId(), "스토리단계", 1);
        ChacaLink.audit(p.getUniqueId(), "스토리", "스토리 단계 완료 · " + s.id + (next.isEmpty() ? "" : " → " + next), detail); // 운영 기록 (메모 #92)
        enter(p, st, next, true);
    }

    private void backToActive(UUID uuid, P st) {
        st.phase = Phase.ACTIVE;
        st.waiting = "";
        playingSince.remove(uuid);
        retryAt.put(uuid, System.currentTimeMillis() + 3000);
        saveSoon(null);
        needUpdate.add(uuid);
        scheduleSend();
    }

    /** 호출. 조건이 다 찼는지는 부르는 쪽이 확인한다. 기다리는 중이면 아무것도 하지 않는다 (연속 클릭·포탈 재진입·재접속 중복 방지). */
    private void fire(Player p, P st, Step s, String why) {
        UUID uuid = p.getUniqueId();
        if (st.phase != Phase.ACTIVE) return;
        if (chapterLocked(s)) {
            // 공개 전인 장의 단계: 어떤 호출 경로(포탈·NPC·자동·컷신·관리자 강제 진행)로도 열지 않는다
            heldByGate.add(uuid);
            if (!why.equals("자동") && !why.equals("컷신")) tellLocked(p, why.equals("위치") ? 10_000 : 3_000);
            return;
        }
        if (elsewhere(s)) { sendToStepServer(p, s); return; }   // 다른 서버의 단계는 여기서 호출하지 않는다
        long now = System.currentTimeMillis();
        if (now < retryAt.getOrDefault(uuid, 0L)) return;
        if (!st.submitted && hasSubmit(s)) {
            if (submitting.contains(uuid)) return;
            for (int i = 0; i < s.conds.size(); i++) { Cond c = s.conds.get(i); if (c.submits() && countItems(p, targetOf(st, i, c)) < c.required()) return; }
            // 제출: "걷었음"을 먼저 디스크에 저장하고, 저장이 끝난 뒤 개수를 다시 세서 걷는다 (이중 차감 없음).
            // 저장 뒤 ~ 걷기 전에 서버가 멈추면 아이템은 남고 단계는 진행된다 (손해는 서버 쪽).
            st.submitted = true;
            submitting.add(uuid);
            int submitRun = st.run;
            saveSoon(() -> afterSubmitSaved(uuid, st, s, submitRun, why));
            return;
        }
        int run = st.run;
        if (!s.dialogue.isEmpty()) {
            st.phase = Phase.PLAYING;
            st.waiting = "d:" + s.dialogue;
            playingSince.put(uuid, now);
            saveSoon(null);
            needUpdate.add(uuid);
            scheduleSend();
            String dialogue = s.dialogue;
            plugin.requestNicknameDialogue(p, dialogue).thenAccept(ok -> {
                Runnable check = () -> {
                    if (Boolean.TRUE.equals(ok)) return;
                    if (st.run == run && st.phase == Phase.PLAYING && st.waiting.equals("d:" + dialogue)) {
                        plugin.getLogger().warning("[스토리 단계] " + p.getName() + ": " + s.id + " 의 대화 " + dialogue + " 을(를) 열지 못했습니다 (다른 스토리 재생 중이거나 모드 없음) — 잠시 뒤 다시 호출할 수 있습니다");
                        backToActive(uuid, st);
                    }
                };
                if (Bukkit.isPrimaryThread()) check.run(); else Bukkit.getScheduler().runTask(plugin, check);
            });
        } else if (!s.cutscene.isEmpty()) {
            st.phase = Phase.PLAYING;
            st.waiting = "c:" + s.cutscene;
            playingSince.put(uuid, now);
            saveSoon(null);
            needUpdate.add(uuid);
            scheduleSend();
            plugin.playCutscene(p, s.cutscene, StoryCodec.MODE_AUTO);
        } else {
            advance(p, st, s);
        }
        if (plugin.getConfig().getBoolean("story-debug", false)) plugin.getLogger().info("[스토리 단계] " + p.getName() + " " + s.id + " 호출 (" + why + ")");
    }

    /** 제출 기록이 저장된 뒤 (메인 스레드): 다시 세고 걷은 다음 원래 호출을 이어 간다. 모자라면 기록을 되돌린다. */
    private void afterSubmitSaved(UUID uuid, P st, Step s, int run, String why) {
        submitting.remove(uuid);
        if (st.run != run || !st.submitted || !st.step.equals(s.id)) return;
        Player p = Bukkit.getPlayer(uuid);
        boolean enough = p != null && p.isOnline();
        if (enough) for (int i = 0; i < s.conds.size(); i++) { Cond c = s.conds.get(i); if (c.submits() && countItems(p, targetOf(st, i, c)) < c.required()) { enough = false; break; } }
        if (!enough) {
            st.submitted = false;
            saveSoon(null);
            needUpdate.add(uuid);
            scheduleSend();
            if (p != null && p.isOnline()) ChacaLink.fail(p, "가져갈 아이템이 부족해요", null);
            return;
        }
        for (int i = 0; i < s.conds.size(); i++) { Cond c = s.conds.get(i); if (c.submits()) takeItems(p, targetOf(st, i, c), c.required()); }
        try { p.saveData(); } catch (RuntimeException ex) { plugin.getLogger().log(Level.WARNING, "[스토리 단계] " + p.getName() + " 제출 뒤 플레이어 데이터 저장 실패", ex); }
        itemScans.remove(uuid);
        if (st.phase == Phase.ACTIVE) fire(p, st, s, why);
    }

    private static boolean hasSubmit(Step s) {
        for (Cond c : s.conds) if (c.submits()) return true;
        return false;
    }

    // ================================================================ 조건 계산

    private int[] currents(Player p, P st, Step s) { return currents(p, st, s, false); }

    /** cached = 0.5초 주기 확인: 아이템 개수는 인벤토리가 바뀌었거나 {@value #ITEM_RESCAN_MS}ms 가 지났을 때만 다시 센다. */
    private int[] currents(Player p, P st, Step s, boolean cached) {
        int[] out = new int[s.conds.size()];
        Map<String, Integer> scan = null;
        if (cached && p != null) {
            long now = System.currentTimeMillis();
            ItemScan old = itemScans.get(p.getUniqueId());
            if (old == null || now - old.at() > ITEM_RESCAN_MS) itemScans.put(p.getUniqueId(), old = new ItemScan(now, new HashMap<>()));
            scan = old.counts();
        }
        // 랜덤제출이 아직 안 뽑혔으면 (배포 전에 들어온 단계, 후보 목록을 나중에 넣음) 지금 뽑는다
        if (assignPicks(st, s)) { saveSoon(null); if (p != null) needStep.add(p.getUniqueId()); }
        for (int i = 0; i < out.length; i++) {
            Cond c = s.conds.get(i);
            Map<String, Integer> sc = scan;
            String target = targetOf(st, i, c);
            int v = switch (c.type()) {
                case ITEM -> p == null ? 0 : sc == null ? countItems(p, c.target()) : sc.computeIfAbsent(c.target(), t -> countItems(p, t));
                case SUBMIT, RANDOM -> st.submitted ? c.required() : p == null || target.isEmpty() ? 0 : sc == null ? countItems(p, target) : sc.computeIfAbsent(target, t -> countItems(p, t));
                case VALUE -> (int) Math.min(Integer.MAX_VALUE, Math.max(0, valueOf(p, c.target())));
                case INTERACT -> st.hits.getOrDefault(i, Set.of()).size();
                case PERMISSION -> p != null && p.hasPermission(c.target()) ? c.required() : 0;
                default -> st.counts.getOrDefault(i, 0);
            };
            out[i] = Math.min(v, c.required());
        }
        return out;
    }

    private boolean isReady(Player p, P st, Step s) {
        int[] cur = currents(p, st, s);
        for (int i = 0; i < cur.length; i++) if (cur[i] < s.conds.get(i).required()) return false;
        return true;
    }

    /** 대상: "재료" · "재료|이름(포함)" · "이름:글자" (재료 상관없이 이름에 글자가 들어 있으면). */
    static boolean matches(ItemStack it, String target) {
        if (it == null || it.getType().isAir() || target == null || target.isBlank()) return false;
        String mat = target, name = "";
        if (target.startsWith("이름:")) { mat = "*"; name = target.substring(3); }
        else {
            int bar = target.indexOf('|');
            if (bar >= 0) { mat = target.substring(0, bar); name = target.substring(bar + 1); }
        }
        if (!mat.equals("*") && !mat.isEmpty() && !it.getType().name().equalsIgnoreCase(mat)) return false;
        if (name.isEmpty()) return true;
        return itemName(it).contains(name);
    }

    static String itemName(ItemStack it) {
        ItemMeta meta = it.getItemMeta();
        if (meta == null) return "";
        var plain = PlainTextComponentSerializer.plainText();
        StringBuilder sb = new StringBuilder();
        try { if (meta.hasDisplayName() && meta.displayName() != null) sb.append(plain.serialize(meta.displayName())); } catch (RuntimeException ignored) { }
        try { if (meta.hasItemName()) sb.append('\u0001').append(plain.serialize(meta.itemName())); } catch (RuntimeException | NoSuchMethodError ignored) { }
        return sb.toString();
    }

    private static int countItems(Player p, String target) {
        int n = 0;
        for (ItemStack it : p.getInventory().getContents()) if (matches(it, target)) n += it.getAmount();
        return n;
    }

    private static void takeItems(Player p, String target, int amount) {
        ItemStack[] contents = p.getInventory().getContents();
        int left = amount;
        for (int i = 0; i < contents.length && left > 0; i++) {
            ItemStack it = contents[i];
            if (!matches(it, target)) continue;
            int take = Math.min(left, it.getAmount());
            left -= take;
            if (take >= it.getAmount()) p.getInventory().setItem(i, null);
            else { it.setAmount(it.getAmount() - take); p.getInventory().setItem(i, it); }
        }
    }

    private static boolean inside(Player p, Trigger t) {
        Location l = p.getLocation();
        if (l.getWorld() == null || !l.getWorld().getName().equals(t.world)) return false;
        double dx = l.getX() - t.x, dz = l.getZ() - t.z, dy = l.getY() - t.y;
        return dx * dx + dz * dz <= t.radius * t.radius && dy > -1.5 && dy < 2.5;
    }

    // ================================================================ 주기 확인 (0.5초)

    private void tick() {
        if (!ready) return;
        long now = System.currentTimeMillis();
        picks.entrySet().removeIf(e -> e.getValue().expires() < now);
        for (Player p : Bukkit.getOnlinePlayers()) {
            UUID uuid = p.getUniqueId();
            // 모드 채널이 늦게 잡힌 사람: 첫 단계 패킷을 아직 못 보냈으면 지금 보낸다
            if (!sessions.containsKey(uuid) && !previewing.contains(uuid) && hasMod(p)) { needStep.add(uuid); scheduleSend(); }
            P st = prog.players.get(uuid);
            if (st == null || st.step.isEmpty()) continue;
            Step s = defs.get(st.step);
            if (s == null) continue;
            // 길잡이 별: 목표 10블록 안에 오면 클라가 화살표를 끈다 → 서버도 꺼진 것으로 (다음 시전은 다시 켜기)
            // 움직이는 NPC 를 따라가는 중이면 followTick 이 맡는다 (다른 세계로 가면 상태를 보내고 끈다)
            if (guiding.containsKey(uuid) && !follows.containsKey(uuid)) {
                Location gt = guideTarget(s);
                if (gt == null || !gt.getWorld().equals(p.getWorld()) || flatNear(gt, p.getLocation())) guiding.remove(uuid);
            }
            // 잠겨 있던 장이 열렸다 (관리자 명령·공개 시각): HUD 를 다시 보내고 시작명령을 실행한다
            if (heldByGate.contains(uuid) && !chapterLocked(s)) {
                heldByGate.remove(uuid);
                needStep.add(uuid);
                scheduleSend();
                if (st.phase == Phase.ACTIVE && !s.description.isBlank()) ChacaLink.notify(p, "system", "story-step-" + st.run, "새 목표", s.description, 0);
                if (st.phase == Phase.ACTIVE && !s.startCommands.isEmpty()) runStartCommands(p, st, st.run, false);
            } else if (!heldByGate.contains(uuid) && chapterLocked(s)) {
                heldByGate.add(uuid);   // 관리자가 다시 잠금
                needStep.add(uuid);
                scheduleSend();
            }
            if (st.phase == Phase.ACTIVE && heldByGate.contains(uuid)) continue;   // 잠긴 장: 조건 확인·자동 호출 안 함
            // 포탈 밖으로 나갔으면 (또는 호출 방식이 위치가 아니게 바뀌었으면) 다시 들어올 때 호출되게
            if (mustLeave.contains(uuid) && (s.trigger.type != TrigType.LOCATION || !inside(p, s.trigger))) mustLeave.remove(uuid);
            try {
                if (st.phase == Phase.PLAYING) {
                    long since = playingSince.computeIfAbsent(uuid, k -> now);
                    if (now - since > PLAYING_TIMEOUT_MS) {
                        plugin.getLogger().warning("[스토리 단계] " + p.getName() + ": " + st.waiting + " 이(가) 30분 넘게 끝나지 않아 다시 호출할 수 있게 되돌립니다");
                        backToActive(uuid, st);
                    } else if (st.waiting.startsWith("c:") && now - since > 10_000 && !plugin.hasStory(uuid, false, st.waiting.substring(2))) {
                        // 컷신 요청이 재시도 한도·실패로 취소됨: 대화부터 다시 반복하지 않고 컷신만 몇 번 다시, 그래도 안 되면 건너뛴다
                        cutsceneFailed(p, st, s, now);
                    }
                    continue;
                }
                int[] cur = currents(p, st, s, true);
                boolean r = true;
                for (int i = 0; i < cur.length; i++) if (cur[i] < s.conds.get(i).required()) { r = false; break; }
                String key = st.run + ":" + r + ":" + java.util.Arrays.toString(cur);
                if (!key.equals(sentKey.get(uuid))) { needUpdate.add(uuid); scheduleSend(); }
                Boolean was = readyCache.put(uuid, r);
                if (r && !Boolean.TRUE.equals(was)) readySince.put(uuid, now);
                if (!r) { readySince.remove(uuid); continue; }
                Trigger t = s.trigger;
                // 주기 확인의 아이템 개수는 캐시일 수 있으니 호출 직전에 한 번 새로 확인한다
                if (t.type == TrigType.AUTO && now - readySince.getOrDefault(uuid, now) >= t.delay * 1000L && isReady(p, st, s)) fire(p, st, s, "자동");
                else if (t.type == TrigType.LOCATION && inside(p, t) && !mustLeave.contains(uuid) && isReady(p, st, s)) fire(p, st, s, "위치");
            } catch (RuntimeException ex) {
                plugin.getLogger().log(Level.SEVERE, p.getName() + ": 스토리 단계 확인 중 오류", ex);
            }
        }
    }

    private void cutsceneFailed(Player p, P st, Step s, long now) {
        UUID uuid = p.getUniqueId();
        String cutscene = st.waiting.substring(2);
        CutFail f = cutFails.get(uuid);
        int n = f != null && f.run() == st.run ? f.count() + 1 : 1;
        cutFails.put(uuid, new CutFail(st.run, n));
        if (n <= CUTSCENE_RETRIES) {
            plugin.getLogger().warning("[스토리 단계] " + p.getName() + ": " + s.id + " 의 컷신 " + cutscene + " 을(를) 재생하지 못했습니다 — 컷신만 다시 요청 (" + n + "/" + CUTSCENE_RETRIES + ")");
            playingSince.put(uuid, now);
            plugin.playCutscene(p, cutscene, StoryCodec.MODE_AUTO);
            return;
        }
        cutFails.remove(uuid);
        st.skippedCutscene = s.id + ":" + cutscene;
        plugin.getLogger().severe("[스토리 단계] " + p.getName() + "(" + uuid + "): " + s.id + " 의 컷신 " + cutscene + " 을(를) " + CUTSCENE_RETRIES
                + "번 더 시도해도 재생하지 못해 건너뛰고 다음 단계로 넘깁니다 (클라이언트 컷신 파일 확인 · /유저관리 <닉네임> 스토리진행 보기)");
        ChacaLink.notify(p, "warning", "story-cutscene-skip", "장면을 불러오지 못해 건너뛰었어요", "관리자에게 알려 주세요", 5000);
        advance(p, st, s);
    }

    // ================================================================ 이벤트

    void onJoin(Player p) {
        UUID uuid = p.getUniqueId();
        sessions.remove(uuid);
        sentKey.remove(uuid);
        sentPoints.remove(uuid);
        P st = prog.players.get(uuid);
        if (st == null && !defs.startStep.isEmpty() && defs.startAfterDialogue.isEmpty()) {
            st = prog.of(uuid);
            st.enter(defs.startStep);
            saveSoon(null);
        } else if ((st == null || !st.started) && !defs.startStep.isEmpty() && defs.get(defs.startStep) != null
                && !defs.startAfterDialogue.isEmpty() && plugin.dialogueDone(uuid, defs.startAfterDialogue)) {
            // 단계 모듈 배포 전에 시작 대화(ch1-5)를 끝낸 유저: 그 완료 신호를 다시 받을 수 없으니 접속 때 첫 단계를 시작한다
            plugin.getLogger().info("[스토리 단계] " + p.getName() + ": 대화 " + defs.startAfterDialogue + " 을(를) 이미 끝낸 기록이 있어 첫 단계 " + defs.startStep + " 을(를) 시작합니다");
            st = prog.of(uuid);
            enter(p, st, defs.startStep, true);
        }
        if (st != null) resumeFinished(p, st);
        // 대화를 보다가 나갔으면 다시 호출할 수 있게 (컷신은 스토리 대기열이 이어서 재생하므로 기다린다)
        if (st != null && st.phase == Phase.PLAYING && st.waiting.startsWith("d:")) { st.phase = Phase.ACTIVE; st.waiting = ""; saveSoon(null); }
        // 접속 직후에는 모드 채널이 늦게 잡히므로 조금 뒤에 보낸다
        Bukkit.getScheduler().runTaskLater(plugin, () -> { if (p.isOnline()) { needStep.add(uuid); scheduleSend(); } }, 60L);
        // 시작명령이 있는 단계(예: 분류의 거울)를 끝내지 못하고 나갔으면 다시 실행 (조건을 이미 채웠으면 하지 않는다)
        P cur = st;
        if (cur != null && !cur.step.isEmpty() && elsewhere(defs.get(cur.step))) {
            // 다른 서버의 단계인데 이 서버로 들어옴 (예: 배를 탄 뒤 튜토리얼로 다시 접속): 서버이동 단계면 3초 뒤 그 서버로
            Step there = defs.get(cur.step);
            Bukkit.getScheduler().runTaskLater(plugin, () -> sendToStepServer(p, there), 60L);
        } else if (cur != null && !cur.step.isEmpty()) {
            int run = cur.run;
            Bukkit.getScheduler().runTaskLater(plugin, () -> {
                Step s = defs.get(cur.step);
                if (s == null || s.startCommands.isEmpty() || !p.isOnline() || cur.run != run || isReady(p, cur, s)) return;
                runStartCommands(p, cur, run, false);
            }, 120L);
        }
    }

    @EventHandler
    public void onJoinEvent(PlayerJoinEvent e) { if (ready) onJoin(e.getPlayer()); }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        UUID uuid = e.getPlayer().getUniqueId();
        sessions.remove(uuid); sentKey.remove(uuid); sentPoints.remove(uuid); readyCache.remove(uuid); readySince.remove(uuid);
        hintAt.remove(uuid); clickGate.remove(uuid); picks.remove(uuid); previewing.remove(uuid);
        needStep.remove(uuid); needUpdate.remove(uuid); itemScans.remove(uuid);
        mustLeave.remove(uuid);
        heldByGate.remove(uuid);
        guiding.remove(uuid);
        follows.remove(uuid);
    }

    // 아이템 조건 캐시 무효화 (가벼운 표시만, 실제 세기는 다음 주기 확인 때)
    private void inventoryChanged(Entity e) { if (e instanceof Player p) itemScans.remove(p.getUniqueId()); }
    @EventHandler(priority = EventPriority.MONITOR) public void onInvClick(InventoryClickEvent e) { inventoryChanged(e.getWhoClicked()); }
    @EventHandler(priority = EventPriority.MONITOR) public void onInvDrag(InventoryDragEvent e) { inventoryChanged(e.getWhoClicked()); }
    @EventHandler(priority = EventPriority.MONITOR) public void onInvClose(InventoryCloseEvent e) { inventoryChanged(e.getPlayer()); }
    @EventHandler(priority = EventPriority.MONITOR) public void onPickup(EntityPickupItemEvent e) { inventoryChanged(e.getEntity()); }
    @EventHandler(priority = EventPriority.MONITOR) public void onDrop(PlayerDropItemEvent e) { inventoryChanged(e.getPlayer()); }
    @EventHandler(priority = EventPriority.MONITOR) public void onConsume(PlayerItemConsumeEvent e) { inventoryChanged(e.getPlayer()); }
    @EventHandler(priority = EventPriority.MONITOR) public void onBreak(PlayerItemBreakEvent e) { inventoryChanged(e.getPlayer()); }
    @EventHandler(priority = EventPriority.MONITOR) public void onPlace(BlockPlaceEvent e) { inventoryChanged(e.getPlayer()); }
    @EventHandler(priority = EventPriority.MONITOR) public void onPlayerDeath(PlayerDeathEvent e) { inventoryChanged(e.getEntity()); }

    @EventHandler(ignoreCancelled = true)
    public void onMove(PlayerMoveEvent e) {
        Location to = e.getTo();
        if (to == null || (e.getFrom().getBlockX() == to.getBlockX() && e.getFrom().getBlockY() == to.getBlockY() && e.getFrom().getBlockZ() == to.getBlockZ())) return;
        checkLocation(e.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onTeleport(PlayerTeleportEvent e) {
        Player p = e.getPlayer();
        Bukkit.getScheduler().runTask(plugin, () -> { if (p.isOnline()) checkLocation(p); });
    }

    @EventHandler
    public void onWorld(PlayerChangedWorldEvent e) {
        UUID uuid = e.getPlayer().getUniqueId();
        needStep.add(uuid);
        scheduleSend();
    }

    private void checkLocation(Player p) {
        UUID uuid = p.getUniqueId();
        if (!Boolean.TRUE.equals(readyCache.get(uuid))) return;
        P st = prog.players.get(uuid);
        Step s = st == null ? null : defs.get(st.step);
        if (s == null || st.phase != Phase.ACTIVE || s.trigger.type != TrigType.LOCATION || !inside(p, s.trigger)) return;
        if (mustLeave.contains(uuid)) return;
        if (isReady(p, st, s)) fire(p, st, s, "위치");
    }

    @EventHandler(ignoreCancelled = true)
    public void onDeath(EntityDeathEvent e) {
        LivingEntity dead = e.getEntity();
        Player killer = dead.getKiller();
        if (killer == null) return;
        P st = prog.players.get(killer.getUniqueId());
        Step s = st == null ? null : defs.get(st.step);
        if (s == null || st.phase != Phase.ACTIVE) return;
        boolean changed = false;
        for (int i = 0; i < s.conds.size(); i++) {
            Cond c = s.conds.get(i);
            if (c.type() != CondType.KILL || !mobMatches(dead, c.target())) continue;
            int cur = st.counts.getOrDefault(i, 0);
            if (cur >= c.required()) continue;
            st.counts.put(i, cur + 1);
            changed = true;
        }
        if (changed) { saveSoon(null); needUpdate.add(killer.getUniqueId()); scheduleSend(); }
    }

    private static boolean mobMatches(Entity e, String target) {
        if (target.equals("*") || target.isBlank()) return !(e instanceof Player);
        if (target.startsWith("이름:")) {
            var name = e.customName();
            return name != null && PlainTextComponentSerializer.plainText().serialize(name).contains(target.substring(3));
        }
        return e.getType().name().equalsIgnoreCase(target);
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onInteract(PlayerInteractEvent e) {
        if (e.getAction() != Action.RIGHT_CLICK_BLOCK || e.getHand() != EquipmentSlot.HAND || e.getClickedBlock() == null) return;
        Player p = e.getPlayer();
        UUID uuid = p.getUniqueId();
        String key = blockKey(e.getClickedBlock());
        Pick pick = picks.get(uuid);
        if (pick != null && pick.cond() >= 0) {
            e.setCancelled(true);
            Step s = defs.get(pick.step());
            if (s == null || pick.cond() >= s.conds.size()) { picks.remove(uuid); return; }
            Cond c = s.conds.get(pick.cond());
            List<String> blocks = new ArrayList<>(c.blocks());
            if (blocks.contains(key)) { blocks.remove(key); ChacaLink.ok(p, "블록을 뺐어요 (" + blocks.size() + "개) · " + key); }
            else if (blocks.size() >= QuestDefs.MAX_BLOCKS) { ChacaLink.fail(p, "블록은 " + QuestDefs.MAX_BLOCKS + "개까지예요", null); return; }
            else { blocks.add(key); ChacaLink.ok(p, "블록을 넣었어요 (" + blocks.size() + "개) · " + key + " · 끝나면 /대화관리 단계 지정끝"); }
            s.conds.set(pick.cond(), c.withBlocks(blocks));
            picks.put(uuid, new Pick(pick.step(), pick.cond(), System.currentTimeMillis() + PICK_MS));
            saveDefs();
            return;
        }
        P st = prog.players.get(uuid);
        Step s = st == null ? null : defs.get(st.step);
        if (s == null || st.phase != Phase.ACTIVE || elsewhere(s)) return;
        boolean changed = false;
        for (int i = 0; i < s.conds.size(); i++) {
            Cond c = s.conds.get(i);
            if (c.type() != CondType.INTERACT || !c.blocks().contains(key)) continue;
            Set<String> hit = st.hits.computeIfAbsent(i, k -> new LinkedHashSet<>());
            if (hit.size() >= c.required() || !hit.add(key)) continue;
            changed = true;
        }
        if (changed) { saveSoon(null); needUpdate.add(uuid); scheduleSend(); }
    }

    static String blockKey(Block b) { return b.getWorld().getName() + "," + b.getX() + "," + b.getY() + "," + b.getZ(); }

    // ================================================================ Citizens NPC 클릭 (AI 대화·선물·상점보다 먼저)

    private void hookCitizens() {
        Plugin c = Bukkit.getPluginManager().getPlugin("Citizens");
        if (c == null || !c.isEnabled()) {
            plugin.getLogger().warning("[스토리 단계] Citizens 가 없어 NPC클릭 호출을 쓸 수 없습니다");
            return;
        }
        try {
            ClassLoader cl = c.getClass().getClassLoader();
            Class<? extends Event> click = Class.forName("net.citizensnpcs.api.event.NPCRightClickEvent", true, cl).asSubclass(Event.class);
            npcIface = Class.forName("net.citizensnpcs.api.npc.NPC", true, cl);
            registryIface = Class.forName("net.citizensnpcs.api.npc.NPCRegistry", true, cl);
            npcId = npcIface.getMethod("getId");
            npcEntity = npcIface.getMethod("getEntity");
            npcStored = npcIface.getMethod("getStoredLocation");
            npcName = npcIface.getMethod("getName");
            registryById = registryIface.getMethod("getById", int.class);
            try {
                npcNavigator = npcIface.getMethod("getNavigator");
                navigatorNavigating = npcNavigator.getReturnType().getMethod("isNavigating");
            } catch (ReflectiveOperationException | RuntimeException ignored) {
                npcNavigator = null; navigatorNavigating = null;   // 위치 변화로만 이동 중을 판단
            }
            registry = Class.forName("net.citizensnpcs.api.CitizensAPI", true, cl).getMethod("getNPCRegistry").invoke(null);
            Method getNpc = click.getMethod("getNPC"), getClicker = click.getMethod("getClicker");
            Bukkit.getPluginManager().registerEvent(click, this, EventPriority.LOWEST, (listener, event) -> {
                if (!click.isInstance(event)) return;
                try {
                    onNpcClick(event, getNpc.invoke(event), (Player) getClicker.invoke(event));
                } catch (ReflectiveOperationException | RuntimeException ex) {
                    plugin.getLogger().log(Level.WARNING, "[스토리 단계] NPC 클릭 처리 실패", ex);
                }
            }, plugin, false);
            citizens = true;
            plugin.getLogger().info("[스토리 단계] Citizens NPC 클릭 연결 (서버 ID " + serverId() + ")");
        } catch (ReflectiveOperationException | RuntimeException ex) {
            plugin.getLogger().log(Level.WARNING, "[스토리 단계] Citizens 연결 실패 — NPC클릭 호출을 쓸 수 없습니다", ex);
        }
    }

    private void onNpcClick(Event event, Object npc, Player p) throws ReflectiveOperationException {
        if (p == null || npc == null) return;
        int id = (int) npcId.invoke(npc);
        UUID uuid = p.getUniqueId();
        Pick pick = picks.get(uuid);
        if (pick != null && pick.cond() < 0) {
            ((Cancellable) event).setCancelled(true);
            picks.remove(uuid);
            Step s = defs.get(pick.step());
            if (s == null) return;
            Trigger t = new Trigger();
            t.type = TrigType.NPC;
            t.server = serverId();
            t.npc = id;
            s.trigger = t;
            saveDefs();
            ChacaLink.ok(p, s.id + " 호출을 NPC " + String.valueOf(npcName.invoke(npc)) + " (" + id + "번)로 정했어요");
            preview(p, s);
            return;
        }
        P st = prog.players.get(uuid);
        Step s = st == null ? null : defs.get(st.step);
        if (s == null || elsewhere(s)) return;   // 다른 서버의 단계: 이 서버 NPC 번호와 섞이지 않게 (평소 대화)
        String waitDialogue = s.waiting.get(id);
        boolean called = s.trigger.type == TrigType.NPC && s.trigger.npc == id
                && (s.trigger.server.isEmpty() || s.trigger.server.equals(serverId()));
        if (!called) {
            // 이 단계의 호출 NPC 는 아니지만 대기대화가 있는 NPC (예: 낚시 단계의 올레): 조건을 못 채웠을 때만 짧은 고정 대화
            if (waitDialogue != null && st.phase == Phase.ACTIVE && !chapterLocked(s) && !isReady(p, st, s))
                showWaiting(event, p, s, id, waitDialogue);
            return;
        }
        long now = System.currentTimeMillis();
        if (st.phase == Phase.PLAYING) { ((Cancellable) event).setCancelled(true); return; }
        if (chapterLocked(s)) { tellLocked(p, 3_000); return; }   // 막지 않는다 (평소 NPC 대화는 그대로)
        if (isReady(p, st, s)) {
            ((Cancellable) event).setCancelled(true);
            Long last = clickGate.get(uuid);
            if (last != null && now - last < 400) return;
            clickGate.put(uuid, now);
            fire(p, st, s, "NPC클릭");
            return;
        }
        // 아직 준비가 안 됨: 대기대화가 있으면 그 NPC 의 짧은 고정 대화 (같은 NPC 를 곧바로 다시 누르면 평소 대화로)
        if (waitDialogue != null && showWaiting(event, p, s, id, waitDialogue)) return;
        // 대기대화가 없으면 막지 않는다 (평소대로 선물 → 고정 대화 → AI 대화). 할 일만 한 번 알려 준다
        if (now - hintAt.getOrDefault(uuid, 0L) > 5000) {
            hintAt.put(uuid, now);
            int[] cur = currents(p, st, s);
            String todo = "";
            for (int i = 0; i < cur.length; i++) if (cur[i] < s.conds.get(i).required() && !s.conds.get(i).hidden()) { todo = s.conds.get(i).display() + " " + cur[i] + "/" + s.conds.get(i).required(); break; }
            ChacaLink.notify(p, "system", "story-step-hint", "아직 할 일이 남았어요", todo, 5000);
        }
    }

    /** 대기대화를 마지막으로 보여 준 때: "플레이어|단계|NPC" → 시각. 메모리 전용 (재시작·재접속이면 다시 한 번 보여 줌). */
    private final Map<String, Long> waitingShown = new HashMap<>();

    /**
     * 대기대화 (2026-10-25 오너): 조건을 못 채운 채 NPC 를 누르면 AI 대화 대신 짧은 고정 대화 (예: 올레 "아직도 못 잡았나?").
     * 보여 준 뒤 waiting-dialogue-cooldown-seconds(기본 60) 안에 같은 NPC 를 다시 누르면 막지 않고 평소 대화(고정 대화 → AI 대화창)로
     * 넘긴다 — 피피처럼 대화창에 상점 버튼이 있는 NPC 를 대기 중에도 쓸 수 있게. 단계 진행·완료 판정에는 영향 없음.
     * @return true = 이번 클릭을 대기대화가 가져감 (이벤트 취소)
     */
    private boolean showWaiting(Event event, Player p, Step s, int npc, String dialogue) {
        long now = System.currentTimeMillis();
        long cooldown = Math.max(0, plugin.getConfig().getLong("waiting-dialogue-cooldown-seconds", 60)) * 1000L;
        String key = p.getUniqueId() + "|" + s.id + "|" + npc;
        Long last = waitingShown.get(key);
        if (last != null && now - last < cooldown) return false;
        if (waitingShown.size() > 4096) waitingShown.values().removeIf(t -> now - t > cooldown);
        waitingShown.put(key, now);
        ((Cancellable) event).setCancelled(true);
        plugin.requestNicknameDialogue(p, dialogue).thenAccept(ok -> {
            if (!Boolean.TRUE.equals(ok)) {
                waitingShown.remove(key); // 못 열었으면 다음 클릭에 다시 시도 (그 사이엔 평소 대화가 열린 게 아니라 아무것도 안 열림)
                plugin.getLogger().warning("[스토리 단계] " + p.getName() + ": " + s.id + " 대기대화 " + dialogue + " 을(를) 열지 못했습니다");
            }
        });
        return true;
    }

    // ================================================================ 다른 기능의 성공 신호 (Bukkit 이벤트, 리플렉션)

    /** 플러그인 이름 → 성공 이벤트 클래스. 각 기능이 저장·지급이 끝난 뒤에만 부른다 (getPlayer/getKey/getAmount). */
    private static final String[][] PROGRESS_EVENTS = {
            {"MagicCodexBridge", "school.magiccodex.paper.StoryProgressEvent"},   // 교화성공 · 낚시성공 · 물고기판매 · 연금술성공 · 승급성공 · 지도열기 · 웨이포인트등록 · 튜토리얼재료구매
            {"ChacaHousing", "school.chacademia.housing.StoryProgressEvent"},     // 내방열기
    };
    private final List<String> progressHooks = new ArrayList<>();

    private void hookProgressEvents() {
        for (String[] e : PROGRESS_EVENTS) {
            Plugin owner = Bukkit.getPluginManager().getPlugin(e[0]);
            if (owner == null || !owner.isEnabled()) { plugin.getLogger().warning("[스토리 단계] " + e[0] + " 이(가) 없어 그 기능의 성공 신호를 받을 수 없습니다"); continue; }
            try {
                Class<? extends Event> type = Class.forName(e[1], true, owner.getClass().getClassLoader()).asSubclass(Event.class);
                Method player = type.getMethod("getPlayer"), key = type.getMethod("getKey"), amount = type.getMethod("getAmount");
                Bukkit.getPluginManager().registerEvent(type, this, EventPriority.MONITOR, (listener, event) -> {
                    if (!type.isInstance(event)) return;
                    try {
                        if (player.invoke(event) instanceof Player p && key.invoke(event) instanceof String k && amount.invoke(event) instanceof Integer n)
                            signal(p, k, n);
                    } catch (ReflectiveOperationException | RuntimeException ex) {
                        plugin.getLogger().log(Level.WARNING, "[스토리 단계] 성공 신호 처리 실패", ex);
                    }
                }, plugin, false);
                progressHooks.add(e[0]);
            } catch (ReflectiveOperationException | RuntimeException ex) {
                plugin.getLogger().warning("[스토리 단계] " + e[0] + " 성공 신호 연결 실패 (버전이 낮음?): " + ex);
            }
        }
        if (!progressHooks.isEmpty()) plugin.getLogger().info("[스토리 단계] 성공 신호 연결: " + String.join(", ", progressHooks));
    }

    private Object npcById(int id) {
        if (!citizens || id < 0) return null;
        try { return registryById.invoke(registry, id); } catch (ReflectiveOperationException | RuntimeException e) { return null; }
    }

    private Location npcLocation(Object npc) {
        try {
            Object ent = npcEntity.invoke(npc);
            if (ent instanceof Entity en) return en.getLocation();
            Object loc = npcStored.invoke(npc);
            return loc instanceof Location l ? l : null;
        } catch (ReflectiveOperationException | RuntimeException e) { return null; }
    }

    /** 지금 스폰된 NPC 엔티티의 위치 (언로드·디스폰이면 null — 저장 위치는 {@link #npcLocation}). */
    private Location npcLiveLocation(Object npc) {
        try {
            Object ent = npcEntity.invoke(npc);
            return ent instanceof Entity en && en.isValid() ? en.getLocation() : null;
        } catch (ReflectiveOperationException | RuntimeException e) { return null; }
    }

    /** Citizens 길찾기로 걷는 중인지 (API 가 없거나 실패하면 false). */
    private boolean npcNavigating(Object npc) {
        if (npcNavigator == null || navigatorNavigating == null || npc == null) return false;
        try {
            Object nav = npcNavigator.invoke(npc);
            return nav != null && Boolean.TRUE.equals(navigatorNavigating.invoke(nav));
        } catch (ReflectiveOperationException | RuntimeException e) { return false; }
    }

    private String npcEntityId(Object npc) {
        try {
            Object ent = npcEntity.invoke(npc);
            return ent instanceof Entity en ? en.getUniqueId().toString() : "";
        } catch (ReflectiveOperationException | RuntimeException e) { return ""; }
    }

    // ================================================================ 패킷

    private void scheduleSend() {
        if (sendScheduled || !ready) return;
        sendScheduled = true;
        Bukkit.getScheduler().runTask(plugin, this::sendAll);
    }

    private boolean hasMod(Player p) { return p.getListeningPluginChannels().contains(QuestCodec.STEP); }

    private void sendAll() {
        sendScheduled = false;
        for (UUID uuid : new ArrayList<>(needStep)) {
            Player p = Bukkit.getPlayer(uuid);
            needUpdate.remove(uuid);
            if (p == null || !p.isOnline() || previewing.contains(uuid)) continue;
            sendStep(p);
        }
        needStep.clear();
        for (UUID uuid : new ArrayList<>(needUpdate)) {
            Player p = Bukkit.getPlayer(uuid);
            if (p == null || !p.isOnline() || previewing.contains(uuid)) continue;
            sendUpdate(p);
        }
        needUpdate.clear();
        // 준비가 막 끝난 자동 호출(지연 0)은 다음 확인(0.5초)을 기다리지 않는다
    }

    private void sendStep(Player p) {
        UUID uuid = p.getUniqueId();
        P st = prog.players.get(uuid);
        Step s = st == null ? null : defs.get(st.step);
        if (!hasMod(p)) return;
        int session = sessions.merge(uuid, 1, Integer::sum);
        if (s == null) {
            p.sendPluginMessage(plugin, QuestCodec.STEP, QuestCodec.step(new QuestCodec.StepView(session, "", 0, "", "", false, List.of(),
                    QuestCodec.TARGET_NONE, "", 0, 0, 0, 0, "")));
            sentKey.put(uuid, "");
            return;
        }
        int[] cur = currents(p, st, s, true);
        boolean r = isReadyFrom(s, cur) && st.phase == Phase.ACTIVE && !chapterLocked(s);
        p.sendPluginMessage(plugin, QuestCodec.STEP, QuestCodec.step(view(session, s, st.run, cur, r, true, st.picks)));
        sentKey.put(uuid, st.run + ":" + isReadyFrom(s, cur) + ":" + java.util.Arrays.toString(cur));
        sendPoints(p, session, st, s, true);
    }

    /** 마지막으로 보낸 목표 지점 (플레이어 → "session|블록키…"). 같으면 다시 보내지 않는다. 메모리 전용. */
    private final Map<UUID, String> sentPoints = new HashMap<>();

    /**
     * 아직 안 누른 상호작용 조건 블록들을 목표 지점으로 보낸다 (예: 이론서 세 권 = 조건 3개 × 블록 1개 → 징표 3개).
     * 숨김 조건·다 채운 조건·이미 누른 블록·공개 전인 장은 빼고, 조건 순서대로 최대 16개.
     */
    private void sendPoints(Player p, int session, P st, Step s, boolean force) {
        if (!p.getListeningPluginChannels().contains(QuestCodec.POINTS)) return;
        UUID uuid = p.getUniqueId();
        List<QuestCodec.Point> points = st.phase == Phase.ACTIVE && !chapterLocked(s) && !elsewhere(s) ? pendingPoints(st, s) : List.of();
        StringBuilder key = new StringBuilder().append(session);
        for (QuestCodec.Point pt : points) key.append('|').append(pt.world()).append(',').append(pt.x()).append(',').append(pt.y()).append(',').append(pt.z());
        String k = key.toString();
        if (!force && k.equals(sentPoints.get(uuid))) return;
        if (force && points.isEmpty() && !sentPoints.containsKey(uuid)) return; // 처음부터 없음: 보낼 것 없음
        sentPoints.put(uuid, k);
        p.sendPluginMessage(plugin, QuestCodec.POINTS, QuestCodec.points(session, points));
    }

    private List<QuestCodec.Point> pendingPoints(P st, Step s) {
        List<QuestCodec.Point> out = new ArrayList<>();
        for (int i = 0; i < s.conds.size() && out.size() < QuestCodec.MAX_POINTS; i++) {
            Cond c = s.conds.get(i);
            if (c.type() != CondType.INTERACT || c.hidden()) continue;
            Set<String> hit = st.hits.getOrDefault(i, Set.of());
            if (hit.size() >= c.required()) continue;
            for (String b : c.blocks()) {
                if (out.size() >= QuestCodec.MAX_POINTS) break;
                if (hit.contains(b)) continue;
                Location l = blockCenter(b);
                if (l != null) out.add(new QuestCodec.Point(l.getWorld().getKey().toString(), l.getX(), l.getY(), l.getZ()));
            }
        }
        return out;
    }

    /** blockKey("세계,x,y,z") → 블록 가운데 위 (징표 자리). 세계가 없거나 형식이 틀리면 null. */
    private static Location blockCenter(String key) {
        String[] a = key.split(",");
        if (a.length != 4) return null;
        World w = Bukkit.getWorld(a[0]);
        if (w == null) return null;
        try { return new Location(w, Integer.parseInt(a[1]) + 0.5, Integer.parseInt(a[2]) + 1.0, Integer.parseInt(a[3]) + 0.5); }
        catch (NumberFormatException e) { return null; }
    }

    private void sendUpdate(Player p) {
        UUID uuid = p.getUniqueId();
        P st = prog.players.get(uuid);
        Step s = st == null ? null : defs.get(st.step);
        Integer session = sessions.get(uuid);
        if (s == null || session == null || !hasMod(p)) return;
        int[] cur = currents(p, st, s, true);
        boolean full = isReadyFrom(s, cur);
        int[] visible = visible(s, cur);
        p.sendPluginMessage(plugin, QuestCodec.UPDATE, QuestCodec.update(session, s.id, st.run, full && st.phase == Phase.ACTIVE && !chapterLocked(s), visible));
        sentKey.put(uuid, st.run + ":" + full + ":" + java.util.Arrays.toString(cur));
        sendPoints(p, session, st, s, false);
    }

    private static boolean isReadyFrom(Step s, int[] cur) {
        for (int i = 0; i < cur.length; i++) if (cur[i] < s.conds.get(i).required()) return false;
        return true;
    }

    private static int[] visible(Step s, int[] cur) {
        int n = 0;
        for (Cond c : s.conds) if (!c.hidden()) n++;
        int[] out = new int[n];
        int j = 0;
        for (int i = 0; i < cur.length; i++) if (!s.conds.get(i).hidden()) out[j++] = cur[i];
        return out;
    }

    private QuestCodec.StepView view(int session, Step s, int run, int[] cur, boolean readyFlag) { return view(session, s, run, cur, readyFlag, true, Map.of()); }

    /** gated = false: 운영자 미리보기 (잠긴 장의 포탈·NPC 위치도 보여 준다). */
    private QuestCodec.StepView view(int session, Step s, int run, int[] cur, boolean readyFlag, boolean gated, Map<Integer, String> picks) {
        if (gated && chapterLocked(s)) {
            // 공개 전인 장: 장 이름·설명·목표 위치를 보내지 않는다 (미리 보이지 않게, 포탈·징표 없음)
            return new QuestCodec.StepView(session, s.id, run, "스토리", gate.title, false, List.of(), QuestCodec.TARGET_NONE, "", 0, 0, 0, 0, "");
        }
        List<QuestCodec.CondView> conds = new ArrayList<>();
        for (int i = 0; i < s.conds.size(); i++) {
            Cond c = s.conds.get(i);
            if (!c.hidden()) conds.add(new QuestCodec.CondView(c.type() == CondType.RANDOM ? c.displayPicked(picks.get(i)) : c.display(), cur.length > i ? cur[i] : 0, c.required()));
        }
        Trigger t = s.trigger;
        int type = QuestCodec.TARGET_NONE;
        String world = "", entity = "";
        double x = 0, y = 0, z = 0, radius = 0;
        if (gated && elsewhere(s)) {
            // 다른 서버의 단계: 목표 위치는 보내지 않는다 (두 서버 세계 이름이 같아 엉뚱한 곳에 포탈이 생기지 않게)
        } else if (t.type == TrigType.LOCATION) {
            World w = Bukkit.getWorld(t.world);
            if (w != null) { type = QuestCodec.TARGET_LOCATION; world = w.getKey().toString(); x = t.x; y = t.y; z = t.z; radius = t.radius; }
        } else if (t.type == TrigType.NPC && (t.server.isEmpty() || t.server.equals(serverId()))) {
            Object npc = npcById(t.npc);
            Location l = npc == null ? null : npcLocation(npc);
            if (l != null && l.getWorld() != null) {
                type = QuestCodec.TARGET_NPC; world = l.getWorld().getKey().toString();
                x = l.getX(); y = l.getY(); z = l.getZ(); entity = npcEntityId(npc);
            }
        }
        String chapter = s.chapter.isBlank() ? "스토리" : s.chapter;
        return new QuestCodec.StepView(session, s.id, run, chapter, s.description, readyFlag, conds, type, world, x, y, z, radius, entity);
    }

    /** 길잡이 별 결과 (MagicMythic 132 guiding_star 가 리플렉션으로 부른다). OFF = 켜져 있던 화살표를 끔 (다시 시전). */
    static final int GUIDE_NONE = 0, GUIDE_OK = 1, GUIDE_OTHER_WORLD = 2, GUIDE_NEAR = 3, GUIDE_OFF = 4;
    /** 이 안이면 화살표를 띄우지 않는다 (클라 QuestWorld.GUIDE_NEAR 와 같은 값). */
    static final double GUIDE_NEAR_DISTANCE = 10;

    /** 단계 목표 위치 (위치 포탈 / 이 서버의 NPC클릭 NPC). 없으면 null (자동·컷신·미지정·다른 서버 NPC). */
    private Location guideTarget(Step s) {
        if (elsewhere(s)) return null;
        Trigger t = s.trigger;
        if (t.type == TrigType.LOCATION) {
            World w = Bukkit.getWorld(t.world);
            return w == null ? null : new Location(w, t.x, t.y, t.z);
        }
        if (t.type == TrigType.NPC && (t.server.isEmpty() || t.server.equals(serverId()))) {
            Object npc = npcById(t.npc);
            Location l = npc == null ? null : npcLocation(npc);
            return l == null || l.getWorld() == null ? null : l;
        }
        return null;
    }

    /** 위아래로 이만큼 넘게 떨어져 있으면 가까워도 화살표를 띄운다 (배포 jar final-guide-20261024 에 있던 값, 2026-10-28 소스에 옮김). */
    private static final double GUIDE_NEAR_HEIGHT = 3.0;

    private static boolean flatNear(Location a, Location b) {
        double dx = a.getX() - b.getX(), dz = a.getZ() - b.getZ();
        return dx * dx + dz * dz <= GUIDE_NEAR_DISTANCE * GUIDE_NEAR_DISTANCE && Math.abs(a.getY() - b.getY()) <= GUIDE_NEAR_HEIGHT;
    }

    /**
     * 퀘스트창 메인 스토리 (2026-10-25 오너: 런처 기록 대신 서버 기록). 단계 정의 순서대로, 끝낸 단계 = claimed, 지금 단계 = active.
     * 각 줄: id · title · description · state · chapter · details. meta(대화 id) = {대화 제목, 대화 장} (없으면 null).
     */
    List<Map<String, String>> journalRows(UUID uuid, java.util.function.Function<String, String[]> meta) {
        P st = prog.players.get(uuid);
        if (st == null) return List.of();
        Player p = Bukkit.getPlayer(uuid);
        List<Map<String, String>> out = new ArrayList<>();
        for (Step s : defs.steps.values()) {
            boolean current = s.id.equals(st.step);
            if (!current && !st.history.contains(s.id)) continue;
            if (!s.id.matches("[a-z0-9_-]{1,64}")) continue;
            String[] m = s.dialogue.isEmpty() ? null : meta.apply(s.dialogue);
            String title = m != null && !m[0].isBlank() ? m[0] : !s.description.isBlank() ? s.description : s.id;
            String chapter = m != null && !m[1].isBlank() ? m[1] : s.chapter;
            StringBuilder details = new StringBuilder();
            if (current) {
                int[] cur = currents(p, st, s, true);
                for (int i = 0; i < cur.length; i++) {
                    Cond c = s.conds.get(i);
                    if (c.hidden()) continue;
                    if (details.length() > 0) details.append('\n');
                    details.append(c.label().isBlank() ? c.target() : c.label()).append(' ').append(cur[i]).append(" / ").append(c.required());
                }
            }
            Map<String, String> row = new LinkedHashMap<>();
            row.put("id", s.id);
            row.put("title", title);
            row.put("description", s.description);
            row.put("state", current ? "active" : "claimed");
            row.put("chapter", chapter);
            row.put("details", details.toString());
            out.add(row);
        }
        return out;
    }

    /**
     * 길잡이 별 (오너 2026-10-20·10-23): 지금 단계의 목표(위치 포탈 / 말 걸 NPC)를 화살표로 가리킨다. 조건을 아직 못 채운 단계도
     * 가리킨다. 켜져 있을 때 다시 시전하면 끈다. 목표 10블록 안에서는 클라가 스스로 끈다. 미리보기 중 = NONE.
     * 2026-10-26 메모 #93: 목표가 NPC 면 그 NPC 의 지금 위치를 따라간다 ({@link #followTick}). 스토리에 가리킬 목표가 없으면
     * (단계 없음·공개 전인 장·목표 없는 단계) 진행 중 의뢰의 NPC 목표를 가리킨다 ({@link #guideQuestNpc}), 그것도 없으면 NONE.
     */
    int guide(Player p) {
        if (!ready || p == null || !p.isOnline() || !p.getListeningPluginChannels().contains(QuestCodec.GUIDE)) return GUIDE_NONE;
        UUID uuid = p.getUniqueId();
        P st = prog.players.get(uuid);
        Step s = st == null ? null : defs.get(st.step);
        Integer session = sessions.get(uuid);
        if (session == null || previewing.contains(uuid)) return GUIDE_NONE;
        Integer on = guiding.get(uuid);
        if (on != null && on.equals(session)) {
            guiding.remove(uuid);
            follows.remove(uuid);
            p.sendPluginMessage(plugin, QuestCodec.GUIDE, QuestCodec.guide(0));
            return GUIDE_OFF;
        }
        boolean story = s != null && !chapterLocked(s);
        Location target = story ? guideTarget(s) : null;
        int npc = story && target != null && s.trigger.type == TrigType.NPC ? s.trigger.npc : -1;
        if (story && target == null && st.phase == Phase.ACTIVE) {
            // 호출 목표가 없는 단계(예: 이론서 세 권, 호출 자동): 아직 안 누른 목표 블록 중 같은 세계의 가장 가까운 것
            double best = Double.MAX_VALUE;
            for (QuestCodec.Point pt : pendingPoints(st, s)) {
                org.bukkit.NamespacedKey wk = org.bukkit.NamespacedKey.fromString(pt.world());
                World w = wk == null ? null : Bukkit.getWorld(wk);
                if (w == null) continue;
                Location l = new Location(w, pt.x(), pt.y(), pt.z());
                double d = w.equals(p.getWorld()) ? l.distanceSquared(p.getLocation()) : Double.MAX_VALUE / 2;
                if (d < best) { best = d; target = l; }
            }
        }
        // 스토리에 가리킬 목표가 없음 (단계 없음·다 끝냄·목표 없는 단계·공개 전인 장): 진행 중 의뢰의 NPC 목표 (#93)
        if (target == null) return guideQuestNpc(p, session);
        if (!target.getWorld().equals(p.getWorld())) return GUIDE_OTHER_WORLD;
        if (flatNear(target, p.getLocation())) return GUIDE_NEAR;
        guiding.put(uuid, session);
        if (npc >= 0) startFollow(p, session, npc, false);
        p.sendPluginMessage(plugin, QuestCodec.GUIDE, QuestCodec.guide(session));
        return GUIDE_OK;
    }

    /**
     * 스토리 단계에 가리킬 목표가 없을 때 (2026-10-26 메모 #93): 진행 중인 MagicCodex 의뢰의 아직 못 채운 "NPC" 목표
     * (ChacaNPC 캐릭터 등 이 서버의 Citizens NPC) 중 같은 세계의 가장 가까운 NPC. 새 클라(GUIDE_TARGET)만 — 화살표 위치를 이 패킷으로 받는다.
     */
    private int guideQuestNpc(Player p, int session) {
        if (!citizens || !p.getListeningPluginChannels().contains(QuestCodec.GUIDE_TARGET)) return GUIDE_NONE;
        MagicCodexLink codex = plugin.codex();
        int[] ids = codex == null ? new int[0] : codex.questNpcTargets(p);
        int best = -1;
        double bestD = Double.MAX_VALUE;
        boolean elsewhere = false;
        Location bestAt = null;
        for (int id : ids) {
            Object npc = npcById(id);
            Location l = npc == null ? null : npcLocation(npc);
            if (l == null || l.getWorld() == null) continue;
            if (!l.getWorld().equals(p.getWorld())) { elsewhere = true; continue; }
            double d = l.distanceSquared(p.getLocation());
            if (d < bestD) { bestD = d; best = id; bestAt = l; }
        }
        if (best < 0) return elsewhere ? GUIDE_OTHER_WORLD : GUIDE_NONE;
        if (flatNear(bestAt, p.getLocation())) return GUIDE_NEAR;
        guiding.put(p.getUniqueId(), session);
        startFollow(p, session, best, true);
        p.sendPluginMessage(plugin, QuestCodec.GUIDE, QuestCodec.guide(session));
        return GUIDE_OK;
    }

    /** NPC 따라가기 시작: 지금 위치를 바로 한 번 보낸다 (단계 패킷의 NPC 좌표는 단계를 받은 때 것이라 낡았을 수 있음). 옛 클라는 하지 않는다. */
    private void startFollow(Player p, int session, int npcId, boolean quest) {
        if (!p.getListeningPluginChannels().contains(QuestCodec.GUIDE_TARGET)) return;
        Follow f = new Follow(session, npcId, quest);
        follows.put(p.getUniqueId(), f);
        refreshFollow(p, f, true);
    }

    /**
     * NPC 위치를 다시 보고 필요하면 보낸다. 반환 false = 따라가기 끝 (다른 세계 / 도착 — 클라가 스스로 화살표를 끈다).
     * force = 바뀐 게 없어도 보냄 (시작할 때).
     */
    private boolean refreshFollow(Player p, Follow f, boolean force) {
        Object npc = npcById(f.npc);
        Location live = npc == null ? null : npcLiveLocation(npc);
        Location l = live != null ? live : npc == null ? null : npcLocation(npc);
        int state;
        if (l == null || l.getWorld() == null) state = QuestCodec.FOLLOW_UNLOADED;   // 없어졌거나 위치 모름: 마지막 위치 그대로
        else if (!l.getWorld().equals(p.getWorld())) state = QuestCodec.FOLLOW_OTHER_WORLD;
        else state = live != null ? QuestCodec.FOLLOW_LIVE : QuestCodec.FOLLOW_UNLOADED;
        boolean moving = false;
        if (state == QuestCodec.FOLLOW_LIVE) {
            Location before = f.checked;
            moving = npcNavigating(npc) || before != null && before.getWorld() == l.getWorld() && flatDistSq(before, l) > FOLLOW_MOVING * FOLLOW_MOVING;
        }
        f.checked = state == QuestCodec.FOLLOW_LIVE ? l.clone() : null;
        boolean moved = false;
        if (state != QuestCodec.FOLLOW_OTHER_WORLD && l != null && l.getWorld() != null) {
            String wk = l.getWorld().getKey().toString();
            double dx = l.getX() - f.x, dy = l.getY() - f.y, dz = l.getZ() - f.z;
            moved = !wk.equals(f.world) || dx * dx + dy * dy + dz * dz >= FOLLOW_MOVE * FOLLOW_MOVE;
            if (moved || force) { f.world = wk; f.x = l.getX(); f.y = l.getY(); f.z = l.getZ(); }
        }
        if (npc != null) { String ent = npcEntityId(npc); if (!ent.isEmpty()) f.entity = ent; }
        boolean send = force || moved || state != f.state || moving != f.moving;
        f.state = state;
        f.moving = moving;
        if (send) p.sendPluginMessage(plugin, QuestCodec.GUIDE_TARGET, QuestCodec.guideTarget(f.session, f.state, f.moving, f.world, f.x, f.y, f.z, f.entity));
        if (state == QuestCodec.FOLLOW_OTHER_WORLD) return false;
        return state != QuestCodec.FOLLOW_LIVE || !flatNear(l, p.getLocation());
    }

    /** 2초마다: NPC 를 따라가는 사람만 (보통 몇 명). 단계가 바뀌었거나 껐거나 의뢰가 끝났으면 정리. */
    private void followTick() {
        if (!ready || follows.isEmpty()) return;
        for (var iter = follows.entrySet().iterator(); iter.hasNext(); ) {
            var e = iter.next();
            UUID uuid = e.getKey();
            Follow f = e.getValue();
            Player p = Bukkit.getPlayer(uuid);
            Integer on = guiding.get(uuid), session = sessions.get(uuid);
            if (p == null || !p.isOnline() || on == null || on != f.session || session == null || session != f.session) { iter.remove(); continue; }
            try {
                if (f.quest && !questNpcStill(p, f.npc)) {
                    // 의뢰를 끝냈거나 포기함: 화살표를 끈다
                    iter.remove();
                    guiding.remove(uuid);
                    p.sendPluginMessage(plugin, QuestCodec.GUIDE, QuestCodec.guide(0));
                    continue;
                }
                if (!refreshFollow(p, f, false)) { iter.remove(); guiding.remove(uuid); }
            } catch (RuntimeException ex) {
                iter.remove();
                plugin.getLogger().log(Level.WARNING, p.getName() + ": 길잡이 별 NPC 위치 확인 중 오류", ex);
            }
        }
    }

    private boolean questNpcStill(Player p, int npcId) {
        MagicCodexLink codex = plugin.codex();
        if (codex == null) return false;
        for (int id : codex.questNpcTargets(p)) if (id == npcId) return true;
        return false;
    }

    private static double flatDistSq(Location a, Location b) {
        double dx = a.getX() - b.getX(), dz = a.getZ() - b.getZ();
        return dx * dx + dz * dz;
    }

    /** 운영자 화면에 이 단계를 "준비 완료" 상태로 보여 준다 (포탈·징표 위치 확인용). 진행 기록은 건드리지 않는다. */
    private void preview(Player p, Step s) {
        if (!hasMod(p)) { ChacaLink.ok(p, "스토리 모드가 없어 미리보기를 보여 줄 수 없어요"); return; }
        previewing.add(p.getUniqueId());
        int session = sessions.merge(p.getUniqueId(), 1, Integer::sum);
        int[] cur = new int[s.conds.size()];
        for (int i = 0; i < cur.length; i++) cur[i] = s.conds.get(i).required();
        p.sendPluginMessage(plugin, QuestCodec.STEP, QuestCodec.step(view(session, s, 0, cur, true, false, Map.of())));
    }

    private void endPreview(Player p) {
        previewing.remove(p.getUniqueId());
        needStep.add(p.getUniqueId());
        scheduleSend();
    }

    // ================================================================ 명령어

    private static final String ID = "단계ID";
    /** 기숙사 분류 6문항 대화 ID (SchoolBridge 가 띄움 — SchoolBridge 의 "ch1-19"·"ch1-19r/m/f-" 와 같아야 함. 2026-10-27 정문·그레고르·거울 앞 단계가 생겨 ch1-17 → ch1-19). */
    static final String SORTING_DIALOGUE = "ch1-17";   // 배포 jar 와 같게 (2026-10-28)

    void registerCommands() {
        if (!ChacaLink.ready()) return;
        var root = Commands.literal("단계");
        root.then(Commands.literal("목록").executes(this::cmdList));
        root.then(Commands.literal("시작설정").then(stepArg("시작단계")
                .executes(ctx -> cmdStart(ctx, ""))
                .then(Commands.literal("대화완료").then(Commands.argument("대화ID", ChacaLink.word(List::of))
                        .executes(ctx -> cmdStart(ctx, ctx.getArgument("대화ID", String.class)))))));
        root.then(Commands.literal("점검").executes(this::cmdCheck));
        root.then(Commands.literal("지정끝").executes(ctx -> { Player p = self(ctx); picks.remove(p.getUniqueId()); ChacaLink.ok(p, "지정을 끝냈어요"); return 1; }));
        root.then(Commands.literal("미리보기끝").executes(ctx -> { endPreview(self(ctx)); ChacaLink.ok(ctx.getSource().getSender(), "미리보기를 끝내고 내 진행 상태로 돌아왔어요"); return 1; }));

        var one = stepArg(ID);
        one.then(Commands.literal("만들기").executes(this::cmdCreate));
        one.then(Commands.literal("삭제").executes(ctx -> { throw ChacaLink.error("되돌릴 수 없어요. 끝에 '확인'을 붙여 다시 입력해 주세요"); })
                .then(Commands.literal("확인").executes(this::cmdDelete)));
        one.then(Commands.literal("정보").executes(this::cmdInfo));
        one.then(Commands.literal("장").then(Commands.argument("문구", StringArgumentType.greedyString()).executes(ctx -> edit(ctx, s -> s.chapter = clip(ctx, 64), "장 이름"))));
        one.then(Commands.literal("설명").then(Commands.argument("문구", StringArgumentType.greedyString()).executes(ctx -> edit(ctx, s -> s.description = clip(ctx, 200), "설명"))));
        one.then(Commands.literal("대화").then(idOrNone("대화ID").executes(ctx -> edit(ctx, s -> s.dialogue = idArg(ctx, "대화ID"), "대화"))));
        one.then(Commands.literal("컷신").then(idOrNone("컷신ID").executes(ctx -> edit(ctx, s -> s.cutscene = idArg(ctx, "컷신ID"), "컷신"))));
        one.then(Commands.literal("다음").then(Commands.argument("다음단계", ChacaLink.word(this::stepIdsAndNone))
                .executes(ctx -> edit(ctx, s -> s.next = idArg(ctx, "다음단계"), "다음 단계"))));

        var cond = Commands.literal("조건");
        cond.then(Commands.literal("추가").then(Commands.argument("종류", ChacaLink.word(() -> List.of("아이템", "제출", "처치", "상호작용", "대화완료", "신호", "권한", "값", "랜덤제출")))
                .then(Commands.argument("대상", ChacaLink.word(() -> List.of("손", "*", "블록지정", "교화성공", "낚시성공", "연금술성공", "승급성공", "결투승리", "큐디치참가", "변색종교화", "원소반응", "마력코어제작", "던전클리어", "몬스터기록", "기숙사배정", "내방열기", "chacademi.story.duel")))
                        .then(Commands.argument("개수", IntegerArgumentType.integer(1, 10000))
                                .executes(ctx -> cmdCondAdd(ctx, ""))
                                .then(Commands.argument("표시", StringArgumentType.greedyString())
                                        .executes(ctx -> cmdCondAdd(ctx, ctx.getArgument("표시", String.class))))))));
        cond.then(Commands.literal("삭제").then(Commands.argument("번호", IntegerArgumentType.integer(1, QuestDefs.MAX_CONDITIONS)).executes(this::cmdCondDelete)));
        cond.then(Commands.literal("숨김").then(Commands.argument("번호", IntegerArgumentType.integer(1, QuestDefs.MAX_CONDITIONS)).executes(this::cmdCondHide)));
        cond.then(Commands.literal("블록지정").then(Commands.argument("번호", IntegerArgumentType.integer(1, QuestDefs.MAX_CONDITIONS)).executes(this::cmdCondPick)));
        cond.then(Commands.literal("목록").executes(this::cmdInfo));
        one.then(cond);

        var call = Commands.literal("호출");
        call.then(Commands.literal("없음").executes(ctx -> setTrigger(ctx, new Trigger())));
        call.then(Commands.literal("자동").executes(ctx -> setTrigger(ctx, auto(0)))
                .then(Commands.argument("지연초", IntegerArgumentType.integer(0, 600)).executes(ctx -> setTrigger(ctx, auto(ctx.getArgument("지연초", Integer.class))))));
        call.then(Commands.literal("NPC클릭").executes(this::cmdPickNpc));
        call.then(Commands.literal("NPC").then(Commands.argument("NPC번호", IntegerArgumentType.integer(0)).executes(ctx -> {
            Trigger t = new Trigger(); t.type = TrigType.NPC; t.server = serverId(); t.npc = ctx.getArgument("NPC번호", Integer.class);
            if (citizens && npcById(t.npc) == null) throw ChacaLink.error("Citizens " + t.npc + "번 NPC가 없어요");
            return setTrigger(ctx, t);
        })));
        call.then(Commands.literal("위치").executes(ctx -> setTrigger(ctx, here(ctx, QuestDefs.DEFAULT_RADIUS)))
                .then(Commands.argument("반경", DoubleArgumentType.doubleArg(QuestDefs.MIN_RADIUS, QuestDefs.MAX_RADIUS))
                        .executes(ctx -> setTrigger(ctx, here(ctx, ctx.getArgument("반경", Double.class))))));
        call.then(Commands.literal("컷신").then(Commands.argument("컷신ID", ChacaLink.word(List::of)).executes(ctx -> {
            Trigger t = new Trigger(); t.type = TrigType.CUTSCENE; t.cutscene = idArg(ctx, "컷신ID");
            if (t.cutscene.isEmpty()) throw ChacaLink.error("컷신 ID는 영문 소문자·숫자·_·-만 쓸 수 있어요");
            return setTrigger(ctx, t);
        })));
        one.then(call);
        one.then(Commands.literal("시작명령")
                .then(Commands.literal("추가").then(Commands.argument("명령", StringArgumentType.greedyString()).executes(ctx -> edit(ctx, s -> {
                    if (s.startCommands.size() >= QuestDefs.MAX_START_COMMANDS) throw ChacaLink.error("시작명령은 " + QuestDefs.MAX_START_COMMANDS + "개까지예요");
                    String c = ctx.getArgument("명령", String.class).strip();
                    if (c.startsWith("/")) c = c.substring(1);
                    if (c.isBlank() || StoryPlugin.legacyCommand(c)) throw ChacaLink.error("빈 명령이나 예전(Skript) 명령은 넣을 수 없어요");
                    s.startCommands.add(c);
                }, "시작명령"))))
                .then(Commands.literal("비우기").executes(ctx -> edit(ctx, s -> s.startCommands.clear(), "시작명령"))));
        one.then(Commands.literal("대기대화")
                .then(Commands.literal("추가").then(Commands.argument("NPC번호", IntegerArgumentType.integer(0, 1_000_000))
                        .then(Commands.argument("대화", StringArgumentType.word()).executes(ctx -> edit(ctx, s -> {
                            String d = ctx.getArgument("대화", String.class).strip();
                            if (!StorySafety.validId(d)) throw ChacaLink.error("대화 ID 형식이 아니에요");
                            int npc = ctx.getArgument("NPC번호", Integer.class);
                            if (!s.waiting.containsKey(npc) && s.waiting.size() >= QuestDefs.MAX_WAITING) throw ChacaLink.error("대기대화는 단계마다 " + QuestDefs.MAX_WAITING + "개까지예요");
                            s.waiting.put(npc, d);
                        }, "대기대화")))))
                .then(Commands.literal("삭제").then(Commands.argument("NPC번호", IntegerArgumentType.integer(0, 1_000_000)).executes(ctx -> edit(ctx, s -> {
                    if (s.waiting.remove(ctx.getArgument("NPC번호", Integer.class)) == null) throw ChacaLink.error("그 NPC 의 대기대화가 없어요");
                }, "대기대화"))))
                .then(Commands.literal("비우기").executes(ctx -> edit(ctx, s -> s.waiting.clear(), "대기대화")))
                .then(Commands.literal("목록").executes(ctx -> {
                    Step s = step(ctx);
                    var out = new ArrayList<String>();
                    out.add(s.id + " 대기대화 (조건을 못 채운 채 그 NPC 를 누르면)");
                    if (s.waiting.isEmpty()) out.add("(없음)");
                    s.waiting.forEach((npc, d) -> out.add("NPC " + npc + " → " + d));
                    ChacaLink.lines(ctx.getSource().getSender(), out);
                    return 1;
                })));
        one.then(Commands.literal("이동").executes(this::cmdGoto));
        one.then(Commands.literal("미리보기").executes(ctx -> { Player p = self(ctx); preview(p, step(ctx)); ChacaLink.ok(p, "미리보기 중 · 끝내려면 /대화관리 단계 미리보기끝"); return 1; }));
        root.then(one);
        ChacaLink.branch(plugin, ChacaLink.대화관리, root, "스토리 진행 단계 (조건·호출·다음 대화) 만들기·수정", "단계 목록");

        ChacaLink.branch(plugin, ChacaLink.대화관리, Commands.literal("신호")
                .then(Commands.argument("닉네임", ChacaLink.player())
                        .then(Commands.argument("키", ChacaLink.word(() -> List.of("교화성공", "낚시성공", "물고기판매", "연금술성공", "승급성공", "결투승리", "큐디치참가", "변색종교화", "원소반응", "마력코어제작", "던전클리어", "몬스터기록", "기숙사배정", "내방열기", "지도열기", "웨이포인트등록", "튜토리얼재료구매")))
                                .executes(ctx -> cmdSignal(ctx, 1))
                                .then(Commands.argument("개수", IntegerArgumentType.integer(1, 10000))
                                        .executes(ctx -> cmdSignal(ctx, ctx.getArgument("개수", Integer.class)))))),
                "스토리 단계 신호 조건 채우기 (다른 기능·Skript 연결용, 콘솔 가능)", "신호 ");

        ChacaLink.branch(plugin, ChacaLink.대화관리, Commands.literal("장공개")
                .then(Commands.literal("목록").executes(this::cmdGateList))
                .then(Commands.literal("열기").then(Commands.argument("장", IntegerArgumentType.integer(0, 999)).executes(ctx -> cmdGateSet(ctx, true))))
                .then(Commands.literal("잠그기").then(Commands.argument("장", IntegerArgumentType.integer(0, 999)).executes(ctx -> cmdGateSet(ctx, false)))),
                "스토리 장 공개 상태 보기·즉시 공개·다시 잠그기 (config.yml chapter-release)", "장공개 목록");

        ChacaLink.branch(plugin, ChacaLink.유저관리, Commands.literal("스토리진행")
                .then(Commands.literal("보기").executes(this::cmdUserView))
                .then(Commands.literal("다음").executes(this::cmdUserNext))
                .then(Commands.literal("다시").executes(this::cmdUserRestart))
                .then(Commands.literal("단계").then(stepArg("이동단계").executes(this::cmdUserSet)))
                .then(Commands.literal("초기화").executes(ctx -> { throw ChacaLink.error("되돌릴 수 없어요. 끝에 '확인'을 붙여 다시 입력해 주세요"); })
                        .then(Commands.literal("확인").executes(this::cmdUserReset))),
                "스토리 단계 진행 보기·강제 진행·시작명령 다시·단계 지정·초기화", "스토리진행 보기");
    }

    private com.mojang.brigadier.builder.RequiredArgumentBuilder<CommandSourceStack, String> stepArg(String name) {
        return Commands.argument(name, ChacaLink.word(() -> new ArrayList<>(defs.steps.keySet())));
    }

    private com.mojang.brigadier.builder.RequiredArgumentBuilder<CommandSourceStack, String> idOrNone(String name) {
        return Commands.argument(name, ChacaLink.word(() -> List.of("없음")));
    }

    private List<String> stepIdsAndNone() {
        List<String> l = new ArrayList<>(defs.steps.keySet());
        l.add("없음");
        return l;
    }

    private static Trigger auto(int delay) { Trigger t = new Trigger(); t.type = TrigType.AUTO; t.delay = delay; return t; }

    private Trigger here(CommandContext<CommandSourceStack> ctx, double radius) throws CommandSyntaxException {
        Player p = self(ctx);
        Location l = p.getLocation();
        Trigger t = new Trigger();
        t.type = TrigType.LOCATION;
        t.world = l.getWorld().getName();
        t.x = l.getBlockX() + 0.5; t.y = l.getBlockY(); t.z = l.getBlockZ() + 0.5;
        t.radius = QuestDefs.clampRadius(radius);
        return t;
    }

    private static Player self(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        if (ctx.getSource().getSender() instanceof Player p) return p;
        throw ChacaLink.error("게임 안에서 써 주세요");
    }

    private static String clip(CommandContext<CommandSourceStack> ctx, int max) {
        String s = ctx.getArgument("문구", String.class).strip();
        return s.length() > max ? s.substring(0, max) : s;
    }

    /** "없음" = 빈 값. 그 밖은 ID 규칙을 지켜야 한다 (아니면 빈 값). */
    private static String idArg(CommandContext<CommandSourceStack> ctx, String name) {
        String s = ctx.getArgument(name, String.class).strip();
        if (s.equals("없음")) return "";
        return StorySafety.validId(s) ? s : "";
    }

    private Step step(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        String id = ctx.getArgument(ID, String.class);
        Step s = defs.get(id);
        if (s == null) throw ChacaLink.error("단계 " + id + " 이(가) 없어요 (/대화관리 단계 " + id + " 만들기)");
        return s;
    }

    @FunctionalInterface private interface StepEdit { void apply(Step s) throws CommandSyntaxException; }

    private int edit(CommandContext<CommandSourceStack> ctx, StepEdit change, String what) throws CommandSyntaxException {
        Step s = step(ctx);
        change.apply(s);
        changed(s);
        ChacaLink.ok(ctx.getSource().getSender(), s.id + " 의 " + what + "을(를) 바꿨어요");
        return 1;
    }

    /** 정의가 바뀜: 저장하고, 이 단계를 진행 중인 접속자에게 새 단계 패킷을 보낸다. */
    private void changed(Step s) {
        saveDefs();
        for (Player p : Bukkit.getOnlinePlayers()) {
            P st = prog.players.get(p.getUniqueId());
            if (st != null && st.step.equals(s.id)) needStep.add(p.getUniqueId());
            else if (st != null) resumeFinished(p, st);   // 예: 끝낸 단계의 '다음' 을 지금 정함
        }
        scheduleSend();
    }

    private int cmdList(CommandContext<CommandSourceStack> ctx) {
        List<String> out = new ArrayList<>();
        out.add("스토리 단계 " + defs.steps.size() + "개 · 시작 " + (defs.startStep.isEmpty() ? "-" : defs.startStep)
                + (defs.startAfterDialogue.isEmpty() ? " (첫 접속 때)" : " (대화 " + defs.startAfterDialogue + " 끝나면)"));
        for (Step s : defs.steps.values()) {
            out.add(s.id + " · " + s.trigger.type.ko + (s.dialogue.isEmpty() ? "" : " → 대화 " + s.dialogue) + (s.next.isEmpty() ? "" : " → " + s.next)
                    + (s.trigger.type == TrigType.NONE ? " (호출 미정)" : ""));
        }
        ChacaLink.lines(ctx.getSource().getSender(), out);
        return 1;
    }

    private int cmdStart(CommandContext<CommandSourceStack> ctx, String afterDialogue) throws CommandSyntaxException {
        String id = ctx.getArgument("시작단계", String.class);
        if (defs.get(id) == null) throw ChacaLink.error("단계 " + id + " 이(가) 없어요");
        if (!afterDialogue.isEmpty() && !StorySafety.validId(afterDialogue)) throw ChacaLink.error("대화 ID는 영문 소문자·숫자·_·-만 쓸 수 있어요");
        defs.startStep = id;
        defs.startAfterDialogue = afterDialogue;
        saveDefs();
        ChacaLink.ok(ctx.getSource().getSender(), "시작 단계를 " + id + " (으)로 정했어요" + (afterDialogue.isEmpty() ? " · 처음 접속하면 시작" : " · 대화 " + afterDialogue + " 이(가) 끝나면 시작"));
        return 1;
    }

    /** 이 단계에서 운영자가 아직 정하지 않았거나 잘못된 것. 이것이 있는 단계는 그 부분에서 진행이 멈춘다 (임의로 넘기지 않음). */
    List<String> problems(Step s) {
        List<String> out = new ArrayList<>();
        if (elsewhere(s)) return out;   // 다른 서버의 단계: NPC·월드·블록은 그 서버에서 점검 (/대화관리 단계 점검 을 그 서버에서)
        Trigger t = s.trigger;
        switch (t.type) {
            case NONE -> out.add("호출 미설정 — 조건을 채워도 다음으로 가지 않음 (/대화관리 단계 " + s.id + " 호출 …)");
            case NPC -> {
                if (t.npc < 0) out.add("NPC 미지정");
                else if (!t.server.isEmpty() && !t.server.equals(serverId())) out.add("다른 서버(" + t.server + ")의 NPC — 이 서버에서는 호출되지 않음");
                else if (citizens && npcById(t.npc) == null) out.add("Citizens " + t.npc + "번 NPC가 없음");
                else if (!citizens) out.add("Citizens 연결 없음");
            }
            case LOCATION -> { if (Bukkit.getWorld(t.world) == null) out.add("포탈 월드 " + (t.world.isEmpty() ? "(없음)" : t.world) + " 를 찾을 수 없음"); }
            case CUTSCENE -> { if (t.cutscene.isEmpty()) out.add("호출 컷신 미지정"); }
            default -> { }
        }
        for (int i = 0; i < s.conds.size(); i++) {
            Cond c = s.conds.get(i);
            if (c.type() == CondType.INTERACT && c.blocks().isEmpty()) out.add("조건 " + (i + 1) + " 상호작용 블록 미지정 (/대화관리 단계 " + s.id + " 조건 블록지정 " + (i + 1) + ")");
            if (c.type() == CondType.SIGNAL && !List.of("교화성공", "낚시성공", "연금술성공", "승급성공", "결투승리", "큐디치참가", "변색종교화", "원소반응", "마력코어제작", "던전클리어", "몬스터기록", "내방열기").contains(c.target()))
                out.add("조건 " + (i + 1) + " 신호 " + c.target() + " 는 자동으로 보내는 기능이 없음 (관리자 /대화관리 신호 로만 채워짐)");
            if (c.type() == CondType.RANDOM && !pools.containsKey(c.target())) out.add("조건 " + (i + 1) + " 랜덤제출 후보 목록 " + c.target() + " 이(가) random-pools.yml 에 없음 (채울 수 없음)");
        }
        if (!s.next.isEmpty() && defs.get(s.next) == null) out.add("다음 단계 " + s.next + " 없음");
        // 기숙사 분류: 6문항 대화(ch1-14)의 끝 명령이 결정을 실행해야 배정된다 (dialogues/server_command.yml)
        for (String c : s.startCommands) {
            if (!c.startsWith("기숙사분류 시작")) continue;
            List<String> end = plugin.endingCommandsOf(SORTING_DIALOGUE);
            boolean decide = false;
            if (end != null) for (String e : end) if (e.contains("기숙사분류 결정") && e.contains("{ending}")) decide = true;
            if (!decide) out.add("dialogues/server_command.yml 의 " + SORTING_DIALOGUE + " 에 \"기숙사분류 결정 {player} {ending}\" 이 없음 — 6문항을 끝내도 배정되지 않아 멈춤");
        }
        if (s.startOnce() && s.startCommands.stream().anyMatch(c -> c.startsWith("기숙사분류 시작")))
            out.add("시작명령-한번 이 켜져 있어 분류 대화를 닫은 유저에게 다시 열어 주지 않음 (시작명령-한번: false 권장)");
        return out;
    }

    private int cmdCheck(CommandContext<CommandSourceStack> ctx) {
        List<String> out = new ArrayList<>();
        if (defs.startStep.isEmpty() || defs.get(defs.startStep) == null) out.add("⚠ 시작 단계 미설정/없음 (/대화관리 단계 시작설정 …)");
        int n = 0;
        for (Step s : defs.steps.values()) {
            List<String> p = problems(s);
            if (p.isEmpty()) continue;
            n++;
            out.add(s.id + ": " + String.join(" · ", p));
        }
        out.add(0, "스토리 단계 점검: 손볼 단계 " + n + "개 / 전체 " + defs.steps.size() + "개 · 성공 신호 " + (progressHooks.isEmpty() ? "연결 없음" : String.join(", ", progressHooks)));
        ChacaLink.lines(ctx.getSource().getSender(), out);
        return 1;
    }

    private int cmdCreate(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        String id = ctx.getArgument(ID, String.class);
        if (!StorySafety.validId(id)) throw ChacaLink.error("단계 ID는 영문 소문자·숫자·_·-만 쓸 수 있어요 (예: ch1-8)");
        if (defs.get(id) != null) throw ChacaLink.error("단계 " + id + " 이(가) 이미 있어요");
        Step s = new Step(id);
        s.dialogue = id;
        defs.steps.put(id, s);
        saveDefs();
        ChacaLink.ok(ctx.getSource().getSender(), "단계 " + id + " 을(를) 만들었어요 (대화 " + id + " 를 띄우도록 기본 설정)");
        return 1;
    }

    private int cmdDelete(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        Step s = step(ctx);
        defs.steps.remove(s.id);
        saveDefs();
        ChacaLink.ok(ctx.getSource().getSender(), "단계 " + s.id + " 을(를) 지웠어요 (이 단계를 진행 중인 유저는 다음 단계를 지정해 주세요)");
        return 1;
    }

    private int cmdInfo(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        Step s = step(ctx);
        List<String> out = new ArrayList<>();
        out.add("단계 " + s.id + " · " + (s.chapter.isEmpty() ? "(장 이름 없음)" : s.chapter));
        out.add("설명: " + (s.description.isEmpty() ? "-" : s.description));
        for (int i = 0; i < s.conds.size(); i++) {
            Cond c = s.conds.get(i);
            out.add("조건 " + (i + 1) + ": " + c.type().ko + " " + c.target() + " ×" + c.amount() + " · \"" + c.display() + "\""
                    + (c.hidden() ? " (숨김)" : "") + (c.type() == CondType.INTERACT ? " · 블록 " + c.blocks().size() + "개" : ""));
        }
        if (s.conds.isEmpty()) out.add("조건: 없음 (바로 준비 완료)");
        if (!s.server.isEmpty()) out.add("서버: " + s.server + (s.transfer ? " (다른 서버에서 들어오면 이 서버로 보냄)" : "") + (elsewhere(s) ? " · 이 서버(" + serverId() + ")에서는 진행 안 함" : ""));
        out.add("호출: " + s.trigger.describe());
        out.add("대화: " + (s.dialogue.isEmpty() ? "-" : s.dialogue) + " · 컷신: " + (s.cutscene.isEmpty() ? "-" : s.cutscene) + " · 다음: " + (s.next.isEmpty() ? "(끝)" : s.next));
        if (!s.startCommands.isEmpty()) out.add("시작명령" + (s.startOnce() ? " (한번)" : " (반복 가능)") + ": " + String.join(" / ", s.startCommands));
        for (String problem : problems(s)) out.add("⚠ " + problem);
        ChacaLink.lines(ctx.getSource().getSender(), out);
        return 1;
    }

    private int cmdCondAdd(CommandContext<CommandSourceStack> ctx, String label) throws CommandSyntaxException {
        Step s = step(ctx);
        if (s.conds.size() >= QuestDefs.MAX_CONDITIONS) throw ChacaLink.error("조건은 " + QuestDefs.MAX_CONDITIONS + "개까지예요");
        CondType type = CondType.of(ctx.getArgument("종류", String.class));
        if (type == null) throw ChacaLink.error("종류는 아이템·제출·처치·상호작용·대화완료·신호·권한·값·랜덤제출 중 하나예요");
        String target = ctx.getArgument("대상", String.class).strip();
        int amount = ctx.getArgument("개수", Integer.class);
        CommandSender sender = ctx.getSource().getSender();
        switch (type) {
            case ITEM, SUBMIT -> {
                if (target.equals("손")) {
                    Player p = self(ctx);
                    ItemStack hand = p.getInventory().getItemInMainHand();
                    if (hand.getType().isAir()) throw ChacaLink.error("손에 아이템을 들고 써 주세요");
                    String name = itemName(hand);
                    int cut = name.indexOf('\u0001');
                    String display = cut >= 0 ? (cut == 0 ? name.substring(1) : name.substring(0, cut)) : name;
                    target = hand.getType().name() + (display.isBlank() ? "" : "|" + display);
                } else if (!target.startsWith("이름:") && !target.contains("|") && Material.matchMaterial(target) == null) {
                    throw ChacaLink.error("아이템은 '손'(들고 있는 것), 재료 이름(예: DIAMOND), 또는 이름:글자 로 적어 주세요");
                }
            }
            case DIALOGUE -> { if (!QuestDefs.validDialoguePattern(target)) throw ChacaLink.error("대화 ID는 영문 소문자·숫자·_·-만 (여러 개는 | 로, 앞부분이 같은 것은 끝에 *)"); }
            case SIGNAL -> { if (!QuestDefs.KEY.matcher(target).matches()) throw ChacaLink.error("신호 이름은 글자·숫자·_·- 1~32자예요"); }
            case INTERACT -> target = "블록지정";
            case PERMISSION -> { if (!target.matches("[a-z0-9_.-]{1,64}")) throw ChacaLink.error("권한 노드는 영문 소문자·숫자·_·.·- 1~64자예요"); }
            case VALUE -> { if (!target.matches("%[A-Za-z0-9_\\-:.]{1,60}%")) throw ChacaLink.error("값 조건의 대상은 %자리표시자% 예요 (예: %chacastory_petdex%)"); }
            case RANDOM -> { if (!pools.containsKey(target)) throw ChacaLink.error("random-pools.yml 에 없는 후보 목록이에요: " + target); }
            case KILL -> { }
        }
        Cond c = new Cond(type, target, amount, label.strip(), false, List.of());
        s.conds.add(c);
        changed(s);
        ChacaLink.ok(sender, s.id + " 조건 " + s.conds.size() + "번: " + type.ko + " " + target + " ×" + amount + " · \"" + c.display() + "\"");
        if (type == CondType.INTERACT && sender instanceof Player p) {
            picks.put(p.getUniqueId(), new Pick(s.id, s.conds.size() - 1, System.currentTimeMillis() + PICK_MS));
            ChacaLink.ok(p, "조사할 블록을 우클릭해서 넣어 주세요 (다시 누르면 빠짐) · 끝나면 /대화관리 단계 지정끝");
        }
        return 1;
    }

    private Cond condArg(CommandContext<CommandSourceStack> ctx, Step s) throws CommandSyntaxException {
        int n = ctx.getArgument("번호", Integer.class);
        if (n < 1 || n > s.conds.size()) throw ChacaLink.error("조건 " + n + "번이 없어요 (지금 " + s.conds.size() + "개)");
        return s.conds.get(n - 1);
    }

    private int cmdCondDelete(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        Step s = step(ctx);
        Cond c = condArg(ctx, s);
        int idx = s.conds.indexOf(c);
        s.conds.remove(idx);
        // 진행 중인 유저의 번호가 밀리므로 이 단계 진행도를 번호에 맞게 당긴다
        for (P st : prog.players.values()) {
            if (!st.step.equals(s.id)) continue;
            Map<Integer, Integer> counts = new HashMap<>();
            Map<Integer, Set<String>> hits = new HashMap<>();
            st.counts.forEach((k, v) -> { if (k < idx) counts.put(k, v); else if (k > idx) counts.put(k - 1, v); });
            st.hits.forEach((k, v) -> { if (k < idx) hits.put(k, v); else if (k > idx) hits.put(k - 1, v); });
            st.counts.clear(); st.counts.putAll(counts);
            st.hits.clear(); st.hits.putAll(hits);
        }
        saveSoon(null);
        changed(s);
        ChacaLink.ok(ctx.getSource().getSender(), s.id + " 조건 " + (idx + 1) + "번을 지웠어요");
        return 1;
    }

    private int cmdCondHide(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        Step s = step(ctx);
        Cond c = condArg(ctx, s);
        int idx = s.conds.indexOf(c);
        s.conds.set(idx, new Cond(c.type(), c.target(), c.amount(), c.label(), !c.hidden(), c.blocks()));
        changed(s);
        ChacaLink.ok(ctx.getSource().getSender(), s.id + " 조건 " + (idx + 1) + "번을 HUD에서 " + (c.hidden() ? "보이게" : "숨겼어요"));
        return 1;
    }

    private int cmdCondPick(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        Step s = step(ctx);
        Cond c = condArg(ctx, s);
        if (c.type() != CondType.INTERACT) throw ChacaLink.error("상호작용 조건만 블록을 지정할 수 있어요");
        Player p = self(ctx);
        picks.put(p.getUniqueId(), new Pick(s.id, s.conds.indexOf(c), System.currentTimeMillis() + PICK_MS));
        ChacaLink.ok(p, "조사할 블록을 우클릭해서 넣어 주세요 (다시 누르면 빠짐) · 끝나면 /대화관리 단계 지정끝");
        return 1;
    }

    private int setTrigger(CommandContext<CommandSourceStack> ctx, Trigger t) throws CommandSyntaxException {
        Step s = step(ctx);
        s.trigger = t;
        changed(s);
        CommandSender sender = ctx.getSource().getSender();
        ChacaLink.ok(sender, s.id + " 호출: " + t.describe());
        if (sender instanceof Player p && (t.type == TrigType.LOCATION || t.type == TrigType.NPC)) {
            preview(p, s);
            ChacaLink.ok(p, "내 화면에 미리보기로 표시했어요 · 끝내려면 /대화관리 단계 미리보기끝");
        }
        return 1;
    }

    private int cmdPickNpc(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        Step s = step(ctx);
        if (!citizens) throw ChacaLink.error("Citizens 연결이 없어 NPC를 지정할 수 없어요");
        Player p = self(ctx);
        picks.put(p.getUniqueId(), new Pick(s.id, -1, System.currentTimeMillis() + 30_000));
        ChacaLink.ok(p, "30초 안에 지정할 NPC를 우클릭해 주세요 (그 클릭은 대화를 열지 않아요)");
        return 1;
    }

    private int cmdGoto(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        Step s = step(ctx);
        Player p = self(ctx);
        Trigger t = s.trigger;
        Location to = null;
        if (t.type == TrigType.LOCATION) {
            World w = Bukkit.getWorld(t.world);
            if (w != null) to = new Location(w, t.x, t.y, t.z, p.getLocation().getYaw(), p.getLocation().getPitch());
        } else if (t.type == TrigType.NPC) {
            Object npc = npcById(t.npc);
            to = npc == null ? null : npcLocation(npc);
        }
        if (to == null) throw ChacaLink.error(s.id + " 은(는) 이동할 위치가 없어요 (호출이 위치·NPC클릭일 때만)");
        p.teleport(to);
        ChacaLink.ok(p, s.id + " 호출 위치로 이동했어요");
        return 1;
    }

    private int cmdSignal(CommandContext<CommandSourceStack> ctx, int amount) throws CommandSyntaxException {
        Player p = ChacaLink.resolveOnline(ctx, "닉네임");
        String key = ctx.getArgument("키", String.class);
        if (!QuestDefs.KEY.matcher(key).matches()) throw ChacaLink.error("신호 이름은 글자·숫자·_·- 1~32자예요");
        boolean used = signal(p, key, amount);
        CommandSender s = ctx.getSource().getSender();
        if (!(s instanceof org.bukkit.command.ConsoleCommandSender))
            ChacaLink.ok(s, ChacaLink.name(p.getUniqueId()) + " 님 신호 " + key + (used ? " 반영" : " (지금 단계에 해당 조건 없음)"));
        return 1;
    }

    private int cmdUserView(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        UUID uuid = ChacaLink.target(ctx);
        P st = prog.players.get(uuid);
        List<String> out = new ArrayList<>();
        out.add(ChacaLink.name(uuid) + " 스토리 단계 (이 서버)");
        if (st == null) { out.add("기록 없음"); ChacaLink.lines(ctx.getSource().getSender(), out); return 1; }
        Step s = defs.get(st.step);
        out.add("지금: " + (st.step.isEmpty() ? "없음" : st.step + (s == null ? " (정의 없음)" : "")) + " · " + (st.phase == Phase.PLAYING ? "재생 기다리는 중 " + st.waiting : "진행 중") + " · " + st.run + "번째");
        if (s != null) {
            Player p = Bukkit.getPlayer(uuid);
            int[] cur = currents(p, st, s);
            for (int i = 0; i < cur.length; i++) out.add("  " + (i + 1) + ". " + s.conds.get(i).display() + " " + cur[i] + "/" + s.conds.get(i).required());
        }
        if (!st.skippedCutscene.isEmpty()) out.add("⚠ 컷신을 불러오지 못해 건너뜀: " + st.skippedCutscene);
        if (st.startedRun == st.run && s != null && s.startOnce() && !s.startCommands.isEmpty()) out.add("시작명령: 이 회차에 실행함 (한번)");
        if (!st.history.isEmpty()) out.add("끝낸 단계: " + String.join(", ", st.history.subList(0, Math.min(10, st.history.size()))));
        ChacaLink.lines(ctx.getSource().getSender(), out);
        return 1;
    }

    /** 강제 진행: 조건·호출을 건너뛰고 지금 단계의 대화(있으면)를 띄운다. 기다리는 중이면 다음 단계로 바로 넘긴다. */
    private int cmdUserNext(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        Player p = ChacaLink.targetOnline(ctx);
        P st = prog.players.get(p.getUniqueId());
        Step s = st == null ? null : defs.get(st.step);
        if (s == null) throw ChacaLink.error(ChacaLink.name(p.getUniqueId()) + " 님은 진행 중인 스토리 단계가 없어요");
        if (st.phase == Phase.ACTIVE && chapterLocked(s))
            throw ChacaLink.error(s.id + " 은(는) 아직 공개되지 않은 장이에요 (먼저 /대화관리 장공개 열기 " + ChapterGate.chapterOf(s.id, s.chapter) + ")");
        if (st.phase == Phase.PLAYING) {
            advance(p, st, s);
            ChacaLink.ok(ctx.getSource().getSender(), ChacaLink.name(p.getUniqueId()) + " 님을 다음 단계로 넘겼어요 (기다리던 " + st.waiting + " 건너뜀)");
            return 1;
        }
        retryAt.remove(p.getUniqueId());
        st.submitted = true; // 강제 진행은 아이템을 걷지 않는다
        fire(p, st, s, "관리자");
        ChacaLink.ok(ctx.getSource().getSender(), ChacaLink.name(p.getUniqueId()) + " 님의 " + s.id + " 을(를) 강제로 호출했어요");
        return 1;
    }

    /** 지금 단계의 시작명령을 다시 실행 (예: 분류 6문항을 닫아 버린 유저). 조건·기록은 그대로. */
    private int cmdUserRestart(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        Player p = ChacaLink.targetOnline(ctx);
        P st = prog.players.get(p.getUniqueId());
        Step s = st == null ? null : defs.get(st.step);
        if (s == null || s.startCommands.isEmpty()) throw ChacaLink.error("지금 단계에 다시 실행할 시작명령이 없어요");
        if (st.phase != Phase.ACTIVE) throw ChacaLink.error("지금은 대화·컷신을 기다리는 중이에요");

        mustLeave.remove(p.getUniqueId());
        runStartCommands(p, st, st.run, true);
        ChacaLink.ok(ctx.getSource().getSender(), ChacaLink.name(p.getUniqueId()) + " 님의 " + s.id + " 시작명령을 다시 실행했어요");
        return 1;
    }

    /** 시작 단계에서 다음을 따라가며 id 앞에 오는 단계들 (진행 순). 그 길에 없으면 steps.yml 에 적힌 순서로. */
    private List<String> stepsBefore(String id) {
        List<String> path = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        String cur = defs.startStep;
        while (cur != null && !cur.isEmpty() && seen.add(cur) && defs.get(cur) != null) {
            if (cur.equals(id)) return path;
            path.add(cur);
            cur = defs.get(cur).next;
        }
        List<String> order = new ArrayList<>();
        for (String k : defs.steps.keySet()) { if (k.equals(id)) return order; order.add(k); }
        return List.of();
    }

    private int cmdUserSet(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        Player p = ChacaLink.targetOnline(ctx);
        String id = ctx.getArgument("이동단계", String.class);
        if (defs.get(id) == null) throw ChacaLink.error("단계 " + id + " 이(가) 없어요");
        P st = prog.of(p.getUniqueId());
        // 2026-10-25 오너: 단계를 정하면 그 앞 단계는 전부 완료, 뒤 단계는 미완료로 (퀘스트창 진행중·완료가 그대로 따라옴)
        List<String> before = stepsBefore(id);
        st.history.clear();
        for (int i = before.size() - 1; i >= 0; i--) st.history.add(before.get(i)); // history = 최근 순
        enter(p, st, id, true);
        ChacaLink.ok(ctx.getSource().getSender(), ChacaLink.name(p.getUniqueId()) + " 님을 단계 " + id + " (으)로 옮겼어요"
                + (chapterLocked(defs.get(id)) ? " (공개 전인 장이라 장이 열릴 때까지 멈춰 있어요)" : ""));
        return 1;
    }

    private int cmdUserReset(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        UUID uuid = ChacaLink.target(ctx);
        prog.players.remove(uuid);
        readyCache.remove(uuid); readySince.remove(uuid); retryAt.remove(uuid); playingSince.remove(uuid); mustLeave.remove(uuid);
        saveSoon(null);
        Player p = Bukkit.getPlayer(uuid);
        if (p != null) { onJoin(p); needStep.add(uuid); scheduleSend(); }
        ChacaLink.ok(ctx.getSource().getSender(), ChacaLink.name(uuid) + " 님의 스토리 단계 기록을 지웠어요");
        return 1;
    }

    private int cmdGateList(CommandContext<CommandSourceStack> ctx) {
        long now = System.currentTimeMillis();
        java.util.TreeSet<Integer> seen = new java.util.TreeSet<>(gate.chapters.keySet());
        for (Step s : defs.steps.values()) { int n = ChapterGate.chapterOf(s.id, s.chapter); if (n >= 0) seen.add(n); }
        List<String> out = new ArrayList<>();
        out.add("스토리 장 공개 · " + gate.openThrough + "장까지 항상 공개 · 잠김 안내: " + gate.title);
        for (int n : seen) out.add(" " + gate.describe(n, now, KST));
        int held = 0;
        for (Player p : Bukkit.getOnlinePlayers()) if (heldByGate.contains(p.getUniqueId())) held++;
        out.add(" 잠긴 장 앞에서 기다리는 접속자 " + held + "명");
        ChacaLink.lines(ctx.getSource().getSender(), out);
        return 1;
    }

    /** 즉시 공개(open: true) / 다시 잠그기(open: false, 공개 시각도 지움). config.yml 에 저장하고 바로 적용. */
    private int cmdGateSet(CommandContext<CommandSourceStack> ctx, boolean open) throws CommandSyntaxException {
        int n = ctx.getArgument("장", Integer.class);
        if (n <= gate.openThrough) throw ChacaLink.error(n + "장은 open-through(" + gate.openThrough + ") 이하라 항상 공개예요 (config.yml chapter-release.open-through)");
        var c = plugin.getConfig();
        c.set("chapter-release.chapters." + n + ".open", open);
        if (!open) c.set("chapter-release.chapters." + n + ".open-at", "");
        try { plugin.saveConfig(); }
        catch (RuntimeException e) { plugin.getLogger().log(Level.SEVERE, "[스토리 장 공개] config.yml 저장 실패", e); throw ChacaLink.error("config.yml 을 저장하지 못했어요 (콘솔 확인)"); }
        loadGate();
        plugin.getLogger().info("[스토리 장 공개] " + n + "장 " + (open ? "공개" : "잠금") + " (" + ctx.getSource().getSender().getName() + ")");
        ChacaLink.ok(ctx.getSource().getSender(), n + "장을 " + (open ? "공개했어요 — 기다리던 접속자는 0.5초 안에 이어서 진행돼요" : "다시 잠갔어요 (공개 시각도 지움)"));
        return 1;
    }

    String statusLine() {
        int active = 0;
        for (Player p : Bukkit.getOnlinePlayers()) { P st = prog.players.get(p.getUniqueId()); if (st != null && !st.step.isEmpty()) active++; }
        int unset = 0;
        for (Step s : defs.steps.values()) if (!problems(s).isEmpty()) unset++;
        return "단계 " + defs.steps.size() + "개 (손볼 곳 " + unset + ") · 진행 중 접속자 " + active + "명 · Citizens " + (citizens ? "연결" : "없음");
    }
}
