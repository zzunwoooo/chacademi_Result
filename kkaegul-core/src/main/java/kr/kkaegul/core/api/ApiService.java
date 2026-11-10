package kr.kkaegul.core.api;

import kr.kkaegul.core.KkaegulCore;
import kr.kkaegul.core.data.PlayerData;
import kr.kkaegul.core.util.Txt;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * 후원 API 진입점. 개인 API와 섭주 API는 명령어가 따로라서 번호가 겹쳐도 된다(333/500/1000).
 *   /kapi <플레이어> <번호>   개인 API (해당 스트리머 본인에게)
 *   /ksapi <번호>            섭주 API (서버 전체)
 * 번호 → 액션 목록은 config.yml api.personal / api.server.
 */
public final class ApiService {
    private final KkaegulCore plugin;
    private final File log;

    public ApiService(KkaegulCore plugin) {
        this.plugin = plugin;
        this.log = new File(plugin.getDataFolder(), "api-log.txt");
    }

    public List<String> personalActions(String number) { return plugin.getConfig().getStringList("api.personal." + number); }
    public List<String> serverActions(String number) { return plugin.getConfig().getStringList("api.server." + number); }

    /** @return 처리 결과 메시지 */
    public String personal(String playerName, String number, String source) {
        List<String> actions = personalActions(number);
        if (actions.isEmpty()) return "&c개인 API " + number + " 번이 config에 없어요";
        Player p = plugin.data().online(playerName);
        PlayerData d = p != null ? plugin.data().get(p) : plugin.data().findByName(playerName);
        if (d == null) return "&c플레이어를 찾을 수 없어요: " + playerName;
        write("P " + d.name + " " + number + " (" + source + ")");
        if (p == null && plugin.getConfig().getBoolean("api.queue-offline", true)) {
            d.pendingApi.add(number);
            d.markDirty();
            return "&e" + plugin.nickname().display(d) + " 님이 오프라인이라 접속 시 실행합니다 (" + number + ")";
        }
        execPersonal(d.uuid, number);
        return "&a개인 API " + number + " → " + plugin.nickname().display(d);
    }

    private void execPersonal(UUID id, String number) {
        Player p = Bukkit.getPlayer(id);
        String label = plugin.getConfig().getString("api.labels.personal." + number, number);
        if (p != null && plugin.getConfig().getBoolean("api.announce", true))
            plugin.ui().center(p, "&e[API] &f" + label, "");
        plugin.actions().runAll(id, personalActions(number), "API " + number);
    }

    public String server(String number, String source) { return server(number, source, null); }

    /** self = 후원 받은 섭주(명령이면 친 사람) — 앞에 all 이 없는 액션(box cosmetic 1 등)은 이 사람에게만 */
    public String server(String number, String source, java.util.UUID self) {
        List<String> actions = serverActions(number);
        if (actions.isEmpty()) return "&c섭주 API " + number + " 번이 config에 없어요";
        write("S " + number + " (" + source + ")");
        String label = plugin.getConfig().getString("api.labels.server." + number, number);
        if (plugin.getConfig().getBoolean("api.announce", true))
            plugin.announce("&8[&6섭주 API&8] &f" + label, true);
        plugin.actions().runAll(self, actions, "섭주 API " + number);
        return "&a섭주 API " + number + " 실행";
    }

    /** 접속 시 밀린 API 처리 */
    public void flushPending(Player p) {
        PlayerData d = plugin.data().get(p);
        if (d.pendingApi.isEmpty()) return;
        List<String> list = new ArrayList<>(d.pendingApi);
        d.pendingApi.clear();
        d.markDirty();
        plugin.msg(p, "api.pending", "count", list.size());
        long delay = 40;
        for (String n : list) {
            Bukkit.getScheduler().runTaskLater(plugin, () -> {
                if (!p.isOnline()) { d.pendingApi.add(n); d.markDirty(); return; }
                if (n.startsWith("@")) plugin.actions().run(p.getUniqueId(), n.substring(1), "밀린 API");
                else execPersonal(p.getUniqueId(), n);
            }, delay);
            delay += 20;
        }
    }

    private void write(String line) {
        String s = LocalDateTime.now() + " " + line + System.lineSeparator();
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            synchronized (this) {
                try (FileWriter w = new FileWriter(log, true)) {
                    w.write(s);
                } catch (IOException ignored) {
                }
            }
        });
    }
}
