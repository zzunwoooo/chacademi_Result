package kr.chacademy.portrait.core;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class JobOrderTest {

    @Test
    void ranksAdminThenRerollThenAutoAndKeepsArrivalOrderWithinRank() {
        List<JobOrder> jobs = new ArrayList<>(List.of(
                new JobOrder(JobOrder.AUTO, 1), new JobOrder(JobOrder.REROLL, 2), new JobOrder(JobOrder.AUTO, 3),
                new JobOrder(JobOrder.ADMIN, 4), new JobOrder(JobOrder.REROLL, 5), new JobOrder(JobOrder.ADMIN, 6)));
        Collections.shuffle(jobs, new java.util.Random(7));
        Collections.sort(jobs);
        assertEquals(List.of(new JobOrder(JobOrder.ADMIN, 4), new JobOrder(JobOrder.ADMIN, 6),
                new JobOrder(JobOrder.REROLL, 2), new JobOrder(JobOrder.REROLL, 5),
                new JobOrder(JobOrder.AUTO, 1), new JobOrder(JobOrder.AUTO, 3)), jobs);
        assertTrue(new JobOrder(JobOrder.REROLL, 100).before(new JobOrder(JobOrder.AUTO, 1)));
        assertFalse(new JobOrder(JobOrder.AUTO, 1).before(new JobOrder(JobOrder.AUTO, 1)));
        assertTrue(new JobOrder(JobOrder.AUTO, 1).before(new JobOrder(JobOrder.AUTO, 2)));
    }

    @Test
    void roughWaitGrowsWithQueueAndShrinksWithWorkers() {
        assertEquals(3, JobOrder.roughMinutes(0, 2, 90));   // 앞 작업이 끝나길 기다림 + 내 그림
        assertEquals(6, JobOrder.roughMinutes(4, 2, 90));
        assertEquals(9, JobOrder.roughMinutes(4, 1, 90));
        assertTrue(JobOrder.roughMinutes(40, 2, 90) > JobOrder.roughMinutes(4, 2, 90));
        assertTrue(JobOrder.roughMinutes(-5, 0, 90) >= 1);
    }
}
