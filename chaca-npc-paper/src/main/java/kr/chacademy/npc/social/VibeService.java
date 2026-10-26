package kr.chacademy.npc.social;

import kr.chacademy.npc.ChacaNpcPlugin;
import kr.chacademy.npc.ai.OpenAiClient;
import kr.chacademy.npc.budget.BudgetService;
import kr.chacademy.npc.config.Settings;
import kr.chacademy.npc.core.Json;
import kr.chacademy.npc.core.TextFilter;
import kr.chacademy.npc.core.TextSanitizer;
import kr.chacademy.npc.data.Storage;

import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

/**
 * 서버 분위기 노트: 하루 1~2번 플레이어 대화를 요약해 모든 NPC 프롬프트에 넣는다.
 * AI 모델을 학습시키는 것이 아니라 "요즘 학교 소식"을 알려주는 것.
 */
public final class VibeService {

    private static final String META_LAST = "vibe_last_run";

    private final ChacaNpcPlugin plugin;
    private volatile List<String> approved = List.of();
    private final java.util.concurrent.atomic.AtomicBoolean running = new java.util.concurrent.atomic.AtomicBoolean(false);
    /** running 이 켜진 시각. 어디선가 끝맺지 못해도 이 시간이 지나면 다시 실행할 수 있게 푼다. */
    private volatile long runningSince;
    private static final long MAX_RUN_MS = 10 * 60_000L;
    private static final int NOTE_MAX = 80;
    private static final int KEYWORD_MAX = 40;

    public VibeService(ChacaNpcPlugin plugin) {
        this.plugin = plugin;
    }

    public List<String> approvedNotes() {
        return approved;
    }

    public void refreshCache() {
        int max = plugin.settings().vibeMaxNotes;
        plugin.database().async(() -> plugin.storage().vibes("approved")).thenAccept(rows -> {
            if (rows == null) {
                return;
            }
            List<Storage.VibeRow> sorted = new ArrayList<>(rows);
            sorted.sort(Comparator.comparingLong(Storage.VibeRow::lastSeen).reversed());
            List<String> notes = new ArrayList<>();
            for (Storage.VibeRow r : sorted) {
                if (notes.size() >= max) {
                    break;
                }
                notes.add(r.note());
            }
            approved = List.copyOf(notes);
        });
    }

    /** 10분마다 호출: 정해진 시각이면 실행. */
    public void tick() {
        Settings st = plugin.settings();
        if (running.get() && System.currentTimeMillis() - runningSince > MAX_RUN_MS) {
            plugin.getLogger().warning("[ChacaNPC] 분위기 노트 실행이 끝나지 않아 상태를 초기화합니다.");
            running.set(false);
        }
        if (!st.vibeEnabled || running.get()) {
            return;
        }
        ZonedDateTime now = ZonedDateTime.now(st.zone);
        if (!st.vibeRunHours.contains(now.getHour())) {
            return;
        }
        String stamp = now.toLocalDate() + "-" + now.getHour();
        plugin.database().async(() -> plugin.storage().meta(META_LAST)).thenAccept(last -> {
            if (last != null && last.startsWith(stamp)) {
                return;
            }
            plugin.sync(() -> run(stamp, msg -> plugin.getLogger().info("[ChacaNPC] 분위기 노트: " + msg)));
        });
    }

    /** 바로 실행 (/NPC관리 분위기노트 지금생성). */
    public void run(String stamp, Consumer<String> report) {
        Settings st = plugin.settings();
        if (!plugin.budget().isAiEnabled()) {
            report.accept("AI가 꺼져 있어 건너뜀");
            return;
        }
        if (!running.compareAndSet(false, true)) {
            report.accept("이미 실행 중이에요");
            return;
        }
        long now = System.currentTimeMillis();
        runningSince = now;
        // 어느 단계에서 예외가 나도 running 이 반드시 풀리도록 단계마다 감싼다
        try {
            plugin.database().async(() -> {
                String last = plugin.storage().meta(META_LAST);
                long since = now - 12 * 3_600_000L;
                if (last != null && last.contains("@")) {
                    try {
                        since = Math.max(since - 12 * 3_600_000L, Long.parseLong(last.substring(last.indexOf('@') + 1)));
                    } catch (NumberFormatException ignored) {
                        // 무시
                    }
                }
                return plugin.storage().recentPlayerLines(since, st.vibeMaxLogLines);
            }).thenAccept(lines -> {
                try {
                    summarize(st, lines, stamp, now, report);
                } catch (RuntimeException ex) {
                    fail(stamp, now, report, ex);
                }
            }).exceptionally(ex -> {
                fail(stamp, now, report, ex);
                return null;
            });
        } catch (RuntimeException ex) {
            fail(stamp, now, report, ex);
        }
    }

    private void summarize(Settings st, List<Storage.PlayerLine> lines, String stamp, long now, Consumer<String> report) {
        if (lines == null || lines.isEmpty()) {
            finish(stamp, now, report, "모을 대화가 없어요", true);
            return;
        }
        Map<String, Integer> index = new HashMap<>();
        StringBuilder sb = new StringBuilder("[최근 학생들이 NPC에게 한 말 — P번호는 서로 다른 학생]\n");
        for (Storage.PlayerLine l : lines) {
            int idx = index.computeIfAbsent(l.player(), k -> index.size() + 1);
            String t = TextSanitizer.strip(l.text());
            sb.append('P').append(idx).append(": ").append(t.length() > 100 ? t.substring(0, 100) : t).append('\n');
        }
        if (index.size() < st.vibeMinDistinctPlayers) {
            finish(stamp, now, report, "대화한 학생이 " + index.size() + "명뿐이라 건너뜀", true);
            return;
        }
        Map<String, Object> item = Json.map(
                "type", "object",
                "properties", Json.map(
                        "note", Json.map("type", "string"),
                        "keyword", Json.map("type", "string")),
                "required", List.of("note", "keyword"),
                "additionalProperties", false);
        Map<String, Object> schema = Json.map(
                "type", "object",
                "properties", Json.map("candidates", Json.map("type", "array", "items", item)),
                "required", List.of("candidates"),
                "additionalProperties", false);
        String instructions = plugin.content().vibePrompt()
                .replace("{world_lore}", st.worldLore == null ? "" : st.worldLore.trim())
                .replace("{max_notes}", String.valueOf(st.vibeMaxNotes));
        List<Map<String, Object>> input = OpenAiClient.messages();
        input.add(OpenAiClient.message("user", sb.toString()));
        OpenAiClient.Request req = new OpenAiClient.Request(instructions, input, schema, "vibe_notes",
                800, "chacanpc:vibe");
        BudgetService.Reservation reservation = plugin.budget().reserve(BudgetService.SYSTEM,
                plugin.budget().estimate(instructions.length() + sb.length(), 800));
        if (reservation == null) {
            finish(stamp, now, report, "예산 한도로 건너뜀", true);
            return;
        }
        boolean submitted = false;
        try {
            // 백그라운드 요청: 플레이어 대화가 붐비면 받지 않는다 → 이번에는 건너뛰고 다음 10분 주기에 다시 시도
            java.util.concurrent.CompletableFuture<OpenAiClient.Result> call = plugin.ai().background(req);
            submitted = true;
            call.whenComplete((res, ex) -> {
                try {
                    plugin.budget().settle(reservation, res == null ? null : res.usage(), res != null && res.usageKnown());
                    if (res == null) {
                        finish(stamp, now, report, "AI 호출 실패", true);
                    } else if (!res.ok()) {
                        // 보내지도 못한 경우(대기열·차단기)에는 "했음" 표시를 남기지 않아 다음 주기에 다시 시도한다
                        finish(stamp, now, report, "AI 호출 실패 (" + res.category() + "): " + res.error(), !res.notBilled());
                    } else {
                        verifyAndSave(res.text(), lines, stamp, now, report);
                    }
                } catch (RuntimeException e2) {
                    fail(stamp, now, report, e2);
                }
            });
        } finally {
            if (!submitted) {
                plugin.budget().settle(reservation, OpenAiClient.Usage.ZERO, true); // 보내기 전에 실패: 예약을 풀어 준다
            }
        }
    }

    private void verifyAndSave(String json, List<Storage.PlayerLine> lines, String stamp, long now, Consumer<String> report) {
        Settings st = plugin.settings();
        TextFilter filter = plugin.content().filter();
        List<Object> cands;
        try {
            cands = Json.arr(Json.parseObject(json).get("candidates"));
        } catch (RuntimeException ex) {
            finish(stamp, now, report, "AI 응답을 읽지 못함", true);
            return;
        }
        if (cands == null) {
            cands = List.of();
        }
        List<String[]> accepted = new ArrayList<>();
        for (Object o : cands) {
            Map<String, Object> m = Json.obj(o);
            if (m == null) {
                continue;
            }
            // 노트는 승인되면 모든 NPC의 시스템 프롬프트에 들어간다: 저장 전에 정리(제어문자·색 코드·URL 제거)하고
            // 금지어·탈옥 표현·공개용 금지어 검사를 통과한 것만 후보로 남긴다. 길면 자르지 않고 버린다.
            String note = TextSanitizer.clean(Json.str(m, "note"), 0);
            String keyword = TextSanitizer.clean(Json.str(m, "keyword"), 0);
            if (note == null || keyword == null) {
                continue;
            }
            if (note.codePointCount(0, note.length()) > NOTE_MAX || keyword.codePointCount(0, keyword.length()) > KEYWORD_MAX
                    || filter.storable(note, NOTE_MAX) == null || !filter.isPublicSafe(keyword)) {
                continue;
            }
            // 서로 다른 학생 N명 이상이 실제로 쓴 말인지 확인 (도배 방지)
            String nk = TextFilter.normalize(keyword);
            if (nk.length() < 2) {
                continue;
            }
            Set<String> players = new HashSet<>();
            for (Storage.PlayerLine l : lines) {
                if (TextFilter.normalize(l.text()).contains(nk)) {
                    players.add(l.player());
                }
            }
            if (players.size() >= st.vibeMinDistinctPlayers) {
                accepted.add(new String[]{note, nk});
            }
        }
        String status = st.vibeAutoApprove ? "approved" : "candidate";
        plugin.database().run(() -> {
            String msg;
            try {
                int added = 0;
                for (String[] a : accepted) {
                    if (plugin.storage().upsertVibe(a[0], a[1], status, now)) {
                        added++;
                    }
                }
                plugin.storage().expireVibes(now - st.vibeExpireDays * 86_400_000L);
                msg = "후보 " + accepted.size() + "개 확인, 새로 " + added + "개 추가"
                        + (st.vibeAutoApprove ? " (자동 승인)" : " — /NPC관리 분위기노트 보기 로 확인하세요");
            } catch (RuntimeException ex) {
                msg = "저장 실패: " + ex.getMessage();
            }
            finish(stamp, now, report, msg, true);
        });
    }

    private void fail(String stamp, long now, Consumer<String> report, Throwable ex) {
        plugin.getLogger().log(java.util.logging.Level.WARNING, "[ChacaNPC] 분위기 노트 실행 중 오류", ex);
        finish(stamp, now, report, "오류로 중단: " + ex, false);
    }

    /** 실행 끝. running 을 가장 먼저 푼다 (뒤의 저장·보고가 실패해도 다음 실행이 막히지 않게). */
    private void finish(String stamp, long now, Consumer<String> report, String msg, boolean markDone) {
        running.set(false);
        try {
            if (markDone) {
                plugin.database().run(() -> plugin.storage().setMeta(META_LAST, stamp + "@" + now));
            }
            refreshCache();
        } catch (RuntimeException ex) {
            plugin.getLogger().warning("[ChacaNPC] 분위기 노트 마무리 실패: " + ex.getMessage());
        }
        try {
            report.accept(msg);
        } catch (RuntimeException ex) {
            plugin.getLogger().warning("[ChacaNPC] 분위기 노트 결과 알림 실패: " + ex.getMessage());
        }
    }
}
