package kr.chacademy.npc.data;

import school.magiccodex.database.ConnectionHolder;
import school.magiccodex.database.DatabaseSettings;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * ChacaNPC DB. 다른 MagicCodex 플러그인과 같은 {@link DatabaseSettings} 형식을 쓴다.
 * 설정 파일: plugins/ChacaNPC/database.properties (없으면 SQLite).
 * SQLite fallback 경로: plugins/ChacaNPC/chaca-npc.db (명시 경로).
 * MariaDB는 기존 인스턴스의 전용 스키마(예: chacademi_npc)를 권장한다. 테이블은 모두 cnpc_ 접두어.
 * 모든 쿼리는 전용 스레드 하나에서 순서대로 실행한다.
 */
public final class Database {

    private final Logger log;
    private final DatabaseSettings settings;
    private final ConnectionHolder holder;
    private final ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "ChacaNPC-DB");
        t.setDaemon(true);
        return t;
    });

    public Database(Path dataFolder, Logger log) throws Exception {
        this.log = log;
        this.settings = DatabaseSettings.load(dataFolder.resolve("database.properties"));
        Path sqlite = dataFolder.resolve("chaca-npc.db");
        try {
            this.holder = executor.submit(() -> settings.holder(sqlite)).get(15, TimeUnit.SECONDS);
        } catch (Exception e) {
            executor.shutdownNow();
            throw e;
        }
    }

    public boolean isMariaDb() {
        return settings.mariaDb();
    }

    public String autoId() {
        return isMariaDb() ? "BIGINT AUTO_INCREMENT PRIMARY KEY" : "INTEGER PRIMARY KEY AUTOINCREMENT";
    }

    public String textType() {
        return isMariaDb() ? "TEXT CHARACTER SET utf8mb4" : "TEXT";
    }

    public String keyType(int len) {
        return "VARCHAR(" + len + ")";
    }

    /** 커넥션 사용 (DB 스레드 안에서만 호출). */
    public <T> T with(SqlFunction<T> fn) throws SQLException {
        return fn.apply(holder.get());
    }

    /** 한 트랜잭션으로 실행 (DB 스레드 안에서만 호출). 실패하면 전부 되돌리고 예외를 다시 던진다. */
    public <T> T tx(SqlFunction<T> fn) throws SQLException {
        Connection c = holder.begin();
        try {
            T out = fn.apply(c);
            c.commit();
            return out;
        } catch (SQLException | RuntimeException ex) {
            try {
                holder.rollback();
            } catch (SQLException ignored) {
                // 연결이 끊긴 경우: 다음 사용 때 새로 연결된다
            }
            throw ex;
        } finally {
            holder.end();
        }
    }

    @FunctionalInterface
    public interface SqlFunction<T> {
        T apply(Connection c) throws SQLException;
    }

    /** DB 스레드에서 실행. 실패하면 로그 남기고 null. */
    public <T> CompletableFuture<T> async(Supplier<T> task) {
        try {
            return CompletableFuture.supplyAsync(() -> {
                try {
                    return task.get();
                } catch (RuntimeException ex) {
                    log.log(Level.WARNING, "[ChacaNPC] DB 작업 실패: " + ex.getMessage(), ex);
                    return null;
                }
            }, executor);
        } catch (java.util.concurrent.RejectedExecutionException ex) {
            return CompletableFuture.completedFuture(null);
        }
    }

    /** DB 스레드에서 실행. {@link #async}와 달리 실패를 삼키지 않고 예외로 완료한다 (관리 명령이 실패 알림을 보내도록). */
    public <T> CompletableFuture<T> call(Supplier<T> task) {
        try {
            return CompletableFuture.supplyAsync(task, executor);
        } catch (java.util.concurrent.RejectedExecutionException ex) {
            return CompletableFuture.failedFuture(ex);
        }
    }

    /** Storage 가 치명적이지 않은 문제를 알릴 때 쓴다. */
    void logWarning(String message) {
        log.warning(message);
    }

    public CompletableFuture<Void> run(Runnable task) {
        return async(() -> {
            task.run();
            return null;
        });
    }

    public void close() {
        executor.shutdown();
        try {
            if (!executor.awaitTermination(15, TimeUnit.SECONDS)) {
                log.warning("[ChacaNPC] DB 종료 대기 시간 초과");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        try {
            holder.close();
        } catch (SQLException ignored) {
            // 무시
        }
    }
}
