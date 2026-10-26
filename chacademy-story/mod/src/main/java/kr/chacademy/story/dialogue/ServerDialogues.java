package kr.chacademy.story.dialogue;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 서버가 보낸 스토리 대화 내용 (dialogue.yml 글 그대로). 대화를 열기 직전에 서버가 보내고, 접속이 끊기면 지운다.
 * 있으면 config/chaca_dialogue 의 파일보다 먼저 쓴다 — 대사는 서버에서만 고치면 된다 (런처 배포 없음).
 * 그림은 리소스팩(chacademia_npc)에서 읽는다.
 */
public final class ServerDialogues {
    /** 한 대화 글의 최대 길이 (패킷 한도 1MiB 안). */
    public static final int MAX_CHARS = 300_000;
    private static final Map<String, String> TEXT = new ConcurrentHashMap<>();

    private ServerDialogues() {}

    public static void put(String id, String text) {
        if (id == null || text == null || !DialogueLoader.ID_PATTERN.matcher(id).matches() || text.length() > MAX_CHARS) return;
        TEXT.put(id, text);
    }

    public static String get(String id) { return id == null ? null : TEXT.get(id); }

    public static java.util.Set<String> ids() { return java.util.Set.copyOf(TEXT.keySet()); }

    public static void clear() { TEXT.clear(); }
}
