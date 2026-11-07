package kr.kkaegul.core.casino.holdem;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import kr.kkaegul.core.casino.holdem.Poker.Card;
import kr.kkaegul.core.casino.holdem.Poker.Hand;

/**
 * 홀덤 상태 → 클라이언트 JSON (AI 대전 · 플레이어 대전 공용). 자리는 보는 사람이 0번이 되도록 돌려서 보낸다.
 */
final class HoldemView {

    private HoldemView() {
    }

    /** 자리 정보 (화면용) */
    static final class SeatInfo {
        boolean empty;
        String id = "", avatar = "", name = "";
        long stack;
        boolean host, ready, inSession, out;
    }

    /**
     * 테이블 부분 (판돈·공용 패·차례·자리) 채우기.
     *
     * @param t        현재 판 (없으면 null)
     * @param me       보는 사람 자리
     * @param betting  지금 베팅 중인지
     * @param reveal   쇼다운 공개 중인지 (쉬는 시간)
     */
    static void fill(JsonObject o, HoldemTable t, int me, SeatInfo[] seats, boolean betting, boolean reveal,
                     long now, long turnStarted, long turnLimit) {
        o.addProperty("pot", t == null ? 0 : t.pot());
        o.addProperty("street", t == null ? -1 : t.street);
        JsonArray board = new JsonArray();
        if (t != null) for (Card c : t.board) board.add(c.id());
        o.add("board", board);
        int turn = betting && t != null && t.turn >= 0 ? (t.turn - me + 3) % 3 : -1;
        o.addProperty("turn", turn);
        if (betting && t != null && t.turn >= 0) {
            o.addProperty("timeLeft", Math.max(0, turnLimit - (now - turnStarted)));
            o.addProperty("turnLimit", turnLimit);
        }
        boolean meIn = t != null && t.present[me] && !t.folded[me];
        o.addProperty("toCall", meIn ? t.toCall(me) : 0);
        o.addProperty("canRaise", betting && meIn && t.turn == me && t.canRaise(me));
        o.addProperty("minRaiseTo", meIn ? t.minRaiseTo(me) : 0);
        o.addProperty("maxRaiseTo", meIn ? t.maxRaiseTo(me) : 0);
        o.addProperty("myBet", t == null ? 0 : t.bet[me]);
        o.addProperty("currentBet", t == null ? 0 : t.currentBet);

        // 쇼다운에서 이긴 사람들의 5장
        java.util.Set<String> bestIds = new java.util.HashSet<>();
        boolean[] winner = new boolean[3];
        if (reveal && t != null && t.over) {
            for (HoldemTable.PotResult pr : t.pots) for (int w : pr.winners()) winner[w] = true;
            if (t.showdown) for (int i = 0; i < 3; i++) if (winner[i]) {
                Hand h = t.handOf(i);
                if (h != null) for (Card c : h.best()) bestIds.add(c.id());
            }
        }
        JsonArray arr = new JsonArray();
        for (int v = 0; v < 3; v++) {
            int i = (me + v) % 3;
            SeatInfo s = seats[i];
            JsonObject j = new JsonObject();
            boolean inHand = t != null && t.present[i];
            j.addProperty("empty", s.empty && !inHand);
            j.addProperty("id", s.id);
            j.addProperty("avatar", s.avatar);
            j.addProperty("name", s.name);
            j.addProperty("stack", inHand ? t.stack[i] : s.stack);
            j.addProperty("bet", inHand ? t.bet[i] : 0);
            j.addProperty("host", s.host);
            j.addProperty("ready", s.ready);
            j.addProperty("inSession", s.inSession);
            j.addProperty("out", s.out && !inHand);
            j.addProperty("status", inHand ? t.status[i] : s.out ? "파산" : "");
            j.addProperty("folded", inHand && t.folded[i]);
            j.addProperty("allIn", inHand && t.allIn[i]);
            String badge = "";
            if (inHand) {
                if (i == t.dealer) badge = "D";
                if (i == t.sbSeat) badge = badge.isEmpty() ? "SB" : badge + "·SB";
                else if (i == t.bbSeat) badge = badge.isEmpty() ? "BB" : badge + "·BB";
            }
            j.addProperty("badge", badge);
            JsonArray cards = new JsonArray();
            String rank = "";
            if (inHand && t.hole[i] != null) {
                boolean show = v == 0 || (reveal && t.showdown && !t.folded[i]);
                for (Card c : t.hole[i]) cards.add(show ? c.id() : "back");
                if (show && t.board.size() >= 3) {
                    Hand h = t.handOf(i);
                    if (h != null) rank = h.name();
                }
            }
            j.add("cards", cards);
            j.addProperty("rank", rank);
            j.addProperty("winner", winner[i]);
            arr.add(j);
        }
        o.add("seats", arr);
        JsonArray best = new JsonArray();
        for (String id : bestIds) best.add(id);
        o.add("best", best);
    }

    /** 판 결과 (보는 사람 기준) */
    static JsonObject result(HoldemTable t, int me, int id, String[] names, long[] rake) {
        JsonObject r = new JsonObject();
        r.addProperty("id", id);
        long mine = t.won[me] - (rake == null ? 0 : rake[me]);
        r.addProperty("won", mine);
        r.addProperty("net", mine - t.total[me]);
        r.addProperty("byFold", !t.showdown);
        // 가장 많이 가져간 사람
        int top = -1;
        for (int i = 0; i < 3; i++) if (t.present[i] && (top < 0 || t.won[i] > t.won[top])) top = i;
        r.addProperty("winner", top < 0 ? -1 : (top - me + 3) % 3);
        r.addProperty("winnerName", top == me ? "나" : top < 0 ? "" : names[top]);
        long topWon = top < 0 ? 0 : t.won[top] - (rake == null ? 0 : rake[top]);
        r.addProperty("amount", topWon);
        String rank = "모두 폴드";
        if (t.showdown && top >= 0) {
            Hand h = t.handOf(top);
            if (h != null) rank = h.name();
        }
        r.addProperty("rank", rank);
        int split = 0;
        for (int i = 0; i < 3; i++) if (t.won[i] > 0) split++;
        r.addProperty("split", split > 1);
        r.addProperty("rake", rake == null ? 0 : rake[me]);
        return r;
    }
}
