package kr.chacademi.dungeon;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.Minecraft;
import school.chacademia.dungeon.protocol.DungeonProtocol;

/**
 * 설정 도구 상태 (서버가 보낸 JSON 그대로 들고 있음). 판정·저장은 전부 서버.
 * <ul>
 *   <li>doors = 이 판의 닫힌 문 (모든 유저, 보라 홀로그램)</li>
 *   <li>view = 설정 도구를 든 운영진에게만: 고른 칸의 구역·방·몹 구역·문·표시점</li>
 *   <li>editor = 설정 창 내용</li>
 *   <li>placing = 기믹 설치 미리보기 중인 모델 (클라만, 우클릭으로 서버에 설치 요청)</li>
 * </ul>
 */
final class EditorState {
    private EditorState() {}

    static JsonObject doors = new JsonObject();
    static JsonObject view = new JsonObject();
    static JsonObject editor = new JsonObject();

    /** 설치 미리보기: 모델 id, 이름, 가로·깊이·높이 칸, 방향(0 남·1 서·2 북·3 동). model=null 이면 꺼짐. */
    static String placeModel, placeName;
    static int placeW = 1, placeD = 1, placeH = 1, placeFacing;

    static void clear() {
        doors = new JsonObject(); view = new JsonObject(); editor = new JsonObject();
        placeModel = null;
    }

    static void receive(int kind, String json) {
        JsonObject o;
        try { o = JsonParser.parseString(json).getAsJsonObject(); } catch (RuntimeException e) { return; }
        switch (kind) {
            case DungeonProtocol.K_DOORS -> doors = o;
            case DungeonProtocol.K_VIEW -> view = o;
            case DungeonProtocol.K_EDITOR -> {
                editor = o;
                var mc = Minecraft.getInstance();
                if (mc.screen instanceof EditorScreen s) s.refresh();
                else if (o.has("open") && o.get("open").getAsBoolean()) DungeonClient.open(new EditorScreen());
            }
            default -> { }
        }
    }

    /** 설정 동작 보내기: {"a": 동작, ...}. */
    static void send(JsonObject action) {
        if (!ClientPlayNetworking.canSend(Net.ToServer.TYPE)) return;
        ClientPlayNetworking.send(new Net.ToServer(DungeonProtocol.encode(new DungeonProtocol.Action(DungeonProtocol.C_EDIT, 0, action.toString()))));
    }

    static void send(String action, Object... kv) {
        var o = new JsonObject();
        o.addProperty("a", action);
        for (int i = 0; i + 1 < kv.length; i += 2) {
            Object v = kv[i + 1];
            if (v instanceof Number n) o.addProperty((String) kv[i], n);
            else if (v instanceof Boolean b) o.addProperty((String) kv[i], b);
            else if (v instanceof com.google.gson.JsonElement e) o.add((String) kv[i], e);
            else o.addProperty((String) kv[i], String.valueOf(v));
        }
        send(o);
    }

    static String str(JsonObject o, String k) { return o != null && o.has(k) && !o.get(k).isJsonNull() ? o.get(k).getAsString() : ""; }
    static int num(JsonObject o, String k, int def) { try { return o != null && o.has(k) ? o.get(k).getAsInt() : def; } catch (RuntimeException e) { return def; } }
}
