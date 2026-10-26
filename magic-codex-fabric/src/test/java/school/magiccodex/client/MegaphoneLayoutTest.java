package school.magiccodex.client;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import school.magiccodex.client.MegaphoneLayout.Rect;

/** 확성기 (메모 #102) UI v2: MegaphoneLayout 값이 납품 layout.json 과 같은지, 배율·타이머·한 줄 맞추기. */
class MegaphoneLayoutTest {
    private static JsonObject layout() throws Exception {
        Path p = Path.of("").toAbsolutePath();
        for (int i = 0; i < 4 && p != null; i++, p = p.getParent()) {
            Path f = p.resolve("docs/design/megaphone-ui-v2/layout.json");
            if (Files.isRegularFile(f)) return JsonParser.parseString(Files.readString(f, StandardCharsets.UTF_8)).getAsJsonObject();
        }
        fail("docs/design/megaphone-ui-v2/layout.json 을 찾지 못함");
        return null;
    }

    private static void same(JsonArray a, Rect r, String what) {
        assertEquals(4, a.size(), what);
        assertEquals(a.get(0).getAsFloat(), r.x(), what + " x");
        assertEquals(a.get(1).getAsFloat(), r.y(), what + " y");
        assertEquals(a.get(2).getAsFloat(), r.w(), what + " w");
        assertEquals(a.get(3).getAsFloat(), r.h(), what + " h");
    }

    private static void corner(JsonArray a, int corner, String what) {
        for (int i = 0; i < 4; i++) assertEquals(corner, a.get(i).getAsInt(), what);
    }

    @Test void matchesDeliveredLayoutJson() throws Exception {
        var j = layout();
        var canvas = j.getAsJsonObject("canvas");
        assertEquals(MegaphoneLayout.DESIGN_W, canvas.get("width").getAsFloat());
        assertEquals(MegaphoneLayout.DESIGN_H, canvas.get("height").getAsFloat());
        assertFalse(canvas.get("fullscreenDim").getAsBoolean());
        assertEquals(MegaphoneLayout.TEXT_COLOR & 0xFFFFFF, Integer.parseInt(j.getAsJsonObject("font").get("color").getAsString().substring(1), 16));

        var in = j.getAsJsonObject("input");
        same(in.getAsJsonArray("rect"), MegaphoneLayout.INPUT, "input");
        corner(in.getAsJsonArray("nineSlice"), MegaphoneLayout.INPUT_CORNER, "input 9-slice");
        var title = in.getAsJsonObject("title");
        assertEquals(MegaphoneLayout.TITLE_TEXT, title.get("text").getAsString());
        same(title.getAsJsonArray("rect"), MegaphoneLayout.TITLE, "title");
        assertEquals(MegaphoneLayout.TITLE_SIZE, title.get("size").getAsFloat());
        same(in.getAsJsonObject("close").getAsJsonArray("rect"), MegaphoneLayout.CLOSE_RECT, "close");
        var field = in.getAsJsonObject("field");
        same(field.getAsJsonArray("rect"), MegaphoneLayout.FIELD, "field");
        corner(field.getAsJsonArray("nineSlice"), MegaphoneLayout.FIELD_CORNER, "field 9-slice");
        same(field.getAsJsonArray("textRect"), MegaphoneLayout.FIELD_TEXT, "field text");
        assertEquals(MegaphoneLayout.FIELD_TEXT_SIZE, field.get("fontSize").getAsFloat());
        assertEquals(MegaphoneLayout.MAX_CODE_POINTS, field.get("maxUnicodeCodePoints").getAsInt());
        var send = in.getAsJsonObject("send");
        same(send.getAsJsonArray("rect"), MegaphoneLayout.SEND, "send");
        corner(send.getAsJsonArray("nineSlice"), MegaphoneLayout.SEND_CORNER, "send 9-slice");
        assertEquals(MegaphoneLayout.SEND_TEXT, send.get("text").getAsString());
        assertEquals(MegaphoneLayout.SEND_TEXT_SIZE, send.get("fontSize").getAsFloat());

        var n = j.getAsJsonObject("notice");
        same(n.getAsJsonArray("rect"), MegaphoneLayout.CARD, "card");
        corner(n.getAsJsonArray("nineSlice"), MegaphoneLayout.CARD_CORNER, "card 9-slice");
        same(n.getAsJsonObject("nickname").getAsJsonArray("rect"), MegaphoneLayout.NICKNAME, "nickname");
        assertEquals(MegaphoneLayout.NICKNAME_SIZE, n.getAsJsonObject("nickname").get("size").getAsFloat());
        same(n.getAsJsonObject("message").getAsJsonArray("rect"), MegaphoneLayout.MESSAGE, "message");
        assertEquals(MegaphoneLayout.MESSAGE_SIZE, n.getAsJsonObject("message").get("size").getAsFloat());
        assertEquals(MegaphoneLayout.DURATION_MS, n.get("durationMs").getAsLong());
        same(n.getAsJsonObject("timer").getAsJsonArray("rect"), MegaphoneLayout.TIMER, "timer");
        var item = n.getAsJsonObject("item");
        same(item.getAsJsonArray("backingRect"), MegaphoneLayout.ITEM_BACKING_RECT, "item backing");
        corner(item.getAsJsonArray("backingNineSlice"), MegaphoneLayout.ITEM_BACKING_CORNER, "item backing 9-slice");
        same(item.getAsJsonArray("iconRect"), MegaphoneLayout.ICON, "icon");
        assertEquals(MegaphoneLayout.CARD_GAP, item.get("cardGap").getAsFloat());
        var hint = item.getAsJsonObject("hint");
        assertEquals("Ctrl" + MegaphoneLayout.HINT_SUFFIX, hint.get("text").getAsString());
        same(hint.getAsJsonArray("rect"), MegaphoneLayout.HINT, "hint");
        assertEquals(MegaphoneLayout.HINT_SIZE, hint.get("size").getAsFloat());
        var tip = n.getAsJsonObject("tooltip");
        assertEquals(MegaphoneLayout.TOOLTIP_X, tip.getAsJsonArray("anchor").get(0).getAsFloat());
        assertEquals(MegaphoneLayout.TOOLTIP_Y, tip.getAsJsonArray("anchor").get(1).getAsFloat());
        assertEquals(MegaphoneLayout.TOOLTIP_MARGIN, tip.get("clampMargin").getAsFloat());
    }

    @Test void itemFloatsAboveCardWithGapAndTooltipBelowCard() {
        assertEquals(MegaphoneLayout.CARD.y() - MegaphoneLayout.CARD_GAP, MegaphoneLayout.ITEM_BACKING_RECT.bottom());
        assertEquals(MegaphoneLayout.ITEM_BACKING_RECT.centerX(), MegaphoneLayout.CARD.centerX());
        assertEquals(MegaphoneLayout.ICON.centerX(), MegaphoneLayout.ITEM_BACKING_RECT.centerX());
        assertEquals(MegaphoneLayout.ICON.centerY(), MegaphoneLayout.ITEM_BACKING_RECT.centerY());
        assertTrue(MegaphoneLayout.TOOLTIP_Y > MegaphoneLayout.CARD.bottom());
        // 타이머는 카드 안 아래
        assertTrue(MegaphoneLayout.TIMER.bottom() <= MegaphoneLayout.CARD.bottom() && MegaphoneLayout.TIMER.x() > MegaphoneLayout.CARD.x());
        // 글자 잉크 가운데: rect 위쪽 + 0.48 × 크기 (납품 미리보기에서 잰 값), 글자 영역 안
        assertEquals(177 + 26 * 0.48f, MegaphoneLayout.textY(MegaphoneLayout.NICKNAME, MegaphoneLayout.NICKNAME_SIZE), 1e-4);
        for (var r : new Rect[]{MegaphoneLayout.TITLE, MegaphoneLayout.FIELD_TEXT, MegaphoneLayout.NICKNAME, MegaphoneLayout.MESSAGE, MegaphoneLayout.HINT})
            assertTrue(MegaphoneLayout.textY(r, 26) > r.y() && MegaphoneLayout.textY(r, 18) < r.bottom());
    }

    @Test void canvasIsUniformAndCentered() {
        var f = MegaphoneLayout.canvas(960, 540);       // 16:9, GUI 배율 2
        assertEquals(0.5f, f.scale(), 1e-6);
        assertEquals(0, f.x(), 1e-4); assertEquals(0, f.y(), 1e-4);
        var wide = MegaphoneLayout.canvas(1280, 540);   // 더 넓은 화면: 세로 기준, 가로 가운데
        assertEquals(0.5f, wide.scale(), 1e-6);
        assertEquals((1280 - 960) / 2f, wide.x(), 1e-4);
        var tall = MegaphoneLayout.canvas(800, 600);    // 4:3: 가로 기준, 세로 가운데
        assertEquals(800 / 1920f, tall.scale(), 1e-6);
        assertEquals((600 - 1080 * 800 / 1920f) / 2f, tall.y(), 1e-3);
    }

    @Test void timerUsesElapsedTime() {
        assertEquals(1f, MegaphoneLayout.remaining(0, 6000));
        assertEquals(0.5f, MegaphoneLayout.remaining(3000, 6000), 1e-6);
        assertEquals(0f, MegaphoneLayout.remaining(7000, 6000));
        assertEquals(1f, MegaphoneLayout.remaining(-50, 6000));
        assertEquals(592, MegaphoneLayout.timerWidth(0, 6000));
        assertEquals(296, MegaphoneLayout.timerWidth(3000, 6000));
        assertEquals(0, MegaphoneLayout.timerWidth(6000, 6000));
    }

    /** 글자 폭 = 글자 수 × 크기 (시험용). */
    private static double measure(String s, float size) { return s.codePointCount(0, s.length()) * size; }

    @Test void fitShrinksThenEllipsizes() {
        var shortLine = MegaphoneLayout.fit("오늘 저녁 광장에서 만나요!", 24, 18, 592, MegaphoneLayoutTest::measure);
        assertEquals("오늘 저녁 광장에서 만나요!", shortLine.text());
        assertEquals(24f, shortLine.size());
        // 30자 × 24 = 720 > 592 → 줄여서 들어감 (≥ 18)
        String thirty = "가".repeat(30);
        var shrunk = MegaphoneLayout.fit(thirty, 24, 18, 592, MegaphoneLayoutTest::measure);
        assertEquals(thirty, shrunk.text());
        assertTrue(shrunk.size() < 24 && shrunk.size() >= 18 && measure(thirty, shrunk.size()) <= 592);
        // 80자: 18 에서도 넘침 → 말줄임, 폭 안
        String eighty = "나".repeat(80);
        var cut = MegaphoneLayout.fit(eighty, 24, 18, 592, MegaphoneLayoutTest::measure);
        assertEquals(18f, cut.size());
        assertTrue(cut.text().endsWith(MegaphoneLayout.ELLIPSIS));
        assertTrue(measure(cut.text(), 18) <= 592);
        assertEquals(592 / 18 - 1, cut.text().codePointCount(0, cut.text().length()) - 1);
        // 이모지(서로게이트 쌍)를 반으로 자르지 않는다
        var emoji = MegaphoneLayout.fit("😀".repeat(80), 24, 18, 592, MegaphoneLayoutTest::measure);
        assertEquals(emoji.text().codePointCount(0, emoji.text().length()) * 2 - 1, emoji.text().length());
    }

    @Test void inputIsOneLineAndCodePointLimited() {
        assertEquals("ab", MegaphoneLayout.sanitizeInput("a\r\nb", 80));
        assertEquals(80, MegaphoneLayout.sanitizeInput("가".repeat(100), 80).length());
        String e = MegaphoneLayout.sanitizeInput("😀".repeat(100), 80);
        assertEquals(80, e.codePointCount(0, e.length()));
        assertEquals(160, e.length());
        assertEquals("", MegaphoneLayout.sanitizeInput(null, 80));
    }
}
