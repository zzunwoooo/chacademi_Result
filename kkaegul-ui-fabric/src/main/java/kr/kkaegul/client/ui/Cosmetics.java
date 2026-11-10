package kr.kkaegul.client.ui;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 치장품 정의 + 누가 뭘 입었는지 (서버 cosmetic_defs / cosmetics 메시지).
 * 계정명(소문자) 키로 클라에 계속 들고 있어서 텔포·사망·리스폰·월드 이동과 상관없이 그대로 그려짐.
 * full 메시지가 오면 통째로 바꾸고, delta 는 그 사람 슬롯 전부를 바꿈 ({} = 다 벗음).
 * UiHub 를 새로 만들어도(접속 초기화) 지워지지 않게 전역 — 다시 접속하면 서버가 full 로 덮어씀.
 */
public final class Cosmetics {
    private Cosmetics() {}

    public static final String HEAD = "head", FACE = "face", BACK = "back", OFFHAND = "offhand", BALLOON = "balloon";
    public static final String[] SLOTS = {HEAD, FACE, BACK, OFFHAND, BALLOON};
    public static final String[] SLOT_NAMES = {"모자", "얼굴", "등", "손", "풍선"};
    public static final String[] RARITIES = {"common", "uncommon", "rare", "legendary"};

    public record Def(String id, String slot, String name, String description, String rarity, String icon) {}

    private static volatile Map<String, Def> defs = Map.of();
    private static volatile Map<String, Map<String, String>> players = Map.of();
    /** 정의·장착이 바뀔 때마다 +1 (렌더 쪽 캐시 무효화용) */
    private static volatile int version;

    public static int version() { return version; }

    /** cosmetic_defs: 정의 통째로 바꿈 */
    public static void defs(JsonObject m) {
        Map<String, Def> out = new HashMap<>();
        JsonObject d = J.objOrEmpty(m, "defs");
        for (var e : d.entrySet()) {
            if (!e.getValue().isJsonObject()) continue;
            JsonObject o = e.getValue().getAsJsonObject();
            String slot = J.str(o, "slot").toLowerCase(Locale.ROOT);
            out.put(e.getKey(), new Def(e.getKey(), slot, J.str(o, "name", e.getKey()), J.str(o, "description"),
                    J.str(o, "rarity", "common").toLowerCase(Locale.ROOT), J.str(o, "icon")));
        }
        defs = Map.copyOf(out);
        version++;
    }

    /** cosmetics: full = 전원 상태로 교체, 아니면 온 사람들만 교체 */
    public static void players(JsonObject m) {
        boolean full = J.bool(m, "full");
        Map<String, Map<String, String>> out = full ? new HashMap<>() : new HashMap<>(players);
        JsonObject p = J.objOrEmpty(m, "players");
        for (var e : p.entrySet()) {
            String name = e.getKey().toLowerCase(Locale.ROOT);
            Map<String, String> slots = new HashMap<>();
            if (e.getValue().isJsonObject())
                for (var s : e.getValue().getAsJsonObject().entrySet()) {
                    JsonElement v = s.getValue();
                    if (v.isJsonPrimitive() && !v.getAsString().isEmpty()) slots.put(s.getKey().toLowerCase(Locale.ROOT), v.getAsString());
                }
            if (slots.isEmpty()) out.remove(name); else out.put(name, Map.copyOf(slots));
        }
        players = Map.copyOf(out);
        version++;
    }

    public static Def def(String id) { return id == null ? null : defs.get(id); }

    /** 그 사람이 그 부위에 입은 치장품의 아이템 모양 문자열 (없으면 null) */
    private static long lastFail;

    /** 치장 관련 오류는 로그만 남기고 넘어감 (10초에 한 번) — 치장 때문에 게임이 꺼지면 안 됨 */
    public static void fail(String where, Throwable t) {
        long now = System.currentTimeMillis();
        if (now - lastFail < 10_000) return;
        lastFail = now;
        org.slf4j.LoggerFactory.getLogger("kkaegul").warn("[치장] " + where + " 오류 (건너뜀)", t);
    }

    public static String icon(String account, String slot) {
        if (account == null) return null;
        Map<String, String> s = players.get(account.toLowerCase(Locale.ROOT));
        String id = s == null ? null : s.get(slot);
        if (id == null) return null;   // 그 부위는 안 입음 (Map.copyOf 맵은 null 키 조회에서 예외 → 반드시 먼저 거름)
        Def d = defs.get(id);
        return d == null || d.icon().isEmpty() ? null : d.icon();
    }

    /** 아무거나 입은 사람이 있는지 (렌더 건너뛰기용) */
    public static boolean any() { return !players.isEmpty() && !defs.isEmpty(); }

    public static Map<String, Map<String, String>> players() { return players; }

    /** 그 부위 치장품 목록: 등급 높은 순 → 이름 순 */
    public static List<Def> bySlot(String slot) {
        List<Def> out = new ArrayList<>();
        for (Def d : defs.values()) if (d.slot().equals(slot)) out.add(d);
        out.sort((a, b) -> {
            int r = Integer.compare(rank(b.rarity()), rank(a.rarity()));
            return r != 0 ? r : a.name().compareTo(b.name());
        });
        return out;
    }

    public static int total() { return defs.size(); }

    public static int rank(String rarity) {
        for (int i = 0; i < RARITIES.length; i++) if (RARITIES[i].equals(rarity)) return i;
        return 0;
    }

    public static String rarityName(String r) {
        return switch (r) { case "uncommon" -> "고급"; case "rare" -> "희귀"; case "legendary" -> "전설"; default -> "일반"; };
    }

    /** 크림 판 위 글자색 */
    public static int rarityColor(String r) {
        return switch (r) { case "uncommon" -> 0xFF3F8F33; case "rare" -> 0xFF2F78C8; case "legendary" -> 0xFFD07A12; default -> 0xFF7D7064; };
    }

    public static String slotName(String slot) {
        for (int i = 0; i < SLOTS.length; i++) if (SLOTS[i].equals(slot)) return SLOT_NAMES[i];
        return slot;
    }
}
