package school.magiccodex.paper;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.inventory.ItemStack;

/**
 * plugins/MagicCodexBridge/loot-boxes.yml (뽑기 상자 정의). 사람이 읽고 손으로 고칠 수 있는 형식 — 형식 설명은 파일 머리 주석.
 * 메인 스레드에서만 모델을 바꾼다. 파일 쓰기는 {@link #text()} 로 만든 문자열을 LootBoxBridge 의 IO 스레드가 쓴다.
 */
final class LootBoxStore {
    /** 열쇠: itemsadder(ItemsAdder id) | material(+modelData: 재질+모델) | item(정확히 같은 아이템, base64) 중 하나. */
    record Key(String itemsadder, Material material, Integer modelData, String item) {
        String describe() {
            if (itemsadder != null) return "ItemsAdder " + itemsadder;
            if (material != null) return "재질 " + material.getKey() + (modelData == null ? " (모델 데이터 없음)" : " + 모델 " + modelData);
            if (item != null) return "정확히 같은 아이템 (등록 원본)";
            return "없음";
        }
        boolean empty() { return itemsadder == null && material == null && item == null; }
    }
    /**
     * 보상: itemsadder | vanilla | item(base64) | furniture(하우징 가구 id) 중 하나 + amount·grade(0~3)·weight.
     * label = 사람이 읽기용 메모(지급과 무관) — 단 furniture 는 label 이 화면에 보일 가구 이름(필수), icon = 화면에 그릴 가구 그림(선택).
     * furniture 보상은 아이템이 아니라 LuckPerms 권한(chacademi.housing.furniture.&lt;id&gt;)으로 지급한다 (LootBoxBridge.settle).
     */
    record Reward(String itemsadder, String vanilla, String item, int amount, int grade, int weight, String label, String furniture, String icon) {
        Reward(String itemsadder, String vanilla, String item, int amount, int grade, int weight, String label) {
            this(itemsadder, vanilla, item, amount, grade, weight, label, null, null);
        }
        String spec() {
            if (furniture != null) return "가구 " + furniture + (label == null || label.isBlank() ? "" : " (" + label + ")");
            if (itemsadder != null) return "ItemsAdder " + itemsadder;
            if (vanilla != null) return vanilla;
            return label == null || label.isBlank() ? "등록 원본 아이템" : label;
        }
    }
    record Box(String id, String name, boolean enabled, Key key, List<Reward> rewards, int speed, List<String> problems) {
        boolean valid() { return problems.isEmpty(); }
        long totalWeight() { long t = 0; for (var r : rewards) if (r.weight() > 0) t += r.weight(); return t; }
        int[] weights() { return rewards.stream().mapToInt(r -> Math.max(0, r.weight())).toArray(); }
    }

    static final String HEADER = """
            뽑기 상자 (API 상자) — 2026-10-26
            /상자관리 관리 창에서 바꾸면 이 파일에 저장됩니다. 손으로 고친 뒤에는 /상자관리 새로고침 (또는 /서버관리 새로고침 뽑기상자).
            학교·야생 서버 모두 같은 파일을 둡니다.

            boxes:
              <상자id>:                 # 영문 소문자·숫자·_- (명령어에서는 name 으로도 부를 수 있음)
                name: "API 상자"         # 화면·명령어 이름 (띄어쓰기 없이)
                enabled: true            # false 면 열쇠를 써도 열리지 않음
                key:                     # 열쇠 판정 — 아래 셋 중 하나만
                  itemsadder: "item:api_box_key"   # ItemsAdder id 가 같으면 열쇠
                  # material: PAPER                # 재질 + 모델 데이터가 같으면 열쇠 (이름·설명이 달라도 됨)
                  # model-data: 10001
                  # item: "<base64>"               # 이름·설명까지 정확히 같은 아이템만 (GUI 가 넣는 값)
                animation:
                  speed: 100             # 연출 속도 % (50~200)
                rewards:                 # 보상 목록 — 한 번 열 때 weight(가중치) 비율로 하나
                  - itemsadder: "item:mana_potion"   # 또는 vanilla: "minecraft:diamond" 또는 item: "<base64>"(GUI 등록 원본)
                    amount: 1            # 1~64 (그 아이템 최대 스택 이하)
                    grade: 고급          # 일반 / 고급 / 유일 / 전설 (결과 칸 색·연출 빛)
                    weight: 30           # 확률 = weight / 모든 weight 합
                    label: "마나 물약"    # (선택) 사람이 읽기용 메모, 지급과 무관
                  - furniture: sofa      # 하우징 가구 (아이템이 아니라 가구 보유 권한으로 지급, amount 는 1)
                    icon: "magiccodex:textures/gui/housing/furniture/sofa.png"   # (선택) 결과 칸에 그릴 가구 그림
                    label: "2인 소파"     # 가구 보상은 label 이 화면 이름 (필수)
                    grade: 일반
                    weight: 10           # 이미 가진 가구는 나오지 않음 (가지지 않은 가구 중에서 같은 비율로 다시 뽑음)
            """;

    private final ItemsAdderItems itemsAdder;
    private final LinkedHashMap<String, Box> boxes = new LinkedHashMap<>();
    private long revision = 1;

    LootBoxStore(ItemsAdderItems itemsAdder) { this.itemsAdder = itemsAdder; }

    long revision() { return revision; }
    Collection<Box> boxes() { return boxes.values(); }
    Box get(String id) { return boxes.get(id); }

    /** 이름 또는 id 로 찾기. */
    Box find(String input) {
        if (input == null) return null;
        String t = input.strip();
        var byId = boxes.get(t.toLowerCase(Locale.ROOT));
        if (byId != null) return byId;
        for (var b : boxes.values()) if (b.name().equals(t)) return b;
        return null;
    }

    List<String> names() { return boxes.values().stream().map(Box::name).toList(); }

    // ------------------------------------------------------------------ 읽기

    /** 파일 내용(문자열)을 읽어 모델을 통째로 바꾼다. 형식 오류면 예외(이전 모델 유지). 상자 하나의 값 오류는 그 상자만 problems 에 적고 비활성. */
    void load(String text) throws Exception {
        var y = new YamlConfiguration();
        y.loadFromString(text);
        var next = new LinkedHashMap<String, Box>();
        var section = y.getConfigurationSection("boxes");
        if (section != null) for (String id : section.getKeys(false)) {
            var b = section.getConfigurationSection(id);
            if (b == null) continue;
            next.put(id, parse(id, b));
        }
        boxes.clear();
        boxes.putAll(next);
        revision++;
    }

    private static Box parse(String id, ConfigurationSection b) {
        var problems = new ArrayList<String>();
        if (!id.matches("[a-z0-9_-]{1,48}")) problems.add("상자 id 는 영문 소문자·숫자·_- 1~48자");
        String name = b.getString("name", id).strip();
        if (name.isEmpty() || name.length() > 24 || name.contains(" ")) problems.add("name 은 띄어쓰기 없이 1~24자");
        Key key = new Key(null, null, null, null);
        var k = b.getConfigurationSection("key");
        if (k != null) {
            String ia = blank(k.getString("itemsadder")), item = blank(k.getString("item")), mat = blank(k.getString("material"));
            Material material = mat == null ? null : Material.matchMaterial(mat);
            if (mat != null && (material == null || !material.isItem() || material.isAir())) problems.add("key.material 을 알 수 없음: " + mat);
            Integer model = k.isInt("model-data") ? k.getInt("model-data") : null;
            int kinds = (ia != null ? 1 : 0) + (mat != null ? 1 : 0) + (item != null ? 1 : 0);
            if (kinds > 1) problems.add("key 는 itemsadder / material / item 중 하나만");
            if (item != null && decode(item) == null) problems.add("key.item(base64) 을 읽을 수 없음");
            key = new Key(ia, material, model, item);
        }
        boolean enabled = b.getBoolean("enabled", true);
        if (key.empty() && enabled) problems.add("열쇠(key)가 없음 — /상자관리 관리 창에서 '열쇠 다시 지정'");
        int speed = b.getInt("animation.speed", 100);
        if (speed < 50 || speed > 200) { problems.add("animation.speed 는 50~200"); speed = Math.clamp(speed, 50, 200); }
        var rewards = new ArrayList<Reward>();
        int n = 0;
        for (var raw : b.getMapList("rewards")) {
            n++;
            String ia = blank(String.valueOf(raw.get("itemsadder") == null ? "" : raw.get("itemsadder")));
            String vanilla = blank(String.valueOf(raw.get("vanilla") == null ? "" : raw.get("vanilla")));
            String item = blank(String.valueOf(raw.get("item") == null ? "" : raw.get("item")));
            String furniture = blank(String.valueOf(raw.get("furniture") == null ? "" : raw.get("furniture")));
            String icon = blank(String.valueOf(raw.get("icon") == null ? "" : raw.get("icon")));
            String label = raw.get("label") == null ? null : String.valueOf(raw.get("label"));
            int amount = number(raw.get("amount"), 1), weight = number(raw.get("weight"), 0);
            int grade = LootBoxRules.grade(raw.get("grade") == null ? "일반" : String.valueOf(raw.get("grade")));
            int kinds = (ia != null ? 1 : 0) + (vanilla != null ? 1 : 0) + (item != null ? 1 : 0) + (furniture != null ? 1 : 0);
            if (kinds != 1) problems.add(n + "번 보상: itemsadder / vanilla / item / furniture 중 하나만");
            if (furniture != null) {
                if (!school.magiccodex.protocol.HousingRules.validId(furniture)) problems.add(n + "번 보상: furniture 는 가구 id (영문 소문자·숫자·_ 32자까지): " + furniture);
                if (label == null || label.isBlank() || label.length() > 60) problems.add(n + "번 보상: 가구 보상은 label(화면 이름, 60자까지)이 필요해요");
                if (amount != 1) problems.add(n + "번 보상: 가구 보상은 amount 1");
            }
            if (icon != null && (furniture == null || icon.length() > 96 || !icon.matches("[a-z0-9_.-]+:[a-z0-9_./-]+\\.png"))) problems.add(n + "번 보상: icon 은 가구 보상에만, \"magiccodex:textures/....png\" 형식");
            if (vanilla != null) { var m = Material.matchMaterial(vanilla); if (m == null || !m.isItem() || m.isAir()) problems.add(n + "번 보상: 알 수 없는 vanilla 아이템 " + vanilla); }
            if (item != null && decode(item) == null) problems.add(n + "번 보상: item(base64) 을 읽을 수 없음");
            if (amount < 1 || amount > 64) problems.add(n + "번 보상: amount 는 1~64");
            if (weight < 0 || weight > 1_000_000) problems.add(n + "번 보상: weight 는 0~1000000");
            if (grade < 0) { problems.add(n + "번 보상: grade 는 일반/고급/유일/전설"); grade = 0; }
            rewards.add(new Reward(ia, vanilla, item, amount, grade, weight, label, furniture, icon));
        }
        if (rewards.size() > 200) problems.add("보상은 200개까지");
        return new Box(id, name, enabled, key, List.copyOf(rewards), speed, List.copyOf(problems));
    }

    private static String blank(String s) { return s == null || s.isBlank() || s.equals("null") ? null : s.strip(); }
    private static int number(Object o, int fallback) {
        if (o instanceof Number n) return n.intValue();
        try { return o == null ? fallback : Integer.parseInt(String.valueOf(o).strip()); } catch (NumberFormatException e) { return -1; }
    }

    static ItemStack decode(String base64) {
        try { return ItemStack.deserializeBytes(Base64.getDecoder().decode(base64)); } catch (RuntimeException e) { return null; }
    }
    static String encode(ItemStack item) { var one = item.clone(); one.setAmount(1); return Base64.getEncoder().encodeToString(one.serializeAsBytes()); }

    // ------------------------------------------------------------------ 쓰기 (모델 → yml 문자열)

    String text() {
        var y = new YamlConfiguration();
        y.options().setHeader(HEADER.lines().toList());
        for (var b : boxes.values()) {
            String base = "boxes." + b.id() + ".";
            y.set(base + "name", b.name());
            y.set(base + "enabled", b.enabled());
            var k = b.key();
            if (k.itemsadder() != null) y.set(base + "key.itemsadder", k.itemsadder());
            if (k.material() != null) { y.set(base + "key.material", k.material().name()); if (k.modelData() != null) y.set(base + "key.model-data", k.modelData()); }
            if (k.item() != null) y.set(base + "key.item", k.item());
            y.set(base + "animation.speed", b.speed());
            var list = new ArrayList<Map<String, Object>>();
            for (var r : b.rewards()) {
                var m = new LinkedHashMap<String, Object>();
                if (r.itemsadder() != null) m.put("itemsadder", r.itemsadder());
                if (r.vanilla() != null) m.put("vanilla", r.vanilla());
                if (r.item() != null) m.put("item", r.item());
                if (r.furniture() != null) m.put("furniture", r.furniture());
                if (r.icon() != null) m.put("icon", r.icon());
                m.put("amount", r.amount());
                m.put("grade", LootBoxRules.gradeName(r.grade()));
                m.put("weight", r.weight());
                if (r.label() != null && !r.label().isBlank()) m.put("label", r.label());
                list.add(m);
            }
            y.set(base + "rewards", list);
        }
        if (boxes.isEmpty()) y.createSection("boxes");
        return y.saveToString();
    }

    // ------------------------------------------------------------------ 바꾸기 (관리 창·명령어)

    private void put(Box b) { boxes.put(b.id(), revalidate(b)); revision++; }

    /** 값을 바꾼 뒤 problems 를 다시 계산 (파일 왕복과 같은 판정). */
    private static Box revalidate(Box b) {
        var tmp = new LootBoxStore(null);
        tmp.boxes.put(b.id(), b);
        try { tmp.load(tmp.text()); return tmp.boxes.get(b.id()); } catch (Exception e) { return b; }
    }

    Box create(String name, Key key) {
        if (name.isEmpty() || name.length() > 24 || name.contains(" ")) throw new IllegalArgumentException("이름은 띄어쓰기 없이 1~24자로 해 주세요");
        if (find(name) != null) throw new IllegalArgumentException("같은 이름의 상자가 이미 있어요");
        String id = LootBoxRules.id(name, boxes.keySet());
        var b = new Box(id, name, true, key, List.of(), 100, List.of());
        put(b);
        return boxes.get(id);
    }

    void rename(Box b, String name) {
        if (name.isEmpty() || name.length() > 24 || name.contains(" ")) throw new IllegalArgumentException("이름은 띄어쓰기 없이 1~24자로 해 주세요");
        var other = find(name);
        if (other != null && !other.id().equals(b.id())) throw new IllegalArgumentException("같은 이름의 상자가 이미 있어요");
        put(new Box(b.id(), name, b.enabled(), b.key(), b.rewards(), b.speed(), b.problems()));
    }

    void rekey(Box b, Key key) { put(new Box(b.id(), b.name(), b.enabled(), key, b.rewards(), b.speed(), b.problems())); }

    void delete(Box b) { boxes.remove(b.id()); revision++; }

    void addReward(Box b, Reward r) {
        if (b.rewards().size() >= 200) throw new IllegalArgumentException("보상은 200개까지예요");
        var list = new ArrayList<>(b.rewards()); list.add(r);
        put(new Box(b.id(), b.name(), b.enabled(), b.key(), List.copyOf(list), b.speed(), b.problems()));
    }

    void editReward(Box b, int index, int amount, int grade, int weight) {
        if (index < 0 || index >= b.rewards().size()) throw new IllegalArgumentException("그 보상이 없어요. 새로고침해 주세요");
        var old = b.rewards().get(index);
        var list = new ArrayList<>(b.rewards());
        list.set(index, new Reward(old.itemsadder(), old.vanilla(), old.item(), amount, grade, weight, old.label(), old.furniture(), old.icon()));
        put(new Box(b.id(), b.name(), b.enabled(), b.key(), List.copyOf(list), b.speed(), b.problems()));
    }

    void deleteReward(Box b, int index) {
        if (index < 0 || index >= b.rewards().size()) throw new IllegalArgumentException("그 보상이 없어요. 새로고침해 주세요");
        var list = new ArrayList<>(b.rewards()); list.remove(index);
        put(new Box(b.id(), b.name(), b.enabled(), b.key(), List.copyOf(list), b.speed(), b.problems()));
    }

    // ------------------------------------------------------------------ 아이템

    /** 손에 든(또는 고른 칸의) 아이템으로 열쇠 판정 만들기: ItemsAdder id → 재질+모델 데이터 → 정확히 같은 아이템 순. */
    Key keyFrom(ItemStack held) {
        String ia = itemsAdder == null ? null : itemsAdder.idOf(held);
        if (ia != null) return new Key(ia, null, null, null);
        var meta = held.getItemMeta();
        if (meta != null && meta.hasCustomModelData()) return new Key(null, held.getType(), meta.getCustomModelData(), null);
        return new Key(null, null, null, encode(held));
    }

    /** 고른 아이템으로 보상 만들기: ItemsAdder 원본과 같으면 id, 아무 설정 없는 바닐라면 vanilla id, 그 밖은 원본 그대로(base64). */
    Reward rewardFrom(ItemStack picked, int amount, int grade, int weight) {
        String ia = itemsAdder == null ? null : itemsAdder.idOf(picked);
        String label = net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText().serialize(picked.displayName()).replaceAll("^\\[|\\]$", "");
        if (label.length() > 60) label = label.substring(0, 60);
        if (ia != null) {
            var fresh = itemsAdder.create(ia, 1);
            if (fresh != null && fresh.isSimilar(picked)) return new Reward(ia, null, null, amount, grade, weight, label);
        }
        if (picked.isSimilar(new ItemStack(picked.getType()))) return new Reward(null, picked.getType().getKey().toString(), null, amount, grade, weight, label);
        return new Reward(null, null, encode(picked), amount, grade, weight, label);
    }

    /** 가구 보상 표시용 아이템에 붙이는 그림 경로 (클라 LootBoxScreen·LootBoxAdminScreen 이 읽어 그 PNG 를 그린다). */
    static final org.bukkit.NamespacedKey ICON = new org.bukkit.NamespacedKey("magiccodexbridge", "lootbox_icon");

    /**
     * 가구 보상의 표시용 아이템 (연출·결과 채팅·관리 창 미리보기·영수증 기록용 — 인벤토리에 넣지 않는다).
     * 그림(icon)이 있으면 클라가 그 PNG 를 그리고, 없거나 모드가 없으면 그림 아이템으로 보인다.
     */
    static ItemStack furnitureDisplay(Reward r) {
        var out = new ItemStack(Material.PAINTING);
        var meta = out.getItemMeta();
        meta.displayName(net.kyori.adventure.text.Component.text(r.label() == null ? r.furniture() : r.label(), net.kyori.adventure.text.format.NamedTextColor.WHITE)
                .decoration(net.kyori.adventure.text.format.TextDecoration.ITALIC, false));
        meta.lore(List.of(net.kyori.adventure.text.Component.text("하우징 가구 · 내 방 꾸미기 보관함에 바로 들어가요", net.kyori.adventure.text.format.NamedTextColor.GRAY)
                .decoration(net.kyori.adventure.text.format.TextDecoration.ITALIC, false)));
        if (r.icon() != null) meta.getPersistentDataContainer().set(ICON, org.bukkit.persistence.PersistentDataType.STRING, r.icon());
        out.setItemMeta(meta);
        return out;
    }

    /** 보상 아이템 실물 (메인 스레드). 만들 수 없으면 null. 가구 보상은 표시용 아이템. */
    ItemStack create(Reward r) {
        if (r.furniture() != null) return furnitureDisplay(r);
        ItemStack out;
        if (r.itemsadder() != null) out = itemsAdder == null ? null : itemsAdder.create(r.itemsadder(), 1);
        else if (r.vanilla() != null) { var m = Material.matchMaterial(r.vanilla()); out = m == null ? null : new ItemStack(m); }
        else out = decode(r.item());
        if (out == null || out.getType().isAir()) return null;
        if (r.amount() > out.getMaxStackSize()) return null;
        out.setAmount(r.amount());
        return out;
    }

    /** 열쇠 실물 예시 (관리 창 미리보기). */
    ItemStack keySample(Key k) {
        if (k.itemsadder() != null) return itemsAdder == null ? null : itemsAdder.create(k.itemsadder(), 1);
        if (k.material() != null) {
            var it = new ItemStack(k.material());
            if (k.modelData() != null) { var meta = it.getItemMeta(); if (meta != null) { meta.setCustomModelData(k.modelData()); it.setItemMeta(meta); } }
            return it;
        }
        return k.item() == null ? null : decode(k.item());
    }

    /** 열쇠인지. 메인 스레드. */
    boolean matches(Key k, ItemStack it) {
        if (it == null || it.getType().isAir() || k.empty()) return false;
        if (k.itemsadder() != null) return k.itemsadder().equals(itemsAdder == null ? null : itemsAdder.idOf(it));
        if (k.material() != null) {
            if (it.getType() != k.material()) return false;
            var meta = it.getItemMeta();
            boolean has = meta != null && meta.hasCustomModelData();
            return k.modelData() == null ? !has : has && meta.getCustomModelData() == k.modelData();
        }
        var sample = decode(k.item());
        return sample != null && sample.isSimilar(it);
    }

    // ------------------------------------------------------------------ 파일

    static String read(File file) throws Exception { return Files.readString(file.toPath(), StandardCharsets.UTF_8); }

    /** tmp → fsync → 교체 (IO 스레드). */
    static void write(File file, String text) throws Exception {
        Path target = file.toPath(), tmp = target.resolveSibling(file.getName() + ".tmp");
        Files.writeString(tmp, text, StandardCharsets.UTF_8);
        try (var ch = java.nio.channels.FileChannel.open(tmp, StandardOpenOption.WRITE)) { ch.force(true); }
        try { Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); }
        catch (AtomicMoveNotSupportedException e) { Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING); }
    }
}
