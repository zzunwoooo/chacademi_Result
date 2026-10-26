package school.magiccodex.paper;

import java.util.UUID;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class PopularityRulesTest {
    private static final UUID A=UUID.fromString("00000000-0000-0000-0000-00000000000a"),B=UUID.fromString("00000000-0000-0000-0000-00000000000b"),C=UUID.fromString("00000000-0000-0000-0000-00000000000c");
    @Test void oncePerTargetPerDayThenTicket(){
        assertEquals(PopularityRules.Outcome.FREE,PopularityRules.decide(A,B,false,false));
        assertEquals(PopularityRules.Outcome.FREE,PopularityRules.decide(A,B,false,true)); // 해제권을 떼어 뒀어도 오늘 처음이면 쓰지 않는다 (서버가 돌려줌)
        assertEquals(PopularityRules.Outcome.LIMIT,PopularityRules.decide(A,B,true,false));
        assertEquals(PopularityRules.Outcome.TICKET,PopularityRules.decide(A,B,true,true));
        // 상대마다 따로: B 에게 했어도 C 는 오늘 처음
        assertEquals(PopularityRules.Outcome.FREE,PopularityRules.decide(A,C,false,false));
    }
    @Test void neverSelf(){
        assertEquals(PopularityRules.Outcome.SELF,PopularityRules.decide(A,A,false,false));
        assertEquals(PopularityRules.Outcome.SELF,PopularityRules.decide(A,A,true,true));
    }
    @Test void deltaClampAndText(){
        assertEquals(1,PopularityRules.delta(true));assertEquals(-1,PopularityRules.delta(false));
        assertEquals(PopularityRules.MAX_SCORE,PopularityRules.clamp(Long.MAX_VALUE));assertEquals(PopularityRules.MIN_SCORE,PopularityRules.clamp(-5_000_000L));
        assertEquals("준우님이 인기도를 올렸어요",PopularityRules.format("{player}님이 인기도를 올렸어요","준우"));
        assertEquals("누군가 인기도를 내렸어요",PopularityRules.format("누군가 인기도를 내렸어요","준우"));
    }
}
