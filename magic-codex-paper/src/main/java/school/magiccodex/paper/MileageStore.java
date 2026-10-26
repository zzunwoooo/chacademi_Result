package school.magiccodex.paper;

import java.nio.file.Path;
import java.sql.*;
import java.util.*;
import school.magiccodex.database.*;
import school.magiccodex.protocol.MileageProtocol;

/**
 * 마일리지 상점 저장소 (2026-10-24). Same dedicated DB as the shop/mailbox (shop-mailbox-database.properties), so with the
 * shared MariaDB school and wild see one balance, one catalogue and one order ledger.
 *
 * Balance debit and the order row are one DB transaction (state "paid"). Mail is created afterwards with source key
 * "mileage:&lt;order&gt;" (unique in the mailbox), then the order becomes "done". A "paid" order is therefore always safe to
 * resend: the mailbox returns the original mail instead of a second one. Callers use this class from one IO thread.
 */
final class MileageStore implements AutoCloseable {
    record Product(String id, int category, String name, String description, long price, boolean enabled, int sort, byte[] bytes) {}
    record Order(String id, UUID owner, String product, int quantity, long total, byte[] bytes, String title, String state, long created) {}
    /** Buy outcome: order != null on success (also for a replayed request), otherwise message says why. */
    record Bought(Order order, long balance, String message) {}

    private final ConnectionHolder holder;
    private final boolean maria;
    private final String balances, products, orders;

    MileageStore(DatabaseSettings settings, Path sqlite) throws Exception {
        holder = settings.holder(sqlite);
        maria = settings.mariaDb();
        balances = settings.table("mileage_balance", "mileage_balance");
        products = settings.table("mileage_products", "mileage_products");
        orders = settings.table("mileage_orders", "mileage_orders");
        String tail = maria ? " ENGINE=InnoDB DEFAULT CHARSET=utf8mb4" : "";
        try (var s = db().createStatement()) {
            s.execute("CREATE TABLE IF NOT EXISTS " + balances + "(owner VARCHAR(36) PRIMARY KEY,amount BIGINT NOT NULL,updated BIGINT NOT NULL)" + tail);
            s.execute("CREATE TABLE IF NOT EXISTS " + products + "(id VARCHAR(36) PRIMARY KEY,category INTEGER NOT NULL,name VARCHAR(80) NOT NULL,"
                    + "description VARCHAR(160) NOT NULL,price BIGINT NOT NULL,enabled INTEGER NOT NULL,sort INTEGER NOT NULL,payload BLOB NOT NULL)" + tail);
            s.execute("CREATE TABLE IF NOT EXISTS " + orders + "(id VARCHAR(36) PRIMARY KEY,owner VARCHAR(36) NOT NULL,request_key VARCHAR(80) NOT NULL UNIQUE,"
                    + "product VARCHAR(36) NOT NULL,quantity INTEGER NOT NULL,total BIGINT NOT NULL,payload BLOB NOT NULL,title VARCHAR(80) NOT NULL,"
                    + "state VARCHAR(12) NOT NULL,created BIGINT NOT NULL)" + tail);
        }
        for (String sql : List.of("CREATE INDEX IF NOT EXISTS " + orders + "_owner_state ON " + orders + "(owner,state)",
                "CREATE INDEX IF NOT EXISTS " + orders + "_state ON " + orders + "(state)"))
            try (var s = db().createStatement()) { s.execute(sql); } catch (SQLException ignored) { /* 색인은 성능용 */ }
    }

    private Connection db() throws SQLException { return holder.get(); }
    private interface Work<T> { T run(Connection c) throws Exception; }
    private <T> T tx(Work<T> work) throws Exception {
        var c = holder.begin();
        try { T out = work.run(c); c.commit(); return out; }
        catch (Exception e) { holder.rollback(); throw e; }
        finally { holder.end(); }
    }
    private static boolean duplicate(SQLException e) { return e.getErrorCode() == 19 || e.getErrorCode() == 1062 || "23000".equals(e.getSQLState()) || "23505".equals(e.getSQLState()); }

    // ------------------------------------------------------------------ balance

    long balance(UUID owner) throws SQLException {
        try (var p = db().prepareStatement("SELECT amount FROM " + balances + " WHERE owner=?")) {
            p.setString(1, owner.toString());
            try (var r = p.executeQuery()) { return r.next() ? r.getLong(1) : 0; }
        }
    }
    /** Locks (MariaDB) and returns the balance inside a transaction, creating the row at 0. */
    private long lock(Connection c, UUID owner) throws SQLException {
        try (var p = c.prepareStatement("INSERT INTO " + balances + "(owner,amount,updated) VALUES(?,0,?)")) {
            p.setString(1, owner.toString()); p.setLong(2, System.currentTimeMillis()); p.executeUpdate();
        } catch (SQLException e) { if (!duplicate(e)) throw e; }
        try (var p = c.prepareStatement("SELECT amount FROM " + balances + " WHERE owner=?" + (maria ? " FOR UPDATE" : ""))) {
            p.setString(1, owner.toString());
            try (var r = p.executeQuery()) { if (!r.next()) throw new SQLException("balance row"); return r.getLong(1); }
        }
    }
    private void put(Connection c, UUID owner, long amount) throws SQLException {
        try (var p = c.prepareStatement("UPDATE " + balances + " SET amount=?,updated=? WHERE owner=?")) {
            p.setLong(1, amount); p.setLong(2, System.currentTimeMillis()); p.setString(3, owner.toString());
            if (p.executeUpdate() != 1) throw new SQLException("balance update");
        }
    }
    /** Admin change. mode: 설정 / 추가 / 빼기. Returns {before, after}; a result below 0 or above the ceiling is refused. */
    long[] change(UUID owner, String mode, long value) throws Exception {
        return tx(c -> {
            long before = lock(c, owner);
            long after = switch (mode) {
                case "설정" -> value;
                case "추가" -> before + value;
                case "빼기" -> before - value;
                default -> throw new IllegalArgumentException("mode");
            };
            if (after < 0) throw new IllegalStateException("마일리지가 " + MileageProtocol.amount(before) + "라서 그만큼 뺄 수 없어요");
            if (after > MileageProtocol.MAX_BALANCE) throw new IllegalStateException("마일리지는 최대 " + MileageProtocol.amount(MileageProtocol.MAX_BALANCE) + "까지예요");
            put(c, owner, after);
            return new long[]{before, after};
        });
    }

    // ------------------------------------------------------------------ catalogue

    List<Product> products() throws SQLException {
        var out = new ArrayList<Product>();
        try (var s = db().createStatement(); var r = s.executeQuery("SELECT id,category,name,description,price,enabled,sort,payload FROM " + products + " ORDER BY category,sort,id")) {
            while (r.next()) out.add(new Product(r.getString(1), r.getInt(2), r.getString(3), r.getString(4), r.getLong(5), r.getInt(6) != 0, r.getInt(7), r.getBytes(8)));
        }
        return out;
    }
    private int nextSort(Connection c, int category) throws SQLException {
        try (var p = c.prepareStatement("SELECT MAX(sort) FROM " + products + " WHERE category=?")) {
            p.setInt(1, category);
            try (var r = p.executeQuery()) { return r.next() ? r.getInt(1) + 1 : 0; }
        }
    }
    String add(int category, String name, String description, long price, boolean enabled, byte[] bytes) throws Exception {
        String id = UUID.randomUUID().toString();
        tx(c -> {
            try (var p = c.prepareStatement("INSERT INTO " + products + "(id,category,name,description,price,enabled,sort,payload) VALUES(?,?,?,?,?,?,?,?)")) {
                p.setString(1, id); p.setInt(2, category); p.setString(3, name); p.setString(4, description); p.setLong(5, price);
                p.setInt(6, enabled ? 1 : 0); p.setInt(7, nextSort(c, category)); p.setBytes(8, bytes); p.executeUpdate();
            }
            return null;
        });
        return id;
    }
    /** false when the product no longer exists. A category change moves it to the end of the new category. */
    boolean save(String id, int category, String name, String description, long price, boolean enabled) throws Exception {
        return tx(c -> {
            int old;
            try (var p = c.prepareStatement("SELECT category FROM " + products + " WHERE id=?")) {
                p.setString(1, id);
                try (var r = p.executeQuery()) { if (!r.next()) return false; old = r.getInt(1); }
            }
            boolean moved = old != category;
            try (var p = c.prepareStatement("UPDATE " + products + " SET category=?,name=?,description=?,price=?,enabled=?" + (moved ? ",sort=?" : "") + " WHERE id=?")) {
                int i = 1;
                p.setInt(i++, category); p.setString(i++, name); p.setString(i++, description); p.setLong(i++, price); p.setInt(i++, enabled ? 1 : 0);
                if (moved) p.setInt(i++, nextSort(c, category));
                p.setString(i, id);
                return p.executeUpdate() == 1;
            }
        });
    }
    boolean replaceItem(String id, byte[] bytes) throws SQLException {
        try (var p = db().prepareStatement("UPDATE " + products + " SET payload=? WHERE id=?")) { p.setBytes(1, bytes); p.setString(2, id); return p.executeUpdate() == 1; }
    }
    boolean delete(String id) throws SQLException {
        try (var p = db().prepareStatement("DELETE FROM " + products + " WHERE id=?")) { p.setString(1, id); return p.executeUpdate() == 1; }
    }
    /** Swaps the product with its neighbour in the same category. false when it is already first/last or gone. */
    boolean move(String id, boolean later) throws Exception {
        return tx(c -> {
            var list = new ArrayList<String[]>();
            int category = -1;
            try (var p = c.prepareStatement("SELECT category FROM " + products + " WHERE id=?")) {
                p.setString(1, id);
                try (var r = p.executeQuery()) { if (!r.next()) return false; category = r.getInt(1); }
            }
            try (var p = c.prepareStatement("SELECT id FROM " + products + " WHERE category=? ORDER BY sort,id")) {
                p.setInt(1, category);
                try (var r = p.executeQuery()) { while (r.next()) list.add(new String[]{r.getString(1)}); }
            }
            int at = -1;
            for (int i = 0; i < list.size(); i++) if (list.get(i)[0].equals(id)) at = i;
            int to = at + (later ? 1 : -1);
            if (at < 0 || to < 0 || to >= list.size()) return false;
            var a = list.get(at); list.set(at, list.get(to)); list.set(to, a);
            // Renumber the whole category so equal/old sort values can never make the swap a no-op.
            try (var p = c.prepareStatement("UPDATE " + products + " SET sort=? WHERE id=?")) {
                for (int i = 0; i < list.size(); i++) { p.setInt(1, i); p.setString(2, list.get(i)[0]); p.addBatch(); }
                p.executeBatch();
            }
            return true;
        });
    }

    // ------------------------------------------------------------------ orders

    private static final String ORDER_COLUMNS = "id,owner,product,quantity,total,payload,title,state,created";
    private static Order order(ResultSet r) throws SQLException {
        return new Order(r.getString(1), UUID.fromString(r.getString(2)), r.getString(3), r.getInt(4), r.getLong(5), r.getBytes(6), r.getString(7), r.getString(8), r.getLong(9));
    }

    /**
     * Debits the balance and records a "paid" order in one transaction. requestKey makes a repeated request return the first
     * order (no second debit). The product must be on sale at exactly expectedPrice.
     */
    Bought buy(UUID owner, String requestKey, String productId, int quantity, long expectedPrice) throws Exception {
        return tx(c -> {
            try (var p = c.prepareStatement("SELECT " + ORDER_COLUMNS + " FROM " + orders + " WHERE request_key=?")) {
                p.setString(1, requestKey);
                try (var r = p.executeQuery()) { if (r.next()) { var o = order(r); return new Bought(o.owner().equals(owner) ? o : null, lock(c, owner), o.owner().equals(owner) ? "" : "요청을 다시 보내 주세요"); } }
            }
            String name; long price; boolean enabled; byte[] bytes;
            try (var p = c.prepareStatement("SELECT name,price,enabled,payload FROM " + products + " WHERE id=?")) {
                p.setString(1, productId);
                try (var r = p.executeQuery()) {
                    if (!r.next()) return new Bought(null, balance(owner), "판매가 끝난 상품이에요");
                    name = r.getString(1); price = r.getLong(2); enabled = r.getInt(3) != 0; bytes = r.getBytes(4);
                }
            }
            long before = lock(c, owner);
            if (!enabled) return new Bought(null, before, "판매가 끝난 상품이에요");
            if (price != expectedPrice) return new Bought(null, before, "가격이 바뀌었어요. 다시 확인해 주세요");
            long total = MileageProtocol.total(price, quantity);
            if (total < 0) return new Bought(null, before, "구매 수량을 줄여 주세요");
            if (total > before) return new Bought(null, before, "마일리지가 부족해요");
            put(c, owner, before - total);
            String id = UUID.randomUUID().toString();
            long now = System.currentTimeMillis();
            try (var p = c.prepareStatement("INSERT INTO " + orders + "(" + ORDER_COLUMNS + ",request_key) VALUES(?,?,?,?,?,?,?,?,?,?)")) {
                p.setString(1, id); p.setString(2, owner.toString()); p.setString(3, productId); p.setInt(4, quantity); p.setLong(5, total);
                p.setBytes(6, bytes); p.setString(7, name); p.setString(8, "paid"); p.setLong(9, now); p.setString(10, requestKey);
                p.executeUpdate();
            }
            return new Bought(new Order(id, owner, productId, quantity, total, bytes, name, "paid", now), before - total, "");
        });
    }
    void done(String orderId) throws SQLException {
        try (var p = db().prepareStatement("UPDATE " + orders + " SET state='done' WHERE id=? AND state='paid'")) { p.setString(1, orderId); p.executeUpdate(); }
    }
    /** Paid orders whose mail is not confirmed yet (owner null = everyone), oldest first. */
    List<Order> paid(UUID owner, int limit) throws SQLException {
        String sql = "SELECT " + ORDER_COLUMNS + " FROM " + orders + " WHERE state='paid'" + (owner == null ? "" : " AND owner=?") + " ORDER BY created LIMIT " + Math.max(1, limit);
        var out = new ArrayList<Order>();
        try (var p = db().prepareStatement(sql)) {
            if (owner != null) p.setString(1, owner.toString());
            try (var r = p.executeQuery()) { while (r.next()) out.add(order(r)); }
        }
        return out;
    }
    List<Order> recent(UUID owner, int limit) throws SQLException {
        var out = new ArrayList<Order>();
        try (var p = db().prepareStatement("SELECT " + ORDER_COLUMNS + " FROM " + orders + " WHERE owner=? ORDER BY created DESC LIMIT " + Math.max(1, limit))) {
            p.setString(1, owner.toString());
            try (var r = p.executeQuery()) { while (r.next()) out.add(order(r)); }
        }
        return out;
    }

    @Override public void close() throws SQLException { holder.close(); }
}
