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
        SPELL("마법 습득", Material.ENCHANTED_BOOK, 0, 45, null),
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
        VIGOR("승전의 숨결", Material.GOLDEN_CARROT, 0, 5, new double[]{0.06, 0.10, 0.15, 0.25}),
        HARVEST("사냥꾼의 갈증", Material.BONE, 0, 5, new double[]{0.5, 1, 1.5, 2.5}),
        FEATHER("불사조의 깃털", Material.TOTEM_OF_UNDYING, 2, 3, null),
        CURSED("저주받은 힘", Material.WITHER_ROSE, 1, 4, new double[]{0, 0.20, 0.30, 0.45}),
        LUCK("행운의 주사위", Material.EMERALD, 0, 4, new double[]{1, 1, 2, 3}),
        REROLL("다시 뽑기", Material.ENDER_EYE, 0, 3, new double[]{1, 1, 2, 3}),
        TRIAL("시련의 서약", Material.NETHER_STAR, 1, 4, null),
        GAMBLE("도박사의 내기", Material.GOLD_NUGGET, 0, 4, null),
        // ── 11/12 오너: 증강을 더 다채롭게 (치명타·되갚기 같은, 원래 게임에 없는 것들). 같은 종류는 겹치고, 서로 다른 종류끼리는 곱해진다. ──
        CRIT("치명의 눈", Material.SPECTRAL_ARROW, 0, 7, new double[]{0.10, 0.15, 0.22, 0.35}),          // 치명타 확률 (치명타 = 피해 2배)
        CRITDMG("날카로운 일격", Material.DIAMOND_SWORD, 1, 5, new double[]{0, 0.30, 0.50, 0.80}),       // 치명타 피해 배수 +
        VENGEANCE("되갚기", Material.IRON_SWORD, 0, 5, new double[]{3.0, 3.0, 3.5, 4.0}),               // 맞은 뒤 다음 공격 배수 (재사용 60·45·35·25초)
        FIRSTSTRIKE("선제 일격", Material.TRIDENT, 0, 5, new double[]{0.40, 0.70, 1.00, 1.50}),          // 체력이 가득 찬 몹에게
        BERSERK("궁지의 힘", Material.REDSTONE, 0, 5, new double[]{0.03, 0.05, 0.07, 0.10}),            // 잃은 체력 10%마다
        MOMENTUM("연쇄 처치", Material.FIRE_CHARGE, 0, 5, new double[]{0.12, 0.18, 0.25, 0.40}),        // 처치 뒤 5초, 3번까지 겹침
        OVERFLOW("넘치는 마력", Material.LAPIS_LAZULI, 0, 5, new double[]{0.15, 0.22, 0.32, 0.50}),      // 마나 80% 이상일 때
        MANA("마나 샘", Material.PRISMARINE_CRYSTALS, 0, 8, new double[]{20, 30, 45, 70}),              // 최대 마나 (회복도 같이)
        SIPHON("마력 수확", Material.EXPERIENCE_BOTTLE, 0, 5, new double[]{8, 12, 18, 28}),              // 처치마다 마나
        HASTE("시간 왜곡", Material.CLOCK, 0, 7, new double[]{15, 25, 40, 60}),                         // 시전 가속 (재사용 대기 감소)
        RELOAD("재장전", Material.CROSSBOW, 1, 4, new double[]{0, 0.25, 0.35, 0.50}),                   // 처치 때 마법 하나 재사용 초기화 확률
        GLASS("유리 대포", Material.GLASS, 1, 4, new double[]{0, 0.35, 0.50, 0.80}),                    // 주는 피해 +, 받는 피해 +25%
        RETALIATE("가시 갑옷", Material.CACTUS, 0, 5, new double[]{0.08, 0.12, 0.18, 0.28}),            // 맞으면 때린 몹 최대 체력의 % (합계 최대 35%, 수문장은 1/3)
        DODGE("잔상", Material.PHANTOM_MEMBRANE, 0, 5, new double[]{0.06, 0.09, 0.13, 0.20}),           // 공격을 피할 확률 (최대 45%)
        SECONDWIND("숨 고르기", Material.GLOW_BERRIES, 0, 6, new double[]{3, 5, 8, 12}),                // 3초 동안 안 맞으면 초당 1 회복, 층마다 이만큼까지
        BARRIER("마력 보호막", Material.AMETHYST_CLUSTER, 0, 5, new double[]{4, 6, 10, 16}),            // 층 시작 때 보호막
        CHAIN("연쇄 번개", Material.LIGHTNING_ROD, 0, 5, new double[]{0.20, 0.30, 0.45, 0.70}),         // 확률로 옆 몹에게 피해의 50%
        FINISHER("마무리", Material.NETHERITE_AXE, 1, 4, new double[]{0, 0.08, 0.12, 0.18}),            // 체력 이 비율 이하 몹 즉시 처치 (수문장은 절반)
        HUNTER("거물 사냥", Material.TARGET, 0, 5, new double[]{0.15, 0.25, 0.40, 0.60}),               // 정예·수문장에게
        COMBO("연타", Material.RABBIT_FOOT, 0, 5, new double[]{0.04, 0.06, 0.08, 0.12}),                // 같은 몹 연속 적중마다 (5번까지)
        DICE("주사위 일격", Material.PRISMARINE_SHARD, 0, 4, new double[]{1.6, 1.8, 2.1, 2.6}),          // 피해가 50% ~ 이 배수 사이 무작위
        PACT("피의 계약", Material.WITHER_SKELETON_SKULL, 2, 3, new double[]{0, 0, 0.70, 1.10}),        // 적중마다 체력 1 소모 (30% 까지만), 피해 +
        INTEREST("복리", Material.GOLD_INGOT, 0, 5, new double[]{0.010, 0.015, 0.020, 0.030}),          // 층을 깰 때마다 피해 + (영구 누적)
        SCHOLAR("학자의 서", Material.WRITABLE_BOOK, 0, 5, new double[]{0.5, 0.8, 1.2, 1.8}),                   // 층을 깰 때마다 지능 +
        OVERKILL("여파", Material.TNT, 1, 4, new double[]{0, 0.60, 0.80, 1.00}),                        // 처치하고 남은 피해가 옆 몹에게
        LASTSTAND("배수의 진", Material.NETHERITE_SCRAP, 0, 4, new double[]{0.20, 0.30, 0.40, 0.55});   // 체력 30% 이하일 때 받는 피해 -

        /** 되갚기 재사용 대기 (초), 등급별. 겹치면 가장 짧은 것에서 한 장마다 5초씩 더 줄어든다 (최소 15초). */
        static final int[] VENGEANCE_COOLDOWN = {60, 45, 35, 25};

        final String title; final Material icon; final int minGrade; final int weight; final double[] values;
        Type(String title, Material icon, int minGrade, int weight, double[] values) {
            this.title = title; this.icon = icon; this.minGrade = minGrade; this.weight = weight; this.values = values;
        }
        double value(int grade) { return values == null ? 0 : values[Math.clamp(grade, 0, 3)]; }
        String key() { return name().toLowerCase(Locale.ROOT); }
    }

    /** 탑의 유물 (탑 안에서만, 원래 아티팩트는 탑에 들어갈 때 잠시 해제). */
    enum Relic {
        FANG("흡혈귀의 송곳니", "몹에게 피해를 줄 때마다 체력을 회복해요. (생명 흡수 6%)"),
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

    /** 생명 흡수 1.00 일 때 한 층을 다 잡으면 차는 체력 (config balance.lifesteal-floor-heal 과 같은 기본값 — 카드 설명용). */
    static double LIFESTEAL_FLOOR_HEAL = 80;

    static String pct(double v) { return Math.round(v * 100) + "%"; }
    /** 2.0 → "2", 2.5 → "2.5" */
    static String num(double v) { return v == Math.rint(v) ? String.valueOf((long) v) : String.format(java.util.Locale.ROOT, "%.1f", v); }

    /** 카드 설명 (여러 줄). */
    static List<String> describe(Card c) { return describe(c, c.value()); }

    /** v = 보여 줄 수치 (카드 한 장이면 그 등급 값, 내 증강 창이면 지금까지 합계). */
    static List<String> describe(Card c, double v) {
        int g = c.grade();
        return switch (c.type()) {
            case SPELL -> List.of(c.spellName() + " 마법을 배워요.");
            case INTELLIGENCE -> List.of("탑 안 지능 +" + (int) v);
            case HEALTH -> List.of("최대 체력 +" + (int) v + " (하트 " + num(v / 2) + "칸)");
            case HEAL -> List.of("체력을 " + pct(v) + " 회복해요.");
            case ARMOR -> List.of(armorName(g) + " 방어구 한 부위를 받아요.", "아직 없는 부위가 먼저 나와요.");
            case FOOD -> List.of(foodText(g));
            case POTION -> List.of(potionText(g));
            case ARTIFACT -> List.of(Relic.valueOf(c.spell()).desc, "탑을 나가면 사라져요.");
            case POWER -> List.of("주는 피해 +" + pct(v));
            case GUARD -> List.of("받는 피해 -" + pct(v) + " (최대 60%)");
            case LIFESTEAL -> List.of("몹에게 피해를 줄 때마다 체력을 회복해요.", "한 층을 다 잡으면 약 " + num(Math.round(v * LIFESTEAL_FLOOR_HEAL * 10) / 10.0) + " 회복");
            case SWIFT -> List.of("이동 속도 +" + pct(v));
            case VIGOR -> List.of("층을 깰 때마다 최대 체력의 " + pct(v) + " 회복");
            case HARVEST -> List.of("몹을 처치할 때마다 체력 " + num(v) + " 회복");
            case FEATHER -> List.of("쓰러질 때 한 번 버티고", "체력 50%로 일어나요.");
            case CURSED -> List.of("주는 피해 +" + pct(v), "대신 최대 체력 -" + (g >= 3 ? 6 : 4));
            case LUCK -> List.of("다음 카드 " + (int) v + "번은 등급이 한 단계 올라가요.");
            case REROLL -> List.of("다시 뽑기 +" + (int) v + "회");
            case TRIAL -> List.of("다음 3개 층은 카드를 받지 않아요.", "3개 층을 모두 깨면 " + trialText(g) + "를 골라요.", "실패하면 아무것도 없어요.");
            case GAMBLE -> List.of("다음 층 몹 체력 +" + (g >= 2 ? "80%" : "50%") + ".", "깨면 카드를 " + (g >= 3 ? 3 : 2) + "번 골라요.");
            case CRIT -> List.of("치명타 확률 +" + pct(v), "치명타는 피해가 2배예요.");
            case CRITDMG -> List.of("치명타 피해 +" + pct(v));
            case VENGEANCE -> List.of("피해를 받은 뒤 다음 공격이 " + num(v) + "배", "다시 쓰려면 " + Type.VENGEANCE_COOLDOWN[Math.clamp(g, 0, 3)] + "초", "또 고르면 배수 +0.5 · 대기 -5초");
            case FIRSTSTRIKE -> List.of("체력이 가득 찬 몹에게 피해 +" + pct(v));
            case BERSERK -> List.of("잃은 체력 10%마다 피해 +" + pct(v));
            case MOMENTUM -> List.of("몹을 처치하면 5초 동안 피해 +" + pct(v), "3번까지 겹쳐요.");
            case OVERFLOW -> List.of("마나가 80% 이상이면 피해 +" + pct(v));
            case MANA -> List.of("최대 마나 +" + (int) v, "마나 회복도 그만큼 빨라져요.");
            case SIPHON -> List.of("몹을 처치할 때마다 마나 " + (int) v + " 회복");
            case HASTE -> List.of("시전 가속 +" + (int) v, "마법 재사용 대기가 줄어요.");
            case RELOAD -> List.of("몹을 처치하면 " + pct(v) + " 확률로", "마법 하나를 바로 다시 쓸 수 있어요.");
            case GLASS -> List.of("주는 피해 +" + pct(v), "대신 받는 피해 +25%");
            case RETALIATE -> List.of("맞으면 때린 몹에게", "그 몹 최대 체력의 " + pct(v) + " 피해", "수문장은 1/3 · 최대 35%");
            case DODGE -> List.of("공격을 " + pct(v) + " 확률로 피해요. (최대 45%)");
            case SECONDWIND -> List.of("3초 동안 맞지 않으면 체력이 차요.", "층마다 " + num(v) + "까지");
            case BARRIER -> List.of("층이 시작될 때 보호막 " + (int) v);
            case CHAIN -> List.of(pct(v) + " 확률로 가까운 다른 몹에게", "피해의 50%가 튀어요.");
            case FINISHER -> List.of("체력 " + pct(v) + " 이하인 몹을 바로 처치", "수문장은 절반만 적용돼요. (최대 25%)");
            case HUNTER -> List.of("정예·수문장에게 피해 +" + pct(v));
            case COMBO -> List.of("같은 몹을 이어서 맞힐 때마다", "피해 +" + pct(v) + " (5번까지)");
            case DICE -> List.of("피해가 매번 50% ~ " + pct(v), "사이에서 정해져요.", "또 고르면 윗값 +40%");
            case PACT -> List.of("주는 피해 +" + pct(v), "맞힐 때마다 체력 1을 잃어요.", "체력 30% 아래로는 안 내려가요.");
            case INTEREST -> List.of("층을 깰 때마다 피해 +" + num(v * 100) + "%", "탑을 나갈 때까지 쌓여요.");
            case SCHOLAR -> List.of("층을 깰 때마다 지능 +" + num(v));
            case OVERKILL -> List.of("몹을 처치하고 남은 피해의 " + pct(v) + "가", "가까운 다른 몹에게 넘어가요.");
            case LASTSTAND -> List.of("체력이 30% 이하일 때", "받는 피해 -" + pct(v));
        };
    }

    // [기능:탑내증강 시작]
    /** 내 증강 창의 한 칸: 이름 · 고른 횟수 · 등급 · 그림 · 지금까지 합친 효과. */
    record Owned(String name, int count, int grade, String icon, Material item, List<String> lines) {}

    /** 지금까지 얻은 것 전부: 요약 → 증강(고른 순서, 같은 종류는 한 칸에 합계) → 유물 → 마법. 바로 쓰고 사라지는 카드(회복·방어구·음식·포션 등)는 빠진다. */
    static List<Owned> owned(Settings s, Run run) {
        var out = new ArrayList<Owned>();
        var sum = new ArrayList<String>();
        sum.add("지능 " + (int) (s.startIntelligence + run.intelligence) + " · 체력 " + num(20 + run.maxHealthBonus));
        sum.add("피해 +" + pct(run.damageBonus + run.interestBonus) + " · 마나 " + (int) (s.startMana + run.manaBonus));
        double taken = (1 - run.guard) * (1 + run.glassTaken);
        sum.add(taken <= 1 ? "받는 피해 -" + pct(1 - taken) : "받는 피해 +" + pct(taken - 1));
        if (run.crit > 0) sum.add("치명타 " + pct(run.crit) + " · " + num(2 + run.critDamage) + "배");
        out.add(new Owned("지금 능력", 1, 3, "", Material.NETHER_STAR, sum));
        for (var e : run.taken.entrySet()) {
            Type t;
            try { t = Type.valueOf(e.getKey().toUpperCase(Locale.ROOT)); } catch (IllegalArgumentException ex) { continue; }
            int g = run.takenGrade.getOrDefault(e.getKey(), 0);
            double v = run.takenSum.getOrDefault(e.getKey(), 0.0);
            var card = new Card(t, g, "", "");
            List<String> lines = switch (t) {
                case SPELL, ARTIFACT, HEAL, ARMOR, FOOD, POTION, LUCK, REROLL, TRIAL, GAMBLE -> null;
                case GUARD -> List.of("받는 피해 -" + pct(Math.min(0.6, run.guard)) + " (최대 60%)");
                case RETALIATE -> describe(card, Math.min(0.35, v));
                case LIFESTEAL -> describe(card, Math.min(s.bal("lifesteal-cap", 0.15), run.lifesteal));
                case VIGOR -> describe(card, Math.min(s.bal("vigor-cap", 0.5), v));
                case FEATHER -> List.of(run.feathers > 0 ? "남은 깃털 " + run.feathers + "개" : "깃털을 다 썼어요.", "쓰러질 때 체력 50%로 일어나요.");
                case CURSED -> List.of("주는 피해 +" + pct(v), "대신 최대 체력 -" + num(run.cursedLoss));
                case GLASS -> List.of("주는 피해 +" + pct(v), "대신 받는 피해 +" + pct(run.glassTaken));
                case CRIT -> List.of("치명타 확률 " + pct(Math.min(1, v)), "치명타는 피해가 " + num(2 + run.critDamage) + "배예요.");
                case VENGEANCE -> List.of("피해를 받은 뒤 다음 공격이 " + num(run.vengeMult) + "배", "다시 쓰려면 " + run.vengeCooldownMs / 1000 + "초");
                case DICE -> List.of("피해가 매번 50% ~ " + pct(run.diceMax), "사이에서 정해져요.");
                case DODGE -> describe(card, Math.min(0.45, v));
                case FINISHER -> describe(card, Math.min(0.25, v));
                case CHAIN, RELOAD, OVERKILL -> describe(card, Math.min(1, v));
                case LASTSTAND -> describe(card, Math.min(0.8, v));
                case INTEREST -> List.of("층을 깰 때마다 피해 +" + num(v * 100) + "%", "지금까지 +" + pct(run.interestBonus));
                default -> describe(card, v);
            };
            if (lines == null) continue;
            out.add(new Owned(t.title, e.getValue(), g, iconOf(t), t.icon, lines));
        }
        for (String id : run.relicOrder) {
            Relic r;
            try { r = Relic.valueOf(id); } catch (IllegalArgumentException ex) { continue; }
            out.add(new Owned(r.title, 1, run.relicGrades.getOrDefault(id, 2), "relic_" + id.toLowerCase(Locale.ROOT), Type.ARTIFACT.icon, List.of(r.desc)));
        }
        for (String id : run.spells)
            out.add(new Owned(run.spellNames.getOrDefault(id, id), 1, run.spellGrades.getOrDefault(id, 0), "", Type.SPELL.icon, List.of("탑에서 배운 마법")));
        return out;
    }

    /** 탑 그림이 있는 카드 (클라 textures/gui/tower). 없으면 "" → 아이템 그림. */
    static String iconOf(Type t) {
        return switch (t) {
            case INTELLIGENCE, HEALTH, HEAL, POWER, GUARD, FEATHER, CURSED, TRIAL, GAMBLE -> "icon_" + t.key();
            default -> "";
        };
    }
    // [기능:탑내증강 끝]

    static String armorName(int g) { return switch (g) { case 0 -> "철"; case 1 -> "다이아몬드"; case 2 -> "보호 II 다이아몬드"; default -> "보호 III 네더라이트"; }; }

    /** 탑 안은 배고픔으로 체력이 차지 않아 빵·스테이크는 쓸모가 없었다 (11/12 밸런스 점검) → 먹으면 바로 효과가 있는 황금 사과로. */
    static String foodText(int g) { return switch (g) { case 0 -> "황금 사과 1개"; case 1 -> "황금 사과 2개"; case 2 -> "황금 사과 3개"; default -> "황금 사과 4개 + 마법이 부여된 황금 사과 1개"; }; }

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
            // 첫 카드는 반드시 마법 3장: 기본 공격 마법에서 뽑고, 모자라면 다른 공격 마법으로 채운다
            for (int i = 0; i < 3; i++) {
                var pool = !others.isEmpty() && rng.nextDouble() < s.firstCardOtherSpellChance ? others : basics;
                var sp = pickSpell(pool.isEmpty() ? others : pool, usedSpells, 99, s.startMana);
                if (sp == null) sp = pickSpell(others, usedSpells, 99, s.startMana);
                if (sp == null) sp = pickSpell(basics, usedSpells, 99, s.startMana);
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
                    var sp = pickSpell(magic.combatSpells(s), usedSpells, maxCircle(grade, run.floor), s.startMana + run.manaBonus);
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
                    if (useless(s, t, run)) { usedTypes.add(t); continue; }
                    if ((t == Type.TRIAL || t == Type.GAMBLE) && (run.trialLeft > 0 || run.gambleArmed || run.floor >= s.floors - 2)) { usedTypes.add(t); continue; }
                    c = new Card(t, grade, "", "");
                }
                usedTypes.add(t);
            }
            if (c != null) out.add(c);
        }
        return out;
    }

    /** 지금 골라도 아무 효과가 없는 카드 (이미 상한이거나, 짝이 되는 증강이 없음). */
    static boolean useless(Settings s, Type t, Run run) {
        return switch (t) {
            case CRIT -> run.crit >= 1;
            case CRITDMG -> run.crit <= 0;
            case DODGE -> run.dodge >= 0.45;
            case GUARD -> run.guard >= 0.6;
            case LIFESTEAL -> run.lifesteal >= s.bal("lifesteal-cap", 0.15);
            case VIGOR -> run.vigor >= s.bal("vigor-cap", 0.5);
            case RELOAD -> run.spells.size() < 2 || run.reload >= 1;
            case CHAIN -> run.chain >= 1;
            case FINISHER -> run.finisher >= 0.25;
            case OVERKILL -> run.overkill >= 1;
            case LASTSTAND -> run.lastStand >= 0.8;
            case PACT -> run.pact;
            case RETALIATE -> run.retaliate >= 0.35;
            default -> false;
        };
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

    /** maxMana: 지금 탑 안 최대 마나. 그보다 비싼 마법은 배워도 못 쓰니 카드로 나오지 않는다 (11/12 밸런스 점검: 마나 100 고정이라 5서클·4서클 일부가 죽은 카드였음). */
    private static Magic.SpellInfo pickSpell(List<Magic.SpellInfo> pool, java.util.Set<String> used, int maxCircle, double maxMana) {
        var options = new ArrayList<Magic.SpellInfo>();
        for (var sp : pool) if (!used.contains(sp.id()) && sp.circle() <= maxCircle && sp.cost() <= maxMana) options.add(sp);
        if (options.isEmpty()) return null;
        // 높은 서클일수록 조금 더 잘 나오게 (같은 등급 안에서)
        int total = 0;
        for (var sp : options) total += 1 + sp.circle();
        int r = ThreadLocalRandom.current().nextInt(total);
        for (var sp : options) { r -= 1 + sp.circle(); if (r < 0) return sp; }
        return options.getLast();
    }
}
