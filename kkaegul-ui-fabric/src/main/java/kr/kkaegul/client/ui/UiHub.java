package kr.kkaegul.client.ui;

import com.google.gson.JsonObject;

import java.util.HashMap;
import java.util.Map;

/**
 * 서버 메시지(kkaegul:ui) → 화면 상태. 게임/미리보기 둘 다 이걸 쓴다.
 * 상시 레이어: HUD, 멘트, 토스트, 룰렛, 큰 연출. 창(Panel): 가챠/메뉴/유저선택/닉네임.
 */
public final class UiHub {
    public final HudView hud = new HudView();
    public final NoticeView notice = new NoticeView();
    public final RouletteView roulette = new RouletteView();
    public final FxView fx = new FxView();
    private final Host host;
    /** UUID → {name, title} 머리 위 이름표 */
    private volatile Map<String, String[]> names = Map.of();

    public UiHub(Host host) { this.host = host; }

    public Map<String, String[]> names() { return names; }

    public void handle(JsonObject m, long now) {
        String type = J.str(m, "type");
        switch (type) {
            case "open" -> open(J.str(m, "screen"), J.objOrEmpty(m, "data"), now);
            case "update" -> update(J.str(m, "screen"), J.objOrEmpty(m, "data"), now);
            case "close" -> {
                Panel p = host.openPanel();
                String s = J.str(m, "screen");
                if (p != null && (s.isEmpty() || p.screen().equals(s))) host.closePanel(p);
            }
            case "center" -> notice.center(J.str(m, "text"), J.str(m, "sub"), J.num(m, "ms", 2500), now);
            case "toast" -> notice.toast(J.str(m, "text"), now);
            case "roulette" -> roulette.add(m, now);
            case "fx" -> fx(J.str(m, "id"), J.objOrEmpty(m, "data"), now);
            case "names" -> {
                Map<String, String[]> map = new HashMap<>();
                JsonObject o = J.objOrEmpty(m, "map");
                for (var e : o.entrySet()) {
                    if (!e.getValue().isJsonObject()) continue;
                    JsonObject v = e.getValue().getAsJsonObject();
                    map.put(e.getKey().toLowerCase(), new String[]{J.str(v, "name"), J.str(v, "title")});
                }
                names = Map.copyOf(map);
            }
            default -> { }
        }
    }

    private void open(String screen, JsonObject data, long now) {
        Panel cur = host.openPanel();
        if (cur != null && cur.screen().equals(screen)) { cur.update(data, now); return; }
        Panel p = switch (screen) {
            case "gacha" -> new GachaPanel(host, data);
            case "menu" -> new MenuPanel(host, data, now);
            case "player_select" -> new PlayerSelectPanel(host, data, now);
            case "nickname" -> new NicknamePanel(host, data, now);
            case "submit" -> new SubmitPanel(host, data, now);
            case "hud" -> { hud.set(data, now); yield null; }
            default -> null;
        };
        if (p != null) host.openPanel(p);
    }

    private void update(String screen, JsonObject data, long now) {
        switch (screen) {
            case "hud" -> hud.set(data, now);
            case "gauge" -> hud.gauge(data, now);
            case "diamond" -> hud.diamond(data, now);
            case "fever" -> hud.fever(data, now);
            default -> {
                Panel p = host.openPanel();
                if (p != null && p.screen().equals(screen)) p.update(data, now);
            }
        }
    }

    private void fx(String id, JsonObject data, long now) {
        Panel p = host.openPanel();
        if (p != null && p.fx(id, data, now)) return;
        if (this.fx.handles(id)) { this.fx.add(id, data, now); return; }
        switch (id) {
            case "gacha_jackpot" -> {
                long[] a = J.longs(data, "after");
                notice.center("&6&l잭팟!!!", a.length == 3 ? a[0] + " / " + a[1] + " / " + a[2] : "", 3500, now);
            }
            case "gacha_result" -> {
                long[] a = J.longs(data, "after");
                if (a.length == 3) notice.center("&e행운 " + a[0] + " · 효율 " + a[1] + " · 내구성 " + a[2], "", 2500, now);
            }
            case "gacha_destroy" -> notice.center("&c곡괭이 파괴!", "&7깨굴이가 웃음을 참고 있어요", 2500, now);
            case "gacha_protect" -> notice.center("&d파괴 방지권 발동!", "&f곡괭이를 지켰어요", 2500, now);
            case "submit_normal" -> notice.center("&a제출 완료!", "&f" + J.num(data, "credited") + "개 제출 · 남은 다이아 " + J.num(data, "debt"), 2500, now);
            case "random_submit" -> notice.center(J.str(data, "message", "&a제출 완료!"), "&f" + J.num(data, "used") + "개 → " + J.num(data, "credited") + "개 인정 · 남은 다이아 " + J.num(data, "debt"), 2800, now);
            case "title_unlock" -> notice.center("&d칭호 획득!", J.str(data, "name") + (J.bool(data, "serverFirst") ? " &6(서버 최초!)" : ""), 3000, now);
            default -> { }
        }
    }

    /** 상시 레이어 그리기 (HUD 단계에서 매 프레임) */
    public void renderOverlay(Canvas c, long now, boolean cursor, float mx, float my, boolean panelOpen) {
        if (!panelOpen) hud.render(c, now);
        roulette.render(c, now, host);
        fx.render(c, now, host);
        notice.render(c, now);
        if (cursor && !panelOpen) hud.tooltip(c, mx, my);
    }
}
