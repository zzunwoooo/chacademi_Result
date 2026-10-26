package school.chacademia.housing;

import java.util.*;
import java.util.function.BooleanSupplier;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import school.magiccodex.protocol.HousingProtocol;

/**
 * #108 (2026-10-26) 하우징 특수 가구 전시 값: 방 주인이 장착한 빗자루(id·이름)와 아티팩트 id 목록.
 * 빗자루 거치대·아티팩트 진열장 위에 그리는 데만 쓴다. 큐디치 티어는 클라이언트가 프록시 전적(PROFILE)으로 직접 묻는다.
 *
 * <p>값을 적는 때 (모두 한 번씩, 매 틱·주기 작업 없음, 바뀌었을 때만 DB 에 씀):
 * <ul>
 *   <li>접속 30초 뒤 (아티팩트·빗자루 정보가 다 읽힌 다음)</li>
 *   <li>주인이 자기 방을 열 때</li>
 *   <li>누가 이 서버에 접속해 있는 주인의 방을 열 때 (지금 값으로 맞춘 뒤 읽음)</li>
 * </ul>
 * 나갈 때는 적지 않는다: 다른 플러그인(MagicCodexBridge)이 먼저 아티팩트 보유 정보를 지우면 빈 목록이 적힐 수 있어서.
 * 주인이 다른 서버에 있으면 공용 DB(codex_housing_showcase)에 마지막으로 적힌 값을 쓴다.
 * 값을 읽는 쪽(ChacademyMotion·MagicCodexBridge)은 리플렉션 — 플러그인이 없으면 빈 값 (꾸며 내지 않음).
 */
final class Showcases {
    private final HousingPlugin plugin;
    /** 이 서버가 마지막으로 적은 값 (같으면 다시 안 씀). 접속할 때 지운다 (다른 서버가 그 사이 적었을 수 있음). */
    private final Map<UUID, String> written = new HashMap<>();

    Showcases(HousingPlugin plugin) { this.plugin = plugin; }

    void joined(Player p) {
        UUID id = p.getUniqueId();
        written.remove(id);
        Bukkit.getScheduler().runTaskLater(plugin, () -> { if (Bukkit.getPlayer(id) == p && p.isOnline()) capture(p); }, 20L * 30);
    }
    void quit(UUID id) { written.remove(id); }

    /** 주인의 지금 값을 DB 에 (바뀐 경우만). 메인 스레드, 리플렉션 몇 번뿐. */
    void capture(Player owner) {
        UUID id = owner.getUniqueId();
        String broom = broomId(owner), name = broom.isEmpty() ? "" : broomName(broom), arts = String.join(",", artifacts(owner));
        String key = broom + "\n" + name + "\n" + arts;
        if (key.equals(written.get(id))) return;
        written.put(id, key);
        plugin.store().saveShowcase(id, broom, name, arts, ok -> { if (!Boolean.TRUE.equals(ok)) written.remove(id); });
    }

    /** 방에 들어온 viewer 에게 owner 의 전시 값을 한 번 보낸다. still = 아직 그 방을 보고 있는지. */
    void send(Player viewer, UUID owner, BooleanSupplier still) {
        Player here = Bukkit.getPlayer(owner);
        if (here != null) capture(here); // 같은 DB 작업 줄이라 읽기보다 먼저 적힌다
        plugin.store().showcase(owner, row -> {
            if (row == null || !viewer.isOnline() || !still.getAsBoolean()) return;
            plugin.net().send(viewer, new HousingProtocol.Showcase(owner, clip(row[0]), clip(row[1]), split(row[2])));
        });
    }

    /** DB 의 "a,b,c" → 아티팩트 id 목록 (빈 칸·중복·너무 긴 id 버림, 최대 {@link HousingProtocol#MAX_SHOWCASE_ARTIFACTS}개). */
    static List<String> split(String raw) {
        var out = new ArrayList<String>();
        if (raw == null) return out;
        for (String part : raw.split(",")) {
            String id = part.strip();
            if (id.isEmpty() || id.length() > HousingProtocol.SHOWCASE_CHARS || out.contains(id)) continue;
            out.add(id);
            if (out.size() >= HousingProtocol.MAX_SHOWCASE_ARTIFACTS) break;
        }
        return out;
    }
    static String clip(String v) { return v == null ? "" : v.length() > HousingProtocol.SHOWCASE_CHARS ? v.substring(0, HousingProtocol.SHOWCASE_CHARS) : v; }

    // ------------------------------------------------------------ 다른 플러그인 값 (리플렉션, 없으면 빈 값)
    /** ChacademyMotion.equippedBroomId (정보창 StatsBridge·큐디치 Hooks 와 같은 방법). */
    private static String broomId(Player p) {
        var m = Bukkit.getPluginManager().getPlugin("ChacademyMotion");
        if (m == null || !m.isEnabled()) return "";
        try { return m.getClass().getMethod("equippedBroomId", Player.class).invoke(m, p) instanceof String s ? clip(s) : ""; }
        catch (ReflectiveOperationException | RuntimeException | LinkageError e) { return ""; }
    }
    /** ChacademyMotion.brooms() (BroomService) → brooms() (id → BroomType) → name(). 방문자 클라이언트는 남의 빗자루 이름을 모른다. */
    private static String broomName(String id) {
        var m = Bukkit.getPluginManager().getPlugin("ChacademyMotion");
        if (m == null || !m.isEnabled()) return "";
        try {
            Object service = m.getClass().getMethod("brooms").invoke(m);
            if (service == null) return "";
            Object map = service.getClass().getMethod("brooms").invoke(service);
            Object type = map instanceof Map<?, ?> all ? all.get(id) : null;
            if (type == null) return "";
            return type.getClass().getMethod("name").invoke(type) instanceof String s ? clip(s) : "";
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) { return ""; }
    }
    /** MagicCodexBridge.equippedArtifactIds (장착 중이고 아직 가진 것만). */
    private static List<String> artifacts(Player p) {
        var m = Bukkit.getPluginManager().getPlugin("MagicCodexBridge");
        if (m == null || !m.isEnabled()) return List.of();
        try {
            Object v = m.getClass().getMethod("equippedArtifactIds", Player.class).invoke(m, p);
            if (!(v instanceof List<?> list)) return List.of();
            var out = new ArrayList<String>();
            for (Object o : list) if (o instanceof String s) out.add(s);
            return split(String.join(",", out));
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) { return List.of(); }
    }
}
