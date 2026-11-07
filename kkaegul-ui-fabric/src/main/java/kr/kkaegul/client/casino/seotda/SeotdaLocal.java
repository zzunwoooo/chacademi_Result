package kr.kkaegul.client.casino.seotda;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import kr.kkaegul.client.casino.seotda.engine.Seotda;
import kr.kkaegul.client.casino.seotda.engine.Seotda.Hand;
import kr.kkaegul.client.casino.seotda.engine.Seotda.Outcome;
import kr.kkaegul.client.casino.seotda.engine.SeotdaTable;
import kr.kkaegul.client.casino.seotda.engine.SeotdaTable.Move;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 서버 없이 섯다 AI 대전 미리보기 (/elseahud seotda 판돈). 서버(ElseaCasino)의 진행을 그대로 흉내 낸다.
 * 한 판 = 파산하거나 그만둘 때까지 이어지는 턴들. 돈은 가짜 (50만 원으로 시작).
 */
public final class SeotdaLocal {

    private static final List<String> AVATARS = List.of("bear", "rabbit", "fox", "raccoon", "cat", "squirrel", "deer", "panda");
    private static final Map<String, String> NAMES = Map.of(
            "bear", "밤톨", "rabbit", "모찌", "fox", "유자", "raccoon", "도토",
            "cat", "치즈", "squirrel", "호두", "deer", "새봄", "panda", "두부");
    private static final long TURN_MS = 20_000, BREAK_MS = 4_000;

    private enum Phase { BET, BREAK, END }

    private static boolean active;
    private static long balance = 500_000;
    private static long ante, paid, turnStarted, aiAt, rematchUntil, breakUntil, startBalance;
    private static SeotdaTable table;
    private static Phase phase = Phase.BET;
    private static JsonObject resultJson, summaryJson;
    private static List<String> avatars = List.of();
    private static final long[] aiChips = new long[3];
    private static final boolean[] aiOut = new boolean[3];
    private static String log = "";
    private static int hands;

    private SeotdaLocal() {
    }

    public static boolean active() {
        return active;
    }

    public static boolean start(long a) {
        ante = a;
        active = true;
        if (!newSession()) {
            active = false;
            return false;
        }
        push(true);
        return true;
    }

    private static boolean newSession() {
        List<String> pool = new ArrayList<>(AVATARS), out = new ArrayList<>();
        for (int seat = 0; seat < 3; seat++) {
            String old = avatars.size() > seat ? avatars.get(seat) : null;
            List<String> ch = new ArrayList<>(pool);
            ch.remove(old);
            String pick = ch.get(ThreadLocalRandom.current().nextInt(ch.size()));
            out.add(pick);
            pool.remove(pick);
        }
        avatars = out;
        for (int i = 1; i <= 2; i++) {
            aiChips[i] = ante * (30 + ThreadLocalRandom.current().nextInt(51));
            aiOut[i] = false;
        }
        hands = 0;
        summaryJson = null;
        startBalance = balance;
        return newHand();
    }

    private static boolean newHand() {
        if (balance < ante) return false;
        boolean[] present = {true, false, false};
        long[] banks = new long[3];
        for (int i = 1; i <= 2; i++) {
            if (aiChips[i] < ante) aiOut[i] = true;
            present[i] = !aiOut[i];
        }
        if (!present[1] && !present[2]) return false;
        balance -= ante;
        for (int i = 1; i <= 2; i++) {
            if (!present[i]) continue;
            aiChips[i] -= ante;
            banks[i] = aiChips[i];
        }
        table = new SeotdaTable(SeotdaTable.Config.defaults(), ThreadLocalRandom.current(), ante, balance, present, banks);
        paid = ante;
        phase = Phase.BET;
        resultJson = null;
        rematchUntil = 0;
        hands++;
        log = hands == 1 ? "판돈 " + num(ante) + "원씩 걸고 시작!" : hands + "번째 턴 · 판돈 " + num(ante) + "원씩";
        startTurn();
        return true;
    }

    private static void endSession(String reason) {
        phase = Phase.END;
        JsonObject o = new JsonObject();
        o.addProperty("reason", reason);
        o.addProperty("net", balance - startBalance);
        o.addProperty("hands", hands);
        o.addProperty("balance", balance);
        summaryJson = o;
        log = reason;
    }

    private static void startTurn() {
        turnStarted = System.currentTimeMillis();
        aiAt = turnStarted + 700 + ThreadLocalRandom.current().nextInt(900);
    }

    /** 매 틱 */
    public static void tick() {
        if (!active) return;
        long now = System.currentTimeMillis();
        if (phase == Phase.BREAK) {
            if (now >= breakUntil) nextHandOrEnd();
            return;
        }
        if (phase != Phase.BET) return;
        if (rematchUntil > 0) {
            if (now < rematchUntil) return;
            rematchUntil = 0;
            startTurn();
            push(false);
            return;
        }
        if (table.turn == 0 && now - turnStarted > TURN_MS) {
            act(Move.die());
            if (phase == Phase.BET) {
                log = "시간이 지나서 자동으로 다이했어요";
                push(false);
            }
        } else if (table.turn > 0 && now >= aiAt) {
            int seat = table.turn;
            Move m = table.aiMove(seat);
            long before = table.put[seat];
            table.apply(seat, m);
            long p = table.put[seat] - before;
            aiChips[seat] -= p;
            String n = NAMES.get(avatars.get(seat));
            log = switch (m.act()) {
                case DIE -> n + "(이)가 다이했어요";
                case CALL -> p > 0 ? n + "(이)가 " + num(p) + "원 콜했어요" : n + "(이)가 체크했어요";
                case RAISE -> n + "(이)가 " + num(p) + "원을 걸었어요 (레이즈)";
            };
            after();
        }
    }

    private static void nextHandOrEnd() {
        if (balance < ante) endSession("보유 금액이 판돈보다 적어서 판이 끝났어요");
        else if (aiChips[1] < ante && aiChips[2] < ante) endSession("AI가 모두 파산했어요!");
        else if (!newHand()) endSession("판이 끝났어요");
        push(false);
    }

    public static void handle(String json) {
        if (!active) return;
        JsonObject o = JsonParser.parseString(json).getAsJsonObject();
        String a = o.get("action").getAsString();
        long amount = o.has("amount") ? o.get("amount").getAsLong() : 0;
        switch (a) {
            case "seotda_die" -> act(Move.die());
            case "seotda_call" -> act(Move.call());
            case "seotda_raise" -> act(Move.raise(amount));
            case "seotda_next" -> {
                if (phase == Phase.BREAK) nextHandOrEnd();
                else if (phase == Phase.END) {
                    if (!newSession()) endSession("보유 금액이 판돈보다 적어서 새 판을 시작할 수 없어요");
                    push(false);
                }
            }
            case "seotda_stop" -> {
                if (phase == Phase.BREAK) {
                    endSession("그만하기를 눌러서 판이 끝났어요");
                    push(false);
                }
            }
            case "seotda_close" -> active = false;
            default -> {
            }
        }
    }

    private static void act(Move m) {
        if (phase != Phase.BET || table.turn != 0 || rematchUntil > 0) return;
        if (m.act() == SeotdaTable.Act.RAISE && !table.canRaise(0)) return;
        long p = table.apply(0, m);
        balance -= p;
        paid += p;
        log = switch (m.act()) {
            case DIE -> "다이했어요";
            case CALL -> p > 0 ? num(p) + "원 콜했어요" : "체크했어요";
            case RAISE -> num(p) + "원을 걸었어요 (레이즈)";
        };
        after();
    }

    private static void after() {
        if (!table.bettingOver()) {
            startTurn();
            push(false);
            return;
        }
        Outcome o = table.showdown();
        if (o.rematch()) {
            table.rematch();
            log = "재경기! (" + o.reason() + ") 판돈은 그대로 다시 패를 받아요";
            rematchUntil = System.currentTimeMillis() + 2500;
            resultJson = new JsonObject();
            resultJson.addProperty("kind", "rematch");
            resultJson.addProperty("reason", o.reason());
            push(false);
            resultJson = null;
            return;
        }
        int w = o.winner();
        long pot = table.pot(), payout = 0;
        if (w == 0) {
            payout = pot;
            balance += pot;
        } else aiChips[w] += pot;
        boolean byFold = table.activeCount() == 1;
        JsonObject r = new JsonObject();
        r.addProperty("id", hands);
        r.addProperty("kind", w == 0 ? "win" : "lose");
        r.addProperty("winner", w);
        Hand h = table.hands[w];
        r.addProperty("rank", byFold ? "모두 다이" : h.displayName());
        JsonArray c = new JsonArray();
        c.add(h.a().id());
        c.add(h.b().id());
        r.add("cards", c);
        String wn = w == 0 ? "나" : NAMES.get(avatars.get(w));
        r.addProperty("winnerName", wn);
        r.addProperty("pot", pot);
        r.addProperty("payout", payout);
        r.addProperty("net", payout - paid);
        r.addProperty("lost", paid);
        r.addProperty("folded", byFold);
        resultJson = r;
        phase = Phase.BREAK;
        breakUntil = System.currentTimeMillis() + BREAK_MS;
        log = w == 0 ? "이번 턴 승리! " + num(pot) + "원을 가져왔어요" : wn + "(이)가 판돈 " + num(pot) + "원을 가져갔어요";
        for (int i = 1; i <= 2; i++) {
            if (!aiOut[i] && aiChips[i] < ante) {
                aiOut[i] = true;
                log += " · " + NAMES.get(avatars.get(i)) + " 파산!";
            }
        }
        push(false);
    }

    private static void push(boolean open) {
        long now = System.currentTimeMillis();
        JsonObject o = new JsonObject();
        o.addProperty("type", "state");
        o.addProperty("open", open);
        o.addProperty("ante", ante);
        o.addProperty("pot", phase == Phase.BET ? table.pot() : 0);
        o.addProperty("balance", balance);
        o.addProperty("phase", switch (phase) {
            case BET -> rematchUntil > 0 ? "rematch" : "bet";
            case BREAK -> "break";
            case END -> "end";
        });
        o.addProperty("turn", phase == Phase.BET && rematchUntil == 0 ? table.turn : -1);
        o.addProperty("timeLeft", phase == Phase.BET && table.turn == 0 ? Math.max(0, TURN_MS - (now - turnStarted))
                : phase == Phase.BREAK ? Math.max(0, breakUntil - now) : 0);
        o.addProperty("turnLimit", phase == Phase.BREAK ? BREAK_MS : TURN_MS);
        o.addProperty("toCall", phase == Phase.BET ? table.toCall(0) : 0);
        o.addProperty("canRaise", phase == Phase.BET && table.canRaise(0));
        o.addProperty("minRaise", table.minRaise());
        o.addProperty("maxRaise", Math.max(0, table.playerMaxRaise()));
        o.addProperty("raisesLeft", table.raisesLeft);
        o.addProperty("paid", paid);
        o.addProperty("log", log);
        o.addProperty("rematches", table.rematches);
        o.addProperty("hands", hands);
        JsonArray seats = new JsonArray();
        boolean reveal = phase == Phase.BREAK;
        for (int i = 0; i < 3; i++) {
            JsonObject j = new JsonObject();
            String av = avatars.get(i);
            j.addProperty("avatar", av);
            j.addProperty("name", i == 0 ? "나" : NAMES.get(av) + " · AI " + i);
            j.addProperty("chips", i == 0 ? balance : Math.max(0, aiChips[i]));
            boolean inHand = phase != Phase.END && table.present[i];
            boolean out = i > 0 && aiOut[i] && !inHand;
            j.addProperty("out", out);
            j.addProperty("status", inHand ? table.status[i] : out ? "파산" : "");
            j.addProperty("put", inHand ? table.put[i] : 0);
            j.addProperty("folded", inHand && table.folded[i]);
            JsonArray cards = new JsonArray();
            String rank = "";
            if (inHand) {
                boolean show = i == 0 || (reveal && !table.folded[i]);
                cards.add(show ? table.hands[i].a().id() : "back");
                cards.add(show ? table.hands[i].b().id() : "back");
                if (show) rank = table.hands[i].displayName();
            } else {
                cards.add("");
                cards.add("");
            }
            j.add("cards", cards);
            j.addProperty("rank", rank);
            seats.add(j);
        }
        o.add("seats", seats);
        if (resultJson != null) o.add("result", resultJson);
        if (summaryJson != null) o.add("summary", summaryJson);
        int r = SeotdaState.apply(o.toString());
        if (r != 0) kr.kkaegul.client.casino.CasinoNet.onSeotda(r);
    }

    private static String num(long v) {
        return String.format(Locale.US, "%,d", v);
    }

    /** 족보 표 (화면용) */
    public static String[][] basic() {
        return Seotda.BASIC;
    }

    public static String[][] special() {
        return Seotda.SPECIAL;
    }

    // ----- 미리보기 테스트용 -----

    /** 지금 턴을 바로 끝내고 결과(쉬는 시간)로 */
    public static void debugFinish() {
        while (phase == Phase.BET) {
            if (rematchUntil > 0) {
                rematchUntil = 0;
                continue;
            }
            if (table.turn == 0) act(Move.call());
            else {
                aiAt = 0;
                tick();
            }
        }
    }

    public static void debugEnd() {
        debugFinish();
        if (phase == Phase.BREAK) {
            endSession("그만하기를 눌러서 판이 끝났어요");
            push(false);
        }
    }
}
