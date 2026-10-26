package kr.chacademy.portrait.core;

/**
 * 대기열 순서: 등급(rank)이 낮을수록 먼저, 같은 등급 안에서는 먼저 들어온 순서(seq).
 * 등급은 관리자(0) → 다시 그리기(1) → 자동(2).
 */
public record JobOrder(int rank, long seq) implements Comparable<JobOrder> {

    public static final int ADMIN = 0, REROLL = 1, AUTO = 2;

    @Override
    public int compareTo(JobOrder o) {
        int c = Integer.compare(rank, o.rank);
        return c != 0 ? c : Long.compare(seq, o.seq);
    }

    /** 이 작업이 other보다 먼저 처리되는지. */
    public boolean before(JobOrder other) {
        return compareTo(other) < 0;
    }

    /**
     * 대략의 대기 시간(분). ahead = 내 앞에 대기 중인 작업 수, workers = 동시에 그리는 수, secondsPerJob = 한 장에 걸리는 시간 추정.
     * 앞 작업들이 빠질 때까지 + 내 그림 한 장.
     */
    public static int roughMinutes(int ahead, int workers, int secondsPerJob) {
        int w = Math.max(1, workers);
        long seconds = ((long) Math.max(0, ahead) / w + 1) * secondsPerJob + secondsPerJob;
        return (int) Math.max(1, (seconds + 59) / 60);
    }
}
