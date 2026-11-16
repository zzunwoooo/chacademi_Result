import com.diamondserver.client.St;
import com.diamondserver.ui.G;
import com.diamondserver.ui.HudUi;
import com.diamondserver.ui.NickUi;
import com.diamondserver.ui.Paint;
import com.diamondserver.ui.ShopUi;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.awt.AlphaComposite;
import java.awt.Color;
import java.awt.Composite;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.Shape;
import java.awt.geom.AffineTransform;
import java.awt.image.BufferedImage;
import java.io.File;
import java.util.HashMap;
import java.util.Map;
import java.util.Random;
import javax.imageio.ImageIO;

/**
 * 화면 검수 도구. 게임이 쓰는 화면 코드(mod 의 ui 패키지)를 그대로 불러서 그림 파일로 뽑는다.
 * 게임을 켜지 않고도 글자 위치·넘침·겹침을 눈으로 확인할 수 있다.
 *
 * 실행 (저쪽, diamond-server 폴더에서):
 *   javac -encoding UTF-8 -cp <gson.jar> -d <임시폴더> mod/src/main/java/com/diamondserver/ui/*.java \
 *         mod/src/main/java/com/diamondserver/client/St.java tools/ui-review/Review.java
 *   java -Dfile.encoding=UTF-8 -cp "<임시폴더>;<gson.jar>" Review <게임 기본 그림 폴더> <출력 폴더>
 * 게임 기본 그림 폴더 = 게임 파일(minecraft-client.jar)에서 꺼낸 아이템·스킨 png 를 모아 둔 곳.
 */
public class Review {
    static final File TEX = new File("mod/src/main/resources/assets/diamondserver/textures/ui");
    static File mc;

    /** 그림 파일로 그리는 그리기 도구 */
    static final class AwtG implements G {
        final Graphics2D g;
        final double vw, vh;
        static final Map<String, BufferedImage> cache = new HashMap<>();

        AwtG(Graphics2D g, double vw, double vh) { this.g = g; this.vw = vw; this.vh = vh; }

        static BufferedImage load(File f) {
            return cache.computeIfAbsent(f.getPath(), k -> {
                try { return f.isFile() ? ImageIO.read(f) : null; } catch (Exception e) { return null; }
            });
        }

        @Override public double vw() { return vw; }
        @Override public double vh() { return vh; }

        private void missing(String name, double x, double y, double w, double h) {
            g.setColor(Color.MAGENTA);
            g.fillRect((int) x, (int) y, (int) w, (int) h);
            System.out.println("!! 없는 그림: " + name);
        }

        @Override public void img(String name, double x, double y, double w, double h, double alpha) {
            BufferedImage im = load(new File(TEX, name + ".png"));
            if (im == null) { missing(name, x, y, w, h); return; }
            Composite old = g.getComposite();
            g.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, (float) Math.max(0, Math.min(1, alpha))));
            int pw = (int) Math.round(w), ph = (int) Math.round(h);
            g.drawImage(Paint.scaled(im, pw, ph), (int) Math.round(x), (int) Math.round(y), null);
            g.setComposite(old);
        }

        @Override public void imgClip(String name, double x, double y, double w, double h, double from, double to) {
            Shape old = g.getClip();
            g.clipRect((int) Math.round(x + w * from), (int) Math.round(y), (int) Math.round(w * (to - from)), (int) Math.round(h));
            img(name, x, y, w, h, 1);
            g.setClip(old);
        }

        @Override public void imgRot(String name, double cx, double cy, double w, double h, double deg) {
            BufferedImage im = load(new File(TEX, name + ".png"));
            if (im == null) { missing(name, cx - w / 2, cy - h / 2, w, h); return; }
            AffineTransform old = g.getTransform();
            g.rotate(Math.toRadians(deg), cx, cy);
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
            g.drawImage(im, (int) Math.round(cx - w / 2), (int) Math.round(cy - h / 2), (int) Math.round(w), (int) Math.round(h), null);
            g.setTransform(old);
        }

        @Override public void rect(double x, double y, double w, double h, int argb) {
            g.setColor(new Color(argb, true));
            g.fill(new java.awt.geom.Rectangle2D.Double(x, y, w, h));
        }

        @Override public double textW(String s, double px) { return Paint.width(s, px); }

        @Override public void text(String s, double x, double y, double px, int anchor, int argb) {
            double w = Paint.width(s, px);
            Paint.text(g, s, anchor == 1 ? x - w / 2 : anchor == 2 ? x - w : x, y, px, argb);
        }

        @Override public void item(String mcId, double x, double y, double size) {
            BufferedImage im = load(new File(mc, mcId.substring(mcId.indexOf(':') + 1) + ".png"));
            if (im == null) { missing(mcId, x, y, size, size); return; }
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
            g.drawImage(im, (int) Math.round(x), (int) Math.round(y), (int) Math.round(size), (int) Math.round(size), null);
        }

        @Override public void face(String uuid, double x, double y, double size) {
            String[] skins = {"steve", "alex", "ari", "kai"};
            BufferedImage s = load(new File(mc, skins[Math.floorMod(uuid.hashCode(), skins.length)] + ".png"));
            if (s == null) { missing("face", x, y, size, size); return; }
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
            int ix = (int) Math.round(x), iy = (int) Math.round(y), sz = (int) Math.round(size);
            g.drawImage(s, ix, iy, ix + sz, iy + sz, 8, 8, 16, 16, null);
            g.drawImage(s, ix, iy, ix + sz, iy + sz, 40, 8, 48, 16, null);
        }
    }

    interface Scene { void draw(G g); }

    static void save(File out, String name, int w, int h, Scene scene) throws Exception {
        BufferedImage im = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = im.createGraphics();
        Paint.hints(g);
        // 게임 화면 대신 까는 배경
        g.setPaint(new java.awt.GradientPaint(0, 0, new Color(0x9CC3DE), 0, h * 0.57f, new Color(0xD7E8EE)));
        g.fillRect(0, 0, w, h);
        Random r = new Random(11);
        for (int y = (int) (h * 0.52); y < h; y += 40) for (int x = 0; x < w; x += 40) {
            int v = r.nextInt(18);
            g.setColor(y < h * 0.6 ? new Color(96 - v, 150 - v, 84 - v) : new Color(110 - v, 140 - v + (y < h * 0.7 ? 20 : -20), 86 - v));
            g.fillRect(x, y, 40, 40);
        }
        double k = Math.min(w / 1920.0, h / 1080.0);       // 게임과 같은 맞춤: 가로·세로 중 작은 쪽 기준
        g.scale(k, k);
        scene.draw(new AwtG(g, w / k, h / k));
        g.dispose();
        ImageIO.write(im, "png", new File(out, name));
        System.out.println(name);
    }

    static JsonObject j(String s) { return JsonParser.parseString(s).getAsJsonObject(); }

    static final ShopUi.Host HOST = new ShopUi.Host() {
        @Override public int count(String id) {
            return switch (id) { case "minecraft:coal" -> 128; case "minecraft:iron_ingot" -> 48; case "minecraft:raw_gold" -> 12;
                case "minecraft:redstone" -> 200; case "minecraft:lapis_lazuli" -> 64; case "minecraft:emerald" -> 3; default -> 0; };
        }
        @Override public void buy(String id, int qty) { }
        @Override public void sell(String mat) { }
        @Override public void submit(long qty) { }
        @Override public void close() { }
        @Override public void clickSound() { }
    };

    static void base(long goal, long held) {
        St.clear();
        St.applyState(j("{goal:" + goal + ",held:" + held + ",done:false}"));
        St.applyShop(j("""
            {cats:[{id:"pickaxe",label:"곡괭이"},{id:"enchant",label:"인챈트북"},{id:"item",label:"아이템"}],
             items:[
              {id:"wooden_pickaxe",cat:"pickaxe",name:"나무 곡괭이",price:1,amount:1,icon:"minecraft:wooden_pickaxe"},
              {id:"stone_pickaxe",cat:"pickaxe",name:"돌 곡괭이",price:3,amount:1,icon:"minecraft:stone_pickaxe"},
              {id:"iron_pickaxe",cat:"pickaxe",name:"철 곡괭이",price:10,amount:1,icon:"minecraft:iron_pickaxe"},
              {id:"golden_pickaxe",cat:"pickaxe",name:"금 곡괭이",price:8,amount:1,icon:"minecraft:golden_pickaxe"},
              {id:"diamond_pickaxe",cat:"pickaxe",name:"다이아 곡괭이",price:40,amount:1,icon:"minecraft:diamond_pickaxe"},
              {id:"netherite_pickaxe",cat:"pickaxe",name:"네더라이트 곡괭이",price:150,amount:1,icon:"minecraft:netherite_pickaxe"},
              {id:"efficiency_3",cat:"enchant",name:"효율 III",price:20,amount:1,icon:"minecraft:enchanted_book"},
              {id:"efficiency_5",cat:"enchant",name:"효율 V",price:60,amount:1,icon:"minecraft:enchanted_book"},
              {id:"fortune_1",cat:"enchant",name:"행운 I",price:30,amount:1,icon:"minecraft:enchanted_book"},
              {id:"fortune_2",cat:"enchant",name:"행운 II",price:80,amount:1,icon:"minecraft:enchanted_book"},
              {id:"fortune_3",cat:"enchant",name:"행운 III",price:200,amount:1,icon:"minecraft:enchanted_book"},
              {id:"unbreaking_3",cat:"enchant",name:"내구성 III",price:30,amount:1,icon:"minecraft:enchanted_book"},
              {id:"mending",cat:"enchant",name:"수선",price:150,amount:1,icon:"minecraft:enchanted_book"},
              {id:"food",cat:"item",name:"밥",price:1,amount:8,icon:"minecraft:bread",art:"items/food"},
              {id:"inven_save_ticket",cat:"item",name:"인벤세이브권",price:15,amount:1,icon:"minecraft:paper",art:"items/inventory_save_ticket"},
              {id:"return_ticket",cat:"item",name:"귀환권",price:5,amount:1,icon:"minecraft:paper",art:"items/return_ticket"},
              {id:"teleport_ticket",cat:"item",name:"텔레포트권",price:5,amount:1,icon:"minecraft:paper",art:"items/teleport_ticket"}],
             sell:[{mat:"minecraft:coal",name:"석탄",price:0.015},{mat:"minecraft:iron_ingot",name:"철 주괴",price:0.05},{mat:"minecraft:raw_gold",name:"금 원석",price:0.08},
                   {mat:"minecraft:redstone",name:"레드스톤",price:0.008},{mat:"minecraft:lapis_lazuli",name:"청금석",price:0.015},{mat:"minecraft:emerald",name:"에메랄드",price:0.5}]}
            """));
    }

    static void age(String what, long ms) throws Exception {
        // 시간이 지난 장면을 만들기 위해 시작 시각을 과거로 돌린다
        long now = System.currentTimeMillis();
        switch (what) {
            case "banner" -> St.banner = new St.Banner(St.banner.donor(), St.banner.count(), St.banner.name(), St.banner.kind(), St.banner.icon(), St.banner.target(), now - ms);
            case "pick" -> St.pick = new St.Pick(St.pick.title(), St.pick.icon(), St.pick.names(), St.pick.ids(), St.pick.winner(), now - ms, St.pick.ms());
            case "wheel" -> St.wheel = new St.Wheel(St.wheel.title(), St.wheel.labels(), St.wheel.icons(), St.wheel.winner(), now - ms, St.wheel.ms());
            case "announce" -> St.announce = new St.Announce(St.announce.icon(), St.announce.title(), St.announce.desc(), now - ms);
            case "death" -> St.death = new St.Death(St.death.name(), now - ms);
            case "toasts" -> { for (int i = 0; i < St.toasts.size(); i++) { St.Toast t = St.toasts.get(i); St.toasts.set(i, new St.Toast(t.text(), t.ok(), now - ms)); } }
            case "gain" -> St.heldChangedAt = now - ms;
            default -> { }
        }
    }

    static final String WHEEL = """
        {title:"룰렛 → 나니",labels:["목표 -300","목표 +300","목표 ±100","다이아 200개","연출 3연속","AI 플레이어","다이아 반토막","꽝: 밥 3개"],
         icons:["icons/goal_down","icons/goal_up","icons/random_goal","icons/diamond_pile","icons/triple_effect","icons/ai_player","icons/half_diamond","icons/no_prize"],winner:%d,ms:3500}""";

    public static void main(String[] a) throws Exception {
        mc = new File(a[0]);
        File out = new File(a[1]);
        out.mkdirs();

        // 01 평소 화면: 효과 3개 + 대기열 6건(4줄 + 외 2건) + 획득 알림. 숫자는 일부러 크게(넘침 확인)
        base(12470, 9860);
        St.applyEffects(j("{list:[{id:'curse',name:'저주받은 곡괭이',icon:'icons/cursed_pick',ms:148000,total:180000},{id:'reverse',name:'거꾸로 세상',icon:'icons/upside_down',ms:12000,total:30000},{id:'giant',name:'거인화',icon:'icons/giant',ms:21000,total:30000}]}"));
        St.applyQueue(j("{lines:[{icon:'icons/goal_up',text:'목표 증가 10회 → 다이아왕나니'},{icon:'icons/roulette',text:'룰렛 → 린코'},{icon:'icons/meal',text:'밥 3개 → 준우'},{icon:'icons/instant_death',text:'즉사 → 깨굴'},{icon:'icons/meal',text:'x'},{icon:'icons/meal',text:'y'}]}"));
        St.heldDelta = 3; age("gain", 300);
        save(out, "01_평소화면.png", 1920, 1080, g -> HudUi.draw(g));

        // 02 후원 알림 + 알림 글 3개 (가장 긴 글 포함)
        base(1284, 86);
        St.applyEvent(j("{donor:'별풍선을정말많이쏘는사람',count:4444,name:'목표 증가 10회',kind:'bad',icon:'icons/goal_up',target:'다이아왕나니'}"));
        St.toast("나니님의 남은 목표 +312 (10회)", false);
        St.toast("보유 다이아가 부족합니다. (필요 12,800개)", false);
        St.toast("까마귀가 다이아왕나니님의 다이아 64개를 물고 달아납니다. 때려서 되찾으세요!", true);
        age("banner", 500); age("toasts", 500);
        save(out, "02_후원알림.png", 1920, 1080, g -> HudUi.draw(g));

        // 03 발표 창
        base(1247, 86);
        St.applyAnnounce(j("{icon:'icons/ant_size',title:'개미화',desc:'준우님'}"));
        age("announce", 500);
        save(out, "03_발표창.png", 1920, 1080, g -> HudUi.draw(g));

        // 04 대상 룰렛: 도는 중 / 멈춘 뒤
        base(1247, 86);
        St.applyEvent(j("{donor:'장난꾸러기',count:100,name:'장난 랜덤',kind:'neutral',icon:'icons/hotbar_shuffle',target:''}"));
        St.applyPick(j("{title:'다이아 도둑 까마귀',icon:'icons/crow',names:['나니','린코','준우','깨굴'],ids:['a','b','c','d'],winner:2,ms:3500}"));
        age("banner", 2500); age("pick", 1500);
        save(out, "04_대상룰렛_도는중.png", 1920, 1080, g -> HudUi.draw(g));
        St.banner = null; age("pick", 3800);
        save(out, "05_대상룰렛_결과.png", 1920, 1080, g -> HudUi.draw(g));

        // 06 룰렛: 도는 중 / 멈춘 뒤 (당첨 칸마다 바늘 아래에 오는지 3가지)
        base(1247, 86);
        St.applyEvent(j("{donor:'룰렛장인',count:1000,name:'룰렛',kind:'neutral',icon:'icons/roulette',target:'나니'}"));
        St.applyWheel(j(String.format(WHEEL, 3)));
        age("banner", 2500); age("wheel", 1200);
        save(out, "06_룰렛_도는중.png", 1920, 1080, g -> HudUi.draw(g));
        St.banner = null;
        for (int k : new int[]{0, 3, 6}) {
            St.applyWheel(j(String.format(WHEEL, k)));
            age("wheel", 3800);
            save(out, "07_룰렛_결과_" + k + "번칸.png", 1920, 1080, g -> HudUi.draw(g));
        }

        // 08 즉사 (긴 닉네임)
        base(1247, 86);
        St.applyDeath(j("{name:'다이아왕나니짱'}"));
        age("death", 600);
        save(out, "08_즉사.png", 1920, 1080, g -> HudUi.draw(g));

        // 09~ 상점
        String[] cats = {"pickaxe", "enchant", "item"};
        String[] sels = {"netherite_pickaxe", "fortune_3", "food"};
        int[] qtys = {2, 64, 3};
        for (int i = 0; i < 3; i++) {
            base(1247, i == 1 ? 25 : 86);
            ShopUi ui = new ShopUi(HOST);
            ui.preset(0, cats[i], sels[i], qtys[i]);
            save(out, "09_상점_구매_" + cats[i] + ".png", 1920, 1080, g -> { HudUi.under(g, true); ui.draw(g, 0, 0); HudUi.over(g, true); });
        }
        base(1247, 86);
        ShopUi none = new ShopUi(HOST);
        none.preset(0, "pickaxe", null, 1);
        save(out, "10_상점_구매_안고름.png", 1920, 1080, g -> { HudUi.under(g, true); none.draw(g, 900, 380); HudUi.over(g, true); });
        ShopUi sell = new ShopUi(HOST);
        sell.preset(1, null, null, 1);
        save(out, "11_상점_판매.png", 1920, 1080, g -> { HudUi.under(g, true); sell.draw(g, 0, 0); HudUi.over(g, true); });
        ShopUi sub = new ShopUi(HOST);
        sub.preset(2, null, null, 1);
        save(out, "12_상점_제출.png", 1920, 1080, g -> { HudUi.under(g, true); sub.draw(g, 0, 0); HudUi.over(g, true); });
        base(1234567, 987654);
        ShopUi big = new ShopUi(HOST);
        big.preset(2, null, null, 1);
        save(out, "13_상점_제출_큰숫자.png", 1920, 1080, g -> { HudUi.under(g, true); big.draw(g, 0, 0); HudUi.over(g, true); });

        // 창이 열려 있을 때 알림은 가장 새것 하나만
        base(1247, 86);
        St.toast("철 곡괭이 구매 완료 (다이아 -10)", true);
        St.toast("보유 다이아가 부족합니다. (필요 300개)", false);
        age("toasts", 500);
        ShopUi withToast = new ShopUi(HOST);
        withToast.preset(0, "pickaxe", "netherite_pickaxe", 2);
        save(out, "13b_상점_알림.png", 1920, 1080, g -> { HudUi.under(g, true); withToast.draw(g, 0, 0); HudUi.over(g, true); });

        // 14~ 닉네임 창
        NickUi.Host nh = new NickUi.Host() {
            @Override public void submit(String name) { }
            @Override public void close() { }
            @Override public void clickSound() { }
        };
        base(1247, 86);
        NickUi first = new NickUi(nh, true, "", "한글·숫자 2~8글자", "a");
        first.input = "나니";
        save(out, "14_닉네임_처음.png", 1920, 1080, g -> { HudUi.under(g, false); first.draw(g); HudUi.over(g, true); });
        NickUi change = new NickUi(nh, false, "다이아왕나니짱짱", "한글·숫자 2~8글자", "a");
        change.showError("이미 다른 사람이 쓰고 있는 이름입니다.");
        save(out, "15_닉네임_바꾸기_거절.png", 1920, 1080, g -> { HudUi.under(g, false); change.draw(g); HudUi.over(g, true); });

        // 16~ 다른 창 크기: 넓은 화면(21:9), 4:3, 작은 창
        base(1247, 86);
        St.applyEvent(j("{donor:'룰렛장인',count:1000,name:'룰렛',kind:'neutral',icon:'icons/roulette',target:'나니'}"));
        St.applyQueue(j("{lines:[{icon:'icons/goal_up',text:'목표 증가 10회 → 나니'}]}"));
        age("banner", 500);
        ShopUi wide = new ShopUi(HOST);
        wide.preset(0, "pickaxe", "iron_pickaxe", 1);
        save(out, "16_넓은화면_2560x1080.png", 2560, 1080, g -> { HudUi.under(g, true); wide.draw(g, 0, 0); HudUi.over(g, true); });
        save(out, "17_4대3_1440x1080.png", 1440, 1080, g -> { HudUi.under(g, true); wide.draw(g, 0, 0); HudUi.over(g, true); });
        save(out, "18_작은창_1280x720.png", 1280, 720, g -> { HudUi.under(g, true); wide.draw(g, 0, 0); HudUi.over(g, true); });
    }
}
