package kr.chacademy.npc.dialogue;

import kr.chacademy.npc.core.Defs.Button;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextComponent;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import org.bukkit.entity.Player;

import java.util.List;

/**
 * 채팅 대화 화면 (MagicCodex HUD 대화를 지원하지 않는 클라이언트용 대체 화면).
 * 플레이어는 /말 <할 말> 로 대화한다. "생각 중" 표시는 하지 않는다 (오너 결정: 액션바 없음).
 */
public final class ChatDialogueView implements DialogueView {

    private static TextColor nameColor(DialogueSession s) {
        String hex = s.character().color();
        if (hex != null) {
            TextColor c = TextColor.fromHexString(hex.startsWith("#") ? hex : "#" + hex);
            if (c != null) {
                return c;
            }
        }
        return NamedTextColor.GOLD;
    }

    private static Component npcLine(DialogueSession s, String text) {
        return Component.text("[" + s.character().name() + "] ", nameColor(s))
                .append(Component.text(text, NamedTextColor.WHITE));
    }

    @Override
    public void open(Player player, DialogueSession session, String greeting, List<Button> buttons, int hearts) {
        player.sendMessage(Component.empty());
        player.sendMessage(npcLine(session, greeting));
        TextComponent.Builder row = Component.text().content("  ");
        for (Button b : buttons) {
            row.append(Component.text("[" + b.label() + "]", NamedTextColor.AQUA)
                    .clickEvent(ClickEvent.runCommand("/대화선택 버튼 " + b.id()))
                    .hoverEvent(HoverEvent.showText(Component.text("클릭해서 말하기"))));
            row.append(Component.text(" "));
        }
        player.sendMessage(row.build());
        player.sendMessage(Component.text("  /말 <할 말> 로 대화, /말 그만 으로 끝내기", NamedTextColor.DARK_GRAY));
    }

    @Override
    public void thinking(Player player, DialogueSession session, int seq) {
        // 채팅 화면에서는 "생각 중" 표시를 하지 않는다
    }

    @Override
    public void stall(Player player, DialogueSession session, int seq, String line) {
        // 채팅 화면에서는 "잠깐만..." 이어 말하기를 보여주지 않는다 (대답이 오면 바로 채팅으로)
    }

    @Override
    public void line(Player player, DialogueSession session, int seq, String line) {
        player.sendMessage(npcLine(session, line));
    }

    @Override
    public void questOffer(Player player, DialogueSession session, int seq, String title, String token) {
        player.sendMessage(Component.text("  부탁: " + title + " ", NamedTextColor.YELLOW)
                .append(Component.text("[수락]", NamedTextColor.GREEN)
                        .clickEvent(ClickEvent.runCommand("/대화선택 부탁 수락 " + token)))
                .append(Component.text(" "))
                .append(Component.text("[거절]", NamedTextColor.RED)
                        .clickEvent(ClickEvent.runCommand("/대화선택 부탁 거절 " + token))));
    }

    @Override
    public void gifts(Player player, DialogueSession session, int seq, List<GiftService.GiftItem> slots) {
        // 채팅 화면: 줄 수 있는 선물을 눌러서 고른다 (서버가 다시 검사)
        Component row = Component.text("  선물 고르기: ", NamedTextColor.GOLD);
        for (GiftService.GiftItem g : slots) {
            row = row.append(Component.text("[" + g.name() + "]", NamedTextColor.YELLOW)
                    .clickEvent(ClickEvent.runCommand("/대화선택 선물 " + g.slot()))).append(Component.text(" "));
        }
        player.sendMessage(row);
    }

    @Override
    public void info(Player player, DialogueSession session, String message) {
        player.sendMessage(Component.text(message, NamedTextColor.GRAY));
    }

    @Override
    public void close(Player player, DialogueSession session, String farewell) {
        if (farewell != null) {
            player.sendMessage(npcLine(session, farewell));
        }
        player.sendMessage(Component.text("  (" + session.character().name() + "와의 대화가 끝났어요)", NamedTextColor.DARK_GRAY));
    }
}
