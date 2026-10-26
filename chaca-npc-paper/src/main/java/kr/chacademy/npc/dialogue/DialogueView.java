package kr.chacademy.npc.dialogue;

import kr.chacademy.npc.core.Defs.Button;
import org.bukkit.entity.Player;

import java.util.List;

/**
 * 대화를 화면에 보여주는 방법: 채팅(ChatDialogueView) 또는 MagicCodex HUD(HudDialogueView).
 * 모든 메서드는 메인 스레드에서 호출된다. seq = 이 화면 갱신이 대답하는 요청 순번.
 * 대사(line)는 서버 검사를 통과한 완성 문장만 온다 — 검사 전 조각은 보내지 않는다.
 */
public interface DialogueView {

    void open(Player player, DialogueSession session, String greeting, List<Button> buttons, int hearts);

    /** AI가 생각하는 중 (입력 직후). */
    void thinking(Player player, DialogueSession session, int seq);

    /** 3초 넘게 대답이 없을 때 이어 말하기 ("아, 잠깐만..."). */
    void stall(Player player, DialogueSession session, int seq, String line);

    /** 검사를 통과한 NPC 대사. HUD는 타자기 효과로 보여준다. */
    void line(Player player, DialogueSession session, int seq, String line);

    void questOffer(Player player, DialogueSession session, int seq, String title, String token);

    /** 선물 창 열기 (대화 버튼 3번). slots = 선물로 줄 수 있는 인벤토리 칸과 등급 (서버가 고른 것). */
    void gifts(Player player, DialogueSession session, int seq, List<GiftService.GiftItem> slots);

    /** 시스템 안내 (회색 글씨 등). session이 없으면 null. */
    void info(Player player, DialogueSession session, String message);

    /** 대화 종료. farewell이 null이면 조용히 닫는다. */
    void close(Player player, DialogueSession session, String farewell);
}
