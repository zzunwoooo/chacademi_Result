package school.magiccodex.paper;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import java.io.File;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Sound;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.messaging.PluginMessageListener;
import school.magiccodex.protocol.LootBoxProtocol;

/**
 * 뽑기 상자 (오너 이름: API 상자, 2026-10-26) — 모든 뽑기권 연출에 쓰는 범용 상자.
 *
 * <ul>
 *   <li>열쇠 아이템 우클릭 = 1개 열기, Shift+우클릭 = 10개 열기 (열쇠 10개 미만이면 거절, 소모 없음).</li>
 *   <li>서버가 추첨을 확정하고 {@link LootBoxJournal} 에 적은 뒤, 같은 틱에 열쇠 차감 + 보상 넣기({@link RewardDelivery} 칸 규칙) +
 *       영수증(PDC) + saveData, 남은 것만 우편. 그 다음에야 클라에 연출(PLAY)을 보낸다. 확인 버튼·연출 끝(DONE)은 지급과 무관.</li>
 *   <li>모드 없는 클라: 연출 없이 같은 지급 + 채팅 결과.</li>
 *   <li>/상자관리 생성·관리·목록·삭제·확률·테스트·새로고침·대기. 정의는 plugins/MagicCodexBridge/loot-boxes.yml.</li>
 *   <li>가구 보상(furniture, 2026-10-26 가구 뽑기권): 아이템이 아니라 하우징 가구 권한. 이미 가진 가구·이번 개봉에서 이미 나온 가구는
 *       추첨에서 빠진다(가지지 않은 것 중 같은 비율로 다시 뽑기, {@link LootBoxRules#drawUnique}). 권한은 ②(열쇠 차감·영수증) 뒤 ③에서
 *       우편과 함께 주고({@link #settle}), 영수증이 있으면 복구 때 다시 준다 (권한은 여러 번 줘도 같음).</li>
 * </ul>
 * 서버 부담: 우클릭 때 열쇠 재질 집합으로 먼저 거르고, 파일·영수증 IO 는 전용 스레드 하나. 게임 스레드는 추첨·칸 계산(36칸)만.
 */
final class LootBoxBridge implements Listener, PluginMessageListener, AutoCloseable {
    private static final long COOLDOWN_MS = 1200, ADMIN_SESSION_MS = 600_000;
    private static final String MAIL_BODY = "인벤토리에 자리가 없어 남은 보상을 우편으로 보내 드려요.";

    private final MagicCodexBridge plugin;
    private final ItemsAdderItems itemsAdder;
    private final LootBoxStore store;
    private final LootBoxJournal journal;
    private final File file;
    private final ThreadPoolExecutor io = new ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS, new LinkedBlockingQueue<>(),
            r -> { var t = new Thread(r, "MagicCodex-lootbox-io"); t.setDaemon(true); return t; });
    private final Set<UUID> busy = new HashSet<>();
    private final Map<UUID, Long> cooldown = new HashMap<>();
    /** 지급(②)까지 끝나 우편·정리만 남은 개봉 (op → 기록). 메인 스레드. */
    private final Map<String, LootBoxJournal.Opening> pending = new LinkedHashMap<>();
    private final Set<String> mailing = new HashSet<>();
    private final Map<String, Integer> retries = new HashMap<>();
    /** 가구 권한 주는 중 / 이번 실행에서 다 준 개봉 (op). 메인 스레드. */
    private final Set<String> granting = new HashSet<>(), granted = new HashSet<>();
    private final Map<String, Integer> grantRetries = new HashMap<>();
    private final List<Path> broken = new ArrayList<>();
    private Set<Material> keyMaterials;
    private long keyRevision = -1;
    private boolean closing;
    /** 이 서버 표시(우편과 같은 mailbox-origin.txt 앞 8자리). 영수증 이름에 넣어, 다른 서버(학교↔야생)가 이 서버의 영수증을 지우지 않게 한다. */
    private final String origin;

    private record Admin(Player player, long token, long sequence, long expires) {}
    private final Map<UUID, Admin> admins = new HashMap<>();

    LootBoxBridge(MagicCodexBridge plugin) throws Exception {
        this.plugin = plugin;
        this.itemsAdder = new ItemsAdderItems(plugin.getLogger());
        this.store = new LootBoxStore(itemsAdder);
        this.file = new File(plugin.getDataFolder(), "loot-boxes.yml");
        if (!file.exists()) plugin.saveResource("loot-boxes.yml", false);
        store.load(LootBoxStore.read(file));
        for (var b : store.boxes()) if (!b.valid()) plugin.getLogger().warning("뽑기 상자 '" + b.name() + "' (" + b.id() + ") 설정 오류: " + String.join(" / ", b.problems()));
        journal = new LootBoxJournal(plugin.getDataFolder().toPath().resolve("lootbox-receipts"));
        origin = NpcSocialService.serverOrigin(plugin.getDataFolder().toPath()).replace("-", "").substring(0, 8);
        Bukkit.getPluginManager().registerEvents(this, plugin);
        var m = Bukkit.getMessenger();
        m.registerOutgoingPluginChannel(plugin, LootBoxProtocol.PLAY);
        m.registerIncomingPluginChannel(plugin, LootBoxProtocol.DONE, this);
        m.registerIncomingPluginChannel(plugin, LootBoxProtocol.ADMIN_REQUEST, this);
        m.registerOutgoingPluginChannel(plugin, LootBoxProtocol.ADMIN_RESPONSE);
        CmdKit.register(plugin, "/상자관리", this::commands);
        if (ChacaApi.ready()) {
            ChacaApi.reload(plugin, "뽑기상자", "loot-boxes.yml (뽑기 상자)", this::reloadNow);
            ChacaApi.status(plugin, "뽑기 상자", () -> store.boxes().size() + "개 · 지급 정리 대기 " + pending.size() + "건" + (broken.isEmpty() ? "" : " · 읽을 수 없는 영수증 " + broken.size()));
        }
        Bukkit.getScheduler().runTask(plugin, this::recover);
        plugin.getLogger().info("뽑기 상자 " + store.boxes().size() + "개 준비");
    }

    private void main(Runnable r) { if (!closing && plugin.isEnabled()) Bukkit.getScheduler().runTask(plugin, r); }
    private NamespacedKey key(String receipt) { return new NamespacedKey(plugin, receipt); }
    /** 플레이어 데이터(PDC) 영수증 이름: lootbox_&lt;이 서버 8자리&gt;_&lt;개봉번호&gt;. */
    private String receipt(LootBoxJournal.Opening o) { return "lootbox_" + origin + "_" + o.operation().replace("-", ""); }
    private static Path playerFile(UUID id) { return Bukkit.getWorlds().getFirst().getWorldFolder().toPath().resolve("playerdata").resolve(id + ".dat"); }

    // ------------------------------------------------------------------ 저장 · 새로고침

    /** 메인 스레드에서 모델을 문자열로 만들고, 파일 쓰기는 IO 스레드. */
    private void persist(CommandSender who) {
        String text = store.text();
        io.execute(() -> {
            try { LootBoxStore.write(file, text); }
            catch (Exception e) {
                plugin.getLogger().severe("loot-boxes.yml 저장 실패: " + e);
                main(() -> { if (who != null) CmdKit.fail(who, "loot-boxes.yml 저장에 실패했어요 (콘솔 확인). 서버를 끄기 전에 다시 저장해 주세요", e); });
            }
        });
    }

    /** /서버관리 새로고침 뽑기상자 (메인 스레드, 작은 파일 하나). */
    private void reloadNow() throws Exception {
        store.load(LootBoxStore.read(file));
        keyRevision = -1;
        for (var b : store.boxes()) if (!b.valid()) plugin.getLogger().warning("뽑기 상자 '" + b.name() + "' 설정 오류: " + String.join(" / ", b.problems()));
    }

    /** /상자관리 새로고침: 읽기는 IO 스레드, 바꾸기는 메인. */
    private void reload(CommandSender sender) {
        io.execute(() -> {
            String text;
            try { text = LootBoxStore.read(file); }
            catch (Exception e) { main(() -> CmdKit.fail(sender, "loot-boxes.yml 을 읽지 못했어요", e)); return; }
            main(() -> {
                try { store.load(text); }
                catch (Exception e) { CmdKit.fail(sender, "loot-boxes.yml 형식 오류 — 이전 설정을 그대로 씁니다", e); return; }
                keyRevision = -1;
                long bad = store.boxes().stream().filter(b -> !b.valid()).count();
                CmdKit.ok(sender, "뽑기 상자 " + store.boxes().size() + "개를 다시 읽었어요" + (bad > 0 ? " (설정 오류 " + bad + "개 — /상자관리 목록)" : ""));
            });
        });
    }

    // ------------------------------------------------------------------ 열쇠 우클릭

    /** 열쇠 재질 집합 (우클릭마다 ItemsAdder 조회를 하지 않게). null = 아직 모름(ItemsAdder 준비 전) → 모든 아이템을 확인. */
    private Set<Material> keyMaterials() {
        if (keyRevision == store.revision() && keyMaterials != null) return keyMaterials;
        var out = EnumSet.noneOf(Material.class);
        for (var b : store.boxes()) {
            var sample = store.keySample(b.key());
            if (sample == null) { if (b.key().itemsadder() != null) return null; continue; }
            out.add(sample.getType());
        }
        keyMaterials = out; keyRevision = store.revision();
        return out;
    }

    private LootBoxStore.Box boxFor(ItemStack item) {
        if (item == null || item.getType().isAir() || store.boxes().isEmpty()) return null;
        var materials = keyMaterials();
        if (materials != null && !materials.contains(item.getType())) return null;
        for (var b : store.boxes()) if (store.matches(b.key(), item)) return b;
        return null;
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void use(PlayerInteractEvent event) {
        if (event.getAction() != Action.RIGHT_CLICK_AIR && event.getAction() != Action.RIGHT_CLICK_BLOCK) return;
        var p = event.getPlayer();
        if (event.getHand() != EquipmentSlot.HAND) {
            // 보조 손의 열쇠는 쓰지 않는다 (설치·사용만 막음).
            if (event.getHand() == EquipmentSlot.OFF_HAND && boxFor(p.getInventory().getItemInOffHand()) != null) event.setCancelled(true);
            return;
        }
        var box = boxFor(p.getInventory().getItemInMainHand());
        if (box == null) return;
        event.setCancelled(true);
        open(p, box, p.isSneaking() ? 10 : 1, true, true, null);
    }

    // ------------------------------------------------------------------ 개봉

    /**
     * consume = 열쇠를 쓴다(일반 개봉), grant = 실제 지급(false = 관리자 미리보기, 아무것도 바뀌지 않음). admin = 테스트 명령을 친 사람(결과 안내).
     */
    private void open(Player p, LootBoxStore.Box box, int count, boolean consume, boolean grant, CommandSender admin) {
        UUID id = p.getUniqueId();
        long now = System.currentTimeMillis();
        if (busy.contains(id) || now < cooldown.getOrDefault(id, 0L)) return; // 연타: 아무것도 하지 않음
        String refused = null;
        if (!box.valid()) refused = "상자 설정을 확인해야 해요. 관리자에게 알려 주세요";
        else if (!box.enabled() && consume) refused = "지금은 열 수 없는 상자예요";
        else if (box.totalWeight() <= 0) refused = "아직 보상이 없는 상자예요";
        else if (!plugin.playerStateReady(p) || p.isDead() || p.getGameMode() == GameMode.SPECTATOR) refused = "지금은 열 수 없어요. 잠시 뒤 다시 해 주세요";
        if (refused != null) { refuse(p, admin, refused); return; }
        String[] unique = box.rewards().stream().map(LootBoxStore.Reward::furniture).toArray(String[]::new);
        boolean hasFurniture = Arrays.stream(unique).anyMatch(Objects::nonNull);
        int[] picks;
        if (grant && hasFurniture) {
            var owned = ownedFurniture(p, unique);
            picks = LootBoxRules.drawUnique(box.weights(), unique, owned, count, ThreadLocalRandom.current());
            if (picks == null) {
                int left = LootBoxRules.uniqueLeft(box.weights(), unique, owned);
                refuse(p, admin, left == 0 ? "이 상자의 가구를 모두 모았어요 (열쇠는 그대로예요)"
                        : "받을 수 있는 가구가 " + left + "개 남아 " + count + "개 열기는 할 수 없어요. 1개씩 열어 주세요 (열쇠는 그대로예요)");
                return;
            }
        } else picks = LootBoxRules.drawMany(box.weights(), count, ThreadLocalRandom.current());
        if (picks == null) { refuse(p, admin, "아직 보상이 없는 상자예요"); return; }
        var stacks = new ArrayList<ItemStack>(count);   // 화면·기록용 (가구는 표시용 아이템)
        var items = new ArrayList<ItemStack>(count);    // 실제로 인벤토리·우편으로 가는 아이템
        var furniture = new ArrayList<String>();        // 권한으로 주는 하우징 가구
        int[] grades = new int[count];
        for (int i = 0; i < count; i++) {
            var reward = box.rewards().get(picks[i]);
            var stack = store.create(reward);
            if (stack == null) {
                plugin.getLogger().severe("LOOTBOX 보상 아이템을 만들 수 없음 box=" + box.id() + " reward#" + (picks[i] + 1) + " (" + reward.spec() + ")");
                refuse(p, admin, "보상 아이템을 만들 수 없어요. 관리자에게 알려 주세요 (열쇠는 그대로예요)");
                return;
            }
            stacks.add(stack); grades[i] = reward.grade();
            if (reward.furniture() != null) furniture.add(reward.furniture()); else items.add(stack);
        }
        if (grant && !furniture.isEmpty() && !(ChacaApi.ready() && Bukkit.getPluginManager().isPluginEnabled("LuckPerms"))) {
            plugin.getLogger().severe("LOOTBOX 가구 보상을 줄 수 없음 (LuckPerms 또는 명령어 허브 없음) box=" + box.id());
            refuse(p, admin, "지금은 가구를 받을 수 없어요. 관리자에게 알려 주세요 (열쇠는 그대로예요)");
            return;
        }
        int grade = LootBoxRules.representative(grades);
        if (!grant) { play(p, box, ThreadLocalRandom.current().nextLong(1, Long.MAX_VALUE), grade, stacks, grades, "관리자 미리보기 · 소모·지급 없음"); return; }

        var before = RewardDelivery.copy(p.getInventory().getStorageContents());
        var next = RewardDelivery.copy(before);
        boolean[] usable = RewardDelivery.usable(plugin, p);
        if (consume) {
            int[] amounts = new int[next.length]; boolean[] matches = new boolean[next.length];
            for (int i = 0; i < next.length; i++) if (next[i] != null && store.matches(box.key(), next[i])) { matches[i] = true; amounts[i] = next[i].getAmount(); }
            var take = RewardPlan.takePlan(amounts, matches, usable, p.getInventory().getHeldItemSlot(), count);
            if (take == null) {
                int have = 0;
                for (int i = 0; i < next.length; i++) if (matches[i] && (i == p.getInventory().getHeldItemSlot() || usable[i])) have += amounts[i];
                refuse(p, admin, count == 10 ? "10개 열기는 열쇠가 10개 필요해요 (지금 " + have + "개)" : "열쇠가 부족해요");
                return;
            }
            for (var e : take.entrySet()) { var s = next[e.getKey()]; int left = s.getAmount() - e.getValue(); if (left <= 0) next[e.getKey()] = null; else s.setAmount(left); }
        }
        var left = RewardDelivery.place(next, usable, items, false);
        var mail = new ArrayList<byte[]>();
        for (var part : RewardPlan.mailParts(left, RewardDelivery.STACKS)) for (var it : part) mail.add(it.serializeAsBytes());
        var results = stacks.stream().map(ItemStack::serializeAsBytes).toList();
        String op = UUID.randomUUID().toString();
        busy.add(id);
        cooldown.put(id, now + COOLDOWN_MS);
        try {
            io.execute(() -> {
                LootBoxJournal.Opening saved;
                try { saved = journal.save(op, id, box.id(), box.name(), count, grade, results, grades, mail, furniture); }
                catch (Exception e) {
                    plugin.getLogger().severe("LOOTBOX 영수증 저장 실패 player=" + id + " box=" + box.id() + " (" + e + ") — 열지 않음");
                    main(() -> { busy.remove(id); refuse(p, admin, "지금은 열 수 없어요. 잠시 뒤 다시 해 주세요 (열쇠는 그대로예요)"); });
                    return;
                }
                main(() -> apply(p, box, saved, before, next, usable, stacks, consume, admin));
            });
        } catch (RejectedExecutionException e) { busy.remove(id); refuse(p, admin, "지금은 열 수 없어요. 잠시 뒤 다시 해 주세요"); }
    }

    /** ② 같은 틱: 확인 → 열쇠 차감·보상 넣기(next) + 영수증 + saveData → 연출 → 남은 것 우편. */
    private void apply(Player p, LootBoxStore.Box box, LootBoxJournal.Opening o, ItemStack[] before, ItemStack[] next, boolean[] usable,
                       List<ItemStack> stacks, boolean consume, CommandSender admin) {
        UUID id = p.getUniqueId();
        busy.remove(id);
        boolean same = p.isOnline() && Bukkit.getPlayer(id) == p && !p.isDead() && plugin.playerStateReady(p)
                && MailboxBridge.same(before, p.getInventory().getStorageContents()) && Arrays.equals(usable, RewardDelivery.usable(plugin, p));
        if (!same) {
            // 아무것도 바꾸지 않았다: 기록만 지운다(결과는 아무에게도 보내지 않았으므로 다시 여는 것은 새 개봉이다).
            io.execute(() -> { try { journal.remove(o.operation()); } catch (Exception e) { plugin.getLogger().warning("LOOTBOX 영수증 정리 실패 " + o.operation() + " (" + e + "); 접속 때 영수증 없음으로 정리됨"); } });
            if (p.isOnline()) refuse(p, admin, "인벤토리가 바뀌어 열지 않았어요. 다시 사용해 주세요 (열쇠는 그대로예요)");
            return;
        }
        p.getInventory().setStorageContents(next);
        p.getPersistentDataContainer().set(key(receipt(o)), PersistentDataType.BYTE, (byte) 1);
        try { p.saveData(); } catch (RuntimeException e) { plugin.getLogger().warning("LOOTBOX saveData failed player=" + id + " op=" + o.operation() + " (" + e + "); 다음 저장 때 함께 저장됨"); }
        pending.put(o.operation(), o);
        plugin.getLogger().info("LOOTBOX OPEN player=" + p.getName() + " box=" + box.id() + " count=" + o.count() + " op=" + o.operation() + " mail=" + o.mail().size() + (consume ? "" : " (관리자 테스트 지급)"));
        play(p, box, opening(o.operation()), o.grade(), stacks, o.grades(), o.mail().isEmpty() ? "" : "자리가 부족한 보상은 우편으로 보냈어요");
        audit(p, box, o, stacks, consume);
        if (admin != null && admin != p) CmdKit.ok(admin, ChacaApi.name(id) + " 님에게 " + box.name() + " " + o.count() + "개 결과를 지급했어요 (열쇠 소모 없음)");
        settle(o);
    }

    /** 이미 가진 하우징 가구 (하우징 보관함과 같은 판정: 권한) + 아직 권한을 주는 중인 이 사람의 개봉에 든 가구. 메인 스레드. */
    private Set<String> ownedFurniture(Player p, String[] unique) {
        var owned = new HashSet<String>();
        for (String f : unique) if (f != null && p.hasPermission(school.magiccodex.protocol.HousingRules.permission(f))) owned.add(f);
        for (var o : pending.values()) if (o.player().equals(p.getUniqueId())) owned.addAll(o.furniture());
        return owned;
    }

    private static long opening(String op) { long v = UUID.fromString(op).getMostSignificantBits() & Long.MAX_VALUE; return v == 0 ? 1 : v; }

    private void refuse(Player p, CommandSender admin, String message) {
        CmdKit.notify(p, "warning", "lootbox", "상자를 열지 못했어요", message, 1500);
        if (admin != null && admin != p) CmdKit.userFail(admin, message);
    }

    private static String name(ItemStack it) {
        String s = net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText().serialize(it.displayName());
        if (s.startsWith("[") && s.endsWith("]")) s = s.substring(1, s.length() - 1);
        return s.length() > 120 ? s.substring(0, 120) : s;
    }

    /** 연출 (모드) 또는 채팅 결과 (모드 없음). stacks = 서버가 실제로 지급 확정한 ItemStack. */
    private void play(Player p, LootBoxStore.Box box, long opening, int grade, List<ItemStack> stacks, int[] grades, String notice) {
        if (p.getListeningPluginChannels().contains(LootBoxProtocol.PLAY)) {
            var results = new ArrayList<LootBoxProtocol.Result>();
            for (int i = 0; i < stacks.size(); i++) {
                ItemStack shown = plugin.coreAppearance(stacks.get(i).clone()); // 우편·상점 미리보기와 같은 표시 규칙
                results.add(new LootBoxProtocol.Result(grades[i], shown.serializeAsBytes(), name(stacks.get(i))));
            }
            try {
                p.sendPluginMessage(plugin, LootBoxProtocol.PLAY, LootBoxProtocol.encode(new LootBoxProtocol.Play(opening, box.id(), box.name(), grade, results, notice, box.speed())));
                return;
            } catch (IllegalArgumentException e) { plugin.getLogger().warning("LOOTBOX 연출 패킷이 너무 큼 (" + e.getMessage() + "); 채팅 결과로 대신함"); }
        }
        var lines = new ArrayList<String>();
        lines.add("── " + box.name() + " " + stacks.size() + "개 결과 (" + LootBoxRules.gradeName(grade) + ") ──");
        for (int i = 0; i < stacks.size(); i++) lines.add(" [" + LootBoxRules.gradeName(grades[i]) + "] " + name(stacks.get(i)) + " ×" + stacks.get(i).getAmount());
        if (!notice.isEmpty()) lines.add(" " + notice);
        ChacaApi.lines(p, lines);
        p.playSound(p.getLocation(), grade >= 3 ? Sound.UI_TOAST_CHALLENGE_COMPLETE : Sound.BLOCK_CHEST_OPEN, .7f, 1f);
    }

    private void audit(Player p, LootBoxStore.Box box, LootBoxJournal.Opening o, List<ItemStack> stacks, boolean consume) {
        var text = new StringBuilder();
        for (int i = 0; i < stacks.size(); i++) { if (text.length() > 0) text.append(", "); text.append(LootBoxRules.gradeName(o.grades()[i])).append(' ').append(name(stacks.get(i))).append(" x").append(stacks.get(i).getAmount()); }
        AuditLog.log(p, AuditLog.뽑기상자, box.name() + " " + o.count() + "개 열기 · " + AuditText.clip(text.toString(), 160) + (consume ? "" : " (관리자 테스트)"),
                "상자", box.id(), "이름", box.name(), "횟수", o.count(), "대표등급", LootBoxRules.gradeName(o.grade()), "결과", text.toString(),
                "우편", o.mail().isEmpty() ? null : o.mail().size() + "첨부", "개봉번호", o.operation(), "열쇠소모", consume ? "예" : "아니오");
        if (consume) {
            AuditStats.add(p.getUniqueId(), "뽑기상자개봉", o.count());
            AuditStats.add(p.getUniqueId(), "뽑기상자:" + box.id(), o.count());
            for (int g : o.grades()) if (g >= 2) AuditStats.add(p.getUniqueId(), "뽑기상자" + LootBoxRules.gradeName(g), 1);
        }
    }

    // ------------------------------------------------------------------ ③ 가구 권한 · 남은 것 우편 · ④ 정리

    /** ③: 가구 권한(있으면) → 남은 것 우편(있으면) → ④ 정리. 복구·재접속도 여기로. */
    private void settle(LootBoxJournal.Opening o) {
        if (!o.furniture().isEmpty() && !granted.contains(o.operation())) { grantFurniture(o); return; }
        if (o.mail().isEmpty()) finish(o); else mail(o);
    }

    private void grantFurniture(LootBoxJournal.Opening o) {
        if (!granting.add(o.operation())) return;
        CompletableFuture<Void> all;
        try {
            if (!ChacaApi.ready()) throw new IllegalStateException("command hub unavailable");
            all = CompletableFuture.allOf(o.furniture().stream()
                    .map(f -> ChacaApi.permission(o.player(), school.magiccodex.protocol.HousingRules.permission(f), true)).toArray(CompletableFuture[]::new));
        } catch (RuntimeException e) { granting.remove(o.operation()); grantFailed(o, e); return; }
        all.whenComplete((v, error) -> {
            if (!plugin.isEnabled()) return; // 종료 중: 기록이 남아 다음 시작 때 다시 준다(같은 권한이라 한 번 준 것과 같음)
            Bukkit.getScheduler().runTask(plugin, () -> {
                granting.remove(o.operation());
                if (error != null) { grantFailed(o, error); return; }
                granted.add(o.operation()); grantRetries.remove(o.operation());
                plugin.getLogger().info("LOOTBOX FURNITURE granted player=" + o.player() + " op=" + o.operation() + " furniture=" + String.join(",", o.furniture()));
                if (pending.containsKey(o.operation())) settle(o);
            });
        });
    }

    private void grantFailed(LootBoxJournal.Opening o, Throwable error) {
        int n = grantRetries.merge(o.operation(), 1, Integer::sum);
        plugin.getLogger().warning("LOOTBOX FURNITURE failed (kept) player=" + o.player() + " op=" + o.operation() + " try=" + n + " (" + error + ")");
        var online = Bukkit.getPlayer(o.player());
        if (online != null && n == 1) CmdKit.notify(online, "warning", "lootbox-furniture-wait", "가구 지급이 늦어지고 있어요", "가구는 안전하게 기록돼 있고 자동으로 다시 줘요.", 3000);
        if (n <= 3 && !closing) Bukkit.getScheduler().runTaskLater(plugin, () -> { var cur = pending.get(o.operation()); if (cur != null) settle(cur); }, 1200L);
    }

    private void mail(LootBoxJournal.Opening o) {
        if (!mailing.add(o.operation())) return;
        var service = Bukkit.getServicesManager().load(MailService.class);
        var futures = new ArrayList<CompletableFuture<UUID>>();
        try {
            if (service == null) throw new IllegalStateException("mail service unavailable");
            var items = new ArrayList<ItemStack>();
            for (byte[] b : o.mail()) items.add(ItemStack.deserializeBytes(b));
            for (int part = 0; part * 9 < items.size(); part++)
                futures.add(service.sendSystem("lootbox:" + o.operation() + ":" + part, o.player(), clip(o.title() + " 보상"), MAIL_BODY,
                        List.copyOf(items.subList(part * 9, Math.min(items.size(), part * 9 + 9)))));
        } catch (RuntimeException e) { mailing.remove(o.operation()); mailFailed(o, e); return; }
        CompletableFuture.allOf(futures.toArray(CompletableFuture[]::new)).whenComplete((v, error) -> {
            if (!plugin.isEnabled()) return; // 종료 중: 기록이 남아 다음 시작 때 다시 보낸다(우편 source UNIQUE 라 한 통)
            Bukkit.getScheduler().runTask(plugin, () -> {
                mailing.remove(o.operation());
                if (error != null) { mailFailed(o, error); return; }
                plugin.getLogger().info("LOOTBOX MAIL delivered player=" + o.player() + " op=" + o.operation() + " attachments=" + o.mail().size());
                var online = Bukkit.getPlayer(o.player());
                if (online != null) CmdKit.notify(online, "shop", "lootbox-mail", "남은 상자 보상을 우편으로 보냈어요", "우편함에서 받아 주세요", 0);
                finish(o);
            });
        });
    }

    private static String clip(String s) { return s.length() > 80 ? s.substring(0, 80) : s; }

    private void mailFailed(LootBoxJournal.Opening o, Throwable error) {
        int n = retries.merge(o.operation(), 1, Integer::sum);
        plugin.getLogger().warning("LOOTBOX MAIL failed (kept) player=" + o.player() + " op=" + o.operation() + " try=" + n + " (" + error + ")");
        var online = Bukkit.getPlayer(o.player());
        if (online != null && n == 1) CmdKit.notify(online, "warning", "lootbox-mail-wait", "상자 보상 우편이 늦어지고 있어요", "보상은 안전하게 보관 중이고 자동으로 다시 보내요.", 3000);
        if (n <= 3 && !closing) Bukkit.getScheduler().runTaskLater(plugin, () -> { var cur = pending.get(o.operation()); if (cur != null) mail(cur); }, 1200L);
    }

    /** ④ 영수증 파일을 지운 뒤 플레이어 데이터의 영수증도 지운다. */
    private void finish(LootBoxJournal.Opening o) {
        io.execute(() -> {
            try { journal.remove(o.operation()); }
            catch (Exception e) { plugin.getLogger().warning("LOOTBOX 영수증 삭제 실패 " + o.operation() + " (" + e + "); 다음 접속 때 다시 정리(우편은 한 통)"); return; }
            main(() -> {
                pending.remove(o.operation()); retries.remove(o.operation()); granted.remove(o.operation()); grantRetries.remove(o.operation());
                var online = Bukkit.getPlayer(o.player());
                if (online != null) online.getPersistentDataContainer().remove(key(receipt(o)));
            });
        });
    }

    // ------------------------------------------------------------------ 복구 (재시작 · 재접속)

    private void recover() {
        broken.clear();
        List<LootBoxJournal.Opening> all;
        try { all = journal.pending(broken); }
        catch (Exception e) { plugin.getLogger().severe("LOOTBOX 영수증 읽기 실패 (" + e + "); 기록 유지"); return; }
        if (!broken.isEmpty()) plugin.getLogger().severe("LOOTBOX 읽을 수 없는 영수증 " + broken.size() + "개 (자동 처리 안 함, 관리자 확인): " + broken);
        for (var o : all) pending.putIfAbsent(o.operation(), o);
        if (!all.isEmpty()) plugin.getLogger().warning("LOOTBOX 끝나지 않은 개봉 " + all.size() + "건 다시 확인");
        for (var o : List.copyOf(pending.values())) {
            if (Bukkit.getPlayer(o.player()) != null) continue;
            boolean saved;
            try { saved = DeliveryReceipt.saved(playerFile(o.player()), key(receipt(o)).toString()); }
            catch (Exception x) { plugin.getLogger().warning("LOOTBOX 영수증 확인 실패 op=" + o.operation() + " (" + x + "); 접속 때 다시 확인"); continue; }
            if (saved) settle(o);
            // 디스크에 영수증이 없으면 접속 때 판정한다 (이 서버에서 접속하면 그때 버림)
        }
        for (Player p : Bukkit.getOnlinePlayers()) resolve(p);
    }

    /** 접속: 영수증 있음 = 지급 끝남 → 남은 우편만 다시. 영수증 없음 = 지급 전에 멈춤(열쇠도 그대로) → 기록 버림. */
    private void resolve(Player p) {
        var pdc = p.getPersistentDataContainer();
        for (var o : List.copyOf(pending.values())) {
            if (!o.player().equals(p.getUniqueId()) || mailing.contains(o.operation()) || granting.contains(o.operation())) continue;
            if (pdc.has(key(receipt(o)), PersistentDataType.BYTE)) { settle(o); continue; }
            pending.remove(o.operation());
            io.execute(() -> { try { journal.remove(o.operation()); } catch (Exception e) { plugin.getLogger().warning("LOOTBOX 영수증 정리 실패 " + o.operation() + " (" + e + ")"); } });
            plugin.getLogger().warning("LOOTBOX discarded (영수증 없음: 지급 전에 멈춤, 열쇠 그대로) player=" + p.getUniqueId() + " op=" + o.operation());
        }
        String ns = plugin.getName().toLowerCase(Locale.ROOT);
        for (var k : List.copyOf(pdc.getKeys()))
            if (k.getNamespace().equals(ns) && k.getKey().startsWith("lootbox_" + origin + "_") && pending.values().stream().noneMatch(o -> receipt(o).equals(k.getKey()))) pdc.remove(k); // 이 서버 것만
    }

    @EventHandler public void join(PlayerJoinEvent e) { resolve(e.getPlayer()); }

    @EventHandler public void quit(PlayerQuitEvent e) { UUID id = e.getPlayer().getUniqueId(); cooldown.remove(id); admins.remove(id); /* busy 는 진행 중인 개봉이 스스로 푼다 */ }

    // ------------------------------------------------------------------ 클라 → 서버

    @Override
    public void onPluginMessageReceived(String channel, Player p, byte[] bytes) {
        if (LootBoxProtocol.DONE.equals(channel)) {
            try { LootBoxProtocol.done(bytes); } catch (IllegalArgumentException ignored) { }
            return; // 연출 끝 알림: 지급과 무관 (지급은 연출 전에 끝남)
        }
        if (LootBoxProtocol.ADMIN_REQUEST.equals(channel)) adminRequest(p, bytes);
    }

    // ------------------------------------------------------------------ 명령어 /상자관리

    private LootBoxStore.Box box(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        var b = store.find(CmdKit.text(ctx, "이름"));
        if (b == null) throw ChacaApi.error("그 이름의 상자가 없어요 (/상자관리 목록)");
        return b;
    }

    private void commands() {
        var root = ChacaApi.상자관리;
        ChacaApi.branch(plugin, root, CmdKit.literal("생성").then(CmdKit.word("이름", List::of).executes(CmdKit.run(ctx -> {
            var p = CmdKit.self(ctx);
            var held = p.getInventory().getItemInMainHand();
            if (held.getType().isAir()) throw ChacaApi.error("열쇠로 쓸 아이템을 손에 들고 해 주세요");
            var other = boxFor(held);
            if (other != null) throw ChacaApi.error("이 아이템은 이미 '" + other.name() + "' 상자의 열쇠예요");
            var key = store.keyFrom(held);
            var b = CmdKit.user(() -> store.create(CmdKit.text(ctx, "이름").strip(), key));
            persist(p); keyRevision = -1;
            AuditLog.log(p, AuditLog.관리자, "뽑기 상자 생성 · " + b.name(), "상자", b.id(), "열쇠", key.describe());
            CmdKit.ok(p, "'" + b.name() + "' 상자를 만들었어요 · 열쇠: " + key.describe() + " · 보상은 /상자관리 관리 " + b.name());
        }))), "손에 든 아이템을 열쇠로 새 상자 만들기", "생성 API상자");
        ChacaApi.branch(plugin, root, CmdKit.literal("관리").executes(CmdKit.run(ctx -> adminOpen(CmdKit.self(ctx), "")))
                .then(CmdKit.word("이름", store::names).executes(CmdKit.run(ctx -> adminOpen(CmdKit.self(ctx), box(ctx).id())))),
                "상자 내용·확률 관리 창 열기", "관리");
        ChacaApi.branch(plugin, root, CmdKit.literal("목록").executes(CmdKit.run(ctx -> {
            var out = new ArrayList<String>();
            out.add("── 뽑기 상자 " + store.boxes().size() + "개 ──");
            for (var b : store.boxes()) out.add(" " + b.name() + " (" + b.id() + ") · 보상 " + b.rewards().size() + "개 · 열쇠 " + b.key().describe()
                    + (b.enabled() ? "" : " · 꺼짐") + (b.valid() ? "" : " · 오류: " + String.join(" / ", b.problems())));
            if (store.boxes().isEmpty()) out.add(" (없음) 손에 열쇠 아이템을 들고 /상자관리 생성 <이름>");
            CmdKit.lines(CmdKit.sender(ctx), out);
        })), "상자 목록", "목록");
        ChacaApi.branch(plugin, root, CmdKit.literal("삭제").then(CmdKit.confirm(CmdKit.word("이름", store::names), ctx -> {
            var b = box(ctx);
            store.delete(b); persist(CmdKit.sender(ctx)); keyRevision = -1;
            AuditLog.log(CmdKit.sender(ctx) instanceof Player p ? p.getUniqueId() : null, AuditLog.관리자, "뽑기 상자 삭제 · " + b.name(), "상자", b.id());
            CmdKit.ok(CmdKit.sender(ctx), "'" + b.name() + "' 상자를 지웠어요 (이미 나눠 준 열쇠는 이제 아무 일도 하지 않아요)");
        })), "상자 지우기 (끝에 확인)", "삭제 API상자 확인");
        ChacaApi.branch(plugin, root, CmdKit.literal("확률").then(CmdKit.word("이름", store::names).executes(CmdKit.run(ctx -> {
            var b = box(ctx);
            long total = b.totalWeight();
            var out = new ArrayList<String>();
            out.add("── " + b.name() + " 확률 (가중치 합 " + total + ") ──");
            long[] byGrade = new long[4];
            for (var r : b.rewards()) if (r.weight() > 0) byGrade[r.grade()] += r.weight();
            out.add(" 등급: 일반 " + LootBoxRules.percent(byGrade[0], total) + " · 고급 " + LootBoxRules.percent(byGrade[1], total)
                    + " · 유일 " + LootBoxRules.percent(byGrade[2], total) + " · 전설 " + LootBoxRules.percent(byGrade[3], total));
            int n = 0;
            for (var r : b.rewards()) out.add(" " + (++n) + ". [" + LootBoxRules.gradeName(r.grade()) + "] " + r.spec() + " ×" + r.amount() + " · " + LootBoxRules.percent(r.weight(), total) + " (가중치 " + r.weight() + ")");
            if (b.rewards().isEmpty()) out.add(" (보상 없음)");
            CmdKit.lines(CmdKit.sender(ctx), out);
        }))), "상자 보상 확률 표 (채팅)", "확률 API상자");
        var test = CmdKit.word("이름", store::names).executes(CmdKit.run(ctx -> test(ctx, 1, false)));
        test.then(CmdKit.argument("개수", IntegerArgumentType.integer(1, 10)).executes(CmdKit.run(ctx -> test(ctx, IntegerArgumentType.getInteger(ctx, "개수"), false)))
                .then(CmdKit.literal("지급").executes(CmdKit.run(ctx -> test(ctx, IntegerArgumentType.getInteger(ctx, "개수"), true)))));
        ChacaApi.branch(plugin, root, CmdKit.literal("테스트").then(test),
                "관리자 미리보기: 열쇠 소모 없이 연출만 (끝에 '지급'을 붙이면 실제 지급)", "테스트 API상자 10");
        ChacaApi.branch(plugin, root, CmdKit.literal("새로고침").executes(CmdKit.run(ctx -> reload(CmdKit.sender(ctx)))), "loot-boxes.yml 다시 읽기", "새로고침");
        ChacaApi.branch(plugin, root, CmdKit.literal("대기").executes(CmdKit.run(ctx -> {
            var out = new ArrayList<String>();
            out.add("── 우편·정리가 남은 개봉 " + pending.size() + "건 ──");
            for (var o : pending.values()) out.add(" " + o.operation() + " · " + ChacaApi.name(o.player()) + " · " + o.title() + " " + o.count() + "개 · 우편 첨부 " + o.mail().size()
                    + (o.furniture().isEmpty() ? "" : " · 가구 " + o.furniture().size() + (granted.contains(o.operation()) ? "(지급됨)" : granting.contains(o.operation()) ? "(주는 중)" : "(재시도 " + grantRetries.getOrDefault(o.operation(), 0) + "회)"))
                    + (mailing.contains(o.operation()) ? " · 보내는 중" : " · 재시도 " + retries.getOrDefault(o.operation(), 0) + "회"));
            if (!broken.isEmpty()) out.add(" 읽을 수 없는 영수증 파일 " + broken.size() + "개 (lootbox-receipts 폴더, 콘솔 LOOTBOX 로그 확인)");
            out.add(" 유저가 이 서버에 접속하면 자동으로 정리돼요 (영수증 있음 = 남은 우편만 다시, 없음 = 열지 않은 것으로)");
            CmdKit.lines(CmdKit.sender(ctx), out);
        })), "끝나지 않은 개봉(우편 대기) 보기", "대기");
    }

    private void test(CommandContext<CommandSourceStack> ctx, int count, boolean grant) throws CommandSyntaxException {
        var p = CmdKit.self(ctx);
        if (count != 1 && count != 10) throw ChacaApi.error("개수는 1 또는 10이에요");
        var b = box(ctx);
        if (!b.valid()) throw ChacaApi.error("설정 오류가 있는 상자예요: " + String.join(" / ", b.problems()));
        if (grant) AuditLog.log(p, AuditLog.관리자, "뽑기 상자 테스트 지급 · " + b.name() + " " + count + "개", "상자", b.id());
        open(p, b, count, false, grant, p);
    }

    // ------------------------------------------------------------------ 운영자 관리 창

    private void adminOpen(Player p, String box) throws CommandSyntaxException {
        if (!p.getListeningPluginChannels().contains(LootBoxProtocol.ADMIN_RESPONSE)) throw ChacaApi.error("상자 관리 창이 있는 차카데미 모드가 필요해요 (명령: 목록·확률·loot-boxes.yml)");
        var s = new Admin(p, ThreadLocalRandom.current().nextLong(1, Long.MAX_VALUE), 0, System.currentTimeMillis() + ADMIN_SESSION_MS);
        admins.put(p.getUniqueId(), s);
        send(p, s, 0, box, 0, "");
    }

    private boolean adminAllowed(Player p) { return !closing && p.isOnline() && Bukkit.getPlayer(p.getUniqueId()) == p && (p.isOp() || p.hasPermission("magiccodex.lootbox.admin")); }

    private void adminRequest(Player p, byte[] bytes) {
        if (!adminAllowed(p)) return;
        LootBoxProtocol.Request r;
        try { r = LootBoxProtocol.request(bytes); } catch (IllegalArgumentException e) { return; }
        var s = admins.get(p.getUniqueId());
        if (s == null || s.player() != p || s.token() != r.session() || r.sequence() <= s.sequence() || System.currentTimeMillis() > s.expires()) return;
        s = new Admin(p, s.token(), r.sequence(), System.currentTimeMillis() + ADMIN_SESSION_MS);
        admins.put(p.getUniqueId(), s);
        if (r.action() == LootBoxProtocol.CLOSE) { admins.remove(p.getUniqueId()); return; }
        String message = "";
        String boxId = r.box();
        if (r.action() != LootBoxProtocol.LIST) {
            var b = store.get(r.box());
            try {
                if (b == null) throw new IllegalArgumentException("그 상자가 없어요. 새로고침해 주세요");
                if (r.revision() != store.revision()) throw new IllegalArgumentException("다른 곳에서 상자가 바뀌었어요. 새로고침 후 다시 해 주세요");
                switch (r.action()) {
                    case LootBoxProtocol.ADD -> {
                        var picked = picked(p, r);
                        if (r.amount() > picked.getMaxStackSize()) throw new IllegalArgumentException("이 아이템은 한 칸에 " + picked.getMaxStackSize() + "개까지예요");
                        store.addReward(b, store.rewardFrom(picked, r.amount(), r.grade(), r.weight()));
                        message = "보상을 추가했어요";
                    }
                    case LootBoxProtocol.EDIT -> {
                        if (r.index() >= b.rewards().size()) throw new IllegalArgumentException("그 보상이 없어요. 새로고침해 주세요");
                        var old = b.rewards().get(r.index());
                        if (old.furniture() != null && r.amount() != 1) throw new IllegalArgumentException("가구 보상은 1개만 돼요");
                        var sample = store.create(new LootBoxStore.Reward(old.itemsadder(), old.vanilla(), old.item(), 1, old.grade(), old.weight(), old.label(), old.furniture(), old.icon()));
                        if (sample != null && r.amount() > sample.getMaxStackSize()) throw new IllegalArgumentException("이 아이템은 한 칸에 " + sample.getMaxStackSize() + "개까지예요");
                        store.editReward(b, r.index(), r.amount(), r.grade(), r.weight());
                        message = (r.index() + 1) + "번 보상을 저장했어요";
                    }
                    case LootBoxProtocol.DELETE -> { store.deleteReward(b, r.index()); message = (r.index() + 1) + "번 보상을 지웠어요"; }
                    case LootBoxProtocol.RENAME -> { store.rename(b, r.text().strip()); message = "이름을 바꿨어요"; }
                    case LootBoxProtocol.REKEY -> {
                        var picked = picked(p, r);
                        var other = boxFor(picked);
                        if (other != null && !other.id().equals(b.id())) throw new IllegalArgumentException("그 아이템은 이미 '" + other.name() + "' 상자의 열쇠예요");
                        var key = store.keyFrom(picked);
                        store.rekey(b, key); keyRevision = -1;
                        message = "열쇠를 다시 지정했어요 · " + key.describe();
                    }
                    case LootBoxProtocol.DELETE_BOX -> { store.delete(b); keyRevision = -1; boxId = ""; message = "'" + b.name() + "' 상자를 지웠어요"; }
                    default -> throw new IllegalArgumentException("알 수 없는 동작");
                }
                persist(p);
                AuditLog.log(p, AuditLog.관리자, "뽑기 상자 관리 · " + b.name() + " · " + message, "상자", b.id(), "동작", r.action());
            } catch (IllegalArgumentException e) { message = e.getMessage() == null ? "요청을 처리하지 못했어요" : e.getMessage(); }
        }
        send(p, s, r.sequence(), boxId, r.action() == LootBoxProtocol.LIST ? r.offset() : 0, message);
    }

    /** 고른 내 인벤토리 칸(서버의 실제 칸). 고른 뒤 그 칸이 바뀌었으면 거절. slot -1 = 주 손. (상점 관리 창과 같은 방식) */
    private static ItemStack picked(Player p, LootBoxProtocol.Request r) {
        if (r.slot() < 0) { var held = p.getInventory().getItemInMainHand().clone(); if (held.getType().isAir()) throw new IllegalArgumentException("아이템을 주 손에 들어 주세요"); return held; }
        var item = p.getInventory().getItem(r.slot());
        if (item == null || item.getType().isAir()) throw new IllegalArgumentException("고른 칸이 비어 있어요. 다시 골라 주세요");
        if (!item.getType().getKey().toString().equals(r.slotItem())) throw new IllegalArgumentException("고른 칸의 아이템이 바뀌었어요. 다시 골라 주세요");
        return item.clone();
    }

    private byte[] preview(ItemStack it) {
        if (it == null) return new byte[0];
        try {
            byte[] b = plugin.coreAppearance(it.clone()).serializeAsBytes();
            if (b.length > 3072) b = new ItemStack(it.getType(), it.getAmount()).serializeAsBytes();
            return b.length > 3072 ? new byte[0] : b;
        } catch (RuntimeException e) { return new byte[0]; }
    }

    private void send(Player p, Admin s, long sequence, String boxId, int offset, String message) {
        var list = new ArrayList<LootBoxProtocol.Box>();
        for (var b : store.boxes()) { if (list.size() >= 200) break; list.add(new LootBoxProtocol.Box(b.id(), b.name(), b.rewards().size(), b.valid())); }
        var b = boxId.isEmpty() ? store.boxes().stream().findFirst().orElse(null) : store.get(boxId);
        if (b == null && !store.boxes().isEmpty()) b = store.boxes().iterator().next();
        var rewards = new ArrayList<LootBoxProtocol.Reward>();
        long[] grades = new long[4];
        int next = -1;
        String title = "", keyText = "";
        byte[] keyPreview = new byte[0];
        long total = 0;
        if (b != null) {
            title = b.name(); keyText = b.key().describe();
            keyPreview = preview(store.keySample(b.key()));
            total = b.totalWeight();
            for (var r : b.rewards()) if (r.weight() > 0) grades[r.grade()] += r.weight();
            if (offset >= b.rewards().size()) offset = 0;
            int size = 0, i = offset;
            for (; i < b.rewards().size() && rewards.size() < LootBoxProtocol.PAGE; i++) {
                var r = b.rewards().get(i);
                byte[] pv = preview(store.create(r));
                if (size + pv.length + 400 > 40000 && !rewards.isEmpty()) break;
                size += pv.length + 400;
                String spec = r.spec();
                rewards.add(new LootBoxProtocol.Reward(i, r.grade(), r.amount(), r.weight(), spec.length() > 160 ? spec.substring(0, 160) : spec, pv));
            }
            next = i < b.rewards().size() ? i : -1;
            if (message.isEmpty() && !b.valid()) message = "설정 오류: " + String.join(" / ", b.problems());
        } else if (message.isEmpty()) message = "상자가 없어요. 열쇠 아이템을 손에 들고 /상자관리 생성 <이름>";
        if (message.length() > 240) message = message.substring(0, 237) + "…";
        try {
            p.sendPluginMessage(plugin, LootBoxProtocol.ADMIN_RESPONSE, LootBoxProtocol.encode(new LootBoxProtocol.Response(sequence, s.token(), b == null ? "" : b.id(), store.revision(),
                    message, List.copyOf(list), title, keyText.length() > 160 ? keyText.substring(0, 160) : keyText, keyPreview, total, grades, List.copyOf(rewards), offset, next)));
        } catch (IllegalArgumentException e) { plugin.getLogger().warning("뽑기 상자 관리 창 패킷 실패: " + e.getMessage()); }
    }

    @Override public void close() {
        closing = true;
        admins.clear();
        io.shutdown();
        try { if (!io.awaitTermination(10, TimeUnit.SECONDS)) plugin.getLogger().warning("LOOTBOX IO 가 끝나지 않음; 영수증은 유지되어 다음 시작 때 정리"); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }
}
