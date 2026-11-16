package com.diamondserver.ui;

/**
 * 닉네임 설정 창. 받은 그림만 재활용: 상점 창(절반 크기) + 대기열 칸(입력 칸) + 구매 버튼(확인).
 * 글자 입력은 게임 쪽이 받아서 input 에 넣어 준다 (한글 조합은 게임 입력 칸과 한글 패치 모드가 처리).
 */
public final class NickUi {
    public interface Host {
        void submit(String name);
        void close();
        void clickSound();
    }

    private final Host host;
    public final boolean first;        // 처음 정하는 것: 닫을 수 없다
    private final String rule, self;
    public String input = "";
    private String error = "";
    private long sentAt;
    private String down = "";
    private double ox, oy;

    public NickUi(Host host, boolean first, String current, String rule, String selfUuid) {
        this.host = host;
        this.first = first;
        this.input = current == null ? "" : current;
        this.rule = rule == null ? "" : rule;
        this.self = selfUuid == null ? "" : selfUuid;
    }

    /** 서버가 거절한 이유를 보여 준다 */
    public void showError(String text) { error = text == null ? "" : text; sentAt = 0; }

    private static boolean in(double mx, double my, double x, double y, double w, double h) {
        return mx >= x && mx < x + w && my >= y && my < y + h;
    }

    public void draw(G g) {
        ox = (g.vw() - 1920) / 2;
        oy = (g.vh() - 1080) / 2;
        double x = ox + 630, y = oy + 315, w = 660, h = 450;             // shop_window 1320x900 의 절반
        g.img("shop/shop_window", x, y, w, h, 1);
        g.face(self, x + 20, y + 14, 30);
        g.text(first ? "닉네임을 정해 주세요" : "닉네임 설정", x + 62, y + 28, 24, 0, G.TEXT);
        if (!first) g.img(down.equals("close") ? "shop/close_pressed" : "shop/close_normal", x + w - 24 - 29, y + 13, 29, 29, 1);
        g.text(rule, ox + 960, y + 160, 20, 1, G.DIM);
        // 입력 칸: 대기열 한 줄 그림(360x56) — 왼쪽 칸에 내 얼굴, 오른쪽에 적는 글자
        g.img("hud/queue_row", ox + 780, y + 202, 360, 56, 1);
        g.face(self, ox + 790, y + 213, 34);
        double px = 22;
        while (px > 15 && g.textW(input, px) > 274) px -= 1;
        g.text(input, ox + 842, y + 230, px, 0, G.TEXT);
        if (System.currentTimeMillis() / 500 % 2 == 0) g.rect(ox + 842 + g.textW(input, px) + 4, y + 217, 2, 26, G.TEXT);     // 깜빡이는 입력 표시
        boolean waiting = sentAt > 0 && System.currentTimeMillis() - sentAt < 3000;
        if (!error.isEmpty()) g.fit(error, ox + 960, y + 296, 18, 1, G.WARN, 600);
        else g.text(waiting ? "확인하는 중..." : "엔터를 누르거나 확인을 누르세요", ox + 960, y + 296, 18, 1, G.DIM);
        g.img(down.equals("ok") ? "shop/buy_pressed" : "shop/buy_normal", x + w - 24 - 132, y + h - 48, 132, 39, 1);
        g.text("확인", x + w - 24 - 66, y + h - 29, 19, 1, G.DARK);
    }

    public void submit() {
        String name = input.strip();
        if (name.isEmpty()) { error = "닉네임을 적어 주세요."; return; }
        error = "";
        sentAt = System.currentTimeMillis();
        host.submit(name);
    }

    public void mouseDown(double mx, double my) {
        double x = ox + 630, y = oy + 315, w = 660, h = 450;
        if (in(mx, my, x + w - 24 - 132, y + h - 48, 132, 39)) { down = "ok"; host.clickSound(); submit(); }
        else if (!first && in(mx, my, x + w - 24 - 29, y + 13, 29, 29)) { down = "close"; host.clickSound(); host.close(); }
    }

    public void mouseUp() { down = ""; }
}
