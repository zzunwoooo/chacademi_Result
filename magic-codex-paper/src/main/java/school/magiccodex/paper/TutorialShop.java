package school.magiccodex.paper;

import java.util.*;
import org.bukkit.configuration.ConfigurationSection;

/**
 * 피피 튜토리얼 전용 재료 상점 (2026-10-25, 오픈 메모 #12) — 설정과 판정만 (Bukkit 서버 없이 테스트).
 *
 * <ul>
 *   <li>tutorial-shop.yml 의 shop = 튜토리얼 전용 상점 ID (/상점관리 만들기·편집으로 만든 일반 상점). 비우면 이 기능은 꺼짐.</li>
 *   <li>general-shops 의 상점(기본 pipi)을 튜토리얼 대상 유저가 열면 그 대신 전용 상점이 열린다.</li>
 *   <li>튜토리얼 대상 = /유저관리 &lt;닉네임&gt; 튜토리얼상점 시작 (스토리 단계 시작명령이 부름) ~ … 끝.</li>
 *   <li>required = 전용 상점의 상품 이름: 수량. 대상이 된 뒤 결제를 마친 구매가 모두 채워진 상태로 상점 창을 닫으면
 *       스토리 신호(signal, 기본 튜토리얼재료구매)를 보낸다. 비어 있으면 신호를 보내지 않는다.</li>
 * </ul>
 */
final class TutorialShop {
    static final String DEFAULT_SIGNAL = "튜토리얼재료구매";

    record Config(String shop, Set<String> general, Map<String, Integer> required, String signal) {
        static final Config OFF = new Config("", Set.of(), Map.of(), DEFAULT_SIGNAL);
        boolean enabled() { return !shop.isEmpty(); }
        /** Opening this shop shows the tutorial shop instead (when the player is a tutorial target). */
        boolean redirects(String id) { return enabled() && general.contains(id) && !id.equals(shop); }
    }

    private TutorialShop() {}

    static Config parse(ConfigurationSection y) {
        String shop = y.getString("shop", "").strip();
        if (!shop.isEmpty() && !shop.matches("[a-z0-9_-]{1,48}")) throw new IllegalArgumentException("shop: 상점 ID 는 영문 소문자·숫자·_·- 1~48자");
        var general = new LinkedHashSet<String>();
        for (String g : y.getStringList("general-shops")) {
            String id = g.strip();
            if (!id.matches("[a-z0-9_-]{1,48}")) throw new IllegalArgumentException("general-shops: 상점 ID 형식 (" + g + ")");
            general.add(id);
        }
        var required = new LinkedHashMap<String, Integer>();
        var section = y.getConfigurationSection("required");
        if (section != null) for (String name : section.getKeys(false)) {
            int n = section.getInt(name, 0);
            if (name.isBlank() || name.length() > 120) throw new IllegalArgumentException("required: 상품 이름 (" + name + ")");
            if (n < 1 || n > 576) throw new IllegalArgumentException("required." + name + ": 수량은 1~576");
            required.put(name.strip(), n);
        }
        String signal = y.getString("signal", DEFAULT_SIGNAL).strip();
        if (signal.isEmpty() || signal.length() > 64) throw new IllegalArgumentException("signal: 1~64자");
        return new Config(shop, Set.copyOf(general), Collections.unmodifiableMap(required), signal);
    }

    /** Required product name → amount, as product id → amount in that shop. Names the shop does not sell go to `missing`. */
    static Map<String, Integer> requiredIds(List<ShopStore.Product> products, Map<String, Integer> required, List<String> missing) {
        var out = new LinkedHashMap<String, Integer>();
        for (var e : required.entrySet()) {
            String id = null;
            for (var p : products) if (p.name().equals(e.getKey()) && !p.buy().isEmpty()) { id = p.id(); break; }
            if (id == null) missing.add(e.getKey()); else out.merge(id, e.getValue(), Integer::sum);
        }
        return out;
    }

    /** Every required product bought at least the required amount (an empty requirement is never complete). */
    static boolean complete(Map<String, Integer> need, Map<String, Integer> bought) {
        if (need.isEmpty()) return false;
        for (var e : need.entrySet()) if (bought.getOrDefault(e.getKey(), 0) < e.getValue()) return false;
        return true;
    }
}
