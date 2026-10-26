package school.magiccodex.paper;

import java.io.File;
import java.util.*;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.messaging.PluginMessageListener;
import school.magiccodex.protocol.MegaphoneProtocol;

/**
 * 확성기 (오픈 전 메모 #102, 오너 2026-10-26).
 *
 * <p>확성기(ItemsAdder item:megaphone, megaphone.yml item)를 손에 들고 우클릭 → 입력 창(클라이언트 MegaphoneScreen) →
 * 보내기 → 검사(쿨다운·글자 수·금칙어·확성기가 아직 그 손에 있는지) → 1개 소모 → 모든 서버 접속자 화면 위쪽에 공지
 * (보낸 사람 한글 닉네임 + 문장, 반대 손 아이템이 있으면 그 아이콘 + Ctrl 커서로 툴팁). 취소(Esc·X)·창이 닫힘 = 소모 없음.
 *
 * <p>같이 보여 줄 아이템 = 확성기를 들지 않은 반대 손 (주 손 확성기 → 왼손, 왼손 확성기 → 주 손). 비었으면 공지만.
 * 아이템은 보낼 때 서버가 손에서 직접 읽어 직렬화한다 (클라이언트는 아이템을 보내지 않음). 숨은 플러그인 데이터(PDC)는 빼고,
 * 크기 상한(MegaphoneProtocol.MAX_ITEM_BYTES)을 넘으면 설명 줄을 빼고, 그래도 크면 재료 모양만 (연금술 미리보기와 같은 단계).
 *
 * <p>서버 간: Velocity 의 BungeeCord 호환 채널 Forward ALL (#95 /공지 와 같은 방식, 하위 채널 ChacaMegaphone).
 * 프록시 쪽 플러그인 변경 없음. 받는 서버에 접속자가 있어야 도착한다.
 *
 * <p>부담: 매 틱 일 없음. 우클릭·보내기 때만 일한다. 아이템 직렬화는 확성기 한 번에 한 번.
 */
final class MegaphoneBridge implements Listener, PluginMessageListener {
    static final String FILE = "megaphone.yml";
    static final String BUNGEE = "BungeeCord";

    /** 입력 창이 열린 확성기 한 번: 어느 손·어느 칸의 확성기인지 (보낼 때 그대로 있는지 확인). test = /테스트 확성기 창 (소모·전달 없음, 나에게만). */
    private record Pending(long token, EquipmentSlot hand, int slot, long expiresAt, boolean test) {}

    private final MagicCodexBridge plugin;
    private final ItemsAdderItems itemsAdder;
    private final File file;
    private MegaphoneRules.Settings settings = MegaphoneRules.Settings.DEFAULT;
    /** 금칙어: megaphone.yml banned-words + (켜져 있으면) ChacaNPC filter.yml banned-words. 정규화된 글자. */
    private List<String> banned = List.of();
    private final String origin = UUID.randomUUID().toString();
    private final Map<UUID, Pending> pending = new HashMap<>();
    private final Map<UUID, Long> lastUse = new HashMap<>();
    private final Map<UUID, Long> clickGate = new HashMap<>();
    private final LinkedHashSet<String> seen = new LinkedHashSet<>();

    MegaphoneBridge(MagicCodexBridge plugin) throws Exception {
        this.plugin = plugin;
        this.itemsAdder = new ItemsAdderItems(plugin.getLogger());
        this.file = new File(plugin.getDataFolder(), FILE);
        if (!file.exists()) plugin.saveResource(FILE, false);
        reload();
        Bukkit.getPluginManager().registerEvents(this, plugin);
        var messenger = Bukkit.getMessenger();
        messenger.registerIncomingPluginChannel(plugin, MegaphoneProtocol.REQUEST, this);
        messenger.registerOutgoingPluginChannel(plugin, MegaphoneProtocol.RESPONSE);
        messenger.registerOutgoingPluginChannel(plugin, BUNGEE);
        messenger.registerIncomingPluginChannel(plugin, BUNGEE, this);
        ChacaApi.reload(plugin, "확성기", FILE + " (확성기 아이템·쿨다운·글자 수·표시 시간·금칙어)", this::reload);
        var test = CmdKit.literal("확성기");
        test.then(CmdKit.literal("공지").executes(CmdKit.run(ctx -> testNotice(CmdKit.self(ctx)))));
        test.then(CmdKit.literal("창").executes(CmdKit.run(ctx -> testWindow(CmdKit.self(ctx)))));
        ChacaApi.branch(plugin, ChacaApi.테스트, test, "확성기 공지 미리보기(나에게만, 왼손 아이템)·입력 창 열기(소모 없음)", "확성기 공지");
    }

    void reload() throws Exception {
        var y = new YamlConfiguration();
        y.load(file);
        var d = MegaphoneRules.Settings.DEFAULT;
        var next = MegaphoneRules.settings(y.getString("item", d.item()), y.getDouble("cooldown-seconds", d.cooldownMs() / 1000.0),
                y.getInt("max-length", d.maxLength()), y.getDouble("show-seconds", d.showMs() / 1000.0), y.getBoolean("cross-server", d.crossServer()),
                y.getDouble("prompt-timeout-seconds", d.promptTimeoutMs() / 1000.0), y.getBoolean("use-chacanpc-filter", d.chacaNpcFilter()),
                y.isList("banned-words") ? y.getStringList("banned-words") : null);
        var words = new LinkedHashSet<>(next.bannedWords());
        if (next.chacaNpcFilter()) {
            // ChacaNPC 의 금칙어 목록(filter.yml banned-words)을 그대로 같이 쓴다. 파일이 없으면 건너뜀.
            File npc = new File(plugin.getDataFolder().getParentFile(), "ChacaNPC/filter.yml");
            if (npc.isFile()) {
                try {
                    var f = new YamlConfiguration(); f.load(npc);
                    for (String w : f.getStringList("banned-words")) { String n = MegaphoneRules.normalize(w); if (!n.isEmpty()) words.add(n); }
                } catch (Exception e) { plugin.getLogger().warning("확성기: ChacaNPC filter.yml 을 읽지 못했어요 (" + e.getMessage() + ") — megaphone.yml 목록만 씀"); }
            }
        }
        settings = next;
        banned = List.copyOf(words);
    }

    // ------------------------------------------------------------------ 우클릭 → 입력 창

    private boolean isMegaphone(ItemStack it) {
        return it != null && !it.getType().isAir() && settings.item().equalsIgnoreCase(String.valueOf(itemsAdder.idOf(it)));
    }

    private static boolean modded(Player p) { return p.getListeningPluginChannels().contains(MegaphoneProtocol.RESPONSE); }

    @EventHandler(priority = EventPriority.HIGH)
    public void use(PlayerInteractEvent e) {
        if (e.getAction() != Action.RIGHT_CLICK_AIR && e.getAction() != Action.RIGHT_CLICK_BLOCK) return;
        EquipmentSlot hand = e.getHand();
        if (hand != EquipmentSlot.HAND && hand != EquipmentSlot.OFF_HAND) return;
        Player p = e.getPlayer();
        if (!isMegaphone(p.getInventory().getItem(hand))) return;
        e.setCancelled(true);
        long now = System.currentTimeMillis();
        Long last = clickGate.put(p.getUniqueId(), now);
        if (last != null && now - last < 500) return;
        // 주 손·왼손 둘 다 확성기면 우클릭이 두 번 오므로, 주 손 것만 쓴다
        if (hand == EquipmentSlot.OFF_HAND && isMegaphone(p.getInventory().getItemInMainHand())) return;
        if (!modded(p)) { CmdKit.notify(p, "warning", "megaphone", "확성기는 차카데미 런처로 접속했을 때 쓸 수 있어요", "", 1500); return; }
        long left = cooldownLeft(p, now);
        if (left > 0) { CmdKit.notify(p, "warning", "megaphone", "확성기는 " + MegaphoneRules.secondsLeft(left) + "초 뒤에 다시 쓸 수 있어요", "", 1500); return; }
        open(p, new Pending(token(), hand, hand == EquipmentSlot.HAND ? p.getInventory().getHeldItemSlot() : -1, now + settings.promptTimeoutMs(), false));
    }

    private void open(Player p, Pending pd) {
        pending.put(p.getUniqueId(), pd);
        send(p, MegaphoneProtocol.open(new MegaphoneProtocol.Open(pd.token(), settings.maxLength())));
    }

    private static long token() { long t; do { t = java.util.concurrent.ThreadLocalRandom.current().nextLong(); } while (t == 0); return t; }

    private long cooldownLeft(Player p, long now) {
        Long at = lastUse.get(p.getUniqueId());
        return ConvenienceRules.cooldownLeft(at == null ? -1 : at, now, settings.cooldownMs());
    }

    // ------------------------------------------------------------------ 보내기 · 취소

    @Override public void onPluginMessageReceived(String channel, Player p, byte[] data) {
        if (BUNGEE.equals(channel)) { received(data); return; }
        if (!MegaphoneProtocol.REQUEST.equals(channel)) return;
        MegaphoneProtocol.Request r;
        try { r = MegaphoneProtocol.request(data); } catch (IllegalArgumentException ignored) { return; }
        Pending pd = pending.get(p.getUniqueId());
        if (pd == null || pd.token() != r.token()) return;
        pending.remove(p.getUniqueId());
        if (r.action() == MegaphoneProtocol.CANCEL) return;   // 취소: 소모 없음
        submit(p, pd, r.text());
    }

    private void submit(Player p, Pending pd, String raw) {
        long now = System.currentTimeMillis();
        if (now > pd.expiresAt()) { refuse(p, "입력 시간이 지났어요. 확성기를 다시 써 주세요"); return; }
        String text = MegaphoneRules.clean(raw, settings.maxLength());
        if (text.isEmpty()) { refuse(p, "보낼 말을 적어 주세요"); return; }
        if (MegaphoneRules.banned(text, banned)) { refuse(p, "쓸 수 없는 말이 들어 있어요. 확성기는 그대로예요"); return; }
        EquipmentSlot other = pd.hand() == EquipmentSlot.HAND ? EquipmentSlot.OFF_HAND : EquipmentSlot.HAND;
        if (pd.test()) {   // /테스트 확성기 창: 나에게만, 소모·쿨다운·기록·전달 없음
            show(List.of(p), new MegaphoneRules.Wire(origin, UUID.randomUUID().toString(), serverId(), MegaphoneRules.senderName(plugin.names().name(p)), text, settings.showMs(), itemBytes(p.getInventory().getItem(other))));
            return;
        }
        long left = cooldownLeft(p, now);
        if (left > 0) { refuse(p, "확성기는 " + MegaphoneRules.secondsLeft(left) + "초 뒤에 다시 쓸 수 있어요"); return; }
        // 확성기가 아직 그 손(주 손이면 같은 칸)에 있어야 한다
        var inv = p.getInventory();
        if (pd.hand() == EquipmentSlot.HAND && inv.getHeldItemSlot() != pd.slot()) { refuse(p, "확성기를 손에 든 채로 보내 주세요"); return; }
        ItemStack held = inv.getItem(pd.hand());
        if (!isMegaphone(held)) { refuse(p, "확성기를 손에 든 채로 보내 주세요"); return; }
        ItemStack shown = inv.getItem(other);
        byte[] item = itemBytes(shown);
        String who = MegaphoneRules.senderName(plugin.names().name(p));
        var w = new MegaphoneRules.Wire(origin, UUID.randomUUID().toString(), serverId(), who, text, settings.showMs(), item);
        // 공지 모양을 먼저 확인하고 나서 소모 (공지를 못 만들면 확성기는 그대로)
        try { MegaphoneProtocol.show(new MegaphoneProtocol.Show(w.id(), w.sender(), w.text(), w.durationMs(), w.item())); }
        catch (IllegalArgumentException e) { refuse(p, "공지를 만들지 못했어요. 확성기는 그대로예요"); return; }
        // 1개 소모 (그다음 공지)
        held.setAmount(held.getAmount() - 1);
        inv.setItem(pd.hand(), held.getAmount() <= 0 ? null : held);
        lastUse.put(p.getUniqueId(), now);
        seen(w.id());
        show(Bukkit.getOnlinePlayers(), w);
        boolean forwarded = settings.crossServer() && forward(p, w);
        String itemName = shown == null || shown.getType().isAir() ? "" : AlchemyBridge.displayName(shown);
        plugin.getLogger().info("MEGAPHONE " + p.getName() + " (" + who + ")" + (itemName.isEmpty() ? "" : " [" + itemName + "]") + " : " + text + (forwarded ? "" : " (이 서버만)"));
        var detail = new LinkedHashMap<String, String>();
        detail.put("문장", text);
        if (!itemName.isEmpty()) detail.put("아이템", itemName);
        if (!w.server().isEmpty()) detail.put("서버", w.server());
        ChacaApi.audit(p.getUniqueId(), "확성기", who + " · " + text + (itemName.isEmpty() ? "" : " · [" + itemName + "]"), detail);
    }

    private static void refuse(Player p, String why) { CmdKit.notify(p, "warning", "megaphone", why, "", 0); }

    // ------------------------------------------------------------------ 아이템 직렬화

    /** 반대 손 아이템 → 클라이언트 아이콘·툴팁용 바이트. 없으면 빈 배열. */
    static byte[] itemBytes(ItemStack item) {
        if (item == null || item.getType().isAir()) return new byte[0];
        try {
            ItemStack one = item.clone(); one.setAmount(1);
            var meta = one.getItemMeta();
            if (meta != null) {   // 숨은 플러그인 데이터는 보내지 않는다 (툴팁·아이콘에 필요 없음)
                var pdc = meta.getPersistentDataContainer();
                for (var k : List.copyOf(pdc.getKeys())) pdc.remove(k);
                one.setItemMeta(meta);
            }
            byte[] b = one.serializeAsBytes();
            if (b.length <= MegaphoneProtocol.MAX_ITEM_BYTES) return b;
            meta = one.getItemMeta();
            if (meta != null) { meta.lore(null); one.setItemMeta(meta); b = one.serializeAsBytes(); if (b.length <= MegaphoneProtocol.MAX_ITEM_BYTES) return b; }
            b = new ItemStack(item.getType()).serializeAsBytes();
            return b.length <= MegaphoneProtocol.MAX_ITEM_BYTES ? b : new byte[0];
        } catch (RuntimeException e) { return new byte[0]; }
    }

    // ------------------------------------------------------------------ 보여 주기 · 서버 간

    private void show(Collection<? extends Player> to, MegaphoneRules.Wire w) {
        byte[] frame;
        try { frame = MegaphoneProtocol.show(new MegaphoneProtocol.Show(w.id(), w.sender(), w.text(), w.durationMs(), w.item())); }
        catch (IllegalArgumentException e) { plugin.getLogger().warning("확성기 공지를 만들지 못했어요: " + e.getMessage()); return; }
        Component chat = null;
        for (Player p : to) {
            if (!p.isOnline()) continue;
            if (modded(p)) { send(p, frame); continue; }
            // 모드 없는 접속자(관리 도구 등): 채팅 한 줄
            if (chat == null) chat = Component.text("[확성기] ", NamedTextColor.GOLD).append(Component.text(w.sender() + ": ", NamedTextColor.YELLOW)).append(Component.text(w.text(), NamedTextColor.WHITE));
            p.sendMessage(chat);
        }
    }

    private void send(Player p, byte[] bytes) {
        try { p.sendPluginMessage(plugin, MegaphoneProtocol.RESPONSE, bytes); }
        catch (RuntimeException e) { plugin.getLogger().warning("확성기 전송 실패 " + p.getName() + ": " + e); }
    }

    /** 다른 서버로 (실어 보낼 접속자가 없으면 false). */
    private boolean forward(Player carrier, MegaphoneRules.Wire w) {
        Player via = carrier != null && carrier.isOnline() ? carrier : Bukkit.getOnlinePlayers().stream().findFirst().orElse(null);
        if (via == null) return false;
        try { via.sendPluginMessage(plugin, BUNGEE, MegaphoneRules.forwardAll(MegaphoneRules.encode(w))); return true; }
        catch (RuntimeException e) { plugin.getLogger().warning("확성기 서버 간 전달 실패: " + e); return false; }
    }

    private void received(byte[] message) {
        var w = MegaphoneRules.decode(MegaphoneRules.unwrapForward(message));
        if (w == null || origin.equals(w.origin()) || !seen(w.id())) return;
        show(Bukkit.getOnlinePlayers(), w);
    }

    /** 처음 보는 id 면 true (최근 256개 기억). */
    private boolean seen(String id) {
        if (!seen.add(id)) return false;
        if (seen.size() > 256) { var it = seen.iterator(); it.next(); it.remove(); }
        return true;
    }

    private String serverId() {
        var travel = plugin.travel();
        try { return travel == null || travel.config() == null ? "" : travel.config().self(); }
        catch (RuntimeException e) { return ""; }
    }

    // ------------------------------------------------------------------ 테스트

    private void testNotice(Player p) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        if (!modded(p)) throw ChacaApi.error("차카데미 런처로 접속해야 볼 수 있어요");
        show(List.of(p), new MegaphoneRules.Wire(origin, UUID.randomUUID().toString(), serverId(), MegaphoneRules.senderName(plugin.names().name(p)),
                "확성기 공지 미리보기예요 · 왼손에 든 아이템이 위에 보여요", settings.showMs(), itemBytes(p.getInventory().getItemInOffHand())));
        CmdKit.ok(p, "확성기 공지를 나에게만 띄웠어요");
    }

    private void testWindow(Player p) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        if (!modded(p)) throw ChacaApi.error("차카데미 런처로 접속해야 열 수 있어요");
        open(p, new Pending(token(), EquipmentSlot.HAND, p.getInventory().getHeldItemSlot(), System.currentTimeMillis() + settings.promptTimeoutMs(), true));
    }

    @EventHandler public void quit(PlayerQuitEvent e) {
        UUID id = e.getPlayer().getUniqueId();
        pending.remove(id);
        clickGate.remove(id);
        if (lastUse.size() > 512) { long now = System.currentTimeMillis(); lastUse.values().removeIf(at -> now - at >= settings.cooldownMs()); }
    }
}
