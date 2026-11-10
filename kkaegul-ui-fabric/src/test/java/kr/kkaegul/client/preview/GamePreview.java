package kr.kkaegul.client.preview;

import kr.kkaegul.client.ui.*;

import java.io.File;
import java.io.FileInputStream;

/** 오목·러시안 룰렛 화면 미리보기: java GamePreview <assets/kkaegul> <출력폴더> */
public final class GamePreview {
    public static void main(String[] args) throws Exception {
        File assets = new File(args[0]), out = new File(args[1]);
        out.mkdirs();
        TextRaster.load(new FileInputStream(new File(assets, "font/jua.ttf")), new FileInputStream(new File(assets, "font/pretendard_semibold.ttf")), new FileInputStream(new File(assets, "font/pretendard_extrabold.ttf")));
        File tex = new File(assets, "textures/ui");
        long T = 1_000_000;
        // 오목: 흑 차례(나 = 흑), 쌍삼 자리 하나
        StringBuilder b = new StringBuilder("0".repeat(225));
        int[][] bl = {{7, 7}, {8, 6}, {6, 9}, {9, 9}}, wh = {{8, 7}, {6, 6}, {9, 7}, {7, 9}};
        for (int[] s : bl) b.setCharAt(s[1] * 15 + s[0], '1');
        for (int[] s : wh) b.setCharAt(s[1] * 15 + s[0], '2');
        String d = "{\"board\":\"" + b + "\",\"last\":" + (9 * 15 + 7) + ",\"turn\":1,\"me\":1,\"seconds\":20,\"stake\":50,\"forbidden\":[" + (8 * 15 + 7) + "],"
                + "\"black\":{\"name\":\"깨굴러버\",\"account\":\"Frog_1\",\"strikes\":0},\"white\":{\"name\":\"초록개구리\",\"account\":\"Steve\",\"strikes\":1}}";
        OmokPanel op = new OmokPanel(PreviewMain.HOST, J.parse(d), T);
        PreviewMain.shot(out, "70_omok.png", tex, c -> op.render(c, T + 3000, 960 + 0, 0));
        OmokPanel op2 = new OmokPanel(PreviewMain.HOST, J.parse(d), T);
        op2.update(J.parse("{\"result\":\"win\",\"resultSub\":\"오목 완성 · 다이아 +50\",\"done\":true}"), T + 100);
        PreviewMain.shot(out, "71_omok_win.png", tex, c -> op2.render(c, T + 1200, 0, 0));
        OmokPanel op3 = new OmokPanel(PreviewMain.HOST, J.parse(d), T);
        op3.update(J.parse("{\"result\":\"lose\",\"resultSub\":\"오목 완성 · 다이아 -50\",\"done\":true}"), T + 100);
        PreviewMain.shot(out, "72_omok_lose.png", tex, c -> op3.render(c, T + 1200, 0, 0));
        // 러시안 룰렛
        UiHub h = new UiHub(PreviewMain.HOST);
        h.handle(J.parse("{\"type\":\"open\",\"screen\":\"minigame\",\"data\":{\"game\":\"rroulette\",\"title\":\"러시안 룰렛 · 테이블 1\",\"participants\":[{\"name\":\"깨굴러버\",\"account\":\"a\",\"state\":\"wait\"},{\"name\":\"초록\",\"account\":\"b\",\"state\":\"out\"}],"
                + "\"pot\":57,\"round\":2,\"chambers\":\"EEUUUU\",\"cursor\":2,\"bangs\":0,\"partner\":{\"name\":\"깨굴러버 차례\",\"account\":\"a\"},\"seconds\":15}}"), T);
        PreviewMain.shot(out, "73_rroulette.png", tex, c -> h.renderOverlay(c, T + 2000, false, 0, 0, false));
        h.handle(J.parse("{\"type\":\"update\",\"screen\":\"minigame\",\"data\":{\"chambers\":\"EEEBUU\",\"cursor\":3,\"bangs\":1}}"), T + 3000);
        PreviewMain.shot(out, "74_rroulette_bang.png", tex, c -> h.renderOverlay(c, T + 3300, false, 0, 0, false));
    }
}
