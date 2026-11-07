package kr.kkaegul.client.casino.seotda;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/** 섯다 화면 데이터 (서버 또는 데모에서 받음) */
public final class SeotdaState {

    public static final class Seat {
        public String avatar = "bear", name = "", status = "", rank = "", id = "";
        public long chips, put;
        public boolean folded, empty, host, ready, out, inSession;
        public String[] cards = {"back", "back"};
    }

    public static long ante, pot, balance, toCall, minRaise, maxRaise, paid, timeLeft, turnLimit = 20000;
    public static String phase = "bet", log = "";
    public static int turn = -1, raisesLeft, rematches;
    public static boolean canRaise;
    /** PvP 방이면 true */
    public static boolean pvp;
    public static int roomId, roomCount;
    public static String roomTitle = "";
    public static boolean isHost, allReady, session, inSession;
    public static int hands;
    /** 판 결과 요약 (없으면 null): reason, net, hands, balance */
    public static JsonObject summary;
    public static double rake = 5;
    public static final Seat[] seats = {new Seat(), new Seat(), new Seat()};
    /** 결과 (없으면 null): kind win/lose/rematch, rank, cards, winnerName, pot, payout, net */
    public static JsonObject result;
    /** 받은 시각 (남은 시간 계산용) */
    public static long receivedAt;
    /** 이번 상태로 바뀐 횟수 (화면이 변화 감지용) */
    public static int version;

    private SeotdaState() {
    }

    /** @return "open":true 면 1, "close" 면 -1, 그 외 0 */
    public static int apply(String json) {
        try {
            JsonObject o = JsonParser.parseString(json).getAsJsonObject();
            String type = o.has("type") ? o.get("type").getAsString() : "state";
            if (type.equals("close")) return -1;
            if (!type.equals("state")) return 0;
            pvp = o.has("mode") && o.get("mode").getAsString().equals("pvp");
            if (pvp && o.has("room")) {
                JsonObject r = o.getAsJsonObject("room");
                roomId = (int) r.get("id").getAsLong();
                roomTitle = r.get("title").getAsString();
                isHost = r.get("isHost").getAsBoolean();
                roomCount = (int) r.get("count").getAsLong();
                allReady = r.get("allReady").getAsBoolean();
                rake = r.has("rake") ? r.get("rake").getAsDouble() : 5;
                session = r.has("session") && r.get("session").getAsBoolean();
                inSession = r.has("inSession") && r.get("inSession").getAsBoolean();
            } else {
                isHost = false;
                roomTitle = "";
            }
            ante = o.get("ante").getAsLong();
            pot = o.get("pot").getAsLong();
            balance = o.get("balance").getAsLong();
            phase = o.get("phase").getAsString();
            turn = (int) o.get("turn").getAsLong();
            timeLeft = o.get("timeLeft").getAsLong();
            turnLimit = o.has("turnLimit") ? o.get("turnLimit").getAsLong() : 20000;
            toCall = o.get("toCall").getAsLong();
            canRaise = o.get("canRaise").getAsBoolean();
            minRaise = o.get("minRaise").getAsLong();
            maxRaise = o.get("maxRaise").getAsLong();
            raisesLeft = (int) o.get("raisesLeft").getAsLong();
            paid = o.has("paid") ? o.get("paid").getAsLong() : 0;
            log = o.has("log") ? o.get("log").getAsString() : "";
            rematches = o.has("rematches") ? (int) o.get("rematches").getAsLong() : 0;
            JsonArray arr = o.getAsJsonArray("seats");
            for (int i = 0; i < 3 && i < arr.size(); i++) {
                JsonObject j = arr.get(i).getAsJsonObject();
                Seat s = seats[i];
                s.avatar = j.get("avatar").getAsString();
                s.name = j.get("name").getAsString();
                s.chips = j.get("chips").getAsLong();
                s.status = j.get("status").getAsString();
                s.put = j.get("put").getAsLong();
                s.folded = j.get("folded").getAsBoolean();
                JsonArray c = j.getAsJsonArray("cards");
                s.cards = new String[]{c.get(0).getAsString(), c.get(1).getAsString()};
                s.rank = j.has("rank") ? j.get("rank").getAsString() : "";
                s.empty = j.has("empty") && j.get("empty").getAsBoolean();
                s.id = j.has("id") ? j.get("id").getAsString() : "";
                s.host = j.has("host") && j.get("host").getAsBoolean();
                s.ready = j.has("ready") && j.get("ready").getAsBoolean();
                s.out = j.has("out") && j.get("out").getAsBoolean();
                s.inSession = j.has("inSession") && j.get("inSession").getAsBoolean();
            }
            result = o.has("result") ? o.getAsJsonObject("result") : null;
            summary = o.has("summary") ? o.getAsJsonObject("summary") : null;
            hands = o.has("hands") ? (int) o.get("hands").getAsLong() : 0;
            receivedAt = System.currentTimeMillis();
            version++;
            return o.has("open") && o.get("open").getAsBoolean() ? 1 : 0;
        } catch (Exception e) {
            return 0;
        }
    }

    /** 내 차례 남은 시간 (밀리초) */
    public static long remaining() {
        return Math.max(0, timeLeft - (System.currentTimeMillis() - receivedAt));
    }
}
