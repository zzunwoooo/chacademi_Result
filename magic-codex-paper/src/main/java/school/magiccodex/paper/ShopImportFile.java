package school.magiccodex.paper;

import com.mojang.brigadier.exceptions.CommandSyntaxException;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import org.bukkit.Material;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.inventory.ItemStack;

/**
 * 상점 상품 목록 파일: plugins/MagicCodexBridge/shops/&lt;이름&gt;.yml
 *
 * <pre>
 * 상점:
 *   아이디: pipi
 *   이름: 피피의 잡화점
 *   초상: pipi-neutral  # 비우면 기존 초상 유지 (새 상점은 elena-neutral)
 *   주인: 피피          # NPC 없이 열었을 때 보일 이름 (NPC로 열면 NPC 이름)
 *   역할: 잡화상인      # 이름 위 문구 (비우면 "상점 주인")
 * 상품:
 *   - { 분류: 광물, 아이템: coal, 이름: 석탄, 구매: 100, 판매: 20 }
 *   - { 분류: 광물, 아이템: ancient_debris, 이름: 고대 잔해, 구매: 없음, 판매: 2000 }
 *   - { 아이템: "item:random_artifact", 이름: 유물함 열쇠, 교환: "item:artifact_shard 10" }
 * </pre>
 *
 * 교환(2026-10-25): "비용아이템키 개수" — 돈 대신 그 아이템을 개수만큼(수량 1개당) 내고 산다. 있으면 구매·판매 값은 무시하고 판매는 막힌다.
 *
 * 순서는 파일에 적힌 순서(10, 20, 30…). 「순서」 칸을 적으면 그 값을 쓴다.
 * 아이템은 바닐라 아이템 ID, 또는 ItemsAdder 키(namespace:id, ItemsAdder 가 켜져 있을 때). 그 밖의 커스텀 아이템은 관리 창에서 인벤토리로 등록.
 * 파일 읽기와 아이템 만들기는 명령을 실행한 메인 스레드에서 한다.
 */
final class ShopImportFile {
    record Loaded(String shop, String title, String portrait, String owner, String role, List<ShopStore.ImportEntry> entries) {}

    private static final List<String> BUNDLED = List.of("pipi", "artifact_exchange");

    private ShopImportFile() {}

    private static Path folder(MagicCodexBridge plugin) {
        return plugin.getDataFolder().toPath().resolve("shops");
    }

    /** 플러그인에 들어 있는 기본 목록 파일을 처음 한 번만 꺼내 둔다 (이미 있으면 덮어쓰지 않음). */
    private static void extract(MagicCodexBridge plugin) {
        for (String name : BUNDLED) {
            if (!Files.exists(folder(plugin).resolve(name + ".yml")) && plugin.getResource("shops/" + name + ".yml") != null) {
                plugin.saveResource("shops/" + name + ".yml", false);
            }
        }
    }

    static Collection<String> names(MagicCodexBridge plugin) {
        extract(plugin);
        var out = new TreeSet<String>();
        try (var files = Files.list(folder(plugin))) {
            files.map(f -> f.getFileName().toString()).filter(f -> f.endsWith(".yml")).forEach(f -> out.add(f.substring(0, f.length() - 4)));
        } catch (IOException ignored) {
            // 폴더가 없으면 자동완성만 비어 있다
        }
        return out;
    }

    static Loaded load(MagicCodexBridge plugin, String name) throws CommandSyntaxException {
        if (!name.matches("[a-z0-9_-]{1,48}")) throw ChacaApi.error("파일 이름은 영문 소문자·숫자·_·- 로 써요");
        extract(plugin);
        Path path = folder(plugin).resolve(name + ".yml");
        if (!Files.isRegularFile(path)) throw ChacaApi.error("shops/" + name + ".yml 파일이 없어요");
        var yml = new YamlConfiguration();
        try {
            yml.loadFromString(Files.readString(path));
        } catch (IOException | InvalidConfigurationException e) {
            throw ChacaApi.error("shops/" + name + ".yml 을 읽지 못했어요 (들여쓰기·따옴표 확인)");
        }
        String shop = yml.getString("상점.아이디", "").strip();
        String title = yml.getString("상점.이름", "").strip();
        String portrait = yml.getString("상점.초상", "").strip();
        String owner = yml.getString("상점.주인", "").strip();
        String role = yml.getString("상점.역할", "").strip();
        if (!shop.matches("[a-z0-9_-]{1,48}")) throw ChacaApi.error("상점.아이디 를 영문 소문자·숫자·_·- 로 적어 주세요");
        if (title.isEmpty() || title.length() > 80) throw ChacaApi.error("상점.이름 을 1~80자로 적어 주세요");
        var rows = yml.getMapList("상품");
        if (rows.isEmpty()) throw ChacaApi.error("상품 목록이 비어 있어요");
        var entries = new ArrayList<ShopStore.ImportEntry>();
        var problems = new ArrayList<String>();
        int line = 0;
        for (Map<?, ?> row : rows) {
            line++;
            String label = line + "번째";
            try {
                String itemId = text(row, "아이템");
                String itemName = text(row, "이름");
                ItemStack item = item(plugin, itemId);
                if (item == null) {
                    problems.add(label + " 아이템 ID " + itemId);
                    continue;
                }
                if (itemName.isEmpty()) {
                    problems.add(label + " 이름 없음");
                    continue;
                }
                String exchange = text(row, "교환");
                var cost = exchange.isEmpty() ? null : ShopExchange.parse(exchange);
                String buy = cost != null ? "" : price(row.get("구매"));
                String sell = cost != null ? "" : price(row.get("판매"));
                Object order = row.get("순서");
                int sort = order == null ? line * 10 : Integer.parseInt(String.valueOf(order).strip());
                byte[] bytes = item.serializeAsBytes();
                entries.add(new ShopStore.ImportEntry(itemName, bytes, buy, sell, text(row, "분류"), sort,
                        cost == null ? "" : cost.item(), cost == null ? 0 : cost.count()));
            } catch (RuntimeException e) {
                problems.add(label + " " + (e.getMessage() == null ? "값 확인" : e.getMessage()));
            }
        }
        if (!problems.isEmpty()) {
            throw ChacaApi.error("고칠 곳 " + problems.size() + "개: " + String.join(", ", problems.subList(0, Math.min(5, problems.size()))) + (problems.size() > 5 ? " …" : ""));
        }
        return new Loaded(shop, title, portrait, owner, role, List.copyOf(entries));
    }

    /** 바닐라 ID(coal, minecraft:coal) 또는 ItemsAdder 키(item:random_artifact). 1개짜리, 없으면 null. */
    private static ItemStack item(MagicCodexBridge plugin, String id) {
        String key = id.strip().toLowerCase(Locale.ROOT);
        if (key.contains(":") && !key.startsWith("minecraft:")) {
            ItemStack custom = new ItemsAdderItems(plugin.getLogger()).create(key, 1);
            return custom == null || custom.getType().isAir() ? null : custom;
        }
        Material material = Material.matchMaterial(key);
        return material == null || !material.isItem() || material.isAir() ? null : new ItemStack(material, 1);
    }

    private static String text(Map<?, ?> row, String key) {
        Object v = row.get(key);
        return v == null ? "" : String.valueOf(v).strip();
    }

    /** 없음·off·빈칸 = 그 방향 거래 막기. 숫자는 상점 가격 규칙(0 이상, 소수점 6자리)으로 맞춘다. */
    private static String price(Object value) {
        String v = value == null ? "" : String.valueOf(value).strip();
        if (v.isEmpty() || v.equals("없음") || v.equalsIgnoreCase("off")) return "";
        try {
            return ShopStore.price(v);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("가격 " + v);
        }
    }
}
