package kr.kkaegul.core.ui;

import kr.kkaegul.core.KkaegulCore;
import kr.kkaegul.core.data.PlayerData;
import kr.kkaegul.core.util.Json;
import kr.kkaegul.core.util.Txt;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

/**
 * 왼쪽 HUD(운빨 게이지 등)에 들어갈 값을 렌더러로 보낸다.
 * 렌더러가 없으면 설정에 따라 액션바로 간단히 표시한다.
 */
public final class HudService {
    private final KkaegulCore plugin;

    public HudService(KkaegulCore plugin) {
        this.plugin = plugin;
        long period = Math.max(20, plugin.getConfig().getLong("ui.hud-refresh-ticks", 40));
        Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            for (Player p : Bukkit.getOnlinePlayers()) refresh(p);
        }, period, period);
    }

    public Json snapshot(Player p) {
        PlayerData d = plugin.data().get(p);
        return Json.obj()
                .put("debt", d.debt)                                   // 목표(남은) 다이아
                .put("held", d.held)                                   // 보유 다이아 (주운 다이아 화폐)
                .put("gauge", plugin.gauge().snapshot(d))
                .put("coins", d.coins)
                .put("money", d.money)
                .put("title", plugin.titles().display(d))
                .put("fever", plugin.fever().state())
                .put("day", plugin.clock().serverDay())
                .put("protected", plugin.clock().protectionActive() && d.debt < plugin.diamonds().threshold())
                .put("jail", d.inJail ? Json.obj().put("done", d.squatDone).put("required", d.squatRequired) : null)
                .put("mail", d.mail.size())
                .put("tips", tips(p, d));
    }

    /**
     * HUD 아이콘 마우스 오버 툴팁 (채팅/인벤토리를 열어 커서가 보일 때). 키 = HUD 요소 id.
     * 문구는 config ui.tooltips.<id> 에서 바꿀 수 있고 {값} 치환을 지원한다.
     */
    private Json tips(Player p, PlayerData d) {
        long thr = plugin.diamonds().threshold();
        boolean prot = plugin.clock().protectionActive() && d.debt < thr;
        String tier = plugin.gauge().tierName(d);
        Json t = Json.obj();
        t.put("target", tip("target", "&b목표 다이아\n&f제출까지 남은 다이아 {debt}개\n&7증가 룰렛이면 늘고, 감소·제출하면 줄어요. 0이 되면 졸업!", "debt", Txt.num(d.debt)));
        t.put("held", tip("held", "&b보유 다이아\n&f모아둔 다이아 {held}개\n&7주운 다이아가 여기로 들어와요. /인출 [개수] 로 꺼내기", "held", Txt.num(d.held)));
        t.put("coin", tip("coin", "&e깨굴코인 {coins}개\n&7가챠샵에서 곡괭이 가챠를 돌릴 때 써요", "coins", Txt.num(d.coins)));
        t.put("gauge", tip("gauge." + tier, gaugeDefault(tier), "gauge", plugin.gauge().value()));
        if (prot) t.put("protection", tip("protection", "&a초반 보호 중\n&f서버 {days}일차까지 남은 다이아가 {threshold}개 미만이면\n&f감소가 적용되지 않아요 (강제졸업 방지)",
                "days", plugin.getConfig().getInt("protection.days", 2), "threshold", Txt.num(thr)));
        if (plugin.fever().diamondFeverActive() || plugin.fever().boxFeverActive()) {
            Json fs = plugin.fever().state();
            t.put("fever", tip("fever", "&c피버 타임!\n&f다이아 피버 {d}초 · 3배 {t}초 · 상자 피버 {b}초\n&7다이아 피버: 룰렛 빨라짐 + 1+1 (3배면 1+2)\n&7상자 피버: 상자 내용물 1+1",
                    "d", fs.map().get("diamond"), "t", fs.map().get("triple"), "b", fs.map().get("box")));
        }
        if (d.inJail) t.put("jail", tip("jail", "&c깜빵에서 운동 중\n&f웅크리기 {done} / {required}회\n&7다 채우면 출구 NPC로 나갈 수 있어요",
                "done", d.squatDone, "required", d.squatRequired));
        return t;
    }

    private String gaugeDefault(String tier) {
        return switch (tier) {
            case "low" -> "&e운빨 게이지 {gauge}\n&f0~40: 다이아 원석 캘 때 다이아 1~2개 추가";
            case "mid" -> "&e운빨 게이지 {gauge}\n&f41~51: 다이아 블록이 드랍될 수 있어요";
            case "high" -> "&e운빨 게이지 {gauge}\n&f52~91: 가챠 파괴확률 감소\n&c증가 룰렛 1+1";
            case "jackpot" -> "&6운빨 게이지 {gauge} (잭팟 구간)\n&f92~99: 가챠 파괴확률 감소 + 높은 숫자 잘 나옴\n&c증가 룰렛 1+2";
            default -> "&e운빨 게이지 {gauge}";
        };
    }

    private String tip(String key, String def, Object... kv) {
        return Txt.color(Txt.fmt(plugin.getConfig().getString("ui.tooltips." + key, def), kv));
    }

    public void refresh(Player p) {
        if (plugin.ui().hasRenderer(p)) {
            plugin.ui().update(p, "hud", snapshot(p));
            return;
        }
        if (!plugin.getConfig().getBoolean("ui.fallback-hud-actionbar", true)) return;
        PlayerData d = plugin.data().get(p);
        String line = Txt.fmt(plugin.getConfig().getString("ui.fallback-hud-format",
                        "&b남은 다이아 &f{debt} &8| &e운빨 &f{gauge} &8| &a코인 &f{coins}{jail}"),
                "debt", Txt.num(d.debt), "gauge", plugin.gauge().value(), "coins", Txt.num(d.coins), "money", Txt.num(d.money),
                "jail", d.inJail ? " &8| &c운동 " + d.squatDone + "/" + d.squatRequired : "");
        p.sendActionBar(Txt.color(line));
    }
}
