package school.chacademia.housing;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.logging.Logger;
import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;
import school.magiccodex.database.ConnectionHolder;
import school.magiccodex.database.DatabaseSettings;
import school.magiccodex.protocol.HousingProtocol.Entry;
import school.magiccodex.protocol.HousingProtocol.Friend;
import school.magiccodex.protocol.HousingProtocol.Person;
import school.magiccodex.protocol.HousingProtocol.Viewer;
import school.magiccodex.protocol.HousingRules;
import school.magiccodex.protocol.HousingRules.Def;
import school.magiccodex.protocol.HousingRules.Layout;
import school.magiccodex.protocol.SchoolProtocol;

/**
 * 방·레이아웃·보유 가구·좋아요·방문·방명록·구경 중 명단. 공용 MariaDB(codex_housing_*)를 학교·야생이 함께 쓴다.
 * SQL 은 전부 작업 스레드 하나에서 돌고, 결과는 메인 스레드로 돌아온다. 실패하면 경고를 남기고 결과는 null.
 * MagicCodexBridge 테이블(닉네임·기숙사·친구)은 읽기만 한다.
 */
final class HousingStore {
    record RoomRow(UUID owner, String name, int house, String skin, boolean slim, int size, int active, int likes, int visits, long rev) {
        Person person() { return new Person(owner, name.isBlank() ? "학생" : name, house, skin, slim); }
    }
    record RoomData(RoomRow row, Map<Integer, Layout> layouts, int today, boolean liked) {}
    record Presence(UUID viewer, UUID owner, String name) {}
    record Sync(Map<UUID, List<Viewer>> viewers, Map<UUID, Long> revs) {}

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");
    static final long PRESENCE_TTL = 8_000, ONLINE_TTL = 75_000;

    private final Plugin plugin; private final Logger log;
    private final ExecutorService io = Executors.newSingleThreadExecutor(r -> { var t = new Thread(r, "ChacaHousing-db"); t.setDaemon(true); return t; });
    private ConnectionHolder db; private DatabaseSettings settings; private boolean maria;
    private HousingNames displayNames;
    private HousingDirectory directory;
    private String tRoom, tLayout, tLike, tVisit, tBook, tPresence, tOnline, tFriends, tNick, tHouse, tShowcase;
    private ExpandLedger ledger;
    /** 종료 중(onDisable)에 끝난 작업의 콜백: 메인 스레드로 보낼 수 없으므로 close() 가 작업 스레드를 기다린 뒤 메인 스레드에서 실행한다. */
    private final java.util.concurrent.ConcurrentLinkedQueue<Runnable> late = new java.util.concurrent.ConcurrentLinkedQueue<>();

    HousingStore(Plugin plugin) { this.plugin = plugin; this.log = plugin.getLogger(); }

    void open(Path dataFolder) throws Exception {
        var bridge=Bukkit.getPluginManager().getPlugin("MagicCodexBridge");
        if(bridge==null)throw new IllegalStateException("MagicCodexBridge nickname domain unavailable");
        displayNames=new HousingNames(bridge.getDataFolder().toPath());
        directory=new HousingDirectory(bridge.getDataFolder().toPath());
        io.submit(() -> {
            Path file = dataFolder.resolve("database.properties");
            settings = DatabaseSettings.load(file);
            log.info(DatabaseSettings.describe(file, settings));
            if (!settings.mariaDb()) log.warning("SQLite 모드: 이 서버 안에서만 방이 보이고 닉네임·기숙사·친구 목록은 읽지 못합니다. 학교·야생 공용 MariaDB 를 설정해 주세요.");
            maria = settings.mariaDb();
            db = settings.holder(dataFolder.resolve("housing.db"));
            tRoom = settings.table("housing_room", "housing_room");
            tLayout = settings.table("housing_layout", "housing_layout");
            tLike = settings.table("housing_like", "housing_like");
            tVisit = settings.table("housing_visit", "housing_visit");
            tBook = settings.table("housing_guestbook", "housing_guestbook");
            tPresence = settings.table("housing_presence", "housing_presence");
            tOnline = settings.table("housing_online", "housing_online");
            tFriends = settings.table("friends", "friends");
            tNick = settings.table("nicknames", "nicknames");
            tHouse = settings.table("houses", "houses");
            tShowcase = settings.table("housing_showcase", "housing_showcase");
            ledger = new ExpandLedger(settings.table("housing_expand_tx", "housing_expand_tx"), tRoom);
            String engine = maria ? " ENGINE=InnoDB DEFAULT CHARSET=utf8mb4" : "";
            try (var s = db.get().createStatement()) {
                s.execute("CREATE TABLE IF NOT EXISTS " + tRoom + "(owner CHAR(36) NOT NULL PRIMARY KEY, name VARCHAR(32) NOT NULL, house INT NOT NULL,"
                        + " skin VARCHAR(64) NOT NULL, slim INT NOT NULL, size INT NOT NULL, active INT NOT NULL, likes INT NOT NULL, visits INT NOT NULL,"
                        + " rev BIGINT NOT NULL)" + engine);
                s.execute("CREATE TABLE IF NOT EXISTS " + tLayout + "(owner CHAR(36) NOT NULL, slot INT NOT NULL, data TEXT NOT NULL, PRIMARY KEY(owner, slot))" + engine);
                s.execute("CREATE TABLE IF NOT EXISTS " + tLike + "(owner CHAR(36) NOT NULL, liker CHAR(36) NOT NULL, created BIGINT NOT NULL, PRIMARY KEY(owner, liker))" + engine);
                s.execute("CREATE TABLE IF NOT EXISTS " + tVisit + "(owner CHAR(36) NOT NULL, visitor CHAR(36) NOT NULL, day INT NOT NULL, PRIMARY KEY(owner, visitor, day))" + engine);
                s.execute("CREATE TABLE IF NOT EXISTS " + tBook + "(" + (maria ? "id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY" : "id INTEGER PRIMARY KEY AUTOINCREMENT")
                        + ", owner CHAR(36) NOT NULL, author CHAR(36) NOT NULL, name VARCHAR(32) NOT NULL, house INT NOT NULL, skin VARCHAR(64) NOT NULL,"
                        + " slim INT NOT NULL, created BIGINT NOT NULL, body VARCHAR(255) NOT NULL" + (maria ? ", KEY ix_housing_book(owner, id)" : "") + ")" + engine);
                if (!maria) s.execute("CREATE INDEX IF NOT EXISTS ix_housing_book ON " + tBook + "(owner, id)");
                s.execute("CREATE TABLE IF NOT EXISTS " + tPresence + "(viewer CHAR(36) NOT NULL PRIMARY KEY, owner CHAR(36) NOT NULL, name VARCHAR(32) NOT NULL,"
                        + " server VARCHAR(32) NOT NULL, seen BIGINT NOT NULL" + (maria ? ", KEY ix_housing_presence(owner)" : "") + ")" + engine);
                if (!maria) s.execute("CREATE INDEX IF NOT EXISTS ix_housing_presence ON " + tPresence + "(owner)");
                s.execute("CREATE TABLE IF NOT EXISTS " + tOnline + "(player CHAR(36) NOT NULL PRIMARY KEY, server VARCHAR(32) NOT NULL, seen BIGINT NOT NULL)" + engine);
                // #108 (2026-10-26) 특수 가구 전시 값: 주인의 장착 빗자루 id·이름, 장착 아티팩트 id(쉼표). 다른 서버에 있는 주인 방에서도 보이게.
                s.execute("CREATE TABLE IF NOT EXISTS " + tShowcase + "(owner CHAR(36) NOT NULL PRIMARY KEY, broom VARCHAR(64) NOT NULL, broom_name VARCHAR(64) NOT NULL,"
                        + " artifacts VARCHAR(600) NOT NULL, updated BIGINT NOT NULL)" + engine);
            }
            ledger.create(db.get(), engine);
            return null;
        }).get(15, TimeUnit.SECONDS);
    }

    void close() {
        io.execute(()->{try{if(displayNames!=null)displayNames.close();if(directory!=null)directory.close();}catch(Exception ignored){}});
        io.shutdown();
        try { io.awaitTermination(10, TimeUnit.SECONDS); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        // 종료 중에 끝난 작업의 콜백을 버리지 않는다 (onDisable = 메인 스레드). 여기서 새로 부르는 DB 작업은 closing() 이라 바로 null 로 끝난다.
        for (Runnable r; (r = late.poll()) != null; ) {
            try { r.run(); } catch (RuntimeException e) { log.warning("하우징 종료 중 콜백 실패: " + e); }
        }
        try { if (db != null) db.close(); } catch (Exception ignored) { }
    }
    /** 종료 중이면 true: 돈을 움직이는 후속 처리(환불 등)는 하지 않고 다음 시작 때 관리자 확인으로 넘긴다. */
    boolean closing() { return io.isShutdown(); }

    interface Sql<T> { T run(Connection c) throws SQLException; }

    private <T> void async(String what, Sql<T> work, Consumer<T> done) {
        // 종료 중: 작업은 하지 않고, 콜백은 "DB 오류(null)"로 바로 알린다 (이전에는 아무 말 없이 버렸음).
        if (io.isShutdown()) { log.warning("하우징 DB 작업을 하지 않음 (종료 중): " + what); if (done != null) done.accept(null); return; }
        try {
            io.execute(() -> {
                T v;
                try { v = work.run(db.get()); }
                catch (SQLException | RuntimeException e) { log.warning("하우징 DB 작업 실패 (" + what + "): " + e.getMessage()); v = null; }
                T out = v;
                if (done == null) return;
                Runnable r = () -> done.accept(out);
                // onDisable 중에는 isEnabled()=false 라 runTask 를 쓸 수 없다: close() 가 메인 스레드에서 실행한다.
                if (plugin.isEnabled()) try { Bukkit.getScheduler().runTask(plugin, r); return; } catch (RuntimeException disabling) { /* 아래 late 로 */ }
                late.add(r);
            });
        } catch (java.util.concurrent.RejectedExecutionException e) { log.warning("하우징 DB 작업을 하지 않음 (종료 중): " + what); if (done != null) done.accept(null); }
    }
    private <T> T tx(Sql<T> work) throws SQLException {
        Connection c = db.begin();
        try { T v = work.run(c); c.commit(); return v; }
        catch (SQLException | RuntimeException e) { db.rollback(); throw e; }
        finally { db.end(); }
    }

    // ------------------------------------------------------------ SQL 방언
    private String upsert(String table, String keys, String... cols) {
        var all = new ArrayList<String>(List.of(keys.split(","))); all.addAll(List.of(cols));
        String marks = String.join(",", Collections.nCopies(all.size(), "?"));
        var set = new StringJoiner(",");
        for (String c : cols) set.add(maria ? c + "=VALUES(" + c + ")" : c + "=excluded." + c);
        return "INSERT INTO " + table + "(" + String.join(",", all) + ") VALUES(" + marks + ") "
                + (maria ? "ON DUPLICATE KEY UPDATE " : "ON CONFLICT(" + keys + ") DO UPDATE SET ") + set;
    }
    private String insertIgnore() { return maria ? "INSERT IGNORE INTO " : "INSERT OR IGNORE INTO "; }
    static int today() { var d = LocalDate.now(KST); return d.getYear() * 10000 + d.getMonthValue() * 100 + d.getDayOfMonth(); }
    private static String in(int n) { return String.join(",", Collections.nCopies(n, "?")); }

    // ------------------------------------------------------------ 방
    /**
     * 접속할 때: 방이 없으면 만들고, 닉네임·기숙사·스킨을 최신으로 맞춘다.
     * 기숙사는 Bridge 와 같은 순서: codex_houses → (없으면) fallbackHouse (Bridge StatsService·magiccodex.house.* 권한, 메인 스레드에서 구함).
     * allowed = 지금 권한으로 놓을 수 있는 가구 → 권한이 빠진 가구는 저장된 레이아웃에서 뺀다.
     */
    void touch(UUID id, String fallback, int fallbackHouse, String skin, boolean slim, int startSize, Function<String, Def> defs, Map<String, Integer> allowed, Consumer<RoomRow> done) {
        async("접속 갱신", c -> {
            RoomRow existing = room(c,id);
            String name = existing == null ? fallback : existing.name();
            int house = house(c, id); if (house < 0) house = fallbackHouse;
            RoomRow row = room(c, id);
            if (row == null) {
                try (var s = c.prepareStatement(insertIgnore() + tRoom + "(owner,name,house,skin,slim,size,active,likes,visits,rev) VALUES(?,?,?,?,?,?,0,0,0,1)")) {
                    s.setString(1, id.toString()); s.setString(2, cut(name)); s.setInt(3, house); s.setString(4, skin); s.setInt(5, slim ? 1 : 0);
                    s.setInt(6, Math.clamp(startSize, HousingRules.MIN_SIZE, HousingRules.MAX_SIZE)); s.executeUpdate();
                }
            } else if (!row.name().equals(cut(name)) || row.house() != house || !row.skin().equals(skin) || row.slim() != slim) {
                try (var s = c.prepareStatement("UPDATE " + tRoom + " SET name=?,house=?,skin=?,slim=?,rev=rev+1 WHERE owner=?")) {
                    s.setString(1, cut(name)); s.setInt(2, house); s.setString(3, skin); s.setInt(4, slim ? 1 : 0); s.setString(5, id.toString()); s.executeUpdate();
                }
            }
            RoomRow now = room(c, id);
            if (now != null) pruneAll(c, id, now.size(), defs, allowed);
            return display(now);
        }, done);
    }

    /** 평생 한 번 방문 기록 (visits.count-once-per: ever) 에 쓰는 day 값. 하루 기록 정리(heartbeat)에서 지우지 않는다. */
    static final int EVER_DAY = 0;

    /**
     * 방 전체. viewer 가 주인이 아니고 countVisit 이면 방문을 센다: 오늘 방문(today)은 방문자별 하루 한 번,
     * 누적 방문(visits)은 onceEver=false 면 방문자별 하루 한 번, true 면 방문자별 평생 한 번 (#51). 방이 없으면 null.
     */
    void load(UUID owner, UUID viewer, boolean countVisit, Consumer<RoomData> done) { load(owner, viewer, countVisit, false, done); }
    void load(UUID owner, UUID viewer, boolean countVisit, boolean onceEver, Consumer<RoomData> done) {
        async("방 읽기", c -> {
            if (room(c, owner) == null) return null;
            int day = today();
            if (countVisit && !owner.equals(viewer)) {
                int added;
                try (var s = c.prepareStatement(insertIgnore() + tVisit + "(owner,visitor,day) VALUES(?,?,?)")) {
                    s.setString(1, owner.toString()); s.setString(2, viewer.toString()); s.setInt(3, day); added = s.executeUpdate();
                    if (onceEver) { s.setInt(3, EVER_DAY); added = s.executeUpdate(); }
                }
                if (added > 0) try (var s = c.prepareStatement("UPDATE " + tRoom + " SET visits=visits+1,rev=rev+1 WHERE owner=?")) { s.setString(1, owner.toString()); s.executeUpdate(); }
            }
            RoomRow row = room(c, owner);
            var layouts = layouts(c, owner);
            int today;
            try (var s = c.prepareStatement("SELECT COUNT(*) FROM " + tVisit + " WHERE owner=? AND day=?")) {
                s.setString(1, owner.toString()); s.setInt(2, day);
                try (var r = s.executeQuery()) { today = r.next() ? r.getInt(1) : 0; }
            }
            boolean liked;
            try (var s = c.prepareStatement("SELECT 1 FROM " + tLike + " WHERE owner=? AND liker=?")) {
                s.setString(1, owner.toString()); s.setString(2, viewer.toString());
                try (var r = s.executeQuery()) { liked = r.next(); }
            }
            return new RoomData(display(row), layouts, today, liked);
        }, done);
    }

    /** @return null = 저장됨, 아니면 이유 */
    void save(UUID owner, int slot, Layout layout, Function<String, Def> defs, Map<String, Integer> allowed, Consumer<String> done) {
        async("레이아웃 저장", c -> tx(t -> {
            RoomRow row = room(t, owner);
            if (row == null) return "방을 찾을 수 없어요.";
            String why = HousingRules.validate(layout, row.size(), defs, allowed);
            if (why != null) return why;
            try (var s = t.prepareStatement(upsert(tLayout, "owner,slot", "data"))) {
                s.setString(1, owner.toString()); s.setInt(2, slot); s.setString(3, HousingRules.encode(layout)); s.executeUpdate();
            }
            try (var s = t.prepareStatement("UPDATE " + tRoom + " SET active=?,rev=rev+1 WHERE owner=?")) { s.setInt(1, slot); s.setString(2, owner.toString()); s.executeUpdate(); }
            return "";
        }), r -> done.accept(r == null ? "저장하지 못했어요. 잠시 후 다시 시도해 주세요." : r.isEmpty() ? null : r));
    }

    /** 지금 크기가 from 일 때만 to 로 (두 번 눌러도 한 번만). @return true = 넓어짐, false = 크기가 달라짐, null = DB 오류 */
    void expand(UUID owner, int from, int to, Consumer<Boolean> done) {
        async("방 확장", c -> {
            try (var s = c.prepareStatement("UPDATE " + tRoom + " SET size=?,rev=rev+1 WHERE owner=? AND size=?")) {
                s.setInt(1, to); s.setString(2, owner.toString()); s.setInt(3, from); return s.executeUpdate() > 0;
            }
        }, done);
    }

    // ------------------------------------------------------------ 방 확장 결제 원장 (A03, 2026-10-21)
    /** 출금 전에 거래를 기록한다. @return true = 기록됨 (null = 기록 못 함 → 출금하지 않는다) */
    void expandPrepare(String id, UUID owner, int from, int to, long cost, String origin, Consumer<Boolean> done) {
        async("방 확장 기록", c -> { ledger.prepare(c, id, owner, from, to, cost, origin); return true; }, done);
    }
    /** 거래 단계 옮기기. @return true = 옮김, false = 이미 다른 단계, null = DB 오류 */
    void expandMove(String id, String from, String to, String note, Consumer<Boolean> done) {
        async("방 확장 단계 " + from + "→" + to, c -> ledger.move(c, id, from, to, note), done);
    }
    /** 출금된 거래를 한 트랜잭션으로 방에 반영. @return applied / refund_pending (이미 처리됐으면 지금 단계), null = DB 오류(결과 모름) */
    void expandApply(String id, Consumer<String> done) {
        async("방 확장 반영", c -> tx(t -> ledger.apply(t, id, maria)), done);
    }
    /** 시작 시: 이 서버의 끝나지 않은 거래를 uncertain 으로 (돈을 자동으로 움직이지 않음). */
    void expandRecover(String origin, Consumer<int[]> done) {
        async("방 확장 기록 복구", c -> ledger.recover(c, origin), done);
    }
    void expandList(UUID owner, int limit, Consumer<List<ExpandLedger.Tx>> done) {
        async("방 확장 기록 읽기", c -> ledger.list(c, owner, limit), done);
    }
    /** @return 거래 (없으면 빈 Optional), null = DB 오류 */
    void expandGet(String id, Consumer<Optional<ExpandLedger.Tx>> done) {
        async("방 확장 기록 읽기", c -> Optional.ofNullable(ledger.get(c, id, false)), done);
    }

    /** 관리자: 크기 바꾸기 (줄이면 밖으로 나간 가구는 보관함으로). */
    void setSize(UUID owner, int size, Function<String, Def> defs, Map<String, Integer> limits, Consumer<Boolean> done) {
        async("방 크기 설정", c -> tx(t -> {
            int n;
            try (var s = t.prepareStatement("UPDATE " + tRoom + " SET size=?,rev=rev+1 WHERE owner=?")) { s.setInt(1, size); s.setString(2, owner.toString()); n = s.executeUpdate(); }
            if (n == 0) return false;
            pruneAll(t, owner, size, defs, limits);
            return true;
        }), done);
    }

    static final String LIKE_OK = "", LIKE_ALREADY = "이미 좋아요를 누른 방이에요.", LIKE_USED = "좋아요는 한 번만 누를 수 있어요. 이미 다른 방에 눌렀어요.";
    /**
     * 좋아요(추천) — 한 번 누르면 취소·다시 누르기 없음 (#78, 2026-10-25. 예전에는 켜기/끄기 토글이라 끄고 다시 켜면 몇 번이든 됐음).
     * global = true 면 모든 방을 통틀어 한 사람 한 번 (like.limit: global). 기록은 공용 DB codex_housing_like (학교·야생 공유).
     * @return LIKE_OK = 눌림, 그 밖 = 이유 문구, null = DB 오류
     */
    void like(UUID owner, UUID liker, boolean global, Consumer<String> done) {
        async("좋아요", c -> tx(t -> {
            try (var s = t.prepareStatement("SELECT owner FROM " + tLike + " WHERE liker=?" + (global ? "" : " AND owner=?") + " LIMIT 1")) {
                s.setString(1, liker.toString()); if (!global) s.setString(2, owner.toString());
                try (var r = s.executeQuery()) { if (r.next()) return owner.toString().equals(r.getString(1)) ? LIKE_ALREADY : LIKE_USED; }
            }
            int added;
            try (var s = t.prepareStatement(insertIgnore() + tLike + "(owner,liker,created) VALUES(?,?,?)")) {
                s.setString(1, owner.toString()); s.setString(2, liker.toString()); s.setLong(3, System.currentTimeMillis()); added = s.executeUpdate();
            }
            if (added == 0) return LIKE_ALREADY; // 다른 서버에서 같은 순간에 눌림 (방별 기본키)
            try (var s = t.prepareStatement("UPDATE " + tRoom + " SET likes=likes+1,rev=rev+1 WHERE owner=?")) { s.setString(1, owner.toString()); s.executeUpdate(); }
            return LIKE_OK;
        }), done);
    }

    /**
     * 누적 방문 순위 (#51): 방 누적 방문 수(codex_housing_room.visits) 많은 순 상위 limit 개 (같으면 UUID 순). 이름은 MagicCodex 등록 닉네임.
     * 요청할 때만 읽는다 (주기 순회 없음). @return 목록, null = DB 오류
     */
    void topVisited(int limit, Consumer<List<RoomRow>> done) {
        int n = Math.clamp(limit, 1, 100);
        async("방문 순위", c -> {
            var rows = new ArrayList<RoomRow>();
            try (var s = c.prepareStatement("SELECT * FROM " + tRoom + " WHERE visits>0 ORDER BY visits DESC, owner LIMIT ?")) {
                s.setInt(1, n);
                try (var r = s.executeQuery()) { while (r.next()) { try { rows.add(row(r)); } catch (RuntimeException bad) { /* 깨진 줄은 건너뜀 */ } } }
            }
            var names = displayNames.read(rows.stream().map(RoomRow::owner).toList());
            var out = new ArrayList<RoomRow>();
            for (var row : rows) out.add(new RoomRow(row.owner(), HousingNames.shown(names.get(row.owner())), row.house(), row.skin(), row.slim(), row.size(), row.active(), row.likes(), row.visits(), row.rev()));
            return List.copyOf(out);
        }, done);
    }

    // ------------------------------------------------------------ 하우징 랭킹 (#51, 승인 레퍼런스 2026-10-25)
    record RankRow(UUID owner, String name, int today, int total, int hearts, int rank, int visit) {}
    record RankPage(int page, int pages, List<RankRow> rows) {}

    /**
     * 랭킹 한 쪽 (6줄). metric 0 = 일일 방문 (KST 오늘, 주인별 고유 방문자 수 — 본인 방문은 처음부터 기록 안 됨),
     * 1 = 누적 방문 (codex_housing_room.visits), 2 = 하트 (codex_housing_room.likes, 좋아요 토글 값). 측정 정의는 기존 그대로.
     * 그 값이 0 인 방은 빠진다. 같은 값이면 같은 순위 (1,1,3 …), 표시 순서는 값 → 누적 → 하트 → 오늘 → UUID.
     * visit: 내 방 2 · 그 밖 1 (랭킹에서는 친구가 아니어도 이동 가능, 오너 결정 2026-10-25).
     * 요청할 때만 읽는다 (주기 순회 없음). DB 오류면 null.
     */
    void ranking(int metric, int page, UUID viewer, boolean operator, Consumer<RankPage> done) {
        async("하우징 랭킹", c -> {
            int day = today();
            String daily = "(SELECT owner, COUNT(*) AS c FROM " + tVisit + " WHERE day=? GROUP BY owner)";
            String from, where, order, value, rankSql;
            switch (metric) {
                case 0 -> { from = tRoom + " r JOIN " + daily + " v ON v.owner=r.owner"; where = ""; value = "v.c";
                    order = "v.c DESC, r.visits DESC, r.likes DESC, r.owner"; rankSql = "SELECT COUNT(*) FROM " + tRoom + " r JOIN " + daily + " v ON v.owner=r.owner WHERE v.c>?"; }
                case 1 -> { from = tRoom + " r LEFT JOIN " + daily + " v ON v.owner=r.owner"; where = " WHERE r.visits>0"; value = "r.visits";
                    order = "r.visits DESC, r.likes DESC, COALESCE(v.c,0) DESC, r.owner"; rankSql = "SELECT COUNT(*) FROM " + tRoom + " WHERE visits>?"; }
                default -> { from = tRoom + " r LEFT JOIN " + daily + " v ON v.owner=r.owner"; where = " WHERE r.likes>0"; value = "r.likes";
                    order = "r.likes DESC, r.visits DESC, COALESCE(v.c,0) DESC, r.owner"; rankSql = "SELECT COUNT(*) FROM " + tRoom + " WHERE likes>?"; }
            }
            int count;
            try (var s = c.prepareStatement("SELECT COUNT(*) FROM " + from + where)) { s.setInt(1, day); try (var r = s.executeQuery()) { count = r.next() ? r.getInt(1) : 0; } }
            int pages = Math.max(1, Math.min(school.magiccodex.protocol.HousingRankProtocol.MAX_PAGE + 1, (count + 5) / 6));
            int p = Math.clamp(page, 0, pages - 1);
            record Raw(UUID owner, int today, int total, int hearts, int value) {}
            var raw = new ArrayList<Raw>();
            try (var s = c.prepareStatement("SELECT r.owner, r.visits, r.likes, COALESCE(v.c,0) AS today, " + value + " AS val FROM " + from + where + " ORDER BY " + order + " LIMIT ? OFFSET ?")) {
                s.setInt(1, day); s.setInt(2, 6); s.setInt(3, p * 6);
                try (var r = s.executeQuery()) {
                    while (r.next()) {
                        try { raw.add(new Raw(UUID.fromString(r.getString(1)), Math.max(0, r.getInt(4)), Math.max(0, r.getInt(2)), Math.max(0, r.getInt(3)), r.getInt(5))); }
                        catch (IllegalArgumentException bad) { /* 깨진 줄은 건너뜀 */ }
                    }
                }
            }
            var ranks = new HashMap<Integer, Integer>();
            for (var x : raw) {
                if (ranks.containsKey(x.value())) continue;
                try (var s = c.prepareStatement(rankSql)) {
                    int i = 1; if (metric == 0) s.setInt(i++, day); s.setInt(i, x.value());
                    try (var r = s.executeQuery()) { ranks.put(x.value(), 1 + (r.next() ? r.getInt(1) : 0)); }
                }
            }
            var names = displayNames.read(raw.stream().map(Raw::owner).toList());
            var out = new ArrayList<RankRow>();
            for (var x : raw) {
                int visit = x.owner().equals(viewer) ? 2 : 1; // 랭킹에서는 누구 방이든 이동 가능 (오너 결정 2026-10-25)
                String name = HousingNames.shown(names.get(x.owner()));
                if (name.length() > 32) name = name.substring(0, 32);
                out.add(new RankRow(x.owner(), name, x.today(), x.total(), x.hearts(), ranks.getOrDefault(x.value(), 1), visit));
            }
            return new RankPage(p, pages, List.copyOf(out));
        }, done);
    }

    /** 관리자: 방 정보 한 줄 (요약·보기용). 방이 없으면 null. */
    void row(UUID owner, Consumer<RoomRow> done) { async("방 정보", c -> display(room(c, owner)), done); }

    /** 관리자: 레이아웃 지우기. slot &lt; 0 이면 전부. @return 지운 레이아웃 수 (방이 없으면 -1, DB 오류 null) */
    void resetLayouts(UUID owner, int slot, Consumer<Integer> done) {
        async("배치 초기화", c -> tx(t -> {
            if (room(t, owner) == null) return -1;
            int n;
            try (var s = t.prepareStatement("DELETE FROM " + tLayout + " WHERE owner=?" + (slot >= 0 ? " AND slot=?" : ""))) {
                s.setString(1, owner.toString()); if (slot >= 0) s.setInt(2, slot); n = s.executeUpdate();
            }
            bump(t, owner);
            return n;
        }), done);
    }

    /** 관리자: 좋아요 전부 지우기. @return 지운 수 (방이 없으면 -1, DB 오류 null) */
    void resetLikes(UUID owner, Consumer<Integer> done) {
        async("좋아요 초기화", c -> tx(t -> {
            if (room(t, owner) == null) return -1;
            int n;
            try (var s = t.prepareStatement("DELETE FROM " + tLike + " WHERE owner=?")) { s.setString(1, owner.toString()); n = s.executeUpdate(); }
            try (var s = t.prepareStatement("UPDATE " + tRoom + " SET likes=0,rev=rev+1 WHERE owner=?")) { s.setString(1, owner.toString()); s.executeUpdate(); }
            return n;
        }), done);
    }

    // ------------------------------------------------------------ 방명록
    void post(UUID owner, Person author, String text, int keep, Consumer<Boolean> done) {
        async("방명록 쓰기", c -> tx(t -> {
            try (var s = t.prepareStatement("INSERT INTO " + tBook + "(owner,author,name,house,skin,slim,created,body) VALUES(?,?,?,?,?,?,?,?)")) {
                s.setString(1, owner.toString()); s.setString(2, author.id().toString()); s.setString(3, cut(author.name())); s.setInt(4, author.house());
                s.setString(5, author.skin()); s.setInt(6, author.slim() ? 1 : 0); s.setLong(7, System.currentTimeMillis()); s.setString(8, text); s.executeUpdate();
            }
            Long floor = null;
            try (var s = t.prepareStatement("SELECT id FROM " + tBook + " WHERE owner=? ORDER BY id DESC LIMIT 1 OFFSET ?")) {
                s.setString(1, owner.toString()); s.setInt(2, Math.max(0, keep - 1));
                try (var r = s.executeQuery()) { if (r.next()) floor = r.getLong(1); }
            }
            if (floor != null) try (var s = t.prepareStatement("DELETE FROM " + tBook + " WHERE owner=? AND id<?")) { s.setString(1, owner.toString()); s.setLong(2, floor); s.executeUpdate(); }
            bump(t, owner);
            return true;
        }), done);
    }

    /** @return null = 지움, 아니면 이유 */
    void deleteEntry(UUID owner, long id, UUID requester, boolean admin, Consumer<String> done) {
        async("방명록 지우기", c -> tx(t -> {
            UUID author;
            try (var s = t.prepareStatement("SELECT author FROM " + tBook + " WHERE owner=? AND id=?")) {
                s.setString(1, owner.toString()); s.setLong(2, id);
                try (var r = s.executeQuery()) { if (!r.next()) return "이미 지워진 글이에요."; author = UUID.fromString(r.getString(1)); }
            }
            if (!admin && !requester.equals(owner) && !requester.equals(author)) return "내 방이나 내가 쓴 글만 지울 수 있어요.";
            try (var s = t.prepareStatement("DELETE FROM " + tBook + " WHERE owner=? AND id=?")) { s.setString(1, owner.toString()); s.setLong(2, id); s.executeUpdate(); }
            bump(t, owner);
            return "";
        }), r -> done.accept(r == null ? "지우지 못했어요." : r.isEmpty() ? null : r));
    }

    void guestbook(UUID owner, int limit, Consumer<List<Entry>> done) {
        async("방명록 읽기", c -> {
            var list = new ArrayList<Entry>();
            try (var s = c.prepareStatement("SELECT id,author,name,house,skin,slim,created,body FROM " + tBook + " WHERE owner=? ORDER BY id DESC LIMIT ?")) {
                s.setString(1, owner.toString()); s.setInt(2, limit);
                try (var r = s.executeQuery()) {
                    while (r.next()) {
                        try {
                            var p = new Person(UUID.fromString(r.getString(2)), r.getString(3), Math.clamp(r.getInt(4), -1, 3), skinOrEmpty(r.getString(5)), r.getInt(6) != 0);
                            list.add(new Entry(r.getLong(1), p, r.getLong(7), r.getString(8)));
                        } catch (IllegalArgumentException bad) { /* 깨진 줄은 건너뛴다 */ }
                    }
                }
            }
            var names=displayNames.read(list.stream().map(e->e.author().id()).toList());
            return list.stream().map(e->new Entry(e.id(),HousingNames.resolve(e.author(),names.get(e.author().id())),e.time(),e.text())).toList();
        }, done);
    }

    /** viewer has target in their friend list (Bridge friends table). null = could not read. */
    void isFriend(UUID viewer,UUID target,Consumer<Boolean> done){async("친구 방문 권한",c->{try{return directory.friends(viewer).contains(target);}catch(Exception e){throw new SQLException("Authoritative friendship unavailable",e);}},done);}


    void search(UUID viewer,school.magiccodex.protocol.HousingSearchProtocol.Query query,Set<UUID> online,Consumer<school.magiccodex.protocol.HousingSearchProtocol.Page> done){
        async("유저 검색",c->{try{var slice=directory.search(viewer,query.text(),query.page(),online);var out=new ArrayList<school.magiccodex.protocol.HousingSearchProtocol.User>();for(var hit:slice.hits()){var row=room(c,hit.id());var person=new Person(hit.id(),hit.name(),row==null?-1:row.house(),row==null?"":row.skin(),row!=null&&row.slim());out.add(new school.magiccodex.protocol.HousingSearchProtocol.User(person,hit.online(),hit.friend(),row==null?0:row.size()));}return new school.magiccodex.protocol.HousingSearchProtocol.Page(query.generation(),query.page(),slice.more(),out,"");}catch(Exception e){throw new SQLException("Global user directory lookup failed",e);}},done);
    }
    // ------------------------------------------------------------ 친구 방 목록
    void friends(UUID viewer, int limit, Consumer<List<Friend>> done) {
        async("친구 목록", c -> {
            record F(UUID id, String name, int house) {}
            var list = new ArrayList<F>();
            try (var s = c.prepareStatement("SELECT target,name,dorm FROM " + tFriends + " WHERE owner=? ORDER BY name LIMIT ?")) {
                s.setString(1, viewer.toString()); s.setInt(2, limit);
                try (var r = s.executeQuery()) {
                    while (r.next()) {
                        try { list.add(new F(UUID.fromString(r.getString(1)), r.getString(2), SchoolProtocol.HOUSES.indexOf(r.getString(3)))); }
                        catch (IllegalArgumentException bad) { /* 건너뜀 */ }
                    }
                }
            } catch (SQLException missing) { // SQLite 모드 등 친구 테이블 없음: 하우징 서버가 학교 서버(Bridge)와 같은 MariaDB 를 써야 함
                log.warning("하우징 친구 목록: 친구 테이블(" + tFriends + ")을 읽을 수 없어요 — 학교 서버와 같은 MariaDB 인지 database.properties 확인: " + missing.getMessage());
                return List.<Friend>of();
            }
            if (list.isEmpty()) return List.<Friend>of();
            var ids = list.stream().map(f -> f.id().toString()).toList();
            var rooms = new HashMap<UUID, RoomRow>();
            // a friend without a room (or an unreadable room row) is still listed
            try (var s = c.prepareStatement("SELECT * FROM " + tRoom + " WHERE owner IN (" + in(ids.size()) + ")")) {
                for (int i = 0; i < ids.size(); i++) s.setString(i + 1, ids.get(i));
                try (var r = s.executeQuery()) { while (r.next()) { try { var row = row(r); rooms.put(row.owner(), row); } catch (RuntimeException bad) { } } }
            } catch (SQLException e) { log.warning("하우징 친구 목록: 방 정보 읽기 실패 " + e.getMessage()); }
            var online = new HashSet<UUID>();
            try (var s = c.prepareStatement("SELECT player FROM " + tOnline + " WHERE seen>? AND player IN (" + in(ids.size()) + ")")) {
                s.setLong(1, System.currentTimeMillis() - ONLINE_TTL);
                for (int i = 0; i < ids.size(); i++) s.setString(i + 2, ids.get(i));
                try (var r = s.executeQuery()) { while (r.next()) online.add(UUID.fromString(r.getString(1))); }
            } catch (SQLException e) { log.warning("하우징 친구 목록: 접속 정보 읽기 실패 " + e.getMessage()); }
            var names=displayNames.read(list.stream().map(F::id).toList());
            var out = new ArrayList<Friend>();
            for (var f : list) {
                var room = rooms.get(f.id());
                String name = HousingNames.shown(names.get(f.id()));
                try {
                    String shown = name == null || name.isBlank() ? "학생" : cut(name);
                    Person p;
                    try { p = new Person(f.id(), shown, room != null ? room.house() : f.house(), room != null ? room.skin() : "", room != null && room.slim()); }
                    catch (IllegalArgumentException odd) { p = new Person(f.id(), "학생", Math.clamp(f.house(), -1, 3), "", false); } // 이상한 이름·스킨이라도 목록에는 남김
                    out.add(new Friend(p, online.contains(f.id()), room != null ? room.size() : 0));
                } catch (IllegalArgumentException bad) { /* 건너뜀 */ }
            }
            out.sort(Comparator.comparing((Friend f) -> !f.online()).thenComparing(f -> f.person().name()));
            return List.copyOf(out);
        }, done);
    }

    /** 닉네임(또는 저장된 방 이름)으로 찾기. 없으면 null. */
    void find(String name, Consumer<UUID> done) {
        async("닉네임 찾기", c -> {
            try (var s = c.prepareStatement("SELECT owner FROM " + tNick + " WHERE LOWER(nickname)=LOWER(?) LIMIT 1")) {
                s.setString(1, name); try (var r = s.executeQuery()) { if (r.next()) return UUID.fromString(r.getString(1)); }
            } catch (SQLException missing) { /* SQLite 모드 */ }
            try (var s = c.prepareStatement("SELECT owner FROM " + tRoom + " WHERE LOWER(name)=LOWER(?) LIMIT 1")) {
                s.setString(1, name); try (var r = s.executeQuery()) { return r.next() ? UUID.fromString(r.getString(1)) : null; }
            }
        }, done);
    }

    // ------------------------------------------------------------ 구경 중 명단 · 접속 표시
    /**
     * 이 서버의 구경 중 명단을 올리고(seen 갱신), owners 방에 지금 있는 사람(모든 서버)과 방 revision 을 읽는다.
     * 오래된 줄은 지운다.
     */
    void sync(String server, List<Presence> local, Collection<UUID> owners, Consumer<Sync> done) {
        async("구경 중 명단", c -> {
            long now = System.currentTimeMillis();
            if (!local.isEmpty()) try (var s = c.prepareStatement(upsert(tPresence, "viewer", "owner", "name", "server", "seen"))) {
                for (var p : local) { s.setString(1, p.viewer().toString()); s.setString(2, p.owner().toString()); s.setString(3, cut(p.name())); s.setString(4, server); s.setLong(5, now); s.addBatch(); }
                s.executeBatch();
            }
            try (var s = c.prepareStatement("DELETE FROM " + tPresence + " WHERE seen<?")) { s.setLong(1, now - PRESENCE_TTL * 4); s.executeUpdate(); }
            var viewers = new HashMap<UUID, List<Viewer>>(); var revs = new HashMap<UUID, Long>();
            if (owners.isEmpty()) return new Sync(viewers, revs);
            var ids = owners.stream().map(UUID::toString).toList();
            try (var s = c.prepareStatement("SELECT owner,viewer,name FROM " + tPresence + " WHERE seen>? AND owner IN (" + in(ids.size()) + ") ORDER BY seen")) {
                s.setLong(1, now - PRESENCE_TTL);
                for (int i = 0; i < ids.size(); i++) s.setString(i + 2, ids.get(i));
                try (var r = s.executeQuery()) {
                    while (r.next()) viewers.computeIfAbsent(UUID.fromString(r.getString(1)), k -> new ArrayList<>()).add(new Viewer(UUID.fromString(r.getString(2)), r.getString(3)));
                }
            }
            try (var s = c.prepareStatement("SELECT owner,rev FROM " + tRoom + " WHERE owner IN (" + in(ids.size()) + ")")) {
                for (int i = 0; i < ids.size(); i++) s.setString(i + 1, ids.get(i));
                try (var r = s.executeQuery()) { while (r.next()) revs.put(UUID.fromString(r.getString(1)), r.getLong(2)); }
            }
            var names=displayNames.read(viewers.values().stream().flatMap(List::stream).map(Viewer::id).toList());
            viewers.replaceAll((owner,list)->list.stream().map(v->new Viewer(v.id(),HousingNames.shown(names.get(v.id())))).toList());
            return new Sync(viewers, revs);
        }, done);
    }
    void leave(UUID viewer) {
        async("구경 끝", c -> { try (var s = c.prepareStatement("DELETE FROM " + tPresence + " WHERE viewer=?")) { s.setString(1, viewer.toString()); s.executeUpdate(); } return null; }, null);
    }
    void heartbeat(String server, List<UUID> players) {
        if (players.isEmpty()) return;
        async("접속 표시", c -> {
            long now = System.currentTimeMillis();
            try (var s = c.prepareStatement(upsert(tOnline, "player", "server", "seen"))) {
                for (var id : players) { s.setString(1, id.toString()); s.setString(2, server); s.setLong(3, now); s.addBatch(); }
                s.executeBatch();
            }
            try (var s = c.prepareStatement("DELETE FROM " + tOnline + " WHERE seen<?")) { s.setLong(1, now - ONLINE_TTL * 4); s.executeUpdate(); }
            try (var s = c.prepareStatement("DELETE FROM " + tVisit + " WHERE day<? AND day<>" + EVER_DAY)) { s.setInt(1, today() - 1); s.executeUpdate(); }
            return null;
        }, null);
    }
    /** 나갈 때: 다른 서버로 옮겨 가는 중이면 그 서버가 곧 다시 올린다. 이 서버 줄만 지운다. */
    void offline(UUID id, String server) {
        async("접속 끝", c -> {
            try (var s = c.prepareStatement("DELETE FROM " + tOnline + " WHERE player=? AND server=?")) { s.setString(1, id.toString()); s.setString(2, server); s.executeUpdate(); }
            try (var s = c.prepareStatement("DELETE FROM " + tPresence + " WHERE viewer=? AND server=?")) { s.setString(1, id.toString()); s.setString(2, server); s.executeUpdate(); }
            return null;
        }, null);
    }

    // ------------------------------------------------------------ 내부
    private RoomRow display(RoomRow row) throws SQLException {
        if(row==null)return null;String name=HousingNames.shown(displayNames.read(List.of(row.owner())).get(row.owner()));
        return new RoomRow(row.owner(),name,row.house(),row.skin(),row.slim(),row.size(),row.active(),row.likes(),row.visits(),row.rev());
    }
    private void bump(Connection c, UUID owner) throws SQLException {
        try (var s = c.prepareStatement("UPDATE " + tRoom + " SET rev=rev+1 WHERE owner=?")) { s.setString(1, owner.toString()); s.executeUpdate(); }
    }
    private void pruneAll(Connection c, UUID owner, int size, Function<String, Def> defs, Map<String, Integer> owned) throws SQLException {
        for (var e : layouts(c, owner).entrySet()) {
            Layout pruned = HousingRules.prune(e.getValue(), size, defs, owned);
            if (pruned.equals(e.getValue())) continue;
            try (var s = c.prepareStatement("UPDATE " + tLayout + " SET data=? WHERE owner=? AND slot=?")) {
                s.setString(1, HousingRules.encode(pruned)); s.setString(2, owner.toString()); s.setInt(3, e.getKey()); s.executeUpdate();
            }
        }
    }
    /** 정보창(MagicCodexBridge) 하우징 좋아요 합계: 방이 없으면 0, DB 오류면 null (메인 스레드 콜백). */
    void likes(UUID owner, Consumer<Integer> done) {
        async("likes", c -> {
            try (var s = c.prepareStatement("SELECT likes FROM " + tRoom + " WHERE owner=?")) {
                s.setString(1, owner.toString()); try (var r = s.executeQuery()) { return r.next() ? Math.max(0, r.getInt(1)) : 0; }
            }
        }, done);
    }
    // ------------------------------------------------------------ #108 특수 가구 전시 값
    /** 주인의 지금 전시 값을 적는다 (바뀌었을 때만 부름 — Showcases 가 거른다). @return true = 적음, null = DB 오류 */
    void saveShowcase(UUID owner, String broom, String broomName, String artifacts, Consumer<Boolean> done) {
        async("전시 값 저장", c -> {
            try (var s = c.prepareStatement(upsert(tShowcase, "owner", "broom", "broom_name", "artifacts", "updated"))) {
                s.setString(1, owner.toString()); s.setString(2, clip(broom, 64)); s.setString(3, clip(broomName, 64)); s.setString(4, clip(artifacts, 600));
                s.setLong(5, System.currentTimeMillis()); s.executeUpdate(); return true;
            }
        }, done);
    }
    /** 마지막으로 적힌 전시 값 {빗자루 id, 빗자루 이름, 아티팩트 id 쉼표}. 기록이 없으면 빈 값 셋, DB 오류면 null. */
    void showcase(UUID owner, Consumer<String[]> done) {
        async("전시 값 읽기", c -> {
            try (var s = c.prepareStatement("SELECT broom,broom_name,artifacts FROM " + tShowcase + " WHERE owner=?")) {
                s.setString(1, owner.toString());
                try (var r = s.executeQuery()) { return r.next() ? new String[]{r.getString(1), r.getString(2), r.getString(3)} : new String[]{"", "", ""}; }
            }
        }, done);
    }
    private static String clip(String v, int max) { return v == null ? "" : v.length() > max ? v.substring(0, max) : v; }

    private RoomRow room(Connection c, UUID owner) throws SQLException {
        try (var s = c.prepareStatement("SELECT * FROM " + tRoom + " WHERE owner=?")) {
            s.setString(1, owner.toString()); try (var r = s.executeQuery()) { return r.next() ? row(r) : null; }
        }
    }
    private static RoomRow row(ResultSet r) throws SQLException {
        return new RoomRow(UUID.fromString(r.getString("owner")), r.getString("name"), Math.clamp(r.getInt("house"), -1, 3), skinOrEmpty(r.getString("skin")),
                r.getInt("slim") != 0, Math.clamp(r.getInt("size"), HousingRules.MIN_SIZE, HousingRules.MAX_SIZE),
                Math.clamp(r.getInt("active"), 0, HousingRules.SLOTS - 1), Math.max(0, r.getInt("likes")), Math.max(0, r.getInt("visits")), r.getLong("rev"));
    }
    private Map<Integer, Layout> layouts(Connection c, UUID owner) throws SQLException {
        var map = new HashMap<Integer, Layout>();
        try (var s = c.prepareStatement("SELECT slot,data FROM " + tLayout + " WHERE owner=?")) {
            s.setString(1, owner.toString());
            try (var r = s.executeQuery()) {
                while (r.next()) {
                    int slot = r.getInt(1); if (slot < 0 || slot >= HousingRules.SLOTS) continue;
                    try { map.put(slot, HousingRules.decode(r.getString(2))); }
                    catch (IllegalArgumentException bad) { log.warning("방 " + owner + " 레이아웃 " + slot + " 형식 오류: 빈 방으로 보여요."); }
                }
            }
        }
        return map;
    }
    private String nickname(Connection c, UUID id) {
        try (var s = c.prepareStatement("SELECT nickname FROM " + tNick + " WHERE owner=?")) {
            s.setString(1, id.toString()); try (var r = s.executeQuery()) { return r.next() ? r.getString(1) : null; }
        } catch (SQLException missing) { return null; }
    }
    private int house(Connection c, UUID id) {
        try (var s = c.prepareStatement("SELECT house FROM " + tHouse + " WHERE player=?")) {
            s.setString(1, id.toString()); try (var r = s.executeQuery()) { return r.next() ? Math.clamp(r.getInt(1), -1, 3) : -1; }
        } catch (SQLException missing) { return -1; }
    }
    private static String cut(String name) { return name == null ? "" : name.length() > 32 ? name.substring(0, 32) : name; }
    static String skinOrEmpty(String s) { return s != null && s.matches("[0-9a-f]{32,64}") ? s : ""; }
}
