package kr.kkaegul.client.casino.holdem;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.util.ArrayList;
import java.util.List;

/** 홀덤 화면 데이터 (서버 또는 데모에서 받음). 자리 0은 항상 나. */
public final class HoldemState {

    public static final class Seat {
        public String avatar = "bear", name = "", status = "", rank = "", id = "", badge = "";
        public long stack, bet;
        public boolean folded, allIn, empty, host, ready, out, inSession, winner;
        public String[] cards = {};
    }

    public static boolean pvp;
    public static String phase = "bet", log = "";
    public static long sb, bb, buyIn, balance, pot, toCall, minRaiseTo, maxRaiseTo, myBet, currentBet, timeLeft, turnLimit = 20000;
    public static int turn = -1, street = -1, hands;
    public static boolean canRaise;
    public static List<String> board = List.of();
    public static List<String> best = List.of();
    public static final Seat[] seats = {new Seat(), new Seat(), new Seat()};
    public static JsonObject result, summary;
    // 방
    public static int roomId, roomCount;
    public static String roomTitle = "";
    public static boolean isHost, allReady, session, inSession;
    public static double rake = 5;
    public static long receivedAt;
    public static int version;

    private HoldemState() {
    }

    /** @return open이면 1, close면 -1, 그 외 0 */
    public static int apply(String json) {
        try {
            JsonObject o = JsonParser.parseString(json).getAsJsonObject();
            String type = o.has("type") ? o.get("type").getAsString() : "state";
            if (type.equals("close")) return -1;
            if (!type.equals("state")) return 0;
            pvp = o.has("mode") && o.get("mode").getAsString().equals("pvp");
            phase = o.get("phase").getAsString();
            log = s(o, "log");
            sb = l(o, "sb");
            bb = l(o, "bb");
            buyIn = l(o, "buyIn");
            balance = l(o, "balance");
            pot = l(o, "pot");
            toCall = l(o, "toCall");
            minRaiseTo = l(o, "minRaiseTo");
            maxRaiseTo = l(o, "maxRaiseTo");
            myBet = l(o, "myBet");
            currentBet = l(o, "currentBet");
            timeLeft = l(o, "timeLeft");
            turnLimit = o.has("turnLimit") ? l(o, "turnLimit") : 20000;
            turn = o.has("turn") ? (int) l(o, "turn") : -1;
            street = o.has("street") ? (int) l(o, "street") : -1;
            hands = (int) l(o, "hands");
            canRaise = o.has("canRaise") && o.get("canRaise").getAsBoolean();
            board = strs(o, "board");
            best = strs(o, "best");
            if (pvp && o.has("room")) {
                JsonObject r = o.getAsJsonObject("room");
                roomId = (int) l(r, "id");
                roomTitle = s(r, "title");
                isHost = b(r, "isHost");
                roomCount = (int) l(r, "count");
                allReady = b(r, "allReady");
                rake = r.has("rake") ? r.get("rake").getAsDouble() : 5;
                session = b(r, "session");
                inSession = b(r, "inSession");
            } else {
                isHost = false;
                roomTitle = "";
                session = true;
                inSession = true;
            }
            JsonArray arr = o.getAsJsonArray("seats");
            for (int i = 0; i < 3 && arr != null && i < arr.size(); i++) {
                JsonObject j = arr.get(i).getAsJsonObject();
                Seat st = seats[i];
                st.empty = b(j, "empty");
                st.id = s(j, "id");
                st.avatar = s(j, "avatar");
                st.name = s(j, "name");
                st.stack = l(j, "stack");
                st.bet = l(j, "bet");
                st.host = b(j, "host");
                st.ready = b(j, "ready");
                st.inSession = b(j, "inSession");
                st.out = b(j, "out");
                st.status = s(j, "status");
                st.folded = b(j, "folded");
                st.allIn = b(j, "allIn");
                st.badge = s(j, "badge");
                st.rank = s(j, "rank");
                st.winner = b(j, "winner");
                st.cards = strs(j, "cards").toArray(new String[0]);
            }
            result = o.has("result") ? o.getAsJsonObject("result") : null;
            summary = o.has("summary") ? o.getAsJsonObject("summary") : null;
            receivedAt = System.currentTimeMillis();
            version++;
            return o.has("open") && o.get("open").getAsBoolean() ? 1 : 0;
        } catch (Exception e) {
            return 0;
        }
    }

    public static long remaining() {
        return Math.max(0, timeLeft - (System.currentTimeMillis() - receivedAt));
    }

    private static String s(JsonObject o, String k) {
        return o.has(k) && !o.get(k).isJsonNull() ? o.get(k).getAsString() : "";
    }

    private static long l(JsonObject o, String k) {
        return o.has(k) ? o.get(k).getAsLong() : 0;
    }

    private static boolean b(JsonObject o, String k) {
        return o.has(k) && o.get(k).getAsBoolean();
    }

    private static List<String> strs(JsonObject o, String k) {
        List<String> out = new ArrayList<>();
        if (o.has(k)) for (JsonElement e : o.getAsJsonArray(k)) out.add(e.getAsString());
        return out;
    }
}
