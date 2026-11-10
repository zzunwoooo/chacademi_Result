package kr.kkaegul.core.data;

import org.bukkit.configuration.file.YamlConfiguration;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * 플레이어 1명의 모든 진행 데이터.
 * "다이아(debt)" = 제출해야 할 남은 다이아. 증가 API는 늘리고, 감소 API·제출은 줄인다. 0이 되면 졸업.
 */
public final class PlayerData {
    public final UUID uuid;
    public String name = "";
    public boolean dirty = false;

    // ── 다이아 장부 ──
    public long debt = 0;              // 제출까지 남은 다이아
    public long totalIncreased = 0;    // 누적 증가량 (명예의 전당: 다이아 누적 개수)
    public long totalDecreased = 0;
    public long totalSubmitted = 0;    // 실제로 장부에서 차감된 제출량
    public long totalDonated = 0;      // 랜덤 제출로 손해 본 양(=기부)
    public boolean graduated = false;
    public long graduatedAt = 0;

    // ── 재화 ──
    public long money = 0;
    public double sellDust = 0;        // 판매하고 남은 다이아 소수점 (1이 되면 다이아 1개)
    public long held = 0;              // 보유 다이아 (주운 다이아가 들어가는 화폐)
    public long coins = 0;             // 깨굴코인

    // ── 운빨 게이지 0~99 ──
    public int gauge = 50;

    // ── 감옥 ──
    public boolean inJail = false;
    public int squatRequired = 0;
    public int squatDone = 0;
    public String jailReturn = "";     // 끌려오기 전 위치
    public boolean jailExitReady = false;
    public int jailCount = 0;

    // ── 통계 ──
    public int pickaxeBreaks = 0;
    public int instantDeaths = 0;
    public int gachaSpins = 0;
    public long boxesOpened = 0;
    public int bestPickaxe = 0;        // 가챠로 도달한 최고 수치

    // ── 칭호 ──
    public final Set<String> titles = new LinkedHashSet<>();
    public String selectedTitle = "";

    // ── 접속 보상 (일 단위 리셋) ──
    public String playDay = "";
    public long playSecondsToday = 0;
    public long playSecondsTotal = 0;
    public final Set<String> claimedMilestones = new LinkedHashSet<>();
    public int failPapers = 0;

    // ── 오늘의 운세 ──
    public String fortuneDay = "";
    public int fortuneUses = 0;

    // ── 보물광산 ──
    public String mineDay = "";
    public int mineMoves = 0;

    // ── 스타트 ──
    public boolean armorRolled = false;
    public boolean starterClaimed = false;
    public boolean realtorClaimed = false;
    public boolean profileDone = false;
    public String voiceSetting = "";
    public String nickname = "";

    // ── 우편함 (Base64 직렬화 아이템) ──
    public final List<String> mail = new ArrayList<>();
    /** 위험한 초대 맨몸 입장 때 맡겨 둔 인벤토리 ("칸|아이템") + 돌아갈 자리 */
    public final List<String> stash = new ArrayList<>();
    public String stashBack = "";
    /** 오프라인일 때 들어온 개인 API 번호 (접속 시 실행) */
    public final List<String> pendingApi = new ArrayList<>();
    /** 귀환권/감옥 등으로 강제 이동되기 전 위치 */
    public String backLocation = "";
    /** 광산 안에서 접속 종료/서버 종료됐을 때 돌아갈 위치 */
    public String mineReturn = "";

    // ── 치장품 (id = ItemsAdder namespaced id) ──
    public final Set<String> cosmeticsOwned = new LinkedHashSet<>();
    /** 부위(head/face/back/offhand/balloon) → 장착한 치장품 id */
    public final java.util.Map<String, String> cosmeticsEquipped = new java.util.LinkedHashMap<>();

    public PlayerData(UUID uuid) { this.uuid = uuid; }

    public void markDirty() { dirty = true; }

    public void load(YamlConfiguration y) {
        name = y.getString("name", "");
        debt = y.getLong("diamond.debt", 0);
        totalIncreased = y.getLong("diamond.increased", 0);
        totalDecreased = y.getLong("diamond.decreased", 0);
        totalSubmitted = y.getLong("diamond.submitted", 0);
        totalDonated = y.getLong("diamond.donated", 0);
        graduated = y.getBoolean("diamond.graduated", false);
        graduatedAt = y.getLong("diamond.graduated-at", 0);
        money = y.getLong("money", 0);
        held = y.getLong("diamond.held", 0);
        sellDust = y.getDouble("diamond.sell-dust", 0);
        coins = y.getLong("coins", 0);
        gauge = y.getInt("gauge", 50);
        inJail = y.getBoolean("jail.in", false);
        squatRequired = y.getInt("jail.required", 0);
        squatDone = y.getInt("jail.done", 0);
        jailReturn = y.getString("jail.return", "");
        jailExitReady = y.getBoolean("jail.exit-ready", false);
        jailCount = y.getInt("jail.count", 0);
        pickaxeBreaks = y.getInt("stats.pickaxe-breaks", 0);
        instantDeaths = y.getInt("stats.instant-deaths", 0);
        gachaSpins = y.getInt("stats.gacha-spins", 0);
        boxesOpened = y.getLong("stats.boxes", 0);
        bestPickaxe = y.getInt("stats.best-pickaxe", 0);
        titles.clear();
        titles.addAll(y.getStringList("titles.owned"));
        selectedTitle = y.getString("titles.selected", "");
        playDay = y.getString("play.day", "");
        playSecondsToday = y.getLong("play.today", 0);
        playSecondsTotal = y.getLong("play.total", 0);
        claimedMilestones.clear();
        claimedMilestones.addAll(y.getStringList("play.claimed"));
        failPapers = y.getInt("play.fail-papers", 0);
        fortuneDay = y.getString("fortune.day", "");
        fortuneUses = y.getInt("fortune.uses", 0);
        mineDay = y.getString("mine.day", "");
        mineMoves = y.getInt("mine.moves", 0);
        armorRolled = y.getBoolean("start.armor", false);
        starterClaimed = y.getBoolean("start.kit", false);
        realtorClaimed = y.getBoolean("start.realtor", false);
        profileDone = y.getBoolean("start.profile", false);
        voiceSetting = y.getString("start.voice", "");
        nickname = y.getString("start.nickname", "");
        mail.clear();
        mail.addAll(y.getStringList("mail"));
        stash.clear();
        stash.addAll(y.getStringList("stash"));
        stashBack = y.getString("stash-back", "");
        pendingApi.clear();
        pendingApi.addAll(y.getStringList("pending-api"));
        backLocation = y.getString("back", "");
        mineReturn = y.getString("mine-return", "");
        cosmeticsOwned.clear();
        cosmeticsOwned.addAll(y.getStringList("cosmetics.owned"));
        cosmeticsEquipped.clear();
        var eq = y.getConfigurationSection("cosmetics.equipped");
        if (eq != null) for (String slot : eq.getKeys(false)) {
            String id = eq.getString(slot, "");
            if (id != null && !id.isEmpty()) cosmeticsEquipped.put(slot, id);
        }
    }

    public void save(YamlConfiguration y) {
        y.set("uuid", uuid.toString());
        y.set("name", name);
        y.set("diamond.debt", debt);
        y.set("diamond.increased", totalIncreased);
        y.set("diamond.decreased", totalDecreased);
        y.set("diamond.submitted", totalSubmitted);
        y.set("diamond.donated", totalDonated);
        y.set("diamond.graduated", graduated);
        y.set("diamond.graduated-at", graduatedAt);
        y.set("money", money);
        y.set("diamond.held", held);
        y.set("diamond.sell-dust", sellDust);
        y.set("coins", coins);
        y.set("gauge", gauge);
        y.set("jail.in", inJail);
        y.set("jail.required", squatRequired);
        y.set("jail.done", squatDone);
        y.set("jail.return", jailReturn);
        y.set("jail.exit-ready", jailExitReady);
        y.set("jail.count", jailCount);
        y.set("stats.pickaxe-breaks", pickaxeBreaks);
        y.set("stats.instant-deaths", instantDeaths);
        y.set("stats.gacha-spins", gachaSpins);
        y.set("stats.boxes", boxesOpened);
        y.set("stats.best-pickaxe", bestPickaxe);
        y.set("titles.owned", new ArrayList<>(titles));
        y.set("titles.selected", selectedTitle);
        y.set("play.day", playDay);
        y.set("play.today", playSecondsToday);
        y.set("play.total", playSecondsTotal);
        y.set("play.claimed", new ArrayList<>(claimedMilestones));
        y.set("play.fail-papers", failPapers);
        y.set("fortune.day", fortuneDay);
        y.set("fortune.uses", fortuneUses);
        y.set("mine.day", mineDay);
        y.set("mine.moves", mineMoves);
        y.set("start.armor", armorRolled);
        y.set("start.kit", starterClaimed);
        y.set("start.realtor", realtorClaimed);
        y.set("start.profile", profileDone);
        y.set("start.voice", voiceSetting);
        y.set("start.nickname", nickname);
        y.set("mail", new ArrayList<>(mail));
        y.set("stash", new ArrayList<>(stash));
        y.set("stash-back", stashBack);
        y.set("pending-api", new ArrayList<>(pendingApi));
        y.set("back", backLocation);
        y.set("mine-return", mineReturn);
        y.set("cosmetics.owned", new ArrayList<>(cosmeticsOwned));
        for (java.util.Map.Entry<String, String> e : cosmeticsEquipped.entrySet()) y.set("cosmetics.equipped." + e.getKey(), e.getValue());
    }
}
