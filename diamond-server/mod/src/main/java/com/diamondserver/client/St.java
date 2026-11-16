package com.diamondserver.client;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.List;

/** 서버가 알려 준 지금 상태 (남은 목표·보유 다이아·상점 목록·알림) */
public final class St {
    private St() { }

    public record Cat(String id, String label) { }
    public record Item(String id, String cat, String name, String desc, long price, int amount, String icon) { }
    public record Sell(String mat, String block, String name, double price) { }
    public record Toast(String text, boolean ok, long at) { }

    public static boolean has;          // 서버에서 상태를 한 번이라도 받았는지
    public static long goal, held;
    public static boolean done;
    public static long heldChangedAt;   // 보유 다이아가 바뀐 시각 (숫자 강조용)
    public static long heldDelta;
    public static long goalChangedAt;   // 남은 목표가 바뀐 시각
    public static long goalDelta;

    /** 후원 이벤트 알림 (화면 위쪽 가운데에 크게) */
    public record Banner(String donor, int count, String name, String kind, String target, long at) { }
    public static Banner banner;
    public static final long BANNER_MS = 3500;
    /** 줄 서 있는 이벤트 (맨 위가 진행 중) */
    public static final List<String> queue = new ArrayList<>();

    /** 지금 나에게 걸려 있는 효과 (이름 · 끝나는 시각) */
    public record Effect(String id, String name, long endsAt) { }
    public static final List<Effect> effects = new ArrayList<>();
    public static long reverseUntil;    // 이 시각까지 이동 조작 반전
    public static long blackoutUntil;   // 이 시각까지 화면 암전
    public static long blackoutFrom;

    /** 대상 룰렛: 이름들 사이를 돌다가 winner 에서 멈춘다 */
    public record Pick(String title, List<String> names, int winner, long at, long ms) { }
    public static Pick pick;
    public static final long PICK_HOLD_MS = 1800;   // 멈춘 뒤 결과를 보여 주는 시간

    public static final List<Cat> cats = new ArrayList<>();
    public static final List<Item> items = new ArrayList<>();
    public static final List<Sell> sells = new ArrayList<>();
    public static final List<Toast> toasts = new ArrayList<>();

    public static final long TOAST_MS = 3500;

    public static void clear() {
        has = false; goal = 0; held = 0; done = false; heldChangedAt = 0; heldDelta = 0; goalChangedAt = 0; goalDelta = 0;
        cats.clear(); items.clear(); sells.clear(); toasts.clear(); queue.clear(); banner = null;
        effects.clear(); reverseUntil = 0; blackoutUntil = 0; blackoutFrom = 0; pick = null;
    }

    static String str(JsonObject o, String k, String def) {
        JsonElement e = o.get(k);
        return e == null || e.isJsonNull() ? def : e.getAsString();
    }

    static long num(JsonObject o, String k, long def) {
        JsonElement e = o.get(k);
        try { return e == null || e.isJsonNull() ? def : e.getAsLong(); } catch (RuntimeException x) { return def; }
    }

    static void applyState(JsonObject o) {
        long newHeld = num(o, "held", 0);
        if (has && newHeld != held) { heldDelta = newHeld - held; heldChangedAt = System.currentTimeMillis(); }
        long newGoal = num(o, "goal", 0);
        if (has && newGoal != goal) { goalDelta = newGoal - goal; goalChangedAt = System.currentTimeMillis(); }
        goal = newGoal;
        held = newHeld;
        JsonElement d = o.get("done");
        done = d != null && !d.isJsonNull() && d.getAsBoolean();
        has = true;
    }

    static void applyShop(JsonObject o) {
        cats.clear(); items.clear(); sells.clear();
        JsonArray ca = o.getAsJsonArray("cats");
        if (ca != null) for (JsonElement e : ca) {
            JsonObject c = e.getAsJsonObject();
            cats.add(new Cat(str(c, "id", ""), str(c, "label", "")));
        }
        JsonArray ia = o.getAsJsonArray("items");
        if (ia != null) for (JsonElement e : ia) {
            JsonObject i = e.getAsJsonObject();
            items.add(new Item(str(i, "id", ""), str(i, "cat", ""), str(i, "name", ""), str(i, "desc", ""),
                    num(i, "price", 0), (int) num(i, "amount", 1), str(i, "icon", "minecraft:paper")));
        }
        JsonArray sa = o.getAsJsonArray("sell");
        if (sa != null) for (JsonElement e : sa) {
            JsonObject s = e.getAsJsonObject();
            double price = s.has("price") ? s.get("price").getAsDouble() : 0;
            sells.add(new Sell(str(s, "mat", ""), str(s, "block", ""), str(s, "name", ""), price));
        }
    }

    static void applyEvent(JsonObject o) {
        banner = new Banner(str(o, "donor", "익명"), (int) num(o, "count", 0), str(o, "name", ""), str(o, "kind", "neutral"),
                str(o, "target", ""), System.currentTimeMillis());
    }

    static void applyQueue(JsonObject o) {
        queue.clear();
        JsonArray a = o.getAsJsonArray("lines");
        if (a != null) for (JsonElement e : a) queue.add(e.getAsString());
    }

    static void applyEffects(JsonObject o) {
        effects.clear();
        long now = System.currentTimeMillis();
        JsonArray a = o.getAsJsonArray("list");
        if (a != null) for (JsonElement e : a) {
            JsonObject x = e.getAsJsonObject();
            effects.add(new Effect(str(x, "id", ""), str(x, "name", ""), now + num(x, "ms", 0)));
        }
    }

    static void applyFx(JsonObject o) {
        long now = System.currentTimeMillis(), ms = num(o, "ms", 0);
        switch (str(o, "name", "")) {
            case "reverse" -> reverseUntil = ms <= 0 ? 0 : now + ms;
            case "blackout" -> {
                if (ms > 0 && now >= blackoutUntil) blackoutFrom = now;
                blackoutUntil = ms <= 0 ? 0 : now + ms;
            }
            default -> { }
        }
    }

    static void applyPick(JsonObject o) {
        List<String> names = new ArrayList<>();
        JsonArray a = o.getAsJsonArray("names");
        if (a != null) for (JsonElement e : a) names.add(e.getAsString());
        int w = Math.max(0, names.indexOf(str(o, "winner", "")));
        if (!names.isEmpty()) pick = new Pick(str(o, "title", ""), names, w, System.currentTimeMillis(), Math.max(500, num(o, "ms", 3500)));
    }

    static void toast(String text, boolean ok) {
        if (text == null || text.isEmpty()) return;
        toasts.add(new Toast(text, ok, System.currentTimeMillis()));
        while (toasts.size() > 4) toasts.remove(0);
    }

    static void expireToasts() {
        long now = System.currentTimeMillis();
        toasts.removeIf(t -> now - t.at() > TOAST_MS);
    }
}
