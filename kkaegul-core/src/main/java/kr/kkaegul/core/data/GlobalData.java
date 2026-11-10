package kr.kkaegul.core.data;

import kr.kkaegul.core.KkaegulCore;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.logging.Level;

/** 서버 전체 공유 데이터: 첫 달성자, 농사 시세, 제출 누계 등 */
public final class GlobalData {
    private final KkaegulCore plugin;
    private final File file;
    public boolean dirty = false;

    /** 칭호/업적 id -> 최초 달성자 uuid */
    public final Map<String, String> firsts = new LinkedHashMap<>();
    /** 작물 material -> 현재 판매가 */
    public final Map<String, Double> farmPrices = new LinkedHashMap<>();
    public long farmUpdatedAt = 0;
    /** 농작물 탭(AddCrop) 작물 1개 판매가 시세 — 작물 이름 → 다이아 (농사 시세와 같이 바뀜) */
    public final Map<String, Double> cropPrices = new LinkedHashMap<>();
    public long totalSubmittedAll = 0;
    public long totalDonatedAll = 0;
    public long lastMinigameAt = 0;
    /** 서버 공용 운빨 게이지 (-1 = 아직 없음 → config gauge.start) */
    public int gauge = -1;
    /** 개구리 경주 이월 상금 (깨굴코인. 다이아로 걸던 때의 race.carry 는 버림) */
    public long raceCarry = 0;
    /** 미니게임 일정: invite / treasure / casino → 다음 시각(ms) */
    public final Map<String, Long> sched = new LinkedHashMap<>();
    /** 카지노 번갈아 (짝수 홀덤, 홀수 섯다) */
    public int casinoTurn = 0;
    /** 개구리 경주 자동 진행 멈춤 */
    public boolean racePaused = false;
    /** 이름 있는 위치 저장 (lobby, jail 등은 config 대신 여기에 /kg setloc 으로 저장 가능) */
    public final Map<String, String> locations = new LinkedHashMap<>();

    public GlobalData(KkaegulCore plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "global.yml");
        load();
    }

    private void load() {
        YamlConfiguration y = YamlConfiguration.loadConfiguration(file);
        ConfigurationSection f = y.getConfigurationSection("firsts");
        if (f != null) for (String k : f.getKeys(false)) firsts.put(k, f.getString(k));
        ConfigurationSection fp = y.getConfigurationSection("farm.prices");
        if (fp != null) for (String k : fp.getKeys(false)) farmPrices.put(k, fp.getDouble(k));
        farmUpdatedAt = y.getLong("farm.updated", 0);
        ConfigurationSection cp = y.getConfigurationSection("crop.prices");
        if (cp != null) for (String k : cp.getKeys(false)) cropPrices.put(k, cp.getDouble(k));
        totalSubmittedAll = y.getLong("submit.total", 0);
        totalDonatedAll = y.getLong("submit.donated", 0);
        lastMinigameAt = y.getLong("minigame.last", 0);
        gauge = y.getInt("gauge", -1);
        raceCarry = y.getLong("race.carry-coin", 0);
        ConfigurationSection sc = y.getConfigurationSection("minigame.sched");
        if (sc != null) for (String k : sc.getKeys(false)) sched.put(k, sc.getLong(k));
        casinoTurn = y.getInt("minigame.casino-turn", 0);
        racePaused = y.getBoolean("race.paused", false);
        ConfigurationSection l = y.getConfigurationSection("locations");
        // 이름에 점(.)이 있으면 YAML 에서 중첩되므로 깊게 읽고 문자열 값만 취한다
        if (l != null) for (String k : l.getKeys(true)) if (l.isString(k)) locations.put(k, l.getString(k));
    }

    public void save() {
        YamlConfiguration y = new YamlConfiguration();
        for (Map.Entry<String, String> e : firsts.entrySet()) y.set("firsts." + e.getKey(), e.getValue());
        for (Map.Entry<String, Double> e : farmPrices.entrySet()) y.set("farm.prices." + e.getKey(), e.getValue());
        y.set("farm.updated", farmUpdatedAt);
        for (Map.Entry<String, Double> e : cropPrices.entrySet()) y.set("crop.prices." + e.getKey(), e.getValue());
        y.set("submit.total", totalSubmittedAll);
        y.set("submit.donated", totalDonatedAll);
        y.set("minigame.last", lastMinigameAt);
        y.set("gauge", gauge);
        y.set("race.carry-coin", raceCarry);
        for (Map.Entry<String, Long> e : sched.entrySet()) y.set("minigame.sched." + e.getKey(), e.getValue());
        y.set("minigame.casino-turn", casinoTurn);
        y.set("race.paused", racePaused);
        for (Map.Entry<String, String> e : locations.entrySet()) y.set("locations." + e.getKey(), e.getValue());
        try {
            y.save(file);
            dirty = false;
        } catch (IOException e) {
            plugin.getLogger().log(Level.SEVERE, "global.yml 저장 실패", e);
        }
    }
}
