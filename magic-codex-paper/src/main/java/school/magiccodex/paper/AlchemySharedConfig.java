package school.magiccodex.paper;

import java.sql.Connection;
import java.util.concurrent.*;
import java.util.function.Consumer;
import org.bukkit.Bukkit;
import school.magiccodex.database.DatabaseSettings;

/**
 * alchemy.yml shared by school and wild (owner 2026-10-17: "레시피 설정은 두 서버가 동일해야 함").
 *
 * The whole file lives in the existing shared MariaDB (the player-state connection, same as shared artifacts) as one
 * row codex_alchemy_config('alchemy') with a revision. The local plugins/MagicCodexBridge/alchemy.yml is only this
 * server's copy: on start the shared text replaces it (the very first server seeds the row from its file); every
 * 5 seconds each server checks the revision and applies a newer text; the recipe editor and /연금술관리 새로고침
 * publish with a revision check, so two servers editing at once cannot silently overwrite each other.
 * Without a MariaDB player-state connection (test servers) it stays local-only and says so. JDBC never runs on the
 * main thread except the one-time start check.
 */
final class AlchemySharedConfig implements AutoCloseable {
    interface Apply { String apply(String text); }      // main thread: write the local file + reload, error text or null

    private static final String ID = "alchemy";
    private final MagicCodexBridge plugin;
    private final DatabaseSettings settings;
    private final Apply apply;
    private final ExecutorService io = Executors.newSingleThreadExecutor(r -> { var t = new Thread(r, "MagicCodex-alchemy-config"); t.setDaemon(true); return t; });
    private final org.bukkit.scheduler.BukkitTask timer;
    private volatile long revision = -1;
    private volatile boolean closed, polling, warned;

    AlchemySharedConfig(MagicCodexBridge plugin, String localText, Apply apply) {
        this.plugin = plugin; this.apply = apply;
        DatabaseSettings s = null;
        try {
            var folder = plugin.getDataFolder().toPath();
            var dedicated = folder.resolve("player-state-database.properties");
            s = DatabaseSettings.load(java.nio.file.Files.exists(dedicated) ? dedicated : folder.resolve("database.properties"));
            if (!s.mariaDb()) s = null;
        } catch (Exception e) { s = null; }
        settings = s;
        if (settings == null) {
            plugin.getLogger().warning("연금술 설정 공유 꺼짐: MariaDB 공유 연결(player-state-database.properties)이 없어 이 서버의 alchemy.yml 만 씁니다.");
            timer = null; return;
        }
        // one-time start: create/seed the row and take the shared text
        try (var c = open(); var st = c.createStatement()) {
            st.execute("CREATE TABLE IF NOT EXISTS codex_alchemy_config(id VARCHAR(32) PRIMARY KEY,revision BIGINT NOT NULL,content MEDIUMTEXT NOT NULL,updated_by VARCHAR(64) NOT NULL,updated_at BIGINT NOT NULL) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE utf8mb4_unicode_ci");
            try (var p = c.prepareStatement("INSERT IGNORE INTO codex_alchemy_config(id,revision,content,updated_by,updated_at) VALUES(?,1,?,?,?)")) {
                p.setString(1, ID); p.setString(2, localText); p.setString(3, "seed:" + Bukkit.getServer().getPort()); p.setLong(4, System.currentTimeMillis());
                if (p.executeUpdate() == 1) plugin.getLogger().info("연금술 설정을 공유 DB에 처음 올렸습니다 (이 서버의 alchemy.yml 기준).");
            }
            var row = read(c);
            if (row != null) {
                revision = row.revision();
                if (!row.content().equals(localText)) {
                    String error = apply.apply(row.content());
                    if (error != null) plugin.getLogger().severe("공유 연금술 설정을 적용하지 못했습니다 (로컬 파일 유지): " + error);
                    else plugin.getLogger().info("공유 연금술 설정(revision " + revision + ")을 적용했습니다.");
                }
            }
        } catch (Exception e) {
            plugin.getLogger().warning("연금술 설정 공유 DB 연결 실패, 잠시 뒤 다시 확인합니다: " + e.getMessage());
            warned = true;
        }
        timer = Bukkit.getScheduler().runTaskTimer(plugin, this::poll, 100, 100);
    }

    boolean shared() { return settings != null; }

    private Connection open() throws Exception { return settings.connect(null); }

    private record Row(long revision, String content) {}
    private static Row read(Connection c) throws Exception {
        try (var p = c.prepareStatement("SELECT revision,content FROM codex_alchemy_config WHERE id=?")) {
            p.setString(1, ID);
            try (var r = p.executeQuery()) { return r.next() ? new Row(r.getLong(1), r.getString(2)) : null; }
        }
    }

    /** Another server changed the shared text: apply it here. */
    private void poll() {
        if (closed || polling) return;
        polling = true;
        long known = revision;
        try {
            io.execute(() -> {
                Row row = null; Exception failure = null;
                try (var c = open()) { row = read(c); } catch (Exception e) { failure = e; }
                Row result = row; Exception error = failure;
                main(() -> {
                    polling = false;
                    if (error != null) { if (!warned) { warned = true; plugin.getLogger().warning("연금술 설정 공유 DB 확인 실패: " + error.getMessage()); } return; }
                    if (warned) { warned = false; plugin.getLogger().info("연금술 설정 공유 DB 연결이 돌아왔습니다."); }
                    if (result == null || result.revision() <= known || revision != known) return;
                    String failed = apply.apply(result.content());
                    revision = result.revision();
                    if (failed != null) plugin.getLogger().severe("다른 서버의 연금술 설정(revision " + result.revision() + ")을 적용하지 못했습니다: " + failed);
                    else plugin.getLogger().info("다른 서버에서 바뀐 연금술 설정(revision " + result.revision() + ")을 적용했습니다.");
                });
            });
        } catch (RejectedExecutionException e) { polling = false; }
    }

    /**
     * Publish a text this server already applied locally. done gets null on success or the reason it was not shared
     * (the caller then restores its previous local text). Local-only servers succeed at once.
     */
    void publish(String text, String by, Consumer<String> done) {
        if (settings == null) { done.accept(null); return; }
        long expected = revision;
        try {
            io.execute(() -> {
                String error = null; long next = expected;
                try (var c = open(); var p = c.prepareStatement("UPDATE codex_alchemy_config SET content=?,revision=revision+1,updated_by=?,updated_at=? WHERE id=? AND revision=?")) {
                    p.setString(1, text); p.setString(2, by.length() > 64 ? by.substring(0, 64) : by); p.setLong(3, System.currentTimeMillis()); p.setString(4, ID); p.setLong(5, expected);
                    if (p.executeUpdate() == 1) next = expected + 1;
                    else error = "다른 서버에서 먼저 바뀌었습니다. 잠시 뒤 화면을 다시 열어 확인해 주세요.";
                } catch (Exception e) { error = "공유 DB 저장 실패: " + e.getMessage(); }
                String e2 = error; long n = next;
                main(() -> { if (e2 == null) revision = n; done.accept(e2); });
            });
        } catch (RejectedExecutionException e) { done.accept("서버가 종료 중입니다."); }
    }

    private void main(Runnable r) { if (!closed) Bukkit.getScheduler().runTask(plugin, () -> { if (!closed) r.run(); }); }

    @Override public void close() {
        closed = true;
        if (timer != null) timer.cancel();
        io.shutdown();
        try { if (!io.awaitTermination(5, TimeUnit.SECONDS)) io.shutdownNow(); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }
}
