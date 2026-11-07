package kr.kkaegul.core.hook;

import kr.kkaegul.core.KkaegulCore;
import kr.kkaegul.core.data.PlayerData;
import kr.kkaegul.core.economy.EconomyService;
import kr.kkaegul.core.util.Txt;
import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import org.bukkit.OfflinePlayer;

/**
 * PlaceholderAPI 연동 — UnlimitedNametags(머리 위 이름표)·탭·스코어보드에서 쓰기.
 *  %kkaegul_nickname%   닉네임 (없으면 계정명)
 *  %kkaegul_title%      칭호 (색 포함, 없으면 빈칸)
 *  %kkaegul_display%    "칭호 닉네임"
 *  %kkaegul_debt%       남은(목표) 다이아     %kkaegul_held% 보유 다이아
 *  %kkaegul_coins%      깨굴코인             %kkaegul_gauge% 공용 운빨 게이지
 * PlaceholderAPI 가 있을 때만 KkaegulCore 가 이 클래스를 불러온다.
 */
public final class KkaegulPlaceholders extends PlaceholderExpansion {
    private final KkaegulCore plugin;

    public KkaegulPlaceholders(KkaegulCore plugin) { this.plugin = plugin; }

    @Override public String getIdentifier() { return "kkaegul"; }
    @Override public String getAuthor() { return "junwoo"; }
    @Override public String getVersion() { return "0.1.0"; }
    @Override public boolean persist() { return true; }

    @Override
    public String onRequest(OfflinePlayer player, String params) {
        String key = params == null ? "" : params.toLowerCase();
        if (key.equals("gauge")) return String.valueOf(plugin.gauge().value());
        if (player == null) return "";
        PlayerData d = plugin.data().get(player.getUniqueId());
        if (d == null) return player.getName() == null ? "" : player.getName();
        String nick = plugin.nickname().display(d);
        String title = Txt.color(plugin.titles().display(d));
        return switch (key) {
            case "nickname", "name" -> nick;
            case "title" -> title;
            case "display" -> (title.isBlank() ? "" : title + " ") + Txt.color("&f") + nick;
            case "debt" -> Txt.num(d.debt);
            case "debt_raw" -> String.valueOf(d.debt);   // 탭 정렬용 (쉼표 없는 숫자)
            case "held" -> Txt.num(d.held);
            case "coins" -> Txt.num(d.coins);
            case "dust" -> EconomyService.fmt(d.sellDust);
            default -> null;
        };
    }
}
