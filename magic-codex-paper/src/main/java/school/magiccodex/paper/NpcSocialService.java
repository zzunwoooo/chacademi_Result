package school.magiccodex.paper;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.IntPredicate;
import org.bukkit.Bukkit;
import org.bukkit.NamespacedKey;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import school.magiccodex.database.DatabaseSettings;

/**
 * NPC 호감도·스토리 우선 판단·퀘스트 연결. 외부 플러그인은 {@link NpcSocialFacade}로만 쓴다.
 * DB는 전용 IO 스레드 하나, 결과는 메인 스레드에서 완료된다.
 */
final class NpcSocialService implements Listener, AutoCloseable {

    private final MagicCodexBridge plugin;
    private final ExecutorService io = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "MagicCodex-affinity-io");
        t.setDaemon(true);
        return t;
    });
    private final AffinityStore store;
    private final Map<UUID, Map<String, AffinityStore.Row>> cache = new ConcurrentHashMap<>();
    private final List<Consumer<Map<String, String>>> listeners = new CopyOnWriteArrayList<>();
    private final Map<String, Integer> caps = new ConcurrentHashMap<>();
    private final int defaultCap;
    private final int giftDailyCount;
    private final ZoneId zone;
    /** #83 호의적 AI 대화 보상: 한 번에 오르는 양 · NPC 하나당 하루 횟수 (affinity.yml chat-reward). */
    private final int chatRewardAmount;
    private final int chatRewardCount;
    /** #39 호감도 퀘스트 연결 (affinity.yml gate-quests): NPC → (상한 30·60·90 → 퀘스트 ID). 비어 있으면 아무 일 없음. */
    private final Map<String, Map<Integer, String>> gateQuests = new java.util.HashMap<>();
    /** 퀘스트 ID → {NPC, 완료 시 올릴 하트 단계(1·2·3)} */
    private final Map<String, String[]> questGates = new java.util.HashMap<>();
    private final java.util.Set<String> gateOffering = ConcurrentHashMap.newKeySet();
    private volatile boolean closing;
    private boolean catalogFailureReported;
    private NpcSocialFacade facade;
    /** 이 서버 ID (우편과 같은 mailbox-origin.txt). 선물 pending 은 시작한 서버에서만 영수증으로 확정·취소한다 (A08). */
    private final String origin;

    NpcSocialService(MagicCodexBridge plugin) throws Exception {
        this.plugin = plugin;
        Path folder = plugin.getDataFolder().toPath();
        if (!Files.exists(folder.resolve("affinity.yml"))) {
            plugin.saveResource("affinity.yml", false);
        }
        var cfg = YamlConfiguration.loadConfiguration(folder.resolve("affinity.yml").toFile());
        var section = cfg.getConfigurationSection("daily-caps");
        if (section != null) {
            for (String k : section.getKeys(false)) {
                caps.put(k, Math.max(0, section.getInt(k)));
            }
        }
        defaultCap = Math.max(0, cfg.getInt("default-daily-cap", 5));
        giftDailyCount = Math.max(1, cfg.getInt("gift-daily-count", 1));
        zone = ZoneId.of(cfg.getString("timezone", "Asia/Seoul"));
        chatRewardAmount = Math.max(0, cfg.getInt("chat-reward.amount", 1));
        chatRewardCount = Math.max(0, cfg.getInt("chat-reward.daily-count", 3));
        loadGateQuests(cfg.getConfigurationSection("gate-quests"));
        origin = serverOrigin(folder);

        var settings = DatabaseSettings.load(folder.resolve("database.properties"));
        // SQLite fallback 경로를 명시: plugins/MagicCodexBridge/affinity.db
        Path sqlite = folder.resolve("affinity.db");
        try {
            store = io.submit(() -> new AffinityStore(settings, sqlite)).get(15, TimeUnit.SECONDS);
            // A08 (2026-10-21): 시작 시 일괄 확정은 하지 않는다. pending 은 접속할 때 시작한 서버에서 영수증으로 정한다.
            int legacy = io.submit(store::legacyPendingGifts).get(15, TimeUnit.SECONDS);
            if (legacy > 0) {
                plugin.getLogger().warning("NPC 선물 미확정 옛 기록(서버 정보 없음) " + legacy + "건: 자동 처리하지 않아요. /유저관리 <닉네임> 호감도 선물기록 으로 확인하세요.");
            }
        } catch (Exception e) {
            io.shutdownNow();
            throw e;
        }
        Bukkit.getPluginManager().registerEvents(this, plugin);
        for (Player p : Bukkit.getOnlinePlayers()) {
            load(p.getUniqueId());
            resolveGifts(p);
        }
        registerDialogueHooks();
        QuestBridge questBridge = plugin.questBridge();
        if (questBridge != null) {
            questBridge.onCompleted(this::questCompleted);
        }
        facade = new NpcSocialFacade(this);
        Bukkit.getServicesManager().register(NpcSocialFacade.class, facade, plugin, org.bukkit.plugin.ServicePriority.Normal);
    }

    String playerName(Player player) {
        if (!Bukkit.isPrimaryThread()) throw new IllegalStateException("server thread required");
        return plugin.names().name(player);
    }

    Player resolvePlayer(String input) {
        if (!Bukkit.isPrimaryThread()) throw new IllegalStateException("server thread required");
        return plugin.names().resolve(input);
    }

    // ------------------------------------------------------------------ 공통

    private interface Job<T> {
        T run() throws Exception;
    }

    /** IO 스레드에서 실행하고 메인 스레드에서 완료. 실패하면 예외로 완료. */
    private <T> CompletableFuture<T> work(Job<T> job) {
        CompletableFuture<T> f = new CompletableFuture<>();
        if (closing) {
            f.completeExceptionally(new IllegalStateException("closing"));
            return f;
        }
        try {
            io.execute(() -> {
                try {
                    T v = job.run();
                    main(() -> f.complete(v));
                } catch (Exception e) {
                    plugin.getLogger().warning("NPC affinity storage: " + e.getClass().getSimpleName() + ": " + e.getMessage());
                    main(() -> f.completeExceptionally(e));
                }
            });
        } catch (java.util.concurrent.RejectedExecutionException e) {
            f.completeExceptionally(e);
        }
        return f;
    }

    /** 결과는 항상 메인 스레드에서 완료. 플러그인이 꺼진 뒤면 완료하지 않는다 (외부 콜백이 IO 스레드에서 돌지 않게). */
    private void main(Runnable r) {
        if (plugin.isEnabled()) {
            try {
                Bukkit.getScheduler().runTask(plugin, r);
            } catch (IllegalStateException ignored) {
                // 꺼지는 중
            }
        }
    }

    /** 하루 기준 = timezone 의 자정 (ChacaNPC 하루 대화 횟수·의뢰 일일 주기와 같은 기준). 날짜 문자열로 공유 DB 에 기록. */
    String today() {
        return LocalDate.now(zone).toString();
    }

    /** 일일 제한 관리 (메모 #90): 그날 기록 보기·지우기·하루 1번 기록. 완료는 메인 스레드. */
    CompletableFuture<List<AffinityStore.DailyRow>> dailyRows(UUID player, String day) { return work(() -> store.dailyRows(player, day)); }
    CompletableFuture<Integer> resetDaily(UUID player, String day, String source) { return work(() -> store.resetDaily(player, day, source)); }
    CompletableFuture<Boolean> markOnce(UUID player, String npc, String source) { String day = today(); return work(() -> store.markOnce(player, npc, day, source)); }

    int capFor(String source) {
        return caps.getOrDefault(source, defaultCap);
    }

    private void remember(UUID player, String npc, AffinityStore.Row row) {
        cache.computeIfAbsent(player, k -> new ConcurrentHashMap<>()).put(npc, row);
        titleHook(player, Map.of(npc, row.score()));
    }

    /** 칭호 지급 훅 (메모 #21): 호감도 칭호는 title-awards.yml, 중복은 칭호 장부가 막는다. 서버 스레드에서만. */
    private void titleHook(UUID player, Map<String, Integer> scores) {
        var titles = plugin.titleService();
        if (titles != null && Bukkit.isPrimaryThread()) titles.affinity(player, scores);
        // 마법 발견 v2 축복의 리본: 아무 NPC와 호감도 30 (접속 때 읽은 값도 포함)
        if (Bukkit.isPrimaryThread() && scores.values().stream().anyMatch(v -> v != null && v >= 30)) DiscoveryLink.signal(player, "affinity.any_30", 1);
    }

    private void load(UUID player) {
        work(() -> store.loadAll(player)).thenAccept(map -> {
            if (Bukkit.getPlayer(player) != null) {
                cache.put(player, new ConcurrentHashMap<>(map));
                var scores = new LinkedHashMap<String, Integer>();
                map.forEach((npc, row) -> scores.put(npc, row.score()));
                titleHook(player, scores);
            }
        });
    }

    @EventHandler
    public void join(PlayerJoinEvent e) {
        load(e.getPlayer().getUniqueId());
        resolveGifts(e.getPlayer());
    }

    // ------------------------------------------------------------------ 선물 영수증 (A08, 2026-10-21)

    static String serverOrigin(Path folder) throws java.io.IOException {
        Path identity = folder.resolve("mailbox-origin.txt");
        if (!Files.exists(identity)) {
            try { Files.writeString(identity, UUID.randomUUID().toString(), java.nio.file.StandardOpenOption.CREATE_NEW); }
            catch (java.nio.file.FileAlreadyExistsException ignored) { /* 우편이 먼저 만듦 */ }
        }
        return UUID.fromString(Files.readString(identity).strip()).toString();
    }

    private NamespacedKey giftReceipt(String token) {
        return new NamespacedKey(plugin, "gift_receipt_" + token.replace("-", ""));
    }

    /**
     * 선물 아이템 1개 차감 + 영수증(PDC) + saveData 를 같은 틱에 한다 (우편 수령과 같은 방식). 메인 스레드.
     * @return 1 = 차감하고 영수증 저장, 0 = 칸의 아이템이 바뀌어 아무것도 안 함
     */
    int takeGift(Player p, int slot, ItemStack one, String token) {
        if (!Bukkit.isPrimaryThread()) throw new IllegalStateException("server thread required");
        UUID.fromString(token);
        if (!p.isOnline() || one == null) {
            return 0;
        }
        var inv = p.getInventory();
        ItemStack cur = inv.getItem(slot);
        if (cur == null || !cur.isSimilar(one) || cur.getAmount() < 1) {
            return 0;
        }
        if (cur.getAmount() == 1) {
            inv.setItem(slot, null);
        } else {
            cur.setAmount(cur.getAmount() - 1);
            inv.setItem(slot, cur);
        }
        if (giftItems.size() < 4096) giftItems.put(token, AuditLog.item(one));
        p.getPersistentDataContainer().set(giftReceipt(token), PersistentDataType.BYTE, (byte) 1);
        try {
            p.saveData();
        } catch (RuntimeException e) {
            plugin.getLogger().warning("NPC 선물 saveData 실패 (토큰 " + token + "): 다음 저장 때 차감·영수증이 함께 저장됩니다");
        }
        return 1;
    }

    private record GiftSweep(List<String> settled, java.util.Set<String> pending, int committed, int cancelled, int waiting, int review) {
    }

    /** 접속(이 서버): 이 서버에서 시작한 pending 선물을 영수증으로 확정·취소한다. 다른 서버·옛 기록은 건드리지 않는다. */
    private void resolveGifts(Player p) {
        UUID id = p.getUniqueId();
        long before = System.currentTimeMillis();
        String ns = plugin.getName().toLowerCase(Locale.ROOT);
        var receipts = new java.util.HashSet<String>();
        for (var key : p.getPersistentDataContainer().getKeys()) {
            if (key.getNamespace().equals(ns) && key.getKey().startsWith("gift_receipt_")) {
                receipts.add(key.getKey().substring("gift_receipt_".length()));
            }
        }
        Path file = MailboxBridge.playerFile(p);
        work(() -> {
            var settled = new java.util.ArrayList<String>();
            var pending = new java.util.HashSet<String>();
            int committed = 0, cancelled = 0, waiting = 0, review = 0;
            for (var g : store.pendingGifts(id, before)) {
                String hex = g.token().replace("-", "");
                pending.add(hex);
                boolean pdc = receipts.contains(hex);
                boolean disk = pdc && DeliveryReceipt.saved(file, giftReceipt(g.token()).toString());
                switch (AffinityStore.recovery(g.origin(), origin, pdc, disk)) {
                    case COMMIT -> {
                        if (store.commitGift(g.token(), System.currentTimeMillis()) != null) committed++;
                        settled.add(hex);
                    }
                    case CANCEL -> {
                        if (store.cancelGift(g.token())) cancelled++;
                    }
                    case WAIT -> waiting++;
                    case REVIEW -> review++;
                    case SKIP -> { }
                }
            }
            return new GiftSweep(settled, pending, committed, cancelled, waiting, review);
        }).whenComplete((r, error) -> {
            if (error != null || r == null) {
                return;   // 다음 접속 때 다시
            }
            if (r.committed() + r.cancelled() + r.review() > 0) {
                plugin.getLogger().info("NPC 선물 복구 player=" + id + " 확정=" + r.committed() + " 취소=" + r.cancelled() + " 대기=" + r.waiting() + " 관리자확인=" + r.review());
            }
            Player online = Bukkit.getPlayer(id);
            if (online == null) {
                return;
            }
            // 확정한 영수증과, pending 이 없는 오래된 영수증(이미 처리됨)은 지운다. 대기(WAIT) 중인 것은 남긴다.
            for (String hex : receipts) {
                if (r.settled().contains(hex) || !r.pending().contains(hex)) {
                    online.getPersistentDataContainer().remove(new NamespacedKey(plugin, "gift_receipt_" + hex));
                }
            }
        });
    }

    String origin() {
        return origin;
    }

    /** 관리 명령: 이 플레이어의 끝나지 않은 선물 전부. */
    CompletableFuture<List<AffinityStore.PendingGift>> adminPendingGifts(UUID owner) {
        return work(() -> store.pendingGifts(owner, Long.MAX_VALUE));
    }

    /** 관리 명령: 확정(점수 반영) 또는 취소(하루 횟수 돌려줌). 이미 처리됐으면 false. */
    CompletableFuture<Boolean> adminResolveGift(String token, boolean commit) {
        long now = System.currentTimeMillis();
        return work(() -> commit ? store.commitGift(token, now) != null : store.cancelGift(token));
    }

    @EventHandler
    public void quit(PlayerQuitEvent e) {
        cache.remove(e.getPlayer().getUniqueId());
    }

    // ------------------------------------------------------------------ 호감도

    /** 현재 켜진 ChacaNPC가 실제로 등록한 캐릭터 ID → 표시 이름. DB의 임의 NPC 키는 포함하지 않는다. */
    Map<String, String> registeredNpcs() {
        if (!Bukkit.isPrimaryThread()) throw new IllegalStateException("server thread required");
        var chaca = Bukkit.getPluginManager().getPlugin("ChacaNPC");
        if (chaca == null || !chaca.isEnabled()) {
            return Map.of();
        }
        try {
            // ChacaNPC는 Bridge를 선택 의존하므로 반대 방향의 컴파일/로드 의존성을 만들지 않는다.
            Object repository = chaca.getClass().getMethod("characters").invoke(chaca);
            Object entries = repository.getClass().getMethod("all").invoke(repository);
            if (!(entries instanceof Collection<?> characters)) {
                throw new IllegalStateException("ChacaNPC character catalog is unavailable");
            }
            Map<String, String> names = new java.util.TreeMap<>();
            for (Object character : characters) {
                Object rawId = character.getClass().getMethod("id").invoke(character);
                Object rawName = character.getClass().getMethod("name").invoke(character);
                if (!(rawId instanceof String id) || id.isBlank() || id.codePointCount(0, id.length()) > 48) {
                    continue;
                }
                String name = rawName instanceof String text && !text.isBlank() ? text : id;
                names.put(id, name);
            }
            catalogFailureReported = false;
            return Collections.unmodifiableMap(new LinkedHashMap<>(names));
        } catch (ReflectiveOperationException | RuntimeException e) {
            if (!catalogFailureReported) {
                plugin.getLogger().warning("NPC registered catalog unavailable: " + e.getClass().getSimpleName());
                catalogFailureReported = true;
            }
            return Map.of();
        }
    }

    /** 관리 명령 전용. 등록 ID 또는 유일한 표시 이름만 허용하며 완료 콜백은 메인 스레드에서 실행한다. */
    CompletableFuture<AffinityStore.Row> adminChange(UUID player, String npc, String action, int nonnegativeValue) {
        if (!Bukkit.isPrimaryThread()) throw new IllegalStateException("server thread required");
        if (player == null) throw new IllegalArgumentException("플레이어를 지정해 주세요.");
        if (nonnegativeValue < 0) throw new IllegalArgumentException("호감도 값은 0 이상이어야 합니다.");
        String operation = switch (action == null ? "" : action.toLowerCase(Locale.ROOT)) {
            case "보기" -> "query";
            case "설정" -> "set";
            case "추가" -> "add";
            case "빼기" -> "remove";
            default -> throw new IllegalArgumentException("보기/설정/추가/빼기 중 하나를 지정해 주세요.");
        };
        String id = registeredNpcId(registeredNpcs(), npc);
        long now = System.currentTimeMillis();
        return work(() -> operation.equals("query") ? store.load(player, id)
                : store.adminChange(player, id, operation, nonnegativeValue, now)).thenApply(row -> {
            if (!operation.equals("query")) AuditLog.log(player, AuditLog.호감도, "관리자 호감도 " + action + " " + nonnegativeValue + " · " + id + " → " + row.score() + " (하트 " + row.heart() + ")",
                    "NPC", id, "경로", "관리자", "변경", action, "값", nonnegativeValue, "점수", row.score(), "하트", row.heart());
            // 오프라인 관리로 접속자 캐시가 무한히 늘어나지 않게 한다.
            if (Bukkit.getPlayer(player) != null) remember(player, id, row);
            return row;
        });
    }

    /** 관리 명령 전용: 하트 단계를 그대로 정한다(내릴 수도 있음). 완료 콜백은 메인 스레드. */
    CompletableFuture<AffinityStore.Row> adminHeart(UUID player, String npc, int level) {
        if (!Bukkit.isPrimaryThread()) throw new IllegalStateException("server thread required");
        long now = System.currentTimeMillis();
        return work(() -> store.adminHeart(player, npc, level, now)).thenApply(row -> {
            AuditLog.log(player, AuditLog.호감도, "관리자 하트 단계 " + level + " · " + npc + " (점수 " + row.score() + ")", "NPC", npc, "경로", "관리자하트", "하트", row.heart(), "점수", row.score());
            if (Bukkit.getPlayer(player) != null) remember(player, npc, row);
            return row;
        });
    }

    static String registeredNpcId(Map<String, String> names, String input) {
        String query = input == null ? "" : input.trim();
        if (!query.isEmpty() && names.containsKey(query)) return query;
        String found = null;
        for (var entry : names.entrySet()) {
            if (entry.getKey().equalsIgnoreCase(query) || entry.getValue().equalsIgnoreCase(query)) {
                if (found != null && !found.equals(entry.getKey())) {
                    throw new IllegalArgumentException("같은 이름의 NPC가 여러 명입니다. 등록 ID로 지정해 주세요.");
                }
                found = entry.getKey();
            }
        }
        if (found == null) throw new IllegalArgumentException("등록된 NPC ID 또는 이름을 지정해 주세요.");
        return found;
    }

    CompletableFuture<AffinityStore.Row> affinity(UUID player, String npc) {
        return work(() -> store.load(player, npc)).thenApply(row -> {
            remember(player, npc, row);
            // 대화를 열 때도 확인: 설정을 나중에 넣었거나 다른 서버에서 상한에 닿은 경우 (이미 받았으면 아무 일 없음)
            offerGateQuest(player, npc, row);
            return row;
        });
    }

    /** Artifact hook (chatty_feather, village_friend): changes an affinity amount before it is stored. Server thread. */
    private volatile java.util.function.ToIntBiFunction<UUID, Integer> affinityModifier;
    void affinityModifier(java.util.function.ToIntBiFunction<UUID, Integer> modifier) { affinityModifier = modifier; }
    private int modified(UUID player, int amount) {
        var m = affinityModifier;
        if (m == null || !Bukkit.isPrimaryThread()) return amount;
        try { return m.applyAsInt(player, amount); } catch (RuntimeException e) { return amount; }
    }

    /** Artifact love_letter: NPCs whose affinity is maxed (score 100) for this player, from the join cache. */
    int maxedCount(UUID player) {
        var rows = cache.get(player);
        if (rows == null) return 0;
        int n = 0;
        for (var row : rows.values()) if (row.score() >= AffinityStore.gate(4)) n++;
        return n;
    }

    CompletableFuture<AffinityStore.AddResult> add(UUID player, String npc, String source, int amount) {
        final int value = modified(player, amount);
        String day = today();
        int cap = capFor(source);
        long now = System.currentTimeMillis();
        return work(() -> store.add(player, npc, source, value, cap, day, now)).thenApply(r -> {
            remember(player, npc, r.row());
            auditAffinity(player, npc, source, value, r, null);
            reachedGate(player, npc, r);
            return r;
        });
    }

    /**
     * #83 호의적 AI 대화 보상 (NPC가 이번 대화를 마음에 들어 했다). NPC 하나당 하루 chat-reward.daily-count 번,
     * 한 번에 chat-reward.amount. 상한(30·60·90, 퀘스트 미완료)이면 오르지 않고 횟수도 쓰지 않는다.
     * 횟수는 공유 DB(codex_affinity_daily, source "chat_reward")에 날짜별로 남아 서버 이동·재접속으로 초기화되지 않는다.
     */
    CompletableFuture<AffinityStore.AddResult> chatReward(UUID player, String npc) {
        final int value = modified(player, chatRewardAmount);
        String day = today();
        int count = chatRewardCount;
        long now = System.currentTimeMillis();
        return work(() -> store.addCounted(player, npc, "chat_reward", value, count, day, now)).thenApply(r -> {
            remember(player, npc, r.row());
            auditAffinity(player, npc, "chat_reward", value, r, null);
            reachedGate(player, npc, r);
            return r;
        });
    }

    // ------------------------------------------------------------------ 호감도 퀘스트 연결 (#39, #100)

    /**
     * affinity.yml gate-quests:
     * <pre>
     * gate-quests:
     *   ella:
     *     30: ella_affinity_1
     *     60: ella_affinity_2
     *     90: ella_affinity_3
     * </pre>
     * 상한(30·60·90)에 닿으면 그 퀘스트를 기존 의뢰 수락 경로로 자동으로 준다. 그 퀘스트를 완료(보상 받기)하면
     * 하트 단계가 올라 다음 상한이 열린다 (custom heart 동작과 같음). 비어 있으면 아무 일 없음.
     */
    private void loadGateQuests(org.bukkit.configuration.ConfigurationSection root) {
        if (root == null) {
            return;
        }
        for (String npc : root.getKeys(false)) {
            var section = root.getConfigurationSection(npc);
            if (section == null || !npc.matches("[a-z0-9_-]{1,48}")) {
                continue;
            }
            Map<Integer, String> byGate = new java.util.TreeMap<>();
            for (String key : section.getKeys(false)) {
                String quest = section.getString(key, "").trim();
                int level = gateLevel(key);
                if (level < 1 || quest.isEmpty()) {
                    if (!quest.isEmpty()) plugin.getLogger().warning("affinity.yml gate-quests." + npc + "." + key + ": 30 / 60 / 90 중 하나여야 해요");
                    continue;
                }
                if (questGates.containsKey(quest)) {
                    plugin.getLogger().warning("affinity.yml gate-quests: 퀘스트 " + quest + " 가 두 번 적혀 있어요 (처음 것만 씀)");
                    continue;
                }
                byGate.put(level * 30, quest);
                questGates.put(quest, new String[]{npc, String.valueOf(level)});
            }
            if (!byGate.isEmpty()) {
                gateQuests.put(npc, byGate);
            }
        }
    }

    /** "30" → 1, "60" → 2, "90" → 3 (깬 뒤의 하트 단계), 그 밖은 0. */
    static int gateLevel(String key) {
        return switch (key == null ? "" : key.trim()) {
            case "30" -> 1;
            case "60" -> 2;
            case "90" -> 3;
            default -> 0;
        };
    }

    /** 지금 상한에 닿아 있으면 그 상한의 퀘스트 ID (설정 없으면 null). */
    static String gateQuest(Map<Integer, String> byGate, AffinityStore.Row row) {
        if (byGate == null || row == null || row.heart() >= 3) {
            return null;
        }
        int gate = AffinityStore.gate(row.heart());
        return row.score() >= gate ? byGate.get(gate) : null;
    }

    enum GateAction { NONE, OFFER, UNLOCK }

    /** 상한에 닿았을 때: 이미 깬 퀘스트면 다음 상한 열기, 받을 수 있으면 지급, 그 밖(진행 중 등)은 아무 일 없음. */
    static GateAction gateAction(boolean completedEver, boolean offerable) {
        if (completedEver) return GateAction.UNLOCK;
        return offerable ? GateAction.OFFER : GateAction.NONE;
    }

    private void reachedGate(UUID player, String npc, AffinityStore.AddResult r) {
        if (r != null && r.applied() > 0) {
            offerGateQuest(player, npc, r.row());
        }
    }

    /** 상한에 닿아 있고 그 상한 퀘스트가 설정돼 있으며 아직 받지도 깨지도 않았으면 자동으로 준다. 메인 스레드. */
    private void offerGateQuest(UUID player, String npc, AffinityStore.Row row) {
        String quest = gateQuest(gateQuests.get(npc), row);
        QuestBridge q = plugin.questBridge();
        Player p = Bukkit.getPlayer(player);
        if (quest == null || q == null || p == null) {
            return;
        }
        GateAction action = gateAction(q.completedEver(p, quest), q.npcOfferable(p, quest));
        if (action == GateAction.UNLOCK) {
            // #100: 이미 깬 상한 퀘스트인데 하트가 안 올라 상한에 갇힌 경우 (다른 서버에서 보상 받음·하트 기록 실패·설정을 나중에 넣음)
            // → 다시 주지 않고 바로 다음 상한을 연다 (하트는 내려가지 않으므로 여러 번 불려도 같음)
            questCompleted(player, quest);
            return;
        }
        if (action == GateAction.NONE) {
            return;   // 진행 중, 권한 없음, 공개 전 → 다음 대화·선물 때 다시 확인
        }
        String key = player + "|" + quest;
        if (!gateOffering.add(key)) {
            return;   // 같은 지급이 처리 중
        }
        q.acceptFromDialogue(p, quest, ok -> {
            gateOffering.remove(key);
            if (Boolean.TRUE.equals(ok)) {
                plugin.getLogger().info("NPC 호감도 퀘스트 지급 player=" + player + " npc=" + npc + " quest=" + quest + " (호감도 " + row.score() + ")");
            }
        });
    }

    /** QuestBridge: 퀘스트 보상까지 끝남. 호감도 퀘스트면 하트 단계를 올려 다음 상한을 연다. 메인 스레드. */
    private void questCompleted(UUID player, String questId) {
        String[] gate = questGates.get(questId);
        if (gate == null || closing) {
            return;
        }
        String npc = gate[0];
        int level = Integer.parseInt(gate[1]);
        setHeart(player, npc, level).thenAccept(row -> {
            plugin.getLogger().info("NPC 호감도 퀘스트 완료 player=" + player + " npc=" + npc + " quest=" + questId + " → 하트 " + row.heart() + " (상한 " + AffinityStore.gate(row.heart()) + ")");
            Player p = Bukkit.getPlayer(player);
            if (p != null) {
                notify(p, npc, "heart", String.valueOf(level));
                offerGateQuest(player, npc, row);   // 이미 다음 상한에도 닿아 있는 경우 (관리자 변경 등)
            }
        }).exceptionally(error -> {
            plugin.getLogger().severe("NPC 호감도 퀘스트 완료 반영 실패 player=" + player + " npc=" + npc + " quest=" + questId
                    + " — /유저관리 <닉네임> 호감도 " + npc + " 하트 설정 " + level + " 로 직접 열어 주세요");
            return null;
        });
    }

    CompletableFuture<AffinityStore.Row> setHeart(UUID player, String npc, int level) {
        long now = System.currentTimeMillis();
        return work(() -> store.setHeart(player, npc, level, now)).thenApply(row -> {
            remember(player, npc, row);
            return row;
        });
    }

    CompletableFuture<AffinityStore.Row> setNickname(UUID player, String npc, String nickname) {
        long now = System.currentTimeMillis();
        return work(() -> store.setNickname(player, npc, nickname, now)).thenApply(row -> {
            remember(player, npc, row);
            return row;
        });
    }

    CompletableFuture<AffinityStore.GiftBegin> beginGift(UUID player, String npc, String token, int delta) {
        final int value = modified(player, delta);
        String day = today();
        long now = System.currentTimeMillis();
        return work(() -> store.beginGift(token, player, npc, value, day, giftDailyCount, now, origin));
    }

    CompletableFuture<AffinityStore.AddResult> commitGift(UUID player, String npc, String token) {
        long now = System.currentTimeMillis();
        return work(() -> store.commitGift(token, now)).thenApply(r -> {
            if (r != null) {
                remember(player, npc, r.row());
                reachedGate(player, npc, r);
                auditAffinity(player, npc, "gift", r.applied(), r, giftItems.get(token));
            }
            giftItems.remove(token);
            // 확정됐거나(이번 또는 이전에) 이미 처리된 토큰: 영수증은 더 필요 없다.
            Player online = Bukkit.getPlayer(player);
            if (online != null) {
                online.getPersistentDataContainer().remove(giftReceipt(token));
            }
            return r;
        });
    }

    /** 운영 기록 (메모 #92): 실제로 바뀐 호감도만 (applied 0 이면 상한·일일 제한으로 안 오른 것 → 남기지 않음, 선물은 성공이라 남김). */
    private static void auditAffinity(UUID player, String npc, String source, int requested, AffinityStore.AddResult r, String item) {
        if (r != null && source.equals("gift")) { AuditStats.add(player, "선물", 1); AuditStats.add(player, "선물:" + npc, 1); }
        if (r == null || r.applied() == 0 && !source.equals("gift")) return;
        String how = switch (source) { case "gift" -> "선물"; case "chat_reward" -> "AI 대화"; default -> source; };
        AuditLog.log(player, AuditLog.호감도, how + " · " + npc + " " + (r.applied() >= 0 ? "+" : "") + r.applied() + " → " + r.row().score() + " (하트 " + r.row().heart() + ")"
                        + (item == null ? "" : " · " + item),
                "NPC", npc, "경로", source, "요청", requested, "증감", r.applied(), "점수", r.row().score(), "하트", r.row().heart(), "선물", item);
    }

    /** 선물 토큰 → 아이템 이름 (운영 기록용, 확정·취소 때 지움). */
    private final Map<String, String> giftItems = new java.util.concurrent.ConcurrentHashMap<>();

    CompletableFuture<Boolean> cancelGift(String token) {
        giftItems.remove(token);
        return work(() -> store.cancelGift(token));
    }

    /** 고정 대화 조건용 (메인 스레드, 캐시만 본다). */
    AffinityStore.Row cached(UUID player, String npc) {
        Map<String, AffinityStore.Row> m = cache.get(player);
        if (m == null) {
            return null;
        }
        return m.getOrDefault(npc, AffinityStore.Row.EMPTY);
    }

    // ------------------------------------------------------------------ 고정 대화 조건·동작

    private void registerDialogueHooks() {
        // 조건: affinity  인자 예) "ella>=40"  "ella heart>=2"  "ella heart<1"
        plugin.registerDialogueCondition("affinity", (p, arg) -> {
            var m = java.util.regex.Pattern.compile("\\s*([a-z0-9_-]{1,48})\\s*(heart\\s*)?(>=|<=|>|<|=)\\s*(-?\\d{1,3})\\s*")
                    .matcher(arg == null ? "" : arg);
            if (!m.matches()) {
                return false;
            }
            AffinityStore.Row row = cached(p.getUniqueId(), m.group(1));
            if (row == null) {
                load(p.getUniqueId());
                return false;
            }
            int v = m.group(2) != null ? row.heart() : row.score();
            int n = Integer.parseInt(m.group(4));
            return switch (m.group(3)) {
                case ">=" -> v >= n;
                case "<=" -> v <= n;
                case ">" -> v > n;
                case "<" -> v < n;
                default -> v == n;
            };
        });
        // 동작: affinity  인자 "ella +5"
        plugin.registerDialogueAction("affinity", (p, arg) -> {
            String[] a = arg.trim().split("\\s+");
            if (a.length == 2 && a[0].matches("[a-z0-9_-]{1,48}") && a[1].matches("[+-]?\\d{1,3}")) {
                add(p.getUniqueId(), a[0], "dialogue", Integer.parseInt(a[1].replace("+", "")));
            } else {
                plugin.getLogger().warning("custom affinity 인자 오류: " + arg);
            }
        });
        // 동작: heart  인자 "ella 2"  (하트 이벤트 2단계 완료 → 점수 상한 해제, ChacaNPC 소문)
        plugin.registerDialogueAction("heart", (p, arg) -> {
            String[] a = arg.trim().split("\\s+");
            if (a.length == 2 && a[0].matches("[a-z0-9_-]{1,48}") && a[1].matches("[0-5]")) {
                int level = Integer.parseInt(a[1]);
                setHeart(p.getUniqueId(), a[0], level).thenAccept(row ->
                        notify(p, a[0], level >= 5 ? "ending" : "heart", String.valueOf(level)));
            } else {
                plugin.getLogger().warning("custom heart 인자 오류: " + arg);
            }
        });
        // 동작: nickname  인자 "ella 책벌레"
        plugin.registerDialogueAction("nickname", (p, arg) -> {
            String[] a = arg.trim().split("\\s+", 2);
            if (a.length == 2 && a[0].matches("[a-z0-9_-]{1,48}") && a[1].codePointCount(0, a[1].length()) <= 8) {
                setNickname(p.getUniqueId(), a[0], a[1]).thenAccept(row -> notify(p, a[0], "nickname", a[1]));
            } else {
                plugin.getLogger().warning("custom nickname 인자 오류: " + arg);
            }
        });
    }

    private void notify(Player p, String npc, String type, String value) {
        Map<String, String> event = Map.of("player", p.getUniqueId().toString(), "playerName", plugin.names().name(p),
                "npc", npc, "type", type, "value", value);
        for (var l : listeners) {
            try {
                l.accept(event);
            } catch (RuntimeException e) {
                plugin.getLogger().warning("NPC social listener: " + e.getClass().getSimpleName());
            }
        }
    }

    void addListener(Consumer<Map<String, String>> l) {
        if (l != null) {
            listeners.add(l);
        }
    }

    void removeListener(Consumer<Map<String, String>> l) {
        listeners.remove(l);
    }

    // ------------------------------------------------------------------ 스토리·퀘스트 위임

    CompletableFuture<String> openStory(Player p, int citizensId, Entity anchor) {
        DialogueBridge d = plugin.dialogueBridge();
        return d == null ? CompletableFuture.completedFuture("BLOCKED") : d.openBoundStory(p, citizensId, anchor);
    }

    void claim(IntPredicate claimed) {
        DialogueBridge d = plugin.dialogueBridge();
        if (d != null) {
            d.claim(claimed);
        }
    }

    CompletableFuture<String> storySummary(UUID player) {
        DialogueBridge d = plugin.dialogueBridge();
        return d == null ? CompletableFuture.completedFuture("") : d.storySummary(player);
    }

    QuestBridge quests() {
        return plugin.questBridge();
    }

    @Override
    public void close() {
        closing = true;
        DialogueBridge d = plugin.dialogueBridge();
        if (d != null) {
            d.claim(null);
        }
        listeners.clear();
        if (facade != null) {
            Bukkit.getServicesManager().unregister(NpcSocialFacade.class, facade);
        }
        io.shutdown();
        try {
            if (io.awaitTermination(15, TimeUnit.SECONDS)) {
                store.close();
            } else {
                plugin.getLogger().warning("NPC affinity worker shutdown timeout");
            }
        } catch (Exception e) {
            plugin.getLogger().warning("NPC affinity close failed");
        }
        HandlerList.unregisterAll(this);
        cache.clear();
    }
}
