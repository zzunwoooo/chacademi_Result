package kr.kkaegul.core.hook;

import kr.kkaegul.core.KkaegulCore;
import kr.kkaegul.core.util.Json;
import kr.kkaegul.core.util.Txt;
import org.bukkit.entity.Player;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * 디스코드 보이스 자동 이동 봇(kkaegul-discord-bot) 연결.
 * 서버 → 봇: HTTP POST {url}/event  (헤더 X-Kkaegul-Secret)
 *   {"event":"link-code","code":"123456","uuid":"..","name":".."}     /디코연동 코드 발급
 *   {"event":"tables","game":"poker","label":"포커","tables":[{"no":1,"players":["uuid",..]}]}  매칭 → 테이블별 음성채널로 이동
 *   {"event":"table-end","game":"poker","no":1}                        테이블 끝 → 원래 채널로
 *   {"event":"end","game":"poker"}                                     게임 끝 → 모두 원래 채널, 임시 채널 삭제
 * 봇이 없거나 꺼져 있어도 게임에는 영향 없음 (보내기만 하고 실패는 무시).
 */
public final class DiscordBridge {
    private final KkaegulCore plugin;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
    private final SecureRandom rnd = new SecureRandom();

    public DiscordBridge(KkaegulCore plugin) { this.plugin = plugin; }

    public boolean enabled() { return plugin.getConfig().getBoolean("discord.enabled", false); }

    private void post(Json body) {
        if (!enabled()) return;
        String url = plugin.getConfig().getString("discord.bot-url", "http://127.0.0.1:8787").replaceAll("/+$", "") + "/event";
        String secret = plugin.getConfig().getString("discord.secret", "");
        try {
            HttpRequest req = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(5))
                    .header("Content-Type", "application/json; charset=utf-8").header("X-Kkaegul-Secret", secret)
                    .POST(HttpRequest.BodyPublishers.ofString(body.toString())).build();
            http.sendAsync(req, HttpResponse.BodyHandlers.discarding()).exceptionally(e -> {
                plugin.getLogger().fine("디스코드 봇 연결 실패: " + e.getMessage());
                return null;
            });
        } catch (RuntimeException e) {
            plugin.getLogger().warning("디스코드 봇 주소 확인 (discord.bot-url): " + e.getMessage());
        }
    }

    /** /디코연동 → 6자리 코드. 디스코드에서 /연동 코드 */
    public void link(Player p) {
        if (!enabled()) { p.sendMessage(Txt.color(Txt.notice("&7디스코드 연동이 꺼져 있어요."))); return; }
        String code = String.format("%06d", rnd.nextInt(1_000_000));
        post(Json.obj().put("event", "link-code").put("code", code).put("uuid", p.getUniqueId().toString()).put("name", p.getName()));
        if (plugin.ui().hasRenderer(p)) plugin.ui().open(p, "discord_link", Json.obj().put("code", code).put("minutes", 10));
        p.sendMessage(Txt.color(Txt.notice("&9[디스코드] &f디스코드 서버에서 &e/연동 " + code + " &f을 입력하세요. &7(10분 안에)")));
        p.sendMessage(Txt.color(Txt.notice("&7연동하면 미니게임 테이블이 정해질 때 같은 테이블 사람끼리 음성 채널로 자동 이동돼요. (음성 채널에 들어가 있을 때만)")));
    }

    public void tables(String game, String label, List<List<UUID>> tables) {
        List<Object> arr = new ArrayList<>();
        int no = 1;
        for (List<UUID> t : tables) {
            List<Object> ids = new ArrayList<>();
            for (UUID u : t) ids.add(u.toString());
            arr.add(Json.obj().put("no", no++).put("players", ids));
        }
        post(Json.obj().put("event", "tables").put("game", game).put("label", label).put("tables", arr));
    }

    public void tableEnd(String game, int no) { post(Json.obj().put("event", "table-end").put("game", game).put("no", no)); }

    public void end(String game) { post(Json.obj().put("event", "end").put("game", game)); }
}
