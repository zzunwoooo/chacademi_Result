package kr.chacademy.npc.dialogue;

import kr.chacademy.npc.ChacaNpcPlugin;
import kr.chacademy.npc.ai.OpenAiClient;
import kr.chacademy.npc.budget.BudgetService;
import kr.chacademy.npc.config.Settings;
import kr.chacademy.npc.core.AffinityStage;
import kr.chacademy.npc.core.BudgetMath;
import kr.chacademy.npc.core.CharacterSheet;
import kr.chacademy.npc.core.Defs.Button;
import kr.chacademy.npc.core.Defs.HintDef;
import kr.chacademy.npc.core.Defs.QuestDef;
import kr.chacademy.npc.core.Defs.RumorView;
import kr.chacademy.npc.core.Defs.Turn;
import kr.chacademy.npc.core.PromptBuilder;
import kr.chacademy.npc.core.ReplyParser;
import kr.chacademy.npc.core.StrikeTracker;
import kr.chacademy.npc.core.TextFilter;
import kr.chacademy.npc.core.TextSanitizer;
import kr.chacademy.npc.core.TimeText;
import kr.chacademy.npc.data.Storage;
import kr.chacademy.npc.integration.MagicCodexLink;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import school.magiccodex.npctalk.NpcTalkProtocol;

import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 대화의 중심. 입력 검사 → 상황 정보 → 예산 예약 → AI 호출 → 대답 검사 → 반영 → (검사 통과한) 대사 표시.
 * AI는 제안과 대사만 만든다. 퀘스트 가능 여부·수락·보상은 MagicCodex(서버)가 판단한다.
 */
public final class DialogueService {

    private final ChacaNpcPlugin plugin;
    private final Map<UUID, DialogueSession> sessions = new ConcurrentHashMap<>();
    private final Map<String, PendingQuest> pendingQuests = new ConcurrentHashMap<>();
    private final Map<String, String> jealousToday = new ConcurrentHashMap<>();
    private final ButtonCache buttonCache = new ButtonCache();
    private final Random random = new Random();
    private final SecureRandom tokens = new SecureRandom();
    private final ChatDialogueView chatView = new ChatDialogueView();
    private volatile StrikeTracker strikes;
    private final Map<String, Long> warmupAt = new ConcurrentHashMap<>();
    private long lastPruneAt;

    /** 정보 불러오기(DB·MagicCodex)가 늦을 때 timeout-seconds 에 더 얹어 주는 여유(초). */
    private static final int LOAD_ALLOWANCE_SECONDS = 3;
    /** 같은 NPC·일과표 칸의 버튼 대답 미리 만들기를 다시 시도하기까지 최소 간격. */
    private static final long WARMUP_RETRY_MS = 10 * 60_000L;

    /**
     * 요청 한 번(플레이어 한마디)의 상태. 메인 스레드에서만 바꾼다.
     * charged = 하루 횟수를 차감했는지, aiSent = AI 대기열에 넘겼는지, offeredHint = 이번 프롬프트에 서버가 넣은 힌트 id.
     */
    static final class TurnState {
        final int seq;
        final String pid;
        final boolean charged;
        /** 하루 횟수가 공유 DB에서 실제로 1 올라갔는지 (올라간 것만 돌려준다). */
        boolean counted;
        boolean refunded;
        boolean aiSent;
        /** 이 요청에 대한 대사(AI 또는 고정)를 화면에 보냈는지. */
        boolean answered;
        String offeredHint;

        TurnState(int seq, String pid, boolean charged) {
            this.seq = seq;
            this.pid = pid;
            this.charged = charged;
        }
    }

    /** 수락 토큰: 플레이어·NPC·퀘스트·만료 시각에 묶이고 한 번만 처리된다. */
    private record PendingQuest(UUID player, String npcId, String sessionToken, String questId, long expiresAt) {
    }

    private static final String[] MAGIC_WORDS = {"마법", "주문", "힌트", "비밀", "마력", "스펠", "spell"};

    public DialogueService(ChacaNpcPlugin plugin) {
        this.plugin = plugin;
        reloadSettings();
    }

    public void reloadSettings() {
        Settings s = plugin.settings();
        // 다시 읽어도 쌓인 장난 기록·잠금은 유지한다 (reload 로 잠금이 풀리지 않게)
        StrikeTracker cur = strikes;
        if (cur == null) {
            strikes = new StrikeTracker(s.strikeWindowMinutes * 60_000L, s.strikeMax, s.strikeLockMinutes * 60_000L);
        } else {
            cur.reconfigure(s.strikeWindowMinutes * 60_000L, s.strikeMax, s.strikeLockMinutes * 60_000L);
        }
    }

    public ButtonCache buttonCache() {
        return buttonCache;
    }

    public DialogueSession session(UUID player) {
        return sessions.get(player);
    }

    public Collection<DialogueSession> sessions() {
        return sessions.values();
    }

    /** 세션이 없을 때 쓰는 안내용 화면. */
    public DialogueView viewFor(Player p) {
        return plugin.talkChannel().isCapable(p) ? plugin.talkChannel() : chatView;
    }

    /** 지금 AI 대화 중인 Citizens NPC 번호 (3D 외형 대화 동작용). */
    public java.util.Set<Integer> talkingNpcIds() {
        java.util.Set<Integer> out = new java.util.HashSet<>();
        for (DialogueSession s : sessions.values()) {
            if (s.citizensId() != null && !s.adminTest()) {
                out.add(s.citizensId());
            }
        }
        return out;
    }

    public boolean isTalkingWith(int citizensId) {
        for (DialogueSession s : sessions.values()) {
            if (s.citizensId() != null && s.citizensId() == citizensId) {
                return true;
            }
        }
        return false;
    }

    private MagicCodexLink link() {
        return plugin.link();
    }

    // =================================================================== 열기/닫기

    public void open(Player p, CharacterSheet c, Integer citizensId, boolean adminTest) {
        DialogueSession old = sessions.get(p.getUniqueId());
        if (old != null) {
            if (old.character().id().equals(c.id()) && old.adminTest() == adminTest) {
                old.lastActivity = System.currentTimeMillis();
                if (old.opened) {
                    old.view.open(p, old, c.fallback("greeting_again", plugin.settings().defaultFallbacks, random),
                            c.buttonsOr(plugin.settings().buttons), hearts(old));
                }
                return;
            }
            close(p, false);
        }
        long now = System.currentTimeMillis();
        DialogueSession s = new DialogueSession(p.getUniqueId(), link().playerName(p), c, citizensId, adminTest, now, viewFor(p));
        sessions.put(p.getUniqueId(), s);
        if (!adminTest) link().stat(p.getUniqueId(), "대화시작:" + c.id(), 1); // 통계 #96: NPC 별 대화 연 횟수
        loadContext(p, s);
        if (citizensId != null) {
            plugin.npcs().face(citizensId, p);
            if (!adminTest && plugin.appearance() != null) {
                plugin.appearance().greet(citizensId); // 3D 외형: 인사 동작 한 번 (표시용, 판정과 무관)
            }
        }
        String greeting = c.fallback("greeting", plugin.settings().defaultFallbacks, random);
        // 호감도(하트 표시)를 불러온 뒤 화면을 연다. 늦어져도 1초 뒤에는 연다.
        int timeoutTask = Bukkit.getScheduler().runTaskLater(plugin, () -> showOpen(p, s, greeting), 20L).getTaskId();
        s.loading.whenComplete((v, ex) -> plugin.sync(() -> {
            Bukkit.getScheduler().cancelTask(timeoutTask);
            showOpen(p, s, greeting);
        }));
        if (!adminTest) {
            warmup(c, plugin.npcs().currentSlot(c)); // 누군가 말을 걸었으니 버튼 대답을 채워 둔다 (이미 했으면 건너뜀)
        }
    }

    private void showOpen(Player p, DialogueSession s, String greeting) {
        if (s.opened || sessions.get(s.player()) != s || !p.isOnline()) {
            return;
        }
        s.opened = true;
        s.view.open(p, s, greeting, s.character().buttonsOr(plugin.settings().buttons), hearts(s));
    }

    private int hearts(DialogueSession s) {
        return stage(s).ordinal();
    }

    private AffinityStage stage(DialogueSession s) {
        return AffinityStage.of(s.score, s.heart, s.character().romanceable());
    }

    private void loadContext(Player p, DialogueSession s) {
        Settings st = plugin.settings();
        String pid = s.player().toString();
        String npc = s.character().id();
        long now = System.currentTimeMillis();
        long dayStart = st.today().atStartOfDay(st.zone).toInstant().toEpochMilli();
        Storage storage = plugin.storage();
        Map<String, String> npcNames = new java.util.HashMap<>();
        for (CharacterSheet other : plugin.characters().all()) {
            npcNames.put(other.id(), other.name());
        }
        CompletableFuture<Void> db = plugin.database().async(() -> {
            s.memos = storage.memos(pid, npc);
            Storage.LastChat lc = storage.lastChat(pid, npc);
            if (lc != null && now - lc.endedAt() <= st.lastChatExpireDays * 86_400_000L) {
                s.lastChat = lc;
            }
            // 소문: 자유 글이 섞인 사건(약속·별명 등)은 원문 대신 서버가 만든 문장만 프롬프트에 넣는다
            List<RumorView> heard = new ArrayList<>();
            for (Storage.HeardRumor h : storage.rumorsFor(npc, pid, now, Math.max(1, st.rumorMaxInPrompt) * 2)) {
                RumorView v = h.view();
                String who = h.sourceNpc() == null ? null : npcNames.getOrDefault(h.sourceNpc(), h.sourceNpc());
                heard.add(new RumorView(v.id(), TextSanitizer.privateRumor(v.type(), who, v.text()), v.type(), v.eventCreatedAt()));
            }
            s.rumors = heard;
            s.promises = storage.promises(pid, npc, 3);
            s.nickname = storage.nickname(pid, npc);
            s.hints = storage.hintState(pid, dayStart);
            s.quests = storage.questState(pid, npc, now - 86_400_000L);
            return (Void) null;
        });
        // MagicCodex: 호감도·NPC별 호칭·스토리 진행 (메인 스레드에서 호출, 메인 스레드에서 완료)
        CompletableFuture<Void> mc;
        if (link().available()) {
            CompletableFuture<Void> aff = link().affinity(s.player(), npc).handle((v, ex) -> {
                if (v != null && v.length >= 2) {
                    s.score = v[0];
                    s.heart = v[1];
                } else {
                    s.score = st.affinityDefaultScore;
                    s.heart = 0;
                }
                return null;
            });
            CompletableFuture<Void> nick = link().nickname(s.player(), npc).handle((v, ex) -> {
                s.npcNickname = v == null || v.isBlank() ? null : v;
                return null;
            });
            CompletableFuture<Void> story = link().storySummary(s.player()).handle((v, ex) -> {
                s.storyChapter = v == null || v.isBlank() ? null : v;
                return null;
            });
            mc = CompletableFuture.allOf(aff, nick, story);
        } else {
            s.score = st.affinityDefaultScore;
            s.heart = 4; // MagicCodex 없음: 하트 단계 제한 없이 기본 점수로
            mc = CompletableFuture.completedFuture(null);
        }
        s.loading = CompletableFuture.allOf(db, mc).handle((v, ex) -> {
            s.loaded = true;
            return null;
        });
    }

    /** 대화 종료. byPlayer = 플레이어가 직접 끝냄(작별 인사). */
    public void close(Player p, boolean byPlayer) {
        DialogueSession s = sessions.remove(p.getUniqueId());
        if (s == null) {
            return;
        }
        cancelTimers(s);
        s.busy = false;
        abandonTurn(s);
        saveLastChat(s);
        String farewell = byPlayer ? s.character().fallback("farewell", plugin.settings().defaultFallbacks, random) : null;
        s.view.close(p, s, farewell);
    }

    /** 접속 종료 등으로 플레이어 없이 닫기. */
    public void closeQuietly(UUID player) {
        DialogueSession s = sessions.remove(player);
        if (s != null) {
            cancelTimers(s);
            s.busy = false;
            abandonTurn(s);
            saveLastChat(s);
        }
    }

    /** 대화가 닫힐 때 처리 중이던 요청: 아직 AI에 보내지 않았으면 하루 횟수를 돌려준다 (보낸 뒤라면 결과가 왔을 때 판단). */
    private void abandonTurn(DialogueSession s) {
        TurnState t = s.turn;
        // 플러그인이 꺼지는 중이면 아직 대답을 기다리던 요청의 결과를 처리할 수 없으므로(sync 가 무시됨) 지금 돌려준다
        if (t != null && (!t.aiSent || (!plugin.isEnabled() && !t.answered && s.activeSeq == t.seq))) {
            refund(t);
        }
    }

    private void cancelTimers(DialogueSession s) {
        if (s.stallTaskId >= 0) {
            Bukkit.getScheduler().cancelTask(s.stallTaskId);
            s.stallTaskId = -1;
        }
        if (s.timeoutTaskId >= 0) {
            Bukkit.getScheduler().cancelTask(s.timeoutTaskId);
            s.timeoutTaskId = -1;
        }
    }

    private void saveLastChat(DialogueSession s) {
        if (s.saveable.isEmpty() || s.adminTest()) {
            return;
        }
        int n = plugin.settings().lastChatTurns * 2;
        List<Turn> last = new ArrayList<>(s.saveable.subList(Math.max(0, s.saveable.size() - n), s.saveable.size()));
        String pid = s.player().toString();
        String npc = s.character().id();
        long now = System.currentTimeMillis();
        plugin.database().run(() -> plugin.storage().saveLastChat(pid, npc, last, now));
    }

    public void closeAll() {
        for (UUID id : new ArrayList<>(sessions.keySet())) {
            Player p = Bukkit.getPlayer(id);
            if (p != null) {
                close(p, false);
            } else {
                closeQuietly(id);
            }
        }
    }

    /** 1초마다: 오래 말 없거나 멀어지면 닫고, NPC가 플레이어를 바라보게 한다. */
    public void tick() {
        Settings st = plugin.settings();
        long now = System.currentTimeMillis();
        pendingQuests.values().removeIf(q -> now > q.expiresAt());
        long maxBusyMs = (Math.max(2, st.timeoutSeconds) + LOAD_ALLOWANCE_SECONDS + 2) * 1000L;
        if (now - lastPruneAt > 60_000L) {
            // 접속을 끊은 플레이어의 기록이 계속 쌓이지 않게 가끔 정리
            lastPruneAt = now;
            String today = st.today().toString();
            jealousToday.values().removeIf(day -> !today.equals(day));
            warmupAt.values().removeIf(at -> now - at > WARMUP_RETRY_MS);
            strikes.prune(now);
        }
        Map<Integer, DialogueSession> facing = newestPerNpc(sessions.values(),
                x -> x.adminTest() ? null : x.citizensId(), x -> x.lastActivity);
        for (DialogueSession s : new ArrayList<>(sessions.values())) {
            Player p = Bukkit.getPlayer(s.player());
            if (p == null || !p.isOnline()) {
                closeQuietly(s.player());
                continue;
            }
            if (s.busy) {
                if (now - s.busySince <= maxBusyMs) {
                    continue;
                }
                // 타이머가 사라졌거나 처리 중 오류로 "생각 중"에 갇힌 대화: 강제로 넘기고 아래 거리·시간 검사를 그대로 한다
                plugin.getLogger().warning("[ChacaNPC] " + s.character().id() + " 대답이 " + (now - s.busySince) / 1000
                        + "초 넘게 끝나지 않아 고정 대사로 넘겼습니다.");
                TurnState stuck = s.turn;
                if (stuck != null) {
                    failTurn(p, s, stuck, "busy");
                } else {
                    cancelTimers(s);
                    s.busy = false;
                    s.activeSeq = -1;
                }
            }
            if (now - s.lastActivity > st.idleTimeoutSeconds * 1000L) {
                close(p, false);
                continue;
            }
            if (s.citizensId() != null && !s.adminTest()) {
                Location npcLoc = plugin.npcs().location(s.citizensId());
                if (npcLoc == null || npcLoc.getWorld() != p.getWorld()
                        || npcLoc.distanceSquared(p.getLocation()) > st.maxDistance * st.maxDistance) {
                    close(p, false);
                    continue;
                }
                if (facing.get(s.citizensId()) == s) {
                    plugin.npcs().face(s.citizensId(), p);
                }
            }
        }
    }

    /**
     * #99 같은 NPC 와 여러 명이 동시에 대화: NPC 몸(서버 엔티티)은 모두에게 하나로 보이므로 1초마다 번갈아 돌아서지 않게
     * NPC 하나당 가장 최근에 말한 사람 한 명만 바라본다. 대화 내용·진행은 원래 사람별 세션이라 영향 없음.
     */
    static <T> Map<Integer, T> newestPerNpc(Collection<T> items, java.util.function.Function<T, Integer> npcOf,
                                            java.util.function.ToLongFunction<T> atOf) {
        Map<Integer, T> out = new java.util.HashMap<>();
        for (T t : items) {
            Integer id = npcOf.apply(t);
            if (id == null) {
                continue;
            }
            T cur = out.get(id);
            if (cur == null || atOf.applyAsLong(t) > atOf.applyAsLong(cur)) {
                out.put(id, t);
            }
        }
        return out;
    }

    // =================================================================== 입력 처리

    /** 채팅 방식 입력 (/t, 채팅 버튼). 요청 순번은 서버가 매긴다. */
    public void input(Player p, String rawText, String buttonId) {
        DialogueSession s = sessions.get(p.getUniqueId());
        if (s == null) {
            viewFor(p).info(p, null, "대화 중인 NPC가 없어요. NPC를 우클릭하거나 가까이 가서 /말 <할 말> 을 입력하세요.");
            return;
        }
        input(p, rawText, buttonId, ++s.chatSeq);
    }

    /** 선물 창에서 칸을 골랐다 (HUD C_GIFT 또는 채팅 /대화선택 선물). seq &lt; 0 이면 새 순번. */
    public void gift(Player p, int slot, int seq) {
        DialogueSession s = sessions.get(p.getUniqueId());
        if (s == null) {
            return;
        }
        s.replySeq = seq < 0 ? ++s.chatSeq : seq;
        s.lastActivity = System.currentTimeMillis();
        plugin.gifts().giveSlot(p, s.character(), slot);
    }

    /** 플레이어가 말했다. buttonId가 있으면 추천 버튼. seq = 요청 순번. */
    public void input(Player p, String rawText, String buttonId, int seq) {
        DialogueSession s = sessions.get(p.getUniqueId());
        if (s == null) {
            return;
        }
        Settings st = plugin.settings();
        long now = System.currentTimeMillis();
        s.replySeq = seq;
        if (s.busy) {
            s.view.info(p, s, "(" + s.character().name() + "이(가) 아직 대답하는 중이에요)");
            return;
        }
        // 대화 버튼 3번 = 선물 창 (대사 대신)
        if (NpcTalkProtocol.GIFT_BUTTON.equals(buttonId)) {
            s.lastActivity = now;
            plugin.gifts().offer(p, s, seq);
            return;
        }
        // 상점 버튼 (예: 피피 "물건 좀 볼 수 있어요?", action: shop:pipi) = 대사 대신 대화창을 닫고 상점을 연다.
        // 스토리 단계 호출은 이 대화창보다 먼저 클릭을 가져가고(QuestEngine, 준비된 단계만), 튜토리얼 대상이면 Bridge 가
        // 일반 잡화점 대신 튜토리얼 재료 상점을 연다 — 그래서 여기서는 상점 ID 만 넘긴다.
        // 수업 버튼 (교수 "수업 받으러 왔어요", action: lesson:<수업키>, 2026-10-26) = 대화창이 Bridge 의 수업 선택 창으로 바뀐다.
        if (buttonId != null) {
            for (Button x : s.character().buttonsOr(st.buttons)) {
                if (!x.id().equals(buttonId) || x.lesson() == null) {
                    continue;
                }
                s.lastActivity = now;
                if (!plugin.link().lessons()) {
                    s.view.info(p, s, "(지금은 수업을 열 수 없어요. 잠시 뒤 다시 말을 걸어 주세요)");
                    plugin.getLogger().warning("[ChacaNPC] 수업 버튼 " + s.character().id() + "/" + x.id() + ": MagicCodexBridge 수업 연결(openLesson)이 없음");
                    return;
                }
                Integer npcId = s.citizensId();
                org.bukkit.entity.Entity anchor = null;
                if (npcId != null) {
                    var npc = net.citizensnpcs.api.CitizensAPI.getNPCRegistry().getById(npcId);
                    if (npc != null && npc.isSpawned()) {
                        anchor = npc.getEntity();
                    }
                }
                close(p, false);
                if (!plugin.link().openLesson(p, x.lesson(), anchor)) {
                    plugin.getLogger().warning("[ChacaNPC] 수업 버튼 " + x.lesson() + " 을(를) 열지 못함");
                }
                return;
            }
        }
        if (buttonId != null) {
            for (Button x : s.character().buttonsOr(st.buttons)) {
                if (!x.id().equals(buttonId) || x.shop() == null) {
                    continue;
                }
                s.lastActivity = now;
                if (!plugin.link().shops()) {
                    s.view.info(p, s, "(지금은 상점을 열 수 없어요. 잠시 뒤 다시 말을 걸어 주세요)");
                    plugin.getLogger().warning("[ChacaNPC] 상점 버튼 " + s.character().id() + "/" + x.id() + ": MagicCodexBridge 상점 연결(openShop)이 없음");
                    return;
                }
                Integer npcId = s.citizensId();
                org.bukkit.entity.Entity anchor = null;
                if (npcId != null) {
                    var npc = net.citizensnpcs.api.CitizensAPI.getNPCRegistry().getById(npcId);
                    if (npc != null && npc.isSpawned()) {
                        anchor = npc.getEntity();
                    }
                }
                close(p, false);
                if (!plugin.link().openShop(p, x.shop(), anchor)) {
                    plugin.getLogger().warning("[ChacaNPC] 상점 버튼 " + x.shop() + " 을(를) 열지 못함 (Bridge 응답 없음)");
                }
                return;
            }
        }
        if (now - s.lastInputAt < st.cooldownSeconds * 1000L) {
            s.view.info(p, s, "(조금만 천천히 말해 주세요)");
            return;
        }
        String text;
        if (buttonId != null) {
            Button b = null;
            for (Button x : s.character().buttonsOr(st.buttons)) {
                if (x.id().equals(buttonId)) {
                    b = x;
                }
            }
            if (b == null) {
                return;
            }
            text = b.label();
        } else {
            text = rawText == null ? "" : rawText.strip();
            if (text.isEmpty()) {
                return;
            }
            // 서버에서도 100자·UTF-8 400바이트·제어문자 검사
            if (!NpcTalkProtocol.validInput(text) || text.codePointCount(0, text.length()) > st.inputMaxLength) {
                s.view.info(p, s, "(말이 너무 길거나 쓸 수 없는 문자가 있어요. " + Math.min(st.inputMaxLength,
                        NpcTalkProtocol.MAX_INPUT_CHARS) + "자 이내로 말해 주세요)");
                return;
            }
        }
        s.lastActivity = now;
        s.lastInputAt = now;
        s.activeSeq = seq;
        CharacterSheet c = s.character();
        String pid = p.getUniqueId().toString();

        if (strikes.isLocked(pid, c.id(), now)) {
            say(p, s, seq, text, c.fallback("locked", st.defaultFallbacks, random), true);
            return;
        }
        if (buttonId == null && plugin.content().filter().checkInput(text) != TextFilter.Verdict.OK) {
            strike(p, s, seq, text);
            return;
        }

        // 추천 버튼: 미리 만든 대답이 충분하면 바로 (AI 호출 없음)
        int slot = plugin.npcs().currentSlot(c);
        String today = st.today().toString();
        if (buttonId != null && st.buttonCacheEnabled) {
            String cached = buttonCache.take(today, c.id(), slot, buttonId, pid, st.buttonCacheVariants);
            if (cached != null) {
                say(p, s, seq, text, cached, false);
                return;
            }
        }

        if (!plugin.budget().isAiEnabled()) {
            say(p, s, seq, text, c.fallback("busy", st.defaultFallbacks, random), false);
            return;
        }
        if (plugin.budget().status() == BudgetMath.Status.EXHAUSTED && !s.adminTest()) {
            say(p, s, seq, text, c.fallback("tired", st.defaultFallbacks, random), false);
            return;
        }
        s.busy = true;
        s.busySince = now;
        TurnState turn = new TurnState(seq, pid, !s.adminTest());
        s.turn = turn;
        s.view.thinking(p, s, seq);
        final String finalText = text;
        // 기한은 지금부터 잰다: 정보 불러오기(DB·MagicCodex)가 늦거나 끝나지 않아도 "생각 중"에 갇히지 않는다.
        // stall-seconds 동안 대답이 없으면 "잠깐만...", 기한이 지나면 고정 대사로 넘기고 이 요청 결과는 화면에 안 보냄
        cancelTimers(s);
        s.stallTaskId = Bukkit.getScheduler().runTaskLater(plugin, () -> {
            s.stallTaskId = -1;
            if (s.busy && s.activeSeq == seq && p.isOnline() && sessions.get(s.player()) == s) {
                s.view.stall(p, s, seq, c.fallback("stall", st.defaultFallbacks, random));
            }
        }, Math.max(1, st.stallSeconds) * 20L).getTaskId();
        armTimeout(p, s, turn, (Math.max(2, st.timeoutSeconds) + LOAD_ALLOWANCE_SECONDS) * 1000L);
        // 1인 하루 횟수: 공유 DB 의 오늘 행을 한도 미만일 때만 원자적으로 +1 (서버 간 합산). 관리자 테스트는 세지 않음
        java.util.concurrent.CompletableFuture<Boolean> gate = s.adminTest()
                ? java.util.concurrent.CompletableFuture.completedFuture(Boolean.TRUE) : plugin.budget().tryUseCall(pid);
        gate.whenComplete((ok, gex) -> plugin.sync(() -> {
            boolean allowed = gex == null && Boolean.TRUE.equals(ok);
            if (allowed && turn.charged) {
                turn.counted = true;
            }
            boolean active = s.busy && s.activeSeq == turn.seq && sessions.get(s.player()) == s;
            if (!active) {
                refund(turn); // 그사이 기한이 지나거나 대화가 닫힘: 올린 횟수는 돌려준다
                return;
            }
            if (!allowed) {
                if (gex != null || ok == null) {
                    plugin.getLogger().warning("[ChacaNPC] 하루 대화 횟수 확인 실패 (DB) — 이번 대화는 고정 대사");
                }
                failTurn(p, s, turn, ok == null ? "busy" : "tired");
                return;
            }
            s.loading.whenComplete((v, ex) -> plugin.sync(() -> proceedSafely(p, s, turn, finalText, buttonId, slot)));
        }));
    }

    /** 이 요청의 화면 기한. 지나면 고정 대사로 넘기고 늦게 온 결과는 버린다 (비용 정산은 그대로). */
    private void armTimeout(Player p, DialogueSession s, TurnState turn, long delayMs) {
        if (s.timeoutTaskId >= 0) {
            Bukkit.getScheduler().cancelTask(s.timeoutTaskId);
        }
        s.timeoutTaskId = Bukkit.getScheduler().runTaskLater(plugin, () -> {
            s.timeoutTaskId = -1;
            if (s.busy && s.activeSeq == turn.seq && sessions.get(s.player()) == s) {
                failTurn(p, s, turn, "busy");
            }
        }, Math.max(1L, delayMs / 50L)).getTaskId();
    }

    /** 하루 횟수 되돌리기 (요청당 한 번만). */
    private void refund(TurnState turn) {
        if (turn != null && turn.charged && turn.counted && !turn.refunded) {
            turn.refunded = true;
            plugin.budget().refundCall(turn.pid);
        }
    }

    /**
     * AI 대답 없이 이 요청을 끝낸다: "생각 중" 해제, 늦게 오는 결과는 버림(activeSeq), 고정 대사 표시.
     * AI에 아직 보내지 않았으면 하루 횟수를 바로 돌려준다 (보낸 뒤라면 결과가 왔을 때 과금 여부를 보고 돌려준다).
     */
    private void failTurn(Player p, DialogueSession s, TurnState turn, String fallbackKey) {
        cancelTimers(s);
        s.busy = false;
        s.activeSeq = -1;
        if (!turn.aiSent) {
            refund(turn);
        }
        if (p.isOnline() && sessions.get(s.player()) == s) {
            s.view.line(p, s, turn.seq, s.character().fallback(fallbackKey, plugin.settings().defaultFallbacks, random));
        }
    }

    /** AI를 부르기 전에 고정 대사로 끝낸다 (예약 실패·AI 쉬는 중). 하루 횟수는 돌려준다. */
    private void endWithoutAi(Player p, DialogueSession s, TurnState turn, String text, String fallbackKey) {
        cancelTimers(s);
        s.busy = false;
        refund(turn);
        say(p, s, turn.seq, text, s.character().fallback(fallbackKey, plugin.settings().defaultFallbacks, random), false);
    }

    /** proceed 가 어디서 실패해도 "생각 중"에 갇히지 않게 감싼다. */
    private void proceedSafely(Player p, DialogueSession s, TurnState turn, String text, String buttonId, int slot) {
        try {
            proceed(p, s, turn, text, buttonId, slot);
        } catch (RuntimeException ex) {
            plugin.getLogger().log(java.util.logging.Level.WARNING, "[ChacaNPC] 대화 준비 중 오류 (" + s.character().id() + ")", ex);
            if (s.busy && s.activeSeq == turn.seq && sessions.get(s.player()) == s) {
                failTurn(p, s, turn, "busy");
            } else if (!turn.aiSent) {
                refund(turn);
            }
        }
    }

    /** 고정 대사로 대답 (AI 없음). */
    private void say(Player p, DialogueSession s, int seq, String playerText, String line, boolean flagged) {
        s.view.line(p, s, seq, line);
        if (!flagged) {
            addTurns(s, playerText, line);
        }
        logChat(s, playerText, line, flagged);
    }

    private void strike(Player p, DialogueSession s, int seq, String text) {
        Settings st = plugin.settings();
        CharacterSheet c = s.character();
        boolean locked = strikes.addStrike(p.getUniqueId().toString(), c.id(), System.currentTimeMillis());
        if (locked) {
            addAffinity(s, "strike", -st.strikeAffinityPenalty);
            say(p, s, seq, text, c.fallback("locked", st.defaultFallbacks, random), true);
        } else {
            say(p, s, seq, text, c.fallback("confused", st.defaultFallbacks, random), true);
        }
    }

    /**
     * AI 대답 뒤 호감도 (#83, 2026-10-25). 새 Bridge: liked=true 면 호의적 대화 보상(유저별·NPC별 하루 횟수·지급량은 Bridge),
     * 양수 mood 로는 더 올리지 않는다(같은 대화로 두 번 오르지 않게). 무례한 대화(mood 음수)는 예전처럼 깎는다.
     * 옛 Bridge(chatReward 없음): 예전 mood 방식 그대로.
     */
    private void chatAffinity(DialogueSession s, ReplyParser.AiReply reply) {
        if (s.adminTest() || !link().available()) {
            return;
        }
        if (!link().chatRewards()) {
            addAffinity(s, "chat", reply.mood());
            return;
        }
        if (reply.mood() < 0) {
            addAffinity(s, "chat", reply.mood());
        }
        if (reply.liked() && !plugin.settings().chatRewardCriteria.isEmpty()) {
            link().chatReward(s.player(), s.character().id()).thenAccept(v -> {
                if (v != null && v.length >= 2) {
                    s.score = v[0];
                    s.heart = v[1];
                }
            });
        }
    }

    private void addAffinity(DialogueSession s, String source, int amount) {
        if (amount == 0 || s.adminTest() || !link().available()) {
            return;
        }
        link().addAffinity(s.player(), s.character().id(), source, amount).thenAccept(v -> {
            if (v != null && v.length >= 2) {
                s.score = v[0];
                s.heart = v[1];
            }
        });
    }

    // =================================================================== AI 호출

    private void proceed(Player p, DialogueSession s, TurnState turn, String text, String buttonId, int slot) {
        int seq = turn.seq;
        String pid = p.getUniqueId().toString();
        if (!p.isOnline() || sessions.get(p.getUniqueId()) != s || s.activeSeq != seq) {
            // 그새 닫혔거나 기한이 지나 이미 고정 대사로 넘어간 요청 (busy 는 그쪽에서 정리했다)
            refund(turn);
            return;
        }
        Settings st = plugin.settings();
        CharacterSheet c = s.character();
        long now = System.currentTimeMillis();
        if (plugin.ai().isCircuitBlocked()) {
            // AI가 연속으로 실패해 쉬는 중: 부르지 않고 바로 고정 대사
            endWithoutAi(p, s, turn, text, "busy");
            return;
        }
        long deadlineAt = Math.min(now + Math.max(2, st.timeoutSeconds) * 1000L,
                s.busySince + (Math.max(2, st.timeoutSeconds) + LOAD_ALLOWANCE_SECONDS) * 1000L);
        if (deadlineAt - now < 500L) {
            endWithoutAi(p, s, turn, text, "busy"); // 불러오기에 기한을 거의 다 썼다
            return;
        }
        BudgetMath.Status status = plugin.budget().status();

        AffinityStage stage = stage(s);
        PromptBuilder.Context x = new PromptBuilder.Context();
        x.nowMillis = now;
        x.stage = stage;
        x.playerName = s.npcNickname != null ? s.npcNickname : s.nickname != null ? s.nickname : link().playerName(p);
        x.storyChapter = s.storyChapter != null ? s.storyChapter : st.defaultStoryChapter;
        fillPlace(x, c, slot);
        x.todayMood = moodOfDay(c.id());
        x.likedCriteria = link().chatRewards() ? st.chatRewardCriteria : null; // 관리자 테스트도 판정은 보여 주고 지급만 안 함

        List<QuestDef> quests = pickQuests(p, s, x.todayMood);
        x.allowedQuests.addAll(quests);
        HintDef hint = pickHint(p, s, stage, text, buttonId);
        x.magicAsked = magicAsked(text, buttonId);
        if (hint != null) {
            x.allowedHints.add(hint);
            // 힌트를 프롬프트에 넣는 순간 서버가 기록한다. 대사가 플레이어에게 나가면 AI의 hint 값과 상관없이 횟수에 넣는다
            turn.offeredHint = hint.id();
        }
        List<RumorView> rumors = new ArrayList<>();
        for (RumorView r : s.rumors) {
            if (!s.usedRumors.contains(r.id()) && rumors.size() < st.rumorMaxInPrompt) {
                rumors.add(r);
            }
        }
        decideJealousy(s, rumors);
        boolean generic = buttonId != null && st.buttonCacheEnabled && quests.isEmpty() && hint == null && !s.jealous;
        x.generic = generic;
        if (!generic) {
            x.memos.addAll(s.memos);
            x.promises.addAll(s.promises);
            if (s.lastChat != null) {
                x.lastChat.addAll(s.lastChat.turns());
                x.lastChatAgo = TimeText.ago(s.lastChat.endedAt(), now);
            }
            x.rumors.addAll(rumors);
            x.jealous = s.jealous;
            x.jealousWithPromise = s.jealousWithPromise;
        }

        String instructions = PromptBuilder.instructions(plugin.content().commonPrompt(), st.worldLore,
                plugin.vibe().approvedNotes(), c, stage);
        List<Map<String, Object>> input = OpenAiClient.messages();
        String ctx = "[상황 정보 — 대사가 아님]\n" + PromptBuilder.contextMessage(c, x);
        input.add(OpenAiClient.message("user", ctx));
        int chars = instructions.length() + ctx.length() + text.length();
        if (!generic) {
            for (Turn t : s.recent) {
                input.add(t.isPlayer()
                        ? OpenAiClient.message("user", x.playerName + ": " + t.text())
                        : OpenAiClient.message("assistant", t.text()));
                chars += t.text().length() + 8;
            }
        }
        input.add(OpenAiClient.message("user", (generic ? "학생" : x.playerName) + ": " + text));

        int maxTokens = status == BudgetMath.Status.REDUCED ? st.reducedOutputTokens : st.maxOutputTokens;
        BudgetService.Reservation res = plugin.budget().reserve(s.adminTest() ? BudgetService.SYSTEM : pid,
                plugin.budget().estimate(chars, maxTokens));
        if (res == null) {
            endWithoutAi(p, s, turn, text, "tired");
            return;
        }
        boolean submitted = false;
        try {
            OpenAiClient.Request req = new OpenAiClient.Request(instructions, input, PromptBuilder.replySchema(),
                    "npc_reply", maxTokens, "chacanpc:" + c.id() + ":" + stage.ordinal());

            Set<String> questIds = new HashSet<>();
            for (QuestDef q : quests) {
                questIds.add(q.id());
            }
            Set<String> hintIds = hint == null ? Set.of() : Set.of(hint.id());
            Set<Long> rumorIds = new HashSet<>();
            for (RumorView r : x.rumors) {
                rumorIds.add(r.id());
            }
            ReplyParser.Limits limits = new ReplyParser.Limits(st.lineMaxLength, st.memoMaxLength, questIds, hintIds, rumorIds);

            // 화면 기한을 AI 요청 기준으로 다시 맞춘다 (입력 때 건 전체 기한보다 늦어지지는 않는다)
            armTimeout(p, s, turn, deadlineAt - now);

            UUID uuid = s.player();
            CompletableFuture<Boolean> moderation = st.moderationEnabled && buttonId == null
                    ? plugin.ai().moderate(text) : CompletableFuture.completedFuture(false);
            turn.aiSent = true;
            // 대기열에서 기한이 지났거나 그새 창을 닫았으면 보내지 않는다 (AI 스레드에서 확인)
            CompletableFuture<OpenAiClient.Result> call = plugin.ai().player(req, deadlineAt,
                    () -> sessions.get(uuid) == s && s.activeSeq == seq);
            submitted = true;
            call.thenCombine(moderation, (r, flagged) -> new Object[]{r, flagged})
                    .whenComplete((pair, ex) -> {
                        OpenAiClient.Result r = pair == null ? null : (OpenAiClient.Result) pair[0];
                        // 화면과 무관하게 비용은 반드시 정산 (늦은 응답·실패 포함)
                        plugin.budget().settle(res, r == null ? null : r.usage(), r != null && r.usageKnown());
                        if (r != null) {
                            plugin.budget().recordLatency(r.firstChunkMs(), r.totalMs());
                        }
                        boolean flagged = pair != null && Boolean.TRUE.equals(pair[1]);
                        plugin.sync(() -> {
                            if (r != null && r.notBilled()) {
                                refund(turn); // 보내지 않았거나 과금 전에 실패: 하루 횟수를 돌려준다
                            }
                            try {
                                finish(p, s, turn, text, buttonId, slot, generic, r, flagged, limits);
                            } catch (RuntimeException fex) {
                                plugin.getLogger().log(java.util.logging.Level.WARNING,
                                        "[ChacaNPC] 대답 처리 중 오류 (" + c.id() + ")", fex);
                                // finish 는 시작하자마자 busy 를 풀기 때문에 busy 로는 판단할 수 없다:
                                // 아직 아무 대사도 못 보냈으면 고정 대사로 끝낸다
                                if (!turn.answered && s.activeSeq == seq && sessions.get(uuid) == s) {
                                    failTurn(p, s, turn, "busy");
                                }
                            }
                        });
                    });
        } finally {
            if (!submitted) {
                turn.aiSent = false; // 대기열에 넣지 못했다: 하루 횟수를 돌려줄 수 있게
                plugin.budget().settle(res, OpenAiClient.Usage.ZERO, true); // 보내기 전에 실패: 예약을 풀어 준다
            }
        }
    }

    private void finish(Player p, DialogueSession s, TurnState turn, String text, String buttonId, int slot, boolean generic,
                        OpenAiClient.Result r, boolean flagged, ReplyParser.Limits limits) {
        int seq = turn.seq;
        UUID uuid = s.player();
        // 창을 닫았거나·재접속했거나·다른 NPC로 바꿨거나·시간 초과로 넘긴 요청이면 화면에 보내지 않는다
        if (!p.isOnline() || sessions.get(uuid) != s || s.activeSeq != seq) {
            return;
        }
        s.busy = false;
        cancelTimers(s);
        Settings st = plugin.settings();
        CharacterSheet c = s.character();
        String pid = uuid.toString();
        if (flagged) {
            turn.answered = true;
            strike(p, s, seq, text);
            return;
        }
        if (r == null || !r.ok()) {
            // 실패 종류별 집계·경고 로그는 OpenAiClient 가 항상 남긴다 (debug 와 무관). 여기서는 자세한 내용만.
            if (r != null && st.debug) {
                plugin.getLogger().info("[ChacaNPC] AI 실패 " + r.category() + ": " + r.error());
            }
            turn.answered = true;
            say(p, s, seq, text, c.fallback("busy", st.defaultFallbacks, random), false);
            return;
        }
        ReplyParser.AiReply reply = ReplyParser.parse(r.text(), limits);
        if (reply == null) {
            turn.answered = true;
            say(p, s, seq, text, c.fallback("busy", st.defaultFallbacks, random), false);
            return;
        }
        TextFilter.Verdict out = plugin.content().filter().checkOutput(reply.line());
        if (out != TextFilter.Verdict.OK) {
            if (st.debug) {
                plugin.getLogger().info("[ChacaNPC] 대사 차단(" + out + "): " + reply.line());
            }
            turn.answered = true;
            s.view.line(p, s, seq, c.fallback("confused", st.defaultFallbacks, random));
            logChat(s, text, "[차단:" + out + "] " + reply.line(), true);
            return;
        }

        long now = System.currentTimeMillis();
        if (!s.adminTest()) {
            if (!generic) {
                chatAffinity(s, reply);
            }
            // 메모·약속은 플레이어가 내용을 유도할 수 있는 글이다: 정리 + 금지어 검사를 통과한 것만 저장한다.
            // 버튼 캐시용(generic) 대답은 특정 플레이어 이야기가 아니므로 저장하지 않는다.
            TextFilter filter = plugin.content().filter();
            String memo = generic ? null : filter.storable(reply.memo(), st.memoMaxLength);
            String promise = generic ? null : filter.storable(reply.promise(), st.memoMaxLength);
            if (memo != null) {
                s.memos.add(memo);
                plugin.database().run(() -> plugin.storage().addMemo(pid, c.id(), memo, st.memoMaxLines, now));
            }
            if (promise != null) {
                s.promises.add(0, promise);
                plugin.social().recordEvent(uuid, link().playerName(p), c.id(), "promise", promise);
            }
            // 힌트: AI가 hint 를 적었는지와 상관없이, 서버가 이번 프롬프트에 넣었고 대사가 나가면 준 것으로 센다
            // (출력이 잘리거나 hint 를 null 로 유도해도 하루 횟수·1회 제한을 피할 수 없다)
            if (turn.offeredHint != null) {
                String h = turn.offeredHint;
                turn.offeredHint = null;
                if (s.hints != null) {
                    Set<String> given = new HashSet<>(s.hints.given());
                    given.add(h);
                    s.hints = new Storage.HintState(given, s.hints.today() + 1);
                }
                plugin.database().run(() -> plugin.storage().addHint(pid, h, c.id(), now));
            }
            if (reply.rumorId() != null) {
                long rid = reply.rumorId();
                s.usedRumors.add(rid);
                plugin.database().run(() -> plugin.storage().markRumorMentioned(rid));
            }
        }
        turn.answered = true;
        s.view.line(p, s, seq, reply.line());
        addTurns(s, text, reply.line());
        logChat(s, text, reply.line(), false);

        if (generic && buttonId != null) {
            buttonCache.add(st.today().toString(), c.id(), slot, buttonId, reply.line(), pid, st.buttonCacheVariants);
        }
        if (reply.quest() != null && link().available() && link().questOfferable(p, reply.quest())) {
            String title = link().questTitle(reply.quest());
            s.questOffered = true;
            String token = Long.toString(tokens.nextLong() & Long.MAX_VALUE, 36);
            pendingQuests.put(token, new PendingQuest(uuid, c.id(), s.token(), reply.quest(), now + 5 * 60_000L));
            s.view.questOffer(p, s, seq, title.isBlank() ? reply.quest() : title, token);
        }
        if (s.adminTest()) {
            s.view.info(p, s, String.format(Locale.ROOT, "(테스트) 입력 %d (캐시 %d) / 출력 %d 토큰, 첫 글자 %dms, 전체 %dms, 기분 %+d%s%s%s",
                    r.usage().input(), r.usage().cached(), r.usage().output(), r.firstChunkMs(), r.totalMs(), reply.mood(),
                    (reply.liked() ? ", 마음에 듦(테스트는 지급 안 함)" : "") + (reply.quest() == null ? "" : ", 퀘스트 " + reply.quest()),
                    limits.allowedHints().isEmpty() ? "" : ", 힌트 제공 " + limits.allowedHints()
                            + (reply.hint() == null ? " (AI 표시 없음)" : ""),
                    reply.memo() == null ? "" : ", 메모 \"" + reply.memo() + "\""));
        }
        if (reply.end()) {
            close(p, false);
        }
    }

    private void addTurns(DialogueSession s, String playerText, String npcLine) {
        if (!s.adminTest()) link().stat(s.player(), "대화:" + s.character().id(), 1); // 통계 #96: NPC 별 주고받은 말 수
        int keep = plugin.settings().recentTurns * 2;
        s.recent.add(new Turn("player", playerText));
        s.recent.add(new Turn("npc", npcLine));
        while (s.recent.size() > keep) {
            s.recent.remove(0);
        }
        s.saveable.add(new Turn("player", playerText));
        s.saveable.add(new Turn("npc", npcLine));
        int maxSave = Math.max(keep, plugin.settings().lastChatTurns * 2) * 2;
        while (s.saveable.size() > maxSave) {
            s.saveable.remove(0);
        }
    }

    private void logChat(DialogueSession s, String playerText, String npcText, boolean flagged) {
        if (s.adminTest()) {
            return;
        }
        String pid = s.player().toString();
        String name = s.playerName();
        String npc = s.character().id();
        long now = System.currentTimeMillis();
        plugin.database().run(() -> plugin.storage().addChat(pid, name, npc, playerText, npcText, flagged, now));
    }

    // =================================================================== 상황 판단

    private void fillPlace(PromptBuilder.Context x, CharacterSheet c, int slot) {
        if (slot >= 0 && slot < c.schedule().size()) {
            var entry = c.schedule().get(slot);
            var place = plugin.places().get(entry.place());
            x.placeLabel = place != null ? place.label() : entry.place();
            x.activity = entry.activity();
        }
        x.partOfDay = plugin.npcs().partOfDay(c);
    }

    /** 그날 NPC 기분: 날짜+NPC로 정해져서 하루 동안 같다. */
    public String moodOfDay(String npcId) {
        int h = Math.floorMod((plugin.settings().today().toString() + npcId).hashCode(), 100);
        return h < 30 ? "좋음" : h < 80 ? "보통" : "별로";
    }

    /** 제안 가능한 퀘스트: 판단은 MagicCodex 기존 데이터(공개·권한·진행 중 여부) + 이 NPC 쿨타임·확률. */
    private List<QuestDef> pickQuests(Player p, DialogueSession s, String mood) {
        Settings st = plugin.settings();
        CharacterSheet c = s.character();
        if (!link().available() || s.questOffered || s.quests == null || c.quests() == null || c.quests().isEmpty()) {
            return List.of();
        }
        if (s.questRoll == null) {
            double chance = "좋음".equals(mood) ? st.questGoodMoodChance : st.questChance;
            s.questRoll = s.adminTest() || random.nextDouble() < chance;
        }
        if (!s.questRoll || s.score < st.questMinScore) {
            return List.of();
        }
        long now = System.currentTimeMillis();
        if (now - s.quests.lastAcceptedFromNpc() < st.questCooldownMinutes * 60_000L) {
            return List.of();
        }
        if (link().activeQuestCount(p) >= st.questMaxActive) {
            return List.of();
        }
        List<QuestDef> out = new ArrayList<>();
        for (String id : c.quests()) {
            if (!link().questOfferable(p, id)) {
                continue;
            }
            QuestDef local = plugin.content().quest(id);
            int minScore = local == null ? 0 : local.minScore();
            if (s.score < minScore) {
                continue;
            }
            String title = link().questTitle(id);
            String summary = local == null ? "" : local.summary();
            out.add(new QuestDef(id, title.isBlank() ? id : title, summary, minScore, List.of()));
        }
        Collections.shuffle(out, random);
        return out.size() > 2 ? out.subList(0, 2) : out;
    }

    /** 마법 이야기를 물었는지: 마법 버튼(id magic) 또는 말 속의 마법 낱말. */
    private static boolean magicAsked(String text, String buttonId) {
        if ("magic".equals(buttonId)) {
            return true;
        }
        String lower = text == null ? "" : text.toLowerCase(Locale.ROOT);
        for (String w : MAGIC_WORDS) {
            if (lower.contains(w)) {
                return true;
            }
        }
        return false;
    }

    private HintDef pickHint(Player p, DialogueSession s, AffinityStage stage, String text, String buttonId) {
        Settings st = plugin.settings();
        CharacterSheet c = s.character();
        if (s.hints == null || s.hints.today() >= st.hintDailyPerPlayer || c.hintTopics() == null
                || c.hintTopics().isEmpty()) {
            return null;
        }
        // 호감도가 낮거나 보통(모르는 사이·아는 사이)이면 힌트를 주지 않는다 → "잘 모르겠는데?" (오너 결정 2026-10-23)
        if (stage.ordinal() <= AffinityStage.ACQUAINTANCE.ordinal()) {
            return null;
        }
        boolean asked = magicAsked(text, buttonId);
        if (!asked && random.nextDouble() >= 0.25) {
            return null;
        }
        int minDifficulty = stage.ordinal() == 2 ? 2 : 1;
        List<HintDef> candidates = new ArrayList<>();
        for (HintDef h : plugin.content().hints()) {
            if (!c.hintTopics().contains(h.topic()) || s.hints.given().contains(h.id()) || h.difficulty() < minDifficulty) {
                continue;
            }
            // MagicDiscovery가 준 권한으로 이미 발견한 마법인지 확인
            if (p.hasPermission(String.format(Locale.ROOT, st.discoveryPermission, h.spell()))) {
                continue;
            }
            candidates.add(h);
        }
        if (candidates.isEmpty()) {
            return null;
        }
        HintDef chosen = candidates.get(random.nextInt(candidates.size()));
        if (chosen.materials() == null || chosen.materials().isBlank()) {
            // 목격담 재료가 비었으면 MagicCodexBridge 마법별 힌트 파일(spell-hints.yml)의 2성 문장으로 (2026-10-26). 없으면 그대로.
            String line = link().spellHint(chosen.spell());
            if (line != null) {
                chosen = new HintDef(chosen.id(), chosen.spell(), chosen.topic(), line, chosen.difficulty(), chosen.vague());
            }
        }
        return chosen;
    }

    private void decideJealousy(DialogueSession s, List<RumorView> rumors) {
        if (s.jealousDecided) {
            return;
        }
        s.jealousDecided = true;
        Settings st = plugin.settings();
        if (s.score < st.jealousyMinScore) {
            return;
        }
        boolean romanceRumor = false;
        for (RumorView r : rumors) {
            if (r.isRomance()) {
                romanceRumor = true;
                break;
            }
        }
        if (!romanceRumor) {
            return;
        }
        String key = s.player() + "|" + s.character().id();
        String today = st.today().toString();
        if (today.equals(jealousToday.get(key))) {
            return;
        }
        boolean promise = !s.promises.isEmpty();
        double chance = promise ? st.jealousyPromiseChance : st.jealousyChance;
        if (random.nextDouble() < chance) {
            s.jealous = true;
            s.jealousWithPromise = promise;
            jealousToday.put(key, today);
            addAffinity(s, "jealousy", -1);
        }
    }

    // =================================================================== 퀘스트 수락

    /** 채팅 버튼용. */
    public void answerQuest(Player p, String token, boolean accept) {
        DialogueSession s = sessions.get(p.getUniqueId());
        answerQuest(p, token, accept, s == null ? 0 : ++s.chatSeq);
    }

    public void answerQuest(Player p, String token, boolean accept, int seq) {
        DialogueView view = viewFor(p);
        DialogueSession s = sessions.get(p.getUniqueId());
        if (s != null) {
            s.replySeq = seq;
        }
        PendingQuest pq = pendingQuests.get(token);
        // 토큰은 플레이어·NPC·대화 세션·만료 시각에 묶인다. 검증을 통과한 뒤에만 (한 번) 제거한다.
        if (pq == null || !pq.player().equals(p.getUniqueId()) || s == null
                || !s.token().equals(pq.sessionToken()) || !s.character().id().equals(pq.npcId())) {
            view.info(p, s, "(이미 지난 부탁이에요)");
            return;
        }
        if (System.currentTimeMillis() > pq.expiresAt()) {
            pendingQuests.remove(token, pq);
            view.info(p, s, "(시간이 지나서 부탁이 사라졌어요)");
            return;
        }
        if (!pendingQuests.remove(token, pq)) {
            return; // 동시에 들어온 같은 토큰
        }
        CharacterSheet c = plugin.characters().get(pq.npcId());
        if (c == null) {
            return;
        }
        Settings st = plugin.settings();
        String pid = p.getUniqueId().toString();
        long offeredAt = pq.expiresAt() - 5 * 60_000L;
        if (!accept) {
            plugin.database().run(() -> plugin.storage().addQuestLog(pid, c.id(), pq.questId(), offeredAt, false, 0));
            replyQuest(p, c, seq, c.fallback("quest_decline", st.defaultFallbacks, random));
            return;
        }
        if (!link().available()) {
            view.info(p, s, "(지금은 부탁을 받을 수 없어요)");
            return;
        }
        // 수락 조건·중복·권한·보상은 MagicCodex 기존 의뢰 경로가 판단
        link().acceptQuest(p, pq.questId()).whenComplete((ok, ex) -> {
            if (!p.isOnline()) {
                return;
            }
            long now = System.currentTimeMillis();
            if (!Boolean.TRUE.equals(ok)) {
                viewFor(p).info(p, sessions.get(p.getUniqueId()), "(지금은 이 부탁을 받을 수 없어요)");
                return;
            }
            plugin.database().run(() -> plugin.storage().addQuestLog(pid, c.id(), pq.questId(), offeredAt, true, now));
            String title = link().questTitle(pq.questId());
            plugin.social().recordEvent(p.getUniqueId(), link().playerName(p), c.id(), "quest_accept",
                    link().playerName(p) + "이(가) " + c.name() + "의 부탁(" + (title.isBlank() ? pq.questId() : title) + ")을 들어주기로 함");
            DialogueSession cur = sessions.get(p.getUniqueId());
            if (cur != null && cur.quests != null && cur.character().id().equals(c.id())) {
                Set<String> acc = new HashSet<>(cur.quests.accepted());
                acc.add(pq.questId());
                cur.quests = new Storage.QuestState(now, acc, cur.quests.acceptedLastDay() + 1);
            }
            replyQuest(p, c, seq, c.fallback("quest_thanks", st.defaultFallbacks, random));
        });
    }

    private void replyQuest(Player p, CharacterSheet c, int seq, String line) {
        DialogueSession s = sessions.get(p.getUniqueId());
        if (s != null && s.character().id().equals(c.id())) {
            s.view.line(p, s, seq, line);
            s.lastActivity = System.currentTimeMillis();
        } else {
            p.sendMessage("[" + c.name() + "] " + line);
        }
    }

    // =================================================================== 버튼 대답 미리 만들기

    /** 근처에 플레이어가 있는 NPC의 일과표 칸, 또는 누군가 말을 건 NPC의 버튼 대답을 백그라운드에서 채운다 (예산 예약 포함). */
    public void warmup(CharacterSheet c, int slot) {
        Settings st = plugin.settings();
        if (!st.buttonCacheEnabled || !st.buttonCacheWarmup || !plugin.budget().isAiEnabled()
                || plugin.budget().status() != BudgetMath.Status.NORMAL) {
            return;
        }
        String today = st.today().toString();
        // 같은 NPC·칸을 짧은 시간에 여러 번 채우지 않는다 (이동 틱·대화 열기 양쪽에서 불린다)
        long now = System.currentTimeMillis();
        String key = today + "|" + c.id() + "|" + slot;
        Long last = warmupAt.get(key);
        if (last != null && now - last < WARMUP_RETRY_MS) {
            return;
        }
        warmupAt.put(key, now);
        int delay = 0;
        for (Button b : c.buttonsOr(st.buttons)) {  // NPC 전용 버튼이면 그 버튼으로 미리 만든다
            if (!b.aiReply()) {
                continue; // 상점 버튼 등: AI 대답이 없으므로 미리 만들지 않는다
            }
            int have = buttonCache.size(today, c.id(), slot, b.id());
            int need = Math.min(2, st.buttonCacheVariants - have);
            for (int i = 0; i < need; i++) {
                delay += 20 + random.nextInt(40);
                Bukkit.getScheduler().runTaskLater(plugin, () -> generateGeneric(c, slot, b), delay);
            }
        }
    }

    private void generateGeneric(CharacterSheet c, int slot, Button b) {
        Settings st = plugin.settings();
        if (plugin.budget().status() != BudgetMath.Status.NORMAL) {
            return;
        }
        PromptBuilder.Context x = new PromptBuilder.Context();
        x.generic = true;
        x.magicAsked = "magic".equals(b.id()); // 미리 만드는 대답에는 힌트가 없다 → 마법 버튼이면 얼버무리는 대답
        x.nowMillis = System.currentTimeMillis();
        fillPlace(x, c, slot);
        x.todayMood = moodOfDay(c.id());
        AffinityStage stage = AffinityStage.ACQUAINTANCE;
        String instructions = PromptBuilder.instructions(plugin.content().commonPrompt(), st.worldLore,
                plugin.vibe().approvedNotes(), c, stage);
        String ctx = "[상황 정보 — 대사가 아님]\n" + PromptBuilder.contextMessage(c, x);
        List<Map<String, Object>> input = OpenAiClient.messages();
        input.add(OpenAiClient.message("user", ctx));
        input.add(OpenAiClient.message("user", "학생: " + b.label()));
        BudgetService.Reservation res = plugin.budget().reserve(BudgetService.SYSTEM,
                plugin.budget().estimate(instructions.length() + ctx.length() + 20, st.maxOutputTokens));
        if (res == null) {
            return;
        }
        OpenAiClient.Request req = new OpenAiClient.Request(instructions, input, PromptBuilder.replySchema(),
                "npc_reply", st.maxOutputTokens, "chacanpc:" + c.id() + ":" + stage.ordinal());
        ReplyParser.Limits limits = new ReplyParser.Limits(st.lineMaxLength, st.memoMaxLength, Set.of(), Set.of(), Set.of());
        // 백그라운드 요청: 플레이어 대화가 붐비면 받지 않는다 (그때는 그냥 건너뜀)
        plugin.ai().background(req).whenComplete((r, ex) -> {
            plugin.budget().settle(res, r == null ? null : r.usage(), r != null && r.usageKnown());
            plugin.sync(() -> {
                if (r == null || !r.ok()) {
                    return;
                }
                ReplyParser.AiReply reply = ReplyParser.parse(r.text(), limits);
                if (reply != null && plugin.content().filter().checkOutput(reply.line()) == TextFilter.Verdict.OK) {
                    buttonCache.add(st.today().toString(), c.id(), slot, b.id(), reply.line(), null, st.buttonCacheVariants);
                }
            });
        });
    }
}
