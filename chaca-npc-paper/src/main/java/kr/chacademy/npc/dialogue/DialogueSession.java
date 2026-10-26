package kr.chacademy.npc.dialogue;

import kr.chacademy.npc.core.CharacterSheet;
import kr.chacademy.npc.core.Defs.RumorView;
import kr.chacademy.npc.core.Defs.Turn;
import kr.chacademy.npc.data.Storage;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * 플레이어 한 명과 NPC 한 명의 대화 한 번. 메인 스레드에서만 바꾼다 (loadContext 결과 제외).
 */
public final class DialogueSession {

    private final UUID player;
    private final String playerName;
    private final CharacterSheet character;
    private final Integer citizensId; // 관리자 테스트면 null일 수 있음
    private final boolean adminTest;
    /** 세션 토큰: 창 닫기·재접속·다른 NPC 전환 뒤 도착한 패킷/응답을 버리는 데 쓴다. */
    private final String token = UUID.randomUUID().toString();
    final DialogueView view;

    final List<Turn> recent = new ArrayList<>();
    final List<Turn> saveable = new ArrayList<>();
    long lastActivity;
    long lastInputAt;
    volatile boolean busy;
    /** busy 가 켜진 시각. 이때부터 정해진 시간 안에 대답(또는 고정 대사)이 반드시 나간다. */
    long busySince;
    /** 지금 처리 중(또는 마지막) 요청 순번. 이 값과 다른 순번의 AI 결과는 화면에 보내지 않는다. AI 스레드도 읽는다. */
    volatile int activeSeq = -1;
    /** 지금 처리 중인 요청의 하루 횟수 차감·환불 상태 (DialogueService 가 관리). */
    DialogueService.TurnState turn;
    /** HUD가 보낸 마지막 요청 순번 (이보다 작거나 같은 요청은 버림). */
    int lastClientSeq = 0;
    /** 채팅 방식일 때 서버가 매기는 요청 순번. */
    int chatSeq = 0;
    /** 안내(INFO)·고정 반응이 대답하는 요청 순번 (마지막으로 받은 요청). */
    int replySeq = 0;
    int stallTaskId = -1;
    int timeoutTaskId = -1;
    boolean opened;

    // 불러오는 정보 (DB + MagicCodex)
    volatile boolean loaded;
    CompletableFuture<Void> loading = CompletableFuture.completedFuture(null);
    List<String> memos = new ArrayList<>();
    Storage.LastChat lastChat;
    List<RumorView> rumors = new ArrayList<>();
    List<String> promises = new ArrayList<>();
    String nickname;            // ChacaNPC 전체 호칭 (/호칭)
    String npcNickname;         // MagicCodex 호칭 이벤트로 정한 NPC별 호칭
    String storyChapter;
    Storage.HintState hints;
    Storage.QuestState quests;
    int score;
    int heart;

    // 이번 대화의 판단
    Boolean questRoll;
    boolean questOffered;
    boolean jealousDecided;
    boolean jealous;
    boolean jealousWithPromise;
    final Set<Long> usedRumors = new HashSet<>();

    public DialogueSession(UUID player, String playerName, CharacterSheet character, Integer citizensId,
                           boolean adminTest, long now, DialogueView view) {
        this.player = player;
        this.playerName = playerName;
        this.character = character;
        this.citizensId = citizensId;
        this.adminTest = adminTest;
        this.lastActivity = now;
        this.view = view;
    }

    public UUID player() {
        return player;
    }

    public String playerName() {
        return playerName;
    }

    public CharacterSheet character() {
        return character;
    }

    public Integer citizensId() {
        return citizensId;
    }

    public boolean adminTest() {
        return adminTest;
    }

    public String token() {
        return token;
    }

    public int lastClientSeq() {
        return lastClientSeq;
    }

    public void setLastClientSeq(int seq) {
        this.lastClientSeq = seq;
    }
}
