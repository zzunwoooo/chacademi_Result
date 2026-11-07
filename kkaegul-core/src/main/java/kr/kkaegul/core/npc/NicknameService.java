package kr.kkaegul.core.npc;

import kr.kkaegul.core.KkaegulCore;
import kr.kkaegul.core.data.PlayerData;
import kr.kkaegul.core.util.Json;
import kr.kkaegul.core.util.Txt;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * 한글 닉네임.
 *  - 창: screen "nickname" (렌더러) / 렌더러 없으면 채팅 입력
 *  - 규칙: config nickname.min~max 글자, 완성형 한글·영문·숫자·_ 만, 금칙어, 다른 사람과 중복 불가
 *  - 적용: 채팅 이름, 탭 리스트(칭호 포함), 랭킹/방송 문구.
 *    머리 위 이름표는 UnlimitedNametags + PlaceholderAPI(%kkaegul_display%) 가 담당 (모드 이름표는 nickname.client-nameplate: true 일 때만)
 * 액션: nickname.save name=..  /  nickname.check name=..
 */
public final class NicknameService {
    private final KkaegulCore plugin;

    public NicknameService(KkaegulCore plugin) { this.plugin = plugin; }

    public int min() { return plugin.getConfig().getInt("nickname.min", 2); }
    public int max() { return plugin.getConfig().getInt("nickname.max", 8); }

    /** 표시 이름: 닉네임이 있으면 닉네임, 없으면 계정명 */
    public String display(PlayerData d) { return d.nickname == null || d.nickname.isBlank() ? d.name : d.nickname; }

    public void open(Player p, boolean first) {
        PlayerData d = plugin.data().get(p);
        if (!first && !d.nickname.isBlank() && !plugin.getConfig().getBoolean("nickname.allow-change", true)
                && !p.hasPermission("kkaegul.admin")) {
            plugin.ui().error(p, "&f{e:error} &c닉네임은 한 번만 정할 수 있어요.");
            return;
        }
        Json data = Json.obj().put("account", p.getName()).put("nickname", d.nickname).put("min", min()).put("max", max())
                .put("first", first).put("title", plugin.titles().display(d))
                .put("rule", Txt.fmt(plugin.getConfig().getString("nickname.rule-text", "한글·영문·숫자 {min}~{max}글자"), "min", min(), "max", max()));
        if (plugin.ui().send(p, Json.obj().put("type", "open").put("screen", "nickname").put("data", data))) return;
        plugin.npcs().starter().openChatName(p); // 렌더러 없음 → 채팅 입력
    }

    /** @return 오류 문구, 통과면 null */
    public String check(Player p, String raw) {
        String n = raw == null ? "" : Txt.jamo(Txt.strip(Txt.color(raw)).trim());
        int len = n.codePointCount(0, n.length());
        if (len < min() || len > max()) return min() + "~" + max() + "글자로 정해 주세요";
        String regex = plugin.getConfig().getString("nickname.pattern", "^[가-힣a-zA-Z0-9_]+$");
        if (!Pattern.compile(regex).matcher(n).matches()) return "한글(완성형)·영문·숫자·_ 만 쓸 수 있어요";
        String lower = n.toLowerCase(Locale.ROOT);
        for (String bad : plugin.getConfig().getStringList("nickname.banned"))
            if (!bad.isBlank() && lower.contains(bad.toLowerCase(Locale.ROOT))) return "쓸 수 없는 단어가 들어 있어요";
        for (PlayerData o : plugin.data().all()) {
            if (o.uuid.equals(p.getUniqueId())) continue;
            if (n.equalsIgnoreCase(o.nickname) || n.equalsIgnoreCase(o.name)) return "이미 누가 쓰고 있는 이름이에요";
        }
        return null;
    }

    public void onCheck(Player p, String name) {
        String err = check(p, name);
        plugin.ui().update(p, "nickname", Json.obj().put("checked", name == null ? "" : name).put("ok", err == null).put("error", err == null ? "" : err));
    }

    public boolean save(Player p, String name) {
        String err = check(p, name);
        if (err != null) {
            if (!plugin.ui().send(p, Json.obj().put("type", "update").put("screen", "nickname").put("data", Json.obj().put("error", err).put("ok", false))))
                plugin.ui().error(p, "&f{e:error} &c" + err);
            return false;
        }
        PlayerData d = plugin.data().get(p);
        boolean first = !d.profileDone;
        d.nickname = Txt.jamo(Txt.strip(Txt.color(name)).trim());
        d.markDirty();
        plugin.titles().applyName(p);
        pushAll();
        plugin.ui().close(p, "nickname");
        plugin.ui().center(p, "&a" + d.nickname + "&f 님, 반가워요!", first ? "&7이제 갑옷 NPC와 부동산업자를 찾아가 보세요" : "&7닉네임을 바꿨어요");
        if (first && plugin.getConfig().getBoolean("nickname.first-completes-profile", true)) plugin.npcs().starter().finishProfile(p);
        return true;
    }

    // ───────── 머리 위 이름표 (렌더러) ─────────

    private Json namesPayload() {
        Json map = Json.obj();
        for (Player o : Bukkit.getOnlinePlayers()) {
            PlayerData d = plugin.data().get(o);
            map.put(o.getUniqueId().toString(), Json.obj().put("name", display(d)).put("title", plugin.titles().display(d)));
        }
        return Json.obj().put("type", "names").put("map", map).put("nameplate", plugin.getConfig().getBoolean("nickname.client-nameplate", false));
    }

    public void pushAll() {
        if (!plugin.isEnabled()) return;
        Json payload = namesPayload();
        for (Player o : Bukkit.getOnlinePlayers()) plugin.ui().send(o, payload);
    }

    /** 접속 직후엔 채널 등록이 늦을 수 있어 조금 뒤에 보낸다 */
    public void pushLater() { Bukkit.getScheduler().runTaskLater(plugin, this::pushAll, 30L); }

    public List<String> namesForSelect(Player except) {
        List<String> out = new java.util.ArrayList<>();
        for (Player o : Bukkit.getOnlinePlayers()) if (!o.equals(except)) out.add(o.getName());
        return out;
    }
}
