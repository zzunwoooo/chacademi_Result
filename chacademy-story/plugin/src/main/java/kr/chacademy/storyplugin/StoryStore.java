package kr.chacademy.storyplugin;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * progress.yml 쓰기 전담 (쓰기 스레드 하나). 메인 스레드는 상태 복사본만 넘기고 파일을 건드리지 않는다.
 * <ul>
 *   <li>쓰는 동안 들어온 요청은 가장 새 복사본 하나로 합친다 (100명에게 한꺼번에 열어도 쓰기는 한 번)</li>
 *   <li>"저장된 뒤 실행" 콜백은 그 내용이 디스크에 끝난 다음 메인 스레드에서 돈다. 쓰기에 실패하면 돌지 않고
 *       {@value #RETRY_SECONDS}초 뒤 다시 쓴다 (성공할 때까지 효과는 실행되지 않는다)</li>
 *   <li>서버가 꺼질 때는 {@link #flushNow} 로 그 자리에서 쓴다</li>
 * </ul>
 */
final class StoryStore {
    static final int RETRY_SECONDS = 2;

    interface Sink {
        void write(String contents) throws IOException;
    }

    private final Sink sink;
    private final ScheduledExecutorService worker;
    private final Executor main;
    private final Logger log;
    private final Object lock = new Object();
    private Supplier<String> queued;
    private final List<Runnable> queuedCallbacks = new ArrayList<>();
    private boolean running;
    private boolean closed;
    private long lastFailureLog;

    /** main = 메인 스레드에서 실행해 주는 것 (플러그인이 꺼지는 중이면 예외를 던져도 된다). */
    StoryStore(Sink sink, ScheduledExecutorService worker, Executor main, Logger log) {
        this.sink = sink;
        this.worker = worker;
        this.main = main;
        this.log = log;
    }

    static ScheduledExecutorService newWorker() {
        ScheduledThreadPoolExecutor ex = new ScheduledThreadPoolExecutor(1, r -> {
            Thread t = new Thread(r, "ChacademyStory-progress-writer");
            t.setDaemon(true);
            return t;
        });
        // 꺼질 때 재시도 대기 때문에 기다리지 않게
        ex.setExecuteExistingDelayedTasksAfterShutdownPolicy(false);
        return ex;
    }

    /** render 는 쓰기 스레드에서 불린다 (넘긴 복사본만 읽을 것). afterDurable 은 저장이 끝난 뒤 메인 스레드에서. */
    void submit(Supplier<String> render, Collection<Runnable> afterDurable) {
        synchronized (lock) {
            queued = render;
            queuedCallbacks.addAll(afterDurable);
            if (running || closed) return;
            running = true;
        }
        try {
            worker.execute(this::drain);
        } catch (RuntimeException e) {
            synchronized (lock) {
                running = false;
            }
            log.log(Level.SEVERE, "스토리 진행 저장을 시작하지 못했습니다", e);
        }
    }

    private void drain() {
        while (true) {
            Supplier<String> job;
            List<Runnable> callbacks;
            synchronized (lock) {
                if (queued == null || closed) {
                    running = false;
                    return;
                }
                job = queued;
                queued = null;
                callbacks = new ArrayList<>(queuedCallbacks);
                queuedCallbacks.clear();
            }
            boolean ok;
            try {
                sink.write(job.get());
                ok = true;
            } catch (IOException | RuntimeException e) {
                ok = false;
                long now = System.currentTimeMillis();
                if (now - lastFailureLog > 30_000) {
                    lastFailureLog = now;
                    log.log(Level.SEVERE, "progress.yml 저장 실패 — 저장될 때까지 스토리 효과(명령어·호감도)를 실행하지 않고 " + RETRY_SECONDS + "초마다 다시 시도합니다", e);
                }
            }
            if (ok) {
                if (!callbacks.isEmpty()) {
                    try {
                        main.execute(() -> {
                            for (Runnable r : callbacks) {
                                try {
                                    r.run();
                                } catch (RuntimeException e) {
                                    log.log(Level.SEVERE, "스토리 효과 실행 중 오류", e);
                                }
                            }
                        });
                    } catch (RuntimeException e) {
                        // 플러그인이 꺼지는 중: 효과는 progress.yml 의 effects 에 남아 있어 다음 접속 때 실행된다
                    }
                }
                continue;
            }
            synchronized (lock) {
                if (queued == null) queued = job;
                queuedCallbacks.addAll(0, callbacks);
                if (closed) {
                    running = false;
                    return;
                }
            }
            try {
                worker.schedule(this::drain, RETRY_SECONDS, TimeUnit.SECONDS);
            } catch (RuntimeException e) {
                synchronized (lock) {
                    running = false;
                }
            }
            return;
        }
    }

    /**
     * 서버가 꺼질 때: 쓰기 스레드를 멈추고 (쓰던 것은 끝까지 기다림) 마지막 상태를 이 스레드에서 바로 쓴다.
     * 기다리던 콜백은 실행하지 않는다 (effects 에 남아 다음 접속 때 실행).
     */
    boolean flushNow(Supplier<String> render) {
        synchronized (lock) {
            closed = true;
            queued = null;
            queuedCallbacks.clear();
        }
        worker.shutdown();
        try {
            if (!worker.awaitTermination(15, TimeUnit.SECONDS)) log.warning("스토리 진행 쓰기 스레드가 제때 끝나지 않았습니다");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        try {
            sink.write(render.get());
            return true;
        } catch (IOException | RuntimeException e) {
            log.log(Level.SEVERE, "progress.yml 마지막 저장 실패", e);
            return false;
        }
    }
}
