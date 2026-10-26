package school.magiccodex.paper;

import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** #38 운영자 AI 도우미: 답 읽기와 "실제 명령인지" 맞춰 보기 (AI 호출 없음). */
class OperatorHelpTest {
    static final List<CommandHub.CatalogLine> CATALOG = List.of(
        new CommandHub.CatalogLine("/유저관리 <닉네임>", "유저 요약"),
        new CommandHub.CatalogLine("/유저관리 <닉네임> 돈 추가 <금액>", "돈"),
        new CommandHub.CatalogLine("/유저관리 <닉네임> 우편 보내기 <제목…>", "우편"),
        new CommandHub.CatalogLine("/서버관리 새로고침 칭호", "칭호"));

    @Test void realCommandsPassAndInventedOnesAreDropped() {
        var p = OperatorHelp.patterns(CATALOG);
        assertTrue(OperatorHelp.valid("/유저관리 준우 돈 추가 100", p));
        assertTrue(OperatorHelp.valid("/유저관리 <닉네임> 돈 추가 <금액>", p));
        assertTrue(OperatorHelp.valid("/유저관리 준우 우편 보내기 사과 선물입니다", p), "last free-text slot takes the rest");
        assertTrue(OperatorHelp.valid("/서버관리 새로고침 칭호", p));
        assertFalse(OperatorHelp.valid("/유저관리 준우 돈 복구 100", p), "invented sub-command (the bare <닉네임> slot does not swallow it)");
        assertFalse(OperatorHelp.valid("/유저관리 준우 돈 추가 100 원", p), "a one-word slot takes one word");
        assertFalse(OperatorHelp.valid("/서버관리 새로고침 칭호 지금", p), "extra word after a fixed word");
        assertFalse(OperatorHelp.valid("유저관리 준우", p), "must start with /");
        assertFalse(OperatorHelp.valid("/돈주기 준우 100", p));
    }

    @Test void replyJsonAndPlainTextFallback() {
        var r = OperatorHelp.parse("{\"answer\":\"먼저 확인하세요\",\"commands\":[{\"command\":\"/유저관리 준우\",\"note\":\"요약\"}]}");
        assertEquals("먼저 확인하세요", r.answer());
        assertEquals(1, r.commands().size());
        assertEquals("/유저관리 준우", r.commands().get(0).command());
        var plain = OperatorHelp.parse("그냥 글");
        assertEquals("그냥 글", plain.answer());
        assertTrue(plain.commands().isEmpty());
    }
}
