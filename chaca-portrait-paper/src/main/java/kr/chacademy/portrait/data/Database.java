package kr.chacademy.portrait.data;

import school.magiccodex.database.ConnectionHolder;
import school.magiccodex.database.DatabaseSettings;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;

/**
 * ChacaPortrait DB. 다른 MagicCodex 플러그인과 같은 {@link DatabaseSettings} 형식을 쓴다.
 * 설정 파일: plugins/ChacaPortrait/database.properties (없으면 SQLite).
 * SQLite fallback 경로: plugins/ChacaPortrait/chaca-portrait.db (명시 경로).
 * MariaDB는 기존 인스턴스의 전용 스키마(예: chacademi_portrait)를 권장한다. 테이블은 모두 cport_ 접두어.
 * 모든 쿼리는 전용 스레드 하나에서 순서대로 실행한다.
 */
public final class Database {

    private final Logger log;
    private final DatabaseSettings settings;
    private final ConnectionHolder holder;
    private final ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "ChacaPortrait-DB");
        t.setDaemon(true);
        return t;
    });

    public Database(Path dataFolder, Logger log) throws Exception {
        this.log = log;
        this.settings = DatabaseSettings.load(dataFolder.resolve("database.properties"));
        Path sqlite = dataFolder.resolve("chaca-portrait.db");
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

    /** 커넥션 사용 (DB 스레드 안에서만 호출). */
    public <T> T with(SqlFunction<T> fn) throws SQLException {
        return fn.apply(holder.get());
    }

    @FunctionalInterface
    public interface SqlFunction<T> {
        T apply(Connection c) throws SQLException;
    }

    @FunctionalInterface
    public interface SqlCall<T> {
        T call() throws Exception;
    }

    /** DB 스레드에서 실행. 실패하면 예외로 완료 (호출 측이 처리). */
    public <T> CompletableFuture<T> call(SqlCall<T> task) {
        CompletableFuture<T> f = new CompletableFuture<>();
        try {
            executor.execute(() -> {
                try {
                    f.complete(task.call());
                } catch (Throwable ex) {
                    f.completeExceptionally(ex);
                }
            });
        } catch (java.util.concurrent.RejectedExecutionException ex) {
            f.completeExceptionally(ex);
        }
        return f;
    }

    public void close() {
        executor.shutdown();
        try {
            if (!executor.awaitTermination(15, TimeUnit.SECONDS)) {
                log.warning("[ChacaPortrait] DB 종료 대기 시간 초과");
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
