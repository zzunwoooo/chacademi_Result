package school.chacademia.tower;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ThreadLocalRandom;
import org.bukkit.Material;

/**
 * 증강 카드. 등급(0 일반 · 1 고급 · 2 유일 · 3 전설)에 따라 수치가 달라진다.
 * 카드 하나 = 종류(type) + 등급 + 그 판에서 정해진 값 (마법 카드는 마법 id).
 */
final class Cards {
    private Cards() {}

    /** 카드 종류. min = 이 등급부터 나옴, values = 등급별 수치 (0 일반 ~ 3 전설), weight = 뽑힐 비중. */
    enum Type {
        SPELL("마법 습득", Material.ENCHANTED_BOOK, 0, 30, null),
        INTELLIGENCE("지능", Material.AMETHYST_SHARD, 0, 14, new double[]{5, 10, 15, 25}),
        HEALTH("최대 체력", Material.RED_DYE, 0, 12, new double[]{4, 6, 10, 16}),
        HEAL("회복", Material.GLISTERING_MELON_SLICE, 0, 8, new double[]{0.3, 0.5, 0.75, 1.0}),
        ARMOR("방어구", Material.IRON_CHESTPLATE, 0, 9, null),
        FOOD("음식", Material.BREAD, 0, 5, null),
        POTION("포션", Material.SPLASH_POTION, 0, 7, null),
        ARTIFACT("탑의 유물", Material.HEART_OF_THE_SEA, 1, 6, null),
        POWER("마력 증폭", Material.BLAZE_POWDER, 0, 9, new double[]{0.06, 0.10, 0.15, 0.25}),
        GUARD("마력 방벽", Material.SHIELD, 0, 8, new double[]{0.04, 0.07, 0.10, 0.15}),
        LIFESTEAL("생명 흡수", Material.GHAST_TEAR, 1, 5, new double[]{0.03, 0.05, 0.08, 0.12}),
        SWIFT("바람걸음", Material.FEATHER, 0, 5, new double[]{0.05, 0.08, 0.12, 0.18}),
        VIGOR("승전의 숨결", Material.GOLDEN_CARROT, 0, 5, new double[]{0.05, 0.08, 0.12, 0.20}),
        HARVEST("사냥꾼의 갈증", Material.BONE, 0, 5, new double[]{0.5, 1, 1.5, 2.5}),
        FEATHER("불사조의 깃털", Material.TOTEM_OF_UNDYING, 2, 3, null),
        CURSED("저주받은 힘", Material.WITHER_ROSE, 1, 4, new double[]{0, 0.20, 0.30, 0.45}),
        LUCK("행운의 주사위", Material.EMERALD, 0, 4, new double[]{1, 1, 2, 3}),
        REROLL("다시 뽑기", Material.ENDER_EYE, 0, 3, new double[]{1, 1, 2, 3}),
        TRIAL("시련의 서약", Material.NETHER_STAR, 1, 4, null),
        GAMBLE("도박사의 내기", Material.GOLD_NUGGET, 0, 4, null);

        final String title; final Material icon; final int minGrade; final int weight; final double[] values;
        Type(String title, Material icon, int minGrade, int weight, double[] values) {
            this.title = title; this.icon = icon; this.minGrade = minGrade; this.weight = weight; this.values = values;
        }
        double value(int grade) { return values == null ? 0 : values[Math.clamp(grade, 0, 3)]; }
        String key() { return name().toLowerCase(Locale.ROOT); }
    }

    /** 탑의 유물 (탑 안에서만, 원래 아티팩트는 탑에 들어갈 때 잠시 해제). */
    enum Relic {
        FANG("흡혈귀의 송곳니", "준 피해의 6%만큼 체력을 회복해요."),
        HEART("거인의 심장", "최대 체력 +8 · 받는 피해 -5%"),
        SCYTHE("처형인의 낫", "체력 30% 이하인 몹에게 피해 +50%"),
        AEGIS("수호성의 방패", "층이 시작될 때 보호막 6을 얻어요."),
        ECHO("메아리 수정", "몹을 처치하면 주변 4칸 몹에게 그 몹 최대 체력의 15% 피해"),
        FROST("서리 핵", "맞힌 몹이 2초 동안 느려져요."),
        MASK("광기의 가면", "내 체력이 50% 이하일 때 피해 +35%"),
        EMBER("불씨 반지", "맞힌 몹을 3초 동안 불태워요."),
        HOURGLASS("멈춘 모래시계", "층이 시작되면 몹이 2초 동안 거의 움직이지 못해요.");
        final String title, desc;
        Relic(String title, String desc) { this.title = title; this.desc = desc; }
    }

    /** spell = 마법 카드의 마법 id (다른 카드는 ""). */
    record Card(Type type, int grade, String spell, String spellName) {
        double value() { return type.value(grade); }
    }

    static String pct(double v) { return Math.round(v * 100) + "%"; }

    /** 카드 설명 (여러 줄). */
    static List<String> describe(Card c) {
        double v = c.value();
        int g = c.grade();
        return switch (c.type()) {
            case SPELL -> List.of(c.spellName() + " 마법을 배워요.");
            case INTELLIGENCE -> List.of("탑 안 지능 +" + (int) v);
            case HEALTH -> List.of("최대 체력 +" + (int) v + " (하트 " + (v / 2) + "칸)");
            case HEAL -> List.of("체력을 " + pct(v) + " 회복해요.");
            case ARMOR -> List.of(armorName(g) + " 방어구 한 부위를 받아요.", "아직 없는 부위가 먼저 나와요.");
            case FOOD -> List.of(foodText(g));
            case POTION -> List.of(potionText(g));
            case ARTIFACT -> List.of(Relic.valueOf(c.spell()).desc, "탑을 나가면 사라져요.");
            case POWER -> List.of("주는 피해 +" + pct(v));
            case GUARD -> List.of("받는 피해 -" + pct(v) + " (최대 60%)");
            case LIFESTEAL -> List.of("준 피해의 " + pct(v) + "만큼 체력을 회복해요.");
            case SWIFT -> List.of("이동 속도 +" + pct(v));
            case VIGOR -> List.of("층을 깰 때마다 최대 체력의 " + pct(v) + " 회복");
            case HARVEST -> List.of("몹을 처치할 때마다 체력 " + v + " 회복");
            case FEATHER -> List.of("쓰러질 때 한 번 버티고", "체력 " + (g >= 3 ? "100%" : "50%") + "로 일어나요.");
            case CURSED -> List.of("주는 피해 +" + pct(v), "대신 최대 체력 -" + (g >= 3 ? 6 : 4));
            case LUCK -> List.of("다음 카드 " + (int) v + "번은 등급이 한 단계 올라가요.");
            case REROLL -> List.of("다시 뽑기 +" + (int) v + "회");
            case TRIAL -> List.of("다음 3개 층은 카드를 받지 않아요.", "3개 층을 모두 깨면 " + trialText(g) + "를 골라요.", "실패하면 아무것도 없어요.");
            case GAMBLE -> List.of("다음 층 몹 체력 +" + (g >= 2 ? "80%" : "50%") + ".", "깨면 카드를 " + (g >= 3 ? 3 : 2) + "번 골라요.");
        };
    }

    static String armorName(int g) { return switch (g) { case 0 -> "철"; case 1 -> "다이아몬드"; case 2 -> "보호 II 다이아몬드"; default -> "보호 III 네더라이트"; }; }

    static String foodText(int g) { return switch (g) { case 0 -> "빵 8개"; case 1 -> "스테이크 12개"; case 2 -> "황금 당근 16개"; default -> "황금 사과 4개 + 마법이 부여된 황금 사과 1개"; }; }

    static String potionText(int g) {
        return switch (g) {
            case 0 -> "치유의 투척 물약 2개";
            case 1 -> "치유 II 투척 물약 2개 + 재생 물약 1개";
            case 2 -> "치유 II 투척 물약 3개 + 힘 물약 + 저항 물약";
            default -> "치유 II 투척 물약 4개 + 힘 II 물약 + 저항 물약 + 재생 II 물약";
        };
    }

    static String trialText(int g) { return switch (g) { case 1 -> "유일 카드 2번"; case 2 -> "전설 카드 1번 + 유일 카드 1번"; default -> "전설 카드 2번"; }; }

    /** 한 층 카드 3장. 첫 카드(1층 클리어 전 = 시작 카드)는 기본 공격 마법 3장. */
    static List<Card> roll(Settings s, Magic magic, Run run, boolean first, int forcedMinGrade) {
        var rng = ThreadLocalRandom.current();
        var out = new ArrayList<Card>();
        var usedTypes = new HashSet<Type>();
        var usedSpells = new HashSet<>(run.spells);
        if (first) {
            var basics = magic.basicSpells(s);
            var others = magic.combatSpells(s);
            for (int i = 0; i < 3; i++) {
                var pool = !others.isEmpty() && rng.nextDouble() < s.firstCardOtherSpellChance ? others : basics;
                var sp = pickSpell(pool.isEmpty() ? others : pool, usedSpells, 99);
                if (sp == null) break;
                usedSpells.add(sp.id());
                out.add(new Card(Type.SPELL, gradeOfCircle(sp.circle()), sp.id(), sp.name()));
            }
            if (!out.isEmpty()) return out;
        }
        for (int i = 0; i < 3; i++) {
            int grade = rollGrade(s, run.floor);
            if (forcedMinGrade >= 0) grade = Math.max(grade, forcedMinGrade);
            if (run.luck > 0) { grade = Math.min(3, grade + 1); }
            Card c = null;
            for (int tries = 0; tries < 30 && c == null; tries++) {
                Type t = rollType(grade, usedTypes);
                if (t == null) break;
                if (t == Type.SPELL) {
                    var sp = pickSpell(magic.combatSpells(s), usedSpells, maxCircle(grade, run.floor));
                    if (sp == null) { usedTypes.add(t); continue; }
                    usedSpells.add(sp.id());
                    c = new Card(t, grade, sp.id(), sp.name());
                } else {
                    if (t == Type.ARTIFACT) {
                        var left = new ArrayList<Relic>();
                        for (var r : Relic.values()) if (!run.relics.contains(r.name())) left.add(r);
                        if (left.isEmpty()) { usedTypes.add(t); continue; }
                        var r = left.get(rng.nextInt(left.size()));
                        c = new Card(t, grade, r.name(), r.title);
                        usedTypes.add(t);
                        continue;
                    }
                    if (t == Type.FEATHER && run.feathers >= 2) { usedTypes.add(t); continue; }
                    if ((t == Type.TRIAL || t == Type.GAMBLE) && (run.trialLeft > 0 || run.floor >= s.floors - 2)) { usedTypes.add(t); continue; }
                    c = new Card(t, grade, "", "");
                }
                usedTypes.add(t);
            }
            if (c != null) out.add(c);
        }
        return out;
    }

    static int rollGrade(Settings s, int floor) {
        double total = 0;
        for (var g : s.grades) total += g.weightAt(floor, s.floors);
        double r = ThreadLocalRandom.current().nextDouble() * Math.max(total, 1e-9);
        for (int i = 0; i < s.grades.size(); i++) {
            r -= s.grades.get(i).weightAt(floor, s.floors);
            if (r <= 0) return i;
        }
        return 0;
    }

    private static Type rollType(int grade, java.util.Set<Type> used) {
        int total = 0;
        for (var t : Type.values()) if (t.minGrade <= grade && !used.contains(t)) total += t.weight;
        if (total == 0) return null;
        int r = ThreadLocalRandom.current().nextInt(total);
        for (var t : Type.values()) {
            if (t.minGrade > grade || used.contains(t)) continue;
            r -= t.weight;
            if (r < 0) return t;
        }
        return null;
    }

    /** 등급·층에 맞는 서클 상한: 일반 2 · 고급 3 · 유일 4 · 전설 제한 없음, 층이 30 넘으면 +1. */
    static int maxCircle(int grade, int floor) { return (grade >= 3 ? 99 : grade + 2) + (floor > 30 ? 1 : 0); }

    static int gradeOfCircle(int circle) { return Math.clamp(circle - 1, 0, 3); }

    private static Magic.SpellInfo pickSpell(List<Magic.SpellInfo> pool, java.util.Set<String> used, int maxCircle) {
        var options = new ArrayList<Magic.SpellInfo>();
        for (var sp : pool) if (!used.contains(sp.id()) && sp.circle() <= maxCircle) options.add(sp);
        if (options.isEmpty()) return null;
        // 높은 서클일수록 조금 더 잘 나오게 (같은 등급 안에서)
        int total = 0;
        for (var sp : options) total += 1 + sp.circle();
        int r = ThreadLocalRandom.current().nextInt(total);
        for (var sp : options) { r -= 1 + sp.circle(); if (r < 0) return sp; }
        return options.getLast();
    }
}
