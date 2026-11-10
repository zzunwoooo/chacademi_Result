package kr.kkaegul.client.ui;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.List;

/**
 * 우편함 창 (받은 묶음 mailbox 좌표): 창 (560,160) 800×740 제목칸 (250,45) 300×55, 닫기 (1290,170) 60,
 * 줄 (610, 290+100i) 700×84 [아이템칸 (12,10) 64, 이름칸 (100,15) 450×54, 개수칸 (570,15) 100×54],
 * 모두 받기 (760,800) 400×70 글자칸 (25,15) 350×40, 스크롤 홈 (1320,290) 16×480
 */
public final class MailPanel implements Panel {
    private static final int TXT = Canvas.BOLD | Canvas.LIGHT;
    private static final int INK = 0xFF4A2D16;
    private final Host host;
    private JsonObject data;
    private final long opened;
    private float k = 1, ox, oy, scroll, shown;
    private int selected = -1;
    private long pressedAt;

    public MailPanel(Host host, JsonObject data, long now) {
        this.host = host;
        this.data = data == null ? new JsonObject() : data;
        this.opened = now;
    }

    @Override public String screen() { return "mail"; }

    @Override public void update(JsonObject d, long now) { if (d != null) data = d.has("items") ? d : J.merge(data, d); }

    private List<JsonObject> items() {
        List<JsonObject> out = new ArrayList<>();
        if (data.has("items") && data.get("items").isJsonArray())
            for (JsonElement e : data.getAsJsonArray("items")) if (e.isJsonObject()) out.add(e.getAsJsonObject());
        return out;
    }

    // 줄은 제목 리본 판(아래 끝 y 300) 밑에서 시작: 예전 290 은 리본 아래 금테를 덮었음
    private static final float TOP = 306, STEP = 96, VIEW_BOTTOM = 780;

    private float maxScroll() { return Math.max(0, items().size() * STEP - 12 - (VIEW_BOTTOM - 6 - TOP)); }

    @Override public void render(Canvas c, long now, float mx, float my) {
        float W = c.width(), H = c.height();
        c.fill(0, 0, W, H, 0, 0x66000000);
        k = Math.min(W / 1920f, H / 1080f);
        float rise = (1 - Anim.outCubic(Anim.t(now, opened, 260))) * 30;
        ox = (W - 1920 * k) / 2;
        oy = (H - 1080 * k) / 2 + rise;
        c.push();
        c.translate(ox, oy);
        c.scale(k, k);
        float lx = (mx - ox) / k, ly = (my - oy) / k;
        List<JsonObject> it = items();
        c.image("mail/mail_panel", 560, 160, 800, 740, 0xFFFFFFFF);
        c.textFit("우편함", 960, 160 + 99.75f, 36, 280, INK, Canvas.CENTER, TXT);   // 리본 판 안쪽 133~266px (재서 맞춤)
        boolean hc = Anim.in(lx, ly, 1290, 170, 60, 60);
        c.image("mail/mail_close", 1290, 170 - (hc ? 2 : 0), 60, 60, 0xFFFFFFFF);

        scroll = Math.max(0, Math.min(scroll, maxScroll()));
        shown += (scroll - shown) * .25f;
        if (Math.abs(scroll - shown) < .3f) shown = scroll;
        c.clip(600, TOP - 6, 716, VIEW_BOTTOM - TOP + 6);
        for (int i = 0; i < it.size(); i++) {
            float y = TOP + STEP * i - shown;
            if (y + 84 < TOP - 10 || y > 790) continue;
            JsonObject o = it.get(i);
            boolean sel = i == selected || Anim.in(lx, ly, 610, y, 700, 84) && ly > TOP - 6 && ly < VIEW_BOTTOM;
            c.image(sel ? "mail/mail_row_selected" : "mail/mail_row_normal", 610, y, 700, 84, 0xFFFFFFFF);
            String mat = J.str(o, "material").toLowerCase();
            // 아이템 칸 (v3 그림에서 잼): 69~190 × 60~142px → 가운데 (64.7, 50.5), 높이 41 → 아이템 36. 글자도 알약 몸통 가운데(50)에
            if (mat.isEmpty() || !c.item("minecraft:" + mat, 610 + 64.7f - 18, y + 50.5f - 18, 36)) c.imageFit("hud/diamond", 610 + 64.7f, y + 50.5f, 34, 0xFFFFFFFF);
            c.textFit(J.str(o, "name"), 610 + 112, y + 50, 26, 440, INK, Canvas.LEFT, TXT);
            c.textFit("×" + J.num(o, "amount", 1), 610 + 570 + 50, y + 50, 26, 100, INK, Canvas.CENTER, TXT);
        }
        if (it.isEmpty()) c.text("받을 우편이 없어요", 960, 520, 28, 0xFF8A7A68, Canvas.CENTER, TXT);
        c.unclip();
        // 스크롤
        if (maxScroll() > 0) {
            float th0 = VIEW_BOTTOM - 6 - TOP;
            c.image("mail/mail_scroll_track", 1320, TOP, 16, th0, 0xFFFFFFFF);
            float th = Math.max(60, th0 * th0 / (it.size() * STEP - 12));
            c.image("mail/mail_scroll_thumb", 1320, TOP + (th0 - th) * shown / maxScroll(), 16, th, 0xFFFFFFFF);
        }
        boolean can = !it.isEmpty();
        boolean hb = can && Anim.in(lx, ly, 760, 800, 400, 70);
        boolean pressed = can && now - pressedAt < 160;
        String btn = !can ? "mail_button_disabled" : pressed ? "mail_button_pressed" : hb ? "mail_button_hover" : "mail_button_normal";
        c.image("mail/" + btn, 760, 800, 400, 70, 0xFFFFFFFF);
        c.textFit("모두 받기" + (can ? " (" + it.size() + ")" : ""), 760 + 25 + 175, 800 + 15 + 20 + (pressed ? 2 : 0), 30, 350, can ? INK : 0xFF9A8A78, Canvas.CENTER, TXT);
        c.pop();
    }

    @Override public boolean scroll(float mx, float my, double amount) {
        scroll -= (float) amount * 60;
        return true;
    }

    @Override public boolean click(float mx, float my, long now) {
        float lx = (mx - ox) / k, ly = (my - oy) / k;
        if (Anim.in(lx, ly, 1290, 170, 60, 60)) { host.closePanel(this); return true; }
        if (!items().isEmpty() && Anim.in(lx, ly, 760, 800, 400, 70)) {
            pressedAt = now;
            host.send("mail.claim");
            host.sound("minecraft:entity.experience_orb.pickup", 1f, .5f);
            return true;
        }
        for (int i = 0; i < items().size(); i++) {
            float y = TOP + STEP * i - shown;
            if (ly > TOP - 6 && ly < VIEW_BOTTOM && Anim.in(lx, ly, 610, y, 700, 84)) { selected = i; return true; }
        }
        return false;
    }
}
