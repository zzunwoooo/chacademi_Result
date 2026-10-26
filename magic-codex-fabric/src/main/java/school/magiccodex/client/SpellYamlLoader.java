package school.magiccodex.client;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;
import school.magiccodex.client.CodexData.Category;
import school.magiccodex.client.CodexData.Spell;

/** UTF-8 local catalog. A failed reload never publishes a partial catalog. */
public final class SpellYamlLoader {
    public static final int MAX_SPELLS = school.magiccodex.protocol.PermissionProtocol.MAX_PERMISSIONS;
    public record Result(List<Spell> spells, List<String> errors) {
        public Result { spells = List.copyOf(spells); errors = List.copyOf(errors); }
        public boolean success() { return errors.isEmpty(); }
    }
    public void installDefaults(Path directory) throws IOException {
        if(Files.exists(directory)) migrateHolyTamingDefault(directory);
        DefaultSpellMigration.install(directory);
    }
    private void migrateHolyTamingDefault(Path directory) throws IOException {
        Path file = directory.resolve("149_rainbow_collar.yml");
        if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS) || Files.size(file) > 65536) return;
        String old = Files.readString(file, StandardCharsets.UTF_8).replace("\r\n", "\n");
        String hash;
        try { hash = HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
                .digest(old.getBytes(StandardCharsets.UTF_8))); }
        catch (java.security.NoSuchAlgorithmException impossible) { throw new AssertionError(impossible); }
        if (!Set.of("18e5cd85e558d04c239ec8eb86c2d589236d996fd77d9786c793076a7417ac47", "a21f7fdb7b587ac125bd8e5c22a2536f4486c4378e9e5d2b40ae62bd25e291a5",
                "160fc443d2ce915d4e65198d6bec01b8bb603b510f6ee25ba7728d86c4ff01d1").contains(hash)) return; // + shipped 149 before the 2026-10-25 renumber
        try (var stream = SpellYamlLoader.class.getResourceAsStream("/assets/magiccodex/default-spells/233_rainbow_collar.yml") /* 2026-10-25 renumber: 149 -> 223; DefaultSpellMigration then renames the file */) {
            if (stream == null) throw new IOException("Missing HolyTaming default");
            byte[] updated = stream.readAllBytes();
            Path backup = directory.resolve("149_rainbow_collar.yml.pre-holytaming.bak");
            if (!Files.exists(backup)) Files.copy(file, backup);
            Path temporary = Files.createTempFile(directory, "holytaming-", ".tmp");
            try {
                Files.write(temporary, updated);
                try { Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); }
                catch (AtomicMoveNotSupportedException unsupported) {
                    Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING);
                }
            } finally { Files.deleteIfExists(temporary); }
        }
    }
    public Result load(Path directory) {
        var errors = new ArrayList<String>();
        var spells = new ArrayList<Spell>();
        var ids = new HashSet<String>();
        try (var entries = Files.list(directory)) {
            var files = entries.filter(p -> Files.isRegularFile(p, LinkOption.NOFOLLOW_LINKS))
                    .filter(p -> p.toString().toLowerCase(Locale.ROOT).matches(".*\\.ya?ml$"))
                    .sorted().limit(MAX_SPELLS + 1L).toList();
            if (files.size() > MAX_SPELLS)
                return new Result(List.of(), List.of("마법 파일은 최대 " + MAX_SPELLS + "개까지 읽을 수 있습니다."));
            for (Path file : files) {
                try {
                    if (Files.size(file) > 65536) throw new IllegalArgumentException("파일은 64KB 이하여야 합니다.");
                    Spell spell = parse(Files.readString(file, StandardCharsets.UTF_8));
                    if (!ids.add(spell.id())) throw new IllegalArgumentException("중복 id: " + spell.id());
                    spells.add(spell);
                } catch (Exception error) {
                    errors.add(file.getFileName() + ": " + error.getMessage());
                }
            }
        } catch (IOException error) { errors.add("마법 폴더 읽기 실패: " + error.getMessage()); }
        spells.sort(Comparator.comparingInt(Spell::order).thenComparing(Spell::id));
        return new Result(errors.isEmpty() ? spells : List.of(), errors);
    }
    static final Set<String> REVIEWED_ICON_IDS = Set.of("pressure_lance","healing_rain","bubble_aria","pressure_hammer","waterspout","concrete_curing","ripple_guard","bouncing_droplet");

    // Artwork assignments only: no catalogue registration, permission, cast, or enablement changes.
    static final Set<String> PREPARED_20261020_ICON_IDS = Set.of("earth_shaping","moss_coating","flint_sorting","dormant_seed","material_exchange","remembering_field","afterlife_gate","barrelroll_stitch","horizon_trim","folding_broom_wings","landing_airbrake","wingmate_towline","potion_effect_decant","twin_cup_draught","wandering_alembic","heartfold_wrapping","conversation_ripples","hushed_soles","thorn_manners","cinder_skipring","droplet_relay","folding_tideplane");

    // Explicit owner-checked replacements only; match the previous resource before overriding.
    static final Map<String,String> REPLACED_ICON_PREVIOUS_PATHS = Map.ofEntries(
            Map.entry("earth_shaping", "magiccodex:textures/spells/intermediate/earth_shaping.png"),
            Map.entry("moss_coating", "magiccodex:textures/spells/expansion_2/moss_coating.png"),
            Map.entry("flint_sorting", "magiccodex:textures/spells/expansion_2/flint_sorting.png"),
            Map.entry("dormant_seed", "magiccodex:textures/spells/expansion_2/dormant_seed.png"),
            Map.entry("material_exchange", "magiccodex:textures/spells/expansion_2/material_exchange.png"),
            Map.entry("remembering_field", "magiccodex:textures/spells/expansion_2/remembering_field.png"),
            Map.entry("afterlife_gate", "magiccodex:textures/spells/advanced/afterlife_gate.png"));

    public Spell parse(String source) {
        LoaderOptions options = new LoaderOptions();
        options.setAllowDuplicateKeys(false);
        options.setMaxAliasesForCollections(0);
        options.setNestingDepthLimit(12);
        options.setCodePointLimit(65536);
        Map<?, ?> root = mapping(new Yaml(new SafeConstructor(options)).load(source), "마법");
        // Retired rank/circle metadata is accepted only for old file compatibility; never used.
        keys(root, Set.of("id", "name", "description", "category", "rank", "circle", "icon", "permission", "discovery", "research", "cast", "order"));
        String id = string(root, "id", null, 64);
        if (!id.matches("[a-z0-9_-]+")) throw new IllegalArgumentException("id는 영문 소문자·숫자·_·-만 사용할 수 있습니다.");
        Category category;
        try { category = Category.valueOf(string(root, "category", null, 16).toUpperCase(Locale.ROOT)); }
        catch (IllegalArgumentException error) { throw new IllegalArgumentException("category: wind, fire, water, earth, light, dark 중 선택하세요."); }
        if (category == Category.ALL) throw new IllegalArgumentException("category에 all은 사용할 수 없습니다.");
        String icon = string(root, "icon", "", 256);
        // Preserve user-configured icons; fill only the eight reviewed legacy blank assignments.
        if (icon.isBlank() && REVIEWED_ICON_IDS.contains(id))
            icon = "magiccodex:textures/spells/reviewed_20261016/" + id + ".png";
        // Fill only blank assignments for these exact prepared IDs; preserve custom icons.
        if (icon.isBlank() && PREPARED_20261020_ICON_IDS.contains(id))
            icon = "magiccodex:textures/spells/reviewed_20261020/" + id + ".png";
        // Repoint the seven confirmed old defaults; keep unrelated/custom icon paths.
        if (REPLACED_ICON_PREVIOUS_PATHS.containsKey(id)
                && (icon.isBlank() || REPLACED_ICON_PREVIOUS_PATHS.get(id).equals(icon)))
            icon = "magiccodex:textures/spells/reviewed_20261020/" + id + ".png";
        icon = GimmickSpellIcons.resolve(id, MobSpellIcons.resolve(id, icon));
        if (!icon.isBlank() && (!icon.matches("[a-z0-9_.-]+:textures/[a-z0-9_./-]+\\.png") || icon.contains("..")))
            throw new IllegalArgumentException("icon은 namespace:textures/.../image.png 형식이어야 합니다.");
        String permission = string(root, "permission", null, 100);
        if (!permission.matches("[a-z0-9_.-]{1,100}")) throw new IllegalArgumentException("permission 형식이 올바르지 않습니다.");
        Map<?, ?> discovery = mapping(root.get("discovery"), "discovery");
        keys(discovery, Set.of("description"));
        Map<?, ?> cast = root.containsKey("cast") ? mapping(root.get("cast"), "cast") : Map.of();
        keys(cast, Set.of("command", "cooldown-seconds", "mana-cost"));
        String command = string(cast, "command", "", 256);
        if (command.startsWith("/") || command.contains("\n") || command.contains("\r"))
            throw new IllegalArgumentException("cast.command는 / 없이 한 줄로 작성하세요.");
        return new Spell(id, string(root, "name", null, 64), category,
                string(root, "description", "", 1024), string(discovery, "description", null, 512),
                string(root, "research", "", 512), decimal(cast, "cooldown-seconds", 0, 0, 86400), false,
                icon, permission, command, number(root, "order", 0, -1000000, 1000000), false,
                number(cast, "mana-cost", 0, 0, 1000000));
    }
    private static Map<?, ?> mapping(Object value, String field) {
        if (!(value instanceof Map<?, ?> map)) throw new IllegalArgumentException(field + " 항목은 key: value 형식이어야 합니다.");
        return map;
    }
    private static void keys(Map<?, ?> map, Set<String> allowed) {
        for (Object key : map.keySet()) if (!allowed.contains(key)) throw new IllegalArgumentException("알 수 없는 항목: " + key);
    }
    private static String string(Map<?, ?> map, String key, String fallback, int max) {
        Object raw = map.get(key);
        if (raw == null && !map.containsKey(key) && fallback != null) return fallback;
        if (!(raw instanceof String value)) throw new IllegalArgumentException(key + ": 문자열을 입력하세요.");
        value = value.strip();
        if ((value.isEmpty() && fallback == null) || value.length() > max || value.chars().anyMatch(c -> c < 32 && c != '\n' && c != '\r'))
            throw new IllegalArgumentException(key + ": 길이 또는 문자를 확인하세요 (최대 " + max + "자).");
        if (!Set.of("description", "research").contains(key) && (value.contains("\n") || value.contains("\r")))
            throw new IllegalArgumentException(key + ": 한 줄로 입력하세요.");
        return value.replace("\r\n", "\n").replace('\r', '\n');
    }
    private static double decimal(Map<?, ?> map,String key,double fallback,double min,double max) {
        if(!map.containsKey(key))return fallback;Object raw=map.get(key);
        if(!(raw instanceof Number n)||!Double.isFinite(n.doubleValue())||n.doubleValue()<min||n.doubleValue()>max)
            throw new IllegalArgumentException(key+": "+min+"~"+max+" 범위의 수를 입력하세요.");
        return n.doubleValue();
    }
    private static int number(Map<?, ?> map, String key, int fallback, int min, int max) {
        if (!map.containsKey(key)) return fallback;
        Object raw = map.get(key);
        if (!(raw instanceof Integer value) || value < min || value > max)
            throw new IllegalArgumentException(key + ": " + min + "~" + max + " 범위의 정수를 입력하세요.");
        return value;
    }
}
