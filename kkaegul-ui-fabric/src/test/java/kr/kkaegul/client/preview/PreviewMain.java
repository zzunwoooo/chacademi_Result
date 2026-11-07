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
        hub.handle(J.parse("{\"type\":\"toast\",\"text\":\"&c이미 받았어요.\",\"kind\":\"error\"}"), T);
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

        GachaPanel gp4 = new GachaPanel(HOST, gd);
        gp4.fx("gacha_protect", new JsonObject(), T);
        shot(out, "09b_gacha_protect.png", tex, c -> gp4.render(c, T + 1200, 0, 0));
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

        // 제출소
        JsonObject sd = J.parse("{\"debt\":1234,\"held\":56,\"inventory\":120,\"available\":176,\"max\":176,\"random\":{\"same\":{\"chance\":70,\"factor\":1},\"double\":{\"chance\":10,\"factor\":2},\"minus30\":{\"chance\":10,\"factor\":0.7},\"minus50\":{\"chance\":10,\"factor\":0.5}}}");
        SubmitPanel sp = new SubmitPanel(HOST, sd, T);
        shot(out, "24_submit.png", tex, c -> sp.render(c, T + 1000, 1300, 600));
        SubmitPanel sp2 = new SubmitPanel(HOST, sd, T);
        sp2.fx("random_submit", J.parse("{\"outcome\":\"minus30\",\"factor\":0.7,\"used\":100,\"credited\":70,\"donated\":30,\"debt\":1164}"), T);
        shot(out, "25_submit_dice.png", tex, c -> sp2.render(c, T + 500, 0, 0));
        shot(out, "26_submit_donate.png", tex, c -> sp2.render(c, T + 1900, 0, 0));
        SubmitPanel sp3 = new SubmitPanel(HOST, sd, T);
        sp3.fx("random_submit", J.parse("{\"outcome\":\"double\",\"factor\":2,\"used\":100,\"credited\":200,\"donated\":0,\"debt\":1034}"), T);
        shot(out, "27_submit_double.png", tex, c -> sp3.render(c, T + 1700, 0, 0));
        // 상점
        JsonObject shd = J.parse("""
          {"tab":"sell","held":128,"available":128,"coins":24,"dust":0.04,"coinPrice":10,"coinMax":100,"farmNextMs":1392000,
           "outcomes":[{"id":"fail","chance":10,"multiplier":0},{"id":"normal","chance":79,"multiplier":1},{"id":"double","chance":10,"multiplier":2},{"id":"triple","chance":1,"multiplier":3}],
           "sell":[{"id":"WHEAT","name":"밀","price":0.024,"have":64,"farm":true,"trend":1},{"id":"CARROT","name":"당근","price":0.012,"have":32,"farm":true,"trend":-1},
                   {"id":"POTATO","name":"감자","price":0.018,"have":48,"farm":true,"trend":1},{"id":"IRON_INGOT","name":"철 주괴","price":0.05,"have":16,"farm":false},
                   {"id":"GOLD_INGOT","name":"금 주괴","price":0.1,"have":8,"farm":false},{"id":"LAPIS_LAZULI","name":"청금석","price":0.015,"have":24,"farm":false},
                   {"id":"EMERALD","name":"에메랄드","price":0.5,"have":0,"farm":false}]}""");
        ShopPanel shs = new ShopPanel(HOST, shd, T);
        shot(out, "28_shop_sell.png", tex, c -> shs.render(c, T + 1000, 700, 700));
        JsonObject shc = J.merge(shd, J.parse("{\"tab\":\"coin\"}"));
        ShopPanel shc1 = new ShopPanel(HOST, shc, T);
        shot(out, "29_shop_coin.png", tex, c -> shc1.render(c, T + 1000, 1300, 600));
        shot(out, "30_shop_odds.png", tex, c -> shc1.render(c, T + 1000, 1100, 275));
        ShopPanel shc2 = new ShopPanel(HOST, shc, T);
        shc2.fx("coin_buy", J.parse("{\"outcome\":\"double\",\"multiplier\":2,\"amount\":3,\"got\":6,\"message\":\"&e2배 구매! 깨굴코인 6개\"}"), T);
        shot(out, "31_shop_double.png", tex, c -> shc2.render(c, T + 1400, 0, 0));
        ShopPanel shc3 = new ShopPanel(HOST, shc, T);
        shc3.fx("coin_buy", J.parse("{\"outcome\":\"fail\",\"multiplier\":0,\"amount\":3,\"got\":0,\"message\":\"&c구매 실패...\"}"), T);
        shot(out, "32_shop_fail.png", tex, c -> shc3.render(c, T + 1400, 0, 0));
        ShopPanel shs2 = new ShopPanel(HOST, shd, T);
        shs2.fx("shop_sold", J.parse("{\"value\":1.536,\"got\":1,\"dust\":0.576}"), T);
        shot(out, "33_shop_sold.png", tex, c -> shs2.render(c, T + 500, 0, 0));
        // 개구리 경주
        JsonObject rd = J.parse("""
          {"state":"betting","endsInMs":42000,"round":3,"available":128,"minBet":1,"maxBet":300,"myTotal":20,"winner":-1,
           "frogs":[{"no":1,"name":"깨굴","colorId":0,"form":3,"odds":2.1,"myBet":0,"progress":0.52},
                    {"no":2,"name":"개굴","colorId":1,"form":1,"odds":6.3,"myBet":0,"progress":0.33},
                    {"no":3,"name":"뚱굴","colorId":2,"form":2,"odds":3.4,"myBet":0,"progress":0.78},
                    {"no":4,"name":"초록이","colorId":3,"form":2,"odds":3.4,"myBet":20,"progress":0.9},
                    {"no":5,"name":"폴짝","colorId":4,"form":1,"odds":6.3,"myBet":0,"progress":0.66},
                    {"no":6,"name":"왕눈이","colorId":5,"form":3,"odds":2.1,"myBet":0,"progress":0.24}]}""");
        RacePanel rp1 = new RacePanel(HOST, rd, T);
        shot(out, "34_race_bet.png", tex, c -> rp1.render(c, T + 1000, 700, 560));
        RacePanel rp2 = new RacePanel(HOST, J.merge(rd, J.parse("{\"state\":\"racing\"}")), T);
        shot(out, "35_race_run.png", tex, c -> rp2.render(c, T + 1000, 0, 0));
        RacePanel rp3 = new RacePanel(HOST, J.merge(rd, J.parse("{\"state\":\"result\",\"winner\":4}")), T);
        rp3.fx("race_result", J.parse("{\"winner\":4,\"colorId\":3,\"name\":\"초록이\",\"odds\":3.4,\"bet\":20,\"spent\":20,\"got\":68}"), T);
        shot(out, "36_race_result.png", tex, c -> rp3.render(c, T + 1200, 0, 0));
        UiHub rh = new UiHub(HOST);
        rh.handle(J.parse("{\"type\":\"update\",\"screen\":\"race_hud\",\"data\":" + J.merge(rd, J.parse("{\"state\":\"racing\"}")) + "}"), T);
        shot(out, "37_race_overlay.png", tex, c -> rh.renderOverlay(c, T + 500, false, 0, 0, false));
        // 개구리 점프
        String steps = "[{\"chance\":90,\"multiplier\":1.1},{\"chance\":85,\"multiplier\":1.25},{\"chance\":80,\"multiplier\":1.5},{\"chance\":75,\"multiplier\":1.9},{\"chance\":70,\"multiplier\":2.5},{\"chance\":65,\"multiplier\":3.4},{\"chance\":60,\"multiplier\":4.8},{\"chance\":55,\"multiplier\":7},{\"chance\":50,\"multiplier\":11},{\"chance\":45,\"multiplier\":18}]";
        JsonObject jd = J.parse("{\"available\":128,\"minBet\":1,\"maxBet\":500,\"playing\":false,\"steps\":" + steps + "}");
        FrogJumpPanel j1 = new FrogJumpPanel(HOST, jd, T);
        shot(out, "38_jump_ready.png", tex, c -> j1.render(c, T + 1000, 1300, 745));
        JsonObject jp = J.parse("{\"available\":118,\"minBet\":1,\"maxBet\":500,\"playing\":true,\"bet\":10,\"step\":3,\"multiplier\":1.5,\"cashout\":15,\"steps\":" + steps + "}");
        FrogJumpPanel j2 = new FrogJumpPanel(HOST, jp, T);
        shot(out, "39_jump_play.png", tex, c -> j2.render(c, T + 1000, 0, 0));
        FrogJumpPanel j3 = new FrogJumpPanel(HOST, jp, T);
        j3.fx("jump_ok", J.parse("{\"step\":4}"), T);
        shot(out, "40_jump_air.png", tex, c -> j3.render(c, T + 230, 0, 0));
        FrogJumpPanel j4 = new FrogJumpPanel(HOST, jp, T);
        j4.fx("jump_fail", J.parse("{\"step\":4,\"bet\":10}"), T);
        j4.update(J.parse("{\"playing\":false}"), T);
        shot(out, "41_jump_fail.png", tex, c -> j4.render(c, T + 1400, 0, 0));
        FrogJumpPanel j5 = new FrogJumpPanel(HOST, jp, T);
        j5.fx("jump_cashout", J.parse("{\"step\":3,\"multiplier\":1.5,\"got\":15,\"bet\":10}"), T);
        j5.update(J.parse("{\"playing\":false}"), T);
        shot(out, "42_jump_cash.png", tex, c -> j5.render(c, T + 900, 0, 0));
        // 미니게임 HUD
        UiHub m1 = new UiHub(HOST);
        m1.handle(J.parse("""
          {"type":"open","screen":"minigame","data":{"game":"pepero","title":"빼빼로 게임","seconds":30,"target":60,"progress":36,
           "partner":{"name":"깨굴이","account":"x"},
           "participants":[{"name":"깨굴이","state":"success"},{"name":"돌맹이","state":"wait"},{"name":"잔디콩","state":"out"},{"name":"다이아맨","state":"wait"},{"name":"a"},{"name":"b"}]}}"""), T);
        shot(out, "43_mini_pepero.png", tex, c -> m1.renderOverlay(c, T + 9000, false, 0, 0, false));
        UiHub m2 = new UiHub(HOST);
        m2.handle(J.parse("{\"type\":\"open\",\"screen\":\"minigame\",\"data\":{\"game\":\"treasure\",\"title\":\"보물찾기\",\"seconds\":90,\"chests\":12,\"left\":7,\"finder\":\"돌맹이\"}}"), T);
        m2.handle(J.parse("{\"type\":\"fx\",\"id\":\"treasure_found\",\"data\":{\"label\":\"다이아 ×10\",\"premium\":true}}"), T + 60000);
        shot(out, "44_mini_treasure.png", tex, c -> m2.renderOverlay(c, T + 61000, false, 0, 0, false));
        UiHub m3 = new UiHub(HOST);
        m3.handle(J.parse("{\"type\":\"open\",\"screen\":\"wasd\",\"data\":{\"keys\":\"[W, A, S, D, W, D]\",\"seconds\":7,\"index\":2,\"title\":\"깨굴이의 위험한 초대\"}}"), T);
        shot(out, "45_mini_keys.png", tex, c -> m3.renderOverlay(c, T + 2000, false, 0, 0, false));
        m3.handle(J.parse("{\"type\":\"update\",\"screen\":\"wasd\",\"data\":{\"index\":3,\"done\":true,\"success\":false}}"), T + 2500);
        shot(out, "46_mini_keys_wrong.png", tex, c -> m3.renderOverlay(c, T + 2600, false, 0, 0, false));
        UiHub m4 = new UiHub(HOST);
        m4.handle(J.parse("{\"type\":\"fx\",\"id\":\"minigame_result\",\"data\":{\"success\":true,\"sub\":\"짝꿍과 빼빼로 60번 성공!\",\"rewards\":[{\"label\":\"고급상자\",\"premium\":true},{\"label\":\"다이아 ×3\"}]}}"), T);
        shot(out, "47_mini_success.png", tex, c -> m4.renderOverlay(c, T + 1200, false, 0, 0, false));
        UiHub m5 = new UiHub(HOST);
        m5.handle(J.parse("{\"type\":\"fx\",\"id\":\"minigame_result\",\"data\":{\"success\":false,\"sub\":\"빼빼로가 이유 없이 부러졌다!\",\"rewards\":[{\"label\":\"빅증가 룰렛\"}]}}"), T);
        shot(out, "48_mini_fail.png", tex, c -> m5.renderOverlay(c, T + 1200, false, 0, 0, false));
        // 깜빵 · 퀴즈 · 운세 · 우편함 · 작은 카드
        UiHub e1 = new UiHub(HOST);
        e1.handle(J.parse("{\"type\":\"open\",\"screen\":\"jail\",\"data\":{\"inJail\":true,\"done\":24,\"required\":40}}"), T);
        e1.handle(J.parse("{\"type\":\"fx\",\"id\":\"jail_game\",\"data\":{\"multiplier\":3,\"label\":\"X3\",\"base\":40,\"amount\":120}}"), T);
        shot(out, "50_jail_slot.png", tex, c -> e1.renderOverlay(c, T + 3500, false, 0, 0, false));
        shot(out, "51_jail_spin.png", tex, c -> e1.renderOverlay(c, T + 1200, false, 0, 0, false));
        JailExitPanel jx = new JailExitPanel(HOST, J.parse("{}"), T);
        shot(out, "52_jail_exit.png", tex, c -> jx.render(c, T + 1000, 1150, 560));
        UiHub e2 = new UiHub(HOST);
        e2.handle(J.parse("{\"type\":\"open\",\"screen\":\"quiz\",\"data\":{\"category\":\"맞춤법\",\"question\":\"다음 중 맞는 표현은? 1) 어의없다 2) 어이없다 3) 어히없다\",\"seconds\":20}}"), T);
        shot(out, "53_quiz.png", tex, c -> e2.renderOverlay(c, T + 6000, false, 0, 0, false));
        e2.handle(J.parse("{\"type\":\"update\",\"screen\":\"quiz\",\"data\":{\"result\":\"correct\",\"rewardText\":\"소소 감소 룰렛!\"}}"), T + 7000);
        shot(out, "54_quiz_correct.png", tex, c -> e2.renderOverlay(c, T + 7600, false, 0, 0, false));
        UiHub e3 = new UiHub(HOST);
        e3.handle(J.parse("{\"type\":\"open\",\"screen\":\"fortune\",\"data\":{\"good\":true,\"stat\":\"fortune\",\"amount\":3,\"seconds\":600,\"message\":\"오늘은 곡괭이에 깨굴이의 기운이 깃들었어요!\",\"usesLeft\":2,\"gaugeDelta\":2}}"), T);
        shot(out, "55_fortune.png", tex, c -> e3.renderOverlay(c, T + 1500, false, 0, 0, false));
        MailPanel mp = new MailPanel(HOST, J.parse("{\"items\":[{\"name\":\"고급상자\",\"amount\":2,\"material\":\"CHEST\"},{\"name\":\"꽝 종이\",\"amount\":3,\"material\":\"PAPER\"},{\"name\":\"수선 책\",\"amount\":1,\"material\":\"ENCHANTED_BOOK\"},{\"name\":\"다이아몬드\",\"amount\":10,\"material\":\"DIAMOND\"},{\"name\":\"철 흉갑\",\"amount\":1,\"material\":\"IRON_CHESTPLATE\"},{\"name\":\"행운상자\",\"amount\":1,\"material\":\"CHEST\"}]}"), T);
        shot(out, "56_mail.png", tex, c -> mp.render(c, T + 1000, 900, 420));
        // 드롭다운 (펼치는 중 / 다 펼침 + 이동 하위 목록) + 인출
        UiHub dd = new UiHub(HOST);
        dd.handle(J.parse("{\"type\":\"update\",\"screen\":\"hud\",\"data\":{\"debt\":1234,\"held\":56,\"coins\":17,\"gauge\":{\"value\":62,\"max\":100}}}"), T);
        dd.renderOverlay(scratch(tex), T, false, 0, 0, false);
        dd.toggleDropdown(T);
        for (long dt : new long[]{70, 140, 230}) {
            long tt = T + dt;
            shot(out, "58_dropdown_" + dt + ".png", tex, c -> { dd.renderOverlay(c, tt, false, 0, 0, true); open.render(c, tt, 0, 0); });
        }
        for (long f = 300; f < 900; f += 16) { long tt = T + f; dd.renderOverlay(scratch(tex), tt, false, 0, 0, true); open.render(scratch(tex), tt, 404, 288); }
        shot(out, "59_dropdown_move.png", tex, c -> { dd.renderOverlay(c, T + 900, false, 0, 0, true); open.render(c, T + 900, 404, 288); });
        open = null;
        WithdrawPanel wp = new WithdrawPanel(HOST, J.parse("{\"held\":40}"), T);
        for (long f = 0; f < 600; f += 16) wp.render(scratch(tex), T + f, 900, 610);
        shot(out, "60_withdraw.png", tex, c -> wp.render(c, T + 600, 900, 610));
        UiHub hr = new UiHub(HOST);
        hr.handle(J.parse("{\"type\":\"fx\",\"id\":\"harass_roulette\",\"data\":{\"mode\":\"personal\",\"tiles\":[\"monsters\",\"mining_fatigue\",\"tp_random_user\",\"freeze\",\"delete_slot\"],\"result\":\"monsters\",\"resultText\":\"랜덤 몬스터 3마리 소환\",\"spinMs\":2800}}"), T);
        shot(out, "61_harass_spin.png", tex, c -> hr.renderOverlay(c, T + 1200, false, 0, 0, false));
        shot(out, "62_harass_result.png", tex, c -> hr.renderOverlay(c, T + 3700, false, 0, 0, false));
        UiHub ho = new UiHub(HOST);
        ho.handle(J.parse("{\"type\":\"fx\",\"id\":\"harass_roulette\",\"data\":{\"mode\":\"owner\",\"tiles\":[\"quiz\",\"wasd\",\"sky\",\"fever\",\"all_jail\",\"lobby_kick\"],\"result\":\"wasd\",\"resultText\":\"WASD 랜덤 입력\",\"spinMs\":2800}}"), T);
        shot(out, "63_harass_owner.png", tex, c -> ho.renderOverlay(c, T + 3700, false, 0, 0, false));
        String[][] cards = {{"title_unlock", "{\"name\":\"&6운빨왕\",\"serverFirst\":true}"}, {"submit_normal", "{\"credited\":50,\"debt\":1184}"},
            {"random_submit", "{\"used\":30,\"credited\":60,\"debt\":1124,\"message\":\"&a대박! x2\"}"}, {"gacha_destroy", "{}"}, {"gacha_protect", "{}"}};
        for (String[] cd : cards) {
            UiHub ch = new UiHub(HOST);
            ch.handle(J.parse("{\"type\":\"fx\",\"id\":\"" + cd[0] + "\",\"data\":" + cd[1] + "}"), T);
            shot(out, "64_card_" + cd[0] + ".png", tex, c -> ch.renderOverlay(c, T + 1200, false, 0, 0, false));
        }
        DonationBoardPanel db = new DonationBoardPanel(HOST, J.parse("{\"total\":12345,\"rows\":[{\"rank\":1,\"name\":\"준우\",\"amount\":3200},{\"rank\":2,\"name\":\"깨굴이팬\",\"amount\":2100},{\"rank\":3,\"name\":\"Steve\",\"amount\":990},{\"rank\":4,\"name\":\"다이아부자\",\"amount\":500},{\"rank\":5,\"name\":\"Alex\",\"amount\":320},{\"rank\":6,\"name\":\"개구리\",\"amount\":100},{\"rank\":7,\"name\":\"뉴비\",\"amount\":12}]}"), T);
        shot(out, "65_donation_board.png", tex, c -> db.render(c, T + 1000, 0, 0));
        UiHub bn = new UiHub(HOST);
        bn.handle(J.parse("{\"type\":\"banner\",\"text\":\"준우님이 행운상자를 오픈!\",\"color\":\"gold\"}"), T);
        bn.handle(J.parse("{\"type\":\"toast\",\"text\":\"&c이미 받았어요.\",\"kind\":\"error\"}"), T);
        shot(out, "66_banner.png", tex, c -> bn.renderOverlay(c, T + 1500, false, 0, 0, false));
        DiscordLinkPanel dl = new DiscordLinkPanel(HOST, J.parse("{\"code\":\"482915\",\"minutes\":10}"), T);
        shot(out, "67_discord.png", tex, c -> dl.render(c, T + 1200, 960, 640));
        UiHub e4 = new UiHub(HOST);
        e4.handle(J.parse("{\"type\":\"fx\",\"id\":\"armor_roll\",\"data\":{\"tier\":\"iron\",\"name\":\"철 갑옷\",\"prefix\":\"IRON\",\"message\":\"&f그나마 사람이네\"}}"), T);
        shot(out, "57_armor_roll.png", tex, c -> e4.renderOverlay(c, T + 1500, false, 0, 0, false));
        UiHub e5 = new UiHub(HOST);
        e5.handle(J.parse("{\"type\":\"fx\",\"id\":\"mending_draw\",\"data\":{\"win\":false}}"), T);
        shot(out, "58_mending.png", tex, c -> e5.renderOverlay(c, T + 1500, false, 0, 0, false));
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
        shot(out, "23_gauge_tiers.png", tex, c -> {
            String[] t = {"low", "mid", "high", "jackpot"};
            float[] rr = {.25f, .45f, .75f, 1f};
            for (int i = 0; i < 4; i++) HudView.gaugeBar(c, 560, 200 + i * 170, 700, 46, rr[i], t[i], T);
        });
        java.nio.file.Files.writeString(new File(out, "AUDIT.txt").toPath(), REPORT.toString());
        System.out.println("문제 " + problems + "건");
        System.out.print(REPORT);
    }

    interface Draw { void draw(Canvas c); }

    static void fx(File out, File tex, String name, String id, String json, long at) throws Exception {
        UiHub h = new UiHub(HOST);
        long T = 1_000_000;
        h.handle(J.parse("{\"type\":\"fx\",\"id\":\"" + id + "\",\"data\":" + json + "}"), T);
        shot(out, name, tex, c -> h.renderOverlay(c, T + at, false, 0, 0, true));
    }

    static final StringBuilder REPORT = new StringBuilder();
    static int problems;

    static AwtCanvas scratchC;
    static AwtCanvas scratch(File tex) {
        if (scratchC == null) scratchC = new AwtCanvas(new BufferedImage(1920, 1080, BufferedImage.TYPE_INT_ARGB), tex);
        return scratchC;
    }

    static void shot(File out, String name, File tex, Draw d) throws Exception {
        int PW = 1920, PH = 1080;
        BufferedImage img = new BufferedImage(PW, PH, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        g.setPaint(new GradientPaint(0, 0, new Color(0x87B8E8), 0, PH, new Color(0x5E8C3A)));
        g.fillRect(0, 0, PW, PH);
        g.setColor(new Color(0x6B4F2E));
        g.fillRect(0, PH * 7 / 9, PW, PH * 2 / 9);
        AwtCanvas c = new AwtCanvas(img, tex);
        d.draw(c);
        ImageIO.write(img, "png", new File(out, name));
        audit(out, name, img, c);
    }

    /** 이미지 속 밝은 판 영역(기기 좌표 x0,y0,x1,y1). 글자 중심이 놓인 곳에서 같은 밝은 판을 채워 찾는다 */
    static double[] plate(AwtCanvas.Box b, double cx, double cy) {
        BufferedImage im = b.src();
        var r = b.r();
        int W = im.getWidth(), H = im.getHeight();
        int px = (int) ((cx - r.getMinX()) / r.getWidth() * W), py = (int) ((cy - r.getMinY()) / r.getHeight() * H);
        java.util.function.IntPredicate light = v -> (v >>> 24) > 200 && (((v >> 16) & 255) * 3 + ((v >> 8) & 255) * 6 + (v & 255)) / 10 > 175;
        int sx = -1, sy = -1;
        outer:
        for (int rad = 0; rad < Math.max(W, H) / 4; rad++)
            for (int dx = -rad; dx <= rad; dx++)
                for (int dy = -rad; dy <= rad; dy++) {
                    if (Math.max(Math.abs(dx), Math.abs(dy)) != rad) continue;
                    int x = px + dx, y = py + dy;
                    if (x >= 0 && y >= 0 && x < W && y < H && light.test(im.getRGB(x, y))) { sx = x; sy = y; break outer; }
                }
        if (sx < 0) return null;
        boolean[] seen = new boolean[W * H];
        java.util.ArrayDeque<int[]> q = new java.util.ArrayDeque<>();
        q.add(new int[]{sx, sy}); seen[sy * W + sx] = true;
        int x0 = sx, x1 = sx, y0 = sy, y1 = sy, n = 0;
        while (!q.isEmpty() && n < 4_000_000) {
            int[] c = q.poll(); n++;
            x0 = Math.min(x0, c[0]); x1 = Math.max(x1, c[0]); y0 = Math.min(y0, c[1]); y1 = Math.max(y1, c[1]);
            int[][] nb = {{c[0] + 1, c[1]}, {c[0] - 1, c[1]}, {c[0], c[1] + 1}, {c[0], c[1] - 1}};
            for (int[] d : nb) {
                if (d[0] < 0 || d[1] < 0 || d[0] >= W || d[1] >= H || seen[d[1] * W + d[0]]) continue;
                seen[d[1] * W + d[0]] = true;
                if (light.test(im.getRGB(d[0], d[1]))) q.add(d);
            }
        }
        double fx = r.getWidth() / W, fy = r.getHeight() / H;
        return new double[]{r.getMinX() + x0 * fx, r.getMinY() + y0 * fy, r.getMinX() + (x1 + 1) * fx, r.getMinY() + (y1 + 1) * fy};
    }

    /** 글자 점검: 너무 작음(1080p 기준 15px 미만) / 담고 있는 칸을 넘어감 */
    static void audit(File out, String name, BufferedImage img, AwtCanvas c) throws Exception {
        java.util.List<String> issues = new java.util.ArrayList<>();
        Graphics2D g = img.createGraphics();
        g.setStroke(new java.awt.BasicStroke(3));
        double screen = img.getWidth() * (double) img.getHeight();
        for (AwtCanvas.TextBox t : c.texts) {
            String txt = kr.kkaegul.client.ui.Fmt.strip(t.text());
            if (txt.isEmpty()) continue;
            boolean bad = false;
            if (t.px() < 15) { issues.add(String.format("작음 %.0fpx  \"%s\"", t.px(), txt)); bad = true; }
            double cx = t.r().getCenterX(), cy = t.r().getCenterY();
            AwtCanvas.Box best = null;
            for (AwtCanvas.Box b : c.boxes) {
                var r = b.r();
                double area = r.getWidth() * r.getHeight();
                if (area > screen * .5 || !r.contains(cx, cy) || r.getHeight() < t.r().getHeight() * .9) continue;
                if (best == null || area < best.r().getWidth() * best.r().getHeight()) best = b;
            }
            if (best != null) {
                var r = best.r();
                double tol = 2;
                double ox = Math.max(r.getMinX() - t.r().getMinX(), t.r().getMaxX() - r.getMaxX());
                double oy = Math.max(r.getMinY() - t.r().getMinY(), t.r().getMaxY() - r.getMaxY());
                if (ox > tol || oy > tol) { issues.add(String.format("넘침 x%.0f y%.0f  \"%s\" in %s", Math.max(0, ox), Math.max(0, oy), txt, best.what())); bad = true; }
            }
            // 칸 가운데 맞춤: 글자 중심 아래 밝은 판(칸 안쪽)을 찾아 세로 중심·크기 비교
            if (best != null && best.src() != null) {
                double[] pl = plate(best, cx, cy);
                if (pl != null) {
                    double ph = pl[3] - pl[1], pcy = (pl[1] + pl[3]) / 2, off = cy - pcy;
                    double fontPx = t.r().getHeight();
                    if (ph < fontPx * 3.2 && ph > fontPx * .8) { // 글자 칸으로 볼 만한 판
                        if (Math.abs(off) > Math.max(3, ph * .08)) { issues.add(String.format("치우침 %+.0fpx(판 높이 %.0f)  \"%s\" in %s", off, ph, txt, best.what())); bad = true; }
                        if (fontPx / ph < .40) { issues.add(String.format("칸 대비 작음 %.0f%%  \"%s\" in %s", fontPx / ph * 100, txt, best.what())); bad = true; }
                    }
                }
            }
            if (bad) { g.setColor(Color.RED); g.draw(t.r()); }
        }
        if (!issues.isEmpty()) {
            ImageIO.write(img, "png", new File(out, "AUDIT_" + name));
            REPORT.append("== ").append(name).append('\n');
            for (String i : issues) REPORT.append("  ").append(i).append('\n');
            problems += issues.size();
        }
    }

}
