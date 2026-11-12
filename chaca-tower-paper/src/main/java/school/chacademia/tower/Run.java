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

    Run(UUID player) { this.player = player; }

    long elapsedMs() { return System.currentTimeMillis() - startedAt; }

    void took(String type) { taken.merge(type, 1, Integer::sum); }
}
