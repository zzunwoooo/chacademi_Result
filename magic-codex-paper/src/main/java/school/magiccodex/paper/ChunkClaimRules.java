package school.magiccodex.paper;

import java.util.*;

/**
 * 야생 청크 점유 (메모 #88) 의 순수 규칙: 설정 값, 제외 구역 판정, 메모리 점유 표, 블록을 바꾸는 손 아이템 판정.
 * Bukkit 을 쓰지 않아 JUnit 으로 고정한다 (ChunkClaimRulesTest).
 */
final class ChunkClaimRules {
    private ChunkClaimRules() {}

    /** 설정의 제외 구역: 세계 world, 중심 (x, z) 에서 반경 radius 블록 원과 조금이라도 겹치는 청크는 "제한". */
    record Exclusion(String world, double x, double z, double radius) {}

    record Settings(boolean enabled, String item, String itemName, Set<String> servers, Set<String> worlds, List<Exclusion> excluded,
                    int maxClaims, int viewRadius, boolean worldGuard, Set<String> ignoreRegions, long notifyCooldownMs,
                    String freeLabel, String restrictedLabel, Map<String, String> messages) {
        Settings {
            servers = Set.copyOf(servers); worlds = Set.copyOf(worlds); excluded = List.copyOf(excluded);
            ignoreRegions = Set.copyOf(ignoreRegions); messages = Map.copyOf(messages);
        }
        /** 알림 문구 ({name}·{max}·{count} 를 바꾼다). */
        String message(String key, Map<String, String> values) {
            String m = messages.getOrDefault(key, DEFAULT_MESSAGES.getOrDefault(key, key));
            for (var e : values.entrySet()) m = m.replace("{" + e.getKey() + "}", e.getValue());
            return m;
        }
        String message(String key) { return message(key, Map.of()); }
        /** 이 서버에서 켜지는지 (servers 가 비어 있으면 모든 서버). */
        boolean activeOn(String serverId) { return enabled && (servers.isEmpty() || servers.contains(serverId)); }
    }

    static final Map<String, String> DEFAULT_MESSAGES = Map.ofEntries(
            Map.entry("claimed", "이 땅은 이제 내 땅이에요"),
            Map.entry("claimed-body", "땅문서를 1장 썼어요"),
            Map.entry("restricted", "이 땅은 소유할 수 없어요"),
            Map.entry("restricted-body", "점유 금지 구역이에요"),
            Map.entry("taken", "이미 주인이 있는 땅이에요"),
            Map.entry("taken-body", "{name}님의 땅"),
            Map.entry("mine", "이미 내 땅이에요"),
            Map.entry("limit", "더 이상 땅을 가질 수 없어요"),
            Map.entry("limit-body", "한 사람당 {max}칸까지예요"),
            Map.entry("denied", "{name}님의 땅이에요"),
            Map.entry("denied-body", "주인이 허용한 사람만 쓸 수 있어요"),
            Map.entry("sold", "땅을 팔았어요"),
            Map.entry("sold-body", "땅문서 1장을 돌려받았어요"),
            Map.entry("trusted", "{name}님을 허용했어요"),
            Map.entry("trusted-body", "내 땅에서 활동할 수 있어요"),
            Map.entry("trusted-target", "{name}님의 땅에서 활동할 수 있어요"),
            Map.entry("untrusted", "{name}님을 허용 명단에서 뺐어요"),
            Map.entry("busy", "잠시 뒤 다시 해 주세요"),
            Map.entry("error", "땅 정보를 저장하지 못했어요"),
            Map.entry("error-body", "땅문서는 돌려 드렸어요. 잠시 뒤 다시 해 주세요"));

    static final Settings DEFAULT = new Settings(true, "item:chunk_deed", "땅문서", Set.of("wild1", "wild2"), Set.of("world"), List.of(),
            0, 1, true, Set.of(), 1500, "소유 가능", "제한", Map.of());

    static int clampRadius(int r) { return Math.clamp(r, 0, school.magiccodex.protocol.ChunkClaimProtocol.MAX_RADIUS); }

    // ------------------------------------------------------------------ 좌표

    static long key(int cx, int cz) { return ((long) cx << 32) ^ (cz & 0xFFFFFFFFL); }
    static int keyX(long key) { return (int) (key >> 32); }
    static int keyZ(long key) { return (int) key; }
    static int chunk(int block) { return block >> 4; }

    /** 청크 (cx, cz) 의 네모 칸과 원(중심 x, z · 반경 r)이 겹치는지. */
    static boolean overlaps(int cx, int cz, double x, double z, double r) {
        if (r <= 0) return false;
        double minX = cx * 16.0, minZ = cz * 16.0, maxX = minX + 16, maxZ = minZ + 16;
        double nx = Math.clamp(x, minX, maxX), nz = Math.clamp(z, minZ, maxZ);
        double dx = x - nx, dz = z - nz;
        return dx * dx + dz * dz <= r * r;
    }

    /** 설정으로 정한 "제한" (대상 세계 밖, 제외 구역). WorldGuard 는 따로 본다. */
    static boolean configRestricted(Settings s, String world, int cx, int cz) {
        if (!s.worlds().contains(world)) return true;
        for (var e : s.excluded()) if (e.world().equals(world) && overlaps(cx, cz, e.x(), e.z(), e.radius())) return true;
        return false;
    }

    // ------------------------------------------------------------------ 손 아이템

    /**
     * 남의 땅 블록에 우클릭할 때 막아야 하는 손 아이템 (블록·땅을 바꾸거나 엔티티를 놓는 것). 먹기·마시기·활·방패·마법 지팡이는 그대로 둔다.
     * 양동이는 PlayerBucket 이벤트가 따로 막는다. name = Material 이름 (대문자).
     */
    static boolean modifiesBlocks(String name) {
        if (name == null || name.isEmpty()) return false;
        if (name.endsWith("_SPAWN_EGG") || name.endsWith("_HOE") || name.endsWith("_SHOVEL") || name.endsWith("_AXE")
                || name.endsWith("_BOAT") || name.endsWith("_RAFT") || name.endsWith("MINECART") || name.endsWith("_DYE")) return true;
        return switch (name) {
            case "FLINT_AND_STEEL", "FIRE_CHARGE", "BONE_MEAL", "SHEARS", "ARMOR_STAND", "ITEM_FRAME", "GLOW_ITEM_FRAME", "PAINTING",
                 "END_CRYSTAL", "LEAD", "HONEYCOMB", "BRUSH", "GLASS_BOTTLE", "WRITABLE_BOOK", "WRITTEN_BOOK", "ENDER_EYE", "INK_SAC", "GLOW_INK_SAC" -> true;
            default -> false;
        };
    }

    // ------------------------------------------------------------------ 메모리 점유 표

    record Claim(UUID owner, long at) {}

    /**
     * 서버 메모리의 점유 표: 세계 → 청크 키 → 주인. 블록 이벤트 판정은 전부 여기서 (DB 를 보지 않는다).
     * 허용 명단은 주인 기준 (주인의 모든 청크에 같이 적용). 서버 스레드에서만 쓴다.
     */
    static final class Index {
        private final Map<String, Map<Long, Claim>> claims = new HashMap<>();
        private final Map<UUID, Integer> counts = new HashMap<>();
        private final Map<UUID, Set<UUID>> trust = new HashMap<>();

        Claim get(String world, int cx, int cz) { var w = claims.get(world); return w == null ? null : w.get(key(cx, cz)); }
        UUID owner(String world, int cx, int cz) { var c = get(world, cx, cz); return c == null ? null : c.owner(); }
        /** 이 사람이 이 청크에서 활동해도 되는지: 주인 없음 · 주인 · 주인의 허용 명단. */
        boolean allowed(UUID player, String world, int cx, int cz) {
            UUID owner = owner(world, cx, cz);
            return owner == null || owner.equals(player) || trusted(owner, player);
        }
        boolean trusted(UUID owner, UUID player) { var s = trust.get(owner); return s != null && s.contains(player); }
        Set<UUID> trustedOf(UUID owner) { var s = trust.get(owner); return s == null ? Set.of() : Collections.unmodifiableSet(s); }
        int count(UUID owner) { return counts.getOrDefault(owner, 0); }
        int size() { int n = 0; for (var w : claims.values()) n += w.size(); return n; }

        void put(String world, int cx, int cz, UUID owner, long at) {
            var old = claims.computeIfAbsent(world, k -> new HashMap<>()).put(key(cx, cz), new Claim(owner, at));
            if (old != null) counts.merge(old.owner(), -1, Integer::sum);
            counts.merge(owner, 1, Integer::sum);
            counts.values().removeIf(v -> v <= 0);
        }
        UUID remove(String world, int cx, int cz) {
            var w = claims.get(world); if (w == null) return null;
            var old = w.remove(key(cx, cz)); if (old == null) return null;
            counts.merge(old.owner(), -1, Integer::sum); counts.values().removeIf(v -> v <= 0);
            return old.owner();
        }
        void trust(UUID owner, UUID player, boolean add) {
            if (add) trust.computeIfAbsent(owner, k -> new LinkedHashSet<>()).add(player);
            else { var s = trust.get(owner); if (s != null) { s.remove(player); if (s.isEmpty()) trust.remove(owner); } }
        }
        /** DB 에서 다시 읽은 허용 명단으로 통째로 바꾼다. */
        void replaceTrust(Map<UUID, ? extends Collection<UUID>> all) {
            trust.clear();
            for (var e : all.entrySet()) if (!e.getValue().isEmpty()) trust.put(e.getKey(), new LinkedHashSet<>(e.getValue()));
        }
        /** 한 주인의 이 서버 청크 (세계, 키). */
        List<Map.Entry<String, Long>> claimsOf(UUID owner) {
            var out = new ArrayList<Map.Entry<String, Long>>();
            for (var w : claims.entrySet()) for (var c : w.getValue().entrySet()) if (c.getValue().owner().equals(owner)) out.add(Map.entry(w.getKey(), c.getKey()));
            return out;
        }
    }
}
