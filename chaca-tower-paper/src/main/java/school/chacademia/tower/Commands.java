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
                    case "보상기본" -> ChacaLink.ok(sender, "기본 보상: " + service().seedRewards(args.length >= 2 && args[1].equals("덮어쓰기")) + " (빈 칸만 · 전부 바꾸려면 /탑관리 보상기본 덮어쓰기)");   // [기능:탑기본보상]
                    case "강제종료" -> {
                        String who = service().forceEnd(args.length >= 2 ? args[1] : null);
                        if (who == null) { ChacaLink.fail(sender, args.length >= 2 ? "그 사람의 판이 없어요" : "판이 없거나 여러 개예요 — /탑관리 강제종료 <이름>"); return true; }
                        ChacaLink.ok(sender, who + " 님의 판을 끝냈어요");
                    }
                    default -> {
                        if (!(sender instanceof Player p)) { ChacaLink.fail(sender, "게임 안에서만 쓸 수 있어요"); return true; }
                        switch (sub) {
                            case "보상" -> p.openInventory(service().rewards.new Menu().getInventory());
                            case "설정도구" -> { p.getInventory().addItem(plugin.tool().item()); ChacaLink.ok(p, "설정 도구를 줬어요. 블록 좌클릭 = 모서리1, 우클릭 = 모서리2"); }
                            // [기능:탑여러방 시작]
                            case "방" -> {
                                if (args.length < 2) { ChacaLink.ok(p, "지금 고치는 방: " + plugin.editing(p) + " · 바꾸려면 /탑관리 방 <이름> (없는 이름이면 새로 만들어요)"); return true; }
                                String id = args[1];
                                if (!id.matches("[0-9A-Za-z가-힣_-]{1,16}")) { ChacaLink.fail(p, "방 이름은 글자·숫자로 16자까지"); return true; }
                                plugin.editing(p, id);
                                boolean exists = service().s.rooms.containsKey(id);
                                if (!exists) plugin.set("rooms." + id + ".spawns", List.of());
                                ChacaLink.ok(p, "방 " + id + (exists ? " 을 고쳐요" : " 을 새로 만들었어요") + " · 설정도구로 구역, /탑관리 시작, /탑관리 소환자리");
                            }
                            case "방목록" -> ChacaLink.ok(p, "탑: " + service().status());
                            case "방삭제" -> {
                                if (args.length < 2 || !service().s.rooms.containsKey(args[1])) { ChacaLink.fail(p, "/탑관리 방삭제 <이름>"); return true; }
                                if (service().roomBusy(args[1])) { ChacaLink.fail(p, "그 방에서 판이 진행 중이에요"); return true; }
                                plugin.set("rooms." + args[1], null);
                                plugin.forgetEditing(args[1]);
                                ChacaLink.ok(p, "방 " + args[1] + " 을 지웠어요" + missing());
                            }
                            case "시작" -> {
                                var room = service().s.rooms.get(plugin.editing(p));
                                if (room != null && room.box() != null && !room.box().contains(p.getLocation())) { ChacaLink.fail(p, "방 " + plugin.editing(p) + " 구역 밖이에요 — 구역 안에 서서 찍어 주세요 (다른 방이면 /탑관리 방 <이름>)"); return true; }
                                save(p, "rooms." + plugin.editing(p) + ".start", "방 " + plugin.editing(p) + " 시작 위치");
                            }
                            case "소환자리" -> {
                                String id = plugin.editing(p), path = "rooms." + id + ".spawns";
                                var list = new java.util.ArrayList<>(plugin.getConfig().getStringList(path));
                                String what = args.length >= 2 ? args[1] : "";
                                switch (what) {
                                    case "목록" -> {
                                        var sb = new StringBuilder("방 " + id + " 소환 자리 " + list.size() + "곳");
                                        for (int i = 0; i < list.size(); i++) { var l = Settings.Loc.parse(list.get(i)); sb.append("\n").append(i + 1).append(". ").append(l == null ? list.get(i) : l.shortText()); }
                                        ChacaLink.ok(p, list.isEmpty() ? "방 " + id + " 소환 자리 없음 (방 구역 안 아무 데나 나와요)" : sb.toString());
                                    }
                                    case "지우기" -> {
                                        if (args.length >= 3) {
                                            try { int n = Integer.parseInt(args[2]); if (n < 1 || n > list.size()) throw new NumberFormatException(); list.remove(n - 1); }
                                            catch (NumberFormatException e) { ChacaLink.fail(p, "/탑관리 소환자리 지우기 <번호> (번호는 목록에서)"); return true; }
                                        } else list.clear();
                                        plugin.set(path, list);
                                        ChacaLink.ok(p, "방 " + id + " 소환 자리 " + list.size() + "곳 남음" + (list.isEmpty() ? " (방 구역 안 아무 데나 나와요)" : ""));
                                    }
                                    case "" -> {
                                        var room = service().s.rooms.get(id);
                                        if (room != null && room.box() != null && !room.box().contains(p.getLocation())) { ChacaLink.fail(p, "방 " + id + " 구역 밖이에요 — 구역 안에 서서 찍어 주세요"); return true; }
                                        var l = Settings.Loc.of(p.getLocation());
                                        list.add(l.text());
                                        plugin.set(path, list);
                                        ChacaLink.ok(p, "방 " + id + " 소환 자리 " + list.size() + "번 = " + l.shortText() + " (설정도구를 들면 초록 기둥으로 보여요)");
                                    }
                                    default -> ChacaLink.fail(p, "/탑관리 소환자리 · 소환자리 목록 · 소환자리 지우기 [번호]");
                                }
                            }
                            // [기능:탑여러방 끝]
                            case "출구" -> save(p, "locations.exit", "출구");
                            case "입구" -> save(p, "locations.portal", "입구");
                            case "층" -> {
                                if (args.length < 2 || !service().inRun(p)) { ChacaLink.fail(p, "탑 안에서 /탑관리 층 <번호>"); return true; }
                                try { service().jumpFloor(p, Integer.parseInt(args[1])); ChacaLink.ok(p, args[1] + "층으로 옮겼어요"); }
                                catch (NumberFormatException e) { ChacaLink.fail(p, "층 번호는 숫자로 적어 주세요"); }
                            }
                            default -> ChacaLink.ok(p, "/탑관리 방 <이름> · 방목록 · 방삭제 <이름> · 설정도구 · 시작 · 소환자리 [목록·지우기] · 출구 · 입구 · 보상 · 상태 · 다시불러오기 · 강제종료 [이름] · 층 <번호>");
                        }
                    }
                }
                return true;
            }

            @Override public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
                if (args.length == 1) return starts(List.of("방", "방목록", "방삭제", "설정도구", "시작", "소환자리", "출구", "입구", "보상", "보상기본", "상태", "다시불러오기", "강제종료", "층"), args[0]);
                if (args.length == 2 && (args[0].equals("방") || args[0].equals("방삭제"))) return starts(List.copyOf(service().s.rooms.keySet()), args[1]);
                if (args.length == 2 && args[0].equals("소환자리")) return starts(List.of("목록", "지우기"), args[1]);
                return List.of();
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
