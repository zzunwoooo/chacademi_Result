import java.nio.file.*;
import java.util.*;
import java.util.regex.*;

/**
 * loot-drops.sk 생성기 (2026-10-30 오너: 보통 몹도 떨구게, 10%).
 * MythicMobs 교화용 바닐라 몹(ChAcademiaWild*, 자연에서는 이로치 1/4096 로만 생김)의 iadrop 전리품을
 * "같은 종류의 보통 바닐라 몹을 플레이어가 잡으면 10%" 로 옮긴다. 개수는 원래 설정 그대로.
 * 사용: java -cp snakeyaml.jar Drops.java <wild plugins 폴더> <출력.sk> [확률%]
 */
public class Drops {
    @SuppressWarnings("unchecked")
    public static void main(String[] a) throws Exception {
        Path pl = Path.of(a[0]);
        String chance = a.length > 2 ? a[2] : "10";
        var y = new org.yaml.snakeyaml.Yaml();
        Pattern p = Pattern.compile("iadrop item:(loot_[a-z0-9_]+) [^\"]*?(?:<random\\.(\\d+)to(\\d+)>|\\b(\\d+))\"");
        var byType = new TreeMap<String, LinkedHashMap<String, int[]>>();
        try (var fs = Files.list(pl.resolve("MythicMobs/Mobs"))) {
            for (Path f : fs.filter(x -> x.toString().endsWith(".yml")).sorted().toList()) {
                Map<String, Object> mobs = y.load(Files.readString(f));
                if (mobs == null) continue;
                for (var m : mobs.entrySet()) {
                    if (!m.getKey().startsWith("ChAcademiaWild") || !(m.getValue() instanceof Map<?, ?> d)) continue;
                    String type = String.valueOf(d.get("Type")).toUpperCase(Locale.ROOT);
                    for (Object s : d.get("Skills") instanceof List<?> l ? l : List.of()) {
                        var mt = p.matcher(String.valueOf(s));
                        while (mt.find()) {
                            int min = mt.group(2) != null ? Integer.parseInt(mt.group(2)) : Integer.parseInt(mt.group(4));
                            int max = mt.group(3) != null ? Integer.parseInt(mt.group(3)) : min;
                            byType.computeIfAbsent(type, k -> new LinkedHashMap<>()).put(mt.group(1), new int[]{min, max});
                        }
                    }
                }
            }
        }
        var out = new StringBuilder();
        out.append("# 보통 바닐라 몹 전리품 (자동 생성 ").append(java.time.LocalDate.now()).append(", tools/loot-info/Drops.java)\n");
        out.append("# 플레이어가 잡은 보통 몹(MythicMobs 몹·스포너 몹 제외)이 종류별 전리품을 하나씩 ").append(chance).append("% 확률로 떨군다.\n");
        out.append("# 확률을 바꾸려면 아래 숫자만 고치고 /sk reload loot-drops\n\n");
        out.append("options:\n    chance: ").append(chance).append("\n\n");
        out.append("import:\n    io.lumine.mythic.bukkit.MythicBukkit\n\n");
        out.append("on load:\n    delete {-vloot::*}\n");
        int n = 0;
        for (var e : byType.entrySet())
            for (var d : e.getValue().entrySet()) {
                out.append("    add \"").append(d.getKey()).append("|").append(d.getValue()[0]).append("|").append(d.getValue()[1])
                        .append("\" to {-vloot::").append(e.getKey()).append("::*}\n");
                n++;
            }
        out.append("""

                on death:
                    victim is not a player
                    attacker is a player
                    set {_type} to victim.getType().name()
                    {-vloot::%{_type}%::*} is set
                    # MythicMobs 몹(교화 이로치·커스텀 몹)은 자기 드롭이 따로 있다
                    if MythicBukkit.inst().getAPIHelper().isMythicMob(victim) is true:
                        stop
                    # 스포너·알·번식으로 생긴 몹은 제외 (전리품 농장 막기)
                    set {_why} to victim.getEntitySpawnReason().name()
                    if {_why} is "SPAWNER" or "SPAWNER_EGG" or "BREEDING" or "EGG" or "DISPENSE_EGG" or "TRIAL_SPAWNER":
                        stop
                    set {_l} to location of victim
                    loop {-vloot::%{_type}%::*}:
                        chance of {@chance}%:
                            set {_p::*} to split loop-value at "|"
                            set {_n} to random integer between ({_p::2} parsed as integer) and ({_p::3} parsed as integer)
                            execute console command "iadrop item:%{_p::1}% %x-coordinate of {_l}% %y-coordinate of {_l}% %z-coordinate of {_l}% %world of {_l}% %{_n}%"
                """);
        Files.writeString(Path.of(a[1]), out.toString());
        System.out.println("몹 종류 " + byType.size() + "개, 전리품 줄 " + n + "개");
    }
}
