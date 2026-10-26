package school.magiccodex.paper;

import java.sql.Connection;
import java.sql.ResultSet;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;
import school.magiccodex.database.DatabaseSettings;

/**
 * 지도 이동 공유 저장소 (2026-10-23, owner: 해금 목록·개인 지점은 UUID 기준 공유 저장소, 서버 추가·제외로 초기화되거나
 * 로컬 SQLite 로 조용히 바뀌면 안 됨).
 *
 * Reads player-state-database.properties (else database.properties) like the other player-state features, but unlike
 * them it REFUSES to run on SQLite: {@link #usable()} stays false and every call answers "unavailable" (logged SEVERE
 * at start). Only travel.yml storage.allow-local-test-db: true (one test server) allows the local travel.db, loudly.
 *
 * Tables (MariaDB, utf8mb4, created if missing — no migration of existing data needed, all new):
 *   codex_travel_unlock   (uuid, point) PK, at, server               — public points a player has found
 *   codex_travel_personal uuid PK, server, world, world_uid, x y z yaw pitch, at, source  — the one personal point
 *   codex_travel_ticket   uuid PK, ticket, point, from/to server, world, world_uid, x y z yaw pitch, created_at,
 *                         expires_at, state (PENDING → ARRIVING → DONE | FAILED), reason, finished_at — one live move
 *   codex_travel_log      id, uuid, ticket, point, from/to server, result, reason, at       — every finished move
 *   codex_travel_server   server PK, heartbeat, online, max_players, worlds ("name=uid;..."), config_hash
 *   codex_travel_presence server PK, at, players (줄마다 uuid·세계·닉네임·기숙사 — 텔포권 목록, 2026-10-26)
 *   codex_travel_death    uuid PK, server, world, world_uid, x y z yaw pitch, at — 리턴권: 마지막 야생 사망 자리 (2026-10-26)
 * Rows are never deleted because a server or a point left travel.yml.
 */
final class TravelStore implements AutoCloseable {
    enum Claim { CLAIMED, ALREADY, ERROR }
    /** Destination read: ticket = to move now; expired = a ticket this call just failed because it timed out (refund it). */
    record Arrival(Ticket ticket, Ticket expired) { static final Arrival NONE = new Arrival(null, null); }
    record Ticket(long ticket, String point, String from, String to, String world, String worldUid, double x, double y, double z,
                  float yaw, float pitch, long created, long expires, String state, String reason) {}

    static final String PENDING = "PENDING", ARRIVING = "ARRIVING", DONE = "DONE", FAILED = "FAILED";

    private final java.util.logging.Logger log;
    /** Runs a callback on the server thread (tests: directly). */
    private final java.util.function.Consumer<Runnable> main;
    private final java.util.function.BooleanSupplier enabled;
    private final DatabaseSettings settings;
    private final boolean usable;
    private final java.nio.file.Path sqlite;
    private final ExecutorService io = Executors.newSingleThreadExecutor(r -> { var t = new Thread(r, "MagicCodex-travel"); t.setDaemon(true); return t; });
    private Connection connection;
    private volatile boolean closed, ready;

    TravelStore(MagicCodexBridge plugin, boolean allowLocalTestDb) {
        this(plugin.getLogger(), plugin.getDataFolder().toPath(), allowLocalTestDb,
                r -> org.bukkit.Bukkit.getScheduler().runTask(plugin, r), plugin::isEnabled);
    }
    TravelStore(java.util.logging.Logger log, java.nio.file.Path folder, boolean allowLocalTestDb, java.util.function.Consumer<Runnable> main,
                java.util.function.BooleanSupplier enabled) {
        this.log = log; this.main = main; this.enabled = enabled;
        DatabaseSettings s;
        String file;
        try {
            var dedicated = folder.resolve("player-state-database.properties");
            var path = java.nio.file.Files.exists(dedicated) ? dedicated : folder.resolve("database.properties");
            file = path.getFileName().toString();
            s = DatabaseSettings.load(path);
        } catch (Exception e) { s = new DatabaseSettings(false, "", "", ""); file = "(읽기 실패: " + e.getMessage() + ")"; }
        settings = s;
        sqlite = folder.resolve("travel.db");
        if (settings.mariaDb()) usable = true;
        else if (allowLocalTestDb) {
            usable = true;
            log.severe("지도 이동: 테스트용 로컬 travel.db 사용 중 (travel.yml storage.allow-local-test-db: true) — 다른 서버와 공유되지 않습니다. 운영 서버에서는 끄세요.");
        } else {
            usable = false;
            log.severe("지도 이동: 공유 MariaDB 연결이 없어 꺼둡니다 (" + file + " 가 MariaDB 가 아님). 해금·개인 지점을 서버 로컬에 저장하지 않습니다.");
        }
        if (usable) io.execute(this::prepare);
    }

    boolean usable() { return usable; }
    boolean ready() { return usable && ready; }
    boolean shared() { return settings.mariaDb(); }

    private void prepare() {
        String tail = settings.mariaDb() ? " ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE utf8mb4_unicode_ci" : "";
        String auto = settings.mariaDb() ? "BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY" : "INTEGER PRIMARY KEY AUTOINCREMENT";
        try (var st = db().createStatement()) {
            st.execute("CREATE TABLE IF NOT EXISTS codex_travel_unlock(uuid VARCHAR(36) NOT NULL,point VARCHAR(48) NOT NULL,at BIGINT NOT NULL,server VARCHAR(48) NOT NULL,PRIMARY KEY(uuid,point))" + tail);
            st.execute("CREATE TABLE IF NOT EXISTS codex_travel_personal(uuid VARCHAR(36) NOT NULL PRIMARY KEY,server VARCHAR(48) NOT NULL,world VARCHAR(64) NOT NULL,world_uid VARCHAR(36) NOT NULL,"
                    + "x DOUBLE NOT NULL,y DOUBLE NOT NULL,z DOUBLE NOT NULL,yaw FLOAT NOT NULL,pitch FLOAT NOT NULL,at BIGINT NOT NULL,source VARCHAR(64) NOT NULL)" + tail);
            st.execute("CREATE TABLE IF NOT EXISTS codex_travel_ticket(uuid VARCHAR(36) NOT NULL PRIMARY KEY,ticket BIGINT NOT NULL,point VARCHAR(48) NOT NULL,from_server VARCHAR(48) NOT NULL,"
                    + "to_server VARCHAR(48) NOT NULL,world VARCHAR(64) NOT NULL,world_uid VARCHAR(36) NOT NULL,x DOUBLE NOT NULL,y DOUBLE NOT NULL,z DOUBLE NOT NULL,yaw FLOAT NOT NULL,pitch FLOAT NOT NULL,"
                    + "created_at BIGINT NOT NULL,expires_at BIGINT NOT NULL,state VARCHAR(12) NOT NULL,reason VARCHAR(120) NOT NULL,finished_at BIGINT NOT NULL)" + tail);
            st.execute("CREATE TABLE IF NOT EXISTS codex_travel_log(id " + auto + ",uuid VARCHAR(36) NOT NULL,ticket BIGINT NOT NULL,point VARCHAR(48) NOT NULL,from_server VARCHAR(48) NOT NULL,"
                    + "to_server VARCHAR(48) NOT NULL,result VARCHAR(12) NOT NULL,reason VARCHAR(120) NOT NULL,at BIGINT NOT NULL)" + tail);
            st.execute("CREATE TABLE IF NOT EXISTS codex_travel_server(server VARCHAR(48) NOT NULL PRIMARY KEY,heartbeat BIGINT NOT NULL,online INT NOT NULL,max_players INT NOT NULL,"
                    + "worlds VARCHAR(2048) NOT NULL,config_hash VARCHAR(16) NOT NULL)" + tail);
            // 리턴권·텔포권 (2026-10-26): 새 표 두 개 (기존 표는 그대로)
            st.execute("CREATE TABLE IF NOT EXISTS codex_travel_presence(server VARCHAR(48) NOT NULL PRIMARY KEY,at BIGINT NOT NULL,players MEDIUMTEXT NOT NULL)" + tail);
            st.execute("CREATE TABLE IF NOT EXISTS codex_travel_death(uuid VARCHAR(36) NOT NULL PRIMARY KEY,server VARCHAR(48) NOT NULL,world VARCHAR(64) NOT NULL,world_uid VARCHAR(36) NOT NULL,"
                    + "x DOUBLE NOT NULL,y DOUBLE NOT NULL,z DOUBLE NOT NULL,yaw FLOAT NOT NULL,pitch FLOAT NOT NULL,at BIGINT NOT NULL)" + tail);
            ready = true;
        } catch (Exception e) { log.severe("지도 이동 테이블 준비 실패: " + e.getMessage()); }
    }

    private long lastUse;
    /** One connection; a validity round trip only after 5 s idle (not before every query: /지도 must answer at once). */
    private Connection db() throws Exception {
        long now = System.currentTimeMillis();
        boolean check = now - lastUse > 5000;
        lastUse = now;
        if (connection == null || connection.isClosed() || (check && !connection.isValid(2))) {
            if (connection != null) try { connection.close(); } catch (Exception ignored) {}
            connection = settings.connect(sqlite);
            settings.configure(connection);
        }
        return connection;
    }
    private String ignore() { return settings.mariaDb() ? "INSERT IGNORE" : "INSERT OR IGNORE"; }
    private void later(Runnable r) { if (!closed && enabled.getAsBoolean()) main.accept(r); }
    private interface Job<T> { T run(Connection c) throws Exception; }
    /** Runs on the IO thread; done gets the value (or `failed` on any error) on the main thread. */
    private <T> void submit(String what, Job<T> job, T failed, Consumer<T> done) {
        if (!usable || closed) { if (done != null) later(() -> done.accept(failed)); return; }
        try {
            io.execute(() -> {
                T value;
                try { value = job.run(db()); }
                catch (Exception e) { log.warning("지도 이동 DB 오류 (" + what + "): " + e.getMessage()); value = failed; }
                T v = value;
                if (done != null) later(() -> done.accept(v));
            });
        } catch (RejectedExecutionException e) { if (done != null) later(() -> done.accept(failed)); }   // never inside the caller's transaction
    }

    // ------------------------------------------------------------------ unlocks

    /** Point ids the player has found. null = read failure. */
    void unlocks(UUID player, Consumer<Set<String>> done) {
        submit("해금 읽기", c -> {
            var out = new HashSet<String>();
            try (var q = c.prepareStatement("SELECT point FROM codex_travel_unlock WHERE uuid=?")) {
                q.setString(1, player.toString());
                try (var r = q.executeQuery()) { while (r.next()) out.add(r.getString(1)); }
            }
            return out;
        }, null, done);
    }
    /** CLAIMED only when this call created the row (first discovery, exactly once across servers). */
    void unlock(UUID player, String point, String server, Consumer<Claim> done) {
        submit("해금 기록", c -> {
            try (var q = c.prepareStatement(ignore() + " INTO codex_travel_unlock(uuid,point,at,server) VALUES(?,?,?,?)")) {
                q.setString(1, player.toString()); q.setString(2, point); q.setLong(3, System.currentTimeMillis()); q.setString(4, server);
                return q.executeUpdate() == 1 ? Claim.CLAIMED : Claim.ALREADY;
            }
        }, Claim.ERROR, done);
    }
    void lock(UUID player, String point, Consumer<Boolean> done) {
        submit("해금 지우기", c -> {
            try (var q = c.prepareStatement("DELETE FROM codex_travel_unlock WHERE uuid=? AND point=?")) {
                q.setString(1, player.toString()); q.setString(2, point); return q.executeUpdate() > 0;
            }
        }, false, done);
    }
    /** point → unlock time, for the admin view. */
    void unlockTimes(UUID player, Consumer<Map<String, Long>> done) {
        submit("해금 목록", c -> {
            var out = new TreeMap<String, Long>();
            try (var q = c.prepareStatement("SELECT point,at FROM codex_travel_unlock WHERE uuid=?")) {
                q.setString(1, player.toString());
                try (var r = q.executeQuery()) { while (r.next()) out.put(r.getString(1), r.getLong(2)); }
            }
            return out;
        }, null, done);
    }

    // ------------------------------------------------------------------ personal point

    /** Optional.empty = none saved; null = read failure. */
    void personal(UUID player, Consumer<Optional<TravelModel.Personal>> done) {
        submit("개인 지점 읽기", c -> {
            try (var q = c.prepareStatement("SELECT server,world,world_uid,x,y,z,yaw,pitch,at,source FROM codex_travel_personal WHERE uuid=?")) {
                q.setString(1, player.toString());
                try (var r = q.executeQuery()) {
                    if (!r.next()) return Optional.<TravelModel.Personal>empty();
                    return Optional.of(new TravelModel.Personal(r.getString(1), r.getString(2), r.getString(3), r.getDouble(4), r.getDouble(5), r.getDouble(6),
                            r.getFloat(7), r.getFloat(8), r.getLong(9), r.getString(10)));
                }
            }
        }, null, done);
    }
    /** Replaces the player's one personal point (every server shares it). done(true) = saved. */
    void savePersonal(UUID player, TravelModel.Personal p, Consumer<Boolean> done) {
        submit("개인 지점 저장", c -> {
            String sql = settings.mariaDb()
                    ? "INSERT INTO codex_travel_personal(uuid,server,world,world_uid,x,y,z,yaw,pitch,at,source) VALUES(?,?,?,?,?,?,?,?,?,?,?) ON DUPLICATE KEY UPDATE "
                      + "server=VALUES(server),world=VALUES(world),world_uid=VALUES(world_uid),x=VALUES(x),y=VALUES(y),z=VALUES(z),yaw=VALUES(yaw),pitch=VALUES(pitch),at=VALUES(at),source=VALUES(source)"
                    : "INSERT OR REPLACE INTO codex_travel_personal(uuid,server,world,world_uid,x,y,z,yaw,pitch,at,source) VALUES(?,?,?,?,?,?,?,?,?,?,?)";
            try (var q = c.prepareStatement(sql)) {
                q.setString(1, player.toString()); q.setString(2, p.server()); q.setString(3, p.world()); q.setString(4, p.worldUid());
                q.setDouble(5, p.x()); q.setDouble(6, p.y()); q.setDouble(7, p.z()); q.setFloat(8, p.yaw()); q.setFloat(9, p.pitch());
                q.setLong(10, p.at()); q.setString(11, p.source());
                q.executeUpdate(); return true;
            }
        }, false, done);
    }
    void deletePersonal(UUID player, Consumer<Boolean> done) {
        submit("개인 지점 삭제", c -> {
            try (var q = c.prepareStatement("DELETE FROM codex_travel_personal WHERE uuid=?")) { q.setString(1, player.toString()); return q.executeUpdate() > 0; }
        }, false, done);
    }

    // ------------------------------------------------------------------ tickets (one live move per player)

    /**
     * Starts a move: writes the ticket unless the player already has a live one (PENDING / ARRIVING, not expired).
     * done(true) = this ticket is now the player's live move.
     */
    void openTicket(UUID player, Ticket t, Consumer<Boolean> done) {
        submit("이동 표 쓰기", c -> {
            long now = System.currentTimeMillis();
            // an expired live ticket gets its FAILED log row before it is replaced
            try (var q = c.prepareStatement("SELECT ticket,point,from_server,to_server,world,world_uid,x,y,z,yaw,pitch,created_at,expires_at,state,reason FROM codex_travel_ticket WHERE uuid=?")) {
                q.setString(1, player.toString());
                try (var r = q.executeQuery()) {
                    if (r.next()) { var old = ticket(r); if ((PENDING.equals(old.state()) || ARRIVING.equals(old.state())) && old.expires() < now)
                        finish(c, player, old.ticket(), old.state(), FAILED, "이동 시간이 지났어요 (도착하지 못함)", old); }
                }
            }
            String set = "ticket=?,point=?,from_server=?,to_server=?,world=?,world_uid=?,x=?,y=?,z=?,yaw=?,pitch=?,created_at=?,expires_at=?,state=?,reason=?,finished_at=0";
            try (var q = c.prepareStatement("UPDATE codex_travel_ticket SET " + set + " WHERE uuid=? AND (state IN ('DONE','FAILED') OR expires_at<?)")) {
                int i = bind(q, t); q.setString(i++, player.toString()); q.setLong(i, now);
                if (q.executeUpdate() == 1) return true;
            }
            try (var q = c.prepareStatement(ignore() + " INTO codex_travel_ticket(ticket,point,from_server,to_server,world,world_uid,x,y,z,yaw,pitch,created_at,expires_at,state,reason,finished_at,uuid) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,0,?)")) {
                int i = bind(q, t); q.setString(i, player.toString());
                return q.executeUpdate() == 1;
            }
        }, false, done);
    }
    private static int bind(java.sql.PreparedStatement q, Ticket t) throws java.sql.SQLException {
        int i = 1;
        q.setLong(i++, t.ticket()); q.setString(i++, t.point()); q.setString(i++, t.from()); q.setString(i++, t.to()); q.setString(i++, t.world());
        q.setString(i++, t.worldUid()); q.setDouble(i++, t.x()); q.setDouble(i++, t.y()); q.setDouble(i++, t.z()); q.setFloat(i++, t.yaw()); q.setFloat(i++, t.pitch());
        q.setLong(i++, t.created()); q.setLong(i++, t.expires()); q.setString(i++, t.state()); q.setString(i++, t.reason());
        return i;
    }

    /** Destination: takes this player's PENDING ticket for `server` (expired ones are failed instead). Empty = nothing to do. */
    void claimArrival(UUID player, String server, Consumer<Arrival> done) {
        submit("도착 표 읽기", c -> {
            long now = System.currentTimeMillis();
            Ticket t;
            try (var q = c.prepareStatement("SELECT ticket,point,from_server,to_server,world,world_uid,x,y,z,yaw,pitch,created_at,expires_at,state,reason FROM codex_travel_ticket WHERE uuid=?")) {
                q.setString(1, player.toString());
                try (var r = q.executeQuery()) { if (!r.next()) return Arrival.NONE; t = ticket(r); }
            }
            if (!PENDING.equals(t.state())) return Arrival.NONE;
            if (t.expires() < now) {   // logged out on the way / landed elsewhere: it never arrived
                return finish(c, player, t.ticket(), PENDING, FAILED, "이동 시간이 지났어요 (도착하지 못함)", t) ? new Arrival(null, t) : Arrival.NONE;
            }
            if (!server.equals(t.to())) return Arrival.NONE;
            try (var q = c.prepareStatement("UPDATE codex_travel_ticket SET state='ARRIVING' WHERE uuid=? AND ticket=? AND state='PENDING'")) {
                q.setString(1, player.toString()); q.setLong(2, t.ticket());
                return q.executeUpdate() == 1 ? new Arrival(t, null) : Arrival.NONE;
            }
        }, Arrival.NONE, done);
    }

    /** Destination gave up waiting for the character data: fail this server's pending ticket so it is never left open. */
    void failPending(UUID player, String server, String reason, Consumer<Boolean> done) {
        submit("도착 포기 기록", c -> {
            Ticket t;
            try (var q = c.prepareStatement("SELECT ticket,point,from_server,to_server,world,world_uid,x,y,z,yaw,pitch,created_at,expires_at,state,reason FROM codex_travel_ticket WHERE uuid=? AND state='PENDING' AND to_server=?")) {
                q.setString(1, player.toString()); q.setString(2, server);
                try (var r = q.executeQuery()) { if (!r.next()) return false; t = ticket(r); }
            }
            return finish(c, player, t.ticket(), PENDING, FAILED, reason, t);
        }, false, done);
    }
    private static Ticket ticket(ResultSet r) throws java.sql.SQLException {
        return new Ticket(r.getLong(1), r.getString(2), r.getString(3), r.getString(4), r.getString(5), r.getString(6), r.getDouble(7), r.getDouble(8), r.getDouble(9),
                r.getFloat(10), r.getFloat(11), r.getLong(12), r.getLong(13), r.getString(14), r.getString(15));
    }

    /**
     * Moves the ticket from `from` to DONE / FAILED and logs it — only if it is still that ticket in that state, so a
     * late failure can never overwrite an arrival (or the other way round). done(true) = this call finished it.
     */
    void finish(UUID player, Ticket t, String from, String result, String reason, Consumer<Boolean> done) {
        submit("이동 결과 기록", c -> finish(c, player, t.ticket(), from, result, reason, t), false, done);
    }
    /** Same, by ticket id only (origin failure path: the proxy answered or nobody did). */
    void fail(UUID player, long ticket, String reason, Consumer<Boolean> done) {
        submit("이동 실패 기록", c -> {
            Ticket t;
            try (var q = c.prepareStatement("SELECT ticket,point,from_server,to_server,world,world_uid,x,y,z,yaw,pitch,created_at,expires_at,state,reason FROM codex_travel_ticket WHERE uuid=? AND ticket=?")) {
                q.setString(1, player.toString()); q.setLong(2, ticket);
                try (var r = q.executeQuery()) { if (!r.next()) return false; t = ticket(r); }
            }
            return finish(c, player, ticket, PENDING, FAILED, reason, t);
        }, false, done);
    }
    private boolean finish(Connection c, UUID player, long ticket, String from, String result, String reason, Ticket t) throws Exception {
        long now = System.currentTimeMillis();
        String why = reason == null ? "" : reason.length() > 120 ? reason.substring(0, 120) : reason;
        try (var q = c.prepareStatement("UPDATE codex_travel_ticket SET state=?,reason=?,finished_at=? WHERE uuid=? AND ticket=? AND state=?")) {
            q.setString(1, result); q.setString(2, why); q.setLong(3, now); q.setString(4, player.toString()); q.setLong(5, ticket); q.setString(6, from);
            if (q.executeUpdate() != 1) return false;
        }
        try (var q = c.prepareStatement("INSERT INTO codex_travel_log(uuid,ticket,point,from_server,to_server,result,reason,at) VALUES(?,?,?,?,?,?,?,?)")) {
            q.setString(1, player.toString()); q.setLong(2, ticket); q.setString(3, t.point()); q.setString(4, t.from()); q.setString(5, t.to());
            q.setString(6, result); q.setString(7, why); q.setLong(8, now); q.executeUpdate();
        }
        return true;
    }

    /** Last moves for the admin view (newest first): "시간 · 결과 · 지점 · from→to · 이유". */
    void history(UUID player, int limit, Consumer<List<String>> done) {
        submit("이동 기록", c -> {
            var out = new ArrayList<String>();
            var fmt = java.time.format.DateTimeFormatter.ofPattern("MM-dd HH:mm:ss").withZone(java.time.ZoneId.systemDefault());
            try (var q = c.prepareStatement("SELECT at,result,point,from_server,to_server,reason FROM codex_travel_log WHERE uuid=? ORDER BY at DESC LIMIT " + Math.clamp(limit, 1, 50))) {
                q.setString(1, player.toString());
                try (var r = q.executeQuery()) {
                    while (r.next()) out.add(fmt.format(java.time.Instant.ofEpochMilli(r.getLong(1))) + " · " + (DONE.equals(r.getString(2)) ? "성공" : "실패") + " · "
                            + r.getString(3) + " · " + r.getString(4) + "→" + r.getString(5) + (r.getString(6).isEmpty() ? "" : " · " + r.getString(6)));
                }
            }
            return out;
        }, null, done);
    }

    // ------------------------------------------------------------------ server heartbeat

    void heartbeat(String server, int online, int max, Map<String, String> worlds, Map<String, double[]> spawns, String configHash) {
        String packed = TravelModel.packWorlds(worlds, spawns);
        submit("서버 상태 쓰기", c -> {
            String sql = settings.mariaDb()
                    ? "INSERT INTO codex_travel_server(server,heartbeat,online,max_players,worlds,config_hash) VALUES(?,?,?,?,?,?) ON DUPLICATE KEY UPDATE heartbeat=VALUES(heartbeat),online=VALUES(online),max_players=VALUES(max_players),worlds=VALUES(worlds),config_hash=VALUES(config_hash)"
                    : "INSERT OR REPLACE INTO codex_travel_server(server,heartbeat,online,max_players,worlds,config_hash) VALUES(?,?,?,?,?,?)";
            try (var q = c.prepareStatement(sql)) {
                q.setString(1, server); q.setLong(2, System.currentTimeMillis()); q.setInt(3, online); q.setInt(4, max); q.setString(5, packed); q.setString(6, configHash);
                q.executeUpdate();
            }
            return true;
        }, false, null);
    }
    /** Marks this server as gone (plugin disable) so others stop offering it at once. Runs synchronously (shutdown). */
    void offline(String server) {
        if (!usable) return;
        try {
            io.submit(() -> {
                try (var q = db().prepareStatement("UPDATE codex_travel_server SET heartbeat=0 WHERE server=?")) { q.setString(1, server); q.executeUpdate(); }
                try (var q = db().prepareStatement("UPDATE codex_travel_presence SET at=0 WHERE server=?")) { q.setString(1, server); q.executeUpdate(); }
                return null;
            }).get(3, TimeUnit.SECONDS);
        } catch (Exception ignored) {}
    }
    void servers(Consumer<Map<String, TravelModel.ServerStatus>> done) {
        submit("서버 상태 읽기", c -> {
            var out = new HashMap<String, TravelModel.ServerStatus>();
            try (var q = c.prepareStatement("SELECT server,heartbeat,online,max_players,worlds,config_hash FROM codex_travel_server"); var r = q.executeQuery()) {
                while (r.next()) {
                    var worlds = new HashMap<String, String>(); var spawns = new HashMap<String, double[]>();
                    TravelModel.unpackWorlds(r.getString(5), worlds, spawns);
                    out.put(r.getString(1), new TravelModel.ServerStatus(r.getString(1), r.getLong(2), r.getInt(3), r.getInt(4), worlds, r.getString(6), spawns));
                }
            }
            return out;
        }, null, done);
    }

    // ------------------------------------------------------------------ 리턴권·텔포권 (2026-10-26)

    /** 이 서버 접속자 한 줄 쓰기 (서버 상태와 같은 15초마다 + 들어오고 나갈 때 몰아서). */
    void presence(String server, String packed) {
        submit("접속자 쓰기", c -> {
            String sql = settings.mariaDb()
                    ? "INSERT INTO codex_travel_presence(server,at,players) VALUES(?,?,?) ON DUPLICATE KEY UPDATE at=VALUES(at),players=VALUES(players)"
                    : "INSERT OR REPLACE INTO codex_travel_presence(server,at,players) VALUES(?,?,?)";
            try (var q = c.prepareStatement(sql)) { q.setString(1, server); q.setLong(2, System.currentTimeMillis()); q.setString(3, packed); q.executeUpdate(); }
            return true;
        }, false, null);
    }
    /** 모든 서버 접속자 줄 (서버 수만큼, 몇 줄 안 됨). null = 읽기 실패. */
    void presenceAll(Consumer<List<TravelItemRules.Presence>> done) {
        submit("접속자 읽기", c -> {
            var out = new ArrayList<TravelItemRules.Presence>();
            try (var q = c.prepareStatement("SELECT server,at,players FROM codex_travel_presence"); var r = q.executeQuery()) {
                while (r.next()) out.add(new TravelItemRules.Presence(r.getString(1), r.getLong(2), TravelItemRules.unpack(r.getString(3))));
            }
            return out;
        }, null, done);
    }
    /** 마지막 야생 사망 자리 바꾸기 (플레이어마다 한 줄). */
    void saveDeath(UUID player, TravelItemRules.DeathPoint d, Consumer<Boolean> done) {
        submit("사망 자리 저장", c -> {
            String sql = settings.mariaDb()
                    ? "INSERT INTO codex_travel_death(uuid,server,world,world_uid,x,y,z,yaw,pitch,at) VALUES(?,?,?,?,?,?,?,?,?,?) ON DUPLICATE KEY UPDATE "
                      + "server=VALUES(server),world=VALUES(world),world_uid=VALUES(world_uid),x=VALUES(x),y=VALUES(y),z=VALUES(z),yaw=VALUES(yaw),pitch=VALUES(pitch),at=VALUES(at)"
                    : "INSERT OR REPLACE INTO codex_travel_death(uuid,server,world,world_uid,x,y,z,yaw,pitch,at) VALUES(?,?,?,?,?,?,?,?,?,?)";
            try (var q = c.prepareStatement(sql)) {
                q.setString(1, player.toString()); q.setString(2, d.server()); q.setString(3, d.world()); q.setString(4, d.worldUid());
                q.setDouble(5, d.x()); q.setDouble(6, d.y()); q.setDouble(7, d.z()); q.setFloat(8, d.yaw()); q.setFloat(9, d.pitch()); q.setLong(10, d.at());
                q.executeUpdate(); return true;
            }
        }, false, done);
    }
    /** 마지막 야생 사망 자리. 빈 Optional = 기록 없음, null = 읽기 실패. */
    void death(UUID player, Consumer<Optional<TravelItemRules.DeathPoint>> done) {
        submit("사망 자리 읽기", c -> {
            try (var q = c.prepareStatement("SELECT server,world,world_uid,x,y,z,yaw,pitch,at FROM codex_travel_death WHERE uuid=?")) {
                q.setString(1, player.toString());
                try (var r = q.executeQuery()) {
                    if (!r.next()) return Optional.<TravelItemRules.DeathPoint>empty();
                    return Optional.of(new TravelItemRules.DeathPoint(r.getString(1), r.getString(2), r.getString(3), r.getDouble(4), r.getDouble(5), r.getDouble(6),
                            r.getFloat(7), r.getFloat(8), r.getLong(9)));
                }
            }
        }, null, done);
    }

    @Override public void close() {
        closed = true;
        io.shutdown();
        try { io.awaitTermination(3, TimeUnit.SECONDS); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        try { if (connection != null) connection.close(); } catch (Exception ignored) {}
    }
}
