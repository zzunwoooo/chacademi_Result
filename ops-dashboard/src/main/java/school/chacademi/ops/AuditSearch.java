package school.chacademi.ops;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 운영 기록 표(codex_audit_log, 다른 작업이 만드는 중) 검색 — 닉·종류·서버·기간·글자. 읽기만 한다.
 * 열 이름은 설정(audit.col.*)으로 맞춘다. 시각 열 형식(밀리초/초/DATETIME)은 audit.time-unit=auto 면 첫 검색 때 알아낸다.
 */
final class AuditSearch {
    record Filter(String name, String kind, String server, String from, String to, String text, int limit) { }

    record Query(String sql, List<Object> params) { }

    enum TimeUnitKind { MILLIS, SECONDS, DATETIME }

    private static final DateTimeFormatter INPUT = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm");
    private static final DateTimeFormatter SHOW = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private final Config config;
    private final Db db;
    private final ZoneId zone;
    private volatile TimeUnitKind unit;
    private volatile List<String> kindsCache = List.of();
    private volatile long kindsAt;

    AuditSearch(Config config, Db db) {
        this.config = config;
        this.db = db;
        this.zone = ZoneId.of(config.zone);
        this.unit = switch (config.auditTimeUnit) {
            case "millis" -> TimeUnitKind.MILLIS;
            case "seconds" -> TimeUnitKind.SECONDS;
            case "datetime" -> TimeUnitKind.DATETIME;
            default -> null;
        };
    }

    /** 순수 함수: 검색 SQL + 인자. 표·열 이름은 Config 에서 이미 [A-Za-z0-9_] 로 검사됨. */
    static Query build(String table, Map<String, String> col, Filter f, TimeUnitKind unit, ZoneId zone, int maxRows) {
        List<String> where = new ArrayList<>();
        List<Object> params = new ArrayList<>();
        if (!blank(f.name()) && !col.get("name").isEmpty()) {
            String n = f.name().trim();
            if (n.matches("[0-9a-fA-F-]{32,36}") && !col.get("uuid").isEmpty()) { where.add(col.get("uuid") + " = ?"); params.add(n.toLowerCase()); }
            else { where.add(col.get("name") + " LIKE ?"); params.add(like(n)); }
        }
        if (!blank(f.kind()) && !col.get("kind").isEmpty()) { where.add(col.get("kind") + " = ?"); params.add(f.kind().trim()); }
        if (!blank(f.server()) && !col.get("server").isEmpty()) { where.add(col.get("server") + " = ?"); params.add(f.server().trim()); }
        if (!blank(f.from())) { where.add(col.get("time") + " >= ?"); params.add(timeParam(f.from(), unit, zone)); }
        if (!blank(f.to())) { where.add(col.get("time") + " <= ?"); params.add(timeParam(f.to(), unit, zone)); }
        if (!blank(f.text())) {
            List<String> ors = new ArrayList<>();
            for (String k : new String[]{"message", "detail", "name"}) {
                if (col.get(k).isEmpty()) continue;
                ors.add(col.get(k) + " LIKE ?");
                params.add(like(f.text().trim()));
            }
            if (!ors.isEmpty()) where.add("(" + String.join(" OR ", ors) + ")");
        }
        List<String> select = new ArrayList<>();
        for (String k : new String[]{"time", "server", "uuid", "name", "kind", "message", "detail"}) {
            String c = col.get(k);
            select.add(c.isEmpty() ? "NULL AS c_" + k : c + " AS c_" + k);
        }
        int limit = Math.max(1, Math.min(maxRows, f.limit() <= 0 ? 100 : f.limit()));
        String sql = "SELECT " + String.join(", ", select) + " FROM " + table
                + (where.isEmpty() ? "" : " WHERE " + String.join(" AND ", where))
                + " ORDER BY " + col.get("time") + " DESC LIMIT " + limit;
        return new Query(sql, params);
    }

    static String like(String s) {
        String esc = s.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
        return "%" + esc + "%";
    }

    static Object timeParam(String input, TimeUnitKind unit, ZoneId zone) {
        LocalDateTime t = LocalDateTime.parse(input.trim().length() == 10 ? input.trim() + "T00:00" : input.trim().substring(0, 16), INPUT);
        Instant at = t.atZone(zone).toInstant();
        return switch (unit) {
            case MILLIS -> at.toEpochMilli();
            case SECONDS -> at.getEpochSecond();
            case DATETIME -> Timestamp.from(at);
        };
    }

    private static boolean blank(String s) { return s == null || s.isBlank(); }

    List<Map<String, Object>> search(Filter f) throws SQLException {
        if (db == null) throw new SQLException("DB 설정 없음");
        return db.with(c -> {
            TimeUnitKind u = detect(c);
            Query q = build(config.auditTable, config.auditColumns, f, u, zone, config.auditMaxRows);
            List<Map<String, Object>> out = new ArrayList<>();
            try (PreparedStatement p = c.prepareStatement(q.sql())) {
                p.setQueryTimeout(10);
                for (int i = 0; i < q.params().size(); i++) p.setObject(i + 1, q.params().get(i));
                try (ResultSet r = p.executeQuery()) {
                    while (r.next()) {
                        Map<String, Object> row = new LinkedHashMap<>();
                        row.put("time", showTime(r.getObject("c_time"), u));
                        for (String k : new String[]{"server", "uuid", "name", "kind", "message", "detail"}) {
                            Object v = r.getObject("c_" + k);
                            row.put(k, v == null ? "" : String.valueOf(v));
                        }
                        out.add(row);
                    }
                }
            }
            return out;
        });
    }

    private TimeUnitKind detect(java.sql.Connection c) throws SQLException {
        if (unit != null) return unit;
        String col = config.auditColumns.get("time");
        try (var st = c.createStatement(); ResultSet r = st.executeQuery("SELECT " + col + " FROM " + config.auditTable + " WHERE 1=0")) {
            int type = r.getMetaData().getColumnType(1);
            if (type == Types.TIMESTAMP || type == Types.DATE || type == Types.TIMESTAMP_WITH_TIMEZONE || type == Types.TIME) {
                unit = TimeUnitKind.DATETIME;
                return unit;
            }
        }
        try (var st = c.createStatement(); ResultSet r = st.executeQuery("SELECT MAX(" + col + ") FROM " + config.auditTable)) {
            long max = r.next() ? r.getLong(1) : 0;
            // 밀리초면 2001년 이후 값이 1e12 를 넘는다. 비어 있으면 일단 밀리초로 보고 다음에 다시 본다.
            if (max == 0) return TimeUnitKind.MILLIS;
            unit = max > 100_000_000_000L ? TimeUnitKind.MILLIS : TimeUnitKind.SECONDS;
            return unit;
        }
    }

    private String showTime(Object v, TimeUnitKind u) {
        if (v == null) return "";
        if (v instanceof Timestamp ts) return LocalDateTime.ofInstant(ts.toInstant(), zone).format(SHOW);
        if (v instanceof LocalDateTime ldt) return ldt.format(SHOW);
        if (v instanceof Number n) {
            Instant at = u == TimeUnitKind.SECONDS ? Instant.ofEpochSecond(n.longValue()) : Instant.ofEpochMilli(n.longValue());
            return LocalDateTime.ofInstant(at, zone).format(SHOW);
        }
        return String.valueOf(v);
    }

    /** 종류 고르기 목록 (1분 캐시). */
    List<String> kinds() {
        String kindCol = config.auditColumns.get("kind");
        if (db == null || kindCol.isEmpty()) return List.of();
        if (System.currentTimeMillis() - kindsAt < 60_000) return kindsCache;
        try {
            kindsCache = db.with(c -> {
                List<String> out = new ArrayList<>();
                try (var st = c.createStatement()) {
                    st.setQueryTimeout(5);
                    try (ResultSet r = st.executeQuery("SELECT DISTINCT " + kindCol + " FROM " + config.auditTable + " ORDER BY " + kindCol + " LIMIT 200")) {
                        while (r.next()) { String k = r.getString(1); if (k != null) out.add(k); }
                    }
                }
                return out;
            });
        } catch (SQLException e) { kindsCache = List.of(); }
        kindsAt = System.currentTimeMillis();
        return kindsCache;
    }
}
