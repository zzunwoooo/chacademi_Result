package kr.kkaegul.client.casino.holdem;

import com.google.gson.JsonObject;
import kr.kkaegul.client.casino.CasinoNet;
import kr.kkaegul.client.casino.UiSound;
import kr.kkaegul.client.casino.render.ElseaFont.Align;
import kr.kkaegul.client.casino.render.HqTexture;
import kr.kkaegul.client.casino.render.PkAssets;
import kr.kkaegul.client.casino.render.SdAssets;
import kr.kkaegul.client.casino.seotda.SdBase;
import kr.kkaegul.client.casino.seotda.SeotdaLobbyState;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.sound.SoundEvents;
import net.minecraft.util.Util;
import org.lwjgl.glfw.GLFW;

/**
 * 텍사스 홀덤 테이블 화면 (AI 대전 · 플레이어 대전). 섯다 화면의 리소스를 같이 쓴다.
 * 1600×1000 캔버스, 자리 0은 항상 나 (서버가 돌려서 보냄).
 */
public final class HoldemScreen extends SdBase {

    private static final float CARD_RATIO = 436f / 640f;
    private static final String[] STREET = {"프리플롭", "플롭", "턴", "리버", "쇼다운"};

    private enum Modal { NONE, BET, END, RANKS, EXIT, KICK, DELETE, SETTINGS }

    private Modal modal = Modal.NONE;
    private long raiseTo;
    private boolean dragging, leaving;
    private int kickSeat = -1;
    private int seenVersion = -1;
    private String handKey = "";
    private long dealAt, boardAt, flipAt;
    private long seenResultId = -1, seenSummaryId = -1;
    private long lastPot;
    private long flyAt = -1;
    private int flyTo = -1;
    private final double[] shown = {-1, -1, -1};
    private long lastFrame;

    public HoldemScreen() {
        super("텍사스 홀덤");
    }

    // ----- 영역 -----
    private static final float TOP = 72, BAR = 866;
    private static final float[] BTN_RANKS = {1210, TOP, 170, 50};
    private static final float[] BTN_SOUND = {1392, TOP, 54, 50};
    private static final float[] BTN_CLOSE = {1458, TOP, 54, 50};
    private static final float[] BTN_FOLD = {330, BAR + 10, 330, 72};
    private static final float[] BTN_CALL = {690, BAR + 10, 330, 72};
    private static final float[] BTN_RAISE = {1050, BAR + 10, 330, 72};
    private static final float[] BTN_SET = {330, BAR + 10, 290, 72};
    private static final float[] BTN_DEL = {640, BAR + 10, 290, 72};
    private static final float[] BTN_START = {950, BAR + 10, 430, 72};
    private static final float[] BTN_READY = {330, BAR + 10, 600, 72};
    private static final float[] BTN_LEAVE = {950, BAR + 10, 430, 72};
    private static final float[] BTN_STOP = {330, BAR + 10, 520, 72};
    private static final float[] BTN_NEXT = {870, BAR + 10, 510, 72};
    private static final float[] SEAT1 = {110, 150, 170, 170};
    private static final float[] SEAT2 = {1320, 150, 170, 170};

    private static final float[] BET = {500, 230, 600, 560};
    private static final float[] BET_CLOSE = {1040, 265, 40, 40};
    private static final float[] SLIDER = {565, 485, 470, 18};
    private static final float[][] PRESET = {{545, 548, 120, 54}, {675, 548, 120, 54}, {805, 548, 120, 54}, {935, 548, 120, 54}};
    private static final float[] BET_CANCEL = {545, 640, 220, 70};
    private static final float[] BET_OK = {780, 640, 275, 70};

    private static final float[] END = {500, 250, 600, 470};
    private static final float[] END_LEFT = {545, 610, 245, 70};
    private static final float[] END_RIGHT = {810, 610, 245, 70};
    private static final float[] RANKS = {460, 70, 680, 860};
    private static final float[] RANKS_CLOSE = {1080, 105, 40, 40};
    private static final float[] EXIT = {500, 300, 600, 400};
    private static final float[] EXIT_STAY = {545, 590, 245, 70};
    private static final float[] EXIT_LEAVE = {810, 590, 245, 70};
    private static final float[] KICK = {500, 250, 600, 460};
    private static final float[] KICK_NO = {545, 600, 245, 70};
    private static final float[] KICK_YES = {810, 600, 245, 70};
    private static final float[] DELETE = {500, 300, 600, 400};
    private static final float[] DEL_NO = {545, 590, 245, 70};
    private static final float[] DEL_YES = {810, 590, 245, 70};

    private static final String[][] RANK_ROWS = {
            {"로열 플러시", "같은 무늬 10 · J · Q · K · A"},
            {"스트레이트 플러시", "같은 무늬로 숫자 5장이 이어짐"},
            {"포카드", "같은 숫자 4장"},
            {"풀하우스", "같은 숫자 3장 + 같은 숫자 2장"},
            {"플러시", "같은 무늬 5장"},
            {"스트레이트", "숫자 5장이 이어짐 (A는 A2345도 가능)"},
            {"트리플", "같은 숫자 3장"},
            {"투페어", "같은 숫자 2장이 두 쌍"},
            {"원페어", "같은 숫자 2장"},
            {"하이카드", "아무 족보 없음 · 가장 높은 카드로 비교"}};

    private static boolean pvp() {
        return HoldemState.pvp;
    }

    private static String phase() {
        return HoldemState.phase;
    }

    private static boolean waiting() {
        return phase().equals("wait");
    }

    private static boolean breaking() {
        return phase().equals("break");
    }

    private static void send(String json) {
        CasinoNet.holdem(json);
    }

    // ------------------------------------------------------------------ 상태 변화

    @Override
    public void tick() {
        if (HoldemLocal.active()) HoldemLocal.tick();
        if (HoldemState.version == seenVersion) return;
        seenVersion = HoldemState.version;
        HoldemState.Seat me = HoldemState.seats[0];
        String key = HoldemState.hands + ":" + String.join(",", me.cards);
        if (!key.equals(handKey)) {
            handKey = key;
            if (phase().equals("bet") && me.cards.length > 0) {
                dealAt = Util.getMeasuringTimeMs();
                play(SoundEvents.ITEM_BOOK_PAGE_TURN, 1.0f);
                if (modal == Modal.BET) modal = Modal.NONE;
            }
        }
        int bs = HoldemState.board.size();
        if (bs > boardSeen) {
            boardAt = Util.getMeasuringTimeMs();
            boardFrom = boardSeen;
            play(SoundEvents.ITEM_BOOK_PAGE_TURN, 1.3f);
        } else if (bs < boardSeen) {
            boardFrom = bs;
        }
        boardSeen = bs;
        if (phase().equals("bet") && HoldemState.pot > lastPot && lastPot > 0) UiSound.chips();
        lastPot = HoldemState.pot;
        JsonObject r = HoldemState.result;
        if (breaking() && r != null && r.has("id") && r.get("id").getAsLong() != seenResultId) {
            seenResultId = r.get("id").getAsLong();
            flipAt = Util.getMeasuringTimeMs();
            flyAt = flipAt + 500;
            flyTo = r.has("winner") ? (int) r.get("winner").getAsLong() : -1;
            boolean win = r.has("won") && r.get("won").getAsLong() > 0;
            play(win ? SoundEvents.ENTITY_PLAYER_LEVELUP : SoundEvents.ENTITY_VILLAGER_NO, win ? 1.3f : 0.9f);
            if (modal == Modal.BET) modal = Modal.NONE;
        }
        JsonObject sm = HoldemState.summary;
        if (sm != null) {
            long id = sm.has("id") ? sm.get("id").getAsLong() : HoldemState.hands * 1000L + 7;
            boolean endNow = pvp() ? waiting() : phase().equals("end");
            if (endNow && id != seenSummaryId) {
                seenSummaryId = id;
                openModal(Modal.END);
            }
        }
        if (!pvp() && !phase().equals("end") && modal == Modal.END) modal = Modal.NONE;
        if (modal == Modal.BET && (HoldemState.turn != 0 || !HoldemState.canRaise)) modal = Modal.NONE;
        if (modal == Modal.KICK && (kickSeat < 0 || HoldemState.seats[kickSeat].empty || !HoldemState.isHost)) modal = Modal.NONE;
        if ((modal == Modal.DELETE || modal == Modal.SETTINGS) && (!HoldemState.isHost || HoldemState.session)) modal = Modal.NONE;
    }

    private int boardSeen, boardFrom;

    private void openModal(Modal m) {
        modal = m;
        modalAt = Util.getMeasuringTimeMs();
    }

    // ------------------------------------------------------------------ 그리기

    @Override
    public void render(DrawContext ctx, int mx, int my, float delta) {
        layout();
        updateChips();
        drawRoom(ctx);
        boolean modalOpen = modal != Modal.NONE;
        int hx = modalOpen ? -1 : mx, hy = modalOpen ? -1 : my;
        drawTable(ctx, hx, hy);
        drawCoinFly(ctx);
        if (!modalOpen) return;
        float[] r = switch (modal) {
            case BET -> BET;
            case END -> END;
            case RANKS -> RANKS;
            case KICK -> KICK;
            case DELETE -> DELETE;
            case SETTINGS -> FORM;
            default -> EXIT;
        };
        beginModal(ctx, r);
        switch (modal) {
            case BET -> drawBet(ctx, mx, my);
            case END -> drawEnd(ctx, mx, my);
            case RANKS -> drawRanks(ctx);
            case EXIT -> drawExit(ctx, mx, my);
            case KICK -> drawKick(ctx, mx, my);
            case DELETE -> drawDelete(ctx, mx, my);
            case SETTINGS -> drawForm(ctx, mx, my, true, HoldemState.balance);
            default -> {
            }
        }
        endModal(ctx);
    }

    private void updateChips() {
        long now = Util.getMeasuringTimeMs();
        float dt = lastFrame == 0 ? 0 : Math.min(0.1f, (now - lastFrame) / 1000f);
        lastFrame = now;
        boolean hold = flyAt > 0 && now < flyAt + 450;
        for (int i = 0; i < 3; i++) {
            double target = HoldemState.seats[i].stack;
            if (shown[i] < 0) shown[i] = target;
            if (hold && i == flyTo) continue;
            double d = target - shown[i];
            if (Math.abs(d) < 1) shown[i] = target;
            else shown[i] += d * Math.min(1, dt * 7);
        }
    }

    private long stackShown(int i) {
        return Math.round(shown[i] < 0 ? HoldemState.seats[i].stack : shown[i]);
    }

    private void drawTable(DrawContext ctx, int mx, int my) {
        titlePlaque(ctx, "텍사스 홀덤");
        if (pvp()) {
            String t = HoldemState.roomTitle;
            float tw = Math.min(260, textW(t, 19)) + 36;
            SdAssets.SD_RANKING_ROW.drawNine(ctx, X(1022), Y(TOP + 4), L(tw), L(42), 20, L(21));
            text(ctx, t, 1022 + tw / 2, TOP + 25, 19, BROWN, Align.CENTER);
        } else {
            SdAssets.SD_STATUS_PILL.drawNine(ctx, X(1030), Y(TOP + 5), L(116), L(40), 30, L(20));
            text(ctx, "AI 대전", 1088, TOP + 25, 21, WHITE, Align.CENTER);
        }
        button(ctx, SdAssets.SD_BUTTON_CREAM, BTN_RANKS, mx, my, true);
        icon(ctx, SdAssets.SD_BOOK, 1242, TOP + 25, 26);
        text(ctx, "족보 보기", 1262, TOP + 25, 21, BROWN, Align.LEFT);
        button(ctx, SdAssets.SD_BUTTON_CREAM, BTN_SOUND, mx, my, true);
        icon(ctx, sound ? SdAssets.SD_SOUND : SdAssets.SD_MUTE, 1419, TOP + 25, 24);
        button(ctx, SdAssets.SD_BUTTON_CREAM, BTN_CLOSE, mx, my, true);
        icon(ctx, pvp() ? SdAssets.SD_PVP_LEAVE : SdAssets.SD_CLOSE, 1485, TOP + 25, pvp() ? 24 : 20);

        SdAssets.SD_TABLE.draw(ctx, X(140), Y(255), L(1320), L(520));

        drawSeat(ctx, 1, SEAT1[0], 300, mx, my);
        drawSeat(ctx, 2, SEAT2[0], 1086, mx, my);
        drawCenter(ctx);
        drawMe(ctx);

        if (pvp() && waiting()) {
            drawWaitFooter(ctx, mx, my);
            return;
        }
        if (breaking() || phase().equals("end")) {
            drawBreakFooter(ctx, mx, my);
            return;
        }
        // 차례 배너
        SdAssets.SD_TURN_BANNER.drawNine(ctx, X(560), Y(BAR - 66), L(480), L(48), 30, L(18));
        int turn = HoldemState.turn;
        String banner;
        if (turn == 0) banner = "내 차례예요";
        else if (turn > 0) banner = (pvp() ? HoldemState.seats[turn].name + " 님" : HoldemState.seats[turn].name.split(" · ")[0]) + " 차례예요";
        else banner = pvp() && !HoldemState.inSession ? "구경 중이에요" : "";
        if (turn == 0) {
            long sec = (HoldemState.remaining() + 999) / 1000;
            text(ctx, banner, 780, BAR - 42, 24, BROWN, Align.CENTER);
            text(ctx, sec + "초", 1010, BAR - 42, 20, sec <= 5 ? CORAL : MUTED, Align.RIGHT);
            float frac = HoldemState.turnLimit <= 0 ? 0 : (float) HoldemState.remaining() / HoldemState.turnLimit;
            fill(ctx, 590, BAR - 25, 420 * frac, 4, sec <= 5 ? 0xFFCD7162 : 0xFF748D67);
        } else text(ctx, banner, 800, BAR - 42, 24, BROWN, Align.CENTER);

        footerBox(ctx, "콜 금액", num(HoldemState.toCall));
        boolean myTurn = turn == 0 && phase().equals("bet");
        boolean free = HoldemState.toCall == 0;
        boolean callAllIn = HoldemState.toCall > 0 && HoldemState.toCall >= HoldemState.seats[0].stack;
        labelButton(ctx, SdAssets.SD_BUTTON_CORAL, BTN_FOLD, "폴드", 28, WHITE, myTurn && !free, mx, my);
        labelButton(ctx, SdAssets.SD_BUTTON_SAGE, BTN_CALL, free ? "체크" : callAllIn ? "올인 " + num(HoldemState.toCall) : "콜 " + num(HoldemState.toCall),
                28, WHITE, myTurn, mx, my);
        labelButton(ctx, SdAssets.SD_BUTTON_CREAM, BTN_RAISE, HoldemState.currentBet == 0 ? "베팅" : "레이즈", 28, BROWN,
                myTurn && HoldemState.canRaise, mx, my);
    }

    private void footerBox(DrawContext ctx, String leftLabel, String leftValue) {
        SdAssets.SD_INPUT_BOX.drawNine(ctx, X(90), Y(BAR), L(1420), L(92), 30, L(24));
        text(ctx, leftLabel, 200, BAR + 25, 19, MUTED, Align.CENTER);
        text(ctx, leftValue, 200, BAR + 58, 30, BROWN, Align.CENTER);
        text(ctx, "블라인드", 1445, BAR + 25, 17, MUTED, Align.CENTER);
        text(ctx, num(HoldemState.sb) + "/" + num(HoldemState.bb), 1445, BAR + 55, 21, BROWN, Align.CENTER);
    }

    private void drawBreakFooter(DrawContext ctx, int mx, int my) {
        boolean end = phase().equals("end");
        long sec = (HoldemState.remaining() + 999) / 1000;
        SdAssets.SD_TURN_BANNER.drawNine(ctx, X(560), Y(BAR - 66), L(480), L(48), 30, L(18));
        String banner = end ? "판이 끝났어요" : pvp() && !HoldemState.inSession ? "구경 중이에요 · 다음 판까지 " + sec + "초"
                : "다음 판까지 " + sec + "초";
        text(ctx, banner, 800, BAR - 42, 22, BROWN, Align.CENTER);
        if (!end) {
            float frac = HoldemState.turnLimit <= 0 ? 0 : (float) HoldemState.remaining() / HoldemState.turnLimit;
            fill(ctx, 590, BAR - 25, 420 * frac, 4, 0xFF748D67);
        }
        footerBox(ctx, "진행한 판", HoldemState.hands + "판");
        if (pvp()) {
            if (HoldemState.inSession && !end) {
                labelButton(ctx, SdAssets.SD_BUTTON_CREAM, BTN_STOP, "그만하기 (칩 정산)", 26, BROWN, true, mx, my);
                text(ctx, "그만하면 남은 칩을 돌려받고 구경해요", 1125, BAR + 46, 18, MUTED, Align.CENTER);
            } else {
                text(ctx, "판이 끝나면 다시 준비할 수 있어요", 590, BAR + 46, 19, MUTED, Align.CENTER);
                iconButton(ctx, SdAssets.SD_BUTTON_CREAM, BTN_LEAVE, SdAssets.SD_PVP_LEAVE, "방 나가기", 26, BROWN, true, mx, my);
            }
            return;
        }
        if (end) {
            labelButton(ctx, SdAssets.SD_BUTTON_CREAM, BTN_STOP, "나가기", 28, BROWN, true, mx, my);
            labelButton(ctx, SdAssets.SD_BUTTON_SAGE, BTN_NEXT, "새 판 시작", 28, WHITE, HoldemState.balance >= HoldemState.buyIn, mx, my);
        } else {
            labelButton(ctx, SdAssets.SD_BUTTON_CREAM, BTN_STOP, "그만하기 (칩 정산)", 26, BROWN, true, mx, my);
            labelButton(ctx, SdAssets.SD_BUTTON_SAGE, BTN_NEXT, "바로 다음 판", 28, WHITE, true, mx, my);
        }
    }

    private void drawWaitFooter(DrawContext ctx, int mx, int my) {
        footerBox(ctx, "참여 인원", HoldemState.roomCount + " / 3");
        if (HoldemState.isHost) {
            String hint = HoldemState.roomCount < 2 ? "2명 이상 입장하면 시작할 수 있어요"
                    : HoldemState.allReady ? "모두 준비됐어요. 게임을 시작해 보세요" : "참가자가 모두 준비하면 시작할 수 있어요";
            text(ctx, hint, 800, BAR - 42, 19, MUTED, Align.CENTER);
            iconButton(ctx, SdAssets.SD_BUTTON_CREAM, BTN_SET, SdAssets.SD_PVP_SETTINGS, "방 설정", 25, BROWN, true, mx, my);
            iconButton(ctx, SdAssets.SD_BUTTON_CREAM, BTN_DEL, SdAssets.SD_PVP_TRASH, "방 삭제", 25, CORAL, true, mx, my);
            boolean can = HoldemState.allReady && HoldemState.balance >= HoldemState.buyIn;
            labelButton(ctx, SdAssets.SD_BUTTON_SAGE, BTN_START, "게임 시작", 30, WHITE, can, mx, my);
        } else {
            boolean ready = HoldemState.seats[0].ready;
            text(ctx, ready ? "시작하면 입장 칩 " + num(HoldemState.buyIn) + "이 보유금에서 빠져요" : "준비하면 방장이 게임을 시작할 수 있어요",
                    800, BAR - 42, 19, MUTED, Align.CENTER);
            boolean can = ready || HoldemState.balance >= HoldemState.buyIn;
            labelButton(ctx, ready ? SdAssets.SD_BUTTON_CREAM : SdAssets.SD_BUTTON_SAGE, BTN_READY,
                    ready ? "준비 취소" : "준비하기", 30, ready ? BROWN : WHITE, can, mx, my);
            iconButton(ctx, SdAssets.SD_BUTTON_CREAM, BTN_LEAVE, SdAssets.SD_PVP_LEAVE, "방 나가기", 26, BROWN, true, mx, my);
        }
    }

    /** 가운데: 팟 · 거리 · 공용 패 */
    private void drawCenter(DrawContext ctx) {
        JsonObject r = HoldemState.result;
        if (pvp() && waiting()) {
            int n = HoldemState.roomCount;
            String line;
            if (n < 2) line = "다른 플레이어를 기다리고 있어요";
            else if (HoldemState.isHost) line = HoldemState.allReady ? "모두 준비됐어요" : "참가자가 준비하면 시작할 수 있어요";
            else line = HoldemState.seats[0].ready ? "방장이 시작하기를 기다려요" : "준비하기를 눌러 주세요";
            text(ctx, line, 800, 390, 24, 0xFFEAF2DF, Align.CENTER);
            text(ctx, n + " / 3", 800, 445, 56, WHITE, Align.CENTER);
            text(ctx, "블라인드 " + num(HoldemState.sb) + "/" + num(HoldemState.bb) + " · 입장 칩 " + num(HoldemState.buyIn),
                    800, 500, 19, 0xFFEAF2DF, Align.CENTER);
            return;
        }
        if (phase().equals("end")) {
            text(ctx, "판이 끝났어요", 800, 430, 30, WHITE, Align.CENTER);
            return;
        }
        // 팟
        boolean flying = flyAt > 0 && Util.getMeasuringTimeMs() < flyAt + 450;
        if (breaking() && r != null) {
            boolean win = r.has("won") && r.get("won").getAsLong() > 0;
            String who = win ? "내가" : str(r, "winnerName") + (pvp() ? " 님이" : "(이)가");
            String rank = str(r, "rank");
            text(ctx, (r.has("split") && r.get("split").getAsBoolean() ? "나눠 가졌어요 · " : who + " 이겼어요 · ") + rank,
                    800, 285, 22, 0xFFFFF1A8, Align.CENTER);
            long amt = win ? r.get("won").getAsLong() : num(r, "amount");
            text(ctx, (win ? "+" : "") + num(amt), 800, 325, 40, WHITE, Align.CENTER);
        } else {
            String pt = num(HoldemState.pot);
            float pw = textW(pt, 36);
            text(ctx, "팟", 800, 276, 18, 0xFFEAF2DF, Align.CENTER);
            if (!flying) icon(ctx, SdAssets.SD_COIN_STACK, 800 - pw / 2 - 12, 314, 42);
            text(ctx, pt, 800 + 22, 314, 36, WHITE, Align.CENTER);
        }
        // 거리
        int st = HoldemState.street;
        if (st >= 0) {
            String label = breaking() ? (r != null && r.has("byFold") && r.get("byFold").getAsBoolean() ? "모두 폴드" : "쇼다운")
                    : STREET[Math.min(4, st)];
            text(ctx, label, 800, 368, 19, 0xFFEAF2DF, Align.CENTER);
            fill(ctx, 560, 368, 180, 1.5f, 0x70EAF2DF);
            fill(ctx, 860, 368, 180, 1.5f, 0x70EAF2DF);
        }
        // 공용 패 5칸
        float h = 150, w = h * CARD_RATIO, gap = 14;
        float x0 = 800 - (5 * w + 4 * gap) / 2, y = 385;
        long now = Util.getMeasuringTimeMs();
        for (int i = 0; i < 5; i++) {
            float x = x0 + i * (w + gap);
            if (i < HoldemState.board.size()) {
                String id = HoldemState.board.get(i);
                // 새로 깔린 카드: 뒤집히며 나타남
                float t = i < boardFrom ? 1 : Math.min(1f, Math.max(0f, (now - boardAt - (i - boardFrom) * 110L) / 300f));
                boolean hi = breaking() && HoldemState.best.contains(id);
                boolean dim = breaking() && !HoldemState.best.isEmpty() && !hi;
                drawCard(ctx, t < 0.5f ? "back" : id, x, y, w, h, Math.abs((float) Math.cos(t * Math.PI)), hi, dim);
            } else {
                // 빈 칸 (점선 느낌)
                fill(ctx, x, y, w, 2, 0x55FFFFFF);
                fill(ctx, x, y + h - 2, w, 2, 0x55FFFFFF);
                fill(ctx, x, y, 2, h, 0x55FFFFFF);
                fill(ctx, x + w - 2, y, 2, h, 0x55FFFFFF);
                String lab = i < 3 ? "플롭" : i == 3 ? "턴" : "리버";
                text(ctx, lab, x + w / 2, y + h / 2, 17, 0x88FFFFFF, Align.CENTER);
            }
        }
        if (!HoldemState.log.isEmpty()) {
            float lw = textW(HoldemState.log, 18) + 40;
            fill(ctx, 800 - lw / 2, 552, lw, 34, 0xCC4D634E);
            text(ctx, HoldemState.log, 800, 569, 18, 0xFFFFF8E6, Align.CENTER);
        }
    }

    private void drawCard(DrawContext ctx, String id, float x, float y, float w, float h, float flip, boolean hi, boolean dim) {
        HqTexture tex = PkAssets.card(id);
        float dw = w * flip;
        float cx = x + w / 2;
        if (hi) {
            fill(ctx, cx - dw / 2 - 4, y - 4, dw + 8, h + 8, 0xFFF2C94C);
        }
        tex.draw(ctx, X(cx - dw / 2), Y(y - (hi ? 6 : 0)), L(dw), L(h));
        if (dim) fill(ctx, cx - dw / 2, y, dw, h, 0x66263126);
    }

    private void drawMe(DrawContext ctx) {
        HoldemState.Seat me = HoldemState.seats[0];
        portrait(ctx, me.avatar, 390, 590, 160);
        if (me.host && pvp()) icon(ctx, SdAssets.SD_PVP_CROWN, 470, 587, 32);
        badge(ctx, me.badge, 540, 730);
        textOutlined(ctx, pvp() ? me.name : "나", 470, 768, 24, BROWN, Align.CENTER);
        coinLine(ctx, stackShown(0), 470, 796, 21);
        boolean has = me.cards.length == 2 && !me.cards[0].isEmpty();
        if (has) {
            float h = 160, w = h * CARD_RATIO;
            long now = Util.getMeasuringTimeMs();
            for (int i = 0; i < 2; i++) {
                float t = Math.min(1f, Math.max(0f, (now - dealAt - i * 120L) / 260f));
                if (t <= 0) continue;
                float e = 1 - (1 - t) * (1 - t);
                float tx = 620 + i * (w + 10), ty = 585;
                float x = 800 + (tx - 800) * e, y = 330 + (ty - 330) * e;
                boolean hi = breaking() && HoldemState.best.contains(me.cards[i]);
                drawCard(ctx, me.cards[i], x, y, w, h, 1, hi, me.folded);
            }
            if (!me.rank.isEmpty() || me.folded) {
                String t = me.folded ? "폴드" : "현재 족보 · " + me.rank;
                float bw = textW(t, 19) + 40;
                float cx = 620 + w + 5;
                SdAssets.SD_RANKING_ROW.drawNine(ctx, X(cx - bw / 2), Y(752), L(bw), L(32), 20, L(14));
                if (me.folded) text(ctx, t, cx, 768, 19, MUTED, Align.CENTER);
                else {
                    text(ctx, "현재 족보 · ", cx - bw / 2 + 20, 768, 19, BROWN, Align.LEFT);
                    text(ctx, me.rank, cx + bw / 2 - 20, 768, 19, CORAL, Align.RIGHT);
                }
            }
            if (me.bet > 0 && phase().equals("bet")) betChip(ctx, me.bet, 940, 640);
        } else if (pvp() && waiting() && !me.host) {
            pill(ctx, me.ready ? SdAssets.SD_BUTTON_SAGE : SdAssets.SD_RANKING_ROW, 680, 650, 180, 44,
                    me.ready ? "준비 완료" : "준비 전", 20, me.ready ? WHITE : MUTED);
        } else if (pvp() && me.out) {
            pill(ctx, SdAssets.SD_RANKING_ROW, 680, 650, 180, 44, "구경 중", 20, MUTED);
        }
        if (!me.status.isEmpty() && has && phase().equals("bet")) statusPill(ctx, me.status, 390, 552, 160);
    }

    private void drawSeat(DrawContext ctx, int seat, float px, float cardX, int mx, int my) {
        HoldemState.Seat st = HoldemState.seats[seat];
        if (st.empty) {
            icon(ctx, SdAssets.SD_PVP_SEAT_EMPTY, px + 85, 235, 166);
            pill(ctx, SdAssets.SD_RANKING_ROW, px, 330, 170, 38, "입장 대기 중…", 18, MUTED);
            return;
        }
        boolean kickable = pvp() && HoldemState.isHost && waiting() && !st.host;
        if (kickable && in(mx, my, px, 150, 170, 170)) SdAssets.SD_AVATAR_FRAME.draw(ctx, X(px - 6), Y(144), L(182), L(182));
        portrait(ctx, st.avatar, px, 150, 170);
        if (st.out) {
            dot(ctx, px + 85, 235, 77, 0xA0F4EBDA);
            pill(ctx, SdAssets.SD_RANKING_ROW, px + 25, 217, 120, 38, pvp() ? "구경" : "파산", 19, pvp() ? MUTED : CORAL);
        }
        if (st.host && pvp()) icon(ctx, SdAssets.SD_PVP_CROWN, px + 85, 146, 34);
        if (kickable) {
            pill(ctx, SdAssets.SD_BUTTON_CREAM, px + 112, 282, 60, 40, "", 10, BROWN);
            icon(ctx, SdAssets.SD_PVP_KICK, px + 142, 302, 21);
        }
        badge(ctx, st.badge, seat == 1 ? px + 165 : px + 5, 300);
        textOutlined(ctx, st.name, px + 85, 340, 21, BROWN, Align.CENTER);
        coinLine(ctx, stackShown(seat), px + 85, 367, 20);
        boolean has = st.cards.length == 2 && !st.cards[0].isEmpty();
        if (has) {
            float h = 150, w = h * CARD_RATIO;
            long now = Util.getMeasuringTimeMs();
            for (int i = 0; i < 2; i++) {
                float t = Math.min(1f, Math.max(0f, (now - dealAt - (i * 3 + seat) * 80L) / 260f));
                if (t <= 0) continue;
                float e = 1 - (1 - t) * (1 - t);
                float tx = cardX + i * (w + 8), ty = 160;
                float x = 800 + (tx - 800) * e, y = 330 + (ty - 330) * e;
                String id = st.cards[i];
                float flip = 1;
                if (breaking() && !id.equals("back")) {
                    float ft = Math.min(1f, (now - flipAt - i * 90L) / 320f);
                    flip = Math.abs((float) Math.cos(ft * Math.PI));
                    if (ft < 0.5f) id = "back";
                }
                boolean hi = breaking() && HoldemState.best.contains(st.cards[i]);
                drawCard(ctx, id, x, y, w, h, flip, hi, st.folded);
            }
            if (!st.status.isEmpty()) statusPill(ctx, st.status, cardX + 2, 322, 2 * w + 4);
            if (!st.rank.isEmpty()) text(ctx, st.rank, cardX + w + 4, 375, 20, CREAM, Align.CENTER);
            if (st.bet > 0 && phase().equals("bet")) betChip(ctx, st.bet, seat == 1 ? cardX + w * 2 + 70 : cardX - 70, 260);
        } else if (pvp() && waiting() && !st.host) {
            pill(ctx, st.ready ? SdAssets.SD_BUTTON_SAGE : SdAssets.SD_RANKING_ROW, px + 10, 392, 150, 36,
                    st.ready ? "준비 완료" : "준비 전", 17, st.ready ? WHITE : MUTED);
        }
    }

    private void statusPill(DrawContext ctx, String status, float x, float y, float w) {
        HqTexture tex = switch (status) {
            case "레이즈", "베팅", "올인" -> SdAssets.SD_BUTTON_CORAL;
            case "폴드" -> SdAssets.SD_RANKING_ROW;
            default -> SdAssets.SD_STATUS_PILL;
        };
        pill(ctx, tex, x, y, w, 36, status, 19, status.equals("폴드") ? MUTED : WHITE);
    }

    /** 이번 라운드에 낸 칩 */
    private void betChip(DrawContext ctx, long amount, float cx, float cy) {
        icon(ctx, SdAssets.SD_COIN_STACK, cx - 30, cy, 34);
        textOutlined(ctx, num(amount), cx - 8, cy, 19, BROWN, Align.LEFT);
    }

    /** D / SB / BB 배지 */
    private void badge(DrawContext ctx, String b, float cx, float cy) {
        if (b == null || b.isEmpty()) return;
        String[] parts = b.split("·");
        float x = cx;
        for (String part : parts) {
            boolean d = part.equals("D");
            dot(ctx, x, cy, 21, 0xFF6B4A2E);
            dot(ctx, x, cy, 19, d ? 0xFFF2C94C : part.equals("SB") ? 0xFFF7E3B5 : 0xFF4D634E);
            text(ctx, part, x, cy, part.length() > 1 ? 15 : 19, part.equals("BB") ? WHITE : BROWN, Align.CENTER);
            x += 40;
        }
    }

    private void drawCoinFly(DrawContext ctx) {
        if (flyAt < 0 || flyTo < 0) return;
        float t = (Util.getMeasuringTimeMs() - flyAt) / 450f;
        if (t < 0) {
            icon(ctx, SdAssets.SD_COIN_STACK, 800, 300, 52);
            return;
        }
        if (t > 1) {
            UiSound.coins();
            flyAt = -1;
            return;
        }
        float tx = flyTo == 0 ? 470 : flyTo == 1 ? 195 : 1405;
        float ty = flyTo == 0 ? 700 : 260;
        float e = 1 - (1 - t) * (1 - t) * (1 - t);
        float x = 800 + (tx - 800) * e, y = 300 + (ty - 300) * e - (float) Math.sin(t * Math.PI) * 60;
        icon(ctx, SdAssets.SD_COIN_STACK, x, y, 52 * (1 - 0.35f * t));
    }

    // ----- 베팅 팝업 -----

    private long minTo() {
        return HoldemState.minRaiseTo;
    }

    private long maxTo() {
        return Math.max(minTo(), HoldemState.maxRaiseTo);
    }

    private long clampTo(long v) {
        return Math.max(minTo(), Math.min(maxTo(), v));
    }

    private void drawBet(DrawContext ctx, int mx, int my) {
        frame(ctx, BET);
        boolean bet = HoldemState.currentBet == 0;
        text(ctx, bet ? "베팅 금액" : "레이즈 금액", 545, 285, 34, BROWN, Align.LEFT);
        icon(ctx, SdAssets.SD_CLOSE, BET_CLOSE[0] + 20, BET_CLOSE[1] + 20, 22);
        text(ctx, bet ? "이번 라운드에 걸 칩" : "이번 라운드 총 금액 (지금까지 " + num(HoldemState.myBet) + " 냄)", 545, 328, 18, MUTED, Align.LEFT);
        SdAssets.SD_INPUT_BOX.drawNine(ctx, X(545), Y(352), L(510), L(96), 30, L(26));
        text(ctx, num(raiseTo), 800, 400, 46, BROWN, Align.CENTER);
        SdAssets.SD_SLIDER_TRACK.drawNine(ctx, X(SLIDER[0]), Y(SLIDER[1]), L(SLIDER[2]), L(SLIDER[3]), 8, L(9));
        float f = maxTo() == minTo() ? 1f : (float) (raiseTo - minTo()) / (maxTo() - minTo());
        fill(ctx, SLIDER[0] + 4, SLIDER[1] + 5, (SLIDER[2] - 8) * f, SLIDER[3] - 10, 0xFF6B855E);
        SdAssets.SD_SLIDER_THUMB.draw(ctx, X(SLIDER[0] + SLIDER[2] * f - 17), Y(SLIDER[1] + SLIDER[3] / 2 - 17), L(34), L(34));
        text(ctx, "최소 " + num(minTo()), SLIDER[0], SLIDER[1] + 36, 15, MUTED, Align.LEFT);
        text(ctx, "최대 " + num(maxTo()), SLIDER[0] + SLIDER[2], SLIDER[1] + 36, 15, MUTED, Align.RIGHT);
        String[] labels = {"최소", "1/2 팟", "팟", "올인"};
        for (int i = 0; i < 4; i++) labelButton(ctx, SdAssets.SD_BUTTON_CREAM, PRESET[i], labels[i], 20, BROWN, true, mx, my);
        labelButton(ctx, SdAssets.SD_BUTTON_CREAM, BET_CANCEL, "취소", 25, BROWN, true, mx, my);
        String ok = raiseTo >= maxTo() ? "올인 " + num(raiseTo) : bet ? "베팅 " + num(raiseTo) : "총 " + num(raiseTo) + "으로";
        labelButton(ctx, SdAssets.SD_BUTTON_SAGE, BET_OK, ok, 22, WHITE, true, mx, my);
        long add = raiseTo - HoldemState.myBet;
        text(ctx, "지금 추가로 내는 칩 " + num(add) + " · 남은 칩 " + num(HoldemState.seats[0].stack - add), 800, 745, 16, MUTED, Align.CENTER);
    }

    private long potTo(double frac) {
        long call = HoldemState.toCall;
        long potAfter = HoldemState.pot + call;
        return clampTo(HoldemState.currentBet + Math.round(potAfter * frac));
    }

    private void setFromMouse(double mx) {
        float f = (float) ((mx - X(SLIDER[0])) / L(SLIDER[2]));
        f = Math.max(0, Math.min(1, f));
        long step = Math.max(1, HoldemState.bb / 2);
        long v = minTo() + Math.round((maxTo() - minTo()) * f / step) * step;
        raiseTo = f >= 0.999f ? maxTo() : clampTo(v);
    }

    // ----- 팝업들 -----

    private void drawEnd(DrawContext ctx, int mx, int my) {
        frame(ctx, END);
        JsonObject sm = HoldemState.summary;
        if (sm == null) return;
        long net = num(sm, "net");
        icon(ctx, SdAssets.SD_LEAF_ACCENT, 800, 330, 80);
        text(ctx, "판이 끝났어요", 800, 400, 36, BROWN, Align.CENTER);
        text(ctx, str(sm, "reason"), 800, 444, 19, MUTED, Align.CENTER);
        text(ctx, "총 " + num(sm, "hands") + "판 · 돌려받은 칩 " + num(num(sm, "cashOut")), 800, 492, 19, MUTED, Align.CENTER);
        text(ctx, (net > 0 ? "+" : net < 0 ? "-" : "") + num(Math.abs(net)), 800, 545, 48, net >= 0 ? GREEN : CORAL, Align.CENTER);
        if (pvp()) {
            labelButton(ctx, SdAssets.SD_BUTTON_SAGE, new float[]{650, 610, 300, 70}, "대기실로", 25, WHITE, true, mx, my);
            text(ctx, "다음 판에 캐릭터가 다시 바뀌어요", 800, 702, 16, MUTED, Align.CENTER);
            return;
        }
        labelButton(ctx, SdAssets.SD_BUTTON_CREAM, END_LEFT, "나가기", 25, BROWN, true, mx, my);
        labelButton(ctx, SdAssets.SD_BUTTON_SAGE, END_RIGHT, "새 판 시작", 25, WHITE, HoldemState.balance >= HoldemState.buyIn, mx, my);
        text(ctx, "새 판은 입장 칩 " + num(HoldemState.buyIn) + "으로 다시 앉아요 · 캐릭터도 바뀌어요", 800, 702, 16, MUTED, Align.CENTER);
    }

    private void drawRanks(DrawContext ctx) {
        frame(ctx, RANKS);
        text(ctx, "족보 보기", 505, 128, 34, BROWN, Align.LEFT);
        icon(ctx, SdAssets.SD_CLOSE, RANKS_CLOSE[0] + 20, RANKS_CLOSE[1] + 20, 22);
        text(ctx, "7장(내 패 2장 + 공용 패 5장) 중 가장 좋은 5장으로 비교해요", 800, 185, 17, MUTED, Align.CENTER);
        for (int i = 0; i < RANK_ROWS.length; i++) {
            float y = 215 + i * 66;
            SdAssets.SD_RANKING_ROW.drawNine(ctx, X(505), Y(y), L(590), L(58), 20, L(14));
            text(ctx, Integer.toString(i + 1), 530, y + 29, 16, MUTED, Align.CENTER);
            text(ctx, RANK_ROWS[i][0], 555, y + 29, 22, i < 3 ? CORAL : BROWN, Align.LEFT);
            text(ctx, RANK_ROWS[i][1], 1080, y + 29, 15, MUTED, Align.RIGHT);
        }
        text(ctx, "같은 족보는 숫자 · 키커로 비교하고, 완전히 같으면 팟을 나눠요 (무늬 우열 없음)", 800, 895, 15, MUTED, Align.CENTER);
    }

    private void drawExit(DrawContext ctx, int mx, int my) {
        frame(ctx, EXIT);
        icon(ctx, SdAssets.SD_LEAF_ACCENT, 800, 395, 100);
        HoldemState.Seat me = HoldemState.seats[0];
        boolean inHand = phase().equals("bet") && me.cards.length == 2 && !me.folded;
        String title = pvp() ? "방을 나갈까요?" : "홀덤을 나갈까요?";
        String sub;
        if (inHand) sub = "지금 나가면 이번 판은 폴드하고 남은 칩을 돌려받아요";
        else if (pvp() && HoldemState.isHost && HoldemState.roomCount > 1) sub = "방장은 다음 사람에게 넘어가요";
        else sub = "남은 칩은 보유금으로 돌아가요";
        text(ctx, title, 800, 475, 32, BROWN, Align.CENTER);
        text(ctx, sub, 800, 522, 19, inHand ? CORAL : MUTED, Align.CENTER);
        labelButton(ctx, SdAssets.SD_BUTTON_CREAM, EXIT_STAY, "계속하기", 25, BROWN, true, mx, my);
        labelButton(ctx, SdAssets.SD_BUTTON_CORAL, EXIT_LEAVE, "나가기", 25, WHITE, true, mx, my);
    }

    private void drawKick(DrawContext ctx, int mx, int my) {
        frame(ctx, KICK);
        if (kickSeat < 0) return;
        HoldemState.Seat st = HoldemState.seats[kickSeat];
        text(ctx, "플레이어를 강퇴할까요?", 800, 300, 32, BROWN, Align.CENTER);
        portrait(ctx, st.avatar, 725, 340, 150);
        text(ctx, st.name + " 님을 대기실에서 내보냅니다.", 800, 525, 22, BROWN, Align.CENTER);
        text(ctx, "강퇴된 사람은 이 방에 다시 들어올 수 없어요.", 800, 562, 17, MUTED, Align.CENTER);
        labelButton(ctx, SdAssets.SD_BUTTON_CREAM, KICK_NO, "취소", 25, BROWN, true, mx, my);
        iconButton(ctx, SdAssets.SD_BUTTON_CORAL, KICK_YES, SdAssets.SD_PVP_KICK, "강퇴하기", 25, WHITE, true, mx, my);
    }

    private void drawDelete(DrawContext ctx, int mx, int my) {
        frame(ctx, DELETE);
        icon(ctx, SdAssets.SD_PVP_TRASH, 800, 385, 70);
        text(ctx, "방을 삭제할까요?", 800, 460, 32, BROWN, Align.CENTER);
        text(ctx, "참가자 모두 방 목록으로 이동합니다.", 800, 505, 20, BROWN, Align.CENTER);
        text(ctx, HoldemState.roomTitle, 800, 540, 18, MUTED, Align.CENTER);
        labelButton(ctx, SdAssets.SD_BUTTON_CREAM, DEL_NO, "유지하기", 25, BROWN, true, mx, my);
        labelButton(ctx, SdAssets.SD_BUTTON_CORAL, DEL_YES, "방 삭제", 25, WHITE, true, mx, my);
    }

    // ----- 방 설정 폼 (블라인드) -----

    private static final long[] BB_PRESETS = {200, 1_000, 2_000, 10_000};

    @Override
    protected long minAnte() {
        return SeotdaLobbyState.game.equals("holdem") ? SeotdaLobbyState.minAnte : 100;
    }

    @Override
    protected long maxAnte() {
        return SeotdaLobbyState.game.equals("holdem") ? SeotdaLobbyState.maxAnte : 100_000;
    }

    @Override
    protected long[] formPresets() {
        return BB_PRESETS;
    }

    @Override
    protected String anteLabel() {
        return "빅 블라인드";
    }

    @Override
    protected String anteRangeText() {
        return "빅 블라인드는 " + num(minAnte()) + " ~ " + num(maxAnte()) + "이에요.";
    }

    @Override
    protected String anteHint(long v) {
        return "블라인드 " + num(Math.max(1, v / 2)) + "/" + num(v) + " · 입장 칩 " + num(v * SeotdaLobbyState.buyinBb);
    }

    @Override
    protected String presetLabel(long v) {
        return "BB " + num(v);
    }

    @Override
    protected long anteStep(long v) {
        return v >= 10_000 ? 1_000 : 100;
    }

    // ------------------------------------------------------------------ 입력

    @Override
    public boolean mouseClicked(double mx, double my, int button) {
        if (button != 0) return super.mouseClicked(mx, my, button);
        layout();
        switch (modal) {
            case BET -> {
                if (in(mx, my, BET_CLOSE) || in(mx, my, BET_CANCEL)) modal = Modal.NONE;
                else if (in(mx, my, SLIDER[0] - 20, SLIDER[1] - 20, SLIDER[2] + 40, SLIDER[3] + 40)) {
                    dragging = true;
                    setFromMouse(mx);
                } else if (in(mx, my, PRESET[0])) raiseTo = minTo();
                else if (in(mx, my, PRESET[1])) raiseTo = potTo(0.5);
                else if (in(mx, my, PRESET[2])) raiseTo = potTo(1.0);
                else if (in(mx, my, PRESET[3])) raiseTo = maxTo();
                else if (in(mx, my, BET_OK)) {
                    send("{\"action\":\"hd_raise\",\"to\":" + raiseTo + "}");
                    modal = Modal.NONE;
                }
                return true;
            }
            case END -> {
                if (pvp()) {
                    if (in(mx, my, 650, 610, 300, 70)) modal = Modal.NONE;
                } else if (in(mx, my, END_LEFT)) leaveNow();
                else if (in(mx, my, END_RIGHT) && HoldemState.balance >= HoldemState.buyIn) {
                    send("{\"action\":\"hd_next\"}");
                    modal = Modal.NONE;
                }
                return true;
            }
            case RANKS -> {
                if (in(mx, my, RANKS_CLOSE) || !in(mx, my, RANKS)) modal = Modal.NONE;
                return true;
            }
            case EXIT -> {
                if (in(mx, my, EXIT_STAY)) modal = Modal.NONE;
                else if (in(mx, my, EXIT_LEAVE)) leaveNow();
                return true;
            }
            case KICK -> {
                if (in(mx, my, KICK_NO)) modal = Modal.NONE;
                else if (in(mx, my, KICK_YES) && kickSeat > 0) {
                    send("{\"action\":\"hdp_kick\",\"id\":\"" + HoldemState.seats[kickSeat].id + "\"}");
                    modal = Modal.NONE;
                }
                return true;
            }
            case DELETE -> {
                if (in(mx, my, DEL_NO)) modal = Modal.NONE;
                else if (in(mx, my, DEL_YES)) {
                    leaving = true;
                    modal = Modal.NONE;
                    send("{\"action\":\"hdp_delete\"}");
                }
                return true;
            }
            case SETTINGS -> {
                int r = clickForm(mx, my);
                if (r == 1) modal = Modal.NONE;
                else if (r == 2) {
                    send(formJson("hdp_settings"));
                    modal = Modal.NONE;
                }
                return true;
            }
            default -> {
            }
        }
        if (in(mx, my, BTN_RANKS)) {
            openModal(Modal.RANKS);
            return true;
        }
        if (in(mx, my, BTN_SOUND)) {
            sound = !sound;
            UiSound.enabled = sound;
            return true;
        }
        if (in(mx, my, BTN_CLOSE)) {
            openModal(Modal.EXIT);
            return true;
        }
        if (pvp() && waiting()) return clickWait(mx, my);
        if (breaking() || phase().equals("end")) return clickBreak(mx, my);
        boolean myTurn = HoldemState.turn == 0 && phase().equals("bet");
        if (!myTurn) return true;
        if (in(mx, my, BTN_FOLD) && HoldemState.toCall > 0) send("{\"action\":\"hd_fold\"}");
        else if (in(mx, my, BTN_CALL)) send("{\"action\":\"hd_call\"}");
        else if (in(mx, my, BTN_RAISE) && HoldemState.canRaise) {
            raiseTo = minTo();
            openModal(Modal.BET);
        }
        return true;
    }

    private void leaveNow() {
        leaving = true;
        modal = Modal.NONE;
        if (pvp()) send("{\"action\":\"hdp_leave\"}");
        else {
            send("{\"action\":\"hd_close\"}");
            close();
        }
    }

    private boolean clickBreak(double mx, double my) {
        boolean end = phase().equals("end");
        if (pvp()) {
            if (HoldemState.inSession && !end && in(mx, my, BTN_STOP)) send("{\"action\":\"hdp_stop\"}");
            else if ((!HoldemState.inSession || end) && in(mx, my, BTN_LEAVE)) openModal(Modal.EXIT);
            return true;
        }
        if (in(mx, my, BTN_STOP)) {
            if (end) leaveNow();
            else send("{\"action\":\"hd_stop\"}");
        } else if (in(mx, my, BTN_NEXT) && (!end || HoldemState.balance >= HoldemState.buyIn)) {
            send("{\"action\":\"hd_next\"}");
        } else if (end && !in(mx, my, 90, BAR, 1420, 100)) openModal(Modal.END);
        return true;
    }

    private boolean clickWait(double mx, double my) {
        if (HoldemState.isHost) {
            for (int seat = 1; seat <= 2; seat++) {
                HoldemState.Seat st = HoldemState.seats[seat];
                if (!st.empty && !st.host && in(mx, my, seat == 1 ? SEAT1 : SEAT2)) {
                    kickSeat = seat;
                    openModal(Modal.KICK);
                    return true;
                }
            }
            if (in(mx, my, BTN_SET)) {
                openForm(HoldemState.roomTitle, HoldemState.bb);
                openModal(Modal.SETTINGS);
            } else if (in(mx, my, BTN_DEL)) openModal(Modal.DELETE);
            else if (in(mx, my, BTN_START) && HoldemState.allReady && HoldemState.balance >= HoldemState.buyIn) {
                send("{\"action\":\"hdp_start\"}");
            }
            return true;
        }
        boolean ready = HoldemState.seats[0].ready;
        if (in(mx, my, BTN_READY) && (ready || HoldemState.balance >= HoldemState.buyIn)) {
            send("{\"action\":\"hdp_ready\",\"on\":" + !ready + "}");
        } else if (in(mx, my, BTN_LEAVE)) openModal(Modal.EXIT);
        return true;
    }

    @Override
    public boolean mouseDragged(double mx, double my, int button, double dx, double dy) {
        if (dragging && modal == Modal.BET) {
            layout();
            setFromMouse(mx);
            return true;
        }
        return super.mouseDragged(mx, my, button, dx, dy);
    }

    @Override
    public boolean mouseReleased(double mx, double my, int button) {
        dragging = false;
        return super.mouseReleased(mx, my, button);
    }

    @Override
    public boolean mouseScrolled(double mx, double my, double h, double v) {
        if (modal == Modal.BET) raiseTo = clampTo(raiseTo + (v > 0 ? HoldemState.bb : -HoldemState.bb));
        return true;
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (keyCode == GLFW.GLFW_KEY_ESCAPE) {
            focus = null;
            if (modal != Modal.NONE) modal = Modal.NONE;
            else openModal(Modal.EXIT);
            return true;
        }
        if (modal == Modal.SETTINGS) {
            if (keyCode == GLFW.GLFW_KEY_ENTER || keyCode == GLFW.GLFW_KEY_KP_ENTER) {
                formTried = true;
                if (formError() == null) {
                    send(formJson("hdp_settings"));
                    modal = Modal.NONE;
                    focus = null;
                }
                return true;
            }
            if (typingKey(keyCode)) return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    @Override
    public boolean charTyped(char chr, int modifiers) {
        if (modal != Modal.SETTINGS) return true;
        return super.charTyped(chr, modifiers);
    }

    @Override
    public void removed() {
        if (!leaving && !switching) send("{\"action\":\"hd_close\"}");
        switching = false;
    }

    private static String str(JsonObject o, String k) {
        return o != null && o.has(k) ? o.get(k).getAsString() : "";
    }

    private static long num(JsonObject o, String k) {
        return o != null && o.has(k) ? o.get(k).getAsLong() : 0;
    }
}
