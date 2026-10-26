package school.chacademia.kyudichi;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.logging.Logger;
import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;
import school.magiccodex.database.ConnectionHolder;
import school.magiccodex.database.DatabaseSettings;

/**
 * Ratings and cumulative stats, shared by every server through the same database (plugins/ChacaKyudichi/database.properties,
 * same format as MagicCodexBridge; MariaDB → tables codex_kyudichi_*). Modes: "kyudichi" (3:3) and "duel" (1:1 결투), each with its
 * own ratings, stats and weekly season (kyudichi_season). All SQL runs on one worker thread; callbacks come back on the main thread.
 */
final class RatingStore {
    static final String MODE = "kyudichi", DUEL = "duel";
    static final List<String> MODES = List.of(MODE, DUEL);
    /** Wire mode number (KyudichiQueueProtocol.MODE_*) → DB mode. */
    static String mode(int wire) { return wire == 1 ? DUEL : MODE; }
    record Season(String mode, int number, long startedAt, long endsAt) {}

    record Row(UUID id, String name, String mode, int rating, int games, int wins, int losses, int draws, int goals, int kills, int deaths,
               int assists, double damage, double heal, int snitch, int deflectHits, int mvp, long reachedAt, long bannedUntil, int leaves) {
        static Row fresh(UUID id, String name, String mode, int start) { return new Row(id, name, mode, start, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0); }
    }
    record Recent(UUID match, long endedAt, int team, int scoreA, int scoreB, int outcome, int goals, int kills, int deaths, boolean mvp, int delta) {}
    record Delta(UUID id, String name, int rating, boolean win, boolean draw, boolean loss, boolean leaver, int goals, int kills, int deaths, int assists,
                 double damage, double heal, int snitch, int deflectHits, boolean mvp, int team, int delta) {}
    /** Match row extras: team names ("준우 파티" / 1:1 = the player) and team houses (-1 = none) for the 전적 table. */
    record Teams(String nameA, String nameB, int houseA, int houseB) {}

    private final Plugin plugin; private final Logger log; private final int start;
    private final ExecutorService io = Executors.newSingleThreadExecutor(r -> { var t = new Thread(r, "ChacaKyudichi-db"); t.setDaemon(true); return t; });
    private final Map<String, Map<UUID, Row>> caches = new ConcurrentHashMap<>();
    private final Map<String, List<UUID>> challengers = new ConcurrentHashMap<>();
    private final Map<String, Season> seasons = new ConcurrentHashMap<>();
    private Map<UUID, Row> cache(String mode) { return caches.computeIfAbsent(mode, k -> new ConcurrentHashMap<>()); }
    private ConnectionHolder db; private DatabaseSettings settings;
    private String tRating, tMatch, tPlayer, tSeason;

    RatingStore(Plugin plugin, int start) { this.plugin = plugin; this.log = plugin.getLogger(); this.start = start; }

    void open(Path dataFolder) throws Exception {
        io.submit(() -> {
            Path file = dataFolder.resolve("database.properties");
            settings = DatabaseSettings.load(file);
            log.info(DatabaseSettings.describe(file, settings));
            db = settings.holder(dataFolder.resolve("kyudichi.db"));
            tRating = settings.table("kyudichi_rating", "kyudichi_rating");
            tMatch = settings.table("kyudichi_match", "kyudichi_match");
            tPlayer = settings.table("kyudichi_match_player", "kyudichi_match_player");
            tSeason = settings.table("kyudichi_season", "kyudichi_season");
            // MariaDB: 한글 이름(name, name_a/b)이 깨지지 않도록 문자셋을 명시한다 (SQLite는 해당 없음).
            String tail = settings.mariaDb() ? " ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE utf8mb4_unicode_ci" : "";
            try (var s = db.get().createStatement()) {
                s.execute("CREATE TABLE IF NOT EXISTS " + tRating + "(player CHAR(36) NOT NULL, mode VARCHAR(16) NOT NULL, name VARCHAR(32) NOT NULL,"
                        + " rating INT NOT NULL, games INT NOT NULL, wins INT NOT NULL, losses INT NOT NULL, draws INT NOT NULL, goals INT NOT NULL,"
                        + " kills INT NOT NULL, deaths INT NOT NULL, assists INT NOT NULL, damage DOUBLE NOT NULL, heal DOUBLE NOT NULL, snitch INT NOT NULL,"
                        + " deflect_hits INT NOT NULL, mvp INT NOT NULL, reached_at BIGINT NOT NULL, banned_until BIGINT NOT NULL, leaves INT NOT NULL,"
                        + " PRIMARY KEY(player, mode))" + tail);
                s.execute("CREATE TABLE IF NOT EXISTS " + tMatch + "(id CHAR(36) NOT NULL PRIMARY KEY, mode VARCHAR(16) NOT NULL, arena VARCHAR(32) NOT NULL,"
                        + " ended_at BIGINT NOT NULL, score_a INT NOT NULL, score_b INT NOT NULL, winner INT NOT NULL, reason VARCHAR(16) NOT NULL)" + tail);
                s.execute("CREATE TABLE IF NOT EXISTS " + tPlayer + "(match_id CHAR(36) NOT NULL, player CHAR(36) NOT NULL, team INT NOT NULL,"
                        + " outcome INT NOT NULL, goals INT NOT NULL, kills INT NOT NULL, deaths INT NOT NULL, mvp INT NOT NULL, delta INT NOT NULL,"
                        + " ended_at BIGINT NOT NULL, PRIMARY KEY(match_id, player))" + tail);
                s.execute("CREATE TABLE IF NOT EXISTS " + tSeason + "(mode VARCHAR(16) NOT NULL PRIMARY KEY, season INT NOT NULL, started_at BIGINT NOT NULL, ends_at BIGINT NOT NULL)" + tail);
            }
            // 2026-10-18 결투장: columns added to the history tables (ignored when they already exist)
            for (String alter : List.of(
                    "ALTER TABLE " + tPlayer + " ADD COLUMN damage DOUBLE NOT NULL DEFAULT 0",
                    "ALTER TABLE " + tPlayer + " ADD COLUMN rating_after INT NOT NULL DEFAULT 0",
                    "ALTER TABLE " + tMatch + " ADD COLUMN name_a VARCHAR(32) NOT NULL DEFAULT ''",
                    "ALTER TABLE " + tMatch + " ADD COLUMN name_b VARCHAR(32) NOT NULL DEFAULT ''",
                    "ALTER TABLE " + tMatch + " ADD COLUMN house_a INT NOT NULL DEFAULT -1",
                    "ALTER TABLE " + tMatch + " ADD COLUMN house_b INT NOT NULL DEFAULT -1")) {
                try (var s = db.get().createStatement()) { s.execute(alter); }
                catch (SQLException e) { if (!duplicateColumn(e)) log.severe("큐디치 DB 열 추가 실패 (이후 기록 저장이 실패할 수 있음): " + alter + " → " + e.getMessage()); }
            }
            loadSeasonsNow();
            for (String m : MODES) refreshChallengersNow(m);
            return null;
        }).get(15, TimeUnit.SECONDS);
    }

    /** "이미 있는 열" 오류만 true: MariaDB/MySQL 1060(ER_DUP_FIELDNAME, SQLState 42S21), SQLite "duplicate column name". 권한 부족 등 다른 오류는 false. */
    static boolean duplicateColumn(SQLException e) {
        String message = e.getMessage() == null ? "" : e.getMessage().toLowerCase(java.util.Locale.ROOT);
        return e.getErrorCode() == 1060 || "42S21".equals(e.getSQLState()) || message.contains("duplicate column");
    }

    void close() {
        io.shutdown();
        try { io.awaitTermination(10, TimeUnit.SECONDS); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        try { if (db != null) db.close(); } catch (Exception ignored) { }
    }

    private <T> void async(Sql<T> work, Consumer<T> done) {
        io.execute(() -> {
            T v;
            try { v = work.run(db.get()); }
            catch (SQLException | RuntimeException e) { log.warning("큐디치 DB 작업 실패: " + e.getMessage()); v = null; }
            T out = v;
            if (done != null && plugin.isEnabled()) Bukkit.getScheduler().runTask(plugin, () -> done.accept(out));
        });
    }
    interface Sql<T> { T run(Connection c) throws SQLException; }

    /** Cached row (or a fresh default) — never blocks. Use {@link #load} on join to fill the cache. */
    Row cached(UUID id, String name) { return cached(id, name, MODE); }
    Row cached(UUID id, String name, String mode) { return cache(mode).getOrDefault(id, Row.fresh(id, name, mode, start)); }
    List<UUID> challengers() { return challengers.getOrDefault(MODE, List.of()); }

    /** Loads a player's row; when they already have games under another name, the stored name becomes {@code name}
     *  (the MagicCodex 한글 닉네임) so 랭킹 · 정보 show the current nickname. */
    void load(UUID id, String name, Consumer<Row> done) { load(id, name, MODE, done); }
    /** Loads both modes (join / arena arrival). */
    void loadAll(UUID id, String name) { for (String m : MODES) load(id, name, m, null); }
    void load(UUID id, String name, String mode, Consumer<Row> done) {
        async(c -> {
            Row r = read(c, id, name, mode);
            if (r != null && r.games() > 0 && name != null && !name.isBlank() && !name.equals(r.name())) {
                try (PreparedStatement s = c.prepareStatement("UPDATE " + tRating + " SET name=? WHERE player=? AND mode=?")) {
                    s.setString(1, name); s.setString(2, id.toString()); s.setString(3, mode); s.executeUpdate();
                }
                r = new Row(r.id(), name, r.mode(), r.rating(), r.games(), r.wins(), r.losses(), r.draws(), r.goals(), r.kills(), r.deaths(), r.assists(),
                        r.damage(), r.heal(), r.snitch(), r.deflectHits(), r.mvp(), r.reachedAt(), r.bannedUntil(), r.leaves());
            }
            return r;
        }, r -> { if (r != null) cache(mode).put(id, r); if (done != null) done.accept(r); });
    }
    void forget(UUID id) { for (var c : caches.values()) c.remove(id); }

    /** Cached row of a mode, or null when this server has not loaded it (never blocks; /유저관리 요약 줄). */
    Row peek(UUID id, String mode) { return cache(mode).get(id); }

    /** By nickname (case-insensitive, latest name we stored) in one mode. */
    void find(String name, String mode, Consumer<Row> done) {
        async(c -> {
            try (PreparedStatement s = c.prepareStatement("SELECT player FROM " + tRating + " WHERE mode=? AND LOWER(name)=LOWER(?) LIMIT 1")) {
                s.setString(1, mode); s.setString(2, name);
                try (ResultSet r = s.executeQuery()) { return r.next() ? read(c, UUID.fromString(r.getString(1)), name, mode) : null; }
            }
        }, done);
    }

    private Row read(Connection c, UUID id, String name, String mode) throws SQLException {
        try (PreparedStatement s = c.prepareStatement("SELECT * FROM " + tRating + " WHERE player=? AND mode=?")) {
            s.setString(1, id.toString()); s.setString(2, mode);
            try (ResultSet r = s.executeQuery()) {
                if (!r.next()) return Row.fresh(id, name, mode, start);
                return new Row(id, r.getString("name"), mode, r.getInt("rating"), r.getInt("games"), r.getInt("wins"), r.getInt("losses"), r.getInt("draws"),
                        r.getInt("goals"), r.getInt("kills"), r.getInt("deaths"), r.getInt("assists"), r.getDouble("damage"), r.getDouble("heal"),
                        r.getInt("snitch"), r.getInt("deflect_hits"), r.getInt("mvp"), r.getLong("reached_at"), r.getLong("banned_until"), r.getInt("leaves"));
            }
        }
    }

    private void write(Connection c, Row w) throws SQLException {
        String up = settings.mariaDb() ? "REPLACE INTO " : "INSERT OR REPLACE INTO ";
        try (PreparedStatement s = c.prepareStatement(up + tRating + "(player,mode,name,rating,games,wins,losses,draws,goals,kills,deaths,assists,damage,heal,snitch,deflect_hits,mvp,reached_at,banned_until,leaves)"
                + " VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)")) {
            int i = 1;
            s.setString(i++, w.id().toString()); s.setString(i++, w.mode()); s.setString(i++, w.name()); s.setInt(i++, w.rating()); s.setInt(i++, w.games());
            s.setInt(i++, w.wins()); s.setInt(i++, w.losses()); s.setInt(i++, w.draws()); s.setInt(i++, w.goals()); s.setInt(i++, w.kills()); s.setInt(i++, w.deaths());
            s.setInt(i++, w.assists()); s.setDouble(i++, w.damage()); s.setDouble(i++, w.heal()); s.setInt(i++, w.snitch()); s.setInt(i++, w.deflectHits());
            s.setInt(i++, w.mvp()); s.setLong(i++, w.reachedAt()); s.setLong(i++, w.bannedUntil()); s.setInt(i, w.leaves());
            s.executeUpdate();
        }
        cache(w.mode()).computeIfPresent(w.id(), (k, v) -> w);
    }

    /**
     * One finished rated match: match row, per-player rows and the cumulative rows, in one transaction.
     * Idempotent per match ID (A09): when the match row already exists (a retry from the result outbox after a lost answer)
     * nothing is written again and the stored rows are returned as success. `done` gets null only when nothing was recorded.
     */
    void record(UUID match, String arena, int scoreA, int scoreB, int winner, String reason, List<Delta> deltas, Consumer<Map<UUID, Row>> done) {
        record(MODE, match, arena, scoreA, scoreB, winner, reason, new Teams("", "", -1, -1), deltas, done);
    }
    void record(String mode, UUID match, String arena, int scoreA, int scoreB, int winner, String reason, Teams teams, List<Delta> deltas, Consumer<Map<UUID, Row>> done) {
        long at = System.currentTimeMillis();
        async(ignored -> {
            var out = new java.util.HashMap<UUID, Row>();
            Connection c = db.begin();
            boolean ok = false;
            try {
                if (recorded(c, match, mode, deltas, out)) { c.commit(); ok = true; log.info("큐디치 결과 " + match + " 는 이미 기록돼 있어요 (재시도 → 저장된 값 사용)"); return out; }
                try (PreparedStatement s = c.prepareStatement("INSERT INTO " + tMatch + "(id,mode,arena,ended_at,score_a,score_b,winner,reason,name_a,name_b,house_a,house_b) VALUES(?,?,?,?,?,?,?,?,?,?,?,?)")) {
                    s.setString(1, match.toString()); s.setString(2, mode); s.setString(3, arena); s.setLong(4, at);
                    s.setInt(5, scoreA); s.setInt(6, scoreB); s.setInt(7, winner); s.setString(8, reason);
                    s.setString(9, cut(teams.nameA())); s.setString(10, cut(teams.nameB())); s.setInt(11, teams.houseA()); s.setInt(12, teams.houseB()); s.executeUpdate();
                }
                for (Delta d : deltas) {
                    Row o = read(c, d.id(), d.name(), mode);
                    int rating = Math.max(0, o.rating() + d.delta());
                    Row n = new Row(d.id(), d.name(), mode, rating, o.games() + 1, o.wins() + (d.win() ? 1 : 0), o.losses() + (d.loss() || d.leaver() ? 1 : 0),
                            o.draws() + (d.draw() ? 1 : 0), o.goals() + d.goals(), o.kills() + d.kills(), o.deaths() + d.deaths(), o.assists() + d.assists(),
                            o.damage() + d.damage(), o.heal() + d.heal(), o.snitch() + d.snitch(), o.deflectHits() + d.deflectHits(), o.mvp() + (d.mvp() ? 1 : 0),
                            d.delta() != 0 ? at : o.reachedAt(), o.bannedUntil(), o.leaves() + (d.leaver() ? 1 : 0));
                    write(c, n);
                    out.put(d.id(), n);
                    try (PreparedStatement s = c.prepareStatement("INSERT INTO " + tPlayer + "(match_id,player,team,outcome,goals,kills,deaths,mvp,delta,ended_at,damage,rating_after) VALUES(?,?,?,?,?,?,?,?,?,?,?,?)")) {
                        s.setString(1, match.toString()); s.setString(2, d.id().toString()); s.setInt(3, d.team());
                        s.setInt(4, d.win() ? 1 : d.draw() ? 2 : 0); s.setInt(5, d.goals()); s.setInt(6, d.kills()); s.setInt(7, d.deaths());
                        s.setInt(8, d.mvp() ? 1 : 0); s.setInt(9, d.delta()); s.setLong(10, at); s.setDouble(11, d.damage()); s.setInt(12, n.rating()); s.executeUpdate();
                    }
                }
                c.commit(); ok = true;
            } finally { if (!ok) db.rollback(); db.end(); }
            // already committed: a failing challenger refresh must not turn the recorded result into a failure (retried next record / admin edit)
            try { refreshChallengersNow(mode); }
            catch (SQLException | RuntimeException e) { log.warning("큐디치 챌린저 갱신 실패 (결과는 저장됨): " + e.getMessage()); }
            return out;
        }, done);
    }

    /** True when the match row exists; then `out` gets each player's stored row with the rating right after that match. */
    private boolean recorded(Connection c, UUID match, String mode, List<Delta> deltas, Map<UUID, Row> out) throws SQLException {
        try (PreparedStatement s = c.prepareStatement("SELECT id FROM " + tMatch + " WHERE id=?")) {
            s.setString(1, match.toString());
            try (ResultSet r = s.executeQuery()) { if (!r.next()) return false; }
        }
        for (Delta d : deltas) {
            Row o = read(c, d.id(), d.name(), mode);
            int after = o.rating();
            try (PreparedStatement s = c.prepareStatement("SELECT rating_after FROM " + tPlayer + " WHERE match_id=? AND player=?")) {
                s.setString(1, match.toString()); s.setString(2, d.id().toString());
                try (ResultSet r = s.executeQuery()) { if (r.next() && r.getInt(1) > 0) after = r.getInt(1); }
            }
            out.put(d.id(), new Row(o.id(), o.name(), o.mode(), after, o.games(), o.wins(), o.losses(), o.draws(), o.goals(), o.kills(), o.deaths(), o.assists(),
                    o.damage(), o.heal(), o.snitch(), o.deflectHits(), o.mvp(), o.reachedAt(), o.bannedUntil(), o.leaves()));
        }
        return true;
    }

    /** Admin edits in one mode (큐디치 = {@link #MODE}, 결투 = {@link #DUEL}). `edit` receives the current row and returns the new one. */
    void update(UUID id, String name, String mode, java.util.function.UnaryOperator<Row> edit, Consumer<Row> done) {
        async(c -> {
            Row n = edit.apply(read(c, id, name, mode)); write(c, n);
            refreshChallengersNow(mode); return n;
        }, done);
    }

    void recent(UUID id, int limit, Consumer<List<Recent>> done) {
        async(c -> {
            var out = new ArrayList<Recent>();
            try (PreparedStatement s = c.prepareStatement("SELECT p.match_id,p.ended_at,p.team,p.outcome,p.goals,p.kills,p.deaths,p.mvp,p.delta,m.score_a,m.score_b FROM "
                    + tPlayer + " p JOIN " + tMatch + " m ON m.id=p.match_id WHERE p.player=? ORDER BY p.ended_at DESC LIMIT ?")) {
                s.setString(1, id.toString()); s.setInt(2, Math.clamp(limit, 1, 50));
                try (ResultSet r = s.executeQuery()) {
                    while (r.next()) out.add(new Recent(UUID.fromString(r.getString(1)), r.getLong(2), r.getInt(3), r.getInt(10), r.getInt(11), r.getInt(4),
                            r.getInt(5), r.getInt(6), r.getInt(7), r.getInt(8) == 1, r.getInt(9)));
                }
            }
            return out;
        }, done);
    }

    void top(int limit, Consumer<List<Row>> done) {
        async(c -> {
            var out = new ArrayList<Row>();
            try (PreparedStatement s = c.prepareStatement("SELECT player,name FROM " + tRating + " WHERE mode=? AND games>0 ORDER BY rating DESC, reached_at ASC LIMIT ?")) {
                s.setString(1, MODE); s.setInt(2, Math.clamp(limit, 1, 100));
                try (ResultSet r = s.executeQuery()) { while (r.next()) out.add(read(c, UUID.fromString(r.getString(1)), r.getString(2), MODE)); }
            }
            return out;
        }, done);
    }

    private void refreshChallengersNow(String mode) throws SQLException {
        var list = new ArrayList<UUID>();
        int master = plugin instanceof KyudichiPlugin k ? k.rules().rating.tierFloors()[4] : 1700;
        try (PreparedStatement s = db.get().prepareStatement("SELECT player FROM " + tRating + " WHERE mode=? AND games>0 AND rating>=? ORDER BY rating DESC, reached_at ASC LIMIT 3")) {
            s.setString(1, mode); s.setInt(2, master);
            try (ResultSet r = s.executeQuery()) { while (r.next()) list.add(UUID.fromString(r.getString(1))); }
        }
        challengers.put(mode, List.copyOf(list));
    }

    /** 1..3 for challengers, 0 otherwise. */
    int rank(UUID id) { return rank(id, MODE); }
    int rank(UUID id, String mode) { int i = challengers.getOrDefault(mode, List.of()).indexOf(id); return i < 0 ? 0 : i + 1; }

    private static String cut(String s) { return s == null ? "" : s.length() > 32 ? s.substring(0, 32) : s; }

    // ================================================================== seasons (owner 2026-10-18: weekly, full reset)
    /** Current season of a mode, or null before the first /큐디치관리 시즌 시작. */
    Season season(String mode) { return seasons.get(mode); }

    private void loadSeasonsNow() throws SQLException {
        try (PreparedStatement s = db.get().prepareStatement("SELECT mode,season,started_at,ends_at FROM " + tSeason);
             ResultSet r = s.executeQuery()) {
            while (r.next()) seasons.put(r.getString(1), new Season(r.getString(1), r.getInt(2), r.getLong(3), r.getLong(4)));
        }
    }

    private void putSeason(Connection c, Season n) throws SQLException {
        try (PreparedStatement d = c.prepareStatement("DELETE FROM " + tSeason + " WHERE mode=?")) { d.setString(1, n.mode()); d.executeUpdate(); }
        try (PreparedStatement s = c.prepareStatement("INSERT INTO " + tSeason + "(mode,season,started_at,ends_at) VALUES(?,?,?,?)")) {
            s.setString(1, n.mode()); s.setInt(2, n.number()); s.setLong(3, n.startedAt()); s.setLong(4, n.endsAt()); s.executeUpdate();
        }
        seasons.put(n.mode(), n);
    }

    /** 레이팅·전적 완전 초기화 (rating = start, every counter 0; bans and the match history stay). */
    private void resetNow(Connection c, String mode) throws SQLException {
        try (PreparedStatement s = c.prepareStatement("UPDATE " + tRating + " SET rating=?, games=0, wins=0, losses=0, draws=0, goals=0, kills=0, deaths=0,"
                + " assists=0, damage=0, heal=0, snitch=0, deflect_hits=0, mvp=0, reached_at=0, leaves=0 WHERE mode=?")) {
            s.setInt(1, start); s.setString(2, mode); s.executeUpdate();
        }
        cache(mode).clear();
        refreshChallengersNow(mode);
    }

    /** /큐디치관리 시즌 초기화: reset now, the season itself (number, end) stays. */
    void resetSeason(String mode, Consumer<Boolean> done) { async(c -> { resetNow(c, mode); return true; }, done); }

    /** /큐디치관리 시즌 시작: a new season from now (number + 1, `days` long). Does not reset by itself. */
    void startSeason(String mode, double days, Consumer<Season> done) {
        async(c -> {
            Season old = seasons.get(mode);
            long now = System.currentTimeMillis();
            Season n = new Season(mode, old == null ? 1 : old.number() + 1, now, now + (long) (days * 86_400_000L));
            putSeason(c, n);
            return n;
        }, done);
    }

    /**
     * Every minute: a season that has ended is closed — full reset, and the next season starts where the last one ended
     * (same length), so a server that was off catches up. @return through done the seasons that rolled over.
     */
    void rollSeasons(Consumer<List<Season>> done) {
        long now = System.currentTimeMillis();
        boolean due = seasons.values().stream().anyMatch(x -> now >= x.endsAt());
        if (!due) return;
        async(c -> {
            var rolled = new ArrayList<Season>();
            for (String mode : MODES) {
                Season x = seasons.get(mode);
                if (x == null || now < x.endsAt()) continue;
                long length = Math.max(3_600_000L, x.endsAt() - x.startedAt());
                long startAt = x.endsAt(); int number = x.number() + 1;
                while (startAt + length <= now) { startAt += length; number++; }
                resetNow(c, mode);
                Season n = new Season(mode, number, startAt, startAt + length);
                putSeason(c, n);
                rolled.add(n);
            }
            return rolled;
        }, done);
    }
}
