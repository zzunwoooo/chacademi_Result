package kr.kkaegul.core.command;

import kr.kkaegul.core.KkaegulCore;
import kr.kkaegul.core.data.PlayerData;
import kr.kkaegul.core.item.CustomItem;
import kr.kkaegul.core.minigame.Minigame;
import kr.kkaegul.core.util.Json;
import kr.kkaegul.core.util.Txt;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.PluginCommand;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 모든 명령어. 플레이어 명령은 UI가 생기기 전까지의 대체 진입점이기도 하다. */
public final class Commands implements CommandExecutor, TabCompleter {
    private final KkaegulCore plugin;
    private final Actions actions;

    public Commands(KkaegulCore plugin, Actions actions) {
        this.plugin = plugin;
        this.actions = actions;
        for (String c : List.of("kg", "kapi", "ksapi", "mailbox", "submit", "randomsubmit", "sell", "buycoin", "farmprice",
                "gacha", "fortune", "wild", "jailexit", "title", "hall", "playtime", "trash", "me", "usertp", "gauge", "profile", "withdraw", "deposit", "nickname", "frogjump", "frograce", "poker", "seotda", "discordlink", "kgnotice", "kgwhisper", "kgreply", "kghelp", "kgaccept", "kgtp", "kgsummon", "coupon", "couponadmin")) {
            PluginCommand pc = plugin.getCommand(c);
            if (pc == null) { plugin.getLogger().warning("plugin.yml 에 명령어 없음: " + c); continue; }
            pc.setExecutor(this);
            pc.setTabCompleter(this);
        }
    }

    private void say(CommandSender s, String msg) { s.sendMessage(Txt.color(msg)); }

    @Override
    public boolean onCommand(CommandSender s, Command cmd, String label, String[] a) {
        String name = cmd.getName().toLowerCase();
        try {
            switch (name) {
                case "kg" -> admin(s, a);
                case "kapi" -> {
                    if (a.length < 2) { say(s, "&e/kapi <플레이어> <번호>"); return true; }
                    say(s, plugin.api().personal(a[0], a[1], s.getName()));
                }
                case "ksapi" -> {
                    if (a.length < 1) { say(s, "&e/ksapi <번호>"); return true; }
                    say(s, plugin.api().server(a[0], s.getName()));
                }
                case "kgnotice" -> {
                    if (!s.hasPermission("kkaegul.admin")) { say(s, "&c권한이 없어요."); return true; }
                    if (a.length == 0) { say(s, "&e/공지 <할 말>"); return true; }
                    plugin.chat().notice(String.join(" ", a));
                }
                default -> {
                    if (!(s instanceof Player p)) { say(s, "&c플레이어만 쓸 수 있어요."); return true; }
                    player(p, name, a);
                }
            }
        } catch (RuntimeException e) {
            say(s, "&c오류: " + e.getMessage());
            plugin.getLogger().warning("명령어 오류 /" + label + " " + String.join(" ", a) + ": " + e);
        }
        return true;
    }

    // ───────── 플레이어 명령 ─────────
    private void player(Player p, String name, String[] a) {
        switch (name) {
            case "coupon" -> plugin.coupons().use(p, a.length > 0 ? a[0] : "");
            case "couponadmin" -> plugin.coupons().admin(p, a);
            case "kgtp", "kgsummon" -> {
                boolean summon = name.equals("kgsummon");
                if (a.length < 1) { plugin.ui().error(p, summon ? "&c/소환 <닉네임>" : "&c/텔포 <닉네임>"); return; }
                Player t = plugin.data().online(a[0]);
                if (t == null || t.equals(p)) { plugin.ui().error(p, "&c접속 중인 다른 유저 닉네임을 입력하세요."); return; }
                if (summon) {
                    plugin.harass().saveBack(t);
                    t.teleport(p.getLocation());
                    say(p, Txt.notice("&f" + plugin.displayName(t) + "님을 내 위치로 소환했어요."));
                } else {
                    plugin.harass().saveBack(p);
                    p.teleport(t.getLocation());
                    say(p, Txt.notice("&f" + plugin.displayName(t) + "님에게 이동했어요."));
                }
            }
            case "mailbox" -> {
                // 창이 있으면 우편함 창, 없으면 바로 받기
                boolean list = a.length > 0 && (a[0].equals("목록") || a[0].equalsIgnoreCase("list"));
                if ((list || a.length == 0) && plugin.ui().hasRenderer(p)) plugin.ui().open(p, "mail", plugin.mail().snapshot(p.getUniqueId()));
                else plugin.mail().claimAll(p);
            }
            case "submit" -> {
                if (a.length > 0 && (a[0].equals("랭킹") || a[0].equalsIgnoreCase("board"))) plugin.ui().open(p, "donation_board", plugin.submit().donorBoard());
                else if (a.length > 0 && (a[0].equals("전부") || a[0].equalsIgnoreCase("all"))) plugin.submit().normal(p, 0);
                else if (a.length > 0) {
                    try { plugin.submit().normal(p, Math.max(1, Long.parseLong(a[0]))); }
                    catch (NumberFormatException e) { plugin.ui().error(p, "&c/제출 [개수|전부|랭킹]"); }
                }
                else plugin.submit().open(p);   // 모드 있으면 수량 고르는 제출 창, 없으면 전부 제출
            }
            case "randomsubmit" -> plugin.submit().random(p);
            case "withdraw" -> {
                long n = 0;
                if (a.length > 0) { try { n = Long.parseLong(a[0]); } catch (NumberFormatException e) { say(p, "&e/인출 [개수] &7(비우면 전부)"); return; } }
                plugin.wallet().withdraw(p, n);
            }
            case "deposit" -> plugin.wallet().depositInventory(p);
            case "sell" -> {
                if (a.length == 0 && plugin.economy().openShop(p, "sell")) return;
                if (a.length > 0 && (a[0].equals("손") || a[0].equalsIgnoreCase("hand"))) plugin.economy().sellHand(p);
                else plugin.economy().sellAll(p);
            }
            case "buycoin" -> {
                if (a.length > 0 && (a[0].equals("다이아") || a[0].equalsIgnoreCase("diamond"))) {
                    plugin.economy().exchangeDiamond(p, a.length > 1 ? Actions.parse(a[1], 1) : 1);
                    return;
                }
                int n = a.length > 0 ? Actions.parse(a[0], 1) : 1;
                plugin.economy().buyCoins(p, Math.max(1, Math.min(n, plugin.getConfig().getInt("economy.coin.max-per-buy", 100))));
            }
            case "frogjump" -> {
                if (a.length == 0) plugin.frogJump().jump(p);
                else if (a[0].equals("시작") || a[0].equalsIgnoreCase("start")) plugin.frogJump().start(p, a.length > 1 ? Actions.parse(a[1], 0) : 0);
                else if (a[0].equals("그만") || a[0].equalsIgnoreCase("stop")) plugin.frogJump().cashout(p);
                else plugin.frogJump().open(p);
            }
            case "discordlink" -> plugin.discord().link(p);
            case "kgwhisper" -> {
                if (a.length < 2) { plugin.ui().error(p, "&c/귓 <닉네임> <할 말>"); return; }
                plugin.chat().whisper(p, a[0], String.join(" ", java.util.Arrays.copyOfRange(a, 1, a.length)));
            }
            case "kgreply" -> {
                if (a.length < 1) { plugin.ui().error(p, "&c/답 <할 말>"); return; }
                plugin.chat().reply(p, String.join(" ", a));
            }
            case "kghelp" -> {
                if (a.length < 1) { plugin.ui().error(p, "&c/도움 <운영자에게 할 말>"); return; }
                plugin.chat().help(p, String.join(" ", a));
            }
            case "kgaccept" -> plugin.minigames().accept(p);
            case "poker", "seotda" -> {
                var g = plugin.minigames().game(name);
                boolean ok = g instanceof kr.kkaegul.core.casino.holdem.PokerGame pg && name.equals("poker") ? pg.reopen(p)
                        : g instanceof kr.kkaegul.core.casino.seotda.SeotdaGame sg && name.equals("seotda") && sg.reopen(p);
                if (!ok) say(p, "&7지금 참가 중인 " + (name.equals("poker") ? "포커" : "섯다") + " 테이블이 없어요. &8(미니게임으로 초대되면 자동으로 열려요)");
            }
            case "frograce" -> {
                if (a.length >= 3 && (a[0].equals("걸기") || a[0].equalsIgnoreCase("bet"))) plugin.frogRace().bet(p, Actions.parse(a[1], 0), Actions.parse(a[2], 0));
                else plugin.frogRace().open(p);
            }
            case "farmprice" -> {
                // 시세 전용 창은 아직 없음 → 채팅으로 (창이 생기면 renderer-screens 에 farm 추가)
                // 창이 있으면 상점 판매 탭(작물 시세·오르내림 화살표가 보임), 없으면 채팅
                if (plugin.ui().hasRenderer(p) && plugin.economy().openShop(p, "sell")) return;
                StringBuilder sb = new StringBuilder("&2[작물 시세] ");
                plugin.global().farmPrices.forEach((k, v) -> sb.append("&f").append(kr.kkaegul.core.economy.EconomyService.NAMES.getOrDefault(k, k)).append(" &b").append(kr.kkaegul.core.economy.EconomyService.fmt(v)).append("  "));
                say(p, sb.toString() + "&7(다이아, 1시간마다 바뀜)");
            }
            case "gacha" -> {
                // /가챠 [1|2|3] → 기계 창 열기 (바로 돌리지 않음). 창이 없는 클라이언트는 /가챠 <번호> 돌리기
                String m = a.length > 0 ? a[0] : "1";
                if (plugin.gacha().machine(m) == null) { say(p, "&c가챠 기계는 1, 2, 3번이 있어요."); return; }
                boolean spin = a.length > 1 && (a[1].equals("돌리기") || a[1].equalsIgnoreCase("spin"));
                if (spin) { plugin.gacha().spin(p, m); return; }
                if (plugin.ui().hasRenderer(p)) plugin.ui().open(p, "gacha", plugin.gacha().info(p, m));
                else {
                    Json i = plugin.gacha().info(p, m);
                    say(p, "&d[가챠 " + m + "번] &f" + i.map().get("label") + " &7· 1회 깨굴코인 &e" + i.map().get("cost") + " &7· 보유 &e" + i.map().get("coins"));
                    say(p, "&7곡괭이를 손에 들고 &f/가챠 " + m + " 돌리기 &7로 돌려요.");
                }
            }
            case "fortune" -> plugin.npcs().fortune().use(p);
            case "wild" -> {
                if (a.length > 0 && (a[0].equals("랜덤") || a[0].equalsIgnoreCase("random"))) plugin.npcs().wild().randomTp(p);
                else plugin.npcs().wild().enter(p);
            }
            case "jailexit" -> {
                String sub = a.length > 0 ? a[0] : "";
                switch (sub) {
                    case "로비", "lobby" -> plugin.jail().exit(p, "lobby");
                    case "복귀", "return" -> plugin.jail().exit(p, "return");
                    case "게임", "game" -> plugin.jail().playGame(p, false);
                    default -> plugin.jail().openExit(p);
                }
            }
            case "title" -> {
                if (a.length == 0) plugin.titles().open(p);
                else if (!plugin.titles().select(p, a[0])) say(p, "&c가지고 있지 않은 칭호예요.");
                else say(p, "&a칭호를 바꿨어요.");
            }
            case "hall" -> plugin.ranking().open(p);
            case "playtime" -> {
                String sub = a.length > 0 ? a[0] : "";
                if (sub.equals("받기") || sub.equalsIgnoreCase("claim")) plugin.rewards().claim(p);
                else if (sub.equals("교환") || sub.equalsIgnoreCase("exchange")) plugin.rewards().exchange(p);
                else plugin.rewards().open(p);
            }
            case "trash" -> plugin.npcs().run(p, "trash");
            case "me" -> {
                PlayerData d = plugin.data().get(p);
                if (!plugin.menu().open(p, "me")) {
                    say(p, "&6[내 정보] &f보유 다이아 &b" + Txt.num(d.held) + " &7/ &f남은(목표) 다이아 &b" + Txt.num(d.debt) + " &7(누적 증가 " + Txt.num(d.totalIncreased) + ", 제출 " + Txt.num(d.totalSubmitted) + ")");
                    say(p, "&f돈 &a" + Txt.num(d.money) + " &7· &f코인 &e" + Txt.num(d.coins) + " &7· &f운빨 &6" + plugin.gauge().value() + " &7(" + plugin.gauge().tierName(d) + ")");
                    say(p, "&f깜빵 " + d.jailCount + "회 · 파괴 " + d.pickaxeBreaks + "회 · 즉사 " + d.instantDeaths + "회 · 최고 수치 " + Txt.num(d.bestPickaxe));
                    if (plugin.clock().protectionActive()) say(p, "&a초반 보호 중: 남은 다이아 " + plugin.diamonds().threshold() + "개 미만은 감소가 적용되지 않아요.");
                }
            }
            case "usertp" -> {
                if (a.length < 1) { say(p, "&e/유저텔포 <이름>"); return; }
                actions.userTp(p, a[0]);
            }
            case "gauge" -> {
                if (a.length < 1) { say(p, "&e/게이지 <위|아래> &7(게이지 변동권 사용)"); return; }
                actions.gaugeTicket(p, a[0].equals("위") || a[0].equalsIgnoreCase("up") ? "up" : "down");
            }
            case "nickname" -> plugin.nickname().open(p, !plugin.data().get(p).profileDone);
            case "profile" -> {
                String sub = a.length > 0 ? a[0] : "";
                String rest = a.length > 1 ? String.join(" ", Arrays.copyOfRange(a, 1, a.length)) : "";
                switch (sub) {
                    case "이름", "name" -> { String before = plugin.data().get(p).nickname; plugin.npcs().starter().saveProfile(p, rest, null); String now = plugin.data().get(p).nickname; if (now != null && !now.equals(before)) say(p, "&a이름: " + now); }
                    case "보이스", "voice" -> { plugin.npcs().starter().saveProfile(p, null, rest); say(p, "&a보이스: " + rest); }
                    case "완료", "done" -> plugin.npcs().starter().finishProfile(p);
                    default -> plugin.npcs().starter().openProfile(p);
                }
            }
            default -> { }
        }
    }

    // ───────── 관리자 /kg ─────────
    private void admin(CommandSender s, String[] a) {
        if (!s.hasPermission("kkaegul.admin")) { say(s, "&c권한이 없어요."); return; }
        if (a.length == 0) { help(s); return; }
        a = translate(a);
        String sub = a[0].toLowerCase();
        Player self = s instanceof Player pp ? pp : null;
        switch (sub) {
            case "reload" -> { plugin.reloadAll(); say(s, "&a설정을 다시 불러왔어요."); }
            case "save" -> { plugin.data().saveAll(); plugin.global().save(); say(s, "&a저장 완료"); }
            case "setloc" -> {
                if (self == null || a.length < 2) { say(s, "&e/kg setloc <이름> (게임 안에서)"); return; }
                plugin.places().set(a[1], self.getLocation());
                say(s, "&a위치 저장: " + a[1]);
            }
            case "delloc" -> { if (a.length > 1) say(s, plugin.places().remove(a[1]) ? "&a삭제: " + a[1] : "&c없음: " + a[1]); }
            case "locs" -> say(s, "&e위치: &f" + String.join(", ", plugin.places().names()));
            case "tp" -> { if (self != null && a.length > 1) plugin.places().tp(self, a[1]); }
            case "npc" -> npc(s, self, a);
            case "api" -> { if (a.length >= 3) say(s, plugin.api().personal(a[1], a[2], s.getName())); else say(s, "&e/kg api <플레이어> <번호>"); }
            case "sapi" -> { if (a.length >= 2) say(s, plugin.api().server(a[1], s.getName())); else say(s, "&e/kg sapi <번호>"); }
            case "run" -> {
                if (a.length < 3) { say(s, "&e/kg run <플레이어|*> <액션...>  예) /kg run 준우 roulette increase"); return; }
                String action = String.join(" ", Arrays.copyOfRange(a, 2, a.length));
                if (a[1].equals("*")) plugin.actions().run(null, action, "관리자");
                else {
                    PlayerData d = target(s, a[1]);
                    if (d != null) plugin.actions().run(d.uuid, action, "관리자");
                }
                say(s, "&a실행: " + action);
            }
            case "giveall" -> {
                if (self == null) { say(s, "&c게임 안에서 손에 아이템을 들고 쓰세요."); return; }
                int n = a.length > 1 ? Actions.parse(a[1], 0) : 0;
                say(s, plugin.chat().giveAll(self, n));
            }
            case "reset" -> {
                if (a.length < 3) { say(s, "&e/깨굴 초기화 <플레이어> <장비|부동산|운세|닉네임|전부>  &7(전부 = 장비·부동산·운세)"); return; }
                PlayerData d = target(s, a[1]);
                if (d == null) return;
                String w = a[2];
                boolean all = w.equals("전부") || w.equals("all");
                List<String> done = new ArrayList<>();
                if (all || w.equals("장비") || w.equals("armor")) { d.armorRolled = false; d.starterClaimed = false; done.add("장비"); }
                if (all || w.equals("부동산") || w.equals("realtor")) { d.realtorClaimed = false; done.add("부동산"); }
                if (all || w.equals("운세") || w.equals("fortune")) { d.fortuneDay = ""; d.fortuneUses = 0; done.add("운세"); }
                if (w.equals("닉네임") || w.equals("nickname")) { d.profileDone = false; done.add("닉네임(다음 접속 때 닉네임 창)"); }
                if (done.isEmpty()) { say(s, "&c장비 | 부동산 | 운세 | 닉네임 | 전부 중에서 고르세요."); return; }
                d.markDirty();
                say(s, "&a" + a[1] + " 초기화: &f" + String.join(", ", done));
            }
            case "diamond" -> {
                if (a.length < 4) { say(s, "&e/kg diamond <플레이어> <set|add|sub|force-sub> <수>"); return; }
                PlayerData d = target(s, a[1]);
                if (d == null) return;
                long n = Long.parseLong(a[3]);
                switch (a[2]) {
                    case "set" -> plugin.diamonds().set(d, n);
                    case "add" -> plugin.diamonds().increase(d, n, "관리자");
                    case "sub" -> plugin.diamonds().decrease(d, n, "관리자");
                    case "force-sub" -> { d.debt = Math.max(0, d.debt - n); d.markDirty(); }
                    default -> { say(s, "&cset|add|sub|force-sub"); return; }
                }
                say(s, "&a" + plugin.nickname().display(d) + " 남은 다이아: " + Txt.num(d.debt));
            }
            case "gauge" -> {
                // 공용 게이지: /깨굴 게이지 <값|+값|-값>  (예전 형식 /kg gauge <플레이어> <값> 도 받음)
                String v = a.length >= 3 ? a[2] : a.length == 2 ? a[1] : null;
                if (v == null) { say(s, "&e/깨굴 게이지 <값|+값|-값> &7(서버 공용, 지금 " + plugin.gauge().value() + ")"); return; }
                boolean rel = v.startsWith("+") || v.startsWith("-");
                int n = Integer.parseInt(v.replace("+", ""));
                plugin.gauge().set(rel ? plugin.gauge().value() + n : n);
                say(s, "&a공용 운빨 게이지: " + plugin.gauge().value());
            }
            case "coins", "money" -> {
                if (a.length < 3) { say(s, "&e/kg " + sub + " <플레이어> <수|+수|-수>"); return; }
                PlayerData d = target(s, a[1]);
                if (d == null) return;
                boolean rel = a[2].startsWith("+") || a[2].startsWith("-");
                long n = Long.parseLong(a[2].replace("+", ""));
                switch (sub) {
                    case "coins" -> d.coins = Math.max(0, rel ? d.coins + n : n);
                    default -> d.money = Math.max(0, rel ? d.money + n : n);
                }
                d.markDirty();
                say(s, "&a" + plugin.nickname().display(d) + " " + sub + " 변경 완료");
            }
            case "give" -> {
                if (a.length < 3) { say(s, "&e/kg give <플레이어> <아이템id> [개수]  &7아이템: " + itemIds()); return; }
                Player t = plugin.data().online(a[1]);
                CustomItem ci = itemByName(a[2]);
                if (t == null || ci == null) { say(s, "&c플레이어 또는 아이템 없음"); return; }
                plugin.mail().giveOrMail(t, plugin.items().create(ci, a.length > 3 ? Actions.parse(a[3], 1) : 1));
                say(s, "&a지급 완료");
            }
            case "box" -> {
                if (a.length < 3) { say(s, "&e/kg box <플레이어> <normal|premium|lucky> [개수]"); return; }
                PlayerData d = target(s, a[1]);
                if (d != null) plugin.boxes().giveBox(d.uuid, a[2], a.length > 3 ? Actions.parse(a[3], 1) : 1);
            }
            case "fever" -> {
                String k = a.length > 1 ? a[1] : "diamond";
                int sec = a.length > 2 ? Actions.parse(a[2], 0) : 0;
                switch (k) {
                    case "box" -> plugin.fever().startBox(sec);
                    case "stop" -> plugin.fever().stopAll();
                    default -> plugin.fever().startDiamond(sec);
                }
            }
            case "jail" -> { Player t = a.length > 1 ? plugin.data().online(a[1]) : null; if (t != null) plugin.jail().send(t, "관리자"); }
            case "unjail" -> { Player t = a.length > 1 ? plugin.data().online(a[1]) : null; if (t != null) plugin.jail().release(t); }
            case "harass" -> {
                if (a.length > 1 && (a[1].equals("확률") || a[1].equalsIgnoreCase("odds"))) {
                    for (String sec : new String[]{"server", "personal"}) {
                        org.bukkit.configuration.ConfigurationSection cs = plugin.getConfig().getConfigurationSection("harass." + sec);
                        if (cs == null) continue;
                        double sum = 0;
                        for (String k : cs.getKeys(false)) sum += Math.max(0, cs.getDouble(k + ".weight", 0));
                        say(s, "&e" + (sec.equals("server") ? "섭주(전체) 괴롭히기" : "개인 괴롭히기") + " 확률");
                        for (String k : cs.getKeys(false)) {
                            double w = Math.max(0, cs.getDouble(k + ".weight", 0));
                            say(s, "&7 - &f" + cs.getString(k + ".label", k) + " &e" + (sum <= 0 ? 0 : Math.round(w / sum * 1000) / 10.0) + "%");
                        }
                    }
                    return;
                }
                if (a.length > 1 && a[1].equals("all")) plugin.harass().serverHarass();
                else { Player t = a.length > 1 ? plugin.data().online(a[1]) : null; if (t != null) plugin.harass().personal(t); }
            }
            case "quiz" -> plugin.quiz().start(a.length > 1 ? a[1] : "random");
            case "wasd" -> {
                Player t = a.length > 1 ? plugin.data().online(a[1]) : self;
                if (t != null) plugin.wasd().start(t, 6, 6, pl -> say(pl, "&a성공"), pl -> say(pl, "&c실패"));
            }
            case "game" -> game(s, a);
            case "mine" -> {
                if (a.length >= 3 && a[1].equals("regen")) say(s, plugin.mine().regen(a[2]) ? "&a재생성 시작: " + a[2] : "&c구역 설정 확인 (mine.regions." + a[2] + ")");
                else if (a.length >= 3 && a[1].equals("send")) { Player t = plugin.data().online(a[2]); if (t != null) plugin.mine().sendTreasure(t, true); }
                else say(s, "&e/kg mine regen <구역> | send <플레이어>");
            }
            case "farm" -> { plugin.economy().rerollFarm(true); say(s, "&a시세 변경"); }
            case "race" -> say(s, "&a[경주] " + plugin.frogRace().admin(a.length > 1 ? a[1] : ""));
            case "help" -> help(s);
            case "day" -> say(s, "&e서버 " + plugin.clock().serverDay() + "일차 · 오늘 " + plugin.clock().dayKey() + " · 초반 보호 " + (plugin.clock().protectionActive() ? "&a켜짐" : "&c꺼짐"));
            case "protection" -> {
                if (a.length > 1) { plugin.getConfig().set("protection.force", a[1]); plugin.saveConfig(); }
                say(s, "&e초반 보호: " + plugin.getConfig().getString("protection.force", "auto") + " → " + (plugin.clock().protectionActive() ? "켜짐" : "꺼짐"));
            }
            case "info" -> {
                PlayerData d = a.length > 1 ? target(s, a[1]) : self == null ? null : plugin.data().get(self);
                if (d == null) return;
                say(s, "&6" + plugin.nickname().display(d) + " &f남은 " + Txt.num(d.debt) + " · 증가 " + Txt.num(d.totalIncreased) + " · 제출 " + Txt.num(d.totalSubmitted)
                        + " · 기부 " + Txt.num(d.totalDonated) + " · 돈 " + Txt.num(d.money) + " · 코인 " + Txt.num(d.coins) + " · 게이지 " + plugin.gauge().value()
                        + " · 감옥 " + (d.inJail ? d.squatDone + "/" + d.squatRequired : "X") + " · 졸업 " + d.graduated + " · 우편 " + d.mail.size()
                        + " · 대기API " + d.pendingApi.size() + " · 보유다이아 " + Txt.num(d.held));
            }
            case "ui" -> {
                if (self == null || a.length < 2) { say(s, "&e/kg ui <액션> [키=값...]  &7액션: " + String.join(", ", plugin.ui().actionNames())); return; }
                Map<String, String> args = new LinkedHashMap<>();
                for (int i = 2; i < a.length; i++) { String[] kv = a[i].split("=", 2); if (kv.length == 2) args.put(kv[0], kv[1]); }
                if (!plugin.ui().dispatch(self, a[1], args)) say(s, "&c없는 액션: " + a[1]);
            }
            default -> help(s);
        }
    }

    private void npc(CommandSender s, Player self, String[] a) {
        String op = a.length > 1 ? a[1] : "";
        switch (op) {
            case "bind" -> {
                if (self == null || a.length < 3) { say(s, "&e/kg npc bind <동작> &7(엔티티를 바라보고)  동작: " + String.join(", ", plugin.npcs().names())); return; }
                Entity e = self.getTargetEntity(6);
                if (e == null) { say(s, "&c바라보는 엔티티가 없어요 (6칸 이내)"); return; }
                plugin.npcs().bind(e, a[2]);
                say(s, "&a" + e.getType().name() + " 에 '" + a[2] + "' 연결");
            }
            case "unbind" -> {
                Entity e = self == null ? null : self.getTargetEntity(6);
                if (e != null) { plugin.npcs().unbind(e); say(s, "&a연결 해제"); }
            }
            case "run" -> {
                if (a.length < 4) { say(s, "&e/kg npc run <동작> <플레이어>"); return; }
                Player t = plugin.data().online(a[3]);
                if (t != null && !plugin.npcs().run(t, a[2])) say(s, "&c없는 동작: " + a[2]);
            }
            default -> say(s, "&e/kg npc bind|unbind|run  &7동작: " + String.join(", ", plugin.npcs().names()));
        }
    }

    private void game(CommandSender s, String[] a) {
        String op = a.length > 1 ? a[1] : "";
        switch (op) {
            case "start" -> {
                if (a.length < 3) { say(s, "&e/kg game start <id|random> [플레이어...]"); return; }
                if (a[2].equals("random")) { say(s, "&e" + plugin.minigames().startRandom()); return; }
                List<Player> chosen = null;
                if (a.length > 3) {
                    chosen = new ArrayList<>();
                    for (int i = 3; i < a.length; i++) { Player t = plugin.data().online(a[i]); if (t != null) chosen.add(t); }
                }
                say(s, "&e" + plugin.minigames().start(a[2], chosen));
            }
            case "stop" -> {
                if (a.length > 2) plugin.minigames().stop(a[2]); else plugin.minigames().stop();
                say(s, "&a중지");
            }
            case "schedule" -> {
                // /깨굴 게임 일정 [invite|treasure|casino 분]
                if (a.length >= 4) {
                    String k = a[2];
                    if (!List.of("invite", "treasure", "casino").contains(k)) { say(s, "&c위험한초대 | 보물찾기 | 카지노"); return; }
                    plugin.minigames().setNext(k, Math.max(0, Actions.parse(a[3], 0)));
                }
                for (String l : plugin.minigames().scheduleLines()) say(s, l);
                say(s, "&7바꾸기: /깨굴 게임 일정 <위험한초대|보물찾기|카지노> <몇 분 뒤>");
            }
            case "time" -> {
                // /깨굴 게임 시간 <게임> <1h|90m|2h30m>
                if (a.length < 4) {
                    say(s, "&e/깨굴 게임 시간 <게임> <시간> &7예) 1h, 90m, 2h30m, 3시간");
                    for (Minigame g : plugin.minigames().games().values()) if (g.timed()) say(s, " &f" + g.id + " (" + g.label() + ") &7지금 " + fmtMin(g.durationMinutes()));
                    return;
                }
                Minigame g = plugin.minigames().games().get(a[2]);
                if (g == null || !g.timed()) { say(s, "&c시간을 정할 수 있는 게임: " + timedIds()); return; }
                int min = parseMinutes(a[3]);
                if (min <= 0) { say(s, "&c시간 형식: 1h, 90m, 2h30m, 3시간, 45분"); return; }
                plugin.getConfig().set("minigame.games." + g.id + ".duration-minutes", min);
                plugin.saveConfig();
                say(s, "&a" + g.label() + " 게임 시간: " + fmtMin(min) + " &7(다음 판부터)");
            }
            case "check" -> {
                for (Minigame g : plugin.minigames().games().values()) {
                    String r = g.readiness();
                    say(s, "&f" + g.id + " (" + g.label() + "): " + (r == null ? "&a준비됨" : "&c" + r) + (g.enabled() ? "" : " &8(꺼짐)") + (g.timed() ? " &7· " + fmtMin(g.durationMinutes()) : ""));
                }
            }
            default -> say(s, "&e/깨굴 게임 시작 <게임|랜덤> [플레이어...] | 중지 [게임] | 확인 | 시간 <게임> <시간> | 일정");
        }
    }

    private PlayerData target(CommandSender s, String name) {
        Player t = plugin.data().online(name);
        PlayerData d = t != null ? plugin.data().get(t) : plugin.data().findByName(name);
        if (d == null) say(s, "&c플레이어 없음: " + name);
        return d;
    }

    private String itemIds() {
        List<String> ids = new ArrayList<>();
        for (CustomItem c : CustomItem.values()) ids.add(itemName(c));
        return String.join(", ", ids);
    }

    /** 아이템 한글 이름 (색·띄어쓰기 없이) */
    private String itemName(CustomItem c) {
        String n = plugin.getConfig().getString("items." + c.id + ".name", c.defName);
        return Txt.strip(Txt.color(n)).replace(" ", "");
    }

    private CustomItem itemByName(String q) {
        CustomItem c = CustomItem.byId(q);
        if (c != null) return c;
        for (CustomItem x : CustomItem.values()) if (itemName(x).equalsIgnoreCase(q.replace(" ", ""))) return x;
        return null;
    }

    // ───────── 한글 명령 → 내부 이름 ─────────
    private static final Map<String, String> SUB = new LinkedHashMap<>();
    private static final Map<String, String> WORD = new LinkedHashMap<>();
    static {
        String[][] subs = {
                {"도움말", "help"}, {"리로드", "reload"}, {"저장", "save"}, {"위치", "loc"}, {"npc", "npc"}, {"엔피씨", "npc"},
                {"api", "api"}, {"서버api", "sapi"}, {"실행", "run"}, {"다이아", "diamond"}, {"코인", "coins"}, {"돈", "money"},
                {"게이지", "gauge"}, {"지급", "give"}, {"상자", "box"}, {"피버", "fever"}, {"감옥", "jail"}, {"석방", "unjail"},
                {"괴롭히기", "harass"}, {"퀴즈", "quiz"}, {"wasd", "wasd"}, {"게임", "game"}, {"광산", "mine"}, {"시세변경", "farm"},
                {"경주", "race"}, {"초기화", "reset"}, {"전체지급", "giveall"}, {"일차", "day"}, {"보호", "protection"}, {"정보", "info"}, {"ui", "ui"}};
        for (String[] x : subs) SUB.put(x[0], x[1]);
        String[][] words = {
                {"설정", "set"}, {"추가", "add"}, {"감소", "sub"}, {"강제감소", "force-sub"},
                {"일반", "normal"}, {"고급", "premium"}, {"행운", "lucky"}, {"중지", "stop"},
                {"전체", "all"}, {"랜덤", "random"}, {"시작", "start"}, {"확인", "check"}, {"시간", "time"},
                {"재생성", "regen"}, {"보내기", "send"}, {"연결", "bind"}, {"해제", "unbind"},
                {"열기", "open"}, {"출발", "go"}, {"취소", "cancel"}, {"이월", "carry"}, {"멈춤", "pause"}, {"재개", "resume"},
                {"켜기", "on"}, {"끄기", "off"}, {"자동", "auto"}, {"일정", "schedule"},
                {"위험한초대", "invite"}, {"보물찾기", "treasure"}, {"카지노", "casino"}, {"홀덤", "poker"}, {"포커", "poker"}, {"섯다", "seotda"}, {"빼빼로", "pepero"}};
        for (String[] x : words) WORD.put(x[0], x[1]);
    }

    /** 한글 하위 명령을 내부 이름으로. 위치는 /깨굴 위치 저장|삭제|목록|이동 → setloc/delloc/locs/tp */
    private static String[] translate(String[] in) {
        String[] a = in.clone();
        a[0] = SUB.getOrDefault(a[0].toLowerCase(), a[0]);
        if (a[0].equals("loc")) {
            String op = a.length > 1 ? a[1] : "목록";
            String t = switch (op) { case "저장", "set" -> "setloc"; case "삭제", "del" -> "delloc"; case "이동", "tp" -> "tp"; default -> "locs"; };
            List<String> out = new ArrayList<>();
            out.add(t);
            for (int i = 2; i < a.length; i++) out.add(a[i]);
            return out.toArray(new String[0]);
        }
        if (a[0].equals("fever") && a.length > 1) a[1] = switch (a[1]) { case "다이아" -> "diamond"; case "상자" -> "box"; default -> WORD.getOrDefault(a[1], a[1]); };
        else if (!List.of("run", "ui", "api", "sapi", "npc").contains(a[0]) || a[0].equals("npc")) for (int i = 1; i < a.length; i++) {
            if (a[0].equals("npc") && i > 1) break;
            // 플레이어 이름 자리에는 바꾸지 않도록: 정해진 단어만
            String w = WORD.get(a[i]);
            if (w == null) continue;
            boolean playerSlot = (a[0].equals("diamond") || a[0].equals("coins") || a[0].equals("money") || a[0].equals("give") || a[0].equals("box") || a[0].equals("info")) && i == 1;
            if (!playerSlot) a[i] = w;
        }
        return a;
    }

    static int parseMinutes(String raw) {
        String t = raw.trim().toLowerCase().replace("시간", "h").replace("분", "m").replace(" ", "");
        try {
            if (t.matches("\\d+")) return Integer.parseInt(t);
            java.util.regex.Matcher m = java.util.regex.Pattern.compile("^(?:(\\d+(?:\\.\\d+)?)h)?(?:(\\d+)m)?$").matcher(t);
            if (!m.matches()) return -1;
            double h = m.group(1) == null ? 0 : Double.parseDouble(m.group(1));
            int mm = m.group(2) == null ? 0 : Integer.parseInt(m.group(2));
            return (int) Math.round(h * 60) + mm;
        } catch (RuntimeException e) { return -1; }
    }

    static String fmtMin(int min) {
        if (min < 60) return min + "분";
        return (min / 60) + "시간" + (min % 60 == 0 ? "" : " " + (min % 60) + "분");
    }

    private String timedIds() {
        List<String> ids = new ArrayList<>();
        for (Minigame g : plugin.minigames().games().values()) if (g.timed()) ids.add(g.id);
        return String.join(", ", ids);
    }

    private void help(CommandSender s) {
        say(s, "&6/깨굴 &7(= /kg) 관리자 명령");
        say(s, "&e다이아 &f<플레이어> <설정|추가|감소|강제감소> <수>  &e코인/돈 &f<플레이어> <수|+수|-수>  &e게이지 &f<수|+수|-수> &7(공용)");
        say(s, "&e지급 &f<플레이어> <아이템> [개수]  &e상자 &f<플레이어> <일반|고급|행운> [개수]  &e피버 &f<다이아|상자|중지> [초]");
        say(s, "&e위치 &f<저장|삭제|목록|이동> [이름]  &eNPC &f<연결|해제|실행> [동작]  &e실행 &f<플레이어|*> <액션...>");
        say(s, "&e전체지급 &f[개수] &7(손에 든 아이템을 접속자 모두에게)  &e게임 &f<시작|중지|확인|시간|일정> ...  &e경주 &f<열기|출발|취소|이월>  &e감옥/석방 &f<플레이어>");
        say(s, "&e괴롭히기 &f<플레이어|전체>  &e퀴즈 &f[유형]  &eWASD &f[플레이어]  &e광산 &f<재생성|보내기>  &e시세변경");
        say(s, "&eAPI &f<플레이어> <번호>  &e서버API &f<번호>  &e정보 &f[플레이어]  &e일차  &e보호 &f[자동|켜기|끄기]  &e리로드  &e저장");
    }

    private List<String> players() { return plugin.data().onlineNames(); }

    private List<String> adminTab(String[] a) {
        List<String> out = new ArrayList<>();
        if (a.length == 1) { out.addAll(SUB.keySet()); out.remove("엔피씨"); return out; }
        String sub = SUB.getOrDefault(a[0].toLowerCase(), a[0].toLowerCase());
        int i = a.length; // 지금 쓰는 칸 번호 (1부터)
        switch (sub) {
            case "loc" -> {
                if (i == 2) out.addAll(List.of("저장", "삭제", "목록", "이동"));
                else if (i == 3 && (a[1].equals("삭제") || a[1].equals("이동"))) out.addAll(plugin.places().names());
                else if (i == 3 && a[1].equals("저장")) { out.addAll(List.of("lobby", "start", "village", "gacha", "jail", "wild", "race-start", "race-finish", "frog-jump-machine.1", "gacha-machine.1", "gacha-machine.2", "gacha-machine.3", "treasure-start", "invite-drop", "board.donation")); out.addAll(plugin.places().names()); }
            }
            case "setloc", "delloc", "tp" -> { if (i == 2) out.addAll(plugin.places().names()); }
            case "npc" -> {
                if (i == 2) out.addAll(List.of("연결", "해제", "실행"));
                else if (i == 3 && !a[1].equals("해제")) out.addAll(plugin.npcs().names());
                else if (i == 4 && a[1].equals("실행")) out.addAll(players());
            }
            case "diamond" -> { if (i == 2) out.addAll(players()); else if (i == 3) out.addAll(List.of("설정", "추가", "감소", "강제감소")); else if (i == 4) out.addAll(List.of("10", "100", "1000")); }
            case "coins", "money" -> { if (i == 2) out.addAll(players()); else if (i == 3) out.addAll(List.of("+10", "-10", "100")); }
            case "gauge" -> { if (i == 2) out.addAll(List.of(String.valueOf(plugin.gauge().value()), "+10", "-10", "50", "92")); }
            case "give" -> { if (i == 2) out.addAll(players()); else if (i == 3) for (CustomItem c : CustomItem.values()) out.add(itemName(c)); else if (i == 4) out.addAll(List.of("1", "5", "10")); }
            case "box" -> { if (i == 2) out.addAll(players()); else if (i == 3) out.addAll(List.of("일반", "고급", "행운")); else if (i == 4) out.addAll(List.of("1", "5")); }
            case "fever" -> { if (i == 2) out.addAll(List.of("다이아", "상자", "중지")); else if (i == 3) out.addAll(List.of("60", "300", "600")); }
            case "jail", "unjail", "wasd", "info" -> { if (i == 2) out.addAll(players()); }
            case "giveall" -> { if (i == 2) out.addAll(List.of("1", "10", "64")); }
            case "reset" -> { if (i == 2) out.addAll(players()); else if (i == 3) out.addAll(List.of("장비", "부동산", "운세", "닉네임", "전부")); }
            case "harass" -> { if (i == 2) { out.add("전체"); out.add("확률"); out.addAll(players()); } }
            case "api" -> { if (i == 2) out.addAll(players()); }
            case "run" -> { if (i == 2) { out.add("*"); out.addAll(players()); } else if (i == 3) out.addAll(List.of("roulette", "box", "coins", "gauge", "item", "jail", "harass", "fever")); }
            case "game" -> {
                if (i == 2) out.addAll(List.of("시작", "중지", "확인", "시간", "일정"));
                else if (i == 3 && a[1].equals("일정")) out.addAll(List.of("위험한초대", "보물찾기", "카지노"));
                else if (i == 4 && a[1].equals("일정")) out.addAll(List.of("1", "5", "30"));
                else if (i == 3 && a[1].equals("중지")) out.addAll(plugin.minigames().games().keySet());
                else if (i == 3 && a[1].equals("시작")) { out.add("랜덤"); out.addAll(plugin.minigames().games().keySet()); }
                else if (i == 3 && a[1].equals("시간")) for (Minigame g : plugin.minigames().games().values()) { if (g.timed()) out.add(g.id); }
                else if (i == 4 && a[1].equals("시간")) out.addAll(List.of("1h", "90m", "2h", "3h"));
                else if (i >= 4 && a[1].equals("시작")) out.addAll(players());
            }
            case "mine" -> { if (i == 2) out.addAll(List.of("재생성", "보내기")); else if (i == 3 && a[1].equals("보내기")) out.addAll(players()); }
            case "race" -> { if (i == 2) out.addAll(List.of("열기", "출발", "취소", "이월", "멈춤", "재개")); }
            case "protection" -> { if (i == 2) out.addAll(List.of("자동", "켜기", "끄기")); }
            case "ui" -> { if (i == 2) out.addAll(plugin.ui().actionNames()); }
            default -> { }
        }
        return out;
    }

    @Override
    public List<String> onTabComplete(CommandSender s, Command cmd, String alias, String[] a) {
        String name = cmd.getName().toLowerCase();
        List<String> out = new ArrayList<>();
        int i = a.length;
        switch (name) {
            case "kg" -> { if (s.hasPermission("kkaegul.admin")) out.addAll(adminTab(a)); }
            case "kapi" -> { if (i == 1) out.addAll(players()); }
            case "kgwhisper" -> { if (i == 1) out.addAll(players()); }
            case "usertp", "kgtp", "kgsummon" -> { if (i == 1) out.addAll(players()); }
            case "couponadmin" -> { if (i == 1) out.addAll(List.of("만들기", "편집", "목록", "삭제")); else if (i == 2 && !a[0].equals("만들기")) out.addAll(plugin.coupons().names()); }
            case "gacha" -> { if (i == 1) out.addAll(List.of("1", "2", "3")); else if (i == 2) out.add("돌리기"); }
            case "mailbox" -> { if (i == 1) out.add("목록"); }
            case "submit" -> { if (i == 1) out.addAll(List.of("10", "64", "전부", "랭킹")); }
            case "sell" -> { if (i == 1) out.addAll(List.of("손", "전부")); }
            case "buycoin" -> { if (i == 1) out.addAll(List.of("1", "10", "다이아")); }
            case "withdraw" -> { if (i == 1) out.addAll(List.of("1", "10", "64")); }
            case "frogjump" -> { if (i == 1) out.addAll(List.of("시작", "그만")); else if (i == 2 && a[0].equals("시작")) out.addAll(List.of("1", "10", "50")); }
            case "frograce" -> { if (i == 1) out.add("걸기"); else if (i == 2) out.addAll(List.of("1", "2", "3", "4", "5", "6")); else if (i == 3) out.addAll(List.of("1", "10", "50")); }
            case "wild" -> { if (i == 1) out.add("랜덤"); }
            case "jailexit" -> { if (i == 1) out.addAll(List.of("로비", "복귀", "게임")); }
            case "playtime" -> { if (i == 1) out.addAll(List.of("받기", "교환")); }
            case "gauge" -> { if (i == 1) out.addAll(List.of("위", "아래")); }
            case "profile" -> { if (i == 1) out.addAll(List.of("이름", "보이스", "완료")); }
            case "title" -> { if (i == 1 && s instanceof Player p) out.addAll(plugin.titles().owned(p)); }
            default -> { }
        }
        String last = a.length == 0 ? "" : a[a.length - 1].toLowerCase();
        out.removeIf(x -> !x.toLowerCase().startsWith(last));
        return out;
    }
}
