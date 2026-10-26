package school.magiccodex.paper;

import java.util.Locale;

/**
 * 상점 교환 상품 (2026-10-25, 오너 결정): 돈 대신 아이템 N개를 내고 사는 상품. 예: 아티팩트 조각 10개 → 유물함 열쇠 1개.
 * 비용 아이템 키는 ItemsAdder {@code namespace:id} 또는 바닐라 {@code minecraft:id}. 교환 상품은 팔 수 없다.
 * Bukkit 없이 검사·계산만 한다 (인벤토리 칸의 키 판정은 ShopBridge.costKey).
 */
final class ShopExchange {
    /** 비용 개수 한도 (36칸 × 64). */
    static final int MAX_COUNT = 2304;

    record Cost(String item, int count) {}

    private ShopExchange() {}

    /** 키 정리·검사. 콜론이 없으면 바닐라(minecraft:)로 본다. */
    static String key(String input) {
        String k = input == null ? "" : input.strip().toLowerCase(Locale.ROOT);
        if (!k.isEmpty() && !k.contains(":")) k = "minecraft:" + k;
        if (k.length() > 80 || !k.matches("[a-z0-9_.-]{1,40}:[a-z0-9_./-]{1,79}"))
            throw new IllegalArgumentException("교환 아이템 키는 namespace:id 로 적어 주세요 (예: item:artifact_shard, minecraft:diamond)");
        return k;
    }

    static int count(int n) {
        if (n < 1 || n > MAX_COUNT) throw new IllegalArgumentException("교환 개수는 1~" + MAX_COUNT + " 사이예요.");
        return n;
    }

    /** 저장소 값 검사: 둘 다 비었으면(""·0) 교환 아님, 아니면 둘 다 올바라야 한다. */
    static void validate(String item, int count) {
        if (item.isEmpty() && count == 0) return;
        if (!key(item).equals(item)) throw new IllegalArgumentException("교환 아이템 키 확인");
        count(count);
    }

    /** 가져오기 파일 값: {@code "item:artifact_shard 10"} (개수 앞 x·× 허용). */
    static Cost parse(String text) {
        String v = text == null ? "" : text.strip();
        String[] parts = v.split("\\s+");
        if (parts.length != 2) throw new IllegalArgumentException("교환은 \"아이템키 개수\" 로 적어 주세요 (예: \"item:artifact_shard 10\")");
        String n = parts[1].replaceFirst("^[xX×]", "");
        if (!n.matches("[0-9]{1,5}")) throw new IllegalArgumentException("교환 개수 " + parts[1]);
        return new Cost(key(parts[0]), count(Integer.parseInt(n)));
    }

    /** 수량 N개를 살 때 내야 하는 비용 아이템 수. */
    static long total(int count, int quantity) { return (long) count * quantity; }

    /** keys[i] = i번 칸 아이템의 키(비용으로 칠 수 없으면 null), amounts[i] = 그 칸 개수. 그 키만 센다. */
    static int held(String[] keys, int[] amounts, String key) {
        int n = 0;
        for (int i = 0; i < keys.length; i++) if (key.equals(keys[i])) n += Math.max(0, amounts[i]);
        return n;
    }

    /** 앞 칸부터 need 개를 덜어 낸 새 개수 배열. 모자라면 null (아무것도 바꾸지 않음). */
    static int[] take(String[] keys, int[] amounts, String key, long need) {
        if (need < 1 || held(keys, amounts, key) < need) return null;
        int[] out = amounts.clone();
        long left = need;
        for (int i = 0; i < out.length && left > 0; i++) {
            if (!key.equals(keys[i]) || out[i] <= 0) continue;
            int n = (int) Math.min(left, out[i]);
            out[i] -= n;
            left -= n;
        }
        return out;
    }
}
