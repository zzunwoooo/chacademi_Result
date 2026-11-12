package school.chacademia.tower;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** 한 판 (플레이어 한 명). 메인 스레드에서만 만진다. */
final class Run {
    enum Phase { COUNTDOWN, FIGHT, CARD, ENDED }

    final UUID player;
    final UUID runId = UUID.randomUUID();
    final long startedAt = System.currentTimeMillis();
    int floor = 1;
    Phase phase = Phase.COUNTDOWN;
    /** 이 단계가 끝나는 시각 (카운트다운·카드 제한시간). */
    long phaseEndsAt;
    /** 남은 무리 (지금 무리를 다 잡으면 다음 무리). */
    final List<List<Floors.Pick>> waves = new ArrayList<>();
    final Set<UUID> mobs = new HashSet<>();
    long outsideSince;

    // ── 카드로 얻은 능력 ──
    final List<String> spells = new ArrayList<>();
    final Map<String, Integer> taken = new LinkedHashMap<>();
    final Set<String> relics = new HashSet<>();
    /** 수문장 보상이 당첨된 층 (판이 끝나고 지급). */
    final List<Integer> pendingRewards = new ArrayList<>();
    int intelligence;
    double maxHealthBonus;
    double damageBonus;     // 0.10 = +10%
    double guard;           // 받는 피해 감소 0.10 = -10%
    double lifesteal;       // 준 피해의 비율만큼 회복
    double speedBonus;
    double vigor;           // 층을 깰 때 최대 체력 비율만큼 회복
    double harvest;         // 처치마다 회복 (체력 점)
    int feathers;           // 쓰러질 때 버티기
    int luck;               // 다음 카드 등급 +1 (한 번 쓰면 줄어듦)
    int rerolls;

    // ── 11/12 새 증강 ──
    double crit;                // 치명타 확률
    double critDamage;          // 치명타 배수에 더해지는 값 (기본 2배)
    double vengeMult;           // 되갚기 배수 (0 = 없음)
    long vengeCooldownMs;       // 되갚기 재사용 대기
    boolean vengeArmed;         // 맞아서 준비된 상태
    long vengeReadyAt;          // 이 시각부터 다시 준비될 수 있음
    double firstStrike, berserk, momentum, overflow, hunter, combo, diceMax;
    int momentumStacks; long momentumUntil;
    java.util.UUID comboTarget; int comboStacks;
    double manaBonus;           // 최대 마나 +
    long quitAskAt;              // 상자 창 포기 버튼 첫 누름 시각
    // ── 11/12 저녁 새 증강 ──
    double manaFlow, manaHeart, manaShield, meditate, echoCast, burn, venom, mark, precision, pointBlank, lone, crowd, deathBlast, absorb, frenzy, mirror, stasis, thunder, wisp, coin, fortune;
    int frenzyStacks; long frenzyUntil; boolean stasisUsed; long stasisUntil; long thunderAt, wispAt;
    /** 몹별: 화상 [틱당 피해, 남은 틱] · 독 중첩 · 표식 끝나는 시각. */
    final java.util.Map<UUID, double[]> burns = new java.util.HashMap<>();
    final java.util.Map<UUID, Integer> venoms = new java.util.HashMap<>();
    final java.util.Map<UUID, Long> marks = new java.util.HashMap<>();
    /** [기능:탑몹스킬] 몹별 다음 큰 스킬 시각. */
    final java.util.Map<UUID, Long> skillAt = new java.util.HashMap<>();
    double manaNow, manaPrev;   // 최근 두 번(0.5초 간격)의 마나 비율 — 넘치는 마력 판정용
    double siphon;              // 처치마다 마나
    double haste;               // 시전 가속
    double reload;              // 처치 때 재사용 초기화 확률
    double glassTaken;          // 받는 피해 + (유리 대포)
    double retaliate, dodge, secondWind, barrier, chain, finisher, overkill, lastStand;
    boolean pact; long pactAt;
    double interestRate, interestBonus;   // 층마다 쌓이는 피해
    double scholar;             // 층마다 지능 (소수 — 모이면 1씩 오른다)
    double scholarCarry;
    double windUsed;            // 이번 층에 숨 고르기로 찬 체력
    int startPicksLeft;         // 남은 시작 마법 고르기
    long lastDamagedAt = System.currentTimeMillis();
    /** 종류별 가장 높은 등급 (내 증강 창 색). */
    final Map<String, Integer> takenGrade = new LinkedHashMap<>();
    /** 유물을 얻은 순서. */
    final List<String> relicOrder = new ArrayList<>();

    // ── 시련·도박 ──
    /** 시련: 남은 층 수 (0 = 없음). 이 동안 카드 없음. 다 깨면 trialGrades 의 등급 이상 카드를 하나씩 고른다. */
    int trialLeft;
    final List<Integer> trialGrades = new ArrayList<>();
    /** 도박: 이번 층 몹 체력 배율 (1 = 없음), 깨면 카드 gamblePicks 번. gambleArmed = 다음 층에 걸림. */
    double gambleHealth = 1;
    int gamblePicks;
    boolean gambleArmed, gambleActive;
    /** 지금 고르는 중인 카드, 앞으로 고를 횟수 (값 = 최소 등급, -1 = 제한 없음). */
    List<Cards.Card> offer = List.of();
    final java.util.ArrayDeque<Integer> picks = new java.util.ArrayDeque<>();
    boolean firstPick;
    /** 들어올 때의 최고 기록 (-1 = 아직 못 읽음). 서버가 꺼질 때 첫 도달 보상을 바로 주는 데 쓴다. */
    int bestAtStart = -1;

    // [기능:탑여러방] 이 판이 쓰는 방 · 수문장 보스바 · 카드 상태 (예전엔 서비스에 하나씩)
    final Settings.Room room;
    net.kyori.adventure.bossbar.BossBar guardianBar;
    UUID guardian;
    int currentMinGrade = -1;
    long offerShownAt;

    Run(UUID player, Settings.Room room) { this.player = player; this.room = room; }

    long elapsedMs() { return System.currentTimeMillis() - startedAt; }

    void took(String type) { taken.merge(type, 1, Integer::sum); }
    void took(String type, int grade, double value) { took(type); takenGrade.merge(type, grade, Math::max); takenSum.merge(type, value, Double::sum); }
    /** 종류별로 고른 수치의 합 (내 증강 창에 "지금까지 합계" 로 보여 준다). */
    final Map<String, Double> takenSum = new LinkedHashMap<>();
    /** 배운 마법·유물의 이름과 등급 (내 증강 창). */
    final Map<String, String> spellNames = new LinkedHashMap<>();
    final Map<String, Integer> spellGrades = new LinkedHashMap<>();
    final Map<String, Integer> relicGrades = new LinkedHashMap<>();
    /** 저주받은 힘으로 잃은 최대 체력. */
    double cursedLoss;
}
