package kr.kkaegul.client.ui;

import com.google.gson.JsonObject;

/**
 * 한글 닉네임 설정창. 글자 입력은 게임이 넘겨주는 문자(한글 IME 조합 완료 글자 포함)를 그대로 받는다.
 * 저장 → nickname.save name=..  / 입력이 멈추면 nickname.check 로 중복·규칙 미리 확인.
 * 첫 접속(first=true)이면 ESC/취소로 닫을 수 없다.
 *
 * 전용 그림(textures/ui/nickname/*.png)이 있으면 그걸 쓰고, 없으면 기존 메뉴/유저선택 그림으로 대신 그린다.
 */
public final class NicknamePanel implements Panel {
    private final Host host;
    private JsonObject data;
    private String text;
    private int caret;
    private String error = "", okMsg = "";
    private long lastEdit, lastCheckSent, opened, savingSince, caretBlink;
    private String checked;
    private record R(String key, float x, float y, float w, float h) {}
    private final java.util.List<R> hits = new java.util.ArrayList<>();

    public NicknamePanel(Host host, JsonObject data, long now) {
        this.host = host;
        this.data = data == null ? new JsonObject() : data;
        this.text = J.str(this.data, "nickname", "");
        this.caret = text.length();
        this.opened = now;
    }

    @Override public String screen() { return "nickname"; }

    private boolean first() { return J.bool(data, "first"); }
    private int min() { return (int) J.num(data, "min", 2); }
    private int max() { return (int) J.num(data, "max", 8); }

    @Override public void update(JsonObject d, long now) {
        if (d == null) return;
        data = J.merge(data, d);
        if (d.has("error") || d.has("ok")) {
            savingSince = 0;
            String err = J.str(d, "error", "");
            String ck = J.str(d, "checked", null);
            if (ck == null || ck.equals(text)) {
                error = err;
                okMsg = err.isEmpty() && ck != null ? "사용할 수 있는 이름이에요" : "";
                if (!err.isEmpty()) host.sound("minecraft:block.note_block.bass", .7f, .6f);
            }
        }
    }

    @Override public boolean closable() { return !first(); }

    /** 화면에서 바로 막을 수 있는 규칙 */
    private String localCheck() {
        int len = text.codePointCount(0, text.length());
        if (len == 0) return "";
        if (len < min() || len > max()) return min() + "~" + max() + "글자로 정해 주세요";
        for (int i = 0; i < text.length(); i++) {
            char ch = text.charAt(i);
            boolean ok = (ch >= '가' && ch <= '힣') || (ch >= 'a' && ch <= 'z') || (ch >= 'A' && ch <= 'Z') || (ch >= '0' && ch <= '9') || ch == '_';
            if (!ok) return (ch >= 'ㄱ' && ch <= 'ㅣ') ? "자음·모음만 따로 쓸 수 없어요" : "한글·영문·숫자·_ 만 쓸 수 있어요";
        }
        return null;
    }

    private static boolean has(Canvas c, String id) { return c.size(id) != null; }

    @Override public void render(Canvas c, long now, float mx, float my) {
        hits.clear();
        float W = c.width(), H = c.height();
        c.fill(0, 0, W, H, 0, first() ? 0xB0000000 : 0x88000000);
        // 잠깐 멈추면 서버에 중복 확인
        String local = localCheck();
        if (local == null && lastEdit > 0 && now - lastEdit > 450 && !text.equals(checked)) {
            checked = text;
            lastCheckSent = now;
            host.send("nickname.check", "name", text);
        }

        float pw = Math.min(900, W - 120), ph = pw * 560 / 900f;
        float px = (W - pw) / 2, py = (H - ph) / 2 + 30;
        float s = .92f + .08f * Anim.outBack(Anim.t(now, opened, 280));
        c.push();
        c.translate(W / 2, py + ph / 2);
        c.scale(s, s);
        c.translate(-W / 2, -(py + ph / 2));
        boolean art = has(c, "nickname/nickname_panel");
        if (art) c.image("nickname/nickname_panel", px, py, pw, ph, 0xFFFFFFFF);
        else {
            c.image("menu/menu_frame", px, py, pw, ph, 0xFFFFFFFF);
            c.fill(px + pw * .04f, py + ph * .06f, pw * .92f, ph * .88f, 30, 0xFFFFF8EA);
            c.imageFit("menu/frog_hanging", px + pw / 2, py - 40 + (float) Math.sin(now / 700.0) * 4, 160, 0xFFFFFFFF);
        }
        c.text(first() ? "이름을 정해 주세요!" : "닉네임 바꾸기", px + pw / 2, py + ph * .14f, 38, Theme.INK, Canvas.CENTER, Canvas.BOLD | Canvas.LIGHT);
        c.text(first() ? "운빨깨굴랜드에서 불릴 이름이에요" : "계정 " + J.str(data, "account"), px + pw / 2, py + ph * .22f, 20, Theme.MUTED, Canvas.CENTER, Canvas.BOLD | Canvas.LIGHT);

        // 이름표 미리보기
        String title = J.str(data, "title");
        String preview = (title.isEmpty() ? "" : title + " ") + "§f" + (text.isEmpty() ? "닉네임" : text);
        float nw = Math.max(260, c.textWidth(preview, 28, Canvas.BOLD) + 60), nh = 52, ny = py + ph * .36f;
        c.fill(px + pw / 2 - nw / 2, ny - nh / 2, nw, nh, 12, 0x88000000);
        c.text(preview, px + pw / 2, ny, 28, text.isEmpty() ? 0x88FFFFFF : Theme.WHITE, Canvas.CENTER, Canvas.BOLD);
        c.text("머리 위에 이렇게 보여요", px + pw / 2, ny + 42, 16, Theme.MUTED, Canvas.CENTER, Canvas.LIGHT);

        // 입력칸
        float iw = pw * .7f, ih = 78, ix = px + (pw - iw) / 2, iy = py + ph * .53f;
        if (has(c, "nickname/input")) c.image("nickname/input_focus", ix, iy, iw, ih, 0xFFFFFFFF);
        else {
            c.fill(ix, iy, iw, ih, ih / 2, 0xFF8DBF63);
            c.fill(ix + 4, iy + 4, iw - 8, ih - 8, ih / 2 - 4, 0xFFFFFFFF);
        }
        String shown = text.isEmpty() ? "§7" + min() + "~" + max() + "글자" : text;
        c.text(shown, ix + iw / 2, iy + ih / 2, 34, Theme.INK, Canvas.CENTER, Canvas.BOLD | Canvas.LIGHT);
        if ((now - caretBlink) / 530 % 2 == 0) {
            float tw = text.isEmpty() ? 0 : c.textWidth(text, 34, Canvas.BOLD);
            float before = c.textWidth(text.substring(0, Math.min(caret, text.length())), 34, Canvas.BOLD);
            float cxp = ix + iw / 2 - tw / 2 + before + 2;
            c.fill(cxp, iy + ih * .24f, 3, ih * .52f, 1, 0xFF5BA346);
        }
        int len = text.codePointCount(0, text.length());
        c.text(len + "/" + max(), ix + iw - 28, iy + ih / 2, 18, len > max() ? Theme.RED : Theme.MUTED, Canvas.RIGHT, Canvas.BOLD | Canvas.LIGHT);
        hits.add(new R("input", ix, iy, iw, ih));

        // 안내 / 오류
        String msg;
        int col;
        if (local != null && !local.isEmpty()) { msg = local; col = Theme.RED; }
        else if (!error.isEmpty()) { msg = error; col = Theme.RED; }
        else if (!okMsg.isEmpty() && text.equals(checked)) { msg = okMsg; col = Theme.GREEN_DARK; }
        else { msg = J.str(data, "rule", "한글·영문·숫자 " + min() + "~" + max() + "글자"); col = Theme.MUTED; }
        if (col == Theme.RED) c.imageFit(has(c, "nickname/warn") ? "nickname/warn" : "notice/face_failure", px + pw / 2 - c.textWidth(msg, 20, Canvas.BOLD) / 2 - 22, iy + ih + 32, 30, 0xFFFFFFFF);
        c.text(msg, px + pw / 2, iy + ih + 32, 20, col, Canvas.CENTER, Canvas.BOLD | Canvas.LIGHT);

        // 버튼
        boolean canSave = local == null && savingSince == 0;
        float bw = 240, bh = 72, by = py + ph * .8f;
        float okX = first() ? px + pw / 2 - bw / 2 : px + pw / 2 + 16, noX = px + pw / 2 - 16 - bw;
        boolean hOk = canSave && Anim.in(mx, my, okX, by, bw, bh);
        if (has(c, "nickname/btn_ok")) c.image(hOk ? "nickname/btn_ok_hover" : "nickname/btn_ok", okX, by, bw, bh, canSave ? 0xFFFFFFFF : 0x90FFFFFF);
        else c.image(canSave ? (hOk ? "user_select/move_pressed" : "user_select/move_normal") : "user_select/move_disabled", okX, by, bw, bh, 0xFFFFFFFF);
        c.text(savingSince > 0 ? "확인 중..." : "정했어요!", okX + bw / 2, by + bh / 2, 26, canSave ? Theme.WHITE : 0xFFB8C4CC, Canvas.CENTER, Canvas.BOLD | (canSave ? Canvas.OUTLINE : 0));
        hits.add(new R("ok", okX, by, bw, bh));
        if (!first()) {
            boolean hNo = Anim.in(mx, my, noX, by, bw, bh);
            if (has(c, "nickname/btn_cancel")) c.image(hNo ? "nickname/btn_cancel_hover" : "nickname/btn_cancel", noX, by, bw, bh, 0xFFFFFFFF);
            else c.image(hNo ? "user_select/cancel_pressed" : "user_select/cancel_normal", noX, by, bw, bh, 0xFFFFFFFF);
            c.text("취소", noX + bw / 2, by + bh / 2, 26, Theme.INK, Canvas.CENTER, Canvas.BOLD | Canvas.LIGHT);
            hits.add(new R("cancel", noX, by, bw, bh));
        }
        c.pop();
    }

    private void save(long now) {
        if (localCheck() != null || savingSince > 0) {
            host.sound("minecraft:block.note_block.bass", .7f, .6f);
            return;
        }
        savingSince = now;
        error = "";
        host.send("nickname.save", "name", text);
        host.sound("minecraft:block.note_block.pling", 1.3f, .6f);
    }

    @Override public boolean click(float mx, float my, long now) {
        if (savingSince > 0 && now - savingSince > 5000) savingSince = 0;
        for (R r : hits) {
            if (!Anim.in(mx, my, r.x(), r.y(), r.w(), r.h())) continue;
            switch (r.key()) {
                case "ok" -> save(now);
                case "cancel" -> host.closePanel(this);
                default -> { }
            }
            return true;
        }
        return false;
    }

    private void edited(long now) {
        lastEdit = now;
        caretBlink = now;
        error = "";
        okMsg = "";
    }

    @Override public boolean typed(char ch, long now) {
        if (Character.isISOControl(ch) || ch == ' ' || ch == '§' || ch == '&') return true;
        if (text.codePointCount(0, text.length()) >= max() + 2) return true;
        text = text.substring(0, caret) + ch + text.substring(caret);
        caret++;
        edited(now);
        return true;
    }

    @Override public boolean key(int key, int mods, long now) {
        if (savingSince > 0 && now - savingSince > 5000) savingSince = 0;
        switch (key) {
            case 259 -> { if (caret > 0) { text = text.substring(0, caret - 1) + text.substring(caret); caret--; edited(now); } return true; } // BACKSPACE
            case 261 -> { if (caret < text.length()) { text = text.substring(0, caret) + text.substring(caret + 1); edited(now); } return true; } // DELETE
            case 263 -> { caret = Math.max(0, caret - 1); caretBlink = now; return true; } // LEFT
            case 262 -> { caret = Math.min(text.length(), caret + 1); caretBlink = now; return true; } // RIGHT
            case 268 -> { caret = 0; return true; } // HOME
            case 269 -> { caret = text.length(); return true; } // END
            case 257, 335 -> { save(now); return true; } // ENTER
            default -> { return false; }
        }
    }

    /** 붙여넣기 */
    public void paste(String s, long now) {
        for (char ch : s.toCharArray()) typed(ch, now);
    }
}
