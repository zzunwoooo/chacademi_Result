package school.magiccodex.paper;

import com.mojang.brigadier.Command;
import io.papermc.paper.command.brigadier.Commands;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.function.BiFunction;

/**
 * 일일 제한 관리 (오너 2026-10-26, 메모 #90): {@code /유저관리 <닉> 일일제한 보기 | 초기화 <종류|전체>}.
 * 선물·AI 대화 호감도·마음 읽기(383)처럼 "하루 N번" 기능이 여기에 종류를 등록한다. 기록은 각자 공유 DB 에 있어
 * 서버 이동·재접속으로 초기화되지 않고, 관리자가 이 명령으로만 오늘 횟수를 지운다 (점수·호감도는 그대로).
 * 하루 기준 = NpcSocialService.today() (Asia/Seoul 자정, 설정 timezone).
 */
final class DailyLimits {
    /** key: 명령에 쓰는 종류 이름(한글), view/reset: (플레이어, 날짜 yyyy-MM-dd) → 메인 스레드에서 완료. */
    record Source(String key, String label,
                  BiFunction<UUID, String, CompletableFuture<List<String>>> view,
                  BiFunction<UUID, String, CompletableFuture<Integer>> reset) {}

    private static final Map<String, Source> SOURCES = new LinkedHashMap<>();
    private static boolean registered;

    private DailyLimits() {}

    static synchronized void register(MagicCodexBridge plugin, Source source) {
        SOURCES.put(source.key(), source);
        if (!registered) { registered = true; command(plugin); }
    }

    /** NPC 호감도 일일 표(codex_affinity_daily)에 든 종류들: 선물·AI 대화 보상·마음 읽기. */
    static void registerAffinity(MagicCodexBridge plugin, NpcSocialService social) {
        if (social == null) return;
        record Kind(String key, String label, String source) {}
        for (var k : List.of(new Kind("선물", "NPC 선물 횟수", "gift"), new Kind("AI대화", "AI 대화 호감도 보상", "chat_reward"),
                new Kind("마음읽기", "마음 읽기 (마법 383)", LifeSpells.HEART_READ_SOURCE))) {
            register(plugin, new Source(k.key(), k.label(),
                    (id, day) -> social.dailyRows(id, day).thenApply(rows -> {
                        var out = new ArrayList<String>();
                        for (var r : rows) if (r.source().equals(k.source())) out.add(r.npc() + " " + r.amount() + "번");
                        return out;
                    }),
                    (id, day) -> social.resetDaily(id, day, k.source()).thenApply(n -> {
                        if (k.source().equals(LifeSpells.HEART_READ_SOURCE)) LifeSpells.forgetReads(id);
                        return n;
                    })));
        }
    }

    private static String today(MagicCodexBridge plugin) {
        var social = plugin.npcSocial();
        return social != null ? social.today() : java.time.LocalDate.now(java.time.ZoneId.of("Asia/Seoul")).toString();
    }

    private static void command(MagicCodexBridge plugin) {
        if (!CodexCommands.ready(plugin, "일일제한")) return;
        var node = Commands.literal("일일제한");
        node.then(Commands.literal("보기").executes(ctx -> {
            UUID id = ChacaApi.target(ctx); var sender = CodexCommands.sender(ctx); String day = today(plugin);
            var sources = List.copyOf(SOURCES.values());
            var futures = new ArrayList<CompletableFuture<List<String>>>();
            for (var s : sources) futures.add(s.view().apply(id, day).exceptionally(e -> List.of("(읽기 실패)")));
            CompletableFuture.allOf(futures.toArray(CompletableFuture[]::new)).whenComplete((v, e) -> {
                var rows = new ArrayList<String>();
                for (int i = 0; i < sources.size(); i++) {
                    var list = futures.get(i).join();
                    rows.add(" " + sources.get(i).key() + " (" + sources.get(i).label() + "): " + (list.isEmpty() ? "오늘 기록 없음" : String.join(", ", list)));
                }
                CodexCommands.lines(sender, "── " + ChacaApi.name(id) + " 님 일일 제한 · " + day + " ──", rows);
            });
            return Command.SINGLE_SUCCESS;
        }));
        node.then(Commands.literal("초기화").then(Commands.argument("종류", ChacaApi.word(() -> {
            var keys = new ArrayList<>(SOURCES.keySet()); keys.add("전체"); return keys;
        })).executes(ctx -> {
            UUID id = ChacaApi.target(ctx); var sender = CodexCommands.sender(ctx); String day = today(plugin);
            String kind = ctx.getArgument("종류", String.class);
            var targets = kind.equals("전체") ? List.copyOf(SOURCES.values()) : SOURCES.containsKey(kind) ? List.of(SOURCES.get(kind)) : List.<Source>of();
            if (targets.isEmpty()) throw ChacaApi.error("종류는 " + String.join("·", SOURCES.keySet()) + "·전체 중 하나예요");
            var futures = new ArrayList<CompletableFuture<Integer>>();
            for (var s : targets) futures.add(s.reset().apply(id, day));
            CompletableFuture.allOf(futures.toArray(CompletableFuture[]::new)).whenComplete((v, e) -> {
                if (e != null) { ChacaApi.fail(sender, "일일 제한을 초기화하지 못했어요", e); return; }
                int n = 0; for (var f : futures) n += f.join();
                ChacaApi.ok(sender, ChacaApi.name(id) + " 님의 오늘 " + kind + " 제한을 초기화했어요 (기록 " + n + "줄)");
            });
            return Command.SINGLE_SUCCESS;
        })));
        ChacaApi.branch(plugin, ChacaApi.유저관리, node, "선물·AI 대화·마음 읽기 등 하루 제한 보기·오늘 횟수 초기화", "일일제한 보기");
    }
}
