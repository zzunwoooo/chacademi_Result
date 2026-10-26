package kr.chacademy.npc.data;

import kr.chacademy.npc.core.Defs.RumorView;
import kr.chacademy.npc.core.Defs.Turn;
import kr.chacademy.npc.core.Json;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 모든 SQL. 메서드는 동기식이므로 반드시 Database.async(...) 안에서 호출한다.
 */
public final class Storage {

    private final Database db;

    public Storage(Database db) {
        this.db = db;
    }

    public Database db() {
        return db;
    }

    // ------------------------------------------------------------------ schema

    public void createTables() {
        String id = db.autoId();
        String text = db.textType();
        String k36 = db.keyType(36);
        String k64 = db.keyType(64);
        String suffix = db.isMariaDb() ? " DEFAULT CHARSET=utf8mb4" : "";
        String[] ddl = {
                "CREATE TABLE IF NOT EXISTS cnpc_memory (id " + id + ", player_uuid " + k36 + " NOT NULL, npc_id " + k64
                        + " NOT NULL, memo " + text + " NOT NULL, created_at BIGINT NOT NULL)" + suffix,
                "CREATE TABLE IF NOT EXISTS cnpc_last_chat (player_uuid " + k36 + " NOT NULL, npc_id " + k64
                        + " NOT NULL, turns_json " + text + " NOT NULL, ended_at BIGINT NOT NULL, PRIMARY KEY (player_uuid, npc_id))" + suffix,
                "CREATE TABLE IF NOT EXISTS cnpc_hint_log (id " + id + ", player_uuid " + k36 + " NOT NULL, hint_id " + k64
                        + " NOT NULL, npc_id " + k64 + " NOT NULL, given_at BIGINT NOT NULL)" + suffix,
                "CREATE TABLE IF NOT EXISTS cnpc_usage (day VARCHAR(10) NOT NULL, player_uuid VARCHAR(40) NOT NULL, calls INT NOT NULL,"
                        + " input_tokens BIGINT NOT NULL, cached_tokens BIGINT NOT NULL, output_tokens BIGINT NOT NULL,"
                        + " cost_usd DOUBLE NOT NULL, PRIMARY KEY (day, player_uuid))" + suffix,
                "CREATE TABLE IF NOT EXISTS cnpc_chat_log (id " + id + ", player_uuid " + k36 + " NOT NULL, player_name VARCHAR(32),"
                        + " npc_id " + k64 + " NOT NULL, player_text " + text + ", npc_text " + text + ", flagged INT NOT NULL,"
                        + " created_at BIGINT NOT NULL)" + suffix,
                "CREATE TABLE IF NOT EXISTS cnpc_events (id " + id + ", player_uuid " + k36 + " NOT NULL, player_name VARCHAR(32),"
                        + " npc_id " + k64 + " NOT NULL, type VARCHAR(32) NOT NULL, text " + text + " NOT NULL,"
                        + " created_at BIGINT NOT NULL, propagated INT NOT NULL)" + suffix,
                "CREATE TABLE IF NOT EXISTS cnpc_rumors (id " + id + ", event_id BIGINT NOT NULL, heard_by " + k64 + " NOT NULL,"
                        + " player_uuid " + k36 + " NOT NULL, text " + text + " NOT NULL, type VARCHAR(32) NOT NULL,"
                        + " event_created_at BIGINT NOT NULL, heard_at BIGINT NOT NULL, mentioned INT NOT NULL)" + suffix,
                "CREATE TABLE IF NOT EXISTS cnpc_vibe (id " + id + ", note " + text + " NOT NULL, keyword VARCHAR(100) NOT NULL,"
                        + " status VARCHAR(16) NOT NULL, created_at BIGINT NOT NULL, last_seen BIGINT NOT NULL)" + suffix,
                "CREATE TABLE IF NOT EXISTS cnpc_nickname (player_uuid " + k36 + " NOT NULL, npc_id " + k64 + " NOT NULL,"
                        + " nickname VARCHAR(32) NOT NULL, PRIMARY KEY (player_uuid, npc_id))" + suffix,
                "CREATE TABLE IF NOT EXISTS cnpc_quest_log (id " + id + ", player_uuid " + k36 + " NOT NULL, npc_id " + k64
                        + " NOT NULL, quest_id " + k64 + " NOT NULL, offered_at BIGINT NOT NULL, accepted INT NOT NULL,"
                        + " accepted_at BIGINT NOT NULL)" + suffix,
                "CREATE TABLE IF NOT EXISTS cnpc_meta (k VARCHAR(64) NOT NULL PRIMARY KEY, v " + text + ")" + suffix
        };
        String[] idx = {
                "CREATE INDEX IF NOT EXISTS idx_cnpc_memory ON cnpc_memory (player_uuid, npc_id)",
                "CREATE INDEX IF NOT EXISTS idx_cnpc_hint ON cnpc_hint_log (player_uuid)",
                "CREATE INDEX IF NOT EXISTS idx_cnpc_chat_time ON cnpc_chat_log (created_at)",
                "CREATE INDEX IF NOT EXISTS idx_cnpc_events_prop ON cnpc_events (propagated)",
                "CREATE INDEX IF NOT EXISTS idx_cnpc_events_player ON cnpc_events (player_uuid, npc_id, type)",
                "CREATE INDEX IF NOT EXISTS idx_cnpc_rumors ON cnpc_rumors (heard_by, player_uuid)",
                "CREATE INDEX IF NOT EXISTS idx_cnpc_quest ON cnpc_quest_log (player_uuid)"
        };
        exec(c -> {
            try (Statement st = c.createStatement()) {
                for (String s : ddl) {
                    st.execute(s);
                }
                for (String s : idx) {
                    try {
                        st.execute(s);
                    } catch (SQLException ignored) {
                        // 일부 MariaDB 버전은 IF NOT EXISTS 인덱스를 지원하지 않음 — 이미 있으면 무시
                    }
                }
                uniqueRumorIndex(st);
            }
            return null;
        });
    }

    /** Fail closed without modifying existing rumors; diagnostics never include row values. */
    static void uniqueRumorIndex(Statement st) throws SQLException {
        try {
            st.execute("CREATE UNIQUE INDEX IF NOT EXISTS uq_cnpc_rumor_event ON cnpc_rumors (event_id, heard_by)");
        } catch (SQLException failure) {
            String kind = switch (failure.getErrorCode()) {
                case 1062, 19, 2067 -> "constraint/duplicate";
                case 1044, 1045, 1142, 1143, 1227 -> "permission";
                case 1064 -> "syntax";
                default -> "database";
            };
            String duplicates;
            try (ResultSet rs = st.executeQuery("SELECT COUNT(*), COALESCE(SUM(n - 1), 0) FROM"
                    + " (SELECT COUNT(*) AS n FROM cnpc_rumors GROUP BY event_id, heard_by HAVING COUNT(*) > 1) duplicate_groups")) {
                if (!rs.next()) throw new SQLException("Missing aggregate result");
                duplicates = "duplicateGroups=" + rs.getLong(1) + ", excessRows=" + rs.getLong(2);
            } catch (SQLException diagnosticFailure) {
                duplicates = "duplicate counts unavailable (code=" + diagnosticFailure.getErrorCode() + ")";
            }
            // Do not attach the driver's message/cause: duplicate-key messages can contain player/NPC data.
            throw new SQLException("[ChacaNPC] Required cnpc_rumors UNIQUE index setup failed: " + kind
                    + " (code=" + failure.getErrorCode() + "); " + duplicates
                    + ". Existing rows were preserved; no automatic cleanup was performed."
                    + " Review database permissions/schema and resolve duplicates only with explicit approval."
                    + " Database initialization has not succeeded.", failure.getSQLState(), failure.getErrorCode());
        }
    }

    // ------------------------------------------------------------------ memory

    public List<String> memos(String player, String npc) {
        return exec(c -> {
            List<String> out = new ArrayList<>();
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT memo FROM cnpc_memory WHERE player_uuid=? AND npc_id=? ORDER BY id DESC LIMIT 10")) {
                ps.setString(1, player);
                ps.setString(2, npc);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        out.add(rs.getString(1));
                    }
                }
            }
            Collections.reverse(out);
            return out;
        });
    }

    public void addMemo(String player, String npc, String memo, int maxLines, long now) {
        exec(c -> {
            try (PreparedStatement ps = c.prepareStatement(
                    "INSERT INTO cnpc_memory (player_uuid, npc_id, memo, created_at) VALUES (?,?,?,?)")) {
                ps.setString(1, player);
                ps.setString(2, npc);
                ps.setString(3, memo);
                ps.setLong(4, now);
                ps.executeUpdate();
            }
            List<Long> ids = new ArrayList<>();
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT id FROM cnpc_memory WHERE player_uuid=? AND npc_id=? ORDER BY id DESC")) {
                ps.setString(1, player);
                ps.setString(2, npc);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        ids.add(rs.getLong(1));
                    }
                }
            }
            for (int i = maxLines; i < ids.size(); i++) {
                try (PreparedStatement ps = c.prepareStatement("DELETE FROM cnpc_memory WHERE id=?")) {
                    ps.setLong(1, ids.get(i));
                    ps.executeUpdate();
                }
            }
            return null;
        });
    }

    // ------------------------------------------------------------------ last chat

    public record LastChat(List<Turn> turns, long endedAt) {
    }

    public LastChat lastChat(String player, String npc) {
        return exec(c -> {
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT turns_json, ended_at FROM cnpc_last_chat WHERE player_uuid=? AND npc_id=?")) {
                ps.setString(1, player);
                ps.setString(2, npc);
                try (ResultSet rs = ps.executeQuery()) {
                    if (!rs.next()) {
                        return null;
                    }
                    List<Turn> turns = new ArrayList<>();
                    try {
                        List<Object> arr = Json.arr(Json.parse(rs.getString(1)));
                        if (arr != null) {
                            for (Object o : arr) {
                                Map<String, Object> m = Json.obj(o);
                                if (m != null) {
                                    turns.add(new Turn(Json.str(m, "s"), Json.str(m, "t")));
                                }
                            }
                        }
                    } catch (RuntimeException ignored) {
                        // 깨진 기록은 무시
                    }
                    return new LastChat(turns, rs.getLong(2));
                }
            }
        });
    }

    public void saveLastChat(String player, String npc, List<Turn> turns, long endedAt) {
        List<Object> arr = new ArrayList<>();
        for (Turn t : turns) {
            arr.add(Json.map("s", t.speaker(), "t", t.text()));
        }
        String json = Json.stringify(arr);
        exec(c -> {
            int n;
            try (PreparedStatement ps = c.prepareStatement(
                    "UPDATE cnpc_last_chat SET turns_json=?, ended_at=? WHERE player_uuid=? AND npc_id=?")) {
                ps.setString(1, json);
                ps.setLong(2, endedAt);
                ps.setString(3, player);
                ps.setString(4, npc);
                n = ps.executeUpdate();
            }
            if (n == 0) {
                try (PreparedStatement ps = c.prepareStatement(
                        "INSERT INTO cnpc_last_chat (player_uuid, npc_id, turns_json, ended_at) VALUES (?,?,?,?)")) {
                    ps.setString(1, player);
                    ps.setString(2, npc);
                    ps.setString(3, json);
                    ps.setLong(4, endedAt);
                    ps.executeUpdate();
                }
            }
            return null;
        });
    }

    // ------------------------------------------------------------------ hints

    public record HintState(Set<String> given, int today) {
    }

    public HintState hintState(String player, long dayStart) {
        return exec(c -> {
            Set<String> given = new HashSet<>();
            int today = 0;
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT hint_id, given_at FROM cnpc_hint_log WHERE player_uuid=?")) {
                ps.setString(1, player);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        given.add(rs.getString(1));
                        if (rs.getLong(2) >= dayStart) {
                            today++;
                        }
                    }
                }
            }
            return new HintState(given, today);
        });
    }

    public void addHint(String player, String hint, String npc, long now) {
        exec(c -> {
            try (PreparedStatement ps = c.prepareStatement(
                    "INSERT INTO cnpc_hint_log (player_uuid, hint_id, npc_id, given_at) VALUES (?,?,?,?)")) {
                ps.setString(1, player);
                ps.setString(2, hint);
                ps.setString(3, npc);
                ps.setLong(4, now);
                ps.executeUpdate();
            }
            return null;
        });
    }

    // ------------------------------------------------------------------ usage

    public void addUsage(String day, String player, int calls, long in, long cached, long out, double cost) {
        String sql = db.isMariaDb()
                ? "INSERT INTO cnpc_usage (day, player_uuid, calls, input_tokens, cached_tokens, output_tokens, cost_usd)"
                + " VALUES (?,?,?,?,?,?,?) ON DUPLICATE KEY UPDATE calls=calls+VALUES(calls), input_tokens=input_tokens+VALUES(input_tokens),"
                + " cached_tokens=cached_tokens+VALUES(cached_tokens), output_tokens=output_tokens+VALUES(output_tokens),"
                + " cost_usd=cost_usd+VALUES(cost_usd)"
                : "INSERT INTO cnpc_usage (day, player_uuid, calls, input_tokens, cached_tokens, output_tokens, cost_usd)"
                + " VALUES (?,?,?,?,?,?,?) ON CONFLICT(day, player_uuid) DO UPDATE SET calls=calls+excluded.calls,"
                + " input_tokens=input_tokens+excluded.input_tokens, cached_tokens=cached_tokens+excluded.cached_tokens,"
                + " output_tokens=output_tokens+excluded.output_tokens, cost_usd=cost_usd+excluded.cost_usd";
        exec(c -> {
            try (PreparedStatement ps = c.prepareStatement(sql)) {
                ps.setString(1, day);
                ps.setString(2, player);
                ps.setInt(3, calls);
                ps.setLong(4, in);
                ps.setLong(5, cached);
                ps.setLong(6, out);
                ps.setDouble(7, cost);
                ps.executeUpdate();
            }
            return null;
        });
    }

    /**
     * 하루 대화 횟수를 한도 미만일 때만 1 올린다. 행이 없으면 0으로 만든 뒤 조건부 UPDATE 한 번 — MariaDB·SQLite 모두
     * 한 문장이 원자적이라 여러 서버가 동시에 불러도 한도를 넘지 않는다. @return 올렸으면 true
     */
    public boolean tryAddCall(String day, String player, int limit) {
        return exec(c -> {
            try (PreparedStatement ps = c.prepareStatement((db.isMariaDb() ? "INSERT IGNORE INTO" : "INSERT OR IGNORE INTO")
                    + " cnpc_usage (day, player_uuid, calls, input_tokens, cached_tokens, output_tokens, cost_usd) VALUES (?,?,0,0,0,0,0)")) {
                ps.setString(1, day);
                ps.setString(2, player);
                ps.executeUpdate();
            }
            try (PreparedStatement ps = c.prepareStatement("UPDATE cnpc_usage SET calls=calls+1 WHERE day=? AND player_uuid=? AND calls<?")) {
                ps.setString(1, day);
                ps.setString(2, player);
                ps.setInt(3, limit);
                return ps.executeUpdate() == 1;
            }
        });
    }

    /** AI를 부르지 못한 호출의 횟수 되돌리기 (0 아래로는 내려가지 않음). */
    public void refundCall(String day, String player) {
        exec(c -> {
            try (PreparedStatement ps = c.prepareStatement("UPDATE cnpc_usage SET calls=calls-1 WHERE day=? AND player_uuid=? AND calls>0")) {
                ps.setString(1, day);
                ps.setString(2, player);
                ps.executeUpdate();
            }
            return null;
        });
    }

    public record UsageSnapshot(double spentBeforeToday, double spentToday, Map<String, Integer> callsToday) {
    }

    public UsageSnapshot usageSnapshot(String today) {
        return exec(c -> {
            double before = 0;
            double todaySpent = 0;
            Map<String, Integer> calls = new HashMap<>();
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT day, player_uuid, calls, cost_usd FROM cnpc_usage")) {
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        String day = rs.getString(1);
                        double cost = rs.getDouble(4);
                        int cmp = day.compareTo(today);
                        if (cmp < 0) {
                            before += cost;
                        } else if (cmp == 0) {
                            todaySpent += cost;
                            calls.put(rs.getString(2), rs.getInt(3));
                        }
                    }
                }
            }
            return new UsageSnapshot(before, todaySpent, calls);
        });
    }

    // ------------------------------------------------------------------ chat log

    public void addChat(String player, String playerName, String npc, String playerText, String npcText,
                        boolean flagged, long now) {
        exec(c -> {
            try (PreparedStatement ps = c.prepareStatement(
                    "INSERT INTO cnpc_chat_log (player_uuid, player_name, npc_id, player_text, npc_text, flagged, created_at)"
                            + " VALUES (?,?,?,?,?,?,?)")) {
                ps.setString(1, player);
                ps.setString(2, playerName);
                ps.setString(3, npc);
                ps.setString(4, playerText);
                ps.setString(5, npcText);
                ps.setInt(6, flagged ? 1 : 0);
                ps.setLong(7, now);
                ps.executeUpdate();
            }
            return null;
        });
    }

    public record PlayerLine(String player, String text) {
    }

    public List<PlayerLine> recentPlayerLines(long since, int limit) {
        return exec(c -> {
            List<PlayerLine> out = new ArrayList<>();
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT player_uuid, player_text FROM cnpc_chat_log WHERE created_at>=? AND flagged=0"
                            + " AND player_text IS NOT NULL ORDER BY id DESC LIMIT ?")) {
                ps.setLong(1, since);
                ps.setInt(2, limit);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        out.add(new PlayerLine(rs.getString(1), rs.getString(2)));
                    }
                }
            }
            return out;
        });
    }

    // ------------------------------------------------------------------ events & rumors

    public record EventRow(long id, String player, String playerName, String npc, String type, String text, long createdAt) {
    }

    public void addEvent(String player, String playerName, String npc, String type, String text, long now) {
        exec(c -> {
            try (PreparedStatement ps = c.prepareStatement(
                    "INSERT INTO cnpc_events (player_uuid, player_name, npc_id, type, text, created_at, propagated)"
                            + " VALUES (?,?,?,?,?,?,0)")) {
                ps.setString(1, player);
                ps.setString(2, playerName);
                ps.setString(3, npc);
                ps.setString(4, type);
                ps.setString(5, text);
                ps.setLong(6, now);
                ps.executeUpdate();
            }
            return null;
        });
    }

    public List<EventRow> unpropagatedEvents(int limit) {
        return exec(c -> {
            List<EventRow> out = new ArrayList<>();
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT id, player_uuid, player_name, npc_id, type, text, created_at FROM cnpc_events"
                            + " WHERE propagated=0 ORDER BY id LIMIT ?")) {
                ps.setInt(1, limit);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        out.add(new EventRow(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getString(4),
                                rs.getString(5), rs.getString(6), rs.getLong(7)));
                    }
                }
            }
            return out;
        });
    }

    /**
     * 사건 하나를 소문으로 퍼뜨리기: 소문 줄 추가 + "퍼뜨림" 표시를 한 트랜잭션으로 한다.
     * 중간에 실패하면 전부 되돌아가므로 다음 번에 다시 해도 소문이 두 번 생기지 않는다
     * (UNIQUE (event_id, heard_by) 가 있으면 한 번 더 막힌다).
     *
     * @param heardAt 소문을 듣는 NPC id → 듣게 되는 시각
     */
    public void propagateEvent(EventRow e, Map<String, Long> heardAt) {
        String insert = (db.isMariaDb() ? "INSERT IGNORE INTO" : "INSERT OR IGNORE INTO")
                + " cnpc_rumors (event_id, heard_by, player_uuid, text, type, event_created_at, heard_at, mentioned)"
                + " VALUES (?,?,?,?,?,?,?,0)";
        try {
            db.tx(c -> {
                if (!heardAt.isEmpty()) {
                    try (PreparedStatement ps = c.prepareStatement(insert)) {
                        for (Map.Entry<String, Long> h : heardAt.entrySet()) {
                            ps.setLong(1, e.id());
                            ps.setString(2, h.getKey());
                            ps.setString(3, e.player());
                            ps.setString(4, e.text());
                            ps.setString(5, e.type());
                            ps.setLong(6, e.createdAt());
                            ps.setLong(7, h.getValue());
                            ps.executeUpdate();
                        }
                    }
                }
                try (PreparedStatement ps = c.prepareStatement("UPDATE cnpc_events SET propagated=1 WHERE id=?")) {
                    ps.setLong(1, e.id());
                    ps.executeUpdate();
                }
                return null;
            });
        } catch (SQLException ex) {
            throw new RuntimeException(ex.getMessage(), ex);
        }
    }

    /** 1:1 대화용 소문 한 줄 + 그 사건의 NPC (원문을 서버 문장으로 바꿀 때 쓴다. 사건이 지워졌으면 null). */
    public record HeardRumor(RumorView view, String sourceNpc) {
    }

    public List<HeardRumor> rumorsFor(String npc, String player, long now, int limit) {
        return exec(c -> {
            List<HeardRumor> out = new ArrayList<>();
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT r.id, r.text, r.type, r.event_created_at, e.npc_id FROM cnpc_rumors r"
                            + " LEFT JOIN cnpc_events e ON e.id=r.event_id WHERE r.heard_by=? AND r.player_uuid=?"
                            + " AND r.heard_at<=? AND r.mentioned=0 ORDER BY r.event_created_at DESC LIMIT ?")) {
                ps.setString(1, npc);
                ps.setString(2, player);
                ps.setLong(3, now);
                ps.setInt(4, limit);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        out.add(new HeardRumor(new RumorView(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getLong(4)),
                                rs.getString(5)));
                    }
                }
            }
            return out;
        });
    }

    /** 공개 잡담용 소문: 사건 종류와 그 사건의 NPC만 (원문·플레이어 정보는 꺼내지 않는다). */
    public record PublicRumor(String type, String sourceNpc) {
    }

    /** 잡담 연출용: 이 NPC가 들은 소문 중 근처 플레이어에 관한 최근 것. 원문은 읽지 않는다. */
    public List<PublicRumor> publicRumorsHeardBy(String npc, Set<String> players, long now, int limit) {
        if (players.isEmpty()) {
            return List.of();
        }
        return exec(c -> {
            List<PublicRumor> out = new ArrayList<>();
            StringBuilder in = new StringBuilder();
            for (int i = 0; i < players.size(); i++) {
                in.append(i == 0 ? "?" : ",?");
            }
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT r.type, e.npc_id FROM cnpc_rumors r LEFT JOIN cnpc_events e ON e.id=r.event_id"
                            + " WHERE r.heard_by=? AND r.heard_at<=? AND r.player_uuid IN (" + in + ")"
                            + " ORDER BY r.event_created_at DESC LIMIT ?")) {
                int i = 1;
                ps.setString(i++, npc);
                ps.setLong(i++, now);
                for (String p : players) {
                    ps.setString(i++, p);
                }
                ps.setInt(i, limit);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        out.add(new PublicRumor(rs.getString(1), rs.getString(2)));
                    }
                }
            }
            return out;
        });
    }

    public void markRumorMentioned(long id) {
        exec(c -> {
            try (PreparedStatement ps = c.prepareStatement("UPDATE cnpc_rumors SET mentioned=1 WHERE id=?")) {
                ps.setLong(1, id);
                ps.executeUpdate();
            }
            return null;
        });
    }

    /** 이 플레이어가 이 NPC에게 했던 특별한 말(promise) 목록. */
    public List<String> promises(String player, String npc, int limit) {
        return exec(c -> {
            List<String> out = new ArrayList<>();
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT text FROM cnpc_events WHERE player_uuid=? AND npc_id=? AND type='promise' ORDER BY id DESC LIMIT ?")) {
                ps.setString(1, player);
                ps.setString(2, npc);
                ps.setInt(3, limit);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        out.add(rs.getString(1));
                    }
                }
            }
            return out;
        });
    }

    // ------------------------------------------------------------------ vibe

    public record VibeRow(long id, String note, String keyword, String status, long lastSeen) {
    }

    public List<VibeRow> vibes(String status) {
        return exec(c -> {
            List<VibeRow> out = new ArrayList<>();
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT id, note, keyword, status, last_seen FROM cnpc_vibe WHERE status=? ORDER BY id")) {
                ps.setString(1, status);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        out.add(new VibeRow(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getLong(5)));
                    }
                }
            }
            return out;
        });
    }

    /** 같은 키워드가 이미 있으면 last_seen만 갱신하고 false, 새로 넣으면 true. */
    public boolean upsertVibe(String note, String keyword, String status, long now) {
        return exec(c -> {
            try (PreparedStatement ps = c.prepareStatement(
                    "UPDATE cnpc_vibe SET last_seen=? WHERE keyword=? AND status IN ('candidate','approved')")) {
                ps.setLong(1, now);
                ps.setString(2, keyword);
                if (ps.executeUpdate() > 0) {
                    return false;
                }
            }
            try (PreparedStatement ps = c.prepareStatement(
                    "INSERT INTO cnpc_vibe (note, keyword, status, created_at, last_seen) VALUES (?,?,?,?,?)")) {
                ps.setString(1, note);
                ps.setString(2, keyword);
                ps.setString(3, status);
                ps.setLong(4, now);
                ps.setLong(5, now);
                ps.executeUpdate();
            }
            return true;
        });
    }

    public void setVibeStatus(long id, String status) {
        exec(c -> {
            try (PreparedStatement ps = c.prepareStatement("UPDATE cnpc_vibe SET status=? WHERE id=?")) {
                ps.setString(1, status);
                ps.setLong(2, id);
                ps.executeUpdate();
            }
            return null;
        });
    }

    public void setAllVibeStatus(String from, String to) {
        exec(c -> {
            try (PreparedStatement ps = c.prepareStatement("UPDATE cnpc_vibe SET status=? WHERE status=?")) {
                ps.setString(1, to);
                ps.setString(2, from);
                ps.executeUpdate();
            }
            return null;
        });
    }

    public void expireVibes(long before) {
        exec(c -> {
            try (PreparedStatement ps = c.prepareStatement(
                    "UPDATE cnpc_vibe SET status='expired' WHERE status IN ('candidate','approved') AND last_seen<?")) {
                ps.setLong(1, before);
                ps.executeUpdate();
            }
            return null;
        });
    }

    // ------------------------------------------------------------------ nickname

    public String nickname(String player, String npc) {
        return exec(c -> {
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT nickname, npc_id FROM cnpc_nickname WHERE player_uuid=? AND (npc_id=? OR npc_id='*')")) {
                ps.setString(1, player);
                ps.setString(2, npc);
                String global = null;
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        if ("*".equals(rs.getString(2))) {
                            global = rs.getString(1);
                        } else {
                            return rs.getString(1);
                        }
                    }
                }
                return global;
            }
        });
    }

    public void setNickname(String player, String npc, String nickname) {
        exec(c -> {
            try (PreparedStatement ps = c.prepareStatement("DELETE FROM cnpc_nickname WHERE player_uuid=? AND npc_id=?")) {
                ps.setString(1, player);
                ps.setString(2, npc);
                ps.executeUpdate();
            }
            if (nickname != null) {
                try (PreparedStatement ps = c.prepareStatement(
                        "INSERT INTO cnpc_nickname (player_uuid, npc_id, nickname) VALUES (?,?,?)")) {
                    ps.setString(1, player);
                    ps.setString(2, npc);
                    ps.setString(3, nickname);
                    ps.executeUpdate();
                }
            }
            return null;
        });
    }

    // ------------------------------------------------------------------ quests

    public record QuestState(long lastAcceptedFromNpc, Set<String> accepted, int acceptedLastDay) {
    }

    public QuestState questState(String player, String npc, long dayAgo) {
        return exec(c -> {
            long last = 0;
            Set<String> accepted = new HashSet<>();
            int recent = 0;
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT npc_id, quest_id, accepted_at FROM cnpc_quest_log WHERE player_uuid=? AND accepted=1")) {
                ps.setString(1, player);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        accepted.add(rs.getString(2));
                        long at = rs.getLong(3);
                        if (npc.equals(rs.getString(1)) && at > last) {
                            last = at;
                        }
                        if (at >= dayAgo) {
                            recent++;
                        }
                    }
                }
            }
            return new QuestState(last, accepted, recent);
        });
    }

    public void addQuestLog(String player, String npc, String quest, long offeredAt, boolean accepted, long acceptedAt) {
        exec(c -> {
            try (PreparedStatement ps = c.prepareStatement(
                    "INSERT INTO cnpc_quest_log (player_uuid, npc_id, quest_id, offered_at, accepted, accepted_at)"
                            + " VALUES (?,?,?,?,?,?)")) {
                ps.setString(1, player);
                ps.setString(2, npc);
                ps.setString(3, quest);
                ps.setLong(4, offeredAt);
                ps.setInt(5, accepted ? 1 : 0);
                ps.setLong(6, acceptedAt);
                ps.executeUpdate();
            }
            return null;
        });
    }

    // ------------------------------------------------------------------ meta

    public String meta(String key) {
        return exec(c -> {
            try (PreparedStatement ps = c.prepareStatement("SELECT v FROM cnpc_meta WHERE k=?")) {
                ps.setString(1, key);
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next() ? rs.getString(1) : null;
                }
            }
        });
    }

    public void setMeta(String key, String value) {
        exec(c -> {
            try (PreparedStatement ps = c.prepareStatement("DELETE FROM cnpc_meta WHERE k=?")) {
                ps.setString(1, key);
                ps.executeUpdate();
            }
            try (PreparedStatement ps = c.prepareStatement("INSERT INTO cnpc_meta (k, v) VALUES (?,?)")) {
                ps.setString(1, key);
                ps.setString(2, value);
                ps.executeUpdate();
            }
            return null;
        });
    }

    // ------------------------------------------------------------------ 관리 (/유저관리 <닉네임> AI대화)

    public record MemoRow(long id, String npc, String memo, long createdAt) {
    }

    /** 한 유저의 기억 줄 (npc가 null이면 모든 NPC). 번호(id) 순. */
    public List<MemoRow> memoRows(String player, String npc) {
        return exec(c -> {
            List<MemoRow> out = new ArrayList<>();
            String sql = "SELECT id, npc_id, memo, created_at FROM cnpc_memory WHERE player_uuid=?"
                    + (npc == null ? "" : " AND npc_id=?") + " ORDER BY npc_id, id";
            try (PreparedStatement ps = c.prepareStatement(sql)) {
                ps.setString(1, player);
                if (npc != null) {
                    ps.setString(2, npc);
                }
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        out.add(new MemoRow(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getLong(4)));
                    }
                }
            }
            return out;
        });
    }

    /** 이 유저의 기억 한 줄 삭제. 지웠으면 true. */
    public boolean deleteMemo(String player, long id) {
        return exec(c -> {
            try (PreparedStatement ps = c.prepareStatement("DELETE FROM cnpc_memory WHERE id=? AND player_uuid=?")) {
                ps.setLong(1, id);
                ps.setString(2, player);
                return ps.executeUpdate() > 0;
            }
        });
    }

    /** 기억(메모)과 지난 대화 기록을 지운다 (npc가 null이면 모든 NPC). 지운 기억 줄 수. */
    public int clearMemory(String player, String npc) {
        return exec(c -> {
            String where = " WHERE player_uuid=?" + (npc == null ? "" : " AND npc_id=?");
            int n;
            try (PreparedStatement ps = c.prepareStatement("DELETE FROM cnpc_memory" + where)) {
                ps.setString(1, player);
                if (npc != null) {
                    ps.setString(2, npc);
                }
                n = ps.executeUpdate();
            }
            try (PreparedStatement ps = c.prepareStatement("DELETE FROM cnpc_last_chat" + where)) {
                ps.setString(1, player);
                if (npc != null) {
                    ps.setString(2, npc);
                }
                ps.executeUpdate();
            }
            return n;
        });
    }

    /** NPC id("*" = 모든 NPC 공통) → 호칭. */
    public Map<String, String> nicknames(String player) {
        return exec(c -> {
            Map<String, String> out = new HashMap<>();
            try (PreparedStatement ps = c.prepareStatement("SELECT npc_id, nickname FROM cnpc_nickname WHERE player_uuid=?")) {
                ps.setString(1, player);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        out.put(rs.getString(1), rs.getString(2));
                    }
                }
            }
            return out;
        });
    }

    /** NPC id → 마지막 대화 끝난 시각. */
    public Map<String, Long> lastChatTimes(String player) {
        return exec(c -> {
            Map<String, Long> out = new HashMap<>();
            try (PreparedStatement ps = c.prepareStatement("SELECT npc_id, ended_at FROM cnpc_last_chat WHERE player_uuid=?")) {
                ps.setString(1, player);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        out.put(rs.getString(1), rs.getLong(2));
                    }
                }
            }
            return out;
        });
    }

    public record UsageRow(String day, int calls, long inputTokens, long cachedTokens, long outputTokens, double cost) {
    }

    /** 한 유저의 날짜별 AI 사용량 (최근 날짜부터 limit일). */
    public List<UsageRow> usage(String player, int limit) {
        return exec(c -> {
            List<UsageRow> out = new ArrayList<>();
            try (PreparedStatement ps = c.prepareStatement("SELECT day, calls, input_tokens, cached_tokens, output_tokens, cost_usd"
                    + " FROM cnpc_usage WHERE player_uuid=? ORDER BY day DESC LIMIT ?")) {
                ps.setString(1, player);
                ps.setInt(2, limit);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        out.add(new UsageRow(rs.getString(1), rs.getInt(2), rs.getLong(3), rs.getLong(4), rs.getLong(5), rs.getDouble(6)));
                    }
                }
            }
            return out;
        });
    }

    // ------------------------------------------------------------------ util

    private <T> T exec(Database.SqlFunction<T> fn) {
        try {
            return db.with(fn);
        } catch (SQLException ex) {
            throw new RuntimeException(ex.getMessage(), ex);
        }
    }
}
