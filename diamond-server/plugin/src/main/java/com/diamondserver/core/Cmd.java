package com.diamondserver.core;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;

/**
 * 명령어.
 * 플레이어: /상점 · /제출 [개수|전부] · /인출 [개수] · /입금 · /목표
 * 관리자: /다이아 리로드 | 지급 <닉> <보유|목표> <±개수> | 설정 <닉> <보유|목표> <개수> | 아이템 <닉> <id> [개수] | 위치 | 정보 <닉>
 */
final class Cmd implements CommandExecutor, TabCompleter {
    private final DiamondPlugin pl;

    Cmd(DiamondPlugin pl) { this.pl = pl; }

    private static void say(CommandSender s, String text) { s.sendMessage(Component.text(text, NamedTextColor.WHITE)); }

    private static long num(String a, long def) {
        try { return Long.parseLong(a.replace(",", "")); } catch (NumberFormatException e) { return def; }
    }

    /** 제출: n 이 0 이하면 낼 수 있는 만큼 전부 */
    void submit(Player p, long n) {
        Store.PData d = pl.store.get(p);
        if (d.goal <= 0) { pl.net.toast(p, "이미 목표를 모두 채웠습니다.", false); return; }
        if (d.held <= 0) { pl.net.toast(p, "제출할 보유 다이아가 없습니다.", false); return; }
        long real = pl.ledger.submit(p, n <= 0 ? Long.MAX_VALUE : n);
        Store.PData after = pl.store.get(p);
        if (after.goal > 0) pl.net.toast(p, "다이아 " + real + "개 제출 (남은 목표 " + after.goal + "개)", true);
    }

    @Override
    public boolean onCommand(CommandSender s, Command c, String label, String[] a) {
        String name = c.getName();
        if (name.equals("다이아")) return admin(s, a);
        if (name.equals("후원테스트")) return donateTest(s, a);
        if (!(s instanceof Player p)) { say(s, "게임 안에서만 쓸 수 있는 명령입니다."); return true; }
        switch (name) {
            case "상점" -> {
                if (pl.net.hasMod(p)) pl.net.open(p, "shop");
                else pl.net.toast(p, "상점 화면은 나니 다이아서버 런처로 접속해야 열립니다.", false);
            }
            case "우편함" -> {
                if (a.length > 0 && a[0].equals("받기")) { pl.mail.claimAll(p); return true; }
                int n = pl.mail.count(p.getUniqueId());
                if (pl.net.hasMod(p)) { pl.net.mail(p); pl.net.open(p, "mail"); }
                else if (n == 0) pl.net.toast(p, "받은 우편이 없습니다.", true);
                else {
                    for (Mail.Letter l : pl.mail.list(p.getUniqueId())) pl.net.toast(p, l.title + " · " + pl.mail.contentName(l), true);
                    pl.net.toast(p, "모두 받으려면 /우편함 받기", true);
                }
            }
            case "닉네임" -> {
                if (a.length == 0) pl.nicks.open(p, false);
                else pl.nicks.save(p, a[0]);
            }
            case "제출" -> submit(p, a.length == 0 || a[0].equals("전부") ? -1 : num(a[0], 0));
            case "인출" -> {
                long want = a.length == 0 ? 64 : num(a[0], 0);
                if (want <= 0) { pl.net.toast(p, "꺼낼 개수를 숫자로 적어 주세요. 예: /인출 32", false); return true; }
                long got = pl.ledger.withdraw(p, want);
                if (got <= 0) pl.net.toast(p, pl.store.get(p).held <= 0 ? "보유 다이아가 없습니다." : "인벤토리에 빈 칸이 없습니다.", false);
                else pl.net.toast(p, "다이아 " + got + "개를 꺼냈습니다.", true);
            }
            case "입금" -> {
                long got = pl.ledger.depositAll(p);
                if (got <= 0) pl.net.toast(p, "인벤토리에 다이아가 없습니다.", false);
                else pl.net.toast(p, "다이아 " + got + "개를 넣었습니다.", true);
            }
            case "목표" -> {
                Store.PData d = pl.store.get(p);
                pl.net.toast(p, "남은 목표 " + d.goal + "개 · 보유 " + d.held + "개", true);
            }
            default -> { }
        }
        return true;
    }

    /** /후원테스트 <개수> [후원자 이름] [대상 닉] — 진짜 후원이 온 것처럼 이벤트 줄에 세운다 */
    private boolean donateTest(CommandSender s, String[] a) {
        if (a.length == 0) { say(s, "/후원테스트 <개수> [후원자 이름] [대상 닉]  (대상을 안 적으면 자기 자신)"); return true; }
        int count = (int) num(a[0], -1);
        if (count <= 0) { say(s, "개수는 숫자로 적어 주세요. 예: /후원테스트 50"); return true; }
        String donor = a.length > 1 ? a[1] : "시험후원";
        Player t = a.length > 2 ? pl.nicks.find(a[2]) : (s instanceof Player p ? p : null);
        if (t == null) { say(s, a.length > 2 ? a[2] + "님은 접속해 있지 않습니다." : "콘솔에서는 대상 닉을 적어 주세요. 예: /후원테스트 50 시험후원 닉네임"); return true; }
        if (!pl.events.enqueue(t.getUniqueId(), pl.nick(t), donor, count)) { say(s, count + "개에 등록된 이벤트가 없습니다. (config.yml 의 events)"); return true; }
        say(s, "후원 " + count + "개 → " + pl.nick(t) + " (대기 " + pl.events.size() + "건)");
        return true;
    }

    private boolean admin(CommandSender s, String[] a) {
        if (a.length == 0) {
            say(s, "/다이아 리로드 | 지급 <닉> <보유|목표> <±개수> | 설정 <닉> <보유|목표> <개수> | 아이템 <닉> <id> [개수] | 닉 <닉> <새 닉|해제> | 우편 <닉|전체> 다이아 <개수> [제목] | 우편 <닉|전체> 아이템 <아이템> <개수> [제목] | 위치 | 정보 <닉> | 연동시험");
            return true;
        }
        switch (a[0]) {
            case "리로드" -> { pl.reload(); say(s, "설정을 다시 읽었습니다."); }
            case "닉" -> {
                if (a.length < 3) { say(s, "/다이아 닉 <닉 또는 계정> <새 닉네임|해제>"); return true; }
                Player t = pl.nicks.find(a[1]);
                if (t == null) { say(s, a[1] + "님은 접속해 있지 않습니다."); return true; }
                String err = pl.nicks.adminSet(t, a[2].equals("해제") ? null : a[2]);
                say(s, err != null ? err : pl.nicks.has(t) ? "닉네임을 " + pl.nick(t) + "(으)로 정했습니다." : "닉네임을 지웠습니다.");
            }
            case "우편" -> {
                // /다이아 우편 <대상|전체> 다이아 <개수> [제목]   또는   /다이아 우편 <대상|전체> 아이템 <전용 아이템 id 또는 게임 아이템> <개수> [제목]
                if (a.length < 4) { say(s, "/다이아 우편 <대상|전체> 다이아 <개수> [제목]  |  /다이아 우편 <대상|전체> 아이템 <아이템> <개수> [제목]"); return true; }
                java.util.List<Player> targets = new java.util.ArrayList<>();
                if (a[1].equals("전체")) targets.addAll(Bukkit.getOnlinePlayers());
                else { Player t = pl.nicks.find(a[1]); if (t != null) targets.add(t); }
                if (targets.isEmpty()) { say(s, a[1].equals("전체") ? "지금 접속한 사람이 없습니다." : a[1] + "님은 접속해 있지 않습니다."); return true; }
                boolean dia = a[2].equals("다이아");
                if (!dia && !a[2].equals("아이템")) { say(s, "다이아 또는 아이템 중 하나를 적어 주세요."); return true; }
                int at = dia ? 3 : 4;
                if (a.length <= at) { say(s, "개수를 적어 주세요."); return true; }
                int n = (int) num(a[at], -1);
                if (n <= 0) { say(s, "개수는 1 이상의 숫자로 적어 주세요."); return true; }
                String title = a.length > at + 1 ? String.join(" ", java.util.Arrays.copyOfRange(a, at + 1, a.length)) : "선물";
                String kind = "diamonds", key = "";
                if (!dia) {
                    if (Items.IDS.contains(a[3])) { kind = "item"; key = a[3]; }
                    else {
                        org.bukkit.Material m = org.bukkit.Material.matchMaterial(a[3]);
                        if (m == null || !m.isItem()) { say(s, "그런 아이템이 없습니다: " + a[3]); return true; }
                        kind = "material"; key = m.name();
                    }
                }
                for (Player t : targets) pl.mail.send(t.getUniqueId(), title, "운영자", kind, key, n);
                say(s, targets.size() + "명에게 우편을 보냈습니다.");
            }
            case "연동시험" -> {
                // 접속한 사람 없이도 모델·NPC 연동이 실제로 되는지 확인: 시작 지점에 잠깐 세웠다가 치운다
                org.bukkit.Location at = pl.spawn();
                at.getChunk().load();
                StringBuilder okList = new StringBuilder(), bad = new StringBuilder();
                for (String id : new String[]{"target_marker", "diamond_crow", "ride_rocket", "giant_anvil", "stun_stars", "curse_mark", "disco_ball",
                        "diamond_pinata", "diamond_cloud", "diamond_meteor", "golden_crown", "diamond_fountain", "grim_reaper"}) {
                    Models.Prop p = pl.models.spawn(at, id, "idle");
                    (p.hasModel() ? okList : bad).append(' ').append(id);
                    p.play("spawn");
                    p.removeLater(20);
                }
                say(s, "모델 성공:" + (okList.length() == 0 ? " 없음" : okList));
                say(s, "모델 실패:" + (bad.length() == 0 ? " 없음" : bad));
                say(s, "AI 플레이어: " + pl.miner.selfTest(at));
            }
            case "지급", "설정" -> {
                if (a.length < 4) { say(s, "/다이아 " + a[0] + " <닉> <보유|목표> <개수>"); return true; }
                Player t = pl.nicks.find(a[1]);
                if (t == null) { say(s, a[1] + "님은 접속해 있지 않습니다."); return true; }
                long n = num(a[3], Long.MIN_VALUE);
                if (n == Long.MIN_VALUE) { say(s, "개수는 숫자로 적어 주세요."); return true; }
                Store.PData d = pl.store.get(t);
                boolean held = a[2].equals("보유");
                if (!held && !a[2].equals("목표")) { say(s, "보유 또는 목표 중 하나를 적어 주세요."); return true; }
                long delta = a[0].equals("지급") ? n : n - (held ? d.held : d.goal);
                if (held) pl.ledger.addHeld(t, delta); else pl.ledger.addGoal(t, delta);
                d = pl.store.get(t);
                say(s, pl.nick(t) + ": 남은 목표 " + d.goal + " · 보유 " + d.held);
            }
            case "아이템" -> {
                if (a.length < 3 || !Items.IDS.contains(a[2])) { say(s, "/다이아 아이템 <닉> <" + String.join("|", Items.IDS) + "> [개수]"); return true; }
                Player t = pl.nicks.find(a[1]);
                if (t == null) { say(s, a[1] + "님은 접속해 있지 않습니다."); return true; }
                int n = (int) Math.max(1, Math.min(64, a.length > 3 ? num(a[3], 1) : 1));
                Items.give(t, pl.items.make(a[2], n));
                say(s, pl.nick(t) + "님에게 " + pl.items.nameOf(a[2]) + " " + n + "개 지급");
            }
            case "위치" -> {
                if (!(s instanceof Player p)) { say(s, "게임 안에서만 쓸 수 있는 명령입니다."); return true; }
                pl.setSpawn(p.getLocation());
                say(s, "귀환권이 돌아올 시작 지점을 지금 서 있는 곳으로 정했습니다.");
            }
            case "정보" -> {
                Player t = a.length > 1 ? pl.nicks.find(a[1]) : null;
                if (t == null) { say(s, "/다이아 정보 <접속 중인 닉>"); return true; }
                Store.PData d = pl.store.get(t);
                say(s, pl.nick(t) + ": 남은 목표 " + d.goal + " · 보유 " + d.held + " · 달성 " + (d.done ? "예" : "아니오") + " · 모드 " + (pl.net.hasMod(t) ? "있음" : "없음"));
            }
            default -> say(s, "모르는 명령입니다. /다이아 만 입력하면 사용법이 나옵니다.");
        }
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender s, Command c, String label, String[] a) {
        List<String> out = new ArrayList<>();
        if (c.getName().equals("제출") && a.length == 1) out.add("전부");
        if (c.getName().equals("후원테스트")) {
            if (a.length == 1) out.addAll(List.of("10", "50", "51", "100", "300", "500", "510", "1000", "2000", "3000", "4444"));
            else if (a.length == 3) for (Player p : Bukkit.getOnlinePlayers()) out.add(pl.nick(p));
        }
        if (!c.getName().equals("다이아")) return filter(out, a);
        if (a.length == 1) out.addAll(List.of("리로드", "지급", "설정", "아이템", "위치", "정보", "닉", "우편", "연동시험"));
        else if (a.length == 2 && List.of("지급", "설정", "아이템", "정보", "닉").contains(a[0])) for (Player p : Bukkit.getOnlinePlayers()) out.add(pl.nick(p));
        else if (a.length == 3 && (a[0].equals("지급") || a[0].equals("설정"))) out.addAll(List.of("보유", "목표"));
        else if (a.length == 3 && a[0].equals("아이템")) out.addAll(Items.IDS);
        return filter(out, a);
    }

    private static List<String> filter(List<String> all, String[] a) {
        String last = a.length == 0 ? "" : a[a.length - 1];
        List<String> out = new ArrayList<>();
        for (String x : all) if (x.startsWith(last)) out.add(x);
        return out;
    }
}
