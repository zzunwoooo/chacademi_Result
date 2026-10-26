package kr.chacademy.npc.config;

import kr.chacademy.npc.core.Defs.Button;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * config.yml 값을 한 번에 읽어 둔다. /서버관리 새로고침 NPC 때 새로 만든다.
 */
public final class Settings {

    // OpenAI
    public final String apiKey;
    public final String baseUrl;
    public final String model;
    public final String reasoningEffort;
    public final int maxOutputTokens;
    public final int reducedOutputTokens;
    public final int timeoutSeconds;
    public final int stallSeconds;
    public final int maxConcurrent;
    public final int maxQueue;
    public final int circuitFailures;
    public final int circuitCooloffSeconds;
    public final double priceInput;
    public final double priceCached;
    public final double priceOutput;
    public final boolean moderationEnabled;
    public final String moderationModel;

    public final int hardTimeoutSeconds;

    // 선물
    public final int giftLoved;
    public final int giftLiked;
    public final int giftDisliked;
    public final int giftNeutral;
    /** 좋아하는 선물(gift_likes.liked)일 때 등급별 점수 — 일반·고급·유일·전설 (2026-10-23). */
    public final Map<String, Integer> giftGrades;
    /** 좋아하지 않는 선물일 때 점수. */
    public final int giftNotLiked;

    // 마법 발견 여부 권한 (MagicDiscovery)
    public final String discoveryPermission;

    // 예산
    public final double budgetTotalUsd;
    /** 오너 결정 6 (2026-10-21): 전체·하루 금액 한도는 끔(false). 켜면 예전처럼 total-usd 를 기간에 나눠 하루 한도로 쓴다. */
    public final boolean budgetLimitEnabled;
    public final LocalDate budgetStartDate;
    public final int budgetDays;
    public final double testDailyUsd;
    public final int perPlayerDailyCalls;
    public final double reduceAtPercent;
    public final ZoneId zone;

    // 대화
    public final int inputMaxLength;
    public final int lineMaxLength;
    public final int memoMaxLength;
    public final int cooldownSeconds;
    public final int idleTimeoutSeconds;
    public final double maxDistance;
    public final double talkStartDistance;
    public final int recentTurns;
    public final int lastChatTurns;
    public final int lastChatExpireDays;
    public final int memoMaxLines;
    public final List<Button> buttons;
    public final boolean buttonCacheEnabled;
    public final int buttonCacheVariants;
    public final boolean buttonCacheWarmup;
    public final String defaultStoryChapter;

    // 퀘스트·힌트
    public final int questMinScore;
    public final int questCooldownMinutes;
    public final int questMaxActive;
    public final double questChance;
    public final double questGoodMoodChance;
    public final int hintDailyPerPlayer;

    // 호감도 (임시 연결부)
    public final int affinityDefaultScore;
    /** #83 호의적 AI 대화 판정 기준 (AI 에게 주는 문구). 비우면 대화 호감도 보상을 쓰지 않음. */
    public final String chatRewardCriteria;

    // 장난 누적
    public final int strikeWindowMinutes;
    public final int strikeMax;
    public final int strikeLockMinutes;
    public final int strikeAffinityPenalty;

    // 이동
    public final double walkRadius;
    public final int staggerSeconds;
    public final double wanderPlayerRadius;
    public final int wanderMinSeconds;
    public final int wanderMaxSeconds;
    public final float walkSpeed;
    public final int walkGiveUpSeconds;

    // 소문
    public final int rumorDelayMinHours;
    public final int rumorDelayMaxHours;
    public final int rumorMaxInPrompt;
    /** false 면 NPC끼리 잡담에 소문을 넣지 않는다 (잡담 자체는 ambient.enabled). */
    public final boolean publicRumors;
    public final int jealousyMinScore;
    public final double jealousyChance;
    public final double jealousyPromiseChance;

    // NPC끼리 잡담
    public final boolean ambientEnabled;
    public final int ambientIntervalMinutes;
    public final double ambientPlayerRadius;

    // 분위기 노트
    public final boolean vibeEnabled;
    public final List<Integer> vibeRunHours;
    public final int vibeMinDistinctPlayers;
    public final int vibeMaxNotes;
    public final int vibeExpireDays;
    public final boolean vibeAutoApprove;
    public final int vibeMaxLogLines;

    public final String worldLore;
    public final Map<String, List<String>> defaultFallbacks;
    public final boolean debug;

    public Settings(FileConfiguration c) {
        String key = c.getString("openai.api-key", "");
        String env = System.getenv("CHACANPC_OPENAI_KEY");
        if ((key == null || key.isBlank() || key.startsWith("sk-여기에")) && env != null && !env.isBlank()) {
            key = env;
        }
        apiKey = key == null ? "" : key.trim();
        baseUrl = trimSlash(c.getString("openai.base-url", "https://api.openai.com/v1"));
        model = c.getString("openai.model", "gpt-6-luna");
        reasoningEffort = c.getString("openai.reasoning-effort", "none");
        maxOutputTokens = c.getInt("openai.max-output-tokens", 250);
        reducedOutputTokens = c.getInt("openai.reduced-output-tokens", 160);
        timeoutSeconds = c.getInt("openai.timeout-seconds", 8);
        stallSeconds = c.getInt("openai.stall-seconds", 3);
        maxConcurrent = Math.max(1, c.getInt("openai.max-concurrent", 20));
        maxQueue = Math.max(1, c.getInt("openai.max-queue", maxConcurrent * 2));
        circuitFailures = Math.max(0, c.getInt("openai.circuit.failures", 5));
        circuitCooloffSeconds = Math.max(5, c.getInt("openai.circuit.cooloff-seconds", 30));
        priceInput = c.getDouble("openai.price.input-per-million", 0.10);
        priceCached = c.getDouble("openai.price.cached-input-per-million", 0.01);
        priceOutput = c.getDouble("openai.price.output-per-million", 0.50);
        moderationEnabled = c.getBoolean("openai.moderation.enabled", true);
        moderationModel = c.getString("openai.moderation.model", "omni-moderation-latest");

        hardTimeoutSeconds = Math.max(timeoutSeconds + 5, c.getInt("openai.hard-timeout-seconds", 45));

        giftLoved = c.getInt("gifts.loved", 6);
        giftLiked = c.getInt("gifts.liked", 3);
        giftDisliked = c.getInt("gifts.disliked", -3);
        giftNeutral = c.getInt("gifts.neutral", 1);
        Map<String, Integer> grades = new HashMap<>(Map.of("일반", 1, "고급", 3, "유일", 7, "전설", 15));
        ConfigurationSection gs = c.getConfigurationSection("gifts.grades");
        if (gs != null) {
            for (String k : gs.getKeys(false)) {
                grades.put(k, gs.getInt(k));
            }
        }
        giftGrades = Map.copyOf(grades);
        giftNotLiked = c.getInt("gifts.not-liked", -1);
        discoveryPermission = c.getString("discovery-permission", "magic.learned.%s");

        zone = ZoneId.of(c.getString("budget.timezone", "Asia/Seoul"));
        budgetTotalUsd = c.getDouble("budget.total-usd", 170);
        budgetLimitEnabled = c.getBoolean("budget.limit-enabled", false);
        LocalDate start;
        try {
            start = LocalDate.parse(c.getString("budget.start-date", "2026-10-26"));
        } catch (Exception ex) {
            start = LocalDate.now(zone).plusDays(30);
        }
        budgetStartDate = start;
        budgetDays = Math.max(1, c.getInt("budget.days", 21));
        testDailyUsd = c.getDouble("budget.test-daily-usd", 2.0);
        perPlayerDailyCalls = c.getInt("budget.per-player-daily-calls", 60);
        reduceAtPercent = c.getDouble("budget.reduce-at-percent", 80);

        inputMaxLength = c.getInt("dialogue.input-max-length", 100);
        lineMaxLength = c.getInt("dialogue.line-max-length", 120);
        memoMaxLength = c.getInt("dialogue.memo-max-length", 60);
        cooldownSeconds = c.getInt("dialogue.cooldown-seconds", 3);
        idleTimeoutSeconds = c.getInt("dialogue.idle-timeout-seconds", 60);
        maxDistance = c.getDouble("dialogue.max-distance", 8);
        talkStartDistance = c.getDouble("dialogue.talk-start-distance", 6);
        recentTurns = c.getInt("dialogue.recent-turns", 3);
        lastChatTurns = c.getInt("dialogue.last-chat-turns", 4);
        lastChatExpireDays = c.getInt("dialogue.last-chat-expire-days", 7);
        memoMaxLines = c.getInt("dialogue.memo-max-lines", 5);
        defaultStoryChapter = c.getString("dialogue.default-story-chapter", "1장");
        List<Button> btns = new ArrayList<>();
        for (Map<?, ?> m : c.getMapList("dialogue.buttons")) {
            Object id = m.get("id");
            Object label = m.get("label");
            if (id != null && label != null) {
                btns.add(new Button(id.toString(), label.toString()));
            }
        }
        if (btns.isEmpty()) {
            btns.add(new Button("daily", "요즘 뭐 해?"));
            btns.add(new Button("help", "도울 일 있어?"));
            btns.add(new Button("magic", "마법 얘기 해줘"));
        }
        buttons = List.copyOf(btns);
        buttonCacheEnabled = c.getBoolean("dialogue.button-cache.enabled", true);
        buttonCacheVariants = c.getInt("dialogue.button-cache.variants", 4);
        buttonCacheWarmup = c.getBoolean("dialogue.button-cache.warmup", true);

        questMinScore = c.getInt("quests.min-score", 20);
        questCooldownMinutes = c.getInt("quests.cooldown-minutes", 120);
        questMaxActive = c.getInt("quests.max-active", 3);
        questChance = c.getDouble("quests.chance", 0.20);
        questGoodMoodChance = c.getDouble("quests.good-mood-chance", 0.35);
        hintDailyPerPlayer = c.getInt("hints.daily-per-player", 3);

        affinityDefaultScore = c.getInt("affinity.default-score", 25);
        chatRewardCriteria = c.getString("affinity.chat-reward-criteria", DEFAULT_CHAT_REWARD_CRITERIA).trim();

        strikeWindowMinutes = c.getInt("strikes.window-minutes", 10);
        strikeMax = c.getInt("strikes.max", 3);
        strikeLockMinutes = c.getInt("strikes.lock-minutes", 10);
        strikeAffinityPenalty = c.getInt("strikes.affinity-penalty", 3);

        walkRadius = c.getDouble("movement.walk-radius", 48);
        staggerSeconds = c.getInt("movement.stagger-seconds", 20);
        wanderPlayerRadius = c.getDouble("movement.wander-player-radius", 32);
        wanderMinSeconds = c.getInt("movement.wander-min-seconds", 8);
        wanderMaxSeconds = Math.max(wanderMinSeconds, c.getInt("movement.wander-max-seconds", 15));
        walkSpeed = (float) c.getDouble("movement.speed", 1.0);
        walkGiveUpSeconds = c.getInt("movement.give-up-seconds", 40);

        rumorDelayMinHours = c.getInt("rumors.delay-min-hours", 12);
        rumorDelayMaxHours = Math.max(rumorDelayMinHours, c.getInt("rumors.delay-max-hours", 24));
        rumorMaxInPrompt = c.getInt("rumors.max-in-prompt", 2);
        publicRumors = c.getBoolean("social.public-rumors", true);
        jealousyMinScore = c.getInt("rumors.jealousy-min-score", 60);
        jealousyChance = c.getDouble("rumors.jealousy-chance", 0.4);
        jealousyPromiseChance = c.getDouble("rumors.jealousy-promise-chance", 0.8);

        ambientEnabled = c.getBoolean("ambient.enabled", true);
        ambientIntervalMinutes = Math.max(1, c.getInt("ambient.interval-minutes", 10));
        ambientPlayerRadius = c.getDouble("ambient.player-radius", 16);

        vibeEnabled = c.getBoolean("vibe.enabled", true);
        List<Integer> hours = c.getIntegerList("vibe.run-hours");
        vibeRunHours = hours.isEmpty() ? List.of(5, 17) : List.copyOf(hours);
        vibeMinDistinctPlayers = c.getInt("vibe.min-distinct-players", 5);
        vibeMaxNotes = c.getInt("vibe.max-notes", 10);
        vibeExpireDays = c.getInt("vibe.expire-days", 3);
        vibeAutoApprove = c.getBoolean("vibe.auto-approve", false);
        vibeMaxLogLines = c.getInt("vibe.max-log-lines", 1500);

        worldLore = c.getString("world-lore", "");
        Map<String, List<String>> fb = new HashMap<>();
        ConfigurationSection s = c.getConfigurationSection("default-fallback-lines");
        if (s != null) {
            for (String k : s.getKeys(false)) {
                fb.put(k, s.getStringList(k));
            }
        }
        defaultFallbacks = fb;
        debug = c.getBoolean("debug", false);
    }

    /** 오늘이 운영 몇 번째 날인지 (시작 전이면 음수). */
    public int dayIndex(LocalDate today) {
        return (int) java.time.temporal.ChronoUnit.DAYS.between(budgetStartDate, today);
    }

    public static final String DEFAULT_CHAT_REWARD_CRITERIA =
            "학생의 이번 말이 너의 성격·좋아하는 것에 잘 맞아 정말 기분이 좋아졌다(진심 어린 칭찬·공감·네가 좋아하는 이야기 등). 평범한 인사·질문·부탁, 무례하거나 억지스러운 말은 해당하지 않는다.";

    public LocalDate today() {
        return LocalDate.now(zone);
    }

    private static String trimSlash(String s) {
        if (s == null) {
            return "https://api.openai.com/v1";
        }
        return s.endsWith("/") ? s.substring(0, s.length() - 1) : s;
    }
}
