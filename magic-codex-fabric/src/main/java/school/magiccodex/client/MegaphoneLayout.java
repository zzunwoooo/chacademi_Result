package school.magiccodex.client;

import java.util.function.ToDoubleBiFunction;
import net.minecraft.util.Identifier;

/**
 * 확성기 (메모 #102) 화면 배치·텍스처를 한곳에 모은 것. 오너 납품 UI v2 패키지 그대로:
 * {@code docs/design/megaphone-ui-v2/} (layout.json · docs/개발_프롬프트.txt). 값이 layout.json 과 같은지는 MegaphoneLayoutTest 가 본다.
 *
 * <p>좌표: 기준 1920×1080, 좌상단 원점, 단위 px. 화면에는 min(가로/1920, 세로/1080) 한 배율로 전체를 줄이고 가운데 정렬한다
 * (코너·글자도 같은 배율). 글자 rect 의 y 는 글자 영역 위쪽 기준 (그릴 때 세로 가운데 = y + h/2).
 * 9-slice 코너: 입력 패널·공지 카드 16px, 입력 필드·보내기 버튼·아이템 배경 12px. 닫기 24×24 고정.
 * 순수 계산(배율·타이머·한 줄 맞추기)도 여기 둔다 — 마인크래프트 없이 시험된다.
 */
final class MegaphoneLayout {
    private MegaphoneLayout() {}

    // ------------------------------------------------------------------ 기준 캔버스
    static final float DESIGN_W = 1920, DESIGN_H = 1080;

    /** 글자 굵기: 400 = Pretendard Regular, 500 = Medium, 600·700 = Bold (저장소의 Pretendard 3종). */
    static final Identifier REGULAR = Identifier.of("magiccodex", "body"), MEDIUM = Identifier.of("magiccodex", "label"),
            BOLD = Identifier.of("magiccodex", "hud_bold");
    /** layout.json font.color #F2F8FF. */
    static final int TEXT_COLOR = 0xFFF2F8FF;
    /**
     * 글자 rect 의 y 는 글자 영역 위쪽. 게임 글자(CodexTypography)는 잉크 가운데를 y 에 맞추므로, 납품 미리보기
     * (previews/*.png) 에서 잰 대로 잉크 가운데 = rect.y + 0.48 × 글자 크기 (제목·입력·닉네임·메시지·힌트 모두 같음).
     */
    static final float TEXT_CENTER = 0.48f;
    static float textY(Rect r, float size) { return r.y() + size * TEXT_CENTER; }

    // ------------------------------------------------------------------ 자산 (납품 PNG 9개, 글자 없음)
    static final String DIR = "magiccodex:textures/gui/megaphone/";
    static final Identifier INPUT_PANEL = Identifier.of("magiccodex", "textures/gui/megaphone/megaphone_input_panel.png"),
            INPUT_FIELD = Identifier.of("magiccodex", "textures/gui/megaphone/megaphone_input_field.png"),
            SEND_NORMAL = Identifier.of("magiccodex", "textures/gui/megaphone/megaphone_send_normal.png"),
            SEND_HOVER = Identifier.of("magiccodex", "textures/gui/megaphone/megaphone_send_hover.png"),
            CLOSE = Identifier.of("magiccodex", "textures/gui/megaphone/megaphone_close.png"),
            NOTICE_CARD = Identifier.of("magiccodex", "textures/gui/megaphone/megaphone_notice_card.png"),
            ITEM_BACKING = Identifier.of("magiccodex", "textures/gui/megaphone/megaphone_item_backing.png");
    /** 타이머는 잘라 그리기(drawPart, 문자열 경로)라 경로 문자열. */
    static final String TIMER_FILL = DIR + "megaphone_timer_fill.png", TIMER_TRACK = DIR + "megaphone_timer_track.png";

    /** x, y, w, h. */
    record Rect(float x, float y, float w, float h) {
        float right() { return x + w; }
        float bottom() { return y + h; }
        float centerX() { return x + w / 2f; }
        float centerY() { return y + h / 2f; }
        boolean contains(double px, double py) { return px >= x && px < x + w && py >= y && py < y + h; }
    }

    // ------------------------------------------------------------------ 입력 창 (layout.json input)
    static final Rect INPUT = new Rect(455, 435, 1010, 210);
    static final int INPUT_CORNER = 16;
    static final Rect TITLE = new Rect(487, 456, 840, 40);
    static final String TITLE_TEXT = "확성기";
    static final float TITLE_SIZE = 30;
    static final Identifier TITLE_FACE = BOLD;           // weight 600
    static final Rect CLOSE_RECT = new Rect(1409, 456, 24, 24);
    static final Rect FIELD = new Rect(487, 516, 786, 72);
    static final int FIELD_CORNER = 12;
    static final Rect FIELD_TEXT = new Rect(507, 534, 746, 36);
    static final float FIELD_TEXT_SIZE = 26;
    static final Identifier FIELD_FACE = REGULAR;
    static final Rect SEND = new Rect(1289, 516, 144, 72);
    static final int SEND_CORNER = 12;
    static final String SEND_TEXT = "보내기";
    static final float SEND_TEXT_SIZE = 26;
    static final Identifier SEND_FACE = MEDIUM;
    /** 최대 글자 (Unicode code point). 서버 OPEN 의 maxChars 가 우선. */
    static final int MAX_CODE_POINTS = 80;

    // ------------------------------------------------------------------ 화면 위 공지 (layout.json notice)
    static final Rect CARD = new Rect(640, 160, 640, 110);
    static final int CARD_CORNER = 16;
    static final Rect NICKNAME = new Rect(664, 177, 592, 32);
    static final float NICKNAME_SIZE = 26;
    static final Identifier NICKNAME_FACE = BOLD;        // weight 700
    static final Rect MESSAGE = new Rect(664, 215, 592, 30);
    static final float MESSAGE_SIZE = 24;
    static final Identifier MESSAGE_FACE = REGULAR;      // weight 400
    /** 한 줄에 안 들어가면 이 크기까지 줄이고(가독성 하한), 그래도 넘치면 말줄임. */
    static final float MESSAGE_MIN_SIZE = 18, NICKNAME_MIN_SIZE = 20;
    static final String ELLIPSIS = "…";
    static final long DURATION_MS = 6000;
    static final Rect TIMER = new Rect(664, 257, 592, 3);
    static final Rect ITEM_BACKING_RECT = new Rect(912, 48, 96, 96);
    static final int ITEM_BACKING_CORNER = 12;
    static final Rect ICON = new Rect(920, 56, 80, 80);
    static final float CARD_GAP = 16;
    static final String HINT_SUFFIX = " 아이템 보기";      // 앞은 "마우스 포인터" 키 이름 (기본 Ctrl)
    static final Rect HINT = new Rect(1024, 90, 220, 25);
    static final float HINT_SIZE = 18;
    static final Identifier HINT_FACE = REGULAR;
    /** 툴팁: 카드 아래 (960, 282) 를 위쪽 가운데로, 화면 가장자리에서 16px 안쪽으로 보정. */
    static final float TOOLTIP_X = 960, TOOLTIP_Y = 282, TOOLTIP_MARGIN = 16;

    // ------------------------------------------------------------------ 순수 계산

    /** 화면(GUI 좌표) w×h 에 기준 캔버스를 한 배율로 맞춘 원점·배율. */
    static CodexLayout canvas(float width, float height) {
        float s = Math.min(width / DESIGN_W, height / DESIGN_H);
        return new CodexLayout((width - DESIGN_W * s) / 2f, (height - DESIGN_H * s) / 2f, s);
    }

    /** 남은 비율 = clamp(1 - 경과/표시 시간, 0, 1). 프레임 수가 아니라 시각 차이로만. */
    static float remaining(long elapsedMs, long durationMs) {
        if (durationMs <= 0) return 0;
        return Math.clamp(1f - elapsedMs / (float) durationMs, 0f, 1f);
    }

    /** 타이머 채움 폭 (기준 px, 반올림). 늘이지 않고 왼쪽부터 잘라 그린다. */
    static int timerWidth(long elapsedMs, long durationMs) { return Math.round(TIMER.w() * remaining(elapsedMs, durationMs)); }

    /** 한 줄 맞추기 결과: 그릴 글자와 크기. */
    record Line(String text, float size) {}

    /**
     * 한 줄 맞추기: 크기 size 로 maxWidth 에 들어가면 그대로, 아니면 minSize 까지 줄이고, 그래도 넘치면 minSize 에서
     * 뒤를 잘라 "…" 를 붙인다 (code point 단위, 원문은 건드리지 않음). measure = (글자, 크기) → 폭.
     */
    static Line fit(String text, float size, float minSize, float maxWidth, ToDoubleBiFunction<String, Float> measure) {
        String value = text == null ? "" : text;
        if (value.isEmpty()) return new Line("", size);
        double w = measure.applyAsDouble(value, size);
        if (w <= maxWidth) return new Line(value, size);
        float shrunk = Math.max(minSize, (float) Math.floor(size * maxWidth / w * 10) / 10f);
        if (measure.applyAsDouble(value, shrunk) <= maxWidth) return new Line(value, shrunk);
        int[] cps = value.codePoints().toArray();
        int lo = 0, hi = cps.length;   // 앞에서 몇 글자까지 + "…" 가 들어가는지 (이진 탐색)
        while (lo < hi) {
            int mid = (lo + hi + 1) >>> 1;
            if (measure.applyAsDouble(new String(cps, 0, mid).stripTrailing() + ELLIPSIS, minSize) <= maxWidth) lo = mid; else hi = mid - 1;
        }
        return new Line(new String(cps, 0, lo).stripTrailing() + ELLIPSIS, minSize);
    }

    /** 앞에서 max 개 code point 까지 (넘으면 자른다). */
    static String cutCodePoints(String value, int max) {
        if (value == null) return "";
        if (value.codePointCount(0, value.length()) <= max) return value;
        return value.substring(0, value.offsetByCodePoints(0, Math.max(0, max)));
    }

    /** 입력값 정리: 줄바꿈(CR LF)과 제어 문자 제거 후 max code point. */
    static String sanitizeInput(String value, int max) {
        if (value == null) return "";
        var out = new StringBuilder(value.length());
        value.codePoints().filter(cp -> cp != '\r' && cp != '\n' && !Character.isISOControl(cp)).forEach(out::appendCodePoint);
        return cutCodePoints(out.toString(), max);
    }
}
