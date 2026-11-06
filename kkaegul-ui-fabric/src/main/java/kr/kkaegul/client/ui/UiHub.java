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
    public final RaceView race = new RaceView();
    public final MinigameView mini = new MinigameView();
    public final ExtraView extra = new ExtraView();
    private final Host host;
    /** UUID → {name, title} 머리 위 이름표 */
    private volatile Map<String, String[]> names = Map.of();

    public UiHub(Host host) { this.host = host; }

    public Map<String, String[]> names() { return names; }
    /** 머리 위 이름표를 모드가 바꿀지 (기본 끔 — 서버의 UnlimitedNametags 가 담당) */
    private volatile boolean nameplate = false;
    public boolean nameplate() { return nameplate; }

    public void handle(JsonObject m, long now) {
        String type = J.str(m, "type");
        switch (type) {
            case "open" -> open(J.str(m, "screen"), J.objOrEmpty(m, "data"), now);
            case "update" -> update(J.str(m, "screen"), J.objOrEmpty(m, "data"), now);
            case "close" -> {
                Panel p = host.openPanel();
                String s = J.str(m, "screen");
                if (s.isEmpty() || mini.handles(s)) mini.close(s, now);
                if (extra.handles(s)) extra.close(s, now);
                if (p != null && (s.isEmpty() || p.screen().equals(s))) host.closePanel(p);
            }
            case "center" -> notice.center(J.str(m, "text"), J.str(m, "sub"), J.num(m, "ms", 2500), now);
            case "toast" -> notice.toast(J.str(m, "text"), J.str(m, "kind"), now);
            case "roulette" -> roulette.add(m, now);
            case "fx" -> fx(J.str(m, "id"), J.objOrEmpty(m, "data"), now);
            case "names" -> {
                nameplate = m.has("nameplate") && m.get("nameplate").getAsBoolean();
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
        if (mini.handles(screen)) { mini.open(screen, data, now); return; }
        if (extra.handles(screen)) { extra.open(screen, data, now); return; }
        Panel cur = host.openPanel();
        if (cur != null && cur.screen().equals(screen)) { cur.update(data, now); return; }
        Panel p = switch (screen) {
            case "gacha" -> new GachaPanel(host, data);
            case "menu" -> new MenuPanel(host, data, now);
            case "player_select" -> new PlayerSelectPanel(host, data, now);
            case "nickname" -> new NicknamePanel(host, data, now);
            case "submit" -> new SubmitPanel(host, data, now);
            case "shop" -> new ShopPanel(host, data, now);
            case "race" -> new RacePanel(host, data, now);
            case "frog_jump" -> new FrogJumpPanel(host, data, now);
            case "jail_exit" -> new JailExitPanel(host, data, now);
            case "mail" -> new MailPanel(host, data, now);
            case "withdraw" -> new WithdrawPanel(host, data, now);
            case "hud" -> { hud.set(data, now); yield null; }
            default -> null;
        };
        if (p != null) host.openPanel(p);
    }

    private void update(String screen, JsonObject data, long now) {
        if (mini.handles(screen)) { mini.update(screen, data, now); return; }
        if (extra.handles(screen)) { extra.update(screen, data, now); return; }
        switch (screen) {
            case "hud" -> hud.set(data, now);
            case "gauge" -> hud.gauge(data, now);
            case "diamond" -> hud.diamond(data, now);
            case "fever" -> hud.fever(data, now);
            case "race_hud" -> {
                race.hud(data, now);
                Panel p = host.openPanel();
                if (p instanceof RacePanel rp) rp.update(data, now);
            }
            default -> {
                Panel p = host.openPanel();
                if (p != null && p.screen().equals(screen)) p.update(data, now);
            }
        }
    }

    private void fx(String id, JsonObject data, long now) {
        Panel p = host.openPanel();
        if (p != null && p.fx(id, data, now)) return;
        if (id.equals("race_result")) { race.result(data, now); return; }
        if (id.equals("race_bet")) return;
        if (mini.handlesFx(id)) { mini.fx(id, data, now); return; }
        if (extra.handlesFx(id)) { extra.fx(id, data, now); return; }
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
    /** HUD 클릭 (Alt 커서 / 채팅창): ▼ = 메뉴, 우편 = 우편함, 나머지 = 내 정보 */
    public boolean clickOverlay(float mx, float my) {
        String k = hud.hitKey(mx, my);
        if (k == null) return false;
        switch (k) {
            case "menu" -> { toggleDropdown(System.currentTimeMillis()); return true; }
            case "target", "held", "coin" -> host.send("menu.open", "tab", "me");
            case "mail" -> host.send("mail.open");   // 우편함 창
            default -> { return false; }
        }
        host.sound("minecraft:ui.button.click", 1.2f, .4f);
        return true;
    }

    /** HUD ▼ / M 키: 드롭다운 펼치기·접기 */
    public void toggleDropdown(long now) {
        Panel cur = host.openPanel();
        if (cur instanceof DropdownPanel dp) { dp.close(now); return; }
        if (cur != null) return;
        host.openPanel(new DropdownPanel(host, hud, now));
        host.sound("minecraft:ui.button.click", 1.3f, .35f);
    }

    public void renderOverlay(Canvas c, long now, boolean cursor, float mx, float my, boolean panelOpen) {
        hud.hideGauge = mini.active(now);
        if (!panelOpen || host.openPanel() instanceof DropdownPanel) hud.render(c, now);
        if (!panelOpen) mini.render(c, now);
        if (!panelOpen) extra.render(c, now);
        roulette.render(c, now, host);
        race.render(c, now, panelOpen);
        fx.render(c, now, host);
        notice.render(c, now);
        if (cursor && !panelOpen) hud.tooltip(c, mx, my);
    }
}
