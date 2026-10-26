package school.magiccodex.paper;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import java.util.function.IntPredicate;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;

/**
 * 외부 플러그인(ChacaNPC)용 공개 facade. Bukkit ServicesManager에 등록된다.
 *
 * <p>클래스 로더 충돌을 피하려고 이 클래스는 MagicCodexBridge JAR에만 있다. 호출하는 쪽은
 * {@code getKnownServices()}에서 이름으로 찾아 리플렉션으로 부른다. 그래서 시그니처에는 JDK·Bukkit 타입만 쓴다.
 *
 * <p>모든 메서드는 메인 스레드에서 호출하고, 반환된 future도 메인 스레드에서 완료된다.
 */
public final class NpcSocialFacade {

    public static final int API_VERSION = 1;

    private final NpcSocialService service;

    NpcSocialFacade(NpcSocialService service) {
        this.service = service;
    }

    /**
     * (추가 메서드, API_VERSION 그대로) NPC 대화창의 상점 버튼 (예: 피피 "물건 좀 볼 수 있어요?").
     * 일반 NPC 상점 클릭과 같은 경로라 거리 확인·튜토리얼 재료 상점 분기(tutorial-shop.yml)가 그대로 적용된다.
     * 물고기 판매소 ID 도 받는다. 메인 스레드에서 호출.
     */
    public boolean openShop(Player player, String shopId, Entity anchor) {
        ShopBridge shops = org.bukkit.Bukkit.getServicesManager().load(ShopBridge.class);
        return shops != null && shops.openFromNpc(player, shopId, anchor);
    }

    /**
     * (추가 메서드, API_VERSION 그대로) NPC 대화창의 수업 버튼 (action: lesson:&lt;수업키&gt;, 2026-10-26).
     * 대화창이 "어떤 수업 들을래?" 선택 창으로 바뀌고, 고른 수업이 책 창으로 열린다 (lessons.yml · books.yml). 메인 스레드에서 호출.
     */
    public boolean openLesson(Player player, String lessonId, Entity anchor) {
        var plugin = org.bukkit.plugin.java.JavaPlugin.getPlugin(MagicCodexBridge.class);
        var lessons = plugin.lessonBridge();
        return lessons != null && lessons.open(player, lessonId, anchor);
    }

    /**
     * (추가 메서드, API_VERSION 그대로, 2026-10-26) 마법별 힌트 파일 spell-hints.yml 의 2성 문장 — ChacaNPC hints.yml 힌트에
     * 목격담 재료(materials)가 비어 있을 때 대신 쓴다. 파일에 없으면 발견 조건 문구를 흐린 자동 목격담, 그것도 안 되면 null. 메인 스레드에서 호출.
     */
    public String spellHint(String spell) {
        var plugin = org.bukkit.plugin.java.JavaPlugin.getPlugin(MagicCodexBridge.class);
        var hints = plugin.spellHints();
        if (hints == null || spell == null) return null;
        String line = hints.line(spell, 2);
        if (line != null) return line;
        var c = hints.condition(spell);
        return c == null ? null : LifeSpells.second(hints, c, null);
    }

    public int apiVersion() {
        return API_VERSION;
    }

    /** Configured plain nickname only; title prefix/suffix are excluded. Main-thread access. */
    public String playerName(Player player) {
        return service.playerName(player);
    }

    /** Exact online account/UUID/nickname resolution; duplicate nicknames return null. */
    public Player resolvePlayer(String input) { return service.resolvePlayer(input); }

    // ------------------------------------------------------------------ 호감도

    /** {score, heart} */
    public CompletableFuture<int[]> affinity(UUID player, String npc) {
        return service.affinity(player, npc).thenApply(r -> new int[]{r.score(), r.heart()});
    }

    /**
     * 호감도 변화. source별 하루 한도(affinity.yml)는 서버 간 공유 DB 트랜잭션으로 지킨다.
     * 반환 {score, heart, applied}
     */
    public CompletableFuture<int[]> addAffinity(UUID player, String npc, String source, int amount) {
        return service.add(player, npc, source, amount)
                .thenApply(r -> new int[]{r.row().score(), r.row().heart(), r.applied()});
    }

    public CompletableFuture<String> nickname(UUID player, String npc) {
        return service.affinity(player, npc).thenApply(r -> r.nickname());
    }

    public CompletableFuture<Boolean> setNickname(UUID player, String npc, String nickname) {
        return service.setNickname(player, npc, nickname).thenApply(r -> true);
    }

    /**
     * (추가 메서드, API_VERSION 그대로) 호의적 AI 대화 보상 (#83): NPC 하나당 하루 횟수·지급량은 affinity.yml chat-reward.
     * 상한(30·60·90, 퀘스트 미완료)이면 오르지 않고 횟수도 쓰지 않는다. 반환 {score, heart, applied}
     */
    public CompletableFuture<int[]> chatReward(UUID player, String npc) {
        return service.chatReward(player, npc)
                .thenApply(r -> new int[]{r.row().score(), r.row().heart(), r.applied()});
    }

    // ------------------------------------------------------------------ 선물 (2단계)

    /** 1단계: 하루 횟수 예약 + pending 기록. "OK" / "CAP"(오늘 횟수 다 씀) / "FULL"(호감도 상한, 횟수 안 씀) / "DUPLICATE" */
    public CompletableFuture<String> beginGift(UUID player, String npc, String token, int delta) {
        return service.beginGift(player, npc, token, delta).thenApply(Enum::name);
    }

    /** 2단계: 아이템 차감 후 확정. {score, heart, applied}, 이미 처리된 토큰이면 null */
    public CompletableFuture<int[]> commitGift(UUID player, String npc, String token) {
        return service.commitGift(player, npc, token)
                .thenApply(r -> r == null ? null : new int[]{r.row().score(), r.row().heart(), r.applied()});
    }

    /**
     * 1.5단계 (A08, 2026-10-21): 손 칸의 선물 아이템 1개 차감 + 영수증(PDC) + saveData 를 같은 틱에 한다.
     * 1 = 차감함, 0 = 칸의 아이템이 바뀌어 아무것도 안 함. 예외 = 결과 모름 (pending 은 다음 접속 때 영수증으로 정해짐).
     * 이 메서드가 없는 옛 Bridge 와는 선물을 하지 않는다 (ChacaNPC 가 확인).
     */
    public int takeGift(Player player, int slot, org.bukkit.inventory.ItemStack one, String token) {
        return service.takeGift(player, slot, one, token);
    }

    /** 아이템 차감 전 취소. 하루 횟수를 돌려준다. */
    public CompletableFuture<Boolean> cancelGift(String token) {
        return service.cancelGift(token);
    }

    // ------------------------------------------------------------------ 스토리 대화

    /**
     * 이 Citizens NPC에 묶인 고정 대화 중 지금 열 수 있는 것을 연다.
     * 기존 권한·조건·진행 상태 검사를 그대로 쓴다.
     * "OPENED" = 열었음 / "NONE" = 열 수 있는 고정 대화 없음 / "BUSY" = 처리 중이라 판단 보류 /
     * "BLOCKED" = 모드 미설치·검토 대기 등 (안내 메시지는 Bridge가 보냄)
     * NONE 일 때만 AI 대화를 열어야 한다.
     */
    public CompletableFuture<String> openStory(Player player, int citizensId, Entity anchor) {
        return service.openStory(player, citizensId, anchor);
    }

    /** 이 NPC id들은 호출한 플러그인이 클릭을 처리한다 (Bridge의 Citizens 클릭 처리에서 제외). null = 해제 */
    public void claimCitizensNpcs(IntPredicate claimed) {
        service.claim(claimed);
    }

    /** 메인 퀘스트 진행 요약 (story.* 상태). 예) "첫 번째 이야기: 2" */
    public CompletableFuture<String> storySummary(UUID player) {
        return service.storySummary(player);
    }

    // ------------------------------------------------------------------ 퀘스트 (기존 MagicCodex 데이터)

    /** 지금 이 플레이어에게 제안할 수 있는 퀘스트인지 (존재·공개·권한·진행 중 아님). */
    public boolean questOfferable(Player player, String questId) {
        QuestBridge q = service.quests();
        return q != null && q.npcOfferable(player, questId);
    }

    public int activeQuestCount(Player player) {
        QuestBridge q = service.quests();
        return q == null ? 0 : q.activeCount(player);
    }

    public String questTitle(String questId) {
        QuestBridge q = service.quests();
        return q == null ? "" : q.title(questId);
    }

    /**
     * (추가 메서드, API_VERSION 그대로) 길잡이 별 (2026-10-26 메모 #93, ChacademyStory 가 부름): 진행 중 의뢰에서 아직 못 채운
     * "NPC" 목표의 Citizens 번호 (이 서버 것만). 스토리 단계에 가리킬 목표가 없을 때 가장 가까운 것을 가리킨다. 메인 스레드.
     */
    public int[] questNpcTargets(Player player) {
        QuestBridge q = service.quests();
        return q == null || player == null ? new int[0] : q.npcTargets(player);
    }

    /** 기존 의뢰 수락 경로(조건·권한·중복 검사 포함)로 수락. */
    public CompletableFuture<Boolean> acceptQuest(Player player, String questId) {
        QuestBridge q = service.quests();
        CompletableFuture<Boolean> f = new CompletableFuture<>();
        if (q == null) {
            f.complete(false);
        } else {
            q.acceptFromDialogue(player, questId, f::complete);
        }
        return f;
    }

    // ------------------------------------------------------------------ 사건 알림

    /** 하트 이벤트·엔딩·호칭 설정 알림. map 키: player, playerName, npc, type(heart/ending/nickname), value */
    public void addSocialListener(Consumer<Map<String, String>> listener) {
        service.addListener(listener);
    }

    public void removeSocialListener(Consumer<Map<String, String>> listener) {
        service.removeListener(listener);
    }

    /**
     * (추가 메서드, API_VERSION 그대로) 서버 통계 (#96 추가, 오너 2026-10-26): ChacaNPC 가 NPC 대화 횟수를 플레이어 누적 통계에 더한다.
     * key 는 "대화:<npcId>"·"대화시작:<npcId>" 등. 메모리에 더하기만 (DB 는 AuditStats 주기 반영).
     */
    public void stat(UUID player, String key, long amount) {
        if (player == null || key == null || key.isBlank() || key.length() > 64 || amount <= 0) return;
        AuditStats.add(player, key, amount);
    }
}
