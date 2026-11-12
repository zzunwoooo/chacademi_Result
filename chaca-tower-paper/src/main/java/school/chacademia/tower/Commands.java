package school.chacademia.tower;

import java.util.List;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Player;

/** /탑 · /탑관리 */
final class Commands {
    private final TowerPlugin plugin;
    private final Records records;

    Commands(TowerPlugin plugin, Records records) { this.plugin = plugin; this.records = records; }

    private TowerService service() { return plugin.service(); }

    TabExecutor player() {
        return new TabExecutor() {
            @Override public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
                String sub = args.length == 0 ? "" : args[0];
                if (sub.equals("랭킹")) { ranking(sender); return true; }
                if (!(sender instanceof Player p)) { ChacaLink.fail(sender, "게임 안에서만 쓸 수 있어요"); return true; }
                switch (sub) {
                    case "입장" -> service().enter(p);
                    case "포기" -> {
                        if (service().inRun(p)) service().end(p, "quit");
                        else ChacaLink.fail(p, "탑에 있지 않아요");
                    }
                    case "대기취소" -> service().leaveQueue(p);
                    case "증강" -> service().showBuild(p);   // [기능:탑내증강]
                    case "기록" -> records.best(p.getUniqueId()).thenAccept(b -> plugin.getServer().getScheduler().runTask(plugin, () ->
                            ChacaLink.ok(p, b.floor() > 0 ? "내 최고 기록: " + b.floor() + "층 · " + TowerService.time(b.ms()) + " · 도전 " + b.runs() + "번" : "아직 기록이 없어요")));
                    default -> ChacaLink.ok(p, "/탑 입장 · /탑 증강 · /탑 포기 · /탑 대기취소 · /탑 랭킹 · /탑 기록");
                }
                return true;
            }

            @Override public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
                return args.length == 1 ? starts(List.of("입장", "증강", "포기", "대기취소", "랭킹", "기록"), args[0]) : List.of();
            }
        };
    }

    private void ranking(CommandSender to) {
        records.top(10).thenAccept(list -> plugin.getServer().getScheduler().runTask(plugin, () -> {
            if (list.isEmpty()) { ChacaLink.ok(to, "아직 기록이 없어요"); return; }
            var sb = new StringBuilder(service().s.name + " 랭킹");
            int i = 1;
            for (var r : list) sb.append("\n").append(i++).append(". ").append(ChacaLink.name(r.player())).append(" — ").append(r.floor()).append("층 · ").append(TowerService.time(r.ms()));
            ChacaLink.ok(to, sb.toString());
        }));
    }

    TabExecutor admin() {
        return new TabExecutor() {
            @Override public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
                String sub = args.length == 0 ? "" : args[0];
                switch (sub) {
                    case "다시불러오기" -> { plugin.reload(); service().rewards.load(); ChacaLink.ok(sender, "config.yml 을 다시 읽었어요" + missing()); }
                    case "상태" -> ChacaLink.ok(sender, "탑: " + service().status() + missing());
                    case "강제종료" -> {
                        var r = service().current();
                        var p = r == null ? null : plugin.getServer().getPlayer(r.player);
                        if (p == null) { ChacaLink.fail(sender, "진행 중인 판이 없어요"); return true; }
                        service().end(p, "admin");
                        ChacaLink.ok(sender, p.getName() + " 님의 판을 끝냈어요");
                    }
                    default -> {
                        if (!(sender instanceof Player p)) { ChacaLink.fail(sender, "게임 안에서만 쓸 수 있어요"); return true; }
                        switch (sub) {
                            case "보상" -> p.openInventory(service().rewards.new Menu().getInventory());
                            case "설정도구" -> { p.getInventory().addItem(plugin.tool().item()); ChacaLink.ok(p, "설정 도구를 줬어요. 블록 좌클릭 = 모서리1, 우클릭 = 모서리2"); }
                            case "시작" -> save(p, "locations.start", "층 시작 위치");
                            case "출구" -> save(p, "locations.exit", "출구");
                            case "입구" -> save(p, "locations.portal", "입구");
                            case "층" -> {
                                if (args.length < 2 || !service().inRun(p)) { ChacaLink.fail(p, "탑 안에서 /탑관리 층 <번호>"); return true; }
                                try { service().jumpFloor(p, Integer.parseInt(args[1])); ChacaLink.ok(p, args[1] + "층으로 옮겼어요"); }
                                catch (NumberFormatException e) { ChacaLink.fail(p, "층 번호는 숫자로 적어 주세요"); }
                            }
                            default -> ChacaLink.ok(p, "/탑관리 보상 · 설정도구 · 시작 · 출구 · 입구 · 상태 · 다시불러오기 · 강제종료 · 층 <번호>");
                        }
                    }
                }
                return true;
            }

            @Override public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
                return args.length == 1 ? starts(List.of("보상", "설정도구", "시작", "출구", "입구", "상태", "다시불러오기", "강제종료", "층"), args[0]) : List.of();
            }
        };
    }

    private void save(Player p, String path, String what) {
        var l = Settings.Loc.of(p.getLocation());
        plugin.set(path, l.text());
        ChacaLink.ok(p, what + " 저장: " + l.shortText() + missing());
    }

    private String missing() {
        var m = service().s.missing();
        return m.isEmpty() ? "" : " · 아직 안 정한 것: " + String.join(", ", m);
    }

    private static List<String> starts(List<String> all, String prefix) { return all.stream().filter(s -> s.startsWith(prefix)).toList(); }
}
