package com.diamondserver.client;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 서버가 알려 준 지금 상태. 화면 코드(ui 패키지)는 이 값만 보고 그린다.
 * 게임 코드를 쓰지 않는 순수 자바라서 검수용 미리보기(tools/ui-review)도 같이 쓴다.
 */
public final class St {
    private St() { }

    public record Cat(String id, String label) { }
    /** 구매 상품. art = 받은 그림(ui 그림 이름), 없으면 icon(게임 아이템 id)을 그린다 */
    public record Item(String id, String cat, String name, String desc, long price, int amount, String icon, String art) { }
    public record Sell(String mat, String block, String name, double price) { }
    public record Toast(String text, boolean ok, long at) { }
    /** 후원 알림. kind = good / bad / neutral */
    public record Banner(String donor, int count, String name, String kind, String icon, String target, long at) { }
    public record Row(String icon, String text) { }
    /** 걸려 있는 효과. total = 전체 시간(ms) */
    public record Effect(String id, String name, String icon, long endsAt, long total) { }
    /** 대상 룰렛 (얼굴 칸) */
    public record Pick(String title, String icon, List<String> names, List<String> ids, int winner, long at, long ms) { }
    /** 1000개 룰렛 (원판) */
    public record Wheel(String title, List<String> labels, List<String> icons, int winner, long at, long ms) { }
    public record Announce(String icon, String title, String desc, long at) { }
    public record Death(String name, long at) { }
    /** 우편 한 통. text = 내용물 이름, art = 받은 그림(없으면 icon 의 게임 아이템 그림) */
    public record Letter(String id, String title, String from, String text, String art, String icon) { }
    public static final List<Letter> mails = new ArrayList<>();

    public static void applyMail(JsonObject o) {
        mails.clear();
        JsonArray a = o.getAsJsonArray("list");
        if (a != null) for (JsonElement e : a) {
            JsonObject m = e.getAsJsonObject();
            mails.add(new Letter(str(m, "id", ""), str(m, "title", ""), str(m, "from", ""), str(m, "text", ""), str(m, "art", ""), str(m, "icon", "minecraft:paper")));
        }
    }

    public static boolean has;          // 서버에서 상태를 한 번이라도 받았는지
    public static long goal, held;
    public static boolean done;
    public static long heldChangedAt, heldDelta, goalChangedAt, goalDelta;

    public static final List<Cat> cats = new ArrayList<>();
    public static final List<Item> items = new ArrayList<>();
    public static final List<Sell> sells = new ArrayList<>();
    public static final List<Toast> toasts = new ArrayList<>();
    public static final List<Row> queue = new ArrayList<>();
    public static final List<Effect> effects = new ArrayList<>();
    public static final Map<String, String> names = new HashMap<>();   // 계정 UUID → 한글 닉네임 (머리 위 이름표)

    public static Banner banner;
    public static Pick pick;
    public static Wheel wheel;
    public static Announce announce;
    public static Death death;
    public static long reverseUntil, blackoutUntil, blackoutFrom;

    public static final long TOAST_MS = 3500, BANNER_MS = 4000, ANNOUNCE_MS = 3500, DEATH_MS = 3500, GAIN_MS = 1500, HOLD_MS = 1800;

    public static void clear() {
        has = false; goal = 0; held = 0; done = false; heldChangedAt = 0; heldDelta = 0; goalChangedAt = 0; goalDelta = 0;
        cats.clear(); items.clear(); sells.clear(); toasts.clear(); queue.clear(); effects.clear(); names.clear(); mails.clear();
        banner = null; pick = null; wheel = null; announce = null; death = null;
        reverseUntil = 0; blackoutUntil = 0; blackoutFrom = 0;
    }

    public static String str(JsonObject o, String k, String def) {
        JsonElement e = o.get(k);
        return e == null || e.isJsonNull() ? def : e.getAsString();
    }

    public static long num(JsonObject o, String k, long def) {
        JsonElement e = o.get(k);
        try { return e == null || e.isJsonNull() ? def : e.getAsLong(); } catch (RuntimeException x) { return def; }
    }

    public static boolean bool(JsonObject o, String k, boolean def) {
        JsonElement e = o.get(k);
        try { return e == null || e.isJsonNull() ? def : e.getAsBoolean(); } catch (RuntimeException x) { return def; }
    }

    private static List<String> strings(JsonObject o, String k) {
        List<String> out = new ArrayList<>();
        JsonArray a = o.getAsJsonArray(k);
        if (a != null) for (JsonElement e : a) out.add(e.getAsString());
        return out;
    }

    public static void applyState(JsonObject o) {
        long now = System.currentTimeMillis();
        long newHeld = num(o, "held", 0), newGoal = num(o, "goal", 0);
        if (has && newHeld != held) { heldDelta = newHeld - held; heldChangedAt = now; }
        if (has && newGoal != goal) { goalDelta = newGoal - goal; goalChangedAt = now; }
        goal = newGoal;
        held = newHeld;
        done = bool(o, "done", false);
        has = true;
    }

    public static void applyShop(JsonObject o) {
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
                    num(i, "price", 0), (int) num(i, "amount", 1), str(i, "icon", "minecraft:paper"), str(i, "art", "")));
        }
        JsonArray sa = o.getAsJsonArray("sell");
        if (sa != null) for (JsonElement e : sa) {
            JsonObject s = e.getAsJsonObject();
            double price = s.has("price") ? s.get("price").getAsDouble() : 0;
            sells.add(new Sell(str(s, "mat", ""), str(s, "block", ""), str(s, "name", ""), price));
        }
    }

    public static void applyEvent(JsonObject o) {
        banner = new Banner(str(o, "donor", "익명"), (int) num(o, "count", 0), str(o, "name", ""), str(o, "kind", "neutral"),
                str(o, "icon", "icons/diamond"), str(o, "target", ""), System.currentTimeMillis());
    }

    public static void applyQueue(JsonObject o) {
        queue.clear();
        JsonArray a = o.getAsJsonArray("lines");
        if (a != null) for (JsonElement e : a) {
            if (e.isJsonObject()) queue.add(new Row(str(e.getAsJsonObject(), "icon", "icons/diamond"), str(e.getAsJsonObject(), "text", "")));
            else queue.add(new Row("icons/diamond", e.getAsString()));
        }
    }

    public static void applyEffects(JsonObject o) {
        effects.clear();
        long now = System.currentTimeMillis();
        JsonArray a = o.getAsJsonArray("list");
        if (a != null) for (JsonElement e : a) {
            JsonObject x = e.getAsJsonObject();
            long ms = num(x, "ms", 0);
            effects.add(new Effect(str(x, "id", ""), str(x, "name", ""), str(x, "icon", "icons/diamond"), now + ms, Math.max(1, num(x, "total", ms))));
        }
    }

    public static void applyFx(JsonObject o) {
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

    public static void applyPick(JsonObject o) {
        List<String> n = strings(o, "names"), ids = strings(o, "ids");
        if (n.isEmpty()) return;
        while (ids.size() < n.size()) ids.add("");
        int w = (int) Math.max(0, Math.min(n.size() - 1, num(o, "winner", 0)));
        pick = new Pick(str(o, "title", ""), str(o, "icon", "icons/diamond"), n, ids, w, System.currentTimeMillis(), Math.max(500, num(o, "ms", 3500)));
        wheel = null; announce = null;
    }

    public static void applyWheel(JsonObject o) {
        List<String> l = strings(o, "labels"), ic = strings(o, "icons");
        if (l.isEmpty()) return;
        while (ic.size() < l.size()) ic.add("icons/diamond");
        int w = (int) Math.max(0, Math.min(l.size() - 1, num(o, "winner", 0)));
        wheel = new Wheel(str(o, "title", ""), l, ic, w, System.currentTimeMillis(), Math.max(500, num(o, "ms", 3500)));
        pick = null; announce = null;
    }

    /** 발표 창이 뜨면 룰렛 화면은 치운다 (겹치지 않게) */
    public static void applyAnnounce(JsonObject o) {
        announce = new Announce(str(o, "icon", "icons/diamond"), str(o, "title", ""), str(o, "desc", ""), System.currentTimeMillis());
        pick = null; wheel = null;
    }

    public static void applyDeath(JsonObject o) {
        death = new Death(str(o, "name", ""), System.currentTimeMillis());
    }

    public static void applyNames(JsonObject o) {
        names.clear();
        JsonObject m = o.getAsJsonObject("map");
        if (m != null) for (String k : m.keySet()) names.put(k, m.get(k).getAsString());
    }

    public static void toast(String text, boolean ok) {
        if (text == null || text.isEmpty()) return;
        toasts.add(new Toast(text, ok, System.currentTimeMillis()));
        while (toasts.size() > 3) toasts.remove(0);
    }

    /** 시간이 지난 것들을 치운다 (매 틱) */
    public static void expire() {
        long now = System.currentTimeMillis();
        toasts.removeIf(t -> now - t.at() > TOAST_MS);
        effects.removeIf(e -> e.endsAt() <= now);
        if (banner != null && now - banner.at() > BANNER_MS) banner = null;
        if (announce != null && now - announce.at() > ANNOUNCE_MS) announce = null;
        if (death != null && now - death.at() > DEATH_MS) death = null;
        if (pick != null && now - pick.at() > pick.ms() + HOLD_MS) pick = null;
        if (wheel != null && now - wheel.at() > wheel.ms() + HOLD_MS) wheel = null;
    }
}
