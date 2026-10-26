package school.magiccodex.party;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;
import org.slf4j.Logger;
import school.magiccodex.database.ConnectionHolder;
import school.magiccodex.database.DatabaseSettings;
import school.magiccodex.protocol.KyudichiQueueProtocol.Game;
import school.magiccodex.protocol.KyudichiQueueProtocol.Profile;
import school.magiccodex.protocol.KyudichiQueueProtocol.Rank;
import school.magiccodex.protocol.KyudichiQueueProtocol.Row;
import school.magiccodex.protocol.KyudichiQueueProtocol.Recent;
import school.magiccodex.protocol.KyudichiQueueProtocol.PlayerRecord;

/**
 * Read-only view of the 큐디치 tables that the arena server (ChacaKyudichi) writes. Shared MariaDB only:
 * the proxy never creates or changes rows. Without MariaDB every player counts as the start rating and
 * the ranking / record screens say there is no data.
 */
final class KyudichiDb {
    static final String MODE = "kyudichi", DUEL = "duel";
    /** Wire mode (0 큐디치 · 1 1:1) → DB mode key written by the arena server. */
    static String mode(int wire) { return wire == 1 ? DUEL : MODE; }
    record Standing(int rating, long bannedUntil, int wins, int losses, int draws) {
        Standing(int rating, long bannedUntil, int wins, int losses) { this(rating, bannedUntil, wins, losses, 0); }
    }
    /** Season row of a mode (number 0 = none started yet). */
    record Season(int number, long endsAt) {}
    /** One page of the ranking. highlight = rank of the searched player (0 = none / not found). */
    record Page(int page, int pages, List<Row> rows, Row mine, int highlight, String notice) {}
    /** Profile plus, per recent game, the opponent's id (1:1) for the proxy to look up a skin; null for 큐디치. */
    record Found(Profile profile, List<UUID> opponents) {}

    private final Logger log; private final int start; private final int[] floors;
    private final ExecutorService io = Executors.newSingleThreadExecutor(r -> { var t = new Thread(r, "Chacademi-kyudichi-db"); t.setDaemon(true); return t; });
    private ConnectionHolder db; private String table, matches, players, seasons, nicknames;private boolean nicknameWarning;private RankingNameCache nameCache;

    KyudichiDb(Logger log, int start, int[] floors) { this.log = log; this.start = start; this.floors = floors; }

    void open(Path file) {
        io.execute(() -> {
            try{nameCache=new RankingNameCache(file.resolveSibling("ranking-display-names.properties"));}catch(java.io.IOException e){log.warn("Ranking display cache unavailable; file preserved");}
            try {
                var s = DatabaseSettings.load(file);
                log.info(DatabaseSettings.describe(file, s));
                if (!s.mariaDb()) {
                    // 2026-10-21: no silent 1000-for-everyone matching (bans were skipped too) — ranked sign-up is refused until this is fixed
                    log.error("==================================================================");
                    log.error("큐디치: " + file.getFileName() + " 에 MariaDB 설정이 없어요. 레이팅·신청 금지를 읽을 수 없어 큐디치·1:1 랭크 매칭 신청을 막습니다.");
                    log.error("경기장 서버 plugins/ChacaKyudichi/database.properties 와 같은 MariaDB를 넣고 Velocity를 재시작하세요.");
                    log.error("==================================================================");
                    return;
                }
                db = s.holder(file.resolveSibling("unused.db"));
                table = s.table("kyudichi_rating", "kyudichi_rating");
                nicknames=s.table("nicknames","nicknames");
                matches = s.table("kyudichi_match", "kyudichi_match");
                players = s.table("kyudichi_match_player", "kyudichi_match_player");
                seasons = s.table("kyudichi_season", "kyudichi_season");
            } catch (Exception e) { log.error("큐디치 DB 연결 실패: 랭크 매칭 신청을 막습니다 (로비·랭킹은 빈 화면)", e); db = null; }
        });
    }
    void close() { io.shutdown(); try { if (db != null) db.close(); } catch (Exception ignored) { } }

    interface Sql<T> { T run(Connection c) throws SQLException; }
    private <T> void async(Sql<T> work, T fallback, Consumer<T> done) {
        io.execute(() -> {
            T v = fallback;
            if (db != null) {
                try { v = work.run(db.get()); }
                catch (SQLException | RuntimeException e) { log.warn("큐디치 DB 읽기 실패: " + e.getMessage()); }
            }
            done.accept(v);
        });
    }
    boolean available() { return db != null; }

    /** Rating and ban for each player (missing rows = start rating, no ban). Callback runs on the DB thread. */
    void standings(List<UUID> ids, Consumer<Map<UUID, Standing>> done) { standings(ids, MODE, done); }
    void standings(List<UUID> ids, String mode, Consumer<Map<UUID, Standing>> done) {
        var fallback = fallback(ids);
        async(c -> standings(c, ids, mode), fallback, done);
    }
    private Map<UUID, Standing> fallback(List<UUID> ids) {
        var out = new HashMap<UUID, Standing>();
        for (UUID id : ids) out.put(id, new Standing(start, 0, 0, 0, 0));
        return out;
    }
    private Map<UUID, Standing> standings(Connection c, List<UUID> ids, String mode) throws SQLException {
        var out = fallback(ids);
        try (PreparedStatement s = c.prepareStatement("SELECT rating, banned_until, wins, losses, draws FROM " + table + " WHERE player=? AND mode=?")) {
            for (UUID id : ids) {
                s.setString(1, id.toString()); s.setString(2, mode);
                try (ResultSet r = s.executeQuery()) { if (r.next()) out.put(id, new Standing(r.getInt(1), r.getLong(2), r.getInt(3), r.getInt(4), r.getInt(5))); }
            }
        }
        return out;
    }

    /**
     * Ranked sign-up: the real ratings and bans, or null when they cannot be read (no shared MariaDB, or a read error) —
     * never the start-rating fallback, which would match everyone at the start rating and skip bans. Callback on the DB thread.
     */
    void rankedStandings(List<UUID> ids, String mode, Consumer<Map<UUID, Standing>> done) {
        io.execute(() -> {
            Map<UUID, Standing> v = null;
            if (db == null) log.error("큐디치 랭크 매칭 신청 거절: 레이팅 DB(MariaDB)가 연결돼 있지 않아요 (kyudichi-database.properties 확인)");
            else try { v = standings(db.get(), ids, mode); }
            catch (SQLException | RuntimeException e) { log.error("큐디치 랭크 매칭 신청 거절: 레이팅 DB 읽기 실패 " + e.getMessage()); }
            done.accept(v);
        });
    }

    /** 로비: standings of `ids` in both modes ([0] 큐디치, [1] 1:1) and both seasons. */
    record Both(List<Map<UUID, Standing>> standings, List<Season> seasons) {}
    void lobby(List<UUID> ids, Consumer<Both> done) {
        var none = new Season(0, 0);
        async(c -> {
            var st = List.of(standings(c, ids, MODE), standings(c, ids, DUEL));
            return new Both(st, List.of(season(c, MODE), season(c, DUEL)));
        }, new Both(List.of(fallback(ids), fallback(ids)), List.of(none, none)), done);
    }
    private Season season(Connection c, String mode) throws SQLException {
        try (PreparedStatement s = c.prepareStatement("SELECT season, ends_at FROM " + seasons + " WHERE mode=?")) {
            s.setString(1, mode);
            try (ResultSet r = s.executeQuery()) { return r.next() ? new Season(r.getInt(1), r.getLong(2)) : new Season(0, 0); }
        } catch (SQLException missing) { return new Season(0, 0); } // arena server not updated yet: no season table
    }
    private long seasonStart(Connection c, String mode) {
        try (PreparedStatement s = c.prepareStatement("SELECT started_at FROM " + seasons + " WHERE mode=?")) {
            s.setString(1, mode);
            try (ResultSet r = s.executeQuery()) { return r.next() ? r.getLong(1) : 0; }
        } catch (SQLException missing) { return 0; }
    }

    /** Lowest rating of a tier and of the next one (-1 = top). Tier 0 브론즈 starts at 0. */
    int floor(int tier) { int t = Math.min(tier, 5); return t == 0 ? 0 : floors[t - 1]; }
    int nextFloor(int tier) { return tier >= 5 ? -1 : floors[tier]; }

    int tier(int rating, int rank) {
        if (rating >= floors[4] && rank >= 1 && rank <= 3) return 5 + rank;
        for (int i = floors.length - 1; i >= 0; i--) if (rating >= floors[i]) return i + 1;
        return 0;
    }

    private int rank(Connection c, int rating, long reachedAt) throws SQLException { return rank(c, MODE, rating, reachedAt); }
    private int rank(Connection c, String mode, int rating, long reachedAt) throws SQLException {
        try (PreparedStatement s = c.prepareStatement("SELECT COUNT(*) FROM " + table + " WHERE mode=? AND games>0 AND (rating>? OR (rating=? AND reached_at<?))")) {
            s.setString(1, mode); s.setInt(2, rating); s.setInt(3, rating); s.setLong(4, reachedAt);
            try (ResultSet r = s.executeQuery()) { return r.next() ? r.getInt(1) + 1 : 0; }
        }
    }

    /** Top 100 (rating, then who reached it first) and the viewer's own line. */
    void ranking(UUID viewer, Consumer<Object[]> done) {
        async(c -> {
            var top = new ArrayList<Rank>(); Rank mine = null;
            try (PreparedStatement s = c.prepareStatement("SELECT player,name,rating,games,wins,losses FROM " + table + " WHERE mode=? AND games>0 ORDER BY rating DESC, reached_at ASC LIMIT 100")) {
                s.setString(1, MODE);
                try (ResultSet r = s.executeQuery()) {
                    int i = 1;
                    while (r.next()) {
                        int rating = r.getInt(3);
                        var row = new Rank(i, UUID.fromString(r.getString(1)), displayName(c,UUID.fromString(r.getString(1)),r.getString(2)), "", rating, tier(rating, i), r.getInt(4), r.getInt(5), r.getInt(6));
                        top.add(row); if (row.id().equals(viewer)) mine = row; i++;
                    }
                }
            }
            if (mine == null) {
                try (PreparedStatement s = c.prepareStatement("SELECT name,rating,games,wins,losses,reached_at FROM " + table + " WHERE player=? AND mode=? AND games>0")) {
                    s.setString(1, viewer.toString()); s.setString(2, MODE);
                    try (ResultSet r = s.executeQuery()) {
                        if (r.next()) { int rating = r.getInt(2); int rk = rank(c, rating, r.getLong(6)); mine = new Rank(rk, viewer, displayName(c,viewer,r.getString(1)), "", rating, tier(rating, rk), r.getInt(3), r.getInt(4), r.getInt(5)); }
                    }
                }
            }
            return new Object[]{List.copyOf(top), mine};
        }, new Object[]{List.<Rank>of(), null}, done);
    }

    /** Full record by player id, or by the last stored nickname when id is null. */
    void record(UUID id, String nick, Consumer<PlayerRecord> done) {
        async(c -> {
            UUID resolved=id!=null?id:findName(c,nick,false);
            String sql = "SELECT * FROM " + table + " WHERE mode=? AND " + (resolved != null ? "player=?" : "LOWER(name)=LOWER(?)") + " LIMIT 1";
            try (PreparedStatement s = c.prepareStatement(sql)) {
                s.setString(1, MODE); s.setString(2, resolved != null ? resolved.toString() : nick);
                try (ResultSet r = s.executeQuery()) {
                    if (!r.next()) return null;
                    UUID who = UUID.fromString(r.getString("player"));
                    int rating = r.getInt("rating"); int rk = r.getInt("games") > 0 ? rank(c, rating, r.getLong("reached_at")) : 0;
                    return new PlayerRecord(who, displayName(c,who,r.getString("name")), "", rating, tier(rating, rk), rk, r.getInt("games"), r.getInt("wins"), r.getInt("losses"), r.getInt("draws"),
                            r.getInt("goals"), r.getInt("kills"), r.getInt("deaths"), r.getInt("assists"), r.getInt("snitch"), r.getInt("deflect_hits"), r.getInt("mvp"),
                            (float) r.getDouble("damage"), (float) r.getDouble("heal"), recent(c, who));
                }
            }
        }, null, done);
    }

    private List<Recent> recent(Connection c, UUID id) throws SQLException {
        var out = new ArrayList<Recent>();
        try (PreparedStatement s = c.prepareStatement("SELECT p.ended_at,p.outcome,p.team,m.score_a,m.score_b,p.goals,p.kills,p.deaths,p.mvp,p.delta FROM "
                + players + " p JOIN " + matches + " m ON m.id=p.match_id WHERE p.player=? ORDER BY p.ended_at DESC LIMIT 10")) {
            s.setString(1, id.toString());
            try (ResultSet r = s.executeQuery()) {
                while (r.next()) out.add(new Recent(r.getLong(1), Math.clamp(r.getInt(2), 0, 2), Math.clamp(r.getInt(3), 0, 1), r.getInt(4), r.getInt(5),
                        r.getInt(6), r.getInt(7), r.getInt(8), r.getInt(9) == 1, r.getInt(10)));
            }
        }
        return out;
    }

    // =============================================================== 결투장 로비 (2026-10-18)
    /**
     * Ranking page (RANK_PAGE rows, 1-based). With `search`: the player whose last nickname matches (exact, else the first
     * that starts with it) — the page jumps to them and their rank is the highlight.
     */
    void ranks(UUID viewer, String mode, int page, String search, int perPage, Consumer<Page> done) {
        async(c -> {
            int total;
            try (PreparedStatement s = c.prepareStatement("SELECT COUNT(*) FROM " + table + " WHERE mode=? AND games>0")) {
                s.setString(1, mode);
                try (ResultSet r = s.executeQuery()) { total = r.next() ? r.getInt(1) : 0; }
            }
            int pages = Math.max(1, (total + perPage - 1) / perPage);
            int want = Math.clamp(page, 1, pages), highlight = 0; String notice = "";
            if (search != null && !search.isBlank()) {
                Row hit = find(c, mode, search.strip());
                if (hit == null) notice = "'" + search.strip() + "' 님의 이번 시즌 기록이 없어요.";
                else { highlight = hit.rank(); want = Math.clamp((hit.rank() - 1) / perPage + 1, 1, pages); }
            }
            var rows = new ArrayList<Row>(); Row mine = null;
            try (PreparedStatement s = c.prepareStatement("SELECT player,name,rating,wins,losses,draws FROM " + table
                    + " WHERE mode=? AND games>0 ORDER BY rating DESC, reached_at ASC LIMIT ? OFFSET ?")) {
                s.setString(1, mode); s.setInt(2, perPage); s.setInt(3, (want - 1) * perPage);
                try (ResultSet r = s.executeQuery()) {
                    int i = (want - 1) * perPage + 1;
                    while (r.next()) {
                        int rating = r.getInt(3);
                        var row = new Row(i, UUID.fromString(r.getString(1)), displayName(c,UUID.fromString(r.getString(1)),r.getString(2)), "", rating, tier(rating, i), r.getInt(4), r.getInt(5), r.getInt(6));
                        rows.add(row); if (row.id().equals(viewer)) mine = row; i++;
                    }
                }
            }
            if (mine == null) mine = row(c, mode, "player=?", viewer.toString());
            return new Page(want, pages, List.copyOf(rows), mine, highlight, notice);
        }, new Page(1, 1, List.of(), null, 0, db == null ? "랭킹을 불러올 수 없어요." : ""), done);
    }
    private Row find(Connection c, String mode, String nick) throws SQLException {
        UUID owner=findName(c,nick,false);if(owner==null)owner=findName(c,nick,true);
        Row r = owner==null?null:row(c,mode,"player=?",owner.toString());if(r==null)r = row(c, mode, "LOWER(name)=LOWER(?)", nick);
        if (r == null) r = row(c, mode, "LOWER(name) LIKE LOWER(?)", nick.replace("%", "").replace("_", "") + "%");
        return r;
    }
    /** One ranked player (games > 0) by a WHERE condition with one parameter, with their rank. */
    private Row row(Connection c, String mode, String where, String arg) throws SQLException {
        try (PreparedStatement s = c.prepareStatement("SELECT player,name,rating,wins,losses,draws,reached_at FROM " + table
                + " WHERE mode=? AND games>0 AND " + where + " ORDER BY rating DESC, reached_at ASC LIMIT 1")) {
            s.setString(1, mode); s.setString(2, arg);
            try (ResultSet r = s.executeQuery()) {
                if (!r.next()) return null;
                int rating = r.getInt(3), rk = rank(c, mode, rating, r.getLong(7));
                return new Row(rk, UUID.fromString(r.getString(1)), displayName(c,UUID.fromString(r.getString(1)),r.getString(2)), "", rating, tier(rating, rk), r.getInt(4), r.getInt(5), r.getInt(6));
            }
        }
    }

    /** 전적: by id, or by the last stored nickname when id is null. Recent games are this season's (the season reset clears stats). */
    void profile(UUID id, String nick, String mode, Consumer<Found> done) {
        async(c -> {
            UUID resolved=id!=null?id:findName(c,nick,false);
            String sql = "SELECT * FROM " + table + " WHERE mode=? AND " + (resolved != null ? "player=?" : "LOWER(name)=LOWER(?)") + " LIMIT 1";
            try (PreparedStatement s = c.prepareStatement(sql)) {
                s.setString(1, mode); s.setString(2, resolved != null ? resolved.toString() : nick);
                try (ResultSet r = s.executeQuery()) {
                    if (!r.next()) return null;
                    UUID who = UUID.fromString(r.getString("player"));
                    int rating = r.getInt("rating"); int rk = r.getInt("games") > 0 ? rank(c, mode, rating, r.getLong("reached_at")) : 0;
                    var opponents = new ArrayList<UUID>();
                    var games = games(c, who, mode, opponents);
                    var p = new Profile(who, displayName(c,who,r.getString("name")), "", rating, tier(rating, rk), rk, r.getInt("games"), r.getInt("wins"), r.getInt("losses"), r.getInt("draws"),
                            r.getInt("goals"), r.getInt("kills"), r.getInt("deaths"), r.getInt("snitch"), r.getInt("deflect_hits"),
                            (float) r.getDouble("damage"), (float) r.getDouble("heal"), games);
                    return new Found(p, opponents);
                }
            }
        }, null, done);
    }
    private List<Game> games(Connection c, UUID id, String mode, List<UUID> opponents) throws SQLException {
        var out = new ArrayList<Game>();
        long since = seasonStart(c, mode);
        try (PreparedStatement s = c.prepareStatement("SELECT p.ended_at,p.outcome,p.team,m.score_a,m.score_b,p.goals,p.kills,p.deaths,p.delta,p.rating_after,p.damage,"
                + "m.name_a,m.name_b,m.house_a,m.house_b,p.match_id FROM " + players + " p JOIN " + matches + " m ON m.id=p.match_id"
                + " WHERE p.player=? AND m.mode=? AND p.ended_at>=? ORDER BY p.ended_at DESC LIMIT 10")) {
            s.setString(1, id.toString()); s.setString(2, mode); s.setLong(3, since);
            try (ResultSet r = s.executeQuery()) {
                while (r.next()) {
                    int team = Math.clamp(r.getInt(3), 0, 1);
                    int mine = team == 0 ? r.getInt(4) : r.getInt(5), theirs = team == 0 ? r.getInt(5) : r.getInt(4);
                    String opp = team == 0 ? r.getString(13) : r.getString(12);
                    int house = team == 0 ? r.getInt(15) : r.getInt(14);
                    UUID other = DUEL.equals(mode) ? opponent(c, r.getString(16), id) : null;
                    opponents.add(other);if(other!=null)opp=displayName(c,other,opp==null?"":opp);
                    out.add(new Game(r.getLong(1), Math.clamp(r.getInt(2), 0, 2), r.getInt(9), r.getInt(10), Math.clamp(r.getInt(6), 0, 255), Math.clamp(r.getInt(7), 0, 255),
                            Math.clamp(r.getInt(8), 0, 255), (float) r.getDouble(11), mine, theirs, opp == null ? "" : opp, "", Math.clamp(house, -1, 3)));
                }
            }
        }
        return out;
    }
    void rememberName(UUID id,String name){io.execute(()->{if(nameCache!=null)try{nameCache.remember(id,name);}catch(java.io.IOException|IllegalArgumentException e){log.warn("Could not preserve trusted ranking display name; previous cache retained");}});}
    private String displayName(Connection c,UUID id,String ignoredAccountSnapshot){String pending="닉네임 확인 중";String previous=nameCache==null?pending:nameCache.name(id,pending);try{return KyudichiNames.display(c,nicknames,id,previous);}catch(SQLException e){warnNames();return previous;}}
    private UUID findName(Connection c,String text,boolean prefix){if(text==null||text.isBlank())return null;try{UUID found=KyudichiNames.find(c,nicknames,text,prefix);if(found!=null)return found;}catch(SQLException e){warnNames();}return nameCache==null?null:nameCache.find(text,prefix);}
    private void warnNames(){if(!nicknameWarning){nicknameWarning=true;log.warn("Ranking nickname directory unavailable; verify shared nickname database configuration. Existing account names retained.");}}
    private UUID opponent(Connection c, String match, UUID me) throws SQLException {
        try (PreparedStatement s = c.prepareStatement("SELECT player FROM " + players + " WHERE match_id=? AND player<>? LIMIT 1")) {
            s.setString(1, match); s.setString(2, me.toString());
            try (ResultSet r = s.executeQuery()) { return r.next() ? UUID.fromString(r.getString(1)) : null; }
        }
    }
}
