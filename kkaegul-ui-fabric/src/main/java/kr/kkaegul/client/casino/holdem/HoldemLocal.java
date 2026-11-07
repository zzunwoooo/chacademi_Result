package kr.kkaegul.client.casino.holdem;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import kr.kkaegul.client.casino.holdem.engine.HoldemAi;
import kr.kkaegul.client.casino.holdem.engine.HoldemTable;
import kr.kkaegul.client.casino.holdem.engine.HoldemTable.Move;
import kr.kkaegul.client.casino.holdem.engine.HoldemView;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 서버 없이 홀덤 AI 대전 미리보기 (/elseahud holdem 입장칩). 서버(HoldemService)의 진행을 그대로 흉내 낸다.
 * 돈은 가짜 (100만 원으로 시작).
 */
public final class HoldemLocal {

    private static final List<String> AVATARS = List.of("bear", "rabbit", "fox", "raccoon", "cat", "squirrel", "deer", "panda");
    private static final Map<String, String> NAMES = Map.of(
            "bear", "밤톨", "rabbit", "모찌", "fox", "유자", "raccoon", "도토",
            "cat", "치즈", "squirrel", "호두", "deer", "새봄", "panda", "두부");
    private static final long TURN_MS = 20_000, BREAK_MS = 5_000;

    private enum Phase { BET, BREAK, END }

    private static boolean active;
    private static long balance = 1_000_000;
    private static long buyIn, sb, bb, turnStarted, aiAt, breakUntil;
    private static final long[] stack = new long[3];
    private static final boolean[] out = new boolean[3];
    private static final boolean[] peek = new boolean[3];
    private static List<String> avatars = List.of();
    private static HoldemTable table;
    private static Phase phase = Phase.BET;
    private static int dealer, hands, lastStreet;
    private static String log = "";
    private static JsonObject result, summary;

    private HoldemLocal() {
    }

    public static boolean active() {
        return active;
    }

    public static boolean start(long b) {
        buyIn = b;
        bb = Math.max(2, b / 50);
        sb = Math.max(1, bb / 2);
        active = true;
        if (!newSession()) {
            active = false;
            return false;
        }
        push(true);
        return true;
    }

    private static boolean newSession() {
        if (balance < buyIn) return false;
        balance -= buyIn;
        List<String> pool = new ArrayList<>(AVATARS), o = new ArrayList<>();
        for (int s = 0; s < 3; s++) {
            String old = avatars.size() > s ? avatars.get(s) : null;
            List<String> ch = new ArrayList<>(pool);
            ch.remove(old);
            String pick = ch.get(ThreadLocalRandom.current().nextInt(ch.size()));
            o.add(pick);
            pool.remove(pick);
        }
        avatars = o;
        for (int i = 0; i < 3; i++) {
            stack[i] = buyIn;
            out[i] = false;
        }
        hands = 0;
        summary = null;
        dealer = ThreadLocalRandom.current().nextInt(3);
        newHand();
        return true;
    }

    private static void newHand() {
        for (int i = 1; i < 3; i++) if (stack[i] <= 0) out[i] = true;
        table = new HoldemTable(ThreadLocalRandom.current(), stack.clone(), dealer, sb, bb);
        dealer = (table.dealer + 1) % 3;
        for (int i = 1; i < 3; i++) peek[i] = ThreadLocalRandom.current().nextDouble() < 0.3;
        phase = Phase.BET;
        result = null;
        hands++;
        lastStreet = 0;
        log = hands + "번째 판 · 블라인드 " + num(sb) + "/" + num(bb);
        startTurn();
        if (table.over) finishHand();
    }

    private static void startTurn() {
        turnStarted = System.currentTimeMillis();
        aiAt = turnStarted + 800 + ThreadLocalRandom.current().nextInt(1000);
    }

    public static void tick() {
        if (!active) return;
        long now = System.currentTimeMillis();
        if (phase == Phase.BREAK) {
            if (now >= breakUntil) nextHandOrEnd();
            return;
        }
        if (phase != Phase.BET) return;
        if (table.turn == 0 && now - turnStarted > TURN_MS) {
            act(table.toCall(0) == 0 ? Move.call() : Move.fold());
        } else if (table.turn > 0 && now >= aiAt) {
            int s = table.turn;
            Move m = HoldemAi.decide(table, s, peek[s], ThreadLocalRandom.current());
            long paid = table.apply(s, m);
            log = actLog(NAMES.get(avatars.get(s)) + "(이)가", table.status[s], paid, table.bet[s]);
            after();
        }
    }

    private static String actLog(String who, String status, long paid, long betTotal) {
        return switch (status) {
            case "폴드" -> who + " 폴드했어요";
            case "체크" -> who + " 체크했어요";
            case "콜" -> who + " " + num(paid) + " 콜했어요";
            case "베팅" -> who + " " + num(betTotal) + " 베팅했어요";
            case "레이즈" -> who + " 총 " + num(betTotal) + "으로 레이즈했어요";
            case "올인" -> who + " 올인! (" + num(betTotal) + ")";
            default -> who + " " + status;
        };
    }

    private static void act(Move m) {
        if (phase != Phase.BET || table.turn != 0) return;
        long paid = table.apply(0, m);
        log = actLog("내가", table.status[0], paid, table.bet[0]).replace("내가 ", "");
        after();
    }

    private static void after() {
        if (table.over) {
            finishHand();
            push(false);
            return;
        }
        if (table.street != lastStreet) {
            lastStreet = table.street;
            log = HoldemTable.STREETS[table.street] + "!";
        }
        startTurn();
        push(false);
    }

    private static void finishHand() {
        for (int i = 0; i < 3; i++) if (table.present[i]) stack[i] = table.stack[i];
        String[] names = names();
        result = HoldemView.result(table, 0, hands, names, null);
        phase = Phase.BREAK;
        breakUntil = System.currentTimeMillis() + BREAK_MS;
        String wn = result.get("winnerName").getAsString();
        log = (wn.equals("나") ? "내가" : wn + "(이)가") + " " + num(result.get("amount").getAsLong()) + " 가져갔어요"
                + (table.showdown ? " · " + result.get("rank").getAsString() : "");
        for (int i = 1; i < 3; i++) if (!out[i] && stack[i] <= 0) {
            out[i] = true;
            log += " · " + names[i] + " 파산!";
        }
    }

    private static String[] names() {
        String[] n = new String[3];
        n[0] = "나";
        for (int i = 1; i < 3; i++) n[i] = NAMES.get(avatars.get(i));
        return n;
    }

    private static void endSession(String reason) {
        phase = Phase.END;
        long cash = stack[0];
        balance += cash;
        JsonObject o = new JsonObject();
        o.addProperty("reason", reason);
        o.addProperty("net", cash - buyIn);
        o.addProperty("hands", hands);
        o.addProperty("cashOut", cash);
        summary = o;
        stack[0] = 0;
        log = reason;
    }

    private static void nextHandOrEnd() {
        if (stack[0] <= 0) endSession("칩을 모두 잃어서 판이 끝났어요");
        else if (stack[1] <= 0 && stack[2] <= 0) endSession("AI가 모두 파산했어요!");
        else newHand();
        push(false);
    }

    public static void handle(String json) {
        if (!active) return;
        JsonObject o = JsonParser.parseString(json).getAsJsonObject();
        String a = o.get("action").getAsString();
        long to = o.has("to") ? o.get("to").getAsLong() : 0;
        switch (a) {
            case "hd_fold" -> act(Move.fold());
            case "hd_call" -> act(Move.call());
            case "hd_raise" -> act(Move.raiseTo(to));
            case "hd_next" -> {
                if (phase == Phase.BREAK) nextHandOrEnd();
                else if (phase == Phase.END) {
                    if (!newSession()) endSession("보유 금액이 입장 칩보다 적어요");
                    push(false);
                }
            }
            case "hd_stop" -> {
                if (phase == Phase.BREAK) {
                    endSession("그만하기를 눌러서 남은 칩을 돌려받았어요");
                    push(false);
                }
            }
            case "hd_close" -> {
                if (phase != Phase.END) {
                    if (phase == Phase.BET && !table.over) {
                        table.forceFold(0);
                        stack[0] = table.stack[0];
                    }
                    balance += stack[0];
                    stack[0] = 0;
                }
                active = false;
            }
            default -> {
            }
        }
    }

    private static void push(boolean open) {
        long now = System.currentTimeMillis();
        JsonObject o = new JsonObject();
        o.addProperty("type", "state");
        o.addProperty("mode", "ai");
        o.addProperty("open", open);
        o.addProperty("phase", switch (phase) {
            case BET -> "bet";
            case BREAK -> "break";
            case END -> "end";
        });
        o.addProperty("sb", sb);
        o.addProperty("bb", bb);
        o.addProperty("buyIn", buyIn);
        o.addProperty("balance", balance);
        o.addProperty("hands", hands);
        o.addProperty("log", log);
        if (phase == Phase.BREAK) {
            o.addProperty("timeLeft", Math.max(0, breakUntil - now));
            o.addProperty("turnLimit", BREAK_MS);
        }
        HoldemView.SeatInfo[] seats = new HoldemView.SeatInfo[3];
        String[] n = names();
        for (int i = 0; i < 3; i++) {
            HoldemView.SeatInfo si = new HoldemView.SeatInfo();
            si.avatar = avatars.get(i);
            si.name = i == 0 ? "나" : n[i] + " · AI " + i;
            si.stack = stack[i];
            si.out = i > 0 && out[i];
            si.inSession = true;
            seats[i] = si;
        }
        HoldemView.fill(o, phase == Phase.END ? null : table, 0, seats, phase == Phase.BET, phase == Phase.BREAK, now, turnStarted, TURN_MS);
        if (result != null && phase == Phase.BREAK) o.add("result", result);
        if (summary != null) o.add("summary", summary);
        int r = HoldemState.apply(o.toString());
        if (r != 0) kr.kkaegul.client.casino.CasinoNet.onHoldem(r);
    }

    private static String num(long v) {
        return String.format(Locale.US, "%,d", v);
    }

    // ----- 미리보기 테스트용 -----

    /** 내 차례가 될 때까지 AI를 바로 진행 */
    public static void debugToMyTurn() {
        for (int k = 0; k < 50 && phase == Phase.BET && table.turn > 0; k++) {
            aiAt = 0;
            tick();
        }
    }

    /** 판을 끝까지 (나는 콜) */
    public static void debugFinish() {
        for (int k = 0; k < 200 && phase == Phase.BET; k++) {
            if (table.turn == 0) act(Move.call());
            else {
                aiAt = 0;
                tick();
            }
        }
    }
}
