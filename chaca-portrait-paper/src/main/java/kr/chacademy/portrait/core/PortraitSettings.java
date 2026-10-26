package kr.chacademy.portrait.core;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;

import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** config.yml 스냅샷 (불변). /서버관리 새로고침 초상화 때 새로 만든다. */
public final class PortraitSettings {

    public final String serverId;
    /** config.yml에 server-id가 직접 적혀 있는지. 비어 있으면 serverId는 "school"로 대신한다 (SQLite 단독 서버용; 공유 DB에서는 생성 차단). */
    public final boolean serverIdSet;
    public final boolean enabled;
    public final String apiKey;
    public final String baseUrl;
    public final int timeoutSeconds;
    public final boolean sendUserHash;

    public final String imageModel;
    public final String quality;
    public final String size;
    public final String background;
    public final String inputFidelity;
    public final String imageModeration;
    public static final List<String> IMAGE_MODELS = List.of("gpt-image-2.5-sunburst", "gpt-image-2.5-flare", "gpt-image-2", "gpt-image-1.5");
    public static boolean nativeTransparent(String model) {
        return "gpt-image-2.5-sunburst".equals(model) || "gpt-image-2.5-flare".equals(model);
    }
    public static boolean needsLocalMatte(String model) { return "gpt-image-2".equals(model); }
    public static boolean supportsQuality(String model, String quality) {
        return List.of("low", "medium", "high", "auto").contains(quality)
                || nativeTransparent(model) && List.of("xhigh", "max").contains(quality);
    }
    public String requestBackground(String model) { return nativeTransparent(model) ? "transparent" : needsLocalMatte(model) ? "opaque" : background; }
    public final List<String> references;
    public final boolean requireTransparent;

    public final boolean describeEnabled;
    public final String describeModel;
    public final int describeMaxTokens;

    public final boolean moderationEnabled;
    public final String moderationModel;

    public final int workers;
    public final int maxWaiting;

    public final boolean autoFirstJoin;
    /** 자동 생성을 하는 server-id 목록 (auto.server-ids, 기본 [school]). 튜토리얼 서버 분리(메모 #3) 뒤 오너가 정한다. */
    public final List<String> autoServerIds;
    public final int autoDelaySeconds;
    public final int autoMaxAttempts;

    public final String rerollItemId;
    public final int promptTimeoutSeconds;
    public final int rerollCooldownSeconds;
    public final int rerollDailyLimit;

    public final double budgetUsd;
    public final Map<String, CostModel.Prices> prices;
    public final CostModel.Estimate estimate;
    public final CostModel.Estimate estimate25;

    public final String promptBase;
    public final String promptAppearance;
    public final String promptRequest;
    public final String promptDescribe;

    private final Map<String, String> messages = new HashMap<>();

    /** 나중에 추가된 안내문의 기본값. 운영 중인 config.yml에 키가 없어도 빈 메시지가 나가지 않게 한다. */
    private static final Map<String, String> DEFAULT_MESSAGES = Map.of(
            "reroll-consumed", "§b[일러스트] §7그림을 완성하지 못했어요. 리롤 아이템은 사용 처리되었습니다. 문의는 관리자에게 해 주세요.",
            "reroll-notice", "§b[일러스트] §e생성 시도가 실패할 경우에도 아이템은 사라집니다. 관련 문의 사항은 관리자를 찾아주세요.",
            "reroll-unavailable", "§b[일러스트] §7지금은 요청을 확인할 수 없어요. 잠시 후 다시 시도해 주세요. (아이템은 그대로예요)",
            "reroll-queued", "§b[일러스트] §f다시 그리기 대기 {position}번째예요. 약 {minutes}분 뒤에 완성돼요. 다 되면 알려드릴게요.",
            "budget", "§b[일러스트] §7이번 시즌 일러스트 예산이 모두 사용됐어요.");

    public PortraitSettings(FileConfiguration c) {
        this(c, System::getenv);
    }

    PortraitSettings(FileConfiguration c, java.util.function.Function<String, String> environment) {
        String rawId = c.getString("server-id", "");
        serverIdSet = rawId != null && !rawId.isBlank();
        serverId = serverIdSet ? safeId(rawId.trim()) : "school";
        enabled = c.getBoolean("enabled", true);
        String key = c.getString("openai.api-key", "");
        String env = environment.apply("CHACAPORTRAIT_OPENAI_KEY");
        if (env == null || env.isBlank()) {
            env = environment.apply("CHACANPC_OPENAI_KEY");
        }
        if (env != null && !env.isBlank()) {
            key = env;
        }
        apiKey = key == null ? "" : key.trim();
        String base = c.getString("openai.base-url", "https://api.openai.com/v1");
        baseUrl = base == null ? "https://api.openai.com/v1" : base.replaceAll("/+$", "");
        timeoutSeconds = clamp(c.getInt("openai.timeout-seconds", 240), 30, 600);
        sendUserHash = c.getBoolean("openai.send-user-hash", true);

        imageModel = c.getString("image.model", "gpt-image-2.5-sunburst").trim();
        quality = oneOf(c.getString("image.quality", "medium"), "medium", "low", "medium", "high", "xhigh", "max", "auto");
        String sz = c.getString("image.size", "1024x1536");
        size = sz != null && sz.matches("\\d{3,4}x\\d{3,4}") ? sz : "1024x1536";
        background = oneOf(c.getString("image.background", "transparent"), "transparent", "transparent", "opaque", "auto");
        inputFidelity = oneOf(c.getString("image.input-fidelity", "high"), "high", "high", "low", "");
        imageModeration = oneOf(c.getString("image.moderation", "auto"), "auto", "auto", "low");
        List<String> refs = c.getStringList("image.references");
        references = refs.isEmpty() ? List.of("reference/style-reference.png") : List.copyOf(refs);
        requireTransparent = c.getBoolean("image.require-transparent", false);

        describeEnabled = c.getBoolean("describe.enabled", true);
        describeModel = c.getString("describe.model", "gpt-6-luna");
        describeMaxTokens = clamp(c.getInt("describe.max-output-tokens", 2048), 1024, 4096);

        moderationEnabled = c.getBoolean("moderation.enabled", true);
        moderationModel = c.getString("moderation.model", "omni-moderation-latest");

        workers = clamp(c.getInt("queue.workers", 12), 1, 16); // 2026-10-25 오너: 60명 10분 안 (동시 12 × 약 90초). CPU 배경 제거는 BackgroundMatte 1칸 고정이라 늘지 않음
        maxWaiting = clamp(c.getInt("queue.max-waiting", 300), 1, 5000);

        autoFirstJoin = c.getBoolean("auto.first-join", false);
        List<String> autoIds = new java.util.ArrayList<>();
        for (String id : c.isList("auto.server-ids") ? c.getStringList("auto.server-ids") : List.of("school", "tutorial")) {
            if (id != null && id.trim().matches("[A-Za-z0-9_-]{1,32}")) autoIds.add(id.trim());
        }
        autoServerIds = List.copyOf(autoIds);
        autoDelaySeconds = clamp(c.getInt("auto.delay-seconds", 20), 0, 600);
        autoMaxAttempts = clamp(c.getInt("auto.max-attempts", 3), 1, 50);

        rerollItemId = c.getString("reroll.item-id", "item:cutscene_ticket").trim();
        promptTimeoutSeconds = clamp(c.getInt("reroll.prompt-timeout-seconds", 180), 15, 1800);
        rerollCooldownSeconds = clamp(c.getInt("reroll.cooldown-seconds", 60), 0, 86400);
        rerollDailyLimit = Math.max(0, c.getInt("reroll.daily-limit", 0));

        budgetUsd = Math.max(0, c.getDouble("budget.total-usd", 30.0));
        Map<String, CostModel.Prices> p = new HashMap<>();
        ConfigurationSection ps = c.getConfigurationSection("prices");
        if (ps != null) {
            for (String model : ps.getKeys(true)) {
                ConfigurationSection m = ps.getConfigurationSection(model);
                if (m == null || !m.isSet("text-input")) {
                    continue;
                }
                p.put(model.replace("gpt-image-2_5-", "gpt-image-2.5-"), new CostModel.Prices(m.getDouble("text-input", 0), m.getDouble("image-input", 0),
                        m.getDouble("image-output", 0), m.getDouble("text-output", 0), m.getDouble("cached-image-input", m.getDouble("image-input", 0))));
            }
        }
        prices = Map.copyOf(p);
        estimate = new CostModel.Estimate(
                c.getInt("estimate.output-tokens.low", 600),
                c.getInt("estimate.output-tokens.medium", 2400),
                c.getInt("estimate.output-tokens.high", 9000),
                c.getInt("estimate.input-image-tokens", 9000),
                c.getInt("estimate.input-text-tokens", 1500),
                c.getInt("estimate.describe-tokens", 3000));

        // Independent, uncalibrated 2.5 reservation estimates: never an API price quote or hard cap.
        estimate25 = new CostModel.Estimate(
                c.getInt("estimate.image-2-5.output-tokens.low", 3000),
                c.getInt("estimate.image-2-5.output-tokens.medium", 9000),
                c.getInt("estimate.image-2-5.output-tokens.high", 18000),
                c.getInt("estimate.image-2-5.input-image-tokens", 18000),
                c.getInt("estimate.image-2-5.input-text-tokens", 3000), 3000);

        promptBase = c.getString("prompt.base", "");
        promptAppearance = c.getString("prompt.appearance", "Appearance notes from the skin: {appearance}");
        promptRequest = c.getString("prompt.request", "Optional player request: {request}");
        promptDescribe = c.getString("prompt.describe", "Describe this Minecraft skin character in at most 60 words.");

        ConfigurationSection ms = c.getConfigurationSection("messages");
        if (ms != null) {
            for (String k : ms.getKeys(false)) {
                messages.put(k, ms.getString(k, ""));
            }
        }
    }

    /** 이 모델이 지원되는 이미지 모델인지 (config 오타 방지). */
    public static boolean supportedImageModel(String model) {
        return IMAGE_MODELS.contains(model == null ? "" : model);
    }

    /** gpt-image-1.5 처럼 input_fidelity를 받는 모델인지. gpt-image-2 는 보내면 안 된다. */
    public static boolean acceptsInputFidelity(String model) {
        return model != null && model.startsWith("gpt-image-1");
    }

    public String message(String key, Object... kv) {
        String m = messages.get(key);
        if (m == null || m.isEmpty()) {
            m = DEFAULT_MESSAGES.getOrDefault(key, "");
        }
        for (int i = 0; i + 1 < kv.length; i += 2) {
            m = m.replace("{" + kv[i] + "}", String.valueOf(kv[i + 1]));
        }
        return m;
    }

    public boolean automaticGenerationAllowed() {
        return enabled && autoFirstJoin && autoServerIds.contains(serverId);
    }

    public boolean hasKey() {
        return !apiKey.isBlank();
    }

    private static int clamp(int v, int lo, int hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    private static String oneOf(String v, String def, String... allowed) {
        if (v == null) {
            return def;
        }
        String s = v.trim().toLowerCase(Locale.ROOT);
        for (String a : allowed) {
            if (a.equals(s)) {
                return s;
            }
        }
        return def;
    }

    private static String safeId(String s) {
        return s != null && s.matches("[A-Za-z0-9_-]{1,32}") ? s : "server";
    }
}
