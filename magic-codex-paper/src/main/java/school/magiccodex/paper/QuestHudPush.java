package school.magiccodex.paper;

// [기능:subquest-hud-20261031 시작] 진행 중인 의뢰를 화면 HUD(스토리 모드 퀘스트창 아래)로 보낸다. 파일 전체가 새 기능.
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.scheduler.BukkitTask;
import static school.magiccodex.paper.QuestDefinition.*;

/**
 * 1초마다 접속자의 진행 중 의뢰(오늘 받은 active)를 모아, 바뀐 사람에게만 magiccodex:quest_hud 로 보낸다.
 * 제출 목표는 가방 안 개수를 센다(가방이 바뀌어도 따라감). 채널을 등록한 클라(스토리 모드)에만 보낸다.
 * 바이트 형식 = 마인크래프트 FriendlyByteBuf (VarInt·VarInt 길이 UTF-8·1바이트 참거짓). 클라 QuestPackets.SubquestsS2C 와 같아야 한다.
 * <pre>VarInt 형식(1) · VarInt 의뢰 수 · [UTF 제목 · UTF 등급 · bool 메인 · bool 완료 · VarInt 목표 수 · [UTF 이름 · VarInt 현재 · VarInt 필요]]</pre>
 */
final class QuestHudPush {
    static final String CHANNEL = "magiccodex:quest_hud";
    static final int FORMAT = 1, MAX_QUESTS = 4;
    private final MagicCodexBridge plugin;
    private final QuestBridge quests;
    private final Map<UUID, byte[]> sent = new HashMap<>();
    /** 제출 목표 아이템 (stack:base64 를 매초 다시 풀지 않게). */
    private final Map<String, ItemStack> submitItems = new HashMap<>();
    private final BukkitTask task;

    QuestHudPush(MagicCodexBridge plugin, QuestBridge quests) {
        this.plugin = plugin;
        this.quests = quests;
        Bukkit.getMessenger().registerOutgoingPluginChannel(plugin, CHANNEL);
        task = Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 40L, 20L);
    }

    void forget(UUID id) { sent.remove(id); }

    void close() { task.cancel(); sent.clear(); submitItems.clear(); }

    private void tick() {
        long now = System.currentTimeMillis();
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (!p.getListeningPluginChannels().contains(CHANNEL)) continue;
            var entries = quests.hudEntries(p.getUniqueId());
            if (entries == null) continue; // 아직 의뢰 기록을 읽는 중
            byte[] data;
            try { data = encode(p, entries, now); } catch (RuntimeException e) { continue; }
            if (Arrays.equals(data, sent.get(p.getUniqueId()))) continue;
            p.sendPluginMessage(plugin, CHANNEL, data);
            sent.put(p.getUniqueId(), data);
        }
        sent.keySet().removeIf(id -> Bukkit.getPlayer(id) == null);
    }

    private byte[] encode(Player p, List<QuestStore.Entry> entries, long now) {
        var list = entries.stream().filter(e -> e.status().equals("active") && e.current(now))
                .sorted(Comparator.comparing((QuestStore.Entry e) -> !e.quest().main()).thenComparing(e -> e.quest().id()))
                .limit(MAX_QUESTS).toList();
        var out = new ByteArrayOutputStream();
        varInt(out, FORMAT);
        varInt(out, list.size());
        for (var e : list) {
            var q = e.quest();
            int n = q.objectives().size();
            int[] current = new int[n];
            boolean done = true;
            for (int i = 0; i < n; i++) {
                var g = q.objectives().get(i);
                int c = g.type() == Type.SUBMIT ? inventoryCount(p, g.target()) : e.progress()[i];
                current[i] = Math.min(g.amount(), Math.max(0, c));
                if (current[i] < g.amount()) done = false;
            }
            utf(out, q.title());
            utf(out, q.rank());
            out.write(q.main() ? 1 : 0);
            out.write(done ? 1 : 0);
            varInt(out, n);
            for (int i = 0; i < n; i++) {
                var g = q.objectives().get(i);
                utf(out, g.label().isBlank() ? g.target() : g.label());
                varInt(out, current[i]);
                varInt(out, g.amount());
            }
        }
        return out.toByteArray();
    }

    private int inventoryCount(Player p, String target) {
        ItemStack required = submitItems.computeIfAbsent(target, QuestBridge::submissionItem);
        int count = 0;
        for (var item : p.getInventory().getStorageContents()) if (item != null && plugin.coreSimilar(item, required)) count += item.getAmount();
        return count;
    }

    private static void varInt(ByteArrayOutputStream out, int value) {
        while ((value & ~0x7F) != 0) { out.write((value & 0x7F) | 0x80); value >>>= 7; }
        out.write(value);
    }

    private static void utf(ByteArrayOutputStream out, String s) {
        byte[] b = s.getBytes(StandardCharsets.UTF_8);
        varInt(out, b.length);
        out.write(b, 0, b.length);
    }
}
// [기능:subquest-hud-20261031 끝]
