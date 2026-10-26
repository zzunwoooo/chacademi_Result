package school.magiccodex.client.api;

import java.util.Map;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import school.magiccodex.client.ExternalDialogueHost;

/**
 * 다른 클라 모드(차카데미 스토리 등)가 MagicCodex 대화창(패널·이름표·선택지·기록·소리 토글·타자기·내 차례)을
 * 그대로 빌려 쓰는 공개 창구. 대화 내용과 흐름은 부르는 쪽이 갖고, 화면만 MagicCodex 가 그린다.
 *
 * <p><b>시그니처에는 JDK 타입만 쓴다</b> (String/int/boolean/Map/List/Path/java.util.function).
 * 부르는 모드는 매핑이 달라도(Mojang ↔ Yarn) 리플렉션으로 그대로 부를 수 있다. 이 규칙을 깨면 {@link #API_VERSION} 을 올린다.
 *
 * <p>모든 메서드는 클라이언트(렌더) 스레드에서 부른다. 콜백도 그 스레드에서 온다.
 *
 * <p>프레임(Map) 키 — 모르는 키는 무시한다:
 * <ul>
 *   <li>{@code session} String, 필수. 부르는 쪽이 정한 대화 id. {@code [A-Za-z0-9_.:-]{1,80}}</li>
 *   <li>{@code sequence} int, 필수, 0 이상. 화면이 바뀔 때마다 커져야 한다. 같은 값이면 "같은 화면 다시 보이기",
 *       더 작은 값은 무시한다</li>
 *   <li>{@code title}(140자) {@code speaker}(64자) {@code text}(1600자) {@code message}(200자) String. 길면 자른다</li>
 *   <li>{@code portrait} String. MagicCodex 에 들어 있는 초상화 이름 ({@code textures/gui/dialogue/<이름>.png})</li>
 *   <li>{@code portraitFile} java.nio.file.Path (또는 String). 리소스팩 밖의 PNG. 한 변 2048px·8MB 이하만.
 *       렌더 스레드 밖에서 읽고, 같은 자리(700×1050 칸)에 비율을 지켜 맞춘다. {@code portrait} 보다 먼저 쓴다</li>
 *   <li>{@code playerPortrait} boolean. true 면 "내 일러스트"(ChacaPortrait)를 내 차례와 같은 자리에. 없으면 위 두 값으로</li>
 *   <li>{@code preload} List&lt;Path&gt; (6개까지). 곧 쓸 portraitFile 들을 미리 읽어 둔다</li>
 *   <li>{@code choices} List. 원소는 [id, 글] (List 또는 String[]). 6개까지, id 는 {@code [a-z0-9_-]{1,48}}.
 *       1개면 SPACE/클릭으로 바로 고르고, 2개 이상이면 버튼 + 고른 뒤 "내 차례"가 나온다 (서버 NPC 대화와 같음)</li>
 *   <li>{@code closable} boolean, 기본 true. false 면 "닫기 ×"가 없고 ESC 는 대화를 닫는 대신
 *       바닐라 게임 메뉴를 연다 (언제든 접속을 끊을 수 있게). 메뉴가 닫히면 부르는 쪽이 같은 프레임을 다시 show 한다</li>
 *   <li>{@code last} boolean, 기본 false. 선택지가 없을 때 아래 안내가 "다음" 대신 "닫기"로 나온다</li>
 * </ul>
 *
 * <p>대화창의 모양·글꼴·타자기 속도·글자 소리·기록·소리 토글은 MagicCodex 서버 NPC 대화와 완전히 같고, 부르는 쪽이 바꿀 수 없다.
 * 접속이 끊기거나 월드를 나가면 세션은 콜백 없이 사라진다 (부르는 쪽도 그때 자기 상태를 지운다).
 */
public final class ExternalDialogue {
    /** 이 창구의 버전. 시그니처나 프레임 키의 뜻이 바뀌면 올린다. */
    public static final int API_VERSION=1;
    private ExternalDialogue(){}

    public static int apiVersion(){return API_VERSION;}

    /**
     * 프레임을 보여 준다. 새 session 이면 대화창을 열고, 같은 session 이면 그 창의 내용을 바꾼다.
     * 외부 대화는 한 번에 하나다: 다른 session 이 열려 있었다면 그 세션은 콜백 없이 끝난다.
     * 서버 NPC 대화창이 떠 있었다면 닫고(서버에 알림) 연다. 외부 대화가 떠 있는 동안 온 서버 NPC 대화는 끝난 뒤에 열린다.
     *
     * @param onChoice (session, choiceId). 선택지를 골랐을 때. 선택지가 없는 프레임에서 넘기면 choiceId 는 "".
     *                 이 안에서 바로 다음 프레임을 show 하거나 close 해도 된다.
     * @param onClosed (session). 부르는 쪽의 close 가 아닌 이유로 화면이 사라졌을 때 (게임 메뉴, 다른 화면이 덮음, 사망 등).
     *                 이 안에서는 show 할 수 없다 (false). 다음 틱 이후에 화면이 비었을 때 다시 show 한다.
     *                 closable 프레임이었다면 세션은 여기서 끝난 것이다.
     * @return 받아들였으면 true. 형식 오류·월드 밖·다른 스레드·오래된 sequence 면 false.
     */
    public static boolean show(Map<String,Object> frame,BiConsumer<String,String> onChoice,Consumer<String> onClosed){
        return ExternalDialogueHost.show(frame,onChoice,onClosed);
    }

    /** 부르는 쪽이 대화를 끝낸다. onClosed 는 오지 않는다. 다른 session 이면 아무 일도 없다. */
    public static void close(String session){ExternalDialogueHost.close(session);}

    /** 이 session 의 대화창이 지금 화면에 떠 있는지. */
    public static boolean isShowing(String session){return ExternalDialogueHost.isShowing(session);}
}
