package kr.kkaegul.client.casino.seotda;

import com.google.gson.JsonObject;
import kr.kkaegul.client.casino.CasinoNet;
import kr.kkaegul.client.casino.UiSound;
import kr.kkaegul.client.casino.render.ElseaFont.Align;
import kr.kkaegul.client.casino.render.HqTexture;
import kr.kkaegul.client.casino.render.SdAssets;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.sound.SoundEvents;
import net.minecraft.util.Util;
import org.lwjgl.glfw.GLFW;

/**
 * 섯다 테이블 화면. AI 대전(나 vs AI 2명)과 플레이어 대전(방, 최대 3명) 둘 다 이 화면을 쓴다.
 * 한 판은 파산하거나 그만둘 때까지 이어지고, 턴마다 판돈이 이긴 쪽으로 넘어가는 게 보인다.
 * 좌표는 1600×1000 캔버스. 판정·돈은 모두 서버가 하고, 화면은 받은 상태를 그린다. 자리 0은 항상 나.
 */
public final class SeotdaScreen extends SdBase {

    private static final float CARD_RATIO = 0.6f;

    private enum Modal { NONE, BET, END, RANKS, EXIT, KICK, DELETE, SETTINGS }

    private Modal modal = Modal.NONE;
    private boolean specialTab;
    private long raise;
    private boolean dragging;
    private boolean leaving;
    private int kickSeat = -1;

    private int seenVersion = -1;
    private String handKey = "";
    private long dealAt;
    private String lastPhase = "";
    private long flipAt;
    private long seenResultId = -1, seenSummaryId = -1;
    private long lastPot;
    // 돈 이동 연출
    private long flyAt = -1;
    private int flyTo = -1;
    private final double[] shownChips = {-1, -1, -1};
    private long lastFrame;

    public SeotdaScreen() {
        super("섯다");
    }

    // ----- 영역 -----
    private static final float TOP = 72;           // 위 오른쪽 버튼 줄
    private static final float BAR = 866;          // 아래 행동 칸
    private static final float[] BTN_RANKS = {1210, TOP, 170, 50};
    private static final float[] BTN_SOUND = {1392, TOP, 54, 50};
    private static final float[] BTN_CLOSE = {1458, TOP, 54, 50};
    private static final float[] BTN_DIE = {330, BAR + 10, 330, 72};
    private static final float[] BTN_CALL = {690, BAR + 10, 330, 72};
    private static final float[] BTN_RAISE = {1050, BAR + 10, 330, 72};
    // 대기실 (방장)
    private static final float[] BTN_SET = {330, BAR + 10, 290, 72};
    private static final float[] BTN_DEL = {640, BAR + 10, 290, 72};
    private static final float[] BTN_START = {950, BAR + 10, 430, 72};
    // 대기실 (참가자) · 쉬는 시간
    private static final float[] BTN_READY = {330, BAR + 10, 600, 72};
    private static final float[] BTN_LEAVE = {950, BAR + 10, 430, 72};
    private static final float[] BTN_STOP = {330, BAR + 10, 520, 72};
    private static final float[] BTN_NEXT = {870, BAR + 10, 510, 72};
    private static final float[] SEAT1 = {120, 180, 180, 180};
    private static final float[] SEAT2 = {1300, 180, 180, 180};

    private static final float[] BET = {500, 250, 600, 525};
    private static final float[] BET_CLOSE = {1040, 285, 40, 40};
    private static final float[] SLIDER = {565, 500, 470, 18};
    private static final float[] P_MIN = {545, 540, 160, 54};
    private static final float[] P_DOUBLE = {720, 540, 160, 54};
    private static final float[] P_MAX = {895, 540, 160, 54};
    private static final float[] BET_CANCEL = {545, 625, 245, 70};
    private static final float[] BET_OK = {810, 625, 245, 70};

    private static final float[] END = {500, 250, 600, 470};
    private static final float[] END_LEFT = {545, 610, 245, 70};
    private static final float[] END_RIGHT = {810, 610, 245, 70};

    private static final float[] RANKS = {460, 70, 680, 860};
    private static final float[] RANKS_CLOSE = {1080, 105, 40, 40};
    private static final float[] TAB_BASIC = {505, 170, 290, 52};
    private static final float[] TAB_SPECIAL = {805, 170, 290, 52};

    private static final float[] EXIT = {500, 300, 600, 400};
    private static final float[] EXIT_STAY = {545, 590, 245, 70};
    private static final float[] EXIT_LEAVE = {810, 590, 245, 70};

    private static final float[] KICK = {500, 250, 600, 460};
    private static final float[] KICK_NO = {545, 600, 245, 70};
    private static final float[] KICK_YES = {810, 600, 245, 70};

    private static final float[] DELETE = {500, 300, 600, 400};
    private static final float[] DEL_NO = {545, 590, 245, 70};
    private static final float[] DEL_YES = {810, 590, 245, 70};

    private static boolean pvp() {
        return SeotdaState.pvp;
    }

    private static String phase() {
        return SeotdaState.phase;
    }

    private static boolean waiting() {
        return phase().equals("wait");
    }

    private static boolean breaking() {
        return phase().equals("break");
    }

    // ------------------------------------------------------------------ 상태 변화

    @Override
    public void tick() {
        if (SeotdaLocal.active()) SeotdaLocal.tick();
        if (SeotdaState.version == seenVersion) return;
        seenVersion = SeotdaState.version;
        boolean dealing = phase().equals("bet") || phase().equals("rematch");
        String key = SeotdaState.hands + ":" + String.join(",", SeotdaState.seats[0].cards) + ":" + SeotdaState.rematches;
        if (!key.equals(handKey)) {
            handKey = key;
            if (dealing && !SeotdaState.seats[0].cards[0].isEmpty()) {
                dealAt = Util.getMeasuringTimeMs();
                play(SoundEvents.ITEM_BOOK_PAGE_TURN, 1.0f);
                if (modal == Modal.BET || modal == Modal.KICK || modal == Modal.DELETE || modal == Modal.SETTINGS) modal = Modal.NONE;
            }
        }
        // 판돈이 늘면 칩 소리
        if (phase().equals("bet") && SeotdaState.pot > lastPot && lastPot > 0) UiSound.chips();
        lastPot = SeotdaState.pot;
        // 턴 결과: 카드 뒤집기 + 판돈이 이긴 쪽으로
        JsonObject r = SeotdaState.result;
        if (breaking() && r != null && r.has("id") && r.get("id").getAsLong() != seenResultId) {
            seenResultId = r.get("id").getAsLong();
            flipAt = Util.getMeasuringTimeMs();
            flyAt = flipAt + 350;
            flyTo = r.has("winner") ? (int) r.get("winner").getAsLong() : -1;
            boolean win = "win".equals(str(r, "kind"));
            play(win ? SoundEvents.ENTITY_PLAYER_LEVELUP : SoundEvents.ENTITY_VILLAGER_NO, win ? 1.3f : 0.9f);
            if (modal == Modal.BET) modal = Modal.NONE;
        }
        // 판 결과 요약
        JsonObject sm = SeotdaState.summary;
        if (sm != null) {
            long id = sm.has("id") ? sm.get("id").getAsLong() : SeotdaState.hands * 1000L + 7;
            boolean endNow = pvp() ? waiting() : phase().equals("end");
            if (endNow && id != seenSummaryId) {
                seenSummaryId = id;
                openModal(Modal.END);
            }
        }
        if (!pvp() && !phase().equals("end") && modal == Modal.END) modal = Modal.NONE;
        lastPhase = phase();
        if (modal == Modal.BET && (SeotdaState.turn != 0 || !SeotdaState.canRaise)) modal = Modal.NONE;
        if (modal == Modal.KICK && (kickSeat < 0 || SeotdaState.seats[kickSeat].empty || !SeotdaState.isHost)) modal = Modal.NONE;
        if ((modal == Modal.DELETE || modal == Modal.SETTINGS) && (!SeotdaState.isHost || SeotdaState.session)) modal = Modal.NONE;
    }

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
            case RANKS -> drawRanks(ctx, mx, my);
            case EXIT -> drawExit(ctx, mx, my);
            case KICK -> drawKick(ctx, mx, my);
            case DELETE -> drawDelete(ctx, mx, my);
            case SETTINGS -> drawForm(ctx, mx, my, true, SeotdaState.balance);
            default -> {
            }
        }
        endModal(ctx);
    }

    /** 보유금 숫자가 실제 값으로 부드럽게 따라감 (돈이 오가는 게 보이게) */
    private void updateChips() {
        long now = Util.getMeasuringTimeMs();
        float dt = lastFrame == 0 ? 0 : Math.min(0.1f, (now - lastFrame) / 1000f);
        lastFrame = now;
        boolean hold = flyAt > 0 && now < flyAt + 450; // 동전이 도착할 때까지 잠깐 기다림
        for (int i = 0; i < 3; i++) {
            double target = SeotdaState.seats[i].chips;
            if (shownChips[i] < 0) shownChips[i] = target;
            if (hold && i == flyTo) continue;
            double d = target - shownChips[i];
            if (Math.abs(d) < 1) shownChips[i] = target;
            else shownChips[i] += d * Math.min(1, dt * 7);
        }
    }

    private long chips(int seat) {
        return Math.round(shownChips[seat] < 0 ? SeotdaState.seats[seat].chips : shownChips[seat]);
    }

    private void drawTable(DrawContext ctx, int mx, int my) {
        titlePlaque(ctx, "섯다");
        if (pvp()) {
            String t = SeotdaState.roomTitle;
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

        SdAssets.SD_TABLE.draw(ctx, X(140), Y(265), L(1320), L(500));

        drawSeat(ctx, 1, 120, 345, mx, my);
        drawSeat(ctx, 2, 1300, 1043, mx, my);

        // 가운데
        JsonObject r = SeotdaState.result;
        if (pvp() && waiting()) {
            String line;
            int n = SeotdaState.roomCount;
            if (n < 2) line = "다른 플레이어를 기다리고 있어요";
            else if (SeotdaState.isHost) line = SeotdaState.allReady ? "모두 준비됐어요" : "참가자가 준비하면 시작할 수 있어요";
            else line = SeotdaState.seats[0].ready ? "방장이 시작하기를 기다려요" : "준비하기를 눌러 주세요";
            text(ctx, line, 800, 400, 24, 0xFFEAF2DF, Align.CENTER);
            text(ctx, n + " / 3", 800, 452, 56, WHITE, Align.CENTER);
        } else if (breaking() && r != null && r.has("pot")) {
            boolean win = "win".equals(str(r, "kind"));
            String who = win ? "내가" : str(r, "winnerName") + (pvp() ? " 님이" : "(이)가");
            text(ctx, who + " 이겼어요 · " + str(r, "rank"), 800, 392, 24, 0xFFEAF2DF, Align.CENTER);
            long pot = num(r, "payout") > 0 ? num(r, "payout") : num(r, "pot");
            text(ctx, (win ? "+" : "") + num(pot), 800, 446, 52, win ? 0xFFFFF1A8 : WHITE, Align.CENTER);
        } else if (phase().equals("end")) {
            text(ctx, "판이 끝났어요", 800, 420, 30, WHITE, Align.CENTER);
        } else {
            boolean flying = flyAt > 0 && Util.getMeasuringTimeMs() < flyAt + 450;
            if (!flying) icon(ctx, SdAssets.SD_COIN_STACK, 800, 362, 62);
            text(ctx, "총 판돈", 800, 410, 21, 0xFFEAF2DF, Align.CENTER);
            text(ctx, num(SeotdaState.pot), 800, 452, 52, WHITE, Align.CENTER);
        }
        if (!SeotdaState.log.isEmpty()) {
            float lw = textW(SeotdaState.log, 19) + 44;
            fill(ctx, 800 - lw / 2, 488, lw, 36, 0xCC4D634E);
            text(ctx, SeotdaState.log, 800, 506, 19, 0xFFFFF8E6, Align.CENTER);
        }

        // 나
        SeotdaState.Seat me = SeotdaState.seats[0];
        portrait(ctx, me.avatar, 500, 565, 170);
        if (me.host && pvp()) icon(ctx, SdAssets.SD_PVP_CROWN, 585, 560, 34);
        textOutlined(ctx, pvp() ? me.name : "나", 585, 764, 25, BROWN, Align.CENTER);
        coinLine(ctx, chips(0), 585, 792, 21);
        boolean hasCards = !me.cards[0].isEmpty();
        if (hasCards) {
            for (int i = 0; i < 2; i++) card(ctx, me.cards[i], 720 + i * 132, 560, 200, 0, i, me.folded);
        } else if (pvp() && waiting() && !me.host) {
            pill(ctx, me.ready ? SdAssets.SD_BUTTON_SAGE : SdAssets.SD_RANKING_ROW, 760, 640, 180, 44,
                    me.ready ? "준비 완료" : "준비 전", 20, me.ready ? WHITE : MUTED);
        } else if (pvp() && me.out) {
            pill(ctx, SdAssets.SD_RANKING_ROW, 760, 640, 180, 44, "구경 중", 20, MUTED);
        }
        if (!me.rank.isEmpty() && hasCards) {
            String t = "내 족보 · " + me.rank;
            float bw = textW(t, 19) + 40;
            SdAssets.SD_RANKING_ROW.drawNine(ctx, X(852 - bw / 2), Y(764), L(bw), L(32), 20, L(14));
            text(ctx, "내 족보 · ", 852 - bw / 2 + 20, 780, 19, BROWN, Align.LEFT);
            text(ctx, me.rank, 852 + bw / 2 - 20, 780, 19, CORAL, Align.RIGHT);
        }

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
        String banner;
        int turn = SeotdaState.turn;
        float by = BAR - 42;
        if (phase().equals("rematch")) banner = "재경기 준비 중";
        else if (turn == 0) banner = "내 차례예요";
        else if (turn > 0) banner = pvp() ? SeotdaState.seats[turn].name + " 님 차례예요"
                : SeotdaState.seats[turn].name.split(" · ")[0] + " 차례예요";
        else banner = pvp() && !SeotdaState.inSession ? "구경 중이에요" : "";
        if (turn == 0) {
            long sec = (SeotdaState.remaining() + 999) / 1000;
            text(ctx, banner, 780, by, 24, BROWN, Align.CENTER);
            text(ctx, sec + "초", 1010, by, 20, sec <= 5 ? CORAL : MUTED, Align.RIGHT);
            float frac = SeotdaState.turnLimit <= 0 ? 0 : (float) SeotdaState.remaining() / SeotdaState.turnLimit;
            fill(ctx, 590, BAR - 25, 420 * frac, 4, sec <= 5 ? 0xFFCD7162 : 0xFF748D67);
        } else {
            text(ctx, banner, 800, by, 24, BROWN, Align.CENTER);
        }

        footerBox(ctx, "콜 금액", num(SeotdaState.toCall));
        boolean myTurn = turn == 0 && phase().equals("bet");
        labelButton(ctx, SdAssets.SD_BUTTON_CREAM, BTN_DIE, "다이", 28, BROWN, myTurn, mx, my);
        labelButton(ctx, SdAssets.SD_BUTTON_SAGE, BTN_CALL,
                SeotdaState.toCall > 0 ? "콜 " + num(SeotdaState.toCall) : "체크", 28, WHITE, myTurn, mx, my);
        labelButton(ctx, SdAssets.SD_BUTTON_CREAM, BTN_RAISE, "레이즈", 28, BROWN, myTurn && SeotdaState.canRaise, mx, my);
    }

    /** 아래 칸 배경 + 왼쪽 정보 + 오른쪽 기본 판돈 */
    private void footerBox(DrawContext ctx, String leftLabel, String leftValue) {
        SdAssets.SD_INPUT_BOX.drawNine(ctx, X(90), Y(BAR), L(1420), L(92), 30, L(24));
        text(ctx, leftLabel, 200, BAR + 25, 19, MUTED, Align.CENTER);
        text(ctx, leftValue, 200, BAR + 58, 32, BROWN, Align.CENTER);
        text(ctx, "기본 판돈", 1445, BAR + 25, 17, MUTED, Align.CENTER);
        text(ctx, num(SeotdaState.ante), 1445, BAR + 55, 23, BROWN, Align.CENTER);
    }

    /** 턴 사이 쉬는 시간: 그만하기 / 바로 다음 턴 */
    private void drawBreakFooter(DrawContext ctx, int mx, int my) {
        boolean end = phase().equals("end");
        long sec = (SeotdaState.remaining() + 999) / 1000;
        SdAssets.SD_TURN_BANNER.drawNine(ctx, X(560), Y(BAR - 66), L(480), L(48), 30, L(18));
        String banner = end ? "판이 끝났어요" : pvp() && !SeotdaState.inSession ? "구경 중이에요 · 다음 턴까지 " + sec + "초"
                : "다음 턴까지 " + sec + "초";
        text(ctx, banner, 800, BAR - 42, 22, BROWN, Align.CENTER);
        if (!end) {
            float frac = SeotdaState.turnLimit <= 0 ? 0 : (float) SeotdaState.remaining() / SeotdaState.turnLimit;
            fill(ctx, 590, BAR - 25, 420 * frac, 4, 0xFF748D67);
        }
        footerBox(ctx, "진행한 턴", SeotdaState.hands + "턴");
        if (pvp()) {
            if (SeotdaState.inSession && !end) {
                labelButton(ctx, SdAssets.SD_BUTTON_CREAM, BTN_STOP, "그만하기", 28, BROWN, true, mx, my);
                text(ctx, "그만하면 대기실에서 구경해요", 1125, BAR + 46, 19, MUTED, Align.CENTER);
            } else {
                text(ctx, "판이 끝나면 다시 준비할 수 있어요", 590, BAR + 46, 19, MUTED, Align.CENTER);
                iconButton(ctx, SdAssets.SD_BUTTON_CREAM, BTN_LEAVE, SdAssets.SD_PVP_LEAVE, "방 나가기", 26, BROWN, true, mx, my);
            }
            return;
        }
        if (end) {
            labelButton(ctx, SdAssets.SD_BUTTON_CREAM, BTN_STOP, "나가기", 28, BROWN, true, mx, my);
            labelButton(ctx, SdAssets.SD_BUTTON_SAGE, BTN_NEXT, "새 판 시작", 28, WHITE,
                    SeotdaState.balance >= SeotdaState.ante, mx, my);
        } else {
            labelButton(ctx, SdAssets.SD_BUTTON_CREAM, BTN_STOP, "그만하기", 28, BROWN, true, mx, my);
            labelButton(ctx, SdAssets.SD_BUTTON_SAGE, BTN_NEXT, "바로 다음 턴", 28, WHITE, true, mx, my);
        }
    }

    /** 대기실 아래 버튼 */
    private void drawWaitFooter(DrawContext ctx, int mx, int my) {
        footerBox(ctx, "참여 인원", SeotdaState.roomCount + " / 3");
        if (SeotdaState.isHost) {
            String hint = SeotdaState.roomCount < 2 ? "2명 이상 입장하면 시작할 수 있어요"
                    : SeotdaState.allReady ? "모두 준비됐어요. 게임을 시작해 보세요" : "참가자가 모두 준비하면 시작할 수 있어요";
            text(ctx, hint, 800, BAR - 42, 19, MUTED, Align.CENTER);
            iconButton(ctx, SdAssets.SD_BUTTON_CREAM, BTN_SET, SdAssets.SD_PVP_SETTINGS, "방 설정", 25, BROWN, true, mx, my);
            iconButton(ctx, SdAssets.SD_BUTTON_CREAM, BTN_DEL, SdAssets.SD_PVP_TRASH, "방 삭제", 25, CORAL, true, mx, my);
            boolean can = SeotdaState.allReady && SeotdaState.balance >= SeotdaState.ante;
            labelButton(ctx, SdAssets.SD_BUTTON_SAGE, BTN_START, "게임 시작", 30, WHITE, can, mx, my);
        } else {
            boolean ready = SeotdaState.seats[0].ready;
            text(ctx, ready ? "방장이 시작하면 판돈 " + num(SeotdaState.ante) + " 다이아씩 걸고 시작해요" : "준비하면 방장이 게임을 시작할 수 있어요",
                    800, BAR - 42, 19, MUTED, Align.CENTER);
            boolean can = ready || SeotdaState.balance >= SeotdaState.ante;
            labelButton(ctx, ready ? SdAssets.SD_BUTTON_CREAM : SdAssets.SD_BUTTON_SAGE, BTN_READY,
                    ready ? "준비 취소" : "준비하기", 30, ready ? BROWN : WHITE, can, mx, my);
            iconButton(ctx, SdAssets.SD_BUTTON_CREAM, BTN_LEAVE, SdAssets.SD_PVP_LEAVE, "방 나가기", 26, BROWN, true, mx, my);
        }
    }

    private void drawSeat(DrawContext ctx, int seat, float px, float cardX, int mx, int my) {
        SeotdaState.Seat st = SeotdaState.seats[seat];
        if (st.empty) {
            icon(ctx, SdAssets.SD_PVP_SEAT_EMPTY, px + 90, 270, 176);
            pill(ctx, SdAssets.SD_RANKING_ROW, px + 5, 372, 170, 38, "입장 대기 중…", 18, MUTED);
            return;
        }
        boolean kickable = pvp() && SeotdaState.isHost && waiting() && !st.host;
        if (kickable && in(mx, my, px, 180, 180, 180)) {
            SdAssets.SD_AVATAR_FRAME.draw(ctx, X(px - 6), Y(174), L(192), L(192));
        }
        portrait(ctx, st.avatar, px, 180, 180);
        if (st.out) {
            // 파산 · 구경: 흐리게
            dot(ctx, px + 90, 270, 82, 0xA0F4EBDA);
            pill(ctx, SdAssets.SD_RANKING_ROW, px + 30, 252, 120, 38, pvp() ? "구경" : "파산", 19, pvp() ? MUTED : CORAL);
        }
        if (st.host && pvp()) icon(ctx, SdAssets.SD_PVP_CROWN, px + 90, 176, 36);
        if (kickable) {
            pill(ctx, SdAssets.SD_BUTTON_CREAM, px + 118, 316, 62, 42, "", 10, BROWN);
            icon(ctx, SdAssets.SD_PVP_KICK, px + 149, 337, 22);
        }
        textOutlined(ctx, st.name, px + 90, 384, 22, BROWN, Align.CENTER);
        coinLine(ctx, chips(seat), px + 90, 412, 21);
        boolean hasCards = !st.cards[0].isEmpty();
        if (hasCards) for (int i = 0; i < 2; i++) card(ctx, st.cards[i], cardX + i * 112, 205, 170, seat, i, st.folded);
        if (pvp() && waiting() && !st.host) {
            pill(ctx, st.ready ? SdAssets.SD_BUTTON_SAGE : SdAssets.SD_RANKING_ROW, px + 15, 432, 150, 36,
                    st.ready ? "준비 완료" : "준비 전", 17, st.ready ? WHITE : MUTED);
        }
        if (!st.status.isEmpty() && hasCards) {
            float[] r = {cardX + 10, 398, 200, 38};
            HqTexture tex = switch (st.status) {
                case "레이즈" -> SdAssets.SD_BUTTON_CORAL;
                case "다이" -> SdAssets.SD_RANKING_ROW;
                default -> SdAssets.SD_STATUS_PILL;
            };
            pill(ctx, tex, r[0], r[1], r[2], r[3], st.status, 20, st.status.equals("다이") ? MUTED : WHITE);
        }
        if (!st.rank.isEmpty() && hasCards) text(ctx, st.rank, cardX + 110, 455, 21, CREAM, Align.CENTER);
    }

    /** 판돈이 이긴 사람에게 날아가는 연출 */
    private void drawCoinFly(DrawContext ctx) {
        if (flyAt < 0 || flyTo < 0) return;
        long now = Util.getMeasuringTimeMs();
        float t = (now - flyAt) / 450f;
        if (t < 0) {
            icon(ctx, SdAssets.SD_COIN_STACK, 800, 362, 62);
            return;
        }
        if (t > 1) {
            UiSound.coins();
            flyAt = -1;
            return;
        }
        float tx = flyTo == 0 ? 585 : flyTo == 1 ? 210 : 1390;
        float ty = flyTo == 0 ? 700 : 300;
        float e = 1 - (1 - t) * (1 - t) * (1 - t);
        float x = 800 + (tx - 800) * e, y = 362 + (ty - 362) * e - (float) Math.sin(t * Math.PI) * 60;
        icon(ctx, SdAssets.SD_COIN_STACK, x, y, 62 * (1 - 0.35f * t));
    }

    /** 카드 한 장 (나눠 주는 연출 + 결과 때 뒤집기) */
    private void card(DrawContext ctx, String id, float x, float y, float h, int seat, int idx, boolean folded) {
        if (id == null || id.isEmpty()) return;
        float w = h * CARD_RATIO;
        long now = Util.getMeasuringTimeMs();
        int order = idx * 3 + seat;
        float t = Math.min(1f, Math.max(0f, (now - dealAt - order * 80L) / 260f));
        if (t <= 0f) return;
        float e = 1 - (1 - t) * (1 - t);
        float cx = 800 + (x + w / 2 - 800) * e, cy = 420 + (y + h / 2 - 420) * e;
        float sc = 0.55f + 0.45f * e;
        float flipK = 1f;
        String shown = id;
        if (seat > 0 && breaking() && !id.equals("back")) {
            float ft = Math.min(1f, (now - flipAt - idx * 90L) / 320f);
            flipK = Math.abs((float) Math.cos(ft * Math.PI));
            if (ft < 0.5f) shown = "back";
        }
        HqTexture tex = SdAssets.card(shown);
        float dw = w * sc * flipK, dh = h * sc;
        tex.draw(ctx, X(cx - dw / 2), Y(cy - dh / 2), L(dw), L(dh));
        if (folded) fill(ctx, cx - dw / 2, cy - dh / 2, dw, dh, 0x88F4E6CE);
    }

    // ----- 베팅 팝업 -----

    private void drawBet(DrawContext ctx, int mx, int my) {
        frame(ctx, BET);
        text(ctx, "베팅 금액", 545, 305, 34, BROWN, Align.LEFT);
        icon(ctx, SdAssets.SD_CLOSE, BET_CLOSE[0] + 20, BET_CLOSE[1] + 20, 22);
        long total = SeotdaState.toCall + raise;
        text(ctx, SeotdaState.toCall > 0 ? "콜 " + num(SeotdaState.toCall) + " 다이아 + 추가로 올릴 금액을 고르세요"
                : "추가로 올릴 금액을 고르세요", 545, 348, 19, MUTED, Align.LEFT);
        SdAssets.SD_INPUT_BOX.drawNine(ctx, X(545), Y(378), L(510), L(96), 30, L(26));
        text(ctx, num(raise), 800, 426, 46, BROWN, Align.CENTER);
        SdAssets.SD_SLIDER_TRACK.drawNine(ctx, X(SLIDER[0]), Y(SLIDER[1]), L(SLIDER[2]), L(SLIDER[3]), 8, L(9));
        float f = sliderFrac();
        fill(ctx, SLIDER[0] + 4, SLIDER[1] + 5, (SLIDER[2] - 8) * f, SLIDER[3] - 10, 0xFF6B855E);
        float tx = SLIDER[0] + SLIDER[2] * f;
        SdAssets.SD_SLIDER_THUMB.draw(ctx, X(tx - 17), Y(SLIDER[1] + SLIDER[3] / 2 - 17), L(34), L(34));
        labelButton(ctx, SdAssets.SD_BUTTON_CREAM, P_MIN, "최소", 22, BROWN, true, mx, my);
        labelButton(ctx, SdAssets.SD_BUTTON_CREAM, P_DOUBLE, "2배", 22, BROWN, true, mx, my);
        labelButton(ctx, SdAssets.SD_BUTTON_CREAM, P_MAX, "최대", 22, BROWN, true, mx, my);
        labelButton(ctx, SdAssets.SD_BUTTON_CREAM, BET_CANCEL, "취소", 26, BROWN, true, mx, my);
        labelButton(ctx, SdAssets.SD_BUTTON_CORAL, BET_OK, "레이즈 " + num(total), 25, WHITE, true, mx, my);
        text(ctx, "보유 " + num(SeotdaState.balance) + " 다이아 · 레이즈 " + SeotdaState.raisesLeft + "번 남음", 800, 735, 17, MUTED, Align.CENTER);
    }

    private float sliderFrac() {
        long lo = SeotdaState.minRaise, hi = Math.max(lo, SeotdaState.maxRaise);
        return hi == lo ? 1f : (float) (raise - lo) / (hi - lo);
    }

    private void setRaiseFromMouse(double mx) {
        float f = (float) ((mx - X(SLIDER[0])) / L(SLIDER[2]));
        f = Math.max(0, Math.min(1, f));
        long lo = SeotdaState.minRaise, hi = Math.max(lo, SeotdaState.maxRaise);
        long step = Math.max(100, SeotdaState.ante / 2);
        long v = lo + Math.round((hi - lo) * f / step) * step;
        raise = Math.max(lo, Math.min(hi, v));
    }

    // ----- 판 결과 -----

    private void drawEnd(DrawContext ctx, int mx, int my) {
        frame(ctx, END);
        JsonObject sm = SeotdaState.summary;
        if (sm == null) return;
        long net = num(sm, "net");
        icon(ctx, SdAssets.SD_LEAF_ACCENT, 800, 330, 80);
        text(ctx, "판이 끝났어요", 800, 400, 36, BROWN, Align.CENTER);
        text(ctx, str(sm, "reason"), 800, 444, 19, MUTED, Align.CENTER);
        text(ctx, "총 " + num(sm, "hands") + "턴 · 이번 판 손익", 800, 492, 20, MUTED, Align.CENTER);
        text(ctx, (net > 0 ? "+" : net < 0 ? "-" : "") + num(Math.abs(net)), 800, 545, 48, net >= 0 ? GREEN : CORAL, Align.CENTER);
        if (pvp()) {
            labelButton(ctx, SdAssets.SD_BUTTON_SAGE, new float[]{650, 610, 300, 70}, "대기실로", 25, WHITE, true, mx, my);
            text(ctx, "다음 판에 캐릭터가 다시 바뀌어요", 800, 702, 16, MUTED, Align.CENTER);
            return;
        }
        labelButton(ctx, SdAssets.SD_BUTTON_CREAM, END_LEFT, "나가기", 25, BROWN, true, mx, my);
        labelButton(ctx, SdAssets.SD_BUTTON_SAGE, END_RIGHT, "새 판 시작", 25, WHITE,
                SeotdaState.balance >= SeotdaState.ante, mx, my);
        text(ctx, "새 판은 캐릭터가 새로 정해져요 · 판돈 " + num(SeotdaState.ante) + " 다이아", 800, 702, 16, MUTED, Align.CENTER);
    }

    // ----- 족보 팝업 -----

    private void drawRanks(DrawContext ctx, int mx, int my) {
        frame(ctx, RANKS);
        text(ctx, "족보 보기", 505, 128, 34, BROWN, Align.LEFT);
        icon(ctx, SdAssets.SD_CLOSE, RANKS_CLOSE[0] + 20, RANKS_CLOSE[1] + 20, 22);
        tab(ctx, TAB_BASIC, "기본 족보", !specialTab, mx, my);
        tab(ctx, TAB_SPECIAL, "특수패", specialTab, mx, my);
        if (!specialTab) {
            text(ctx, "위로 갈수록 강해요 · 같은 족보는 재경기", 800, 248, 17, MUTED, Align.CENTER);
            String[][] rows = SeotdaLocal.basic();
            for (int i = 0; i < rows.length; i++) {
                float y = 268 + i * 29;
                SdAssets.SD_RANKING_ROW.drawNine(ctx, X(505), Y(y), L(590), L(27), 20, L(10));
                text(ctx, Integer.toString(i + 1), 522, y + 14, 14, MUTED, Align.CENTER);
                text(ctx, rows[i][0], 545, y + 14, 18, BROWN, Align.LEFT);
                text(ctx, rows[i][1], 1080, y + 14, 15, MUTED, Align.RIGHT);
            }
        } else {
            text(ctx, "특수패는 정해진 상대만 잡거나, 재경기를 만들어요", 800, 248, 17, MUTED, Align.CENTER);
            String[][] rows = SeotdaLocal.special();
            for (int i = 0; i < rows.length; i++) {
                float y = 275 + i * 140;
                SdAssets.SD_RANKING_ROW.drawNine(ctx, X(505), Y(y), L(590), L(124), 20, L(16));
                text(ctx, rows[i][0], 530, y + 34, 26, CORAL, Align.LEFT);
                String[] parts = rows[i][1].split(" · ");
                text(ctx, parts[0], 1070, y + 34, 18, MUTED, Align.RIGHT);
                if (parts.length > 1) text(ctx, parts[1], 530, y + 84, 19, BROWN, Align.LEFT);
            }
        }
    }

    private void tab(DrawContext ctx, float[] r, String label, boolean sel, int mx, int my) {
        button(ctx, sel ? SdAssets.SD_BUTTON_SAGE : SdAssets.SD_BUTTON_CREAM, r, mx, my, !sel);
        text(ctx, label, r[0] + r[2] / 2, r[1] + r[3] / 2, 22, sel ? WHITE : BROWN, Align.CENTER);
    }

    // ----- 나가기 · 강퇴 · 삭제 -----

    private void drawExit(DrawContext ctx, int mx, int my) {
        frame(ctx, EXIT);
        icon(ctx, SdAssets.SD_LEAF_ACCENT, 800, 395, 100);
        boolean inHand = phase().equals("bet") && !SeotdaState.seats[0].folded && !SeotdaState.seats[0].cards[0].isEmpty();
        String title = pvp() ? "방을 나갈까요?" : "섯다를 나갈까요?";
        String sub;
        if (inHand) sub = "지금 나가면 이번 턴은 다이 처리돼요 (-" + num(SeotdaState.paid) + " 다이아)";
        else if (pvp() && SeotdaState.isHost && SeotdaState.roomCount > 1) sub = "방장은 다음 사람에게 넘어가요";
        else if (pvp()) sub = "방 목록으로 돌아가요";
        else sub = "다음에 또 만나요!";
        text(ctx, title, 800, 475, 32, BROWN, Align.CENTER);
        text(ctx, sub, 800, 522, 19, inHand ? CORAL : MUTED, Align.CENTER);
        labelButton(ctx, SdAssets.SD_BUTTON_CREAM, EXIT_STAY, "계속하기", 25, BROWN, true, mx, my);
        labelButton(ctx, SdAssets.SD_BUTTON_CORAL, EXIT_LEAVE, "나가기", 25, WHITE, true, mx, my);
    }

    private void drawKick(DrawContext ctx, int mx, int my) {
        frame(ctx, KICK);
        if (kickSeat < 0) return;
        SeotdaState.Seat st = SeotdaState.seats[kickSeat];
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
        text(ctx, SeotdaState.roomTitle, 800, 540, 18, MUTED, Align.CENTER);
        labelButton(ctx, SdAssets.SD_BUTTON_CREAM, DEL_NO, "유지하기", 25, BROWN, true, mx, my);
        labelButton(ctx, SdAssets.SD_BUTTON_CORAL, DEL_YES, "방 삭제", 25, WHITE, true, mx, my);
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
                    setRaiseFromMouse(mx);
                } else if (in(mx, my, P_MIN)) raise = SeotdaState.minRaise;
                else if (in(mx, my, P_DOUBLE)) raise = clampRaise(SeotdaState.minRaise * 2);
                else if (in(mx, my, P_MAX)) raise = clampRaise(SeotdaState.maxRaise);
                else if (in(mx, my, BET_OK)) {
                    CasinoNet.seotda("{\"action\":\"seotda_raise\",\"amount\":" + raise + "}");
                    modal = Modal.NONE;
                }
                return true;
            }
            case END -> {
                if (pvp()) {
                    if (in(mx, my, 650, 610, 300, 70)) modal = Modal.NONE;
                } else if (in(mx, my, END_LEFT)) {
                    leaveNow();
                } else if (in(mx, my, END_RIGHT) && SeotdaState.balance >= SeotdaState.ante) {
                    CasinoNet.seotda("{\"action\":\"seotda_next\"}");
                    modal = Modal.NONE;
                }
                return true;
            }
            case RANKS -> {
                if (in(mx, my, RANKS_CLOSE)) modal = Modal.NONE;
                else if (in(mx, my, TAB_BASIC)) specialTab = false;
                else if (in(mx, my, TAB_SPECIAL)) specialTab = true;
                else if (!in(mx, my, RANKS)) modal = Modal.NONE;
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
                    CasinoNet.seotda("{\"action\":\"sdp_kick\",\"id\":\"" + SeotdaState.seats[kickSeat].id + "\"}");
                    modal = Modal.NONE;
                }
                return true;
            }
            case DELETE -> {
                if (in(mx, my, DEL_NO)) modal = Modal.NONE;
                else if (in(mx, my, DEL_YES)) {
                    leaving = true;
                    modal = Modal.NONE;
                    CasinoNet.seotda("{\"action\":\"sdp_delete\"}");
                }
                return true;
            }
            case SETTINGS -> {
                int r = clickForm(mx, my);
                if (r == 1) modal = Modal.NONE;
                else if (r == 2) {
                    CasinoNet.seotda(formJson("sdp_settings"));
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
        boolean myTurn = SeotdaState.turn == 0 && phase().equals("bet");
        if (!myTurn) return true;
        if (in(mx, my, BTN_DIE)) {
            CasinoNet.seotda("{\"action\":\"seotda_die\"}");
        } else if (in(mx, my, BTN_CALL)) {
            CasinoNet.seotda("{\"action\":\"seotda_call\"}");
        } else if (in(mx, my, BTN_RAISE) && SeotdaState.canRaise) {
            raise = SeotdaState.minRaise;
            openModal(Modal.BET);
        }
        return true;
    }

    private void leaveNow() {
        leaving = true;
        modal = Modal.NONE;
        if (pvp()) {
            // 서버가 방 목록을 보내 주면 그 화면으로 바뀜
            CasinoNet.seotda("{\"action\":\"sdp_leave\"}");
        } else {
            CasinoNet.seotda("{\"action\":\"seotda_close\"}");
            close();
        }
    }

    private boolean clickBreak(double mx, double my) {
        boolean end = phase().equals("end");
        if (pvp()) {
            if (SeotdaState.inSession && !end && in(mx, my, BTN_STOP)) {
                CasinoNet.seotda("{\"action\":\"sdp_stop\"}");
            } else if ((!SeotdaState.inSession || end) && in(mx, my, BTN_LEAVE)) {
                openModal(Modal.EXIT);
            }
            return true;
        }
        if (in(mx, my, BTN_STOP)) {
            if (end) leaveNow();
            else CasinoNet.seotda("{\"action\":\"seotda_stop\"}");
        } else if (in(mx, my, BTN_NEXT) && (!end || SeotdaState.balance >= SeotdaState.ante)) {
            CasinoNet.seotda("{\"action\":\"seotda_next\"}");
        } else if (end && !in(mx, my, 90, BAR, 1420, 100)) {
            openModal(Modal.END);
        }
        return true;
    }

    private boolean clickWait(double mx, double my) {
        if (SeotdaState.isHost) {
            for (int seat = 1; seat <= 2; seat++) {
                SeotdaState.Seat st = SeotdaState.seats[seat];
                if (!st.empty && !st.host && in(mx, my, seat == 1 ? SEAT1 : SEAT2)) {
                    kickSeat = seat;
                    openModal(Modal.KICK);
                    return true;
                }
            }
            if (in(mx, my, BTN_SET)) {
                openForm(SeotdaState.roomTitle, SeotdaState.ante);
                openModal(Modal.SETTINGS);
            } else if (in(mx, my, BTN_DEL)) {
                openModal(Modal.DELETE);
            } else if (in(mx, my, BTN_START) && SeotdaState.allReady && SeotdaState.balance >= SeotdaState.ante) {
                CasinoNet.seotda("{\"action\":\"sdp_start\"}");
            }
            return true;
        }
        boolean ready = SeotdaState.seats[0].ready;
        if (in(mx, my, BTN_READY) && (ready || SeotdaState.balance >= SeotdaState.ante)) {
            CasinoNet.seotda("{\"action\":\"sdp_ready\",\"on\":" + !ready + "}");
        } else if (in(mx, my, BTN_LEAVE)) {
            openModal(Modal.EXIT);
        }
        return true;
    }

    private long clampRaise(long v) {
        return Math.max(SeotdaState.minRaise, Math.min(Math.max(SeotdaState.minRaise, SeotdaState.maxRaise), v));
    }

    @Override
    public boolean mouseDragged(double mx, double my, int button, double dx, double dy) {
        if (dragging && modal == Modal.BET) {
            layout();
            setRaiseFromMouse(mx);
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
        if (modal == Modal.BET) {
            long step = Math.max(100, SeotdaState.ante / 2);
            raise = clampRaise(raise + (v > 0 ? step : -step));
        }
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
                    CasinoNet.seotda(formJson("sdp_settings"));
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
        if (!leaving && !switching) CasinoNet.seotda("{\"action\":\"seotda_close\"}");
        switching = false;
    }

    // ------------------------------------------------------------------ 도우미

    private static String str(JsonObject o, String k) {
        return o != null && o.has(k) ? o.get(k).getAsString() : "";
    }

    private static long num(JsonObject o, String k) {
        return o != null && o.has(k) ? o.get(k).getAsLong() : 0;
    }
}
