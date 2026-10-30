import java.nio.file.*;
import java.util.*;
import java.util.regex.*;

/**
 * loot-info.sk 생성기: ItemsAdder 전리품(chacademi_loot.yml) + MythicMobs 몹 드롭(iadrop item:loot_*) + 야생 서식지(habitats.yml)
 * → /전리품 <이름> 이 "어떤 몹 · 어디서" 를 보여 주는 Skript. 사용: java -cp snakeyaml.jar Gen.java <wild plugins 폴더> <출력.sk>
 */
public class Gen {
    record Drop(String mobId, String mobName, String amount, String type, boolean shiny) {}

    static String strip(String s) { return s == null ? "" : s.replaceAll("[§&][0-9a-fk-orA-FK-OR]", "").trim(); }
    static String sk(String s) { return s.replace("\"", "\"\"").replace("%", "%%"); }

    @SuppressWarnings("unchecked")
    public static void main(String[] a) throws Exception {
        Path plugins = Path.of(a[0]);
        var yaml = new org.yaml.snakeyaml.Yaml();
        // 1) 전리품 이름·등급
        Map<String, Object> ia = yaml.load(Files.readString(plugins.resolve("ItemsAdder/contents/item/configs/chacademi_loot.yml")));
        var items = (Map<String, Map<String, Object>>) ia.get("items");
        var lootName = new LinkedHashMap<String, String>();
        var lootGrade = new HashMap<String, String>();
        for (var e : items.entrySet()) {
            lootName.put(e.getKey(), strip((String) e.getValue().get("name")));
            var lore = (List<String>) e.getValue().get("lore");
            lootGrade.put(e.getKey(), lore != null && lore.size() > 1 ? strip(lore.get(1)) : "");
        }
        // 2) 몹 드롭
        Pattern drop = Pattern.compile("iadrop item:(loot_[a-z0-9_]+) [^\"]*?(<random\\.(\\d+)to(\\d+)>|\\b(\\d+))\"");
        var drops = new LinkedHashMap<String, List<Drop>>();
        try (var files = Files.list(plugins.resolve("MythicMobs/Mobs"))) {
            for (Path f : files.filter(p -> p.toString().endsWith(".yml")).sorted().toList()) {
                Map<String, Object> mobs = yaml.load(Files.readString(f));
                if (mobs == null) continue;
                for (var m : mobs.entrySet()) {
                    String id = m.getKey();
                    if (!(m.getValue() instanceof Map<?, ?> def) || id.contains("Pet")) continue; // 교화한 펫은 제외
                    var skills = def.get("Skills") instanceof List<?> l ? l : List.of();
                    for (Object s : skills) {
                        var mt = drop.matcher(String.valueOf(s));
                        while (mt.find()) {
                            String amount = mt.group(3) != null ? (mt.group(3).equals(mt.group(4)) ? mt.group(3) : mt.group(3) + "~" + mt.group(4)) : mt.group(5);
                            drops.computeIfAbsent(mt.group(1), k -> new ArrayList<>()).add(
                                    new Drop(id, strip(String.valueOf(def.get("Display"))), amount, String.valueOf(def.get("Type")), id.endsWith("_shiny")));
                        }
                    }
                }
            }
        }
        // 3) 서식지
        Map<String, Object> hroot = yaml.load(Files.readString(plugins.resolve("ChAcademiaWildlife/habitats.yml")));
        var habitats = (Map<String, Map<String, Object>>) hroot.get("habitats");

        var out = new StringBuilder();
        out.append("# /전리품 <이름> — 어떤 몹이 떨구는지 · 그 몹이 어디서 나오는지 (자동 생성 ").append(java.time.LocalDate.now()).append(")\n");
        out.append("# 만드는 법: 야생 MythicMobs 드롭 + ChAcademiaWildlife habitats.yml + ItemsAdder chacademi_loot.yml. 설정이 바뀌면 다시 생성하고 /sk reload loot-info\n\n");
        out.append("on load:\n    delete {-lootinfo::*}\n    delete {-lootkey::*}\n    delete {-lootname::*}\n");
        int withDrop = 0;
        for (var e : lootName.entrySet()) {
            String id = e.getKey(), name = e.getValue();
            String key = name.replace(" ", "");
            out.append("    set {-lootkey::").append(sk(id)).append("} to \"").append(sk(key)).append("\"\n");
            out.append("    set {-lootname::").append(sk(id)).append("} to \"").append(sk(name)).append("\"\n");
            var lines = new ArrayList<String>();
            lines.add("&6── " + name + " &7(" + lootGrade.getOrDefault(id, "") + ") &6──");
            var list = drops.getOrDefault(id, List.of());
            if (list.isEmpty()) lines.add("&7몬스터 드롭이 아니에요 (다른 방법으로 얻어요).");
            else withDrop++;
            // 같은 몹(이로치 포함)은 한 줄로
            var byBase = new LinkedHashMap<String, List<Drop>>();
            for (var d : list) byBase.computeIfAbsent(d.mobId().replaceAll("_shiny$", ""), k -> new ArrayList<>()).add(d);
            for (var g : byBase.entrySet()) {
                var first = g.getValue().get(0);
                boolean normal = g.getValue().stream().anyMatch(d -> !d.shiny()), shiny = g.getValue().stream().anyMatch(Drop::shiny);
                lines.add("&f· &e" + first.mobName() + (shiny && !normal ? " &d(이로치)" : shiny ? " &7(이로치 포함)" : "") + " &7· " + first.amount() + "개");
                String base = g.getKey();
                Map<String, Object> h = base.startsWith("CA_Wild_") ? habitats.get(base.substring(8)) : null;
                if (h != null) {
                    var biomes = (List<String>) h.getOrDefault("biomes", List.of());
                    String time = switch (String.valueOf(h.get("time"))) { case "day" -> "낮"; case "night" -> "밤"; default -> "언제나"; };
                    String medium = "water".equals(h.get("medium")) ? "물속" : "지상";
                    var cond = new ArrayList<String>();
                    cond.add(time); cond.add(medium);
                    if (Boolean.TRUE.equals(h.get("near-water"))) cond.add("물가");
                    if (Boolean.TRUE.equals(h.get("thunder"))) cond.add("천둥 칠 때");
                    if (Boolean.TRUE.equals(h.get("full-moon"))) cond.add("보름달");
                    int minY = ((Number) h.getOrDefault("min-y", -60)).intValue(), maxY = ((Number) h.getOrDefault("max-y", 310)).intValue();
                    if (minY > -60 || maxY < 310) cond.add("높이 " + minY + "~" + maxY);
                    var seasons = (List<String>) h.getOrDefault("seasons", List.of());
                    if (!seasons.isEmpty()) cond.add("계절 " + String.join("·", seasons));
                    var structures = (List<String>) h.getOrDefault("structures", List.of());
                    if (!structures.isEmpty()) cond.add("구조물 근처 " + String.join("·", structures));
                    lines.add("    &7서식지: &f" + (biomes.isEmpty() ? "어디서나" : String.join(", ", biomes)));
                    lines.add("    &7조건: &f" + String.join(" · ", cond) + " &7· 희귀도 " + h.get("rarity"));
                } else if (base.startsWith("ChAcademiaWild")) {
                    lines.add("    &7서식지: &f자연에서 나오는 보통 " + first.mobName() + " &7(직접 잡으면 10%, 스포너·번식 몹 제외)");
                } else {
                    lines.add(base.startsWith("CA_Wild_") ? "    &7서식지: &f아직 자연에서 나오지 않아요" : "    &7서식지: &f정보 없음");
                }
            }
            for (String l : lines) out.append("    add \"").append(sk(l)).append("\" to {-lootinfo::").append(sk(id)).append("::*}\n");
        }
        out.append("""

                command /전리품 [<text>]:
                    description: 전리품이 어떤 몹에게서 · 어디서 나오는지
                    trigger:
                        if arg-1 is not set:
                            send "&e사용법: &f/전리품 [전리품 이름] &7(이름 일부만 써도 돼요)"
                            stop
                        set {_q} to arg-1
                        replace all " " with "" in {_q}
                        loop {-lootkey::*}:
                            if loop-value is {_q}:
                                set {_exact} to loop-index
                            else if loop-value contains {_q}:
                                add loop-index to {_hits::*}
                        if {_exact} is set:
                            set {_id} to {_exact}
                        else if size of {_hits::*} is 1:
                            set {_id} to {_hits::1}
                        else if size of {_hits::*} is 0:
                            send "&c'%arg-1%' 전리품을 찾지 못했어요."
                            stop
                        else:
                            send "&e여러 개가 있어요. 더 정확히 적어 주세요:"
                            set {_n} to 0
                            loop {_hits::*}:
                                add 1 to {_n}
                                if {_n} <= 12:
                                    send "&7· &f%{-lootname::%loop-value%}%"
                            if {_n} > 12:
                                send "&7… 외 %{_n} - 12%개"
                            stop
                        loop {-lootinfo::%{_id}%::*}:
                            send loop-value

                # 탭 자동완성 (SkBee): 붙여 쓴 이름으로 (띄어쓰기가 있으면 자동완성이 끊김). 친 글자가 들어간 것만.
                on tab complete of "/전리품":
                    set {_t} to tab arg-1
                    if {_t} is not set:
                        set {_t} to ""
                    replace all " " with "" in {_t}
                    loop {-lootkey::*}:
                        if loop-value contains {_t}:
                            add loop-value to {_out::*}
                    set tab completions for position 1 to {_out::*}
                """);
        Files.writeString(Path.of(a[1]), out.toString());
        System.out.println("전리품 " + lootName.size() + "개, 몬스터 드롭 있는 것 " + withDrop + "개");
    }
}
