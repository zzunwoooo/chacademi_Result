package school.magiccodex.paper;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;
import java.util.logging.Level;
import org.bukkit.Bukkit;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;

/**
 * 운영 기록 (감사 로그, 오너 2026-10-26 메모 #92): "누가 어떤 마법을 얻었다 / 무엇을 팔았다 / 어떤 퀘스트를 깼다 …"를
 * 나중에 복구할 때 찾아볼 수 있게 남긴다.
 *
 * <p><b>서버 부담 원칙</b>: 게임 스레드는 {@link #record}에서 메모리 대기열에 한 줄 넣기만 한다 (DB·파일 IO 없음).
 * 기록 스레드 하나가 batch-seconds 마다 묶어서 한 트랜잭션으로 넣는다. 대기열 상한을 넘으면 사소한 종류부터 버리고 경고는 한 번.
 * DB 가 안 되면 plugins/MagicCodexBridge/audit/yyyy-MM-dd.log 파일로 남긴다. 보관 기간이 지난 줄은 하루 한 번 기록 스레드가 지운다.</p>
 *
 * <p>다른 플러그인(MagicDiscovery·ChacademyStory 등)은 {@code ChacaApi.audit(UUID, 종류, 설명, Map)} 을 ChacaLink 로 부른다.</p>
 */
public final class AuditLog implements Listener, AutoCloseable {
    // ---------------------------------------------------------------- 종류 (DB kind 칸에 그대로 들어가는 한글 이름)
    public static final String 마법 = "마법", 상점 = "상점", 마일리지 = "마일리지", 우편 = "우편", 퀘스트 = "퀘스트", 스토리 = "스토리",
            호감도 = "호감도", 아티팩트 = "아티팩트", 펫 = "펫", 강화 = "강화", 감정 = "감정", 연금술 = "연금술", 낚시 = "낚시",
            돈 = "돈", 관리자 = "관리자", 기숙사 = "기숙사", 칭호 = "칭호", 접속 = "접속", 사망 = "사망", 확성기 = "확성기", 뽑기상자 = "뽑기상자", 후원 = "후원";
    /** 모든 종류 (명령 자동완성·audit.yml kinds 기본값 순서). */
    static final List<String> KINDS = List.of(마법, 상점, 마일리지, 우편, 퀘스트, 스토리, 호감도, 아티팩트, 펫, 강화, 감정, 연금술, 낚시,
            돈, 관리자, 기숙사, 칭호, 접속, 사망, 확성기, 뽑기상자, 후원);

    /** 대기열 한 줄 (기록 스레드가 DB/파일로 옮긴다). */
    record Entry(long at, String server, String player, String name, String kind, String summary, String detail) {}

    private static volatile AuditLog instance;
    private static final ZoneId ZONE = ZoneId.of("Asia/Seoul");
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    private final MagicCodexBridge plugin;
    private final Path folder;
    private final Map<UUID, String> accountNames = new ConcurrentHashMap<>();
    private final ScheduledExecutorService worker;
    private volatile Settings settings;
    private volatile AuditQueue<Entry> queue;
    private volatile boolean dropWarned;
    /** 기록 스레드 전용. null 이면 파일만. */
    private AuditStore store;
    private long dbRetryAt;
    private volatile String mode = "준비 중";
    private volatile long written, filed;

    /** audit.yml 내용. */
    record Settings(boolean enabled, String server, int retentionDays, int batchSeconds, int queueLimit, int searchDays,
                    Set<String> kinds, Set<String> minor, long moneyThreshold, boolean stats, int statsMinutes) {}
    /** 접속 시간 통계: 마지막으로 접속초를 센 시각 (ms). */
    private final Map<UUID, Long> onlineSince = new ConcurrentHashMap<>();
    private volatile long statsWritten;

    private AuditLog(MagicCodexBridge plugin) {
        this.plugin = plugin;
        this.folder = plugin.getDataFolder().toPath().resolve("audit");
        this.worker = Executors.newSingleThreadScheduledExecutor(r -> { var t = new Thread(r, "MagicCodex-Audit"); t.setDaemon(true); return t; });
    }

    /** MagicCodexBridge.onEnable 에서 한 번. 실패해도 나머지 기능은 계속 (기록만 꺼짐). */
    static AuditLog start(MagicCodexBridge plugin) {
        var log = new AuditLog(plugin);
        try { log.settings = log.load(); }
        catch (Exception e) { plugin.getLogger().warning("audit.yml 읽기 실패 — 운영 기록 기본값 사용: " + e.getMessage()); log.settings = log.defaults(); }
        log.queue = new AuditQueue<>(log.settings.queueLimit());
        var s = log.settings;
        // DB 연결·표 만들기도 기록 스레드에서 (게임 스레드 IO 없음).
        log.worker.execute(log::openStore);
        log.worker.scheduleWithFixedDelay(log::flushSafe, s.batchSeconds(), s.batchSeconds(), TimeUnit.SECONDS);
        log.worker.scheduleWithFixedDelay(log::pruneSafe, 120, 24 * 3600, TimeUnit.SECONDS);
        AuditStats.enabled(s.stats());
        DiscoveryStats.install(plugin); // 마법 발견 v2: 몇몇 통계를 발견 카운터로도
        log.worker.scheduleWithFixedDelay(() -> log.flushStats(null), s.statsMinutes() * 60L, s.statsMinutes() * 60L, TimeUnit.SECONDS);
        plugin.getServer().getPluginManager().registerEvents(log, plugin);
        instance = log;
        if (ChacaApi.ready()) {
            ChacaApi.reload(plugin, "운영기록", "audit.yml (운영 기록 켜기·종류·보관 일수)", log::reload);
            ChacaApi.status(plugin, "운영 기록", log::statusLine);
        }
        AuditCommands.register(plugin, log);
        return log;
    }

    // ---------------------------------------------------------------- 넣기 (아무 스레드)

    /** 공용 기록기. 아무 스레드에서 불러도 된다 (IO 없음). player 가 null 이면 시스템 기록. detail 값은 문자열로 저장. */
    public static void record(UUID player, String kind, String summary, Map<String, ?> detail) {
        var log = instance;
        if (log != null) log.add(player, null, kind, summary, detail);
    }

    /** 게임 스레드용 줄임: k1, v1, k2, v2 … */
    static void log(Player player, String kind, String summary, Object... kv) {
        var log = instance;
        if (log == null || player == null) return;
        log.accountNames.put(player.getUniqueId(), player.getName());
        log.add(player.getUniqueId(), null, kind, summary, AuditText.pairs(kv));
    }

    static void log(UUID player, String kind, String summary, Object... kv) {
        var log = instance;
        if (log != null) log.add(player, null, kind, summary, AuditText.pairs(kv));
    }

    /** 이 종류가 켜져 있는지 (상세를 만드는 데 비용이 드는 곳에서 먼저 확인). */
    static boolean on(String kind) {
        var log = instance;
        if (log == null) return false;
        var s = log.settings;
        return s.enabled() && s.kinds().contains(kind);
    }

    /** "돈" 종류로 남길 최소 금액 (audit.yml money-threshold). */
    static long moneyThreshold() { var log = instance; return log == null ? Long.MAX_VALUE : log.settings.moneyThreshold(); }

    private void add(UUID player, String name, String kind, String summary, Map<String, ?> detail) {
        var s = settings;
        if (!s.enabled() || kind == null || !s.kinds().contains(kind)) return;
        String id = player == null ? "" : player.toString();
        String who = name != null ? name : player == null ? "" : nameOf(player);
        var e = new Entry(System.currentTimeMillis(), s.server(), id, AuditText.clip(who, 64), kind,
                AuditText.clip(summary, AuditText.SUMMARY_MAX), AuditText.json(detail));
        if (queue.offer(e, s.minor().contains(kind)) > 0 && !dropWarned) {
            dropWarned = true;
            plugin.getLogger().warning("[운영 기록] 대기열이 가득 차(" + s.queueLimit() + "줄) 오래된 사소한 기록부터 버리고 있습니다. DB 상태를 확인하세요. (이 경고는 한 번만)");
        }
    }

    /** 한글 닉네임 (닉네임 저장소는 스레드 안전), 없으면 접속 때 본 계정 이름. */
    private String nameOf(UUID id) {
        try {
            var names = plugin.names();
            if (names != null) { String saved = names.nicknames().stored(id); if (!saved.isEmpty()) return saved; }
        } catch (RuntimeException ignored) { }
        String account = accountNames.get(id);
        if (account != null) return account;
        if (Bukkit.isPrimaryThread()) { var p = Bukkit.getPlayer(id); if (p != null) return p.getName(); }
        return "";
    }

    // ---------------------------------------------------------------- 접속·퇴장·사망 (가벼운 이벤트 한 줄)

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        var p = event.getPlayer();
        accountNames.put(p.getUniqueId(), p.getName());
        onlineSince.put(p.getUniqueId(), System.currentTimeMillis());
        AuditStats.add(p.getUniqueId(), "접속횟수", 1);
        if (!on(접속)) return;
        log(p, 접속, "접속 (" + settings.server() + ")", "계정", p.getName(), "월드", p.getWorld().getName());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        var p = event.getPlayer();
        if (on(접속)) log(p, 접속, "퇴장 (" + settings.server() + ")", "계정", p.getName(), "위치", where(p.getLocation()));
        Long since = onlineSince.remove(p.getUniqueId());
        if (since != null) AuditStats.add(p.getUniqueId(), "접속초", Math.max(0, (System.currentTimeMillis() - since) / 1000));
        String id = p.getUniqueId().toString();
        try { worker.execute(() -> flushStats(id)); } catch (RejectedExecutionException ignored) { }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onDeath(PlayerDeathEvent event) {
        var p = event.getEntity();
        var last = p.getLastDamageCause();
        {
            boolean byPlayer = p.getKiller() != null, byMob = !byPlayer && last instanceof org.bukkit.event.entity.EntityDamageByEntityEvent hit
                    && (hit.getDamager() instanceof org.bukkit.entity.LivingEntity || hit.getDamager() instanceof org.bukkit.entity.Projectile pr && pr.getShooter() instanceof org.bukkit.entity.LivingEntity);
            AuditStats.add(p.getUniqueId(), "사망", 1);
            AuditStats.add(p.getUniqueId(), "사망:" + AuditStats.deathBucket(byPlayer, byMob), 1);
            if (byPlayer) AuditStats.add(p.getKiller().getUniqueId(), "플레이어처치", 1);
        }
        if (!on(사망)) return;
        String cause = last == null ? "알 수 없음" : last.getCause().name();
        var killer = p.getKiller();
        int stacks = 0, items = 0;
        if (!event.getKeepInventory()) for (var drop : event.getDrops()) if (drop != null && !drop.getType().isAir()) { stacks++; items += drop.getAmount(); }
        String message = "";
        try { var c = event.deathMessage(); if (c != null) message = net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText().serialize(c); }
        catch (RuntimeException | LinkageError ignored) { }
        log(p, 사망, "사망 · " + cause + (killer != null ? " · " + killer.getName() : "") + (event.getKeepInventory() ? " · 아이템 유지" : " · 아이템 " + stacks + "묶음 떨굼"),
                "원인", cause, "처치자", killer == null ? null : killer.getName() + "(" + killer.getUniqueId() + ")",
                "위치", where(p.getLocation()), "아이템유지", event.getKeepInventory() ? "예" : "아니오",
                "떨군묶음", stacks, "떨군개수", items, "경험치떨굼", event.getDroppedExp(), "메시지", message.isEmpty() ? null : message);
    }

    /** 아이템 한 줄 이름: 표시 이름(있으면) + 재료 + 개수. 게임 스레드. */
    static String item(org.bukkit.inventory.ItemStack stack) {
        if (stack == null || stack.getType().isAir()) return "";
        String shown = "";
        try {
            var meta = stack.getItemMeta();
            if (meta != null && meta.hasDisplayName()) {
                var c = meta.displayName();
                if (c != null) shown = net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText().serialize(c);
            }
        } catch (RuntimeException | LinkageError ignored) { }
        return AuditText.clip((shown.isEmpty() ? "" : shown + " ") + "(" + stack.getType().name() + ")" + (stack.getAmount() > 1 ? " x" + stack.getAmount() : ""), 120);
    }

    /** 잡은 몹 통계 (메모 #96): 플레이어가 막타를 친 몹. MythicMobs 커스텀 몹은 "처치:<미몹 ID>", 전체는 "몹처치". 맵 조회 하나라 가볍다. */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onKill(org.bukkit.event.entity.EntityDeathEvent event) {
        var dead = event.getEntity();
        if (dead instanceof Player) return;
        var killer = dead.getKiller();
        if (killer == null) return;
        AuditStats.add(killer.getUniqueId(), "몹처치", 1);
        String mythic = mythicId(dead);
        if (mythic != null) AuditStats.add(killer.getUniqueId(), "처치:" + mythic, 1);
    }

    private static boolean mythicBroken;
    private static String mythicId(org.bukkit.entity.Entity e) {
        if (mythicBroken || !Bukkit.getPluginManager().isPluginEnabled("MythicMobs")) return null;
        try {
            var active = io.lumine.mythic.bukkit.MythicBukkit.inst().getMobManager().getActiveMob(e.getUniqueId());
            return active.isPresent() ? active.get().getMobType() : null;
        } catch (RuntimeException | LinkageError error) { mythicBroken = true; return null; }
    }

    static String where(org.bukkit.Location l) {
        if (l == null || l.getWorld() == null) return "";
        return l.getWorld().getName() + " " + l.getBlockX() + " " + l.getBlockY() + " " + l.getBlockZ();
    }

    /** 관리 명령 실행 (CommandHub.wrap 이 성공한 실행마다 부른다). 보기만 하는 명령은 뺀다. 게임 스레드. */
    static void adminCommand(org.bukkit.command.CommandSender sender, String path, String input, UUID target) {
        if (!on(관리자) || AuditText.readOnlyCommand(path)) return;
        String who = sender instanceof Player p ? p.getName() : "콘솔";
        UUID subject = target != null ? target : sender instanceof Player p ? p.getUniqueId() : null;
        String admin = sender instanceof Player p ? p.getUniqueId().toString() : "console";
        String line = "/" + (input == null ? "" : input.startsWith("/") ? input.substring(1) : input);
        var log = instance;
        if (log != null && sender instanceof Player p) log.accountNames.put(p.getUniqueId(), p.getName());
        log(subject, 관리자, who + " → " + line, "관리자", who, "관리자uuid", admin, "명령", line, "경로", path,
                "대상", target == null ? null : target.toString());
    }

    // ---------------------------------------------------------------- 기록 스레드

    private void openStore() {
        try {
            var dbSettings = GenericDatabaseFile.load(plugin.getDataFolder().toPath());
            store = new AuditStore(dbSettings, plugin.getDataFolder().toPath().resolve("audit.db"));
            mode = store.shared() ? "MariaDB 공유" : "SQLite (이 서버 audit.db)";
            plugin.getLogger().info("[운영 기록] 저장소 " + mode + " · 서버 이름 " + settings.server() + " · 보관 " + settings.retentionDays() + "일");
        } catch (Exception | LinkageError e) {
            store = null; mode = "파일만 (DB 연결 실패)";
            dbRetryAt = System.currentTimeMillis() + 60_000;
            plugin.getLogger().warning("[운영 기록] DB 를 열지 못해 audit 폴더 일별 파일로 남깁니다: " + e);
        }
    }

    private void flushSafe() {
        try { flush(5000); } catch (Throwable t) { plugin.getLogger().log(Level.WARNING, "[운영 기록] 쓰기 실패", t); }
    }

    /** 대기열에서 최대 max 줄을 500줄씩 묶어 쓴다. */
    private void flush(int max) {
        int done = 0;
        while (done < max) {
            var rows = queue.drain(Math.min(500, max - done));
            if (rows.isEmpty()) return;
            done += rows.size();
            if (store == null && System.currentTimeMillis() >= dbRetryAt) openStore();
            if (store != null) {
                try { store.insert(rows); written += rows.size(); continue; }
                catch (Exception e) {
                    plugin.getLogger().warning("[운영 기록] DB 쓰기 실패 — 이번 " + rows.size() + "줄은 파일로 남깁니다: " + e);
                    mode = "파일 (DB 쓰기 실패, 1분 뒤 다시 시도)";
                    try { store.close(); } catch (Exception ignored) { }
                    store = null; dbRetryAt = System.currentTimeMillis() + 60_000;
                }
            }
            toFile(rows);
        }
    }

    private void toFile(List<Entry> rows) {
        try {
            Files.createDirectories(folder);
            var byDay = new LinkedHashMap<String, StringBuilder>();
            for (var r : rows) {
                var at = Instant.ofEpochMilli(r.at()).atZone(ZONE);
                byDay.computeIfAbsent(at.format(DAY), k -> new StringBuilder())
                        .append(AuditText.fileLine(at.toOffsetDateTime().toString(), r.server(), r.player(), r.name(), r.kind(), r.summary(), r.detail())).append('\n');
            }
            for (var e : byDay.entrySet())
                Files.writeString(folder.resolve(e.getKey() + ".log"), e.getValue(), StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            filed += rows.size();
        } catch (Exception e) {
            plugin.getLogger().warning("[운영 기록] 파일 기록도 실패해 " + rows.size() + "줄을 잃었습니다: " + e);
        }
    }

    /** 통계 증가분 쓰기 (기록 스레드). player 가 null 이면 전체 (접속 중인 사람의 접속초도 여기서 센다). */
    private void flushStats(String player) {
        try {
            if (player == null) {
                long now = System.currentTimeMillis();
                for (var e : onlineSince.entrySet()) {
                    long since = e.getValue();
                    if (onlineSince.replace(e.getKey(), since, now)) AuditStats.add(e.getKey(), "접속초", Math.max(0, (now - since) / 1000));
                }
            }
            var rows = AuditStats.drain(player);
            if (AuditStats.takeOverflow()) plugin.getLogger().warning("[서버 통계] 메모리 상한(" + AuditStats.MAX_KEYS + "키)을 넘어 새 항목을 버렸습니다. DB 상태를 확인하세요.");
            if (rows.isEmpty()) return;
            if (store == null && System.currentTimeMillis() >= dbRetryAt) openStore();
            if (store == null) { AuditStats.restore(rows); return; }
            for (int i = 0; i < rows.size(); i += 1000) {
                var part = rows.subList(i, Math.min(rows.size(), i + 1000));
                try { store.addStats(part, System.currentTimeMillis()); statsWritten += part.size(); }
                catch (Exception e) {
                    AuditStats.restore(rows.subList(i, rows.size()));
                    plugin.getLogger().warning("[서버 통계] DB 쓰기 실패 — 메모리에 두고 다음에 다시 씁니다: " + e);
                    return;
                }
            }
        } catch (Throwable t) { plugin.getLogger().log(Level.WARNING, "[서버 통계] 쓰기 실패", t); }
    }

    private void pruneSafe() {
        int days = settings.retentionDays();
        long cutoff = System.currentTimeMillis() - days * 86_400_000L;
        try {
            if (store != null) { int n = store.prune(cutoff); if (n > 0) plugin.getLogger().info("[운영 기록] " + days + "일 지난 기록 " + n + "줄 정리"); }
        } catch (Exception e) { plugin.getLogger().warning("[운영 기록] 오래된 기록 정리 실패 (다음 날 다시): " + e); }
        try {
            if (!Files.isDirectory(folder)) return;
            var oldest = LocalDate.now(ZONE).minusDays(days);
            try (var files = Files.list(folder)) {
                for (var f : (Iterable<Path>) files::iterator) {
                    String n = f.getFileName().toString();
                    if (!n.matches("\\d{4}-\\d{2}-\\d{2}\\.log")) continue;
                    if (LocalDate.parse(n.substring(0, 10), DAY).isBefore(oldest)) Files.deleteIfExists(f);
                }
            }
        } catch (Exception e) { plugin.getLogger().warning("[운영 기록] 오래된 기록 파일 정리 실패: " + e); }
    }

    // ---------------------------------------------------------------- 찾기 (기록 스레드에서 읽고, 결과는 게임 스레드로)

    void playerRows(UUID player, String kind, int days, int limit, int offset, Consumer<List<AuditStore.Row>> done, Consumer<String> fail) {
        long since = System.currentTimeMillis() - Math.max(1, days) * 86_400_000L;
        read(() -> store.player(player.toString(), kind, since, limit, offset), done, fail);
    }

    void searchRows(String text, int limit, Consumer<List<AuditStore.Row>> done, Consumer<String> fail) {
        long since = System.currentTimeMillis() - Math.max(1, settings.searchDays()) * 86_400_000L;
        read(() -> store.search(text, since, limit), done, fail);
    }

    int searchDays() { return settings.searchDays(); }

    private void read(Callable<List<AuditStore.Row>> query, Consumer<List<AuditStore.Row>> done, Consumer<String> fail) {
        try {
            worker.execute(() -> {
                List<AuditStore.Row> rows = null; String error = null;
                try {
                    flush(20_000); // 방금 일어난 일도 보이게 대기열을 먼저 비운다
                    if (store == null) error = "DB 가 연결되지 않아 검색할 수 없어요 (서버 audit 폴더의 날짜별 파일을 보세요)";
                    else rows = query.call();
                } catch (Exception e) { error = "기록을 읽지 못했어요: " + e.getClass().getSimpleName(); plugin.getLogger().warning("[운영 기록] 읽기 실패: " + e); }
                var r = rows; var m = error;
                if (plugin.isEnabled()) Bukkit.getScheduler().runTask(plugin, () -> { if (m != null) fail.accept(m); else done.accept(r); });
            });
        } catch (RejectedExecutionException e) { fail.accept("운영 기록이 꺼지는 중이에요"); }
    }

    // ---------------------------------------------------------------- 설정

    private Settings defaults() {
        return new Settings(true, serverName(""), 90, 5, 20000, 7, Set.copyOf(KINDS), Set.of(접속, 낚시), 10000, true, 5);
    }

    private Settings load() throws Exception {
        File file = new File(plugin.getDataFolder(), "audit.yml");
        if (!file.exists()) plugin.saveResource("audit.yml", false);
        var y = new YamlConfiguration(); y.load(file);
        var kinds = new LinkedHashSet<String>();
        var section = y.getConfigurationSection("kinds");
        for (String k : KINDS) if (section == null || section.getBoolean(k, true)) kinds.add(k);
        var minor = new LinkedHashSet<String>();
        for (String k : y.getStringList("minor-kinds")) if (KINDS.contains(k)) minor.add(k);
        if (!y.isList("minor-kinds")) { minor.add(접속); minor.add(낚시); }
        return new Settings(y.getBoolean("enabled", true), serverName(y.getString("server-name", "")),
                Math.clamp(y.getInt("retention-days", 90), 1, 3650), Math.clamp(y.getInt("batch-seconds", 5), 1, 60),
                Math.clamp(y.getInt("queue-limit", 20000), 1000, 500_000), Math.clamp(y.getInt("search-days", 7), 1, 365),
                Set.copyOf(kinds), Set.copyOf(minor), Math.max(0, y.getLong("money-threshold", 10000)),
                y.getBoolean("stats.enabled", true), Math.clamp(y.getInt("stats.flush-minutes", 5), 1, 60));
    }

    /** audit.yml server-name, 비우면 quests.yml server-id (가방·의뢰와 같은 서버 이름), 그것도 없으면 "server". */
    private String serverName(String configured) {
        String sid = configured == null ? "" : configured.trim();
        if (sid.isEmpty()) {
            var quests = new File(plugin.getDataFolder(), "quests.yml");
            if (quests.exists()) try { var q = new YamlConfiguration(); q.load(quests); sid = q.getString("server-id", "").trim(); } catch (Exception ignored) { }
        }
        if (!sid.matches("[A-Za-z0-9_-]{1,48}")) sid = "server";
        return sid;
    }

    private void reload() throws Exception {
        var next = load();
        if (next.queueLimit() != settings.queueLimit()) plugin.getLogger().info("[운영 기록] queue-limit 변경은 서버를 다시 켤 때 적용됩니다.");
        if (next.batchSeconds() != settings.batchSeconds()) plugin.getLogger().info("[운영 기록] batch-seconds 변경은 서버를 다시 켤 때 적용됩니다.");
        settings = new Settings(next.enabled(), next.server(), next.retentionDays(), settings.batchSeconds(), settings.queueLimit(),
                next.searchDays(), next.kinds(), next.minor(), next.moneyThreshold(), next.stats(), settings.statsMinutes());
        AuditStats.enabled(next.stats());
        dropWarned = false;
    }

    String statusLine() {
        var s = settings;
        return (s.enabled() ? "켜짐" : "꺼짐") + " · " + mode + " · 대기 " + queue.size() + "줄 · DB " + written + "줄 · 파일 " + filed + "줄"
                + (queue.dropped() > 0 ? " · 버림 " + queue.dropped() + "줄" : "") + " · 보관 " + s.retentionDays() + "일"
                + " · 통계 " + (s.stats() ? "켜짐 (대기 " + AuditStats.pending() + "칸, 쓴 " + statsWritten + "칸)" : "꺼짐");
    }

    /** 끌 때: 남은 대기열을 최대 5초 동안 마저 쓰고 닫는다. */
    @Override public void close() {
        if (instance == this) instance = null;
        worker.shutdown();
        try {
            if (!worker.awaitTermination(3, TimeUnit.SECONDS)) worker.shutdownNow();
        } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        // 기록 스레드가 끝난 뒤 남은 줄을 별도 스레드에서 마저 쓴다 (최대 5초 기다림). 서버 종료 단계라 게임 렉과 무관.
        var last = new Thread(() -> {
            try { flush(100_000); flushStats(null); if (store != null) store.close(); }
            catch (Exception e) { plugin.getLogger().warning("[운영 기록] 종료 중 쓰기 실패: " + e); }
        }, "MagicCodex-Audit-Close");
        last.setDaemon(true);
        last.start();
        try { last.join(5000); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        if (last.isAlive()) plugin.getLogger().warning("[운영 기록] 종료 중 남은 기록을 5초 안에 다 쓰지 못했습니다 (대기 " + queue.size() + "줄)");
    }
}
