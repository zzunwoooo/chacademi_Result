package kr.chacademy.story.dialogue;

import kr.chacademy.story.compat.MagicCodexClientLink;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

/**
 * 진행 중인 스토리 대화 하나를 MagicCodex 대화창에 띄워 둔다. 이 모드는 대화창을 직접 그리지 않는다.
 * <ul>
 *   <li>{@link DialogueRunner} 가 내놓는 프레임을 MagicCodex 로 넘기고, 대화창에서 온 입력을 진행기로 돌려준다</li>
 *   <li>끝나기 전에는 닫을 수 없다: 게임 메뉴(ESC)나 다른 화면이 덮었다가 화면이 비면 같은 프레임을 다시 띄운다</li>
 * </ul>
 * 모두 클라이언트 스레드에서 부른다.
 */
public final class DialoguePresenter {
    private static DialogueRunner active = null;
    private static long reopenAt = 0;
    private static boolean warned = false;
    private static boolean serverOwned;
    public static boolean isLocal(String id) { return active!=null&&!serverOwned&&active.dialogueId().equals(id); }
    public static boolean serverOwned() { return active != null && serverOwned; }

    private DialoguePresenter() {}

    /** MagicCodex 대화창을 쓸 수 있는지. false 면 대화를 열 수 없다 (자체 대화창으로 대신하지 않는다). */
    public static boolean available() {
        return MagicCodexClientLink.dialogueAvailable();
    }

    /** MagicCodex 가 없거나 오래됐다고 플레이어에게 한 번만 알린다. */
    public static void warnUnavailable() {
        if (warned) return;
        var p = Minecraft.getInstance().player;
        if (p == null) return;
        warned = true;
        p.displayClientMessage(Component.literal("스토리 대화를 보려면 MagicCodex 모드(최신 버전)가 필요합니다.")
                .withStyle(ChatFormatting.RED), false);
    }

    /** 대화를 시작한다. 앞 대화가 있으면 멈춘다. 이미 끝난 진행기(빈 대화)는 띄우지 않는다. */
    public static boolean start(DialogueRunner runner, boolean fromServer) {
        stop();
        if (runner.isEnded()) return false;
        active = runner;
        serverOwned = fromServer;
        reopenAt = System.currentTimeMillis() + 500;
        if (show(runner)) return true;
        stop();
        return false;
    }

    /** 서버가 멈추라고 할 때·접속이 끊길 때. 끝난 것으로 치지 않는다. */
    public static void stop() {
        DialogueRunner r = active;
        active = null;
        if (r == null) return;
        r.stop();
        MagicCodexClientLink.closeDialogue(r.session());
    }

    public static boolean isActive() {
        return active != null;
    }

    /** 매 틱. 대화가 남아 있는데 화면이 비어 있으면 (게임 메뉴를 닫았거나 다른 화면이 닫힘) 다시 띄운다. */
    public static void tick(Minecraft client) {
        DialogueRunner r = active;
        if (r == null) return;
        if (r.isEnded()) {
            active = null;
            return;
        }
        if (client.screen == null && client.player != null && System.currentTimeMillis() >= reopenAt) {
            reopenAt = System.currentTimeMillis() + 500;
            show(r);
        }
    }

    private static boolean show(DialogueRunner r) {
        return MagicCodexClientLink.showDialogue(r.frame(), (session, choice) -> onChoice(r, choice), session -> {
            // 화면이 사라짐 (게임 메뉴 등). 닫을 수 없는 대화라 세션은 살아 있고, tick 이 화면이 빌 때 다시 띄운다
        });
    }

    private static void onChoice(DialogueRunner r, String choice) {
        if (active != r) return;
        r.choose(choice);
        if (active != r) return;
        if (r.isEnded()) {
            active = null;
            MagicCodexClientLink.closeDialogue(r.session());
        } else show(r);
    }
}
