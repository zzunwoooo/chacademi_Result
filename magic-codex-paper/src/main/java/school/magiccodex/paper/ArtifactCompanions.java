package school.magiccodex.paper;

import io.papermc.paper.event.player.AsyncChatEvent;
import java.io.InputStreamReader;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.function.Consumer;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.server.PluginEnableEvent;
import org.bukkit.plugin.Plugin;
import school.magiccodex.protocol.ArtifactFxProtocol;

/**
 * 아티팩트 동행 연출 + 따라 쓰기 (그룹 D, 2026-10-26).
 * <ul>
 *   <li>화염 정령의 등불 (fire_spirit_lantern): 키세팅 마법이 전부 화염이면 정령 표시. 내 화염 마법이 피해를 주면 0.25초 뒤
 *       정령이 같은 대상에게 불덩이 → 내 피해의 20% 추가 피해.</li>
 *   <li>거울 쌍둥이 (mirror_twin): 분신 표시 (클라가 내 모습을 0.5초 늦게 반투명으로 그림). 내 마법 피해 0.5초 뒤 같은 대상에게 30%.</li>
 *   <li>수다 앵무새 (chatty_parrot): 어깨 앵무새 표시 (클라 렌더) + 내 채팅을 {@value ArtifactFxProtocol#MAX_BUBBLE}자로 잘라 말풍선.</li>
 * </ul>
 * 추가 피해는 MagicMythic echoDamage (표식·원소 반응·패시브·MagicDamageEvent 없음 → 재귀 없음). 서버는 모양을 그리지 않고
 * "누가 장착했는지"(바뀔 때만)와 짧은 사건 패킷만 보낸다. 몸·정령·앵무새·말풍선은 각 클라(MagicCodex 모드)가 그린다.
 */
final class ArtifactCompanions implements Listener {
    static final String SPIRIT_ID = "fire_spirit_lantern", TWIN_ID = "mirror_twin", PARROT_ID = "chatty_parrot";
    static final double SPIRIT_RATIO = 0.20, TWIN_RATIO = 0.30;
    static final long SPIRIT_DELAY = 5, TWIN_DELAY = 10;       // ticks (불덩이 비행 0.25초 / 거울 0.5초)
    static final int ECHO_PER_SECOND = 6;                       // 장판·다단 히트 보호: 플레이어·종류별 초당 최대
    static final double VIEW = 48;
    private final ArtifactEffects fx;
    private final MagicCodexBridge plugin;
    private final Map<UUID, Integer> flags = new HashMap<>();
    private final Map<String, long[]> budget = new HashMap<>();
    private final Map<UUID, Long> lastBubble = new HashMap<>();
    private final Set<String> fireSpells;
    private Plugin mythic; private Listener damageListener;
    private final Map<String, Method> methods = new HashMap<>(); private final Set<String> missing = new HashSet<>();

    ArtifactCompanions(ArtifactEffects fx) {
        this.fx = fx; this.plugin = fx.plugin;
        fireSpells = loadElement("fire");
        Bukkit.getMessenger().registerOutgoingPluginChannel(plugin, ArtifactFxProtocol.CHANNEL);
        Bukkit.getPluginManager().registerEvents(this, plugin);
        Bukkit.getScheduler().runTaskTimer(plugin, this::second, 25, 20);
        Bukkit.getScheduler().runTask(plugin, this::connect);
    }

    /** spell-elements.yml (jar 안, spells: 마법ID → 원소)에서 이 원소의 마법 ID 목록. */
    private Set<String> loadElement(String element) {
        try (var in = plugin.getResource("spell-elements.yml")) {
            if (in == null) { plugin.getLogger().warning("spell-elements.yml 없음: 화염 정령의 등불 조건을 확인할 수 없어요"); return Set.of(); }
            var y = YamlConfiguration.loadConfiguration(new InputStreamReader(in, StandardCharsets.UTF_8));
            var spells = y.getConfigurationSection("spells");   // 형식: spells: { 마법ID: 원소 } (무지개 프리즘과 같은 표)
            if (spells == null) return Set.of();
            var out = new java.util.HashSet<String>();
            for (String id : spells.getKeys(false)) if (element.equals(spells.getString(id))) out.add(id);
            return Set.copyOf(out);
        } catch (java.io.IOException e) { return Set.of(); }
    }

    // ------------------------------------------------------------------ MagicMythic (리플렉션, 없는 메서드는 한 번만 경고)

    private void connect() {
        var mm = Bukkit.getPluginManager().getPlugin("ChAcademiaMagicMythic");
        if (mm == null || !mm.isEnabled() || mm == mythic) return;
        mythic = mm; methods.clear(); missing.clear();
        if (damageListener != null) HandlerList.unregisterAll(damageListener);
        damageListener = new Listener() {};
        try {
            @SuppressWarnings("unchecked")
            Class<? extends Event> type = (Class<? extends Event>) Class.forName("school.chacademia.magicmythic.MagicDamageEvent", true, mm.getClass().getClassLoader());
            Consumer<Event> handler = this::damaged;
            // MONITOR: 다른 아티팩트 배율까지 적용된 최종 피해를 본다
            Bukkit.getPluginManager().registerEvent(type, damageListener, EventPriority.MONITOR, (l, ev) -> {
                if (!type.isInstance(ev)) return;
                try { handler.accept(ev); } catch (RuntimeException err) { plugin.getLogger().warning("동행 아티팩트 오류: " + err); }
            }, plugin, false);
        } catch (ClassNotFoundException | LinkageError e) { plugin.getLogger().warning("동행 아티팩트: MagicDamageEvent 연결 실패 " + e); }
    }
    @EventHandler public void pluginEnabled(PluginEnableEvent e) { if (e.getPlugin().getName().equals("ChAcademiaMagicMythic")) connect(); }

    private Object api(String name, Class<?>[] types, Object... args) {
        if (mythic == null || !mythic.isEnabled() || missing.contains(name)) return null;
        try {
            Method m = methods.get(name);
            if (m == null) { m = mythic.getClass().getMethod(name, types); methods.put(name, m); }
            return m.invoke(mythic, args);
        } catch (NoSuchMethodException e) { missing.add(name); plugin.getLogger().warning("MagicMythic " + name + " 없음 (MagicMythic 업데이트 필요)"); return null; }
        catch (ReflectiveOperationException | RuntimeException e) { return null; }
    }
    private static Object get(Object o, String name) {
        try { return o.getClass().getMethod(name).invoke(o); } catch (ReflectiveOperationException e) { throw new IllegalStateException(name, e); }
    }

    // ------------------------------------------------------------------ 표시 상태

    int flags(Player p) { return flags.getOrDefault(p.getUniqueId(), 0); }

    private int computeFlags(Player p) {
        int f = 0;
        if (fx.has(p, SPIRIT_ID) && allFire(p)) f |= ArtifactFxProtocol.SPIRIT;
        if (fx.has(p, TWIN_ID)) f |= ArtifactFxProtocol.TWIN;
        if (fx.has(p, PARROT_ID)) f |= ArtifactFxProtocol.PARROT;
        return f;
    }

    /** 키세팅에 넣은 마법이 하나 이상이고 전부 화염 (빈 칸은 세지 않음). */
    boolean allFire(Player p) {
        if (!(api("slotSpells", new Class<?>[]{Player.class}, p) instanceof List<?> list) || list.isEmpty()) return false;
        for (Object id : list) if (!(id instanceof String s) || !fireSpells.contains(s)) return false;
        return true;
    }

    private void second() {
        for (Player p : Bukkit.getOnlinePlayers()) {
            int now = computeFlags(p), was = flags(p);
            if (now == was) continue;
            if (now == 0) flags.remove(p.getUniqueId()); else flags.put(p.getUniqueId(), now);
            if ((now & ArtifactFxProtocol.SPIRIT) != 0 && (was & ArtifactFxProtocol.SPIRIT) == 0)
                ArtifactEffects.notice(p, "fire_spirit", "화염 정령의 등불", "키세팅이 전부 화염이라 화염 정령이 함께해요");
            broadcastState(p, now);
        }
    }

    private void broadcastState(Player p, int f) {
        byte[] bytes = ArtifactFxProtocol.encode(new ArtifactFxProtocol.States(false, Map.of(p.getUniqueId(), f)));
        for (Player o : p.getWorld().getPlayers()) send(o, bytes);
    }

    /** 받는 사람이 이 세계에 들어왔을 때: 같은 세계의 전체 상태. */
    private void fullState(Player viewer) {
        var map = new HashMap<UUID, Integer>();
        for (Player o : viewer.getWorld().getPlayers()) { int f = flags(o); if (f != 0 && map.size() < ArtifactFxProtocol.MAX_STATES) map.put(o.getUniqueId(), f); }
        send(viewer, ArtifactFxProtocol.encode(new ArtifactFxProtocol.States(true, map)));
    }

    private void send(Player to, byte[] bytes) {
        if (to.isOnline() && to.getListeningPluginChannels().contains(ArtifactFxProtocol.CHANNEL)) to.sendPluginMessage(plugin, ArtifactFxProtocol.CHANNEL, bytes);
    }
    private void sendNear(org.bukkit.Location at, byte[] bytes, double radius) {
        if (at.getWorld() == null) return;
        for (Player o : at.getWorld().getPlayers()) if (o.getLocation().distanceSquared(at) <= radius * radius) send(o, bytes);
    }

    @EventHandler public void join(PlayerJoinEvent e) {
        Player p = e.getPlayer();
        Bukkit.getScheduler().runTaskLater(plugin, () -> { if (p.isOnline()) fullState(p); }, 40);
    }
    @EventHandler public void world(PlayerChangedWorldEvent e) {
        Player p = e.getPlayer();
        Bukkit.getScheduler().runTaskLater(plugin, () -> { if (!p.isOnline()) return; fullState(p); int f = flags(p); if (f != 0) broadcastState(p, f); }, 10);
    }
    @EventHandler public void quit(PlayerQuitEvent e) {
        UUID id = e.getPlayer().getUniqueId();
        lastBubble.remove(id); budget.keySet().removeIf(k -> k.startsWith(id.toString()));
        if (flags.remove(id) != null) {
            byte[] bytes = ArtifactFxProtocol.encode(new ArtifactFxProtocol.States(false, Map.of(id, 0)));
            for (Player o : e.getPlayer().getWorld().getPlayers()) if (!o.equals(e.getPlayer())) send(o, bytes);
        }
    }

    // ------------------------------------------------------------------ 따라 쓰기

    private boolean spend(Player p, String kind) {
        long now = System.currentTimeMillis();
        long[] b = budget.computeIfAbsent(p.getUniqueId() + kind, k -> new long[]{now, 0});
        if (now - b[0] >= 1000) { b[0] = now; b[1] = 0; }
        if (b[1] >= ECHO_PER_SECOND) return false;
        b[1]++; return true;
    }

    private void damaged(Event e) {
        if (!(get(e, "getCaster") instanceof Player p) || !p.isOnline()) return;
        int f = flags(p);
        if ((f & (ArtifactFxProtocol.SPIRIT | ArtifactFxProtocol.TWIN)) == 0) return;
        String element = (String) get(e, "getElement"), reaction = (String) get(e, "getReaction");
        if (element == null || element.isEmpty() || (reaction != null && !reaction.isEmpty())) return;   // 마법 본 피해만 (반응·표식 피해 제외)
        if (!(get(e, "getTarget") instanceof LivingEntity t)) return;
        double damage = (double) get(e, "getDamage");
        if (!(damage > 0)) return;
        if ((f & ArtifactFxProtocol.SPIRIT) != 0 && element.equals("fire") && spend(p, ":spirit")) {
            sendNear(p.getLocation(), ArtifactFxProtocol.encode(new ArtifactFxProtocol.Shot(ArtifactFxProtocol.SPIRIT_SHOT, p.getUniqueId(), t.getEntityId())), VIEW);
            echoLater(p, t, damage * SPIRIT_RATIO, SPIRIT_DELAY, false);
        }
        if ((f & ArtifactFxProtocol.TWIN) != 0 && spend(p, ":twin")) echoLater(p, t, damage * TWIN_RATIO, TWIN_DELAY, true);
    }

    private void echoLater(Player p, LivingEntity t, double amount, long delay, boolean twin) {
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (!p.isOnline() || p.isDead() || !t.isValid() || t.isDead() || t.getWorld() != p.getWorld()) return;
            if (t.getLocation().distanceSquared(p.getLocation()) > 64 * 64) return;
            if (twin) sendNear(t.getLocation(), ArtifactFxProtocol.encode(new ArtifactFxProtocol.Shot(ArtifactFxProtocol.TWIN_ECHO, p.getUniqueId(), t.getEntityId())), VIEW);
            api("echoDamage", new Class<?>[]{Player.class, LivingEntity.class, double.class}, p, t, amount);
        }, delay);
    }

    // ------------------------------------------------------------------ 수다 앵무새

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void chat(AsyncChatEvent e) {
        Player p = e.getPlayer();
        String text = ArtifactFxProtocol.bubbleText(PlainTextComponentSerializer.plainText().serialize(e.message()));
        if (text.isEmpty()) return;
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (!p.isOnline() || (flags(p) & ArtifactFxProtocol.PARROT) == 0) return;
            long now = System.currentTimeMillis();
            if (now - lastBubble.getOrDefault(p.getUniqueId(), 0L) < 1500) return;
            lastBubble.put(p.getUniqueId(), now);
            sendNear(p.getLocation(), ArtifactFxProtocol.encode(new ArtifactFxProtocol.Bubble(p.getUniqueId(), text)), 32);
        });
    }

    void close() {
        if (damageListener != null) HandlerList.unregisterAll(damageListener);
        HandlerList.unregisterAll(this);
        flags.clear(); budget.clear(); lastBubble.clear();
    }
}
