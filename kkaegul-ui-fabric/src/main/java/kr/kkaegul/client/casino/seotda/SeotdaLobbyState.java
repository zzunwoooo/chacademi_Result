package kr.kkaegul.client.casino.seotda;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.util.ArrayList;
import java.util.List;

/** 섯다 방 목록 데이터 */
public final class SeotdaLobbyState {

    public record Member(String name, String avatar, boolean host) {
    }

    /** status: wait / play / full */
    public record Room(int id, String title, long ante, int count, String status, String host, List<Member> members) {
        public boolean joinable() {
            return status.equals("wait") && count < 3;
        }
    }

    public static List<Room> rooms = List.of();
    public static long balance, minAnte = 1000, maxAnte = 1_000_000;
    /** "seotda" 또는 "holdem" */
    public static String game = "seotda";
    /** 홀덤: 입장 칩 = BB × buyinBb, AI 대전 입장 칩 범위 */
    public static long buyinBb = 50, aiMin = 5000, aiMax = 5_000_000;
    /** 안내 팝업 (없으면 null) */
    public static String noticeTitle, noticeText;
    public static int version;
    /** 데모 (서버 없이 미리보기) */
    public static boolean demo;

    private SeotdaLobbyState() {
    }

    /** @return open 이면 1, 그 외 0 (type이 lobby가 아니면 -2) */
    public static int apply(String json) {
        try {
            JsonObject o = JsonParser.parseString(json).getAsJsonObject();
            if (!o.has("type") || !o.get("type").getAsString().equals("lobby")) return -2;
            balance = o.get("balance").getAsLong();
            game = o.has("game") ? o.get("game").getAsString() : "seotda";
            if (o.has("buyinBb")) buyinBb = o.get("buyinBb").getAsLong();
            if (o.has("aiMin")) aiMin = o.get("aiMin").getAsLong();
            if (o.has("aiMax")) aiMax = o.get("aiMax").getAsLong();
            if (o.has("minAnte")) minAnte = o.get("minAnte").getAsLong();
            if (o.has("maxAnte")) maxAnte = o.get("maxAnte").getAsLong();
            List<Room> list = new ArrayList<>();
            for (JsonElement e : o.getAsJsonArray("rooms")) {
                JsonObject r = e.getAsJsonObject();
                List<Member> mem = new ArrayList<>();
                JsonArray ma = r.getAsJsonArray("members");
                if (ma != null) for (JsonElement m : ma) {
                    JsonObject mo = m.getAsJsonObject();
                    mem.add(new Member(mo.get("name").getAsString(), mo.get("avatar").getAsString(), mo.get("host").getAsBoolean()));
                }
                list.add(new Room((int) r.get("id").getAsLong(), r.get("title").getAsString(), r.get("ante").getAsLong(),
                        (int) r.get("count").getAsLong(), r.get("status").getAsString(), r.get("host").getAsString(), mem));
            }
            rooms = list;
            if (o.has("notice")) {
                JsonObject n = o.getAsJsonObject("notice");
                noticeTitle = n.get("title").getAsString();
                noticeText = n.get("text").getAsString();
            }
            version++;
            return o.has("open") && o.get("open").getAsBoolean() ? 1 : 0;
        } catch (Exception e) {
            return 0;
        }
    }

    /** 서버 없이 화면 미리보기용 */
    public static void demo() {
        demo("seotda");
    }

    public static void demo(String g) {
        demo = true;
        game = g;
        if (g.equals("holdem")) {
            minAnte = 100;
            maxAnte = 100_000;
        } else {
            minAnte = 1000;
            maxAnte = 1_000_000;
        }
        balance = 100_000;
        List<Room> list = new ArrayList<>();
        list.add(new Room(1, "가볍게 한 판", 1_000, 2, "wait", "하늘",
                List.of(new Member("하늘", "bear", true), new Member("별이", "rabbit", false))));
        list.add(new Room(2, "초보 환영", 5_000, 1, "wait", "구름", List.of(new Member("구름", "fox", true))));
        list.add(new Room(3, "친구끼리 섯다", 10_000, 3, "play", "달빛",
                List.of(new Member("달빛", "cat", true), new Member("바람", "deer", false), new Member("새벽", "panda", false))));
        list.add(new Room(4, "저녁 한 판", 1_000, 3, "full", "노을",
                List.of(new Member("노을", "raccoon", true), new Member("이슬", "squirrel", false), new Member("소나기", "bear", false))));
        list.add(new Room(5, "고수만", 100_000, 1, "wait", "번개", List.of(new Member("번개", "panda", true))));
        list.add(new Room(6, "커피 한 잔", 5_000, 2, "wait", "모래",
                List.of(new Member("모래", "deer", true), new Member("파도", "cat", false))));
        rooms = list;
        version++;
    }
}
