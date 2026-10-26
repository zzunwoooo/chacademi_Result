package kr.chacademy.npc.dialogue;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** #99: 같은 NPC 와 여러 명이 동시에 대화할 때 NPC 는 가장 최근에 말한 한 명만 바라본다 (번갈아 돌아서지 않음). */
class SharedNpcFacingTest {
    private record Talk(String who, Integer npc, long at) {}

    @Test void newestTalkerPerNpcOnly() {
        var a = new Talk("a", 7, 100);
        var b = new Talk("b", 7, 300);
        var c = new Talk("c", 9, 50);
        var admin = new Talk("admin", null, 999);
        Map<Integer, Talk> out = DialogueService.newestPerNpc(List.of(a, b, c, admin), Talk::npc, Talk::at);
        assertSame(b, out.get(7), "같은 NPC: 나중에 말한 사람");
        assertSame(c, out.get(9), "다른 NPC 는 따로");
        assertEquals(2, out.size(), "NPC 없는 세션(관리자 시험)은 빠짐");
    }

    @Test void tieKeepsFirstSoFacingDoesNotFlip() {
        var a = new Talk("a", 7, 100);
        var b = new Talk("b", 7, 100);
        assertSame(a, DialogueService.newestPerNpc(List.of(a, b), Talk::npc, Talk::at).get(7));
    }
}
