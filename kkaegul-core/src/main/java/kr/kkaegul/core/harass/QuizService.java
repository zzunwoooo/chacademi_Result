package kr.kkaegul.core.harass;

import kr.kkaegul.core.KkaegulCore;
import kr.kkaegul.core.util.Json;
import kr.kkaegul.core.util.Rng;
import kr.kkaegul.core.util.Txt;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 섭주용 거탐 퀴즈: 맞춤법 / 받아쓰기 / 상식퀴즈.
 * 문제 은행: quiz.yml (종류별 "문제|정답1/정답2" 한 줄씩).
 * 채팅으로 답한다. 맞히면 소소 감소 룰렛, 틀리거나 시간 초과면 소소 증가 룰렛.
 */
public final class QuizService {
    private final KkaegulCore plugin;
    private volatile Quiz current;

    public QuizService(KkaegulCore plugin) { this.plugin = plugin; }

    private static final class Quiz {
        final String category, question;
        final List<String> answers;
        final long deadline;
        final Set<UUID> participants = new HashSet<>();
        final Map<UUID, Boolean> result = new ConcurrentHashMap<>();
        volatile boolean closed = false;

        Quiz(String category, String question, List<String> answers, long deadline) {
            this.category = category; this.question = question; this.answers = answers; this.deadline = deadline;
        }
    }

    public boolean running() { return current != null && !current.closed; }

    /** category: spelling | dictation | trivia | random */
    public void start(String category) {
        if (running()) return;
        List<String> cats = List.of("spelling", "dictation", "trivia");
        if (category == null || category.equals("random")) category = Rng.pick(cats);
        List<String> bank = plugin.quizBank().getStringList(category);
        if (bank.isEmpty()) {
            plugin.getLogger().warning("quiz.yml 에 " + category + " 문제가 없어요");
            return;
        }
        String line = Rng.pick(bank);
        String[] qa = line.split("\\|", 2);
        if (qa.length < 2) return;
        List<String> answers = new ArrayList<>();
        for (String s : qa[1].split("/")) answers.add(norm(s));
        int seconds = plugin.getConfig().getInt("quiz.seconds", 20);
        Quiz q = new Quiz(category, qa[0], answers, System.currentTimeMillis() + seconds * 1000L);
        for (Player p : Bukkit.getOnlinePlayers()) q.participants.add(p.getUniqueId());
        current = q;
        String catName = switch (category) {
            case "spelling" -> "맞춤법";
            case "dictation" -> "받아쓰기";
            default -> "상식퀴즈";
        };
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (!plugin.ui().send(p, Json.obj().put("type", "open").put("screen", "quiz")
                    .put("data", Json.obj().put("category", catName).put("question", q.question).put("seconds", seconds)))) {
                plugin.ui().center(p, "&e[" + catName + "]", "&f채팅으로 정답을 입력하세요 (" + seconds + "초)");
            }
            p.sendMessage(Txt.color(Txt.notice("&6[거탐 " + catName + "] &f" + q.question + " &7(" + seconds + "초, 채팅으로 답하기)")));
        }
        Bukkit.getScheduler().runTaskLater(plugin, () -> close(q), seconds * 20L);
    }

    /** 채팅 입력. 퀴즈 답으로 처리했으면 true (채팅 숨김) */
    public boolean onChat(Player p, String msg) {
        Quiz q = current;
        if (q == null || q.closed || !q.participants.contains(p.getUniqueId())) return false;
        boolean ok = q.answers.contains(norm(msg));
        if (q.result.putIfAbsent(p.getUniqueId(), ok) != null) return true;
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (ok) plugin.ui().successSound(p); else plugin.ui().errorSound(p);
            if (plugin.ui().hasRenderer(p)) {
                plugin.ui().update(p, "quiz", Json.obj().put("result", ok ? "correct" : "wrong").put("rewardText", ok ? "소소 감소 룰렛!" : "소소 증가 룰렛…"));
                // 도장을 잠깐 보여 주고 카드를 닫은 다음 룰렛 (화면 겹침 방지)
                Bukkit.getScheduler().runTaskLater(plugin, () -> { if (p.isOnline()) plugin.ui().close(p, "quiz"); reward(p.getUniqueId(), ok); }, 45L);
            } else {
                plugin.ui().center(p, ok ? "&a정답!" : "&c오답!", ok ? "&f소소 감소 룰렛" : "&f소소 증가 룰렛");
                reward(p.getUniqueId(), ok);
            }
        });
        return true;
    }

    private void close(Quiz q) {
        if (q.closed) return;
        q.closed = true;
        for (UUID id : q.participants) {
            if (q.result.putIfAbsent(id, false) != null) continue;
            Player p = Bukkit.getPlayer(id);
            if (p != null) plugin.ui().errorSound(p);
            if (p != null && plugin.ui().hasRenderer(p)) plugin.ui().update(p, "quiz", Json.obj().put("result", "wrong").put("rewardText", "시간 초과! 정답: " + q.answers.get(0)));
            else if (p != null) plugin.ui().center(p, "&c시간 초과!", "&f정답: " + q.answers.get(0));
            UUID rid = id;
            Bukkit.getScheduler().runTaskLater(plugin, () -> reward(rid, false), 52L);   // 카드가 닫힌 뒤 룰렛
        }
        Bukkit.getScheduler().runTaskLater(plugin, () -> { for (Player p : Bukkit.getOnlinePlayers()) plugin.ui().close(p, "quiz"); }, 50L);
        plugin.announce("&6[거탐] &f정답: &e" + q.answers.get(0), false);
        if (current == q) current = null;
    }

    private void reward(UUID id, boolean correct) {
        String kind = correct ? plugin.getConfig().getString("quiz.success-roulette", "small_decrease")
                : plugin.getConfig().getString("quiz.fail-roulette", "small_increase");
        plugin.roulette().spin(id, kind, correct ? "거탐 정답" : "거탐 오답");
    }

    private static String norm(String s) { return s == null ? "" : s.replaceAll("\\s+", "").toLowerCase(); }
}
