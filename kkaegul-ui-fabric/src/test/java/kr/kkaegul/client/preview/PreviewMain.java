package kr.kkaegul.client.preview;

import com.google.gson.JsonObject;
import kr.kkaegul.client.ui.*;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.GradientPaint;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.FileInputStream;

/** 게임 없이 화면을 PNG 로 뽑아 보는 도구: java PreviewMain <리소스 assets/kkaegul 폴더> <출력폴더> */
public final class PreviewMain {
    static Panel open;
    static final Host HOST = new Host() {
        public void send(String a, String... kv) { System.out.println("send " + a + " " + String.join(",", kv)); }
        public void sound(String id, float p, float v) {}
        public void openPanel(Panel p) { open = p; }
        public void closePanel(Panel p) { open = null; }
        public Panel openPanel() { return open; }
    };

    public static void main(String[] args) throws Exception {
        File assets = new File(args[0]), out = new File(args[1]);
        out.mkdirs();
        TextRaster.load(new FileInputStream(new File(assets, "font/jua.ttf")), new FileInputStream(new File(assets, "font/pretendard_semibold.ttf")), new FileInputStream(new File(assets, "font/pretendard_extrabold.ttf")));
        File tex = new File(assets, "textures/ui");
        long T = 1_000_000;

        // 1. HUD + 멘트 + 토스트 (툴팁)
        UiHub hub = new UiHub(HOST);
        hub.handle(J.parse("""
          {"type":"update","screen":"hud","data":{"debt":1234,"held":56,"gauge":{"value":62,"max":100,"tier":"high"},"coins":17,"money":3000,
           "title":"&6[운빨왕]","fever":{"diamond":185,"triple":0,"box":40},"day":2,"protected":true,"jail":{"done":3,"required":10},"mail":2,
           "tips":{"target":"&b목표 다이아\\n&f제출까지 남은 다이아 1,234개\\n&7증가 룰렛이면 늘고, 감소·제출하면 줄어요. 0이 되면 졸업!"}}}"""), T);
        hub.handle(J.parse("{\"type\":\"center\",\"text\":\"&a{e:system} 다이아 50개 제출 완료!\",\"sub\":\"&7남은 다이아 1,234개\",\"ms\":2500}".replace("{e:system}", "⨏")), T);
        hub.handle(J.parse("{\"type\":\"toast\",\"text\":\"&a깨굴코인 +5\"}"), T);
        shot(out, "01_hud.png", tex, c -> hub.renderOverlay(c, T + 800, true, 230, 158, false));
        hub.handle(J.parse("{\"type\":\"update\",\"screen\":\"diamond\",\"data\":{\"debt\":1200,\"delta\":-34}}"), T + 900);
        shot(out, "02_hud_pop.png", tex, c -> hub.renderOverlay(c, T + 1300, false, 0, 0, false));

        // 2. 룰렛
        UiHub r = new UiHub(HOST);
        r.handle(J.parse("""
          {"type":"roulette","kind":"big_increase","label":"빅 증가 룰렛","increase":true,"min":100,"max":500,"rolls":[312,312],"total":624,"extra":1,"spinMs":3000,"fever":true}"""), T);
        shot(out, "03_roulette_spin.png", tex, c -> r.renderOverlay(c, T + 1500, false, 0, 0, true));
        shot(out, "04_roulette_stop.png", tex, c -> r.renderOverlay(c, T + 3600, false, 0, 0, true));
        UiHub r2 = new UiHub(HOST);
        r2.handle(J.parse("""
          {"type":"roulette","kind":"decrease","label":"감소 룰렛","increase":false,"rolls":[37],"total":37,"extra":0,"spinMs":2500,"fever":false}"""), T);
        shot(out, "05_roulette_dec.png", tex, c -> r2.renderOverlay(c, T + 3200, false, 0, 0, true));

        // 3. 가챠
        JsonObject gd = J.parse("""
          {"machine":"2","label":"2번 기계 (999)","max":9,"cost":9,"destroyChance":4.0,"coins":23,"holdingPickaxe":true,
           "stats":{"fortune":3,"efficiency":9,"unbreaking":5},"protectTickets":1,
           "tips":{"cost":"&e1회 비용 · 깨굴코인","destroy":"&c파괴확률\\n&f돌릴 때 곡괭이가 부서질 확률"}}""");
        GachaPanel gp = new GachaPanel(HOST, gd);
        shot(out, "06_gacha.png", tex, c -> gp.render(c, T, 1400, 640));
        gp.fx("gacha_jackpot", J.parse("{\"machine\":\"2\",\"max\":9,\"before\":[3,9,5],\"after\":[9,9,9],\"jackpot\":true}"), T);
        shot(out, "07_gacha_jackpot.png", tex, c -> gp.render(c, T + 2600, 0, 0));
        GachaPanel gp2 = new GachaPanel(HOST, J.parse(gd.toString().replace("\"machine\":\"2\"", "\"machine\":\"3\"")));
        gp2.fx("gacha_destroy", new JsonObject(), T);
        shot(out, "08_gacha_destroy.png", tex, c -> gp2.render(c, T + 900, 0, 0));
        GachaPanel gp3 = new GachaPanel(HOST, J.parse(gd.toString().replace("\"machine\":\"2\"", "\"machine\":\"1\"")));
        gp3.fx("gacha_result", J.parse("{\"machine\":\"1\",\"max\":5,\"before\":[1,2,3],\"after\":[5,2,4]}"), T);
        shot(out, "09_gacha_result.png", tex, c -> gp3.render(c, T + 1300, 0, 0));

        // 4. 메뉴
        JsonObject md = J.parse("""
          {"tab":"me","me":{"name":"깨굴러버","account":"Frog_123","title":"&6[운빨왕]","debt":1234,"held":56,"coins":17,"gauge":{"value":62,"max":100},
             "totalIncreased":5120,"totalSubmitted":3000,"totalDonated":150,"jailCount":4,"pickaxeBreaks":7,"instantDeaths":2,"bestPickaxe":9,"day":3,"pickaxe":{"fortune":7,"efficiency":9,"unbreaking":4}},
           "playtime":{"todaySeconds":4200,"failPapers":6,"exchangeNeed":5,"milestones":[
             {"id":"m30","label":"깨굴코인 3개","needSeconds":1800,"claimed":true,"ready":false},
             {"id":"m60","label":"일반상자","needSeconds":3600,"claimed":false,"ready":true},
             {"id":"m120","label":"고급상자","needSeconds":7200,"claimed":false,"ready":false},
             {"id":"m180","label":"파괴 방지권","needSeconds":10800,"claimed":false,"ready":false}]},
           "titles":{"rows":[{"id":"a","name":"&6[운빨왕]","owned":true,"selected":true,"condition":"가챠 잭팟 달성"},
             {"id":"b","name":"&c[파괴왕]","owned":true,"selected":false,"condition":"곡괭이 10번 파괴"},
             {"id":"c","name":"&b[다이아 부자]","owned":false,"selected":false,"condition":"다이아 누적 10000개"},
             {"id":"d","name":"&a[깜빵 단골]","owned":false,"selected":false,"condition":"깜빵 10번"},
             {"id":"e","name":"&d[777]","owned":false,"selected":false,"condition":"1번 기계 5/5/5"}]},
           "hall":{"boards":[{"id":"increased","label":"다이아 누적","rows":[{"rank":1,"name":"깨굴러버","value":5120,"title":"&6[운빨왕]"},{"rank":2,"name":"초록개구리","value":4000,"title":""},{"rank":3,"name":"올챙이","value":3100,"title":""},{"rank":4,"name":"두꺼비","value":200,"title":""}]},
             {"id":"breaks","label":"곡괭이 파괴","rows":[]},{"id":"deaths","label":"즉사","rows":[]},{"id":"remaining","label":"남은 다이아","rows":[]},{"id":"donated","label":"기부","rows":[]},{"id":"jail","label":"깜빵","rows":[]}]}}""");
        for (String t : new String[]{"me", "playtime", "titles", "hall"}) {
            JsonObject d = md.deepCopy();
            d.addProperty("tab", t);
            MenuPanel mp = new MenuPanel(HOST, d, T);
            shot(out, "10_menu_" + t + ".png", tex, c -> mp.render(c, T + 1000, 1100, 500));
        }

        // 5. 유저 선택
        PlayerSelectPanel ps = new PlayerSelectPanel(HOST, J.parse("{\"purpose\":\"user_tp\",\"players\":[\"Frog_1\",\"Steve\",\"Alex\",\"Notch\",\"Kkaegul\"],\"labels\":[\"깨굴러버\",\"초록개구리\",\"\",\"올챙이\",\"두꺼비왕\"]}"), T);
        ps.click(960, 330, T);
        shot(out, "11_player_select.png", tex, c -> { ps.render(c, T + 1000, 960, 400); });
        ps.click(960, 400, T + 1000);
        shot(out, "11_player_select.png", tex, c -> ps.render(c, T + 1000, 960, 470));

        // 6. 닉네임
        NicknamePanel np = new NicknamePanel(HOST, J.parse("{\"account\":\"Frog_123\",\"nickname\":\"\",\"min\":2,\"max\":8,\"first\":true,\"title\":\"\",\"rule\":\"한글·영문·숫자 2~8글자\"}"), T);
        np.typed('깨', T); np.typed('굴', T); np.typed('러', T); np.typed('버', T);
        shot(out, "12_nickname_first.png", tex, c -> np.render(c, T + 1000, 0, 0));
        np.update(J.parse("{\"error\":\"이미 누가 쓰고 있는 이름이에요\",\"ok\":false}"), T + 1000);
        NicknamePanel np2 = new NicknamePanel(HOST, J.parse("{\"account\":\"Frog_123\",\"nickname\":\"깨굴\",\"min\":2,\"max\":8,\"first\":false,\"title\":\"&6[운빨왕]\"}"), T);
        np2.typed('ㅋ', T);
        shot(out, "13_nickname_change.png", tex, c -> np2.render(c, T + 1000, 0, 0));

        // 7. 상자 / 피버 / 초대장 / 졸업
        fx(out, tex, "14_box_open.png", "box_open", """
          {"box":"premium","label":"고급상자","fever":true,"rewards":[{"id":"diamond","label":"다이아 감소 룰렛","rare":false},{"id":"coin","label":"깨굴코인 5개","rare":false},{"id":"protect","label":"파괴 방지권","rare":true},{"id":"cola","label":"깨굴 콜라","rare":false}]}""", 3800);
        fx(out, tex, "15_box_shake.png", "box_open", "{\"box\":\"lucky\",\"label\":\"행운상자\",\"rewards\":[]}", 500);
        fx(out, tex, "16_fever_diamond.png", "fever_diamond", "{\"seconds\":300}", 1400);
        fx(out, tex, "17_fever_triple.png", "fever_triple", "{\"seconds\":30}", 1400);
        fx(out, tex, "18_fever_box.png", "fever_box", "{\"seconds\":300}", 1400);
        fx(out, tex, "19_invite_envelope.png", "invite_letter", "{\"title\":\"깨굴이의 위험한 초대\",\"to\":\"깨굴러버\",\"place\":\"위험한 의자\"}", 800);
        fx(out, tex, "20_invite_card.png", "invite_letter", "{\"title\":\"깨굴이의 위험한 초대\",\"to\":\"깨굴러버\",\"place\":\"위험한 의자\"}", 3600);
        fx(out, tex, "21_invite_rise.png", "invite_letter", "{\"title\":\"깨굴이의 위험한 초대\",\"to\":\"깨굴러버\",\"from\":\"초록개구리\"}", 1900);
        fx(out, tex, "22_graduate.png", "graduate", "{\"name\":\"깨굴러버\"}", 1500);
        System.out.println("done");
    }

    interface Draw { void draw(Canvas c); }

    static void fx(File out, File tex, String name, String id, String json, long at) throws Exception {
        UiHub h = new UiHub(HOST);
        long T = 1_000_000;
        h.handle(J.parse("{\"type\":\"fx\",\"id\":\"" + id + "\",\"data\":" + json + "}"), T);
        shot(out, name, tex, c -> h.renderOverlay(c, T + at, false, 0, 0, true));
    }

    static void shot(File out, String name, File tex, Draw d) throws Exception {
        BufferedImage img = new BufferedImage(1600, 900, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        g.setPaint(new GradientPaint(0, 0, new Color(0x87B8E8), 0, 900, new Color(0x5E8C3A)));
        g.fillRect(0, 0, 1600, 900);
        g.setColor(new Color(0x6B4F2E));
        g.fillRect(0, 700, 1600, 200);
        d.draw(new AwtCanvas(img, tex));
        ImageIO.write(img, "png", new File(out, name));
    }
}
