package dev.portablevfx.client;

import com.mojang.brigadier.CommandDispatcher;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import org.junit.jupiter.api.Test;
import java.util.Set;
import java.util.stream.Collectors;
import static org.junit.jupiter.api.Assertions.*;

final class ClientDebugCommandTest {
    @Test void localCommandsLiveOnlyUnderExplicitDebugNamespace() {
        var dispatcher = new CommandDispatcher<FabricClientCommandSource>();
        dispatcher.register(ClientCommands.debugRoot(null, null, null, null));
        assertEquals(Set.of("이펙트도구"), dispatcher.getRoot().getChildren().stream()
                .map(node -> node.getName()).collect(Collectors.toSet()));
        var debug = dispatcher.getRoot().getChild("이펙트도구");
        assertNotNull(debug.getCommand());
        assertEquals(Set.of("재생", "증명재생", "여기재생", "좌표재생", "발사", "따라가기",
                "지면발사", "폭발", "마무리", "충돌", "상태", "설정오류", "목록",
                "활성", "전부정지", "새로고침", "정지"), debug.getChildren().stream()
                .map(node -> node.getName()).collect(Collectors.toSet()));
        assertNull(dispatcher.getRoot().getChild("pvfxclient"));
        assertNull(dispatcher.getRoot().getChild("pvfxdebug"), "English debug root removed (2026-10-20)");
        assertNull(dispatcher.getRoot().getChild("마법"), "Normal spell command belongs to the server");
        assertNull(debug.getChild("fireball"));
        assertNull(debug.getChild("bloom"), "Claude authored HDR bloom must not use obsolete global bloom");
    }
}
