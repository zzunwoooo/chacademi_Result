package school.chacademia.kyudichi;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.bukkit.Bukkit;
import org.bukkit.entity.Entity;
import org.bukkit.plugin.java.JavaPlugin;

/** 큐디치 (3:3 aerial PvP). Runs on the arena server; see docs/handoff/CLAUDE_KYUDICHI_*.md. */
public final class KyudichiPlugin extends JavaPlugin {
    private static final List<String> BLUEPRINTS = List.of("kyudichi_quaffle", "kyudichi_bludger", "kyudichi_snitch");

    /** Both duel and team matches own their independent loadout until restoration completes. */
    public boolean generalPresetLocked(java.util.UUID id){return matches!=null&&matches.of(id)!=null;}
    private Rules rules;
    private ArenaStore arenas;
    private Hooks hooks;
    private RatingStore store;
    private Matches matches;
    private Results results;
    private Net net;
    private Pings pings;
    private Spectate spectate;
    private Selections selections;
    private Link link;

    @Override public void onEnable() {
        saveDefaultConfig();
        migrateConfig();
        rules = new Rules(getConfig());
        arenas = new ArenaStore(getDataFolder().toPath().resolve("arenas.yml"), getLogger());
        arenas.load();
        hooks = new Hooks(this);
        store = new RatingStore(this, (int) rules.rating.start());
        try { store.open(getDataFolder().toPath()); }
        catch (Exception e) { getLogger().severe("큐디치 DB를 열 수 없어 플러그인을 끕니다: " + e); Bukkit.getPluginManager().disablePlugin(this); return; }
        matches = new Matches(this); results = new Results(this); net = new Net(this); pings = new Pings(); spectate = new Spectate(this);
        selections = new Selections(this);
        net.register();
        link = new Link(this); link.register();
        var listener = new GameListener(this);
        Bukkit.getPluginManager().registerEvents(listener, this);
        Bukkit.getPluginManager().registerEvents(selections, this);
        // Bridge / MagicMythic events after every plugin is enabled
        Bukkit.getScheduler().runTask(this, () -> { listener.hookBridge(); listener.hookHeal(); listener.hookAlly(); listener.hookMatch(); });
        registerCommands();
        matches.startTicking();
        Bukkit.getScheduler().runTaskTimer(this, () -> { selections.tick(); spectate.tick(); }, 10, 10);
        // weekly seasons: an ended season resets that mode and the next one starts (checked every minute)
        Bukkit.getScheduler().runTaskTimer(this, () -> store.rollSeasons(rolled -> {
            if (rolled != null) for (var s : rolled) getLogger().info("시즌 종료 → " + (s.mode().equals(RatingStore.DUEL) ? "1:1" : "큐디치") + " 시즌 " + s.number() + " 시작 (레이팅·전적 초기화)");
        }), 200, 1200);
        // A09: rated results the DB did not confirm (outbox/) are replayed shortly after start-up and every minute
        Bukkit.getScheduler().runTaskTimer(this, () -> results.retryOutbox(), 100, 1200);
        installBlueprints();
        removeStrays();
        for (var p : Bukkit.getOnlinePlayers()) store.loadAll(p.getUniqueId(), hooks.nick(p));
        getLogger().info("큐디치 준비 완료 · 경기장 " + arenas.all().size() + "개");
    }

    @Override public void onDisable() {
        if (matches != null) matches.stopAll("서버 종료/리로드");
        if (net != null) net.unregister();
        if (link != null) link.unregister();
        if (store != null) store.close();
    }

    /** /큐디치관리 · /유저관리 … 큐디치·결투·파티 · /테스트 큐디치·결투 → MagicCodexBridge 명령어 허브. */
    private void registerCommands() {
        if (!ChacaLink.ready()) { getLogger().warning("MagicCodexBridge 명령어 허브가 없어 큐디치 명령을 등록하지 않았어요"); return; }
        try {
            new KyudichiCommands(this).register();
            ChacaLink.reload(this, "큐디치", "큐디치 config.yml · arenas.yml (진행 중인 경기는 이전 규칙 유지)", this::reload);
            ChacaLink.summary(this, "큐디치", id -> line(id, RatingStore.MODE));
            ChacaLink.summary(this, "결투", id -> line(id, RatingStore.DUEL));
            ChacaLink.status(this, "큐디치 경기장", () -> "정상 · 경기장 " + arenas.all().size() + "개 · 진행 중 " + matches.all().size() + "경기");
        } catch (RuntimeException e) { getLogger().log(java.util.logging.Level.WARNING, "큐디치 명령 등록 실패", e); }
    }

    /** /유저관리 요약 한 줄: 이 서버가 캐시에 가진 값만 (없으면 줄 생략). */
    private String line(java.util.UUID id, String mode) {
        var r = store.peek(id, mode);
        if (r == null) return "";
        return rules.rating.tier(r.rating(), store.rank(id, mode)).label + " " + r.rating() + " · " + r.wins() + "승 " + r.losses() + "패";
    }

    void reload() {
        reloadConfig();
        migrateConfig();
        rules = new Rules(getConfig());
        if (matches.all().isEmpty()) arenas.load();
        hooks.reset();
    }

    /** Ball models go to ModelEngine's blueprint folder (only missing files; /meg reload afterwards). */
    private void installBlueprints() {
        var me = Bukkit.getPluginManager().getPlugin("ModelEngine");
        if (me == null) { getLogger().info("ModelEngine 없음: 공은 아이템 모양으로 표시"); return; }
        Path dir = me.getDataFolder().toPath().resolve("blueprints").resolve("kyudichi");
        int added = 0;
        try {
            Files.createDirectories(dir);
            for (String b : BLUEPRINTS) {
                Path f = dir.resolve(b + ".bbmodel");
                if (Files.exists(f)) continue;
                try (InputStream in = getResource("blueprints/" + b + ".bbmodel")) { if (in != null) { Files.copy(in, f); added++; } }
            }
        } catch (IOException e) { getLogger().warning("공 모델 설치 실패: " + e.getMessage()); }
        if (added > 0) getLogger().warning("공 모델 " + added + "개를 ModelEngine에 설치했어요. /meg reload 후 리소스팩(ItemsAdder) 반영이 필요합니다.");
    }

    /** Ball entities are non-persistent, but a hard crash between saves could leave some: remove tagged leftovers. */
    private void removeStrays() {
        int n = 0;
        for (var w : Bukkit.getWorlds()) for (Entity e : w.getEntities()) if (Hooks.isOurs(e)) { e.remove(); n++; }
        if (n > 0) getLogger().info("남아 있던 큐디치 공 " + n + "개 제거");
    }

    Rules rules() { return rules; }
    ArenaStore arenas() { return arenas; }
    Hooks hooks() { return hooks; }

    /** config-version 1 → 2 (owner 2026-10-17): much faster balls, quaffle gravity. Only these keys change; the rest is kept. */
    private void migrateConfig() {
        var c = getConfig();
        int version = c.getInt("config-version", 1);
        if (version >= 4) return;
        if (version < 2) {
            Object[][] v2 = {
                    {"quaffle.throw-speed", 44.0}, {"quaffle.gravity", 16.0},
                    {"bludger.wander-speed", 16.0}, {"bludger.chase-speed", 30.0}, {"bludger.steer", 6.0}, {"bludger.hit-radius", 1.5},
                    {"bludger.ignore-after-hit-seconds", 1.2}, {"bludger.idle-target-radius", 40}, {"bludger.second-delay-seconds", 0.5},
                    {"bludger.deflect-speed", 42.0},
                    {"snitch.speed", 30.0}, {"snitch.flee-radius", 26}, {"snitch.catch-range", 1.6},
                    {"snitch.pause-every-seconds", java.util.List.of(10, 18)}, {"snitch.pause-seconds", 0.6}};
            for (Object[] kv : v2) c.set((String) kv[0], kv[1]);
            c.set("models.scale", 2.0); c.set("key.range", 5.0);
        } else if (version < 3) {
            // 3 (owner 2026-10-19): balls twice as big, longer F reach — only when the old defaults are still there
            if (c.getDouble("models.scale", 1.0) == 1.0) c.set("models.scale", 2.0);
            if (c.getDouble("key.range", 3.0) == 3.0) c.set("key.range", 5.0);
        }
        // 4 (owner 2026-10-20): balls a bit smaller again, narrower bludger hit, the free quaffle wanders like a bludger
        if (c.getDouble("models.scale", 2.0) == 2.0) c.set("models.scale", 1.4);
        if (c.getDouble("bludger.hit-radius", 1.5) == 1.5) c.set("bludger.hit-radius", 1.0);
        if (c.getDouble("quaffle.drift-speed", 3.0) == 3.0) c.set("quaffle.drift-speed", 12.0);
        c.set("config-version", 4);
        saveConfig();
        getLogger().info("config.yml 을 버전 4로 바꿨어요 (공 크기 1.4·블러저 타격 범위 1.0·퀘이플 자유 비행)");
    }
    RatingStore store() { return store; }
    Matches matches() { return matches; }
    Results results() { return results; }
    Net net() { return net; }
    Pings pings() { return pings; }
    Spectate spectate() { return spectate; }
    Selections selections() { return selections; }
    Link link() { return link; }
}
