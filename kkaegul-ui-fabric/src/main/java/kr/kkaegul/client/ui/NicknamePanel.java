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
    /** 게임 안 한글 입력기 (윈도우 입력기는 이 창이 열려 있는 동안 꺼짐). 한/영: 오른쪽 Alt 또는 한/영 키 */
    private final HangulInput ime = new HangulInput();
    private boolean korean = true, pasting;
    private record R(String key, float x, float y, float w, float h) {}
    private final java.util.List<R> hits = new java.util.ArrayList<>();

    public NicknamePanel(Host host, JsonObject data, long now) {
        this.host = host;
        this.data = data == null ? new JsonObject() : data;
        this.text = J.str(this.data, "nickname", "");
        this.caret = text.length();
        this.opened = now;
    }

    @Override public boolean textInput() { return true; }

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
    /** 보이는 글자 = 확정 글자 + 조합 중인 글자 */
    private String full() { String cm = ime.composing(); return text.substring(0, caret) + cm + text.substring(caret); }

    /** 조합 중인 글자를 확정 */
    private void commit() {
        String cm = ime.flush();
        if (cm.isEmpty()) return;
        text = text.substring(0, caret) + cm + text.substring(caret);
        caret += cm.length();
    }

    private String localCheck() {
        String text = full();
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

    private float k = 1, ox, oy;

    /** 전용 그림(nickname/*) 배치 = GPT 시안 layout.json (패널 900x560 좌표) */
    @Override public void render(Canvas c, long now, float mx, float my) {
        hits.clear();
        float W = c.width(), H = c.height();
        c.fill(0, 0, W, H, 0, first() ? 0xB0000000 : 0x88000000);
        String local = localCheck();
        String text = full();
        if (local == null && lastEdit > 0 && now - lastEdit > 450 && !text.equals(checked)) {
            checked = text;
            lastCheckSent = now;
            host.send("nickname.check", "name", text);
        }
        float pop = .92f + .08f * Anim.outBack(Anim.t(now, opened, 280));
        k = Math.min(W * .7f / 900f, H * .78f / 560f) * pop;
        ox = W / 2 - 450 * k;
        oy = H / 2 - 280 * k;
        c.push();
        c.translate(ox, oy);
        c.scale(k, k);
        mx = (mx - ox) / k;
        my = (my - oy) / k;

        c.image("nickname/nickname_panel", 0, 0, 900, 560, 0xFFFFFFFF);
        // 리본: 제목 (리본 가운데 선을 따라 휘게: 가운데 y133.5, 양끝 x220/680 에서 13 내려감 → 반지름 약 2035)
        c.textArc(first() ? "이름을 정해 주세요!" : "닉네임 바꾸기", 450, 134, 40, 2035, 440, Theme.INK, Canvas.BOLD | Canvas.LIGHT);
        // 말풍선: 머리 위 이름표 미리보기 (칭호 + 닉네임). 말풍선 몸통 y200~268 → 가운데 234
        String title = J.str(data, "title");
        String preview = (title.isEmpty() ? "" : title + " ") + "§r" + (text.isEmpty() ? "§7닉네임" : text);
        c.textFit(preview, 450, 234, 32, 300, Theme.INK, Canvas.CENTER, Canvas.BOLD | Canvas.LIGHT);
        c.text(first() ? "머리 위에 이렇게 보여요" : "머리 위에 이렇게 보여요 · 계정 " + J.str(data, "account"), 450, 298, 15, Theme.MUTED, Canvas.CENTER, Canvas.BOLD | Canvas.LIGHT);

        // 입력칸 (패널 가운데: x130~770)
        float ix = 130, iy = 315, icx = 450, icy = 360;
        c.image(focusArt ? "nickname/input_focus" : "nickname/input", ix, iy, 640, 90, 0xFFFFFFFF);
        String shown = text.isEmpty() ? "§7" + min() + "~" + max() + "글자로 입력" : text;
        c.textFit(shown, icx, icy, 34, 470, Theme.INK, Canvas.CENTER, Canvas.BOLD | Canvas.LIGHT);
        // 조합 중인 글자 밑줄
        String cm = ime.composing();
        float tw = text.isEmpty() ? 0 : Math.min(470, c.textWidth(text, 34, Canvas.BOLD));
        float before = text.isEmpty() ? 0 : c.textWidth(text.substring(0, Math.min(caret, text.length())), 34, Canvas.BOLD);
        float startX = icx - tw / 2;
        if (!cm.isEmpty()) {
            float bw = c.textWidth(text.substring(0, caret), 34, Canvas.BOLD), cw = c.textWidth(cm, 34, Canvas.BOLD);
            c.fill(startX + bw, icy + 20, cw, 3, 1.5f, 0xFF7CB342);
            before = bw + cw;
        }
        if ((now - caretBlink) / 530 % 2 == 0) c.fill(startX + before + 2, icy - 20, 3, 40, 1.5f, 0xFF7CB342);
        // 한/영 표시 (왼쪽 안)
        c.fill(ix + 26, icy - 16, 38, 32, 10, korean ? 0xFF7CB342 : 0xFFB9AFA0);
        c.text(korean ? "한" : "A", ix + 45, icy, 19, Theme.WHITE, Canvas.CENTER, Canvas.BOLD);
        hits.add(new R("lang", ix + 20, icy - 22, 50, 44));
        int len = text.codePointCount(0, text.length());
        c.text(len + "/" + max(), ix + 612, icy, 18, len > max() ? Theme.RED : Theme.MUTED, Canvas.RIGHT, Canvas.BOLD | Canvas.LIGHT);
        hits.add(new R("input", ix, iy, 640, 90));

        // 안내 / 오류 (입력칸과 버튼 사이)
        String msg;
        int col;
        boolean warn = false;
        if (local != null && !local.isEmpty()) { msg = local; col = Theme.RED; warn = true; }
        else if (!error.isEmpty()) { msg = error; col = Theme.RED; warn = true; }
        else if (!okMsg.isEmpty() && text.equals(checked)) { msg = okMsg; col = Theme.GREEN_DARK; }
        else { msg = J.str(data, "rule", "한글·영문·숫자 " + min() + "~" + max() + "글자"); col = Theme.MUTED; }
        if (warn) {
            float sh = Anim.shake(now - lastEdit, 2, 10) * Math.max(0, 1 - (now - lastEdit) / 400f);
            c.image("nickname/warn", 780 + sh, 328, 64, 64, 0xFFFFFFFF);   // 입력칸 오른쪽 바깥
        }
        c.textFit(msg, 450, 424, 19, 640, col, Canvas.CENTER, Canvas.BOLD | Canvas.LIGHT);

        // 버튼 (첫 설정이면 확인만 가운데)
        boolean canSave = local == null && savingSince == 0;
        float okX = first() ? 320 : 150;
        boolean hOk = canSave && Anim.in(mx, my, okX, 445, 260, 80);
        c.image(hOk ? "nickname/btn_ok_hover" : "nickname/btn_ok", okX, 445, 260, 80, canSave ? 0xFFFFFFFF : 0x99FFFFFF);
        c.text(savingSince > 0 ? "확인 중..." : "정했어요!", okX + 130, 484, 30, canSave ? 0xFF3F7F31 : 0xFF9AAA90, Canvas.CENTER, Canvas.BOLD | Canvas.LIGHT);
        hits.add(new R("ok", okX, 445, 260, 80));
        if (!first()) {
            boolean hNo = Anim.in(mx, my, 490, 445, 260, 80);
            c.image(hNo ? "nickname/btn_cancel_hover" : "nickname/btn_cancel", 490, 445, 260, 80, 0xFFFFFFFF);
            c.text("취소", 620, 484, 30, 0xFF6B6B6B, Canvas.CENTER, Canvas.BOLD | Canvas.LIGHT);
            hits.add(new R("cancel", 490, 445, 260, 80));
        }
        c.pop();
    }

    private final boolean focusArt = true; // 입력칸은 항상 포커스

    private void save(long now) {
        commit();
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
        mx = (mx - ox) / k;
        my = (my - oy) / k;
        for (R r : hits) {
            if (!Anim.in(mx, my, r.x(), r.y(), r.w(), r.h())) continue;
            switch (r.key()) {
                case "ok" -> save(now);
                case "lang" -> { commit(); korean = !korean; host.sound("minecraft:ui.button.click", 1.4f, .3f); }
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
        if (full().codePointCount(0, full().length()) >= max() + 2 && ime.empty()) return true;
        // 윈도우가 한글 모드면 조합 안 된 자모(ㅈ,ㅜ…)가 그대로 들어온다 → 그것도 조합기로
        boolean rawJamo = !pasting && ch >= 0x3131 && ch <= 0x3163;
        char j = rawJamo ? ch : korean ? HangulInput.jamo(ch) : 0;
        if (j != 0) {
            String done = ime.feed(j);
            if (!done.isEmpty()) { text = text.substring(0, caret) + done + text.substring(caret); caret += done.length(); }
        } else {
            commit();
            text = text.substring(0, caret) + ch + text.substring(caret);
            caret++;
        }
        edited(now);
        return true;
    }

    @Override public boolean key(int key, int mods, long now) {
        if (savingSince > 0 && now - savingSince > 5000) savingSince = 0;
        if (key == 346 || key == -1) { commit(); korean = !korean; caretBlink = now; return true; } // 오른쪽 Alt / 한영키
        if (key == 259 && ime.backspace()) { edited(now); return true; }
        if (key != 259) commit();
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
        commit();
        boolean k = korean;
        korean = false;      // 붙여넣기는 글자 그대로
        pasting = true;
        for (char ch : s.toCharArray()) typed(ch, now);
        pasting = false;
        korean = k;
    }
}
