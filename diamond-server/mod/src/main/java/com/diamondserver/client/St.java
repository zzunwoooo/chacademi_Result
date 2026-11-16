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

    public static final List<Cat> cats = new ArrayList<>();
    public static final List<Item> items = new ArrayList<>();
    public static final List<Sell> sells = new ArrayList<>();
    public static final List<Toast> toasts = new ArrayList<>();

    public static final long TOAST_MS = 3500;

    public static void clear() {
        has = false; goal = 0; held = 0; done = false; heldChangedAt = 0; heldDelta = 0;
        cats.clear(); items.clear(); sells.clear(); toasts.clear();
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
        goal = num(o, "goal", 0);
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
