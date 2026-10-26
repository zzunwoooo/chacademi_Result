package kr.chacademy.storyplugin;

import kr.chacademy.storyplugin.StoryState.Effect;
import kr.chacademy.storyplugin.StoryState.Kind;
import kr.chacademy.storyplugin.StoryState.PlayerStory;
import kr.chacademy.storyplugin.StoryState.Request;
import com.mojang.brigadier.builder.ArgumentBuilder;
import com.mojang.brigadier.builder.RequiredArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.suggestion.Suggestions;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.ConsoleCommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.server.ServiceRegisterEvent;
import org.bukkit.event.server.ServiceUnregisterEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.plugin.messaging.Messenger;
import org.bukkit.plugin.messaging.PluginMessageListener;
import org.jetbrains.annotations.NotNull;

import java.io.File;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;

/**
 * 차카데미 메인 스토리 서버 쪽.
 * <ul>
 *   <li>/대화관리 재생 &lt;닉네임|모두&gt; 컷신 &lt;id&gt; → 컷신, 끝나면 config on-finish 명령</li>
 *   <li>/대화관리 재생 &lt;닉네임|모두&gt; 대화 &lt;id&gt; → 대화 (콘솔 가능), 끝나면 dialogues/server_command.yml 의 명령</li>
 *   <li>/테스트 스토리 대화|컷신 &lt;id&gt; [닉네임] → 기록·보상 없는 미리보기, /유저관리 &lt;닉네임&gt; 스토리기록 → 기록 보기·초기화</li>
 *   <li>명령은 MagicCodexBridge 명령어 허브 ({@link ChacaLink}) 에 붙는다. 호감도는 MagicCodex 하나로만 ({@link MagicCodexLink})</li>
 *   <li>대사의 {player} = MagicCodex 한글 닉네임, config 의 dialogue-placeholders = PlaceholderAPI 값</li>
 * </ul>
 * 구조:
 * <ul>
 *   <li>재생 요청은 플레이어별 대기열에 들어가고 한 번에 하나만 재생된다 (컷신 중에 온 대화, 대화 중에 온 대화 모두 차례를 기다림).
 *       모드 채널이 잡힐 때까지 기다렸다가 보내며, 끝날 때까지 progress.yml 에 남아 다시 접속하면 이어진다.</li>
 *   <li>대화 흐름은 서버의 dialogues/&lt;id&gt;/dialogue.yml 로 검증한다 ({@link StoryGraph.Walk}).</li>
 *   <li>효과 (명령어·호감도) 는 "기록을 디스크에 쓴 뒤" 실행한다. 파일 쓰기는 {@link StoryStore} 의 쓰기 스레드가 한다.</li>
 *   <li>보는 동안 플레이어 보호는 {@link StoryProtection}.</li>
 * </ul>
 * 모든 상태는 메인 스레드에서만 건드린다.
 */
public class StoryPlugin extends JavaPlugin implements PluginMessageListener, Listener {
    /** 접속 직후 이만큼은 기다렸다가 스토리를 보낸다 (화면이 다 뜨기 전에 보내지 않게). */
    private static final long JOIN_DELAY_MS = 2000;
    /** 접속 뒤 이 시간까지 모드 채널이 안 잡히면 "모드 없음" 으로 본다 (채널 등록은 접속 뒤 몇 틱 늦게 온다). */
    private static final long WAIT_MS = 15000;
    /** 한 번의 대화에서 흐름에 맞지 않는 패킷이 이만큼 오면 대화를 멈춘다. */
    private static final int ILLEGAL_LIMIT = 20;
    private static final char NO_PATH_SEPARATOR = '\u0001';
    private static final long MAX_YML_BYTES = 2L * 1024 * 1024;

    private StoryState state = new StoryState();
    private StoryStore store;
    /** 대화 정의 (흐름 + 명령어). reload 때 통째로 바꾼다. */
    private Map<String, StoryDef> defs = Map.of();
    /** 파일이 있지만 읽을 수 없는 대화 id → 이유. */
    private Map<String, String> brokenDefs = Map.of();
    private final Map<UUID, StorySafety.TokenBucket> buckets = new HashMap<>();
    private final Map<UUID,StoryCatalog> clientCatalogs=new HashMap<>();
    private final Map<UUID,Long> catalogUpdated=new HashMap<>();
    private List<String> registeredCutscenes=List.of();
    private final Map<UUID, Long> joinedAt = new HashMap<>();
    private final ReloadTickets<CommandSender> reloadTickets=new ReloadTickets<>();
    private final ServerCommandRegistry endingCommands=new ServerCommandRegistry();
    private final LocalEndingSessions endingSessions=new LocalEndingSessions();
    private final DialogueAcks dialogueAcks = new DialogueAcks();
    /** 서버가 결말까지 확인한 마지막 로컬 대화 완료 (보상 패킷을 이 완료에 묶는다). 메모리만, 나가면 지운다. */
    private record Finished(String id, String ending, long at) {}
    private final Map<UUID, Finished> recentFinish = new HashMap<>();
    static final long REWARD_WINDOW_MS = 60_000;
    /** 로컬 대화에 함께 보내는 MagicCodex 호감도 글자 키 앞부분 (모드 Affinity.fromVars 와 같게). */
    static final String AFFINITY_VAR = "@affinity:";
    private final Set<UUID> helloSent = new HashSet<>();
    private final Map<UUID, Long> lastPacketLog = new HashMap<>();
    private MagicCodexLink codex;
    private StoryProtection protection;
    /** 스토리 진행 단계 (조건 → 호출 → 다음 대화, HUD). */
    private QuestEngine quests;

    private File progressFile;
    private boolean ready;
    private boolean dirty;
    private boolean flushScheduled;
    private final List<Runnable> afterDurable = new ArrayList<>();
    private int beat;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        File dialogues = new File(getDataFolder(), "dialogues");
        dialogues.mkdirs();
        codex = new MagicCodexLink(getLogger());
        protection = new StoryProtection(() -> getConfig().getBoolean("protect-during-story", true),
                () -> Math.max(0, getConfig().getLong("protect-max-seconds", 900)) * 1000L);
        progressFile = new File(getDataFolder(), "progress.yml");
        loadProgress();
        store = new StoryStore(text -> StorySafety.atomicWrite(progressFile.toPath(), text), StoryStore.newWorker(),
                r -> Bukkit.getScheduler().runTask(this, r), getLogger());
        ready = true;
        applyDefs(loadDefs(dialogues), null);
        try {
            reloadServerSettings();
        } catch (Exception e) {
            getLogger().log(Level.SEVERE, "스토리 서버 설정을 읽지 못했습니다 (config.yml / dialogues/server_command.yml)", e);
        }
        registeredCutscenes=getConfig().getStringList("cutscenes").stream().filter(StorySafety::validId).toList();

        Messenger m = getServer().getMessenger();
        for (String out : List.of(StoryCodec.CUTSCENE_PLAY, StoryCodec.CUTSCENE_STOP, StoryCodec.DIALOGUE_OPEN, StoryCodec.DIALOGUE_VIEW, StoryCodec.DIALOGUE_CONTENT, StoryCodec.DIALOGUE_STOP,
                StoryCodec.STORY_HELLO, StoryCodec.STORY_RELOAD, StoryCodec.DIALOGUE_SESSION, StoryCodec.RELOAD_REQUEST)) {
            m.registerOutgoingPluginChannel(this, out);
        }
        for (String in : List.of(StoryCodec.CUTSCENE_DONE, StoryCodec.DIALOGUE_EVENT, StoryCodec.DIALOGUE_DONE, StoryCodec.DIALOGUE_PROGRESS,
                StoryCodec.CUTSCENE_ABORT, StoryCodec.STORY_FAIL, StoryCodec.DIALOGUE_READY, StoryCodec.DIALOGUE_REWARD, StoryCodec.STORY_CATALOG, StoryCodec.DIALOGUE_SESSION_SIGNAL, StoryCodec.RELOAD_RESULT)) {
            m.registerIncomingPluginChannel(this, in, this);
        }
        quests = new QuestEngine(this);
        quests.enable();
        registerCommands();
        getServer().getPluginManager().registerEvents(this, this);
        getServer().getPluginManager().registerEvents(protection, this);
        Bukkit.getScheduler().runTaskTimer(this, this::heartbeat, 20, 20);
        // /reload 등으로 켜졌을 때 이미 접속해 있는 사람도 이어서
        long now = System.currentTimeMillis();
        for (Player p : Bukkit.getOnlinePlayers()) joinedAt.put(p.getUniqueId(), now);
        // MagicCodexBridge 가 서비스를 등록한 뒤 연결 (서버가 다 켜진 다음 틱)
        Bukkit.getScheduler().runTask(this, () -> {
            if (!codex.connect()) getLogger().warning("MagicCodexBridge 없음 — 스토리 호감도는 적용되지 않고, {player} 는 PlaceholderAPI 또는 마인크래프트 닉네임을 씁니다");
        });
        if (getConfig().getBoolean("finish-if-missing-mod", false)) {
            getLogger().warning("finish-if-missing-mod: true — 모드가 없는 (또는 모드 채널을 숨긴) 플레이어는 스토리를 보지 않고도 끝 명령·호감도를 받습니다.");
        }
        getLogger().info("차카데미 스토리 준비 완료 (프로토콜 " + StoryCodec.PROTOCOL + ")");
    }

    @Override
    public void onDisable() {
        if (quests != null) quests.disable();
        dialogueAcks.clear();endingSessions.clear();reloadTickets.clear();
        clientCatalogs.clear();catalogUpdated.clear();
        Messenger m = getServer().getMessenger();
        m.unregisterIncomingPluginChannel(this);
        m.unregisterOutgoingPluginChannel(this);
        // 서버가 꺼질 때만 이 스레드에서 바로 쓴다
        if (ready && store != null) {
            Map<String, Object> snap = state.snapshot();
            store.flushNow(() -> render(snap));
        }
        ready = false;
        if (protection != null) protection.clear();
        buckets.clear();
        joinedAt.clear();
        helloSent.clear();
    }

    public MagicCodexLink codex() {
        return codex;
    }

    // ================================================================ 저장 (progress.yml)

    /** yml 을 읽은 것을 Map / List / 값으로만 된 나무로 바꾼다. */
    private static Object plain(Object o) {
        if (o instanceof ConfigurationSection s) {
            Map<String, Object> m = new LinkedHashMap<>();
            for (var e : s.getValues(false).entrySet()) m.put(e.getKey(), plain(e.getValue()));
            return m;
        }
        if (o instanceof Map<?, ?> map) {
            Map<String, Object> m = new LinkedHashMap<>();
            for (var e : map.entrySet()) m.put(String.valueOf(e.getKey()), plain(e.getValue()));
            return m;
        }
        if (o instanceof List<?> l) {
            List<Object> out = new ArrayList<>();
            for (Object x : l) out.add(plain(x));
            return out;
        }
        return o;
    }

    /** 키에 점(.)이 있어도 쪼개지지 않게 읽는다. 아무 스레드에서나 부를 수 있다. */
    static Map<?, ?> readYaml(File f) throws Exception {
        if (f.length() > MAX_YML_BYTES) throw new java.io.IOException("파일이 너무 큼 (" + f.length() + " 바이트)");
        YamlConfiguration y = new YamlConfiguration();
        y.options().pathSeparator(NO_PATH_SEPARATOR);
        y.load(f);
        return (Map<?, ?>) plain(y);
    }

    private void loadProgress() {
        state = new StoryState();
        if (!progressFile.isFile()) return;
        Map<?, ?> root;
        try {
            root = readYaml(progressFile);
        } catch (Exception e) {
            throw new IllegalStateException("Cannot read story progress; preserving the existing file", e);
        }
        boolean legacy = !root.isEmpty() && root.get("format") == null;
        List<String> notes = new ArrayList<>();
        try {
            state = StoryState.fromMap(root, notes);
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException("Cannot use story progress; preserving the existing file", e);
        }
        if (legacy) {
            // 예전 형식은 한 번 복사해 두고, 다음 저장부터 새 형식으로 쓴다
            File backup = new File(getDataFolder(), "progress.yml.v1-backup");
            try {
                if (!backup.exists()) Files.copy(progressFile.toPath(), backup.toPath());
            } catch (java.io.IOException | RuntimeException e) {
                getLogger().log(Level.WARNING, "예전 progress.yml 을 복사해 두지 못했습니다", e);
            }
            dirty = true;
        }
        for (String n : notes) getLogger().info(n);
        int active = 0, effects = 0;
        for (PlayerStory ps : state.players.values()) {
            if (ps.active != null || !ps.queue.isEmpty()) active++;
            effects += ps.effects.size();
        }
        if (active > 0) getLogger().info("끝나지 않은 스토리가 있는 플레이어 " + active + "명 (접속하면 이어서 열림)");
        if (effects > 0) getLogger().info("실행 대기 중인 스토리 효과 " + effects + "개 (접속하면 실행)");
    }

    /** 쓰기 스레드에서 불린다: 복사본만 읽는다. */
    private static String render(Map<String, Object> snap) {
        YamlConfiguration y = new YamlConfiguration();
        y.options().pathSeparator(NO_PATH_SEPARATOR);
        y.options().setHeader(List.of("스토리 진행·완료 기록 (format 2). 서버가 켜져 있을 때 직접 고치지 마세요.",
                "active/queue = 보는 중·대기 중, done/fired/gained = 완료 기록, effects = 저장 뒤 실행 대기."));
        y.set("format", snap.get("format"));
        y.createSection("players", (Map<?, ?>) snap.get("players"));
        return y.saveToString();
    }

    /** 바뀐 내용을 다음 틱에 한 번에 저장한다. after = 그 저장이 디스크에 끝난 뒤 메인 스레드에서 실행할 것. */
    private void saveSoon(Runnable after) {
        dirty = true;
        if (after != null) afterDurable.add(after);
        if (flushScheduled || !ready) return;
        flushScheduled = true;
        Bukkit.getScheduler().runTask(this, this::flush);
    }

    private void flush() {
        flushScheduled = false;
        if (!ready || (!dirty && afterDurable.isEmpty())) return;
        dirty = false;
        Map<String, Object> snap = state.snapshot();
        List<Runnable> callbacks = new ArrayList<>(afterDurable);
        afterDurable.clear();
        store.submit(() -> render(snap), callbacks);
    }

    // ================================================================ 대화 정의 읽기 (서버 켤 때 / reload)

    private record DefLoad(Map<String, StoryDef> defs, Map<String, String> broken, List<String> warnings, Map<String, String> texts) {}

    /** 대화 id → 서버 dialogue.yml 글 (클라이언트에 그대로 보낸다). */
    private Map<String, String> dialogueTexts = Map.of();

    /** 대화를 열기 직전에 내용을 보낸다 (최신 스토리 모드만 받음). 예전 모드는 자기 config/chaca_dialogue 파일을 쓴다. */
    private void sendContent(Player p, String id) {
        String text = dialogueTexts.get(id);
        if (text == null || !p.getListeningPluginChannels().contains(StoryCodec.DIALOGUE_CONTENT)) return;
        p.sendPluginMessage(this, StoryCodec.DIALOGUE_CONTENT, StoryCodec.dialogueContent(id, text));
    }

    /**
     * dialogues 폴더를 읽는다 (파일 I/O — 서버를 켤 때와 reload 의 비동기 작업에서만 부른다).
     * <pre>
     * dialogues/&lt;id&gt;/dialogue.yml          흐름 (편집기 zip 의 것 그대로)
     * dialogues/&lt;id&gt;/server_commands.yml   명령어·호감도 (편집기 zip 의 것 그대로)
     * dialogues/&lt;id&gt;.yml                   예전 위치의 명령어 파일 (폴더 안에 server_commands.yml 이 없을 때만 씀)
     * </pre>
     */
    private static DefLoad loadDefs(File dir) {
        Map<String, StoryDef> out = new TreeMap<>();
        Map<String, String> broken = new TreeMap<>();
        List<String> warnings = new ArrayList<>();
        Map<String, String> texts = new TreeMap<>();
        Set<String> ids = new TreeSet<>();
        File[] files = dir.listFiles();
        if (files != null) {
            for (File f : files) {
                String n = f.getName();
                if (f.isDirectory()) {
                    if (new File(f, "dialogue.yml").isFile() || new File(f, "server_commands.yml").isFile()) {
                        if (StorySafety.validId(n)) ids.add(n);
                        else warnings.add("대화 폴더 이름은 소문자·숫자·_·- 1~64자 (무시됨): " + n);
                    }
                } else if (n.endsWith(".yml")&&!n.equals("server_command.yml")) {
                    String id = n.substring(0, n.length() - 4);
                    if (StorySafety.validId(id)) ids.add(id);
                    else warnings.add("대화 파일 이름은 소문자·숫자·_·- 1~64자 (무시됨): " + n);
                }
            }
        }
        for (String id : ids) {
            File folder = new File(dir, id);
            File graphFile = new File(folder, "dialogue.yml");
            File commandFile = new File(folder, "server_commands.yml");
            File legacy = new File(dir, id + ".yml");
            if (commandFile.isFile() && legacy.isFile()) warnings.add(id + ": " + id + "/server_commands.yml 을 쓰고 예전 위치의 " + id + ".yml 은 무시합니다");
            if (!commandFile.isFile()) commandFile = legacy;
            List<String> errors = new ArrayList<>(), notes = new ArrayList<>();
            StoryGraph graph = null;
            if (graphFile.isFile()) {
                try {
                    graph = StoryGraph.parse(id, readYaml(graphFile), errors, notes);
                } catch (Exception e) {
                    errors.add("dialogue.yml 을 읽을 수 없음: " + e.getMessage());
                }
            }
            Map<?, ?> commands = null;
            if (commandFile.isFile()) {
                try {
                    commands = readYaml(commandFile);
                } catch (Exception e) {
                    errors.add(commandFile.getName() + " 을 읽을 수 없음: " + e.getMessage());
                }
            }
            if (!errors.isEmpty()) {
                broken.put(id, summarize(errors));
                continue;
            }
            out.put(id, StoryDef.parse(id, commands, graph, notes));
            if (graph != null) {
                try {
                    String text = Files.readString(graphFile.toPath(), java.nio.charset.StandardCharsets.UTF_8);
                    if (text.length() > StoryCodec.DIALOGUE_CONTENT_MAX_CHARS || text.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > 1_000_000)
                        warnings.add(id + ": dialogue.yml 이 너무 커서 클라이언트에 보내지 않습니다 (클라 파일을 씀)");
                    else texts.put(id, text);
                } catch (Exception e) {
                    warnings.add(id + ": dialogue.yml 글을 읽지 못해 클라이언트에 보내지 않습니다: " + e.getMessage());
                }
            }
            if (!notes.isEmpty()) warnings.add(id + ": " + summarize(notes));
        }
        return new DefLoad(out, broken, warnings, texts);
    }

    private static String summarize(List<String> problems) {
        int show = Math.min(5, problems.size());
        String s = String.join(" / ", problems.subList(0, show));
        return problems.size() > show ? s + " / … 외 " + (problems.size() - show) + "건" : s;
    }

    private void applyDefs(DefLoad load, CommandSender tell) {
        endingSessions.clear();dialogueAcks.clear();
        defs = load.defs();
        brokenDefs = load.broken();
        dialogueTexts = load.texts();
        boolean require = getConfig().getBoolean("require-server-graph", true);
        List<String> noGraph = new ArrayList<>();
        for (StoryDef d : defs.values()) if (d.graph == null) noGraph.add(d.id);
        for (String w : load.warnings()) getLogger().warning("[대화 파일] " + w);
        for (var e : brokenDefs.entrySet()) getLogger().severe("[대화 파일] " + e.getKey() + " 을(를) 열 수 없습니다: " + e.getValue());
        if (!noGraph.isEmpty()) {
            if (require) getLogger().warning("서버에 dialogue.yml 이 없어서 열 수 없는 대화 (dialogues/<id>/dialogue.yml 을 넣어 주세요): " + String.join(", ", noGraph));
            else getLogger().warning("require-server-graph: false — 아래 대화는 서버 검증 없이 열립니다 (선택지·결말을 클라가 정함): " + String.join(", ", noGraph));
        }
        String summary = "대화 " + defs.size() + "개 (흐름 검증 " + (defs.size() - noGraph.size()) + "개"
                + (noGraph.isEmpty() ? "" : ", dialogue.yml 없음 " + noGraph.size() + "개")
                + (brokenDefs.isEmpty() ? "" : ", 오류 " + brokenDefs.size() + "개") + ")";
        getLogger().info(summary);
        if (tell != null && !(tell instanceof ConsoleCommandSender)) ChacaLink.ok(tell, summary);
    }

    // ================================================================ 모드 확인

    /** 이 플레이어의 스토리 대기열(재생 중 포함)에 이 대화·컷신이 있는지. */
    /** 이 서버의 progress.yml 에 이 대화를 끝낸 기록이 있는지 (서버 흐름으로 본 대화). 스토리 단계의 기존 유저 시작 판정용. */
    boolean dialogueDone(UUID player, String id) {
        PlayerStory ps = state.players.get(player);
        return ps != null && id != null && ps.done.containsKey(id);
    }

    /** dialogues/server_command.yml 의 그 대화 끝 명령 (없으면 null). 스토리 단계 점검용. */
    List<String> endingCommandsOf(String id) { return endingCommands.commands(id); }

    boolean hasStory(UUID player, boolean dialogue, String id) {
        PlayerStory ps = state.players.get(player);
        return ps != null && ps.has(dialogue ? Kind.DIALOGUE : Kind.CUTSCENE, id);
    }

    /** 퀘스트창 메인 스토리 (MagicCodexBridge JournalBridge 가 리플렉션으로 부른다, 메인 스레드). */
    public List<Map<String, String>> journalRows(UUID uuid) {
        return quests == null ? List.of() : quests.journalRows(uuid, this::dialogueMeta);
    }

    private static final java.util.regex.Pattern META = java.util.regex.Pattern.compile("(?m)^(title|chapter):\\s*[\"']?(.*?)[\"']?\\s*$");

    /** 서버 dialogue.yml 의 {제목, 장} (없으면 null). */
    private String[] dialogueMeta(String id) {
        String text = dialogueTexts.get(id);
        if (text == null) return null;
        String title = "", chapter = "";
        var m = META.matcher(text);
        while (m.find()) {
            if (m.group(1).equals("title") && title.isEmpty()) title = m.group(2);
            else if (m.group(1).equals("chapter") && chapter.isEmpty()) chapter = m.group(2);
        }
        return new String[]{title, chapter};
    }

    /**
     * 길잡이 별 (MagicMythic 가 리플렉션으로 부른다): 0 = 가리킬 퀘스트 목표 없음, 1 = 화살표 표시 시작,
     * 2 = 목표가 다른 세계, 3 = 이미 목표 근처 (10블록), 4 = 켜져 있던 화살표를 끔 (다시 시전).
     */
    public int questGuide(Player p) {
        return quests == null ? 0 : quests.guide(p);
    }

    public boolean hasMod(Player p) {
        return p.getListeningPluginChannels().contains(StoryCodec.CUTSCENE_PLAY);
    }

    private static String color(String s) {
        return ChatColor.translateAlternateColorCodes('&', s);
    }

    private void message(Player p, String key, String def) {
        String msg = getConfig().getString(key, def);
        if (msg == null || msg.isEmpty()) return;
        String text = ChatColor.stripColor(color(msg)).strip();
        if (text.startsWith("(") && text.endsWith(")")) text = text.substring(1, text.length() - 1);
        ChacaLink.notify(p, "warning", "story-" + key, text, "", 3000);
    }

    private long joined(Player p) {
        Long t = joinedAt.get(p.getUniqueId());
        return t == null ? 0 : t;
    }

    // ================================================================ 재생 요청과 대기열

    /** 컷신 재생 요청. 지금 다른 스토리를 보는 중이면 차례를 기다린다. 끝나면 on-finish 를 요청 한 번에 한 번만 실행. */
    public void playCutscene(Player p, String id, int mode) {
        requestCutscene(null, p, id, mode);
    }

    private boolean requestCutscene(CommandSender who, Player p, String id, int mode) {
        if (!StorySafety.validId(id)) return false;
        PlayerStory ps = state.of(p.getUniqueId());
        ps.name = p.getName();
        if (ps.has(Kind.CUTSCENE, id)) {
            note(who, nick(p) + ": 컷신 " + id + " 은(는) 이미 재생 중이거나 대기 중입니다 (요청 무시)");
            return false;
        }
        if (ps.queue.size() >= StoryState.MAX_QUEUE) {
            refuse(who, nick(p) + ": 스토리 대기열이 가득 찼습니다 (" + StoryState.MAX_QUEUE + "개). 컷신 " + id + " 요청을 버립니다");
            return false;
        }
        Request r = new Request(Kind.CUTSCENE, id, ps.nextSeq());
        r.mode = mode;
        ps.queue.add(r);
        if (ps.active != null) note(who, "[Story cutscene] " + id + " queued behind " + ps.active.id
                + (ps.active.parked ? " (failed/parked; /대화관리 중지 <닉네임> 으로 그 요청만 취소)" : ""));
        saveSoon(null);
        pump(p);
        return true;
    }

    /** 대화 열기 요청 (다시 보기 아님). 이미 끝낸 대화면 아무 일도 하지 않는다. */
    public void openDialogue(Player p, String id) {
        requestNicknameDialogue(p, id);
    }

    /**
     * 클라 고정 보상 (LocalStoryRewards). 서버 세션에 묶인 것만 받고 (이벤트 = 열린 세션, end/결말 = 방금 서버가 확인한 완료),
     * 플레이어·키마다 한 번만: 영수증 pending 을 디스크에 저장한 뒤에 효과를 적용하고 applied/failed 로 바꾼다.
     * pending·applied·failed 가 이미 있으면 무시한다 (failed 는 /유저관리 스토리기록 초기화로만 다시 받을 수 있음).
     */
    private void claimLocalReward(Player p,String key) {
        UUID uuid=p.getUniqueId();long now=System.currentTimeMillis();
        var effect=LocalStoryRewards.resolve(key);
        if(effect==null){packetLog(p,p.getName()+" 의 알 수 없는 스토리 보상 키를 버립니다");return;}
        var live=endingSessions.current(uuid,now);
        Finished fin=recentFinish.get(uuid);
        if(fin!=null&&now-fin.at()>REWARD_WINDOW_MS){recentFinish.remove(uuid);fin=null;}
        StoryDef def=defs.get(LocalStoryRewards.dialogue(key));
        Set<String> events=def==null||def.graph==null?null:def.graph.events();
        if(!LocalStoryRewards.bound(key,live==null?null:live.id(),live!=null&&live.ready(),fin==null?null:fin.id(),fin==null?null:fin.ending(),events)){
            packetLog(p,p.getName()+" 스토리 보상 "+key+" 이(가) 서버 대화 세션에 묶여 있지 않아 무시합니다");return;
        }
        PlayerStory ps=state.of(uuid);ps.name=p.getName();
        if(ps.rewards.containsKey(key))return;
        ps.rewards.put(key,LocalStoryRewards.PENDING);
        String name=p.getName();
        // 영수증이 디스크에 저장된 뒤에만 효과 (저장 전에 서버가 멈추면 효과 0회, 다시 와도 영수증 때문에 1회 이하)
        saveSoon(()->applyLocalReward(uuid,name,key,effect));
    }

    private void applyLocalReward(UUID uuid,String name,String key,LocalStoryRewards.Effect effect) {
        if(effect.affinityNpc()!=null) {
            if(!codex.available()){getLogger().warning("[LocalStoryReward] "+name+" "+key+": MagicCodex 호감도 연결 없음");rewardDone(uuid,key,false);return;}
            codex.addAffinity(uuid,effect.affinityNpc(),effect.affinityDelta()).whenComplete((score,error)->{
                boolean ok=error==null&&score!=null;
                if(ok)getLogger().info("[LocalStoryReward] "+uuid+" "+key+" applied score="+score);
                else getLogger().warning("[LocalStoryReward] "+name+" "+key+": 호감도 적용 실패"+(error==null?"":" ("+error+")"));
                if(Bukkit.isPrimaryThread())rewardDone(uuid,key,ok);
                else if(isEnabled())Bukkit.getScheduler().runTask(this,()->rewardDone(uuid,key,ok));
            });
        } else {
            rewardDone(uuid,key,applyDiscovery(uuid,key,effect));
        }
    }

    private boolean applyDiscovery(UUID uuid,String key,LocalStoryRewards.Effect effect) {
        var discovery=Bukkit.getPluginManager().getPlugin("MagicDiscovery");
        try {
            if(discovery==null||!discovery.isEnabled())throw new IllegalStateException("discovery unavailable");
            boolean applied=Boolean.TRUE.equals(discovery.getClass().getMethod("signal",UUID.class,String.class,double.class)
                    .invoke(discovery,uuid,effect.discoveryEvent(),effect.discoveryAmount()));
            if(!applied)throw new IllegalStateException("discovery not ready");
            getLogger().info("[LocalStoryReward] "+uuid+" "+key+" applied");
            return true;
        }catch(ReflectiveOperationException|RuntimeException e){getLogger().warning("Local story discovery effect unavailable: "+e);return false;}
    }

    /** 영수증 pending → applied / failed (failed 는 자동으로 다시 시도하지 않는다). */
    private void rewardDone(UUID uuid,String key,boolean ok) {
        PlayerStory ps=state.players.get(uuid);
        if(ps!=null&&LocalStoryRewards.PENDING.equals(ps.rewards.get(key))){ps.rewards.put(key,ok?LocalStoryRewards.APPLIED:LocalStoryRewards.FAILED);saveSoon(null);}
        if(!ok){Player p=Bukkit.getPlayer(uuid);if(p!=null)rewardFailed(p);}
    }

    private static void rewardFailed(Player p) {
        ChacaLink.notify(p, "warning", "story-reward-fail", "스토리 보상을 받지 못했어요", "관리자에게 알려 주세요", 3000);
    }

    /** Only requests local playback. Does not inspect server dialogue files or completion/reward state. */
    public CompletableFuture<Boolean> requestNicknameDialogue(Player p, String id) {
        if (!ready || !StorySafety.validId(id) || !p.isOnline() || !p.getListeningPluginChannels().contains(StoryCodec.DIALOGUE_VIEW))
            return CompletableFuture.completedFuture(false);
        UUID uuid=p.getUniqueId();
        var running=endingSessions.current(uuid,System.currentTimeMillis());
        if(running!=null){
            if(!running.id().equals(id))return CompletableFuture.completedFuture(false);
            var pending=dialogueAcks.pending(uuid,id);return pending!=null?pending:CompletableFuture.completedFuture(running.ready());
        }
        var existing=dialogueAcks.pending(uuid,id);
        if(existing!=null)return existing;
        var future=dialogueAcks.begin(uuid,id);
        var commands=endingCommands.commands(id);
        // 스토리 단계가 띄우는 대화는 끝 명령이 없어도 완료 신호를 받아야 한다 (빈 명령 세션)
        if(commands==null&&quests!=null&&quests.wantsSession(uuid,id))commands=List.of();
        // 클라 고정 보상이 있는 대화도 세션을 연다 (보상을 이 세션·완료에 묶는다)
        if(commands==null&&LocalStoryRewards.hasRewards(id))commands=List.of();
        var session=p.getListeningPluginChannels().contains(StoryCodec.DIALOGUE_SESSION)
                ?endingSessions.begin(uuid,id,commands,System.currentTimeMillis()):null;
        withServerAffinity(p,id,vars->{
            if(!isEnabled()||!p.isOnline()||Bukkit.getPlayer(uuid)!=p||dialogueAcks.pending(uuid,id)!=future)return;
            if(session!=null){
                var cur=endingSessions.current(uuid,System.currentTimeMillis());
                if(cur==null||!cur.token().equals(session.token()))return;
                sendContent(p,id);
                p.sendPluginMessage(this,StoryCodec.DIALOGUE_SESSION,StoryCodec.dialogueSession(id,StoryCodec.encodeVars(vars),session.token()));
            }
            else {sendContent(p,id);p.sendPluginMessage(this,StoryCodec.DIALOGUE_VIEW,StoryCodec.dialogueView(id,StoryCodec.encodeVars(vars)));}
        });
        Bukkit.getScheduler().runTaskLater(this,()->{
            if(dialogueAcks.pending(uuid,id)==future){dialogueAcks.reject(uuid,id);if(session!=null)endingSessions.abort(uuid,id,session.token(),System.currentTimeMillis());}
        },400L);
        return future;
    }

    /**
     * 로컬 대화의 선택지 조건(need)도 MagicCodex 호감도만 쓰도록 (호감도는 MagicCodex 하나), 서버 흐름(dialogues/&lt;id&gt;)의 npcs 점수를
     * "@affinity:&lt;npc&gt;" 글자로 함께 보낸다. 서버 흐름이 없거나 MagicCodex 연결이 없으면 점수 없이 (모드는 기본값으로 판단).
     */
    private void withServerAffinity(Player p,String id,java.util.function.Consumer<Map<String,String>> send) {
        Map<String,String> vars=textVars(p);
        StoryDef def=defs.get(id);
        List<String> npcs=def==null?List.of():def.npcs;
        if(npcs.isEmpty()||!codex.available()){send.accept(vars);return;}
        UUID uuid=p.getUniqueId();
        Map<String,Integer> scores=new java.util.concurrent.ConcurrentHashMap<>();
        CompletableFuture<?>[] all=npcs.stream().map(n->codex.affinity(uuid,n).thenAccept(v->{if(v!=null)scores.put(n,v);})).toArray(CompletableFuture[]::new);
        CompletableFuture.allOf(all).orTimeout(3,TimeUnit.SECONDS).handle((r,e)->{
            if(!isEnabled())return null;
            Bukkit.getScheduler().runTask(this,()->{
                for(var sc:new TreeMap<>(scores).entrySet())if(StorySafety.NPC.matcher(sc.getKey()).matches())vars.put(AFFINITY_VAR+sc.getKey(),String.valueOf(sc.getValue()));
                send.accept(vars);
            });
            return null;
        });
    }

    private boolean requestDialogue(CommandSender who, Player p, String id, boolean replayArg) {
        if (!StorySafety.validId(id)) return false;
        String brokenWhy = brokenDefs.get(id);
        if (brokenWhy != null) {
            refuse(who, "대화 " + id + " 의 서버 파일에 오류가 있어 열지 않습니다: " + brokenWhy);
            return false;
        }
        StoryDef def = defs.get(id);
        if ((def == null || def.graph == null) && getConfig().getBoolean("require-server-graph", true)) {
            refuse(who, "대화 " + id + " 을(를) 열지 않습니다: 서버에 plugins/ChacademyStory/dialogues/" + id
                    + "/dialogue.yml 이 없습니다 (넣고 /서버관리 새로고침 스토리, 또는 config 의 require-server-graph: false)");
            return false;
        }
        PlayerStory ps = state.of(p.getUniqueId());
        ps.name = p.getName();
        StoryState.Open open = ps.decideOpen(id, replayArg, def == null ? null : def.allowReplay, getConfig().getBoolean("allow-replay", false));
        switch (open) {
            case DUPLICATE -> {
                note(who, nick(p) + ": 대화 " + id + " 은(는) 이미 진행 중이거나 대기 중입니다 (요청 무시)");
                return false;
            }
            case ALREADY_DONE -> {
                note(who, nick(p) + ": 대화 " + id + " 은(는) 이미 끝냈습니다 — 다시 열지 않고 명령어도 실행하지 않습니다 (다시 보여 주려면 /테스트 스토리 대화 "
                        + id + " " + p.getName() + ")");
                return false;
            }
            case QUEUE_FULL -> {
                refuse(who, nick(p) + ": 스토리 대기열이 가득 찼습니다 (" + StoryState.MAX_QUEUE + "개). 대화 " + id + " 요청을 버립니다");
                return false;
            }
            default -> {
            }
        }
        boolean replay = open == StoryState.Open.REPLAY;
        Request r = new Request(Kind.DIALOGUE, id, ps.nextSeq());
        r.replay = replay;
        ps.queue.add(r);
        saveSoon(null);
        pump(p);
        return true;
    }

    private void note(CommandSender who, String msg) {
        getLogger().info(msg);
        if (who != null && !(who instanceof ConsoleCommandSender)) ChacaLink.ok(who, msg);
    }

    private void refuse(CommandSender who, String msg) {
        getLogger().warning(msg);
        if (who != null && !(who instanceof ConsoleCommandSender)) ChacaLink.fail(who, "스토리를 재생하지 못했어요", new IllegalStateException(msg));
    }

    private void prune(UUID id, PlayerStory ps) {
        if (ps.empty()) state.players.remove(id);
    }

    /**
     * 이 플레이어의 다음 할 일을 진행한다: 대기 중인 효과가 없고 재생 중인 것이 없으면 대기열의 다음 요청을 시작하고,
     * 아직 보내지 않은 요청이면 모드가 준비됐을 때 보낸다. 아무 때나 여러 번 불러도 된다 (1초마다도 불린다).
     */
    private void pump(Player p) {
        UUID uuid = p.getUniqueId();
        PlayerStory ps = state.players.get(uuid);
        if (ps == null || !p.isOnline() || Bukkit.getPlayer(uuid) != p) return;
        long now = System.currentTimeMillis();
        if (now < joined(p) + JOIN_DELAY_MS) return;
        // 앞 스토리의 끝 명령이 먼저 실행된 다음에 다음 스토리를 시작한다
        if (!ps.effects.isEmpty() || ps.running) return;
        if (ps.active == null) {
            if (ps.activateNext() == null) {
                prune(uuid, ps);
                return;
            }
            saveSoon(null);
        }
        Request a = ps.active;
        if (a.sent || a.parked || now < a.retryAtMs) return;
        int proto = StoryCodec.clientProtocol(p.getListeningPluginChannels());
        if (proto >= StoryCodec.MIN_CLIENT_PROTOCOL) {
            if (a.kind == Kind.CUTSCENE) sendCutscene(p, a);
            else sendDialogue(p, ps, a);
            return;
        }
        // 채널 등록은 접속 뒤 늦게 오므로 충분히 기다린 다음에만 "모드 없음" 으로 본다
        if (now < joined(p) + WAIT_MS) return;
        if (!a.toldMissing) {
            a.toldMissing = true;
            if (proto >= 1) message(p, "outdated-mod-message", "&c차카데미 스토리 모드가 오래된 버전입니다. 모드를 업데이트해 주세요.");
            else message(p, "missing-mod-message", "");
            getLogger().info(p.getName() + ": 스토리 모드 " + (proto >= 1 ? "버전이 낮음 (프로토콜 " + proto + " < " + StoryCodec.MIN_CLIENT_PROTOCOL + ")" : "없음")
                    + " — " + (a.kind == Kind.CUTSCENE ? "컷신 " : "대화 ") + a.id);
        }
        if (getConfig().getBoolean("finish-if-missing-mod", false)) {
            if (a.kind == Kind.CUTSCENE) finishCutscene(p, ps, a, true, true);
            else finishDialogue(p, ps, a, "", true);
        } else {
            a.parked = true; // 스토리는 넘어가지 않는다. 모드를 넣고 다시 접속하면 이어진다
        }
    }

    /** 1초마다: 채널을 기다리던 요청, 다시 보내기, 접속 뒤 밀린 효과, 보호 시간 한도, 대사 위치 저장. */
    private void heartbeat() {
        long now = System.currentTimeMillis();
        for (Player p : Bukkit.getOnlinePlayers()) {
            UUID uuid = p.getUniqueId();
            if (!helloSent.contains(uuid) && StoryCodec.clientProtocol(p.getListeningPluginChannels()) >= 2
                    && p.getListeningPluginChannels().contains(StoryCodec.STORY_HELLO)) {
                helloSent.add(uuid);
                p.sendPluginMessage(this, StoryCodec.STORY_HELLO, StoryCodec.hello(StoryCodec.PROTOCOL));
            }
            PlayerStory ps = state.players.get(uuid);
            if (ps == null) continue;
            try {
                if (!ps.effects.isEmpty() && ps.effects.get(0).durable && now >= joined(p) + JOIN_DELAY_MS) runEffects(uuid);
                pump(p);
            } catch (RuntimeException ex) {
                // 한 사람의 오류로 다른 사람의 대기열까지 멈추지 않게
                getLogger().log(Level.SEVERE, p.getName() + ": 스토리 진행 중 오류", ex);
            }
        }
        protection.tick();
        if (++beat % 5 == 0 && dirty && !flushScheduled) flush();
    }

    // ================================================================ 컷신

    private double minSeconds(String id) {
        return Math.max(0, getConfig().getDouble("min-seconds." + id, 0));
    }

    private void sendCutscene(Player p, Request a) {
        a.sent = true;
        a.sentAtMs = System.currentTimeMillis();
        protection.enter(p);
        // "본 적 있으면 건너뛰기" 는 서버 기록(ps.seen)으로 정한다 — 런처 파일(_seen.txt)은 런처를 바꾸면 사라지고 믿을 수도 없다
        PlayerStory owner = state.players.get(p.getUniqueId());
        int mode = a.mode != StoryCodec.MODE_AUTO ? a.mode
                : owner != null && owner.seen.contains(a.id) ? StoryCodec.MODE_SKIP : StoryCodec.MODE_NOSKIP;
        p.sendPluginMessage(this, StoryCodec.CUTSCENE_PLAY, StoryCodec.cutscenePlay(a.id, mode));
    }

    /** 지금 재생 중인 컷신을 멈춘다 (on-finish 는 실행하지 않음). 대기 중인 다음 요청이 있으면 이어서 시작한다. */
    public void stopCutscene(Player p) {
        PlayerStory ps = state.players.get(p.getUniqueId());
        if (ps != null && ps.cancelCutscene(ps.active)) {
            protection.release(p);
            saveSoon(null);
        }
        if (hasMod(p)) p.sendPluginMessage(this, StoryCodec.CUTSCENE_STOP, new byte[0]);
        pump(p);
    }

    private void finishCutscene(Player p, PlayerStory ps, Request a, boolean skipped, boolean missing) {
        if (!ps.cancelCutscene(a)) return;
        protection.release(p);
        if (!skipped && !missing) ps.seen.add(a.id); // 끝까지 봄 → 다음부터 건너뛰기 허용 (서버 기록)
        Effect e = new Effect(ps.nextSeq(), Effect.CUTSCENE_END, a.id, "", skipped, missing);
        queueEffect(p.getUniqueId(), ps, e);
    }

    /** Retry an interrupted cutscene; cancel without effects at the retry limit so the queue can continue. */
    private void resendLater(Player p, Request a, String why) {
        a.sent = false;
        protection.release(p);
        a.resends++;
        int max = Math.max(0, getConfig().getInt("cutscene-max-resends", 5));
        if (a.resends > max) {
            PlayerStory ps = state.players.get(p.getUniqueId());
            if (ps != null && ps.cancelCutscene(a)) {
                saveSoon(null);
                getLogger().warning("[Story cutscene] " + p.getName() + ": " + a.id
                        + " cancelled after retry limit; no completion/rewards. Continuing queued requests.");
                pump(p);
            }
            return;
        }
        a.retryAtMs = System.currentTimeMillis() + (long) (Math.max(0.5, getConfig().getDouble("cutscene-resend-delay-seconds", 3)) * 1000);
    }

    // ================================================================ 대화

    /** 대화 화면을 연다. a.scene 이 있으면 거기서부터 (이어서 보기). */
    private void sendDialogue(Player p, PlayerStory ps, Request a) {
        StoryDef def = defs.get(a.id);
        if (brokenDefs.containsKey(a.id) || ((def == null || def.graph == null) && getConfig().getBoolean("require-server-graph", true))) {
            a.parked = true;
            getLogger().warning(p.getName() + ": 대화 " + a.id + " 을(를) 이어서 열 수 없습니다 — 서버에 dialogues/" + a.id
                    + "/dialogue.yml 이 없거나 오류가 있습니다. 파일을 넣고 /서버관리 새로고침 스토리 하면 다시 시도합니다.");
            return;
        }
        // 저장된 장면이 지금 흐름에 없으면 (파일이 바뀜) 처음부터
        if (def != null && def.graph != null && !a.scene.isEmpty() && !def.graph.scenes.containsKey(a.scene)) {
            a.scene = "";
            a.line = 0;
        }
        a.sent = true;
        a.sentAtMs = System.currentTimeMillis();
        a.illegal = 0;
        a.walk = null;
        Object token = a.token = new Object();
        protection.enter(p);
        if (!a.scene.isEmpty()) getLogger().info(p.getName() + " 대화 이어서 열기: " + a.id + " (" + a.scene + " #" + a.line + ")");
        UUID uuid = p.getUniqueId();
        String vars = StoryCodec.encodeVars(textVars(p));
        List<String> npcs = def == null ? List.of() : def.npcs;
        if (!codex.available()) {
            // 호감도는 MagicCodex 하나로만: 연결이 없으면 점수 없이 연다 (모드·서버 모두 기본값으로 흐름을 따라감)
            deliverOpen(p, a, def, new TreeMap<>(), vars);
            return;
        }
        // MagicCodex 호감도: 이 대화에 나오는 NPC (서버 yml 의 npcs) 만 물어본다
        Map<String, Integer> scores = new TreeMap<>();
        CompletableFuture<?>[] all = npcs.stream().map(n -> codex.affinity(uuid, n)
                .thenAccept(v -> { if (v != null) scores.put(n, v); })).toArray(CompletableFuture[]::new);
        CompletableFuture.allOf(all).orTimeout(3, TimeUnit.SECONDS).handle((r, e) -> {
            if (!isEnabled()) return null;
            Bukkit.getScheduler().runTask(this, () -> {
                if (!isEnabled() || !p.isOnline() || Bukkit.getPlayer(uuid) != p || ps.active != a || a.token != token || !a.sent) return;
                deliverOpen(p, a, def, new TreeMap<>(scores), vars);
            });
            return null;
        });
    }

    private void deliverOpen(Player p, Request a, StoryDef def, Map<String, Integer> scores, String vars) {
        StringBuilder sb = new StringBuilder();
        Map<String, Integer> sent = new HashMap<>();
        for (var e : scores.entrySet()) {
            if (sent.size() >= 64 || !StorySafety.NPC.matcher(e.getKey()).matches()) continue;
            sent.put(e.getKey(), e.getValue());
            sb.append(sb.length() > 0 ? "," : "").append(e.getKey()).append('=').append(e.getValue());
        }
        // 서버도 모드와 같은 호감도에서 시작해 같은 규칙으로 흐름을 따라간다
        a.walk = def != null && def.graph != null ? new StoryGraph.Walk(def.graph, sent, a.scene, a.line) : null;
        sendContent(p, a.id);
        p.sendPluginMessage(this, StoryCodec.DIALOGUE_OPEN, StoryCodec.dialogueOpen(a.id, sb.toString(), vars, a.scene, a.line));
    }

    /** 대사에서 바꿔 넣을 글자. {player} = 한글 닉네임, {account} = 마인크래프트 닉네임, %...% = PlaceholderAPI. */
    private Map<String, String> textVars(Player p) {
        Map<String, String> v = new LinkedHashMap<>();
        v.put("{player}", codex.nickname(p));
        v.put("{account}", p.getName());
        for (String ph : getConfig().getStringList("dialogue-placeholders")) {
            if (ph == null || !ph.matches("%[A-Za-z0-9_\\-:.]{1,60}%") || v.size() >= 40) continue;
            String r = codex.placeholder(p, ph);
            if (r != null && !r.equals(ph)) v.put(ph, ChatColor.stripColor(r));
        }
        return v;
    }

    /** 지금 진행 중인 대화를 닫는다 (끝 명령은 실행하지 않음, 완료로 기록하지 않음). 대기 중인 다음 요청이 있으면 이어서 시작한다. */
    public void stopDialogue(Player p) {
        endingSessions.cancel(p.getUniqueId());dialogueAcks.disconnect(p.getUniqueId());
        if (quests != null) quests.dialogueAborted(p.getUniqueId(), null);
        PlayerStory ps = state.players.get(p.getUniqueId());
        if (ps != null && ps.active != null && ps.active.kind == Kind.DIALOGUE) {
            ps.active = null;
            protection.release(p);
            saveSoon(null);
        }
        if (hasMod(p)) p.sendPluginMessage(this, StoryCodec.DIALOGUE_STOP, new byte[0]);
        pump(p);
    }

    private void finishDialogue(Player p, PlayerStory ps, Request a, String lastScene, boolean missing) {
        if (ps.active != a) return;
        ps.active = null;
        protection.release(p);
        if (!ps.recordDone(a, lastScene, System.currentTimeMillis())) {
            // 다시 보기: 기록도 효과도 없다
            getLogger().info(p.getName() + ": 대화 " + a.id + " 다시 보기 끝 (명령어·호감도 없음)");
            saveSoon(null);
            pump(p);
            return;
        }
        Effect e = new Effect(ps.nextSeq(), Effect.DIALOGUE_END, a.id, lastScene, false, missing);
        queueEffect(p.getUniqueId(), ps, e);
    }

    /** 흐름에 맞지 않는 패킷. 무시하고 세다가, 너무 많으면 대화를 멈춘다 (클라 파일이 서버와 다르거나 조작된 클라). */
    private void illegal(Player p, Request a, String what) {
        illegal(p, a, what, false);
    }

    /**
     * stopNow = 인정할 수 없는 "끝" 신호: 클라는 이미 대화창을 닫았으므로 더 올 패킷이 없다. 세지 않고 바로 멈춘다
     * (그대로 두면 서버만 "보는 중" 으로 남아 플레이어가 제자리에 묶이고 대기열이 멈춘다).
     */
    private void illegal(Player p, Request a, String what, boolean stopNow) {
        a.illegal++;
        if (getConfig().getBoolean("story-debug", false)) getLogger().info(p.getName() + " 흐름에 없는 패킷 (" + a.id + "): " + what);
        if (a.illegal < ILLEGAL_LIMIT && !stopNow) return;
        a.sent = false;
        a.parked = true;
        a.walk = null;
        protection.release(p);
        getLogger().warning(p.getName() + ": 대화 " + a.id + " 에서 서버 흐름에 맞지 않는 패킷이 " + a.illegal + "개 와서 대화를 멈췄습니다 (마지막: " + what
                + "). 클라의 대화 파일이 서버와 다르거나 조작된 클라일 수 있습니다. 다음 접속 때 다시 엽니다.");
        if (hasMod(p)) p.sendPluginMessage(this, StoryCodec.DIALOGUE_STOP, new byte[0]);
        message(p, "client-content-missing-message", "&c스토리 파일이 최신이 아닙니다. 스토리 파일을 업데이트한 뒤 다시 접속해 주세요.");
    }

    // ================================================================ 효과 (저장된 뒤 실행)

    /** 효과를 기록에 넣고 저장을 요청한다. 디스크에 쓰인 다음에야 실행된다. */
    private void queueEffect(UUID uuid, PlayerStory ps, Effect e) {
        ps.effects.add(e);
        saveSoon(() -> {
            e.durable = true;
            runEffects(uuid);
        });
    }

    /**
     * 저장이 끝난 효과를 차례로 실행한다. 플레이어가 없으면 남겨 두었다가 다음 접속 때 실행한다.
     * 실행한 효과는 목록에서 빼고 다시 저장한다 — 실행과 그 저장 사이에 서버가 죽으면 다음 접속 때 한 번 더 실행될 수 있다.
     */
    private void runEffects(UUID uuid) {
        PlayerStory ps = state.players.get(uuid);
        if (ps == null || ps.running) return;
        Player p = Bukkit.getPlayer(uuid);
        if (p == null || !p.isOnline()) return;
        boolean ran = false;
        ps.running = true;
        try {
            while (!ps.effects.isEmpty() && ps.effects.get(0).durable) {
                Effect e = ps.effects.get(0);
                try {
                    execute(p, ps, e);
                } catch (RuntimeException ex) {
                    getLogger().log(Level.SEVERE, p.getName() + ": 스토리 효과 실행 중 오류 (" + e.type + " " + e.id + " " + e.name + ") — 다시 실행하지 않습니다", ex);
                }
                ps.effects.remove(e);
                ran = true;
            }
        } finally {
            ps.running = false;
        }
        if (ran) saveSoon(null);
        pump(p);
    }

    private void execute(Player p, PlayerStory ps, Effect e) {
        switch (e.type) {
            case Effect.EVENT -> {
                StoryDef def = defs.get(e.id);
                if (def == null) {
                    getLogger().warning(p.getName() + ": 대화 " + e.id + " 의 서버 파일이 없어 이벤트 " + e.name + " 의 명령을 실행하지 못했습니다");
                    return;
                }
                applyAffinity(p, ps, e.id, def.affinityEvents.get(e.name), e.id + " " + e.name);
                Bukkit.getPluginManager().callEvent(new StoryEvents.DialogueChoice(p, e.id, e.name));
                run(def.events.get(e.name), p, Map.of("{id}", e.id, "{event}", e.name));
            }
            case Effect.DIALOGUE_END -> {
                Bukkit.getPluginManager().callEvent(new StoryEvents.DialogueFinish(p, e.id, e.name, e.missing));
                StoryDef def = defs.get(e.id);
                if (def == null) {
                    if (!e.missing) getLogger().warning(p.getName() + ": 대화 " + e.id + " 의 서버 파일이 없어 끝 명령을 실행하지 못했습니다");
                    if (quests != null) quests.dialogueFinished(p, e.id);
                    return;
                }
                if (!e.name.isEmpty()) applyAffinity(p, ps, e.id, def.affinityEndings.get(e.name), e.id + " 결말 " + e.name);
                applyAffinity(p, ps, e.id, def.affinityEnd, e.id + " 끝");
                Map<String, String> vars = Map.of("{id}", e.id, "{scene}", e.name);
                if (!e.name.isEmpty()) run(def.endings.get(e.name), p, vars);
                run(def.end, p, vars);
                if (quests != null) quests.dialogueFinished(p, e.id);
            }
            case Effect.CUTSCENE_END -> {
                Bukkit.getPluginManager().callEvent(new StoryEvents.CutsceneFinish(p, e.id, e.skipped, e.missing));
                run(getConfig().getStringList("on-finish." + e.id), p, Map.of("{id}", e.id, "{skipped}", String.valueOf(e.skipped)));
                if (quests != null) quests.cutsceneFinished(p, e.id);
            }
            default -> {
            }
        }
    }

    /**
     * {npc: 값} 을 적용한다. 값은 서버 파일에서 온 것이고 읽을 때 이미 한도로 잘랐다 (StoryDef).
     * affinity-gain-cap-per-dialogue 가 0보다 크면 한 플레이어가 한 대화에서 얻는 호감도 (오른 것만) 합을 거기까지로 막는다.
     */
    private void applyAffinity(Player p, PlayerStory ps, String dialogueId, Map<String, Integer> changes, String why) {
        if (changes == null || changes.isEmpty()) return;
        int cap = Math.max(0, getConfig().getInt("affinity-gain-cap-per-dialogue", 0));
        boolean logging = getConfig().getBoolean("affinity-log", false);
        for (var e : changes.entrySet()) {
            String npc = e.getKey();
            int v = e.getValue();
            if (v > 0 && cap > 0) {
                int room = Math.max(0, cap - ps.gained.getOrDefault(dialogueId, 0));
                if (v > room) {
                    if (logging) getLogger().info(p.getName() + " 호감도 " + npc + " +" + v + " → +" + room + " (대화 " + dialogueId + " 의 합계 한도 " + cap + ")");
                    v = room;
                }
            }
            if (v == 0) continue;
            if (v > 0) ps.gained.merge(dialogueId, v, Integer::sum);
            final int amount = v;
            String name = p.getName();
            if (codex.available()) {
                codex.addAffinity(p.getUniqueId(), npc, amount).thenAccept(now -> {
                    if (now == null) codex.affinityDropped(name, npc, amount);
                });
            } else {
                codex.affinityDropped(name, npc, amount);
            }
            if (logging) getLogger().info(p.getName() + " 호감도 " + npc + " " + (amount > 0 ? "+" : "") + amount + " (" + why + ")");
        }
    }

    private final Set<String> legacyWarned = new HashSet<>();

    /** Skript 를 쓰던 시절의 명령인지 ({@link StorySafety#legacyCommand}). */
    static boolean legacyCommand(String command) { return StorySafety.legacyCommand(command); }

    /** 서버 흐름(dialogues/&lt;id&gt;/dialogue.yml)이 있으면, 클라가 보낸 결말이 그 대화에서 끝날 수 있는 장면인지 확인한다. 흐름이 없으면 예전처럼 형식만 본다. */
    private boolean endingAllowed(String id, String ending) {
        StoryDef def = defs.get(id);
        if (def == null || def.graph == null) return true;
        StoryGraph.Scene scene = def.graph.scenes.get(ending);
        return scene != null && scene.choices().isEmpty() && (scene.next() == null || scene.next().isEmpty());
    }

    private void run(List<String> commands, Player p, Map<String, String> vars) {
        if (commands == null) return;
        for (String raw : commands) {
            if (legacyCommand(raw)) {
                if (legacyWarned.add(raw)) getLogger().warning("예전(Skript) 스토리 명령은 실행하지 않습니다 — 설정에서 지워 주세요: " + raw);
                continue;
            }
            // 명령어의 {player} 는 마인크래프트 닉네임 그대로 (명령어 대상이므로). 한글 닉네임은 {nickname}
            String c = raw.replace("{player}", p.getName()).replace("{uuid}", p.getUniqueId().toString());
            // 닉네임은 플레이어가 정한 글자라 명령어를 바꿔치기하지 못하게 글자·숫자·_·- 만 남긴다
            if (c.contains("{nickname}")) c = c.replace("{nickname}", StorySafety.safeNickname(codex.nickname(p), p.getName()));
            for (var v : vars.entrySet()) c = c.replace(v.getKey(), v.getValue());
            if (c.startsWith("/")) c = c.substring(1);
            if (c.isBlank()) continue;
            try {
                Bukkit.dispatchCommand(Bukkit.getConsoleSender(), c);
            } catch (RuntimeException ex) {
                getLogger().log(Level.SEVERE, "스토리 명령어 실행 실패: " + c, ex);
            }
        }
    }

    // ================================================================ 모드에서 온 신호

    private void packetLog(Player p, String msg) {
        long now = System.currentTimeMillis();
        Long last = lastPacketLog.get(p.getUniqueId());
        if (last != null && now - last < 10_000) return;
        lastPacketLog.put(p.getUniqueId(), now);
        getLogger().warning(msg);
    }

    private static Request active(PlayerStory ps, Kind kind, String id) {
        Request a = ps == null ? null : ps.active;
        return a != null && a.kind == kind && a.sent && a.id.equals(id) ? a : null;
    }

    /** 흐름 검증이 있는 대화인데 아직 열기 신호가 나가지 않았으면 (호감도 조회 중) 그 사이의 패킷은 버린다. */
    private boolean awaitingOpen(Request a) {
        if (a.walk != null) return false;
        StoryDef def = defs.get(a.id);
        return def != null && def.graph != null;
    }

    @Override
    public void onPluginMessageReceived(@NotNull String channel, @NotNull Player p, byte @NotNull [] message) {
        if (!ready || !p.isOnline() || Bukkit.getPlayer(p.getUniqueId()) != p) return;
        UUID uuid = p.getUniqueId();
        // 어떤 일도 하기 전에: 플레이어별 초당 20개 (순간 40개) 까지만
        StorySafety.TokenBucket bucket = buckets.computeIfAbsent(uuid, k -> new StorySafety.TokenBucket(20, 40));
        if (!bucket.tryTake(System.nanoTime())) {
            packetLog(p, p.getName() + " 의 스토리 패킷이 너무 많아 버립니다 (지금까지 " + bucket.dropped + "개)");
            return;
        }
        if (message.length > (channel.equals(StoryCodec.STORY_CATALOG)?StoryCatalog.MAX_PACKET:StoryCodec.MAX_C2S_PACKET)) {
            packetLog(p, p.getName() + " 의 스토리 패킷이 너무 큼 (" + channel + ", " + message.length + " 바이트)");
            return;
        }
        PlayerStory ps = state.players.get(uuid);
        try {
            switch (channel) {
                case StoryCodec.STORY_CATALOG -> {
                    long now=System.currentTimeMillis();
                    StoryCatalog catalog=StoryCodec.catalog(message);
                    clientCatalogs.put(uuid,catalog);catalogUpdated.put(uuid,now);
                }
                case StoryCodec.RELOAD_RESULT -> {
                    var result=StoryCodec.reloadResult(message);CommandSender requester=reloadTickets.finish(uuid,result.token());
                    if(requester!=null){
                        if(result.failures()==0)ChacaLink.ok(requester,nick(p)+" 님 클라이언트의 대화·컷신 파일을 새로고침했어요");
                        else ChacaLink.fail(requester,nick(p)+" 님 클라이언트 파일에 오류가 있어요 (자세한 내용은 그 화면)",new IllegalStateException("[스토리] "+p.getName()+" 클라이언트 새로고침 실패 "+result.failures()+"종류"));
                    }
                }
                case StoryCodec.DIALOGUE_SESSION_SIGNAL -> {
                    var signal=StoryCodec.sessionSignal(message);long now=System.currentTimeMillis();
                    if(signal.action()==0){if(endingSessions.ready(uuid,signal.id(),signal.token(),now))dialogueAcks.accept(uuid,signal.id());}
                    else if(signal.action()==1){
                        // Codec validates a non-null [a-z0-9_-]{1,64} ending; finish authenticates
                        // and consumes the session BEFORE substitution/dispatch. String.replace is literal.
                        var cur=endingSessions.current(uuid,now);
                        boolean valid=cur!=null&&cur.ready()&&cur.id().equals(signal.id())&&cur.token().equals(signal.token())&&StorySafety.validId(signal.ending());
                        if(valid&&!endingAllowed(signal.id(),signal.ending())){
                            // 서버 흐름에 없는 결말: 명령({ending} 포함)을 실행하지 않고 세션을 닫는다 (다시 호출 가능)
                            packetLog(p,p.getName()+" 대화 "+signal.id()+" 의 결말 "+signal.ending()+" 이(가) 서버 흐름의 끝 장면이 아니라 무시합니다");
                            if(endingSessions.abort(uuid,signal.id(),signal.token(),now)){dialogueAcks.reject(uuid,signal.id());if(quests!=null)quests.dialogueAborted(uuid,signal.id());}
                            break;
                        }
                        var commands=endingSessions.finish(uuid,signal.id(),signal.token(),signal.ending(),now);
                        // 보상 패킷(end·결말)은 이 확인된 완료에만 묶인다
                        if(valid)recentFinish.put(uuid,new Finished(signal.id(),signal.ending(),now));
                        run(commands,p,Map.of("{ending}",signal.ending()));
                        // 세션이 확인된 완료만 스토리 단계에 알린다 (끝 명령 실행 뒤: tp 같은 명령이 먼저 돌게)
                        if(valid&&quests!=null)quests.dialogueFinished(p,signal.id());
                    }
                    else if(endingSessions.abort(uuid,signal.id(),signal.token(),now)){dialogueAcks.reject(uuid,signal.id());if(quests!=null)quests.dialogueAborted(uuid,signal.id());}
                }
                case StoryCodec.DIALOGUE_REWARD -> {
                    claimLocalReward(p,StoryCodec.dialogueId(message));
                }
                case StoryCodec.DIALOGUE_READY -> {
                    String id=StoryCodec.dialogueId(message);
                    if(StorySafety.validId(id)&&endingSessions.current(uuid,System.currentTimeMillis())==null)dialogueAcks.accept(uuid,id);
                }
                case StoryCodec.CUTSCENE_DONE -> {
                    var d = StoryCodec.cutsceneDone(message);
                    // 서버가 재생시킨 것만 인정 (클라가 마음대로 퀘스트를 넘기지 못하게)
                    Request a = active(ps, Kind.CUTSCENE, d.id());
                    if (a == null) return;
                    double min = minSeconds(a.id);
                    if (min > 0 && a.mode != StoryCodec.MODE_SKIP && System.currentTimeMillis() - a.sentAtMs < (long) (min * 1000)) {
                        getLogger().warning(p.getName() + ": 컷신 " + a.id + " 이(가) 최소 시간 " + min + "초보다 빨리 끝났다고 왔습니다 — 인정하지 않고 다시 재생합니다");
                        resendLater(p, a, "min-seconds");
                        return;
                    }
                    finishCutscene(p, ps, a, d.skipped(), false);
                }
                case StoryCodec.CUTSCENE_ABORT -> {
                    var d = StoryCodec.cutsceneAbort(message);
                    Request a = active(ps, Kind.CUTSCENE, d.id());
                    if (a == null) return;
                    // 사망 화면·서버 GUI·월드 이동 등으로 컷신 화면이 사라짐: 잠시 뒤 다시 보낸다
                    resendLater(p, a, "화면이 중간에 닫힘");
                }
                case StoryCodec.STORY_FAIL -> {
                    var f = StoryCodec.storyFail(message);
                    if(f.kind()==StoryCodec.KIND_DIALOGUE)dialogueAcks.reject(uuid,f.id());
                    Kind kind = f.kind() == StoryCodec.KIND_CUTSCENE ? Kind.CUTSCENE : Kind.DIALOGUE;
                    Request a = active(ps, kind, f.id());
                    if (a == null) return;
                    String reason = f.reason().replaceAll("[^A-Za-z0-9_\\-]", "");
                    if (reason.length() > 32) reason = reason.substring(0, 32);
                    if (kind == Kind.CUTSCENE && !getConfig().getBoolean("finish-if-client-content-missing", false)) {
                        if (ps.cancelFailedCutscene(f.id())) {
                            protection.release(p);
                            saveSoon(null);
                            getLogger().warning("[Story cutscene] " + p.getName() + ": " + f.id() + " failed (" + reason
                                    + "); cancelled this request without completion/rewards. Continuing queued requests.");
                            ChacaLink.notify(p, "warning", "story-cutscene-fail", "컷신을 재생하지 못했어요", "관리자에게 알려 주세요", 3000);
                            pump(p);
                        }
                        break;
                    }
                    getLogger().warning(p.getName() + ": 클라에 " + (kind == Kind.CUTSCENE ? "컷신 " : "대화 ") + a.id + " 을(를) 보여 줄 수 없습니다 (" + reason
                            + ") — 끝난 것으로 치지 않습니다. 스토리 파일(또는 모드)을 맞춘 뒤 다시 접속하면 이어집니다.");
                    if (!reason.startsWith("magiccodex")) {
                        message(p, "client-content-missing-message", "&c스토리 파일이 최신이 아닙니다. 스토리 파일을 업데이트한 뒤 다시 접속해 주세요.");
                    }
                    if (getConfig().getBoolean("finish-if-client-content-missing", false)) {
                        if (kind == Kind.CUTSCENE) finishCutscene(p, ps, a, true, true);
                        else finishDialogue(p, ps, a, "", true);
                    } else {
                        a.sent = false;
                        a.parked = true;
                        a.walk = null;
                        protection.release(p);
                    }
                }
                case StoryCodec.DIALOGUE_EVENT -> {
                    var e = StoryCodec.dialogueEvent(message);
                    Request a = active(ps, Kind.DIALOGUE, e.id());
                    if (a == null) return;
                    String event = e.event();
                    // 클라가 보낸 npc/add 값은 쓰지 않는다. 이름만 본다.
                    // 이름이 빈 것은 이벤트 없는 선택지의 예전 형식 알림: 할 일이 없다
                    if (event.isEmpty()) return;
                    if (!StorySafety.validId(event)) {
                        illegal(p, a, "event " + event.replaceAll("[^\\p{L}\\p{N}_\\-]", "?"));
                        return;
                    }
                    StoryDef def = defs.get(a.id);
                    if (awaitingOpen(a)) return;
                    if (a.walk != null) {
                        // 지금 고를 수 있는 선택지 / 들어가는 장면의 이벤트만, 장면 방문마다 선택지 이벤트 하나만
                        if (!a.walk.event(event)) {
                            illegal(p, a, "event " + event);
                            return;
                        }
                    }
                    if (def == null || !def.declares(event)) return; // 서버 파일에 할 일이 없는 이벤트
                    // 다시 보기는 아무것도 주지 않는다. 그 밖에는 플레이어마다 대화당 한 번
                    if (!ps.claimEvent(a, event)) return;
                    queueEffect(uuid, ps, new Effect(ps.nextSeq(), Effect.EVENT, a.id, event, false, false));
                }
                case StoryCodec.DIALOGUE_PROGRESS -> {
                    var g = StoryCodec.dialogueProgress(message);
                    Request a = active(ps, Kind.DIALOGUE, g.id());
                    if (a == null) return;
                    if (!StorySafety.validId(g.scene()) || g.line() < 0 || g.line() > 10000) {
                        illegal(p, a, "progress");
                        return;
                    }
                    if (awaitingOpen(a)) return;
                    if (a.walk != null) {
                        if (!a.walk.progress(g.scene(), g.line())) {
                            illegal(p, a, "progress " + g.scene() + " #" + g.line());
                            return;
                        }
                        if (!a.walk.scene.isEmpty()) {
                            a.scene = a.walk.scene;
                            a.line = a.walk.line;
                        }
                    } else {
                        a.scene = g.scene();
                        a.line = g.line();
                    }
                    dirty = true; // 대사 위치는 5초마다 모아서 저장
                }
                case StoryCodec.DIALOGUE_DONE -> {
                    var d = StoryCodec.dialogueDone(message);
                    Request a = active(ps, Kind.DIALOGUE, d.id());
                    if (a == null) return;
                    if (!StorySafety.validId(d.lastScene())) {
                        illegal(p, a, "done");
                        return;
                    }
                    if (awaitingOpen(a)) return;
                    if (a.walk != null) {
                        if (!a.walk.done(d.lastScene())) {
                            illegal(p, a, "done " + d.lastScene(), true);
                            return;
                        }
                    } else if (!d.lastScene().equals(a.scene)) {
                        return;
                    }
                    finishDialogue(p, ps, a, d.lastScene(), false);
                }
                default -> {
                }
            }
        } catch (IllegalArgumentException ex) {
            packetLog(p, p.getName() + " 의 잘못된 스토리 패킷 (" + channel + "): " + ex.getMessage());
        }
    }

    // ================================================================ 접속 / 종료 / MagicCodex 서비스

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        Player p = e.getPlayer();
        UUID uuid = p.getUniqueId();
        joinedAt.put(uuid, System.currentTimeMillis());
        helloSent.remove(uuid);
        buckets.remove(uuid);
        PlayerStory ps = state.players.get(uuid);
        if (ps == null) return;
        ps.name = p.getName();
        // 끝나지 않은 컷신·대화, 밀린 효과는 heartbeat 가 접속 2초 뒤부터 이어 간다 (모드 채널이 잡힐 때까지 기다림)
        if (ps.active != null) ps.active.resetTransient();
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        dialogueAcks.disconnect(e.getPlayer().getUniqueId());endingSessions.cancel(e.getPlayer().getUniqueId());reloadTickets.cancel(e.getPlayer().getUniqueId());
        UUID uuid = e.getPlayer().getUniqueId();
        recentFinish.remove(uuid);
        joinedAt.remove(uuid);
        helloSent.remove(uuid);
        buckets.remove(uuid);
        lastPacketLog.remove(uuid);
        clientCatalogs.remove(uuid);catalogUpdated.remove(uuid);
        // 컷신도 대화도 지우지 않는다: 다시 접속하면 컷신은 처음부터, 대화는 저장된 곳부터 이어서 연다
        PlayerStory ps = state.players.get(uuid);
        if (ps != null && ps.active != null) ps.active.resetTransient();
        if (dirty) saveSoon(null);
    }

    @EventHandler
    public void onServiceRegister(ServiceRegisterEvent e) {
        if (e.getProvider().getService().getName().equals(MagicCodexLink.FACADE)) {
            Bukkit.getScheduler().runTask(this, () -> codex.connect());
        }
    }

    @EventHandler
    public void onServiceUnregister(ServiceUnregisterEvent e) {
        if (e.getProvider().getService().getName().equals(MagicCodexLink.FACADE)) codex.invalidate();
    }


    // ================================================================ 명령어 (MagicCodexBridge 명령어 허브)

    /** 서버 설정 + dialogues/server_command.yml + 서버 대화 파일 다시 읽기. 실패하면 예전 값을 그대로 두고 예외를 던진다. */
    private void reloadServerSettings() throws Exception {
        File commandsFile = new File(getDataFolder(), "dialogues/server_command.yml");
        if (commandsFile.length() > 1_048_576) throw new IllegalArgumentException("server_command.yml exceeds 1 MiB");
        Object candidate = commandsFile.isFile() ? readYaml(commandsFile) : Map.of("commands", Map.of());
        ServerCommandRegistry.parse(candidate);
        // JavaPlugin.reloadConfig 는 오류를 삼키고 빈 설정으로 넘어가므로 먼저 엄격하게 읽어 본다
        var checked = new YamlConfiguration();
        checked.load(new File(getDataFolder(), "config.yml"));
        reloadConfig();
        endingCommands.replace(candidate);
        endingSessions.clear();
        dialogueAcks.clear();
        registeredCutscenes = getConfig().getStringList("cutscenes").stream().filter(StorySafety::validId).toList();
        codex.resetPlaceholderLookup();
        if (!codex.available()) codex.connect();
        getLogger().info("[스토리 새로고침] config.yml + dialogues/server_command.yml (대화 " + endingCommands.size() + "개). 진행·보상 기록은 그대로입니다.");
    }

    /** 명령 대상. 실행 단계에서 정한다 (못 찾으면 허브가 유저용 실패 알림). */
    @FunctionalInterface
    private interface Targets {
        List<Player> get(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException;
    }

    private static final String NICK = "닉네임";
    private static final Targets EVERYONE = ctx -> {
        List<Player> all = List.copyOf(Bukkit.getOnlinePlayers());
        if (all.isEmpty()) throw ChacaLink.error("접속한 유저가 없어요");
        return all;
    };
    private static final Targets NAMED = ctx -> List.of(ChacaLink.resolveOnline(ctx, NICK));

    /** 생략 가능한 [닉네임]: 없으면 명령을 친 사람 (콘솔이면 실패). */
    private static Player selfOr(CommandContext<CommandSourceStack> ctx, boolean named) throws CommandSyntaxException {
        if (named) return ChacaLink.resolveOnline(ctx, NICK);
        if (ctx.getSource().getSender() instanceof Player p) return p;
        throw ChacaLink.error("콘솔에서는 닉네임을 적어 주세요");
    }

    private static String nick(Player p) {
        return ChacaLink.name(p.getUniqueId());
    }

    private static String who(List<Player> players) {
        return players.size() == 1 ? nick(players.get(0)) + " 님" : players.size() + "명";
    }

    private void registerCommands() {
        if (!ChacaLink.ready()) {
            getLogger().warning("MagicCodexBridge 명령어 허브가 없어 /대화관리·/테스트 스토리·/유저관리 스토리기록 명령을 등록하지 않았습니다.");
            return;
        }
        try {
            // /대화관리 재생 <닉네임|모두> <대화|컷신> <ID> [건너뛰기허용|건너뛰기금지|자동]
            var play = Commands.literal("재생");
            var playAll = Commands.literal("모두");
            addPlayKinds(playAll, EVERYONE, "all");
            var playOne = Commands.argument(NICK, ChacaLink.player());
            addPlayKinds(playOne, NAMED, "arg");
            play.then(playAll).then(playOne);
            ChacaLink.branch(this, ChacaLink.대화관리, play, "스토리 대화·컷신 재생 (대기열 순서대로)", "재생 ");

            ChacaLink.branch(this, ChacaLink.대화관리, Commands.literal("중지")
                    .then(Commands.literal("모두").executes(ctx -> stopCommand(ctx, EVERYONE)))
                    .then(Commands.argument(NICK, ChacaLink.player()).executes(ctx -> stopCommand(ctx, NAMED))),
                    "보고 있는 스토리 멈추기 (끝 명령 실행 안 함)", "중지 ");

            ChacaLink.branch(this, ChacaLink.대화관리, Commands.literal("대기열비우기")
                    .then(Commands.literal("모두").executes(ctx -> clearCommand(ctx, EVERYONE)))
                    .then(Commands.argument(NICK, ChacaLink.player()).executes(ctx -> clearCommand(ctx, NAMED))),
                    "재생 중·대기 중인 스토리 모두 취소 (완료 기록 유지)", "대기열비우기 ");

            ChacaLink.branch(this, ChacaLink.대화관리, Commands.literal("목록")
                    .executes(ctx -> listCommand(ctx, false))
                    .then(Commands.argument(NICK, ChacaLink.player()).executes(ctx -> listCommand(ctx, true))),
                    "재생할 수 있는 대화·컷신 ID 보기", "목록");

            ChacaLink.branch(this, ChacaLink.대화관리, Commands.literal("클라이언트새로고침")
                    .executes(ctx -> clientReloadCommand(ctx, false))
                    .then(Commands.argument(NICK, ChacaLink.player()).executes(ctx -> clientReloadCommand(ctx, true))),
                    "유저 클라이언트의 대화·컷신 파일 다시 읽기 (기록 유지)", "클라이언트새로고침");

            // /유저관리 <닉네임> 스토리기록 <보기 [대화]|초기화 <대화|전체> 확인>
            ChacaLink.branch(this, ChacaLink.유저관리, Commands.literal("스토리기록")
                    .then(Commands.literal("보기")
                            .executes(ctx -> ledgerCommand(ctx, null))
                            .then(Commands.argument("대화", ChacaLink.word(() -> defs.keySet()))
                                    .suggests(this::suggestLedgerIds)
                                    .executes(ctx -> ledgerCommand(ctx, ctx.getArgument("대화", String.class)))))
                    .then(Commands.literal("초기화")
                            .then(Commands.literal("전체")
                                    .executes(ctx -> { throw confirmNeeded(); })
                                    .then(Commands.literal("확인").executes(ctx -> resetCommand(ctx, null))))
                            .then(Commands.argument("대화", ChacaLink.word(() -> defs.keySet()))
                                    .suggests(this::suggestLedgerIds)
                                    .executes(ctx -> { throw confirmNeeded(); })
                                    .then(Commands.literal("확인").executes(ctx -> resetCommand(ctx, ctx.getArgument("대화", String.class)))))),
                    "스토리 진행·완료 기록 보기·초기화 (이 서버)", "스토리기록 보기");

            // /테스트 스토리 <대화 <ID>|컷신 <ID>> [닉네임]
            ChacaLink.branch(this, ChacaLink.테스트, Commands.literal("스토리")
                    .then(Commands.literal("대화").then(idArgument(true, "self")
                            .executes(ctx -> previewDialogue(ctx, false))
                            .then(Commands.argument(NICK, ChacaLink.player()).executes(ctx -> previewDialogue(ctx, true)))))
                    .then(Commands.literal("컷신").then(idArgument(false, "self")
                            .executes(ctx -> previewCutscene(ctx, false))
                            .then(Commands.argument(NICK, ChacaLink.player()).executes(ctx -> previewCutscene(ctx, true))))),
                    "스토리 대화·컷신 미리보기 (조건·기록 무시, 보상 없음)", "스토리 대화 ");

            quests.registerCommands();
            ChacaLink.reload(this, "스토리", "스토리 서버 설정 + 대화 파일 + 진행 단계(steps.yml)", () -> {
                reloadServerSettings();
                File dialogues = new File(getDataFolder(), "dialogues");
                applyDefs(loadDefs(dialogues), null);
                quests.reload();
            });
            ChacaLink.status(this, "스토리", () -> {
                int mod = 0, online = 0;
                for (Player p : Bukkit.getOnlinePlayers()) { online++; if (hasMod(p)) mod++; }
                return "정상 · 스토리 모드 " + mod + "/" + online + "명 · 호감도 " + (codex.available() ? "MagicCodex 연결" : "대기 (MagicCodex 연결 없음)") + " · " + quests.statusLine();
            });
        } catch (RuntimeException e) {
            getLogger().log(Level.WARNING, "스토리 명령을 허브에 등록하지 못했습니다", e);
        }
    }

    private static CommandSyntaxException confirmNeeded() {
        return ChacaLink.error("되돌릴 수 없어요. 끝에 '확인'을 붙여 다시 입력해 주세요");
    }

    private void addPlayKinds(ArgumentBuilder<CommandSourceStack, ?> parent, Targets targets, String scope) {
        parent.then(Commands.literal("대화").then(idArgument(true, scope).executes(ctx -> playDialogueCommand(ctx, targets))));
        parent.then(Commands.literal("컷신").then(idArgument(false, scope)
                .executes(ctx -> playCutsceneCommand(ctx, targets, StoryCodec.MODE_AUTO))
                .then(Commands.literal("건너뛰기허용").executes(ctx -> playCutsceneCommand(ctx, targets, StoryCodec.MODE_SKIP)))
                .then(Commands.literal("건너뛰기금지").executes(ctx -> playCutsceneCommand(ctx, targets, StoryCodec.MODE_NOSKIP)))
                .then(Commands.literal("자동").executes(ctx -> playCutsceneCommand(ctx, targets, StoryCodec.MODE_AUTO)))));
    }

    /** scope: all = 접속자 전체의 클라 목록, arg = 앞 칸 닉네임의 클라 목록, self = 명령 친 사람 (콘솔이면 전체). */
    private RequiredArgumentBuilder<CommandSourceStack, String> idArgument(boolean dialogue, String scope) {
        return Commands.argument("ID", ChacaLink.word(() -> completionIds(dialogue, null)))
                .suggests((ctx, b) -> {
                    UUID target = null;
                    try {
                        if (scope.equals("arg")) target = ChacaLink.find(ctx.getArgument(NICK, String.class));
                        else if (scope.equals("self") && ctx.getSource().getSender() instanceof Player p) target = p.getUniqueId();
                    } catch (RuntimeException ignored) {
                    }
                    if (scope.equals("arg") && target == null) return b.buildFuture();
                    return suggest(b, completionIds(dialogue, target));
                });
    }

    private static CompletableFuture<Suggestions> suggest(SuggestionsBuilder b, java.util.Collection<String> values) {
        String typed = b.getRemaining().toLowerCase(Locale.ROOT);
        for (String v : values) if (v.toLowerCase(Locale.ROOT).startsWith(typed)) b.suggest(v);
        return b.buildFuture();
    }

    private CompletableFuture<Suggestions> suggestLedgerIds(CommandContext<CommandSourceStack> ctx, SuggestionsBuilder b) {
        Set<String> ids = new TreeSet<>(defs.keySet());
        try {
            PlayerStory ps = state.players.get(ChacaLink.target(ctx));
            if (ps != null) ids.addAll(ps.done.keySet());
        } catch (Exception ignored) {
        }
        return suggest(b, ids);
    }

    /** 자동완성 후보: 컷신은 config 의 cutscenes + 클라가 알려 온 목록, 대화는 클라가 알려 온 목록. target null = 접속자 전체. */
    private List<String> completionIds(boolean dialogue, UUID target) {
        var ids = new TreeSet<String>();
        if (!dialogue) ids.addAll(registeredCutscenes);
        if (target == null) clientCatalogs.values().forEach(c -> ids.addAll(dialogue ? c.dialogues() : c.cutscenes()));
        else {
            StoryCatalog c = clientCatalogs.get(target);
            if (c != null) ids.addAll(dialogue ? c.dialogues() : c.cutscenes());
        }
        return List.copyOf(ids);
    }

    private static String id(CommandContext<CommandSourceStack> ctx, boolean dialogue) throws CommandSyntaxException {
        String id = ctx.getArgument("ID", String.class);
        if (!StorySafety.validId(id)) throw ChacaLink.error((dialogue ? "대화" : "컷신") + " ID는 영문 소문자·숫자·_·-만 쓸 수 있어요");
        return id;
    }

    /** 대화 재생 (예전 storydialogue &lt;플레이어&gt; &lt;id&gt; 와 같은 경로: 클라이언트 대화 파일 + 서버 끝 명령 세션). */
    private int playDialogueCommand(CommandContext<CommandSourceStack> ctx, Targets targets) throws CommandSyntaxException {
        String id = id(ctx, true);
        // 공개 전인 장의 대화는 명령(끝 명령의 연결 포함)으로도 열지 않는다. 미리 보기는 /테스트 스토리 대화 (보상 없음)
        if (quests != null && quests.idLocked(id)) {
            getLogger().info("[스토리 장 공개] 공개 전인 장의 대화 " + id + " 재생 요청을 막았습니다 (/대화관리 장공개 목록)");
            for (Player p : targets.get(ctx)) ChacaLink.notify(p, "system", "story-chapter-locked", quests.lockedTitle(), "", 3_000);
            throw ChacaLink.error("대화 " + id + " 은(는) 아직 공개되지 않은 장이에요 (/대화관리 장공개 목록 · 미리보기는 /테스트 스토리 대화)");
        }
        List<Player> t = targets.get(ctx);
        int n = 0;
        for (Player p : t) if (p.getListeningPluginChannels().contains(StoryCodec.DIALOGUE_VIEW)) { requestNicknameDialogue(p, id); n++; }
        if (n == 0) throw ChacaLink.error(t.size() == 1 ? nick(t.get(0)) + " 님은 최신 스토리 모드가 없어요" : "최신 스토리 모드가 있는 유저가 없어요");
        ChacaLink.ok(ctx.getSource().getSender(), who(t) + "에게 대화 " + id + " 재생을 요청했어요" + (n < t.size() ? " (모드 없음 " + (t.size() - n) + "명 제외)" : ""));
        return 1;
    }

    private int playCutsceneCommand(CommandContext<CommandSourceStack> ctx, Targets targets, int mode) throws CommandSyntaxException {
        String id = id(ctx, false);
        if (quests != null && quests.idLocked(id)) {
            getLogger().info("[스토리 장 공개] 공개 전인 장의 컷신 " + id + " 재생 요청을 막았습니다 (/대화관리 장공개 목록)");
            throw ChacaLink.error("컷신 " + id + " 은(는) 아직 공개되지 않은 장이에요 (/대화관리 장공개 목록 · 미리보기는 /테스트 스토리 컷신)");
        }
        List<Player> t = targets.get(ctx);
        int n = 0;
        for (Player p : t) if (requestCutscene(null, p, id, mode)) n++;
        if (n == 0) throw ChacaLink.error(t.size() == 1 ? nick(t.get(0)) + " 님은 이미 이 컷신을 보는 중이거나 대기열이 가득 찼어요" : "재생을 요청할 수 있는 유저가 없어요");
        ChacaLink.ok(ctx.getSource().getSender(), who(t) + "에게 컷신 " + id + " 재생을 요청했어요" + (n < t.size() ? " (" + (t.size() - n) + "명 제외)" : ""));
        return 1;
    }

    private int stopCommand(CommandContext<CommandSourceStack> ctx, Targets targets) throws CommandSyntaxException {
        List<Player> t = targets.get(ctx);
        for (Player p : t) {
            PlayerStory ps = state.players.get(p.getUniqueId());
            // 지금 보는 것만 멈춘다 (둘 다 멈추면 컷신을 멈춘 뒤 바로 시작된 다음 대화까지 닫힌다)
            if (ps != null && ps.active != null && ps.active.kind == Kind.CUTSCENE) stopCutscene(p);
            else stopDialogue(p);
        }
        ChacaLink.ok(ctx.getSource().getSender(), who(t) + "의 스토리를 멈췄어요");
        return 1;
    }

    private int clearCommand(CommandContext<CommandSourceStack> ctx, Targets targets) throws CommandSyntaxException {
        List<Player> t = targets.get(ctx);
        for (Player p : t) clearStory(p);
        ChacaLink.ok(ctx.getSource().getSender(), who(t) + "의 스토리 대기열을 비웠어요");
        return 1;
    }

    private int listCommand(CommandContext<CommandSourceStack> ctx, boolean named) throws CommandSyntaxException {
        UUID target = null;
        String title = "접속자 전체";
        if (named) {
            target = ChacaLink.resolve(ctx, NICK);
            title = ChacaLink.name(target);
        } else if (ctx.getSource().getSender() instanceof Player p) {
            target = p.getUniqueId();
            title = nick(p);
        }
        List<String> d = completionIds(true, target), c = completionIds(false, target);
        ChacaLink.lines(ctx.getSource().getSender(), List.of(
                "스토리 목록 (" + title + ")",
                "대화 " + d.size() + "개: " + (d.isEmpty() ? "-" : String.join(", ", d)),
                "컷신 " + c.size() + "개: " + (c.isEmpty() ? "-" : String.join(", ", c))));
        return 1;
    }

    /** 대상 클라이언트의 대화·컷신 파일을 다시 읽게 한다 (서버 설정은 /서버관리 새로고침 스토리). */
    private int clientReloadCommand(CommandContext<CommandSourceStack> ctx, boolean named) throws CommandSyntaxException {
        Player p = selfOr(ctx, named);
        CommandSender s = ctx.getSource().getSender();
        if (p.getListeningPluginChannels().contains(StoryCodec.RELOAD_REQUEST)) {
            var ticket = reloadTickets.begin(p.getUniqueId(), s);
            p.sendPluginMessage(this, StoryCodec.RELOAD_REQUEST, StoryCodec.reloadRequest(ticket.token()));
            ChacaLink.ok(s, nick(p) + " 님 클라이언트에 새로고침을 요청했어요");
            Bukkit.getScheduler().runTaskLater(this, () -> {
                var requester = reloadTickets.finish(p.getUniqueId(), ticket.token());
                if (requester != null) ChacaLink.fail(requester, nick(p) + " 님 클라이언트가 새로고침 결과를 보내지 않았어요",
                        new IllegalStateException("[스토리] " + p.getName() + " 클라이언트 새로고침 응답 없음 (10초)"));
            }, 200L);
            return 1;
        }
        if (!p.getListeningPluginChannels().contains(StoryCodec.STORY_RELOAD)) throw ChacaLink.error(nick(p) + " 님은 최신 스토리 모드가 없어요");
        p.sendPluginMessage(this, StoryCodec.STORY_RELOAD, StoryCodec.reloadContent(StoryCodec.KIND_DIALOGUE));
        ChacaLink.ok(s, nick(p) + " 님 클라이언트에 대화 파일 검사를 요청했어요 (결과는 그 화면에)");
        return 1;
    }

    /** 재생 중인 것과 대기열을 모두 버린다 (막힌 플레이어를 풀 때). 완료 기록과 실행 대기 효과는 건드리지 않는다. */
    private void clearStory(Player p) {
        endingSessions.cancel(p.getUniqueId());dialogueAcks.disconnect(p.getUniqueId());
        if (quests != null) quests.dialogueAborted(p.getUniqueId(), null);
        PlayerStory ps = state.players.get(p.getUniqueId());
        if (ps != null) {
            ps.active = null;
            ps.queue.clear();
            saveSoon(null);
            prune(p.getUniqueId(), ps);
        }
        protection.release(p);
        if (hasMod(p)) {
            p.sendPluginMessage(this, StoryCodec.CUTSCENE_STOP, new byte[0]);
            p.sendPluginMessage(this, StoryCodec.DIALOGUE_STOP, new byte[0]);
        }
    }

    // ---------------------------------------------------------------- /테스트 스토리 (미리보기)

    /**
     * 대화 미리보기: 대기열·완료 기록·끝 명령 세션을 거치지 않고 클라이언트 대화 파일을 바로 연다. 이미 끝낸 대화도 다시 보인다
     * (예전 storydialogue … replay 인자가 무시되던 문제). 서버 흐름용 열기 신호(DIALOGUE_OPEN)를 쓰므로 클라는 로컬 기록·보상·명령을
     * 건드리지 않고, 클라가 보내는 이벤트·끝 신호는 서버에 해당 요청이 없어 무시된다 → 보상·명령 없음.
     */
    private int previewDialogue(CommandContext<CommandSourceStack> ctx, boolean named) throws CommandSyntaxException {
        String id = id(ctx, true);
        Player p = selfOr(ctx, named);
        if (!p.getListeningPluginChannels().contains(StoryCodec.DIALOGUE_OPEN)) throw ChacaLink.error(nick(p) + " 님은 최신 스토리 모드가 없어요");
        PlayerStory ps = state.players.get(p.getUniqueId());
        if ((ps != null && ps.active != null && ps.active.sent) || endingSessions.current(p.getUniqueId(), System.currentTimeMillis()) != null)
            throw ChacaLink.error(nick(p) + " 님은 지금 다른 스토리를 보는 중이에요");
        sendContent(p, id);
        p.sendPluginMessage(this, StoryCodec.DIALOGUE_OPEN, StoryCodec.dialogueOpen(id, "", StoryCodec.encodeVars(textVars(p)), "", 0));
        getLogger().info("[스토리 미리보기] " + ctx.getSource().getSender().getName() + " → " + p.getName() + " 대화 " + id);
        ChacaLink.ok(ctx.getSource().getSender(), nick(p) + " 님에게 대화 " + id + " 미리보기를 열었어요");
        return 1;
    }

    /** 컷신 미리보기: 대기열·기록을 거치지 않고 바로 보낸다. 끝나도 on-finish 를 실행하지 않는다 (서버가 요청한 재생이 아니므로 끝 신호를 무시). */
    private int previewCutscene(CommandContext<CommandSourceStack> ctx, boolean named) throws CommandSyntaxException {
        String id = id(ctx, false);
        Player p = selfOr(ctx, named);
        if (!hasMod(p)) throw ChacaLink.error(nick(p) + " 님은 스토리 모드가 없어요");
        PlayerStory ps = state.players.get(p.getUniqueId());
        if (ps != null && ps.active != null && ps.active.sent) throw ChacaLink.error(nick(p) + " 님은 지금 다른 스토리를 보는 중이에요");
        p.sendPluginMessage(this, StoryCodec.CUTSCENE_PLAY, StoryCodec.cutscenePlay(id, StoryCodec.MODE_SKIP));
        getLogger().info("[스토리 미리보기] " + ctx.getSource().getSender().getName() + " → " + p.getName() + " 컷신 " + id);
        ChacaLink.ok(ctx.getSource().getSender(), nick(p) + " 님에게 컷신 " + id + " 미리보기를 열었어요");
        return 1;
    }

    // ---------------------------------------------------------------- /유저관리 <닉네임> 스토리기록

    private static String describe(Request r) {
        String what = (r.kind == Kind.CUTSCENE ? "컷신 " : "대화 ") + r.id + (r.replay ? " (다시 보기)" : "");
        if (r.kind == Kind.DIALOGUE && !r.scene.isEmpty()) what += " [" + r.scene + " #" + r.line + "]";
        return what;
    }

    /** 진행 상태와 완료 기록 보기 (이 서버의 progress.yml). */
    private int ledgerCommand(CommandContext<CommandSourceStack> ctx, String id) throws CommandSyntaxException {
        UUID uuid = ChacaLink.target(ctx);
        String name = ChacaLink.name(uuid);
        CommandSender s = ctx.getSource().getSender();
        PlayerStory ps = state.players.get(uuid);
        if (ps == null) {
            ChacaLink.ok(s, name + " 님은 이 서버에 스토리 기록이 없어요");
            return 1;
        }
        List<String> out = new ArrayList<>();
        if (id != null) {
            if (!StorySafety.validId(id)) throw ChacaLink.error("대화 ID는 영문 소문자·숫자·_·-만 쓸 수 있어요");
            StoryState.Done d = ps.done.get(id);
            out.add(name + " / 대화 " + id);
            out.add(d == null ? "아직 끝내지 않음" : "끝냄 (결말 " + (d.scene.isEmpty() ? "-" : d.scene) + ", " + d.times + "번, " + new java.util.Date(d.at) + ")");
            Set<String> fired = ps.fired.get(id);
            out.add("실행된 이벤트: " + (fired == null || fired.isEmpty() ? "없음" : String.join(", ", fired)));
            out.add("이 대화로 오른 호감도 합: " + ps.gained.getOrDefault(id, 0));
            for (var r : ps.rewards.entrySet()) if (LocalStoryRewards.dialogue(r.getKey()).equals(id)) out.add("고정 보상 " + r.getKey() + ": " + rewardState(r.getValue()));
            ChacaLink.lines(s, out);
            return 1;
        }
        Request act = ps.active;
        out.add(name + " 스토리 기록 (이 서버)");
        out.add("지금: " + (act == null ? "없음" : describe(act) + (act.sent ? " — 보는 중" : act.parked ? " — 멈춤 (다음 접속 때 다시)" : " — 보내기 대기")));
        if (!ps.queue.isEmpty()) {
            List<String> q = new ArrayList<>();
            for (Request r : ps.queue) q.add(describe(r));
            out.add("대기: " + String.join(", ", q));
        }
        if (!ps.effects.isEmpty()) out.add("실행 대기 효과: " + ps.effects.size() + "개");
        if (!ps.rewards.isEmpty()) {
            List<String> rw = new ArrayList<>();
            for (var r : ps.rewards.entrySet()) rw.add(r.getKey() + " " + rewardState(r.getValue()));
            out.add("고정 보상: " + String.join(", ", rw));
        }
        List<String> done = new ArrayList<>();
        for (var e : ps.done.entrySet()) done.add(e.getKey() + (e.getValue().scene.isEmpty() ? "" : "(" + e.getValue().scene + ")"));
        out.add("끝낸 대화 " + done.size() + "개: " + (done.isEmpty() ? "-" : String.join(", ", done)));
        ChacaLink.lines(s, out);
        return 1;
    }

    private static String rewardState(String s) {
        return switch (s) {
            case LocalStoryRewards.APPLIED -> "받음";
            case LocalStoryRewards.FAILED -> "실패 (초기화하면 다시 받을 수 있음)";
            default -> "처리 중/불확실 (다시 주지 않음)";
        };
    }

    /** 완료 기록 지우기 (다시 처음부터 보상까지 받을 수 있게 됨). id null = 전체. */
    private int resetCommand(CommandContext<CommandSourceStack> ctx, String id) throws CommandSyntaxException {
        UUID uuid = ChacaLink.target(ctx);
        String name = ChacaLink.name(uuid);
        CommandSender s = ctx.getSource().getSender();
        if (id != null && !StorySafety.validId(id)) throw ChacaLink.error("대화 ID는 영문 소문자·숫자·_·-만 쓸 수 있어요");
        PlayerStory ps = state.players.get(uuid);
        if (ps == null) throw ChacaLink.error(name + " 님은 이 서버에 스토리 기록이 없어요");
        if (id == null) {
            int n = ps.done.size();
            ps.done.clear();
            ps.fired.clear();
            ps.gained.clear();
            ps.rewards.clear();
            getLogger().info(s.getName() + " 이(가) " + name + "(" + uuid + ") 의 대화 완료 기록 전체 (" + n + "개) 를 지웠습니다");
            ChacaLink.ok(s, name + " 님의 대화 완료 기록 " + n + "개를 모두 지웠어요");
        } else {
            boolean had = ps.resetLedger(id);
            if (!had) throw ChacaLink.error(name + " 님은 대화 " + id + " 기록이 없어요");
            getLogger().info(s.getName() + " 이(가) " + name + "(" + uuid + ") 의 대화 " + id + " 완료 기록을 지웠습니다");
            ChacaLink.ok(s, name + " 님의 대화 " + id + " 기록을 지웠어요");
        }
        saveSoon(null);
        prune(uuid, ps);
        return 1;
    }
}
