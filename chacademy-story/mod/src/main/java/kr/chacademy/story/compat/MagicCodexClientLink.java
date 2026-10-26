package kr.chacademy.story.compat;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Map;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

/**
 * MagicCodex UI 모드(magiccodex)와의 연결. 두 모드는 따로 빌드되고 매핑도 달라서 (이 모드 Mojang, MagicCodex Yarn)
 * 리플렉션으로만 부른다. 부르는 메서드의 시그니처는 모두 JDK 타입뿐이다.
 * <ul>
 *   <li>한글 닉네임: school.magiccodex.client.NicknameClient.display(String)</li>
 *   <li><b>대화창</b>: school.magiccodex.client.api.ExternalDialogue 의 apiVersion() / show(Map, BiConsumer, Consumer) /
 *       close(String) / isShowing(String). 스토리 대화는 전부 이 창으로 보여 준다 (이 모드는 대화창을 그리지 않는다).
 *       "나"의 일러스트(ChacaPortrait)도 프레임의 playerPortrait 로 MagicCodex 가 그린다</li>
 * </ul>
 * MagicCodex 가 없거나 창구 버전이 낮으면 {@link #dialogueAvailable()} 이 false 이고, 어떤 호출도 예외를 밖으로 내지 않는다.
 */
public final class MagicCodexClientLink {
    /** 이 모드가 필요로 하는 ExternalDialogue.API_VERSION 의 최소값. */
    public static final int DIALOGUE_API_MIN = 1;
    static final String DIALOGUE_API_CLASS = "school.magiccodex.client.api.ExternalDialogue";

    private static final Logger LOGGER = LoggerFactory.getLogger("chaca_story");
    private static boolean looked;
    private static Method display;
    private static Method show, close, isShowing;
    private static int dialogueApi = -1;

    private MagicCodexClientLink() {}

    private static void look() {
        if (looked) return;
        looked = true;
        try {
            Class<?> nick = Class.forName("school.magiccodex.client.NicknameClient");
            display = nick.getMethod("display", String.class);
        } catch (ReflectiveOperationException | LinkageError | RuntimeException ignored) {
        }
        resolveDialogue(DIALOGUE_API_CLASS);
        LOGGER.info("MagicCodex 연결: 한글 닉네임 {}, 대화창 {}", display != null ? "O" : "X",
                show != null ? "O (API " + dialogueApi + ")" : dialogueApi >= 0 ? "X (API " + dialogueApi + " < " + DIALOGUE_API_MIN + ", MagicCodex 업데이트 필요)" : "X");
    }

    /** 대화창 창구를 찾는다. 클래스가 없거나, 버전이 낮거나, 메서드 모양이 다르면 쓰지 않는다. (테스트에서는 다른 클래스 이름으로 부른다) */
    static void resolveDialogue(String className) {
        show = close = isShowing = null;
        dialogueApi = -1;
        try {
            Class<?> api = Class.forName(className);
            Object v = api.getMethod("apiVersion").invoke(null);
            dialogueApi = v instanceof Integer version ? version : -1;
            if (dialogueApi < DIALOGUE_API_MIN) return;
            Method showMethod = api.getMethod("show", Map.class, BiConsumer.class, Consumer.class);
            Method closeMethod = api.getMethod("close", String.class);
            Method showingMethod = api.getMethod("isShowing", String.class);
            show = showMethod;
            close = closeMethod;
            isShowing = showingMethod;
        } catch (ReflectiveOperationException | LinkageError | RuntimeException e) {
            show = close = isShowing = null;
        }
    }

    /** MagicCodex 한글 닉네임. 모르면 fallback. */
    public static String nickname(String fallback) {
        look();
        if (display == null) return fallback;
        try {
            Object v = display.invoke(null, fallback);
            return v instanceof String s && !s.isBlank() ? s : fallback;
        } catch (ReflectiveOperationException | RuntimeException e) {
            return fallback;
        }
    }

    /** MagicCodex 대화창을 쓸 수 있는지 (모드가 있고 창구 버전이 {@link #DIALOGUE_API_MIN} 이상). */
    public static boolean dialogueAvailable() {
        look();
        return show != null;
    }

    /**
     * 프레임을 MagicCodex 대화창에 보여 준다 (클라이언트 스레드에서). 프레임 키는 ExternalDialogue 문서 참고.
     *
     * @param onChoice (session, 선택지 id). 선택지 없는 프레임을 넘기면 id 는 ""
     * @param onClosed (session). 이쪽이 닫지 않았는데 화면이 사라졌을 때
     * @return MagicCodex 가 받아들였으면 true
     */
    public static boolean showDialogue(Map<String, Object> frame, BiConsumer<String, String> onChoice, Consumer<String> onClosed) {
        look();
        if (show == null) return false;
        try {
            return Boolean.TRUE.equals(show.invoke(null, frame, onChoice, onClosed));
        } catch (InvocationTargetException e) {
            LOGGER.warn("MagicCodex 대화창 show 실패", e.getCause());
            return false;
        } catch (ReflectiveOperationException | RuntimeException e) {
            LOGGER.warn("MagicCodex 대화창 show 호출 실패: {}", e.toString());
            return false;
        }
    }

    /** 이쪽에서 대화를 끝낸다 (onClosed 는 오지 않는다). */
    public static void closeDialogue(String session) {
        look();
        if (close == null) return;
        try {
            close.invoke(null, session);
        } catch (InvocationTargetException e) {
            LOGGER.warn("MagicCodex 대화창 close 실패", e.getCause());
        } catch (ReflectiveOperationException | RuntimeException e) {
            LOGGER.warn("MagicCodex 대화창 close 호출 실패: {}", e.toString());
        }
    }

    /** 이 세션의 대화창이 지금 화면에 떠 있는지. */
    public static boolean dialogueShowing(String session) {
        look();
        if (isShowing == null) return false;
        try {
            return Boolean.TRUE.equals(isShowing.invoke(null, session));
        } catch (ReflectiveOperationException | RuntimeException e) {
            return false;
        }
    }
}
