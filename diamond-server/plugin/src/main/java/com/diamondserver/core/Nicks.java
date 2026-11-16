package com.diamondserver.core;

import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;

import java.io.File;
import java.text.Normalizer;
import java.util.Locale;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * 한글 닉네임.
 *  - 규칙(config nickname): min~max 글자, 완성형 한글·영문·숫자·_ 만, 금칙어, 다른 사람의 닉네임·계정 이름과 중복 불가
 *  - 정하는 법: 닉네임 창(모드) 또는 /닉네임 <이름>. 처음 접속했는데 닉네임이 없으면 창이 자동으로 뜬다.
 *  - 쓰이는 곳: 채팅 이름, 탭 목록, 머리 위 이름표(모드), 접속·퇴장·사망 글, 모든 알림 글, 명령어 자동완성.
 *    계정(영문) 이름은 화면에 나오지 않는다.
 */
final class Nicks {
    private final DiamondPlugin pl;

    Nicks(DiamondPlugin pl) { this.pl = pl; }

    private int min() { return pl.getConfig().getInt("nickname.min", 2); }
    private int max() { return pl.getConfig().getInt("nickname.max", 8); }

    private String rule() {
        return pl.getConfig().getString("nickname.rule-text", "한글·숫자 {min}~{max}글자")
                .replace("{min}", String.valueOf(min())).replace("{max}", String.valueOf(max()));
    }

    /** 화면에 쓸 이름: 닉네임이 있으면 닉네임, 없으면 계정 이름 */
    String of(Player p) {
        String n = pl.store.get(p).nick;
        return n == null || n.isBlank() ? p.getName() : n;
    }

    boolean has(Player p) {
        String n = pl.store.get(p).nick;
        return n != null && !n.isBlank();
    }

    private static String clean(String raw) {
        return raw == null ? "" : Normalizer.normalize(raw.strip(), Normalizer.Form.NFC);
    }

    /** 규칙 검사. 통과하면 null, 아니면 이유 */
    String check(UUID self, String raw) {
        String n = clean(raw);
        int len = n.codePointCount(0, n.length());
        if (len < min() || len > max()) return min() + "~" + max() + "글자로 정해 주세요.";
        String regex = pl.getConfig().getString("nickname.pattern", "^[가-힣0-9]+$");
        if (!Pattern.compile(regex).matcher(n).matches()) return "완성된 한글과 숫자만 쓸 수 있습니다.";
        String lower = n.toLowerCase(Locale.ROOT);
        for (String bad : pl.getConfig().getStringList("nickname.banned"))
            if (!bad.isBlank() && lower.contains(bad.toLowerCase(Locale.ROOT))) return "쓸 수 없는 단어가 들어 있습니다.";
        if (taken(self, n)) return "이미 다른 사람이 쓰고 있는 이름입니다.";
        return null;
    }

    /** 다른 사람의 닉네임이나 계정 이름과 같은지 (접속해 있지 않은 사람 포함: 장부 파일을 전부 본다) */
    private boolean taken(UUID self, String n) {
        File[] files = new File(pl.getDataFolder(), "players").listFiles((d, name) -> name.endsWith(".yml"));
        if (files != null) for (File f : files) {
            if (f.getName().equals(self + ".yml")) continue;
            YamlConfiguration y = YamlConfiguration.loadConfiguration(f);
            if (n.equalsIgnoreCase(y.getString("nick", "")) || n.equalsIgnoreCase(y.getString("name", ""))) return true;
        }
        for (Player o : Bukkit.getOnlinePlayers()) {
            if (o.getUniqueId().equals(self)) continue;
            if (n.equalsIgnoreCase(o.getName()) || n.equalsIgnoreCase(pl.store.get(o).nick)) return true;
        }
        return false;
    }

    /** 닉네임을 정한다. 성공하면 true (실패 이유는 창 또는 알림으로 알려 준다) */
    boolean save(Player p, String raw) {
        Store.PData d = pl.store.get(p);
        if (has(p) && !pl.getConfig().getBoolean("nickname.allow-change", true) && !p.hasPermission("diamondserver.admin")) {
            fail(p, "닉네임은 한 번만 정할 수 있습니다.");
            return false;
        }
        String err = check(p.getUniqueId(), raw);
        if (err != null) { fail(p, err); return false; }
        boolean firstTime = !has(p);
        d.nick = clean(raw);
        d.dirty = true;
        pl.store.save(d);
        apply(p);
        sendNames();
        pl.net.closeNick(p);
        // 처음 정한 사람은 이제 접속 글을 낸다 (접속할 때는 닉네임이 없어서 안 냈음)
        if (firstTime) Bukkit.broadcast(Component.text(d.nick + "님이 접속했습니다.", net.kyori.adventure.text.format.NamedTextColor.YELLOW));
        pl.net.toast(p, "닉네임을 " + d.nick + "(으)로 정했습니다.", true);
        return true;
    }

    private void fail(Player p, String reason) {
        if (pl.net.hasMod(p)) pl.net.nickError(p, reason); else pl.net.toast(p, reason, false);
    }

    /** 관리자: 닉네임을 강제로 정하거나(검사 포함) 지운다 */
    String adminSet(Player t, String raw) {
        Store.PData d = pl.store.get(t);
        if (raw == null) d.nick = "";
        else {
            String err = check(t.getUniqueId(), raw);
            if (err != null) return err;
            d.nick = clean(raw);
        }
        d.dirty = true;
        pl.store.save(d);
        apply(t);
        sendNames();
        return null;
    }

    /** 채팅 이름·탭 목록 이름에 닉네임을 적용한다 */
    void apply(Player p) {
        Component name = Component.text(of(p));
        p.displayName(name);
        p.playerListName(name);
    }

    /** 닉네임 창을 연다. first = 처음 정하는 것(닫을 수 없음). 모드가 없으면 명령어 안내 */
    void open(Player p, boolean first) {
        if (pl.net.hasMod(p)) pl.net.openNick(p, first, pl.store.get(p).nick, rule(), max());
        else pl.net.toast(p, "닉네임을 정해 주세요: /닉네임 <이름>  (" + rule() + ")", true);
    }

    /** 처음 접속했는데 닉네임이 없으면 창을 띄운다 */
    void promptIfNeeded(Player p) {
        if (!has(p) && pl.getConfig().getBoolean("nickname.force-on-join", true)) open(p, true);
    }

    /** 모든 사람의 닉네임을 모드에 보낸다 (머리 위 이름표용) */
    void sendNames() {
        for (Player o : Bukkit.getOnlinePlayers()) if (pl.net.hasMod(o)) pl.net.names(o);
    }

    /** 닉네임 또는 계정 이름으로 접속 중인 사람을 찾는다 */
    Player find(String nameOrNick) {
        if (nameOrNick == null) return null;
        for (Player o : Bukkit.getOnlinePlayers())
            if (nameOrNick.equalsIgnoreCase(pl.store.get(o).nick) || nameOrNick.equalsIgnoreCase(o.getName())) return o;
        return null;
    }
}
