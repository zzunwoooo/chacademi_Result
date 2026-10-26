package school.magiccodex.paper;

import java.nio.file.Path;
import java.sql.*;
import java.util.*;
import java.util.random.RandomGenerator;
import school.magiccodex.database.ConnectionHolder;
import school.magiccodex.database.DatabaseSettings;

/**
 * 「행운의 뽑기판」 저장소 (§44). 청크 점유·칭호와 같은 방식: database.properties(GenericDatabaseFile)를 따라 MariaDB 면
 * 학교·야생·경기장이 같이 쓰는 공유 표, 아니면 이 서버의 draw-board.db (SQLite). 저장소 전용 작업 스레드 하나만 부른다.
 *
 * <pre>
 * codex_draw_board (id=1 한 줄) revision · board_round · items_revision · layout(아이템 id 100개, 쉼표) · revealed("0"/"1" 100글자)
 *                                · completed_at(100칸 다 공개된 때, 0 = 진행 중) · updated_at · updated_by
 * codex_draw_item  id(UUID) PK · added_at · added_by · item(ItemStack 원본 바이트 serializeAsBytes)
 * </pre>
 * 모든 변경은 판 줄의 revision 비교(UPDATE … WHERE revision=?)로만 확정한다 → 두 서버의 운영자가 동시에 눌러도 한쪽 변경이 다른 쪽을
 * 덮지 않고, 졌으면 최신 줄을 다시 읽어 다시 계산한다 (뒤집기는 "이 칸을 공개/가림"이라 다시 계산해도 뜻이 같음).
 * 아이템 등록·삭제는 같은 거래 안에서 판 줄 revision 도 올리므로 목록과 판이 어긋나지 않는다. 다음 판 넘기기(100칸 공개 뒤)는
 * "끝난 판의 그 revision" 일 때만 성공 → 여러 서버가 동시에 시도해도 정확히 한 번.
 */
final class DrawBoardStore implements AutoCloseable {
    record Item(String id, long addedAt, String addedBy, byte[] bytes) {}
    enum AddResult { ADDED, FULL }
    record Added(AddResult result, DrawBoardRules.Board board) {}
    record Flipped(DrawBoardRules.Flip flip, DrawBoardRules.Board board) {}

    /** ItemStack 원본 바이트 상한 (책·셜커 상자 같은 큰 아이템도 들어가게 넉넉히, MEDIUMBLOB). */
    static final int MAX_ITEM_BYTES = 1 << 20;

    private final ConnectionHolder holder;
    private final boolean maria;
    private final RandomGenerator random;

    DrawBoardStore(DatabaseSettings settings, Path sqlite, RandomGenerator random) throws Exception {
        this.maria = settings.mariaDb();
        this.random = random;
        this.holder = settings.holder(sqlite);
        try (var s = db().createStatement()) {
            String tail = maria ? " ENGINE=InnoDB DEFAULT CHARSET=utf8mb4" : "";
            s.execute("CREATE TABLE IF NOT EXISTS codex_draw_board(id INT PRIMARY KEY,revision BIGINT NOT NULL,board_round INT NOT NULL,items_revision BIGINT NOT NULL,"
                    + "layout TEXT NOT NULL,revealed VARCHAR(100) NOT NULL,completed_at BIGINT NOT NULL,updated_at BIGINT NOT NULL,updated_by VARCHAR(64) NOT NULL)" + tail);
            s.execute("CREATE TABLE IF NOT EXISTS codex_draw_item(id VARCHAR(36) PRIMARY KEY,added_at BIGINT NOT NULL,added_by VARCHAR(64) NOT NULL,item "
                    + (maria ? "MEDIUMBLOB" : "BLOB") + " NOT NULL)" + tail);
        }
        try (var s = db().prepareStatement((maria ? "INSERT IGNORE" : "INSERT OR IGNORE")
                + " INTO codex_draw_board(id,revision,board_round,items_revision,layout,revealed,completed_at,updated_at,updated_by) VALUES(1,0,0,0,'',?,0,0,'')")) {
            s.setString(1, DrawBoardRules.HIDDEN);
            s.executeUpdate();
        }
    }

    private Connection db() throws SQLException { return holder.get(); }
    boolean shared() { return maria; }

    // ------------------------------------------------------------------ 읽기

    DrawBoardRules.Board board() throws SQLException {
        try (var s = db().prepareStatement("SELECT revision,board_round,items_revision,layout,revealed,completed_at FROM codex_draw_board WHERE id=1");
             var r = s.executeQuery()) {
            if (!r.next()) throw new SQLException("codex_draw_board 줄이 없음");
            String revealed = r.getString(5);
            var layout = DrawBoardRules.split(r.getString(4));
            return new DrawBoardRules.Board(r.getLong(1), r.getInt(2), r.getLong(3), layout,
                    DrawBoardRules.validRevealed(revealed) ? revealed : DrawBoardRules.HIDDEN, layout.isEmpty() ? 0 : r.getLong(6));
        }
    }

    /** 등록 목록 (등록 순서). 이 순서의 번호가 클라 Catalog 번호다. */
    List<Item> items() throws SQLException {
        var out = new ArrayList<Item>();
        try (var s = db().prepareStatement("SELECT id,added_at,added_by,item FROM codex_draw_item ORDER BY added_at,id"); var r = s.executeQuery()) {
            while (r.next()) out.add(new Item(r.getString(1), r.getLong(2), r.getString(3), r.getBytes(4)));
        }
        return out;
    }

    private List<String> ids() throws SQLException {
        var out = new ArrayList<String>();
        try (var s = db().prepareStatement("SELECT id FROM codex_draw_item ORDER BY added_at,id"); var r = s.executeQuery()) { while (r.next()) out.add(r.getString(1)); }
        return out;
    }

    // ------------------------------------------------------------------ 바꾸기 (거래 + revision 비교)

    /** 판 줄의 새 값. null = 바꾸지 않음. */
    private interface Change { DrawBoardRules.Board apply(DrawBoardRules.Board current) throws Exception; }

    /** 거래 하나: 판 줄 읽기 → 바꿀 값 계산(목록 넣기·지우기 포함) → revision 비교 UPDATE. 다른 서버가 먼저 바꿨으면 되돌리고 다시. */
    private DrawBoardRules.Board mutate(String by, long now, Change change) throws Exception {
        for (int attempt = 0; ; attempt++) {
            holder.begin();
            try {
                var current = board();
                var next = change.apply(current);
                if (next == null) { db().commit(); return current; }
                int updated;
                try (var s = db().prepareStatement("UPDATE codex_draw_board SET revision=?,board_round=?,items_revision=?,layout=?,revealed=?,completed_at=?,updated_at=?,updated_by=? WHERE id=1 AND revision=?")) {
                    s.setLong(1, current.revision() + 1); s.setInt(2, next.round()); s.setLong(3, next.itemsRevision());
                    s.setString(4, DrawBoardRules.join(next.layout())); s.setString(5, next.revealed()); s.setLong(6, next.completedAt());
                    s.setLong(7, now); s.setString(8, by.length() > 64 ? by.substring(0, 64) : by); s.setLong(9, current.revision());
                    updated = s.executeUpdate();
                }
                if (updated != 1) { holder.rollback(); if (attempt < 8) { Thread.sleep(5L * (attempt + 1)); continue; } throw new SQLException("뽑기판이 계속 다른 곳에서 바뀌어 저장하지 못함"); }
                db().commit();
                return new DrawBoardRules.Board(current.revision() + 1, next.round(), next.itemsRevision(), next.layout(), next.revealed(), next.completedAt());
            } catch (Exception e) {
                holder.rollback();
                if (attempt < 3 && e instanceof SQLException s && (s.getErrorCode() == 1213 || s.getErrorCode() == 1205 || "40001".equals(s.getSQLState()))) { Thread.sleep(10L * (attempt + 1)); continue; }
                throw e;
            } finally { holder.end(); }
        }
    }

    /** 목록이 바뀐 뒤의 판: 정확히 100개면 섞어서 다음 회차(모두 가림), 아니면 판 없음. */
    private DrawBoardRules.Board fresh(DrawBoardRules.Board b, List<String> ids, long itemsRevision) {
        if (ids.size() != DrawBoardRules.CELLS) return new DrawBoardRules.Board(b.revision(), b.round(), itemsRevision, List.of(), DrawBoardRules.HIDDEN, 0);
        return new DrawBoardRules.Board(b.revision(), b.round() + 1, itemsRevision, DrawBoardRules.shuffle(ids, random), DrawBoardRules.HIDDEN, 0);
    }

    /** 칸 뒤집기. 100칸이 다 공개되면 completed_at = now (다음 판은 {@link #finishRound} 가). */
    Flipped flip(int round, int cell, boolean reveal, String by, long now) throws Exception {
        var result = new DrawBoardRules.Flip[1];
        var after = mutate(by, now, b -> {
            var f = DrawBoardRules.flip(b, round, cell, reveal);
            result[0] = f;
            if (f.outcome() != DrawBoardRules.Outcome.CHANGED) return null;
            return new DrawBoardRules.Board(b.revision(), b.round(), b.itemsRevision(), b.layout(), f.revealed(), f.nowComplete() ? Math.max(1, now) : 0);
        });
        return new Flipped(result[0], after);
    }

    /** 끝난 판(그 revision 그대로일 때만)을 같은 100개로 다시 섞어 다음 회차로. 이미 누가 넘겼으면 그대로 돌려준다 → 정확히 한 번. */
    DrawBoardRules.Board finishRound(long expectRevision, String by, long now) throws Exception {
        return mutate(by, now, b -> {
            if (b.revision() != expectRevision || !b.complete() || !b.ready()) return null;
            return new DrawBoardRules.Board(b.revision(), b.round() + 1, b.itemsRevision(), DrawBoardRules.reshuffle(b.layout(), random), DrawBoardRules.HIDDEN, 0);
        });
    }

    /** /뽑기판 초기화 · 관리 창 「새로 섞기」: 등록 목록을 다시 읽어 새 판 (100개가 아니면 판 없음). */
    DrawBoardRules.Board reshuffle(String by, long now) throws Exception {
        return mutate(by, now, b -> {
            var ids = ids();
            if (ids.size() != DrawBoardRules.CELLS) return b.ready() ? new DrawBoardRules.Board(b.revision(), b.round(), b.itemsRevision(), List.of(), DrawBoardRules.HIDDEN, 0) : null;
            return new DrawBoardRules.Board(b.revision(), b.round() + 1, b.itemsRevision(), DrawBoardRules.shuffle(ids, random), DrawBoardRules.HIDDEN, 0);
        });
    }

    /** 등록 (100개면 FULL). 목록이 바뀌면 판을 새로: 100개가 되는 순간 섞어서 시작, 그 밖에는 판 없음. */
    Added add(byte[] item, String by, long now) throws Exception {
        if (item.length == 0 || item.length > MAX_ITEM_BYTES) throw new IllegalArgumentException("아이템 데이터가 너무 커요 (" + item.length + " 바이트)");
        var result = new AddResult[1];
        var after = mutate(by, now, b -> {
            var ids = new ArrayList<>(ids());
            if (ids.size() >= DrawBoardRules.CELLS) { result[0] = AddResult.FULL; return null; }
            String id = UUID.randomUUID().toString();
            try (var s = db().prepareStatement("INSERT INTO codex_draw_item(id,added_at,added_by,item) VALUES(?,?,?,?)")) {
                s.setString(1, id); s.setLong(2, now); s.setString(3, by.length() > 64 ? by.substring(0, 64) : by); s.setBytes(4, item);
                s.executeUpdate();
            }
            ids.add(id);
            result[0] = AddResult.ADDED;
            return fresh(b, ids, b.itemsRevision() + 1);
        });
        return new Added(result[0], after);
    }

    /** 등록 지우기 (없으면 그대로). 지우면 99개 이하라 판은 멈춘다. */
    DrawBoardRules.Board remove(String id, String by, long now) throws Exception {
        return mutate(by, now, b -> {
            int deleted;
            try (var s = db().prepareStatement("DELETE FROM codex_draw_item WHERE id=?")) { s.setString(1, id); deleted = s.executeUpdate(); }
            if (deleted == 0) return null;
            return fresh(b, ids(), b.itemsRevision() + 1);
        });
    }

    /** 등록 전부 지우기. */
    DrawBoardRules.Board clear(String by, long now) throws Exception {
        return mutate(by, now, b -> {
            int deleted;
            try (var s = db().prepareStatement("DELETE FROM codex_draw_item")) { deleted = s.executeUpdate(); }
            if (deleted == 0 && !b.ready()) return null;
            return new DrawBoardRules.Board(b.revision(), b.round(), b.itemsRevision() + 1, List.of(), DrawBoardRules.HIDDEN, 0);
        });
    }

    @Override public void close() throws SQLException { holder.close(); }
}
