package kr.chacademy.story;
import org.junit.jupiter.api.Test;
import kr.chacademy.story.compat.MagicCodexClientLink;
import static org.junit.jupiter.api.Assertions.*;
class MagicCodexClientLinkTest {
    @Test void publicNicknameMethodWorks(){
        assertEquals("별빛",MagicCodexClientLink.nickname("Account"));
    }
}
