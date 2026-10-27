package school.magiccodex.paper;

import java.io.File;
import java.util.*;
import org.bukkit.Bukkit;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;

/**
 * 수업 (오픈 전 메모 #68·#70, 오너 2026-10-26). 교수 NPC 대화창의 "수업 받으러 왔어요" 버튼
 * (ChacaNPC 버튼 {@code action: "lesson:<수업키>"}) → 대화창이 한 번 바뀌어 "어떤 수업 들을래?" 와 함께
 * 그 교수의 수업 전부를 선택지로 보여 준다 (수업 이름 · 필요 지능 · 들음 표시). 고르면 책 창(BookBridge)으로
 * 수업 내용이 열리고, 끝까지 읽고 닫으면 그 책의 commands(권한·마법 조건 주기 등)가 플레이어당 평생 1번 실행된다.
 *
 * <ul>
 *   <li>lessons.yml — 교수별 수업 목록 (/서버관리 새로고침 수업).</li>
 *   <li>수업 내용·지능 컷·끝날 때 명령 = books.yml 의 그 책 (title·pages·required-intelligence·commands).</li>
 *   <li>목록은 "수업 이름 - 지능 N" 만 보이고, 지능이 모자란 수업을 고르면 교수 대사로 막는다.</li>
 *   <li>이미 들은 수업도 다시 펼쳐 볼 수 있다 (명령은 다시 실행되지 않음).</li>
 * </ul>
 */
final class LessonBridge {
    /** affinity = 이 교수(npc)와의 호감도가 이만큼 있어야 열림 (0 = 제한 없음) — 비전 수업. */
    record Lesson(String book, String name, int affinity) {}
    /** npc = 호감도를 볼 ChacaNPC 캐릭터 ID (비전 수업이 없으면 비워도 됨). */
    record Teacher(String id, String name, String npc, String portrait, String ask, String locked, String secret, String allDone, List<Lesson> lessons) {}

    static final int PER_PAGE = 4, MAX_LESSONS = 40, MY_LINE_TICKS = 30;
    private final MagicCodexBridge plugin;
    private final BookBridge books;
    private final DialogueBridge dialogues;
    private final File file;
    private Map<String, Teacher> teachers = Map.of();
    private final Map<UUID, Long> lastOpen = new HashMap<>();

    LessonBridge(MagicCodexBridge plugin, BookBridge books, DialogueBridge dialogues) throws Exception {
        this.plugin = plugin; this.books = books; this.dialogues = dialogues;
        file = new File(plugin.getDataFolder(), "lessons.yml");
        if (!file.exists()) plugin.saveResource("lessons.yml", false);
        reload();
        CmdKit.register(plugin, "/컨텐츠관리 수업", this::commands);
    }

    void reload() throws Exception {
        var y = new YamlConfiguration();
        y.load(file);
        teachers = parse(y.getConfigurationSection("lessons"));
        for (var t : teachers.values()) for (var l : t.lessons())
            if (books.book(l.book()) == null) plugin.getLogger().warning("lessons.yml " + t.id() + ": books.yml 에 없는 책 " + l.book() + " — 목록에서 빠져요");
            else if (books.book(l.book()).commands().isEmpty()) plugin.getLogger().warning("lessons.yml " + t.id() + ": 책 " + l.book() + " 에 commands 가 없어 '들음' 표시가 안 돼요");
    }

    static Map<String, Teacher> parse(ConfigurationSection root) {
        var out = new LinkedHashMap<String, Teacher>();
        if (root == null) return out;
        for (String id : root.getKeys(false)) {
            if (!id.matches("[a-z0-9_-]{1,48}")) throw new IllegalArgumentException("lessons 키는 영문 소문자·숫자·_·- 1~48자: " + id);
            var s = root.getConfigurationSection(id);
            if (s == null) throw new IllegalArgumentException("lessons." + id + " 형식 확인");
            String name = s.getString("teacher", id).strip(), portrait = s.getString("portrait", "").strip(), npc = s.getString("npc", "").strip();
            if (!npc.isEmpty() && !npc.matches("[A-Za-z0-9_-]{1,64}")) throw new IllegalArgumentException("lessons." + id + ".npc 는 ChacaNPC 캐릭터 ID");
            if (name.isEmpty() || name.length() > 64) throw new IllegalArgumentException("lessons." + id + ".teacher 는 1~64자");
            if (!portrait.isEmpty() && !portrait.matches("[a-z0-9_/-]{1,120}")) throw new IllegalArgumentException("lessons." + id + ".portrait 는 대화 일러스트 ID (영문 소문자·숫자·_·/·-)");
            var lessons = new ArrayList<Lesson>();
            for (Object raw : s.getList("classes", List.of())) {
                String book, label = "";
                int affinity = 0;
                if (raw instanceof Map<?, ?> m) {
                    book = String.valueOf(m.get("book")).strip();
                    if (m.get("name") != null) label = String.valueOf(m.get("name")).strip();
                    if (m.get("affinity") instanceof Number n) affinity = n.intValue();
                }
                else book = String.valueOf(raw).strip();
                if (affinity < 0 || affinity > 100) throw new IllegalArgumentException("lessons." + id + " affinity 는 0~100: " + book);
                if (!book.matches("[a-z0-9_-]{1,48}")) throw new IllegalArgumentException("lessons." + id + ".classes 의 book 은 books.yml 책 ID: " + book);
                if (label.length() > 60) throw new IllegalArgumentException("lessons." + id + " 수업 이름은 60자까지: " + label);
                lessons.add(new Lesson(book, label, affinity));
            }
            if (lessons.size() > MAX_LESSONS) throw new IllegalArgumentException("lessons." + id + ".classes 는 " + MAX_LESSONS + "개까지");
            if (npc.isEmpty() && lessons.stream().anyMatch(l -> l.affinity() > 0)) throw new IllegalArgumentException("lessons." + id + ": 호감도 수업(affinity)이 있으면 npc 를 적어 주세요");
            out.put(id, new Teacher(id, name, npc, portrait,
                    text(s, "ask", "수업 들으러 왔구나! 어떤 수업 들을래?"),
                    text(s, "locked", "이 수업은 아직 조금 어려울 거야. 지능을 더 키워서 다시 오렴."),
                    text(s, "secret", "(교수님의 비전 마법은 조금 더 친해져야 배울 수 있을 것 같다…)"),
                    text(s, "all-done", "벌써 내 수업은 다 들었구나. 언제든 다시 펼쳐 봐도 좋아."),
                    List.copyOf(lessons)));
        }
        return Collections.unmodifiableMap(out);
    }
    private static String text(ConfigurationSection s, String key, String def) {
        String v = s.getString(key, def).strip();
        if (v.length() > 400) throw new IllegalArgumentException(s.getCurrentPath() + "." + key + " 는 400자까지");
        return v;
    }

    /** NPC 대화창 수업 버튼. false = 없는 수업 키 (알림은 여기서). 메인 스레드. */
    boolean open(Player p, String id, Entity anchor) {
        Teacher t = teachers.get(id);
        if (t == null) { CmdKit.notify(p, "warning", "lesson-missing", "수업을 열 수 없어요", "관리자에게 알려 주세요 (lessons.yml " + id + ")", 3000); return false; }
        long now = System.currentTimeMillis();
        if (now - lastOpen.getOrDefault(p.getUniqueId(), 0L) < 500) return true;
        lastOpen.put(p.getUniqueId(), now);
        // NPC 버튼이면 대화창의 "내 차례"(내가 고른 문장)가 다 나온 뒤에 수업 창으로 바꾼다 (2026-10-27).
        if (anchor != null) { Bukkit.getScheduler().runTaskLater(plugin, () -> { if (p.isOnline()) load(p, t, anchor); }, MY_LINE_TICKS); return true; }
        load(p, t, anchor);
        return true;
    }

    private void load(Player p, Teacher t, Entity anchor) {
        books.readBooks(p.getUniqueId(), read -> {
            if (!p.isOnline()) return;
            if (read == null) { CmdKit.notify(p, "warning", "lesson-store", "수업 기록을 불러오지 못했어요", "잠시 후 다시 말을 걸어 주세요", 3000); return; }
            var social = plugin.npcSocial();
            if (t.npc().isEmpty() || social == null || t.lessons().stream().noneMatch(l -> l.affinity() > 0)) { menu(p, t, read, 0, anchor); return; }
            social.affinity(p.getUniqueId(), t.npc()).whenComplete((row, error) -> Bukkit.getScheduler().runTask(plugin, () -> {
                if (!p.isOnline()) return;
                if (error != null) plugin.getLogger().warning("수업 " + t.id() + ": 호감도 읽기 실패 (" + error + ") — 비전 수업은 잠김으로 표시");
                menu(p, t, read, row == null ? 0 : row.score(), anchor);
            }));
        });
    }

    private void menu(Player p, Teacher t, Set<String> read, int affinity, Entity anchor) {
        var lessons = new ArrayList<Lesson>();
        for (var l : t.lessons()) if (books.book(l.book()) != null) lessons.add(l);
        double intelligence = plugin.magicPower(p);
        int pages = Math.max(1, (lessons.size() + PER_PAGE - 1) / PER_PAGE);
        boolean allDone = !lessons.isEmpty() && lessons.stream().allMatch(l -> read.contains(l.book()));
        var nodes = new LinkedHashMap<String, DialogueDefinition.Node>();
        for (int page = 0; page < pages; page++) {
            var choices = new ArrayList<DialogueDefinition.Choice>();
            for (int i = page * PER_PAGE; i < Math.min(lessons.size(), (page + 1) * PER_PAGE); i++)
                choices.add(new DialogueDefinition.Choice("l" + i, label(lessons.get(i), read, intelligence, affinity), "", List.of(), List.of()));
            if (page > 0) choices.add(new DialogueDefinition.Choice("prev", "◀ 이전 수업", "p" + (page - 1), List.of(), List.of()));
            if (page + 1 < pages) choices.add(new DialogueDefinition.Choice("next", "다음 수업 ▶", "p" + (page + 1), List.of(), List.of()));
            if (choices.size() < 6) choices.add(new DialogueDefinition.Choice("bye", "다음에 올게요", "", List.of(), List.of()));
            String text = lessons.isEmpty() ? "지금은 준비된 수업이 없단다. 다음에 다시 오렴." : allDone ? t.allDone() : t.ask();
            if (pages > 1) text += "\n(" + (page + 1) + " / " + pages + ")";
            nodes.put("p" + page, new DialogueDefinition.Node(t.name(), t.portrait(), text, List.of(), List.copyOf(choices)));
        }
        dialogues.openMenu(p, anchor, "", "p0", nodes, (who, choice) -> pick(who, t, lessons, affinity, choice));
    }

    private String label(Lesson l, Set<String> read, double intelligence, int affinity) {
        var book = books.book(l.book());
        String name = l.name().isEmpty() ? book.title() : l.name();
        int need = book.requiredIntelligence();
        if (l.affinity() > affinity && !read.contains(l.book()))   // 비전 수업: 친해지기 전에는 이름을 숨긴다
            return "[비전] ??? - 호감도 " + l.affinity() + (need > 0 ? " · 지능 " + need : "");
        // 오너 2026-10-27: "[중급] 도약하는 물방울 - 지능 20" 형식, (지능 부족) 표시 없음
        String s = name + (need > 0 ? " - 지능 " + need : "");
        if (read.contains(l.book())) s += "  ✔ 들음";
        return s.length() > 140 ? s.substring(0, 140) : s;
    }

    /** 선택: "" = 대화창 닫기(수업이면 다음 틱에 책을 펼침), 그 밖 = 교수 대사로 막기. */
    private String pick(Player p, Teacher t, List<Lesson> lessons, int affinity, String choice) {
        if (!choice.startsWith("l")) return "";
        int i;
        try { i = Integer.parseInt(choice.substring(1)); } catch (NumberFormatException e) { return ""; }
        if (i < 0 || i >= lessons.size()) return "";
        var lesson = lessons.get(i);
        var book = books.book(lesson.book());
        if (book == null) return "이 수업은 지금 준비 중이란다.";
        if (lesson.affinity() > affinity) return t.secret() + " (호감도 " + lesson.affinity() + " 필요 · 지금 " + affinity + ")";
        double intelligence = plugin.magicPower(p);
        if (!BookBridge.intelligenceMet(book.requiredIntelligence(), intelligence))
            return t.locked() + " (필요 지능 " + book.requiredIntelligence() + " · 지금 " + (int) Math.floor(intelligence) + ")";
        String id = book.id();
        Bukkit.getScheduler().runTask(plugin, () -> { if (p.isOnline()) books.open(p, id); });
        return "";
    }

    // ------------------------------------------------------------------ commands

    private void commands() {
        var root = CmdKit.literal("수업")
                .then(CmdKit.literal("목록").executes(CmdKit.run(ctx -> list(CmdKit.sender(ctx)))))
                .then(CmdKit.literal("열기").then(CmdKit.word("수업", () -> teachers.keySet())
                        .executes(CmdKit.run(ctx -> {
                            String id = CmdKit.text(ctx, "수업");
                            if (!teachers.containsKey(id)) throw ChacaApi.error("lessons.yml 에 없는 수업이에요: " + id);
                            open(CmdKit.self(ctx), id, null);
                        }))));
        ChacaApi.branch(plugin, ChacaApi.컨텐츠관리, root, "교수 수업 목록·열기(미리보기). NPC 버튼은 action: \"lesson:<수업키>\"", "수업 목록");
        ChacaApi.reload(plugin, "수업", "lessons.yml (교수별 수업 목록)", this::reload);
    }

    private void list(org.bukkit.command.CommandSender sender) {
        var out = new ArrayList<String>();
        out.add("── 수업 " + teachers.size() + "곳 ──");
        for (var t : teachers.values()) {
            out.add(" " + t.id() + " · " + t.name() + " · 수업 " + t.lessons().size() + "개 (NPC 버튼 action: \"lesson:" + t.id() + "\")");
            for (var l : t.lessons()) {
                var b = books.book(l.book());
                out.add("   - " + l.book() + (b == null ? " (books.yml 에 없음)" : " · " + (l.name().isEmpty() ? b.title() : l.name()) + (b.requiredIntelligence() > 0 ? " · 필요 지능 " + b.requiredIntelligence() : "") + (l.affinity() > 0 ? " · 호감도 " + l.affinity() : "") + " · 명령 " + b.commands().size() + "개"));
            }
        }
        if (teachers.isEmpty()) out.add(" (lessons.yml 에 수업이 없어요)");
        CmdKit.lines(sender, out);
    }
}
