package kr.chacademy.portrait;

import kr.chacademy.portrait.ai.OpenAiImageClient;
import kr.chacademy.portrait.cmd.PortraitCommands;
import kr.chacademy.portrait.core.PortraitSettings;
import kr.chacademy.portrait.data.Database;
import kr.chacademy.portrait.data.PortraitStorage;
import kr.chacademy.portrait.net.PortraitChannel;
import kr.chacademy.portrait.service.ItemsAdderHook;
import kr.chacademy.portrait.service.PortraitService;
import kr.chacademy.portrait.service.RerollService;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.util.List;
import java.util.UUID;
import java.util.logging.Level;

/**
 * ChacaPortrait — 플레이어 스킨을 읽어 레퍼런스 그림체의 상반신 일러스트를 만들고, MagicCodex 대화창 "내 차례"에 쓰도록 클라이언트로 보낸다.
 * 최초 접속 시 1회 자동, 이후 ItemsAdder 아이템(item:cutscene_ticket, 컷신 변경권) 우클릭으로 추가 요청과 함께 다시 그린다.
 */
public final class ChacaPortraitPlugin extends JavaPlugin implements Listener {

    private volatile PortraitSettings settings;
    private Database db;
    private PortraitStorage storage;
    private PortraitService service;
    private RerollService rerolls;
    private PortraitChannel channel;
    private ItemsAdderHook itemsAdder;
    private final OpenAiImageClient ai = new OpenAiImageClient();
    /** 공유 DB(MariaDB)인데 server-id가 비어 있음 → 생성·반환·복구를 모두 멈춘다 (켤 때 정해지고 재시작 전까지 유지). */
    private volatile boolean generationBlocked;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        saveResourceIfMissing("database.properties.example");
        new File(getDataFolder(), "reference").mkdirs();
        settings = new PortraitSettings(getConfig());
        if (!PortraitSettings.supportedImageModel(settings.imageModel)) {
            getLogger().warning("[ChacaPortrait] image.model 값이 지원 목록(gpt-image-2, gpt-image-1.5)에 없습니다: " + settings.imageModel);
        }
        if (!settings.hasKey()) {
            getLogger().warning("[ChacaPortrait] OpenAI 키가 없습니다. 환경변수 CHACAPORTRAIT_OPENAI_KEY (또는 CHACANPC_OPENAI_KEY), 혹은 이 플러그인의 openai.api-key를 설정하세요. "
                    + "그 전까지 일러스트 생성은 쉬고, 이미 있는 일러스트 전송만 합니다.");
        }
        try {
            db = new Database(getDataFolder().toPath(), getLogger());
            storage = new PortraitStorage(db);
            // 공유 DB에서 server-id가 없으면 다른 서버의 진행 중 기록을 건드릴 수 있으므로 복구도 하지 않는다
            boolean blocked = db.isMariaDb() && !settings.serverIdSet;
            generationBlocked = blocked;
            db.call(() -> {
                storage.init();
                if (blocked) {
                    return null;
                }
                int locks = storage.releaseAll(settings.serverId);
                // 유료 생성 시작 전(PENDING)에 멈춘 다시 그리기는 반환, 시작 후(STARTED)에 멈춘 것은 소모 처리 (다음 접속 때 안내)
                int rerollsBack = storage.failPendingRerolls(settings.serverId);
                int rerollsConsumed = storage.consumeStartedRerolls(settings.serverId);
                int stale = storage.settleStale(settings.serverId);
                if (locks + rerollsBack + rerollsConsumed + stale > 0) {
                    getLogger().info("[ChacaPortrait] 지난 실행 정리: 잠금 " + locks + ", 반환 대상 " + rerollsBack
                            + ", 소모 처리(생성 시작 후 중단) " + rerollsConsumed + ", 예약 정산 " + stale);
                }
                return null;
            }).get();
        } catch (Exception e) {
            getLogger().log(Level.SEVERE, "[ChacaPortrait] DB 초기화 실패 — 플러그인을 끕니다", e);
            Bukkit.getPluginManager().disablePlugin(this);
            return;
        }
        if (db.isMariaDb()) {
            if (generationBlocked) {
                getLogger().severe("[ChacaPortrait] DB 모드: MariaDB(공유). config.yml의 server-id가 비어 있습니다 — 일러스트 생성·다시 그리기·아이템 반환을 모두 멈춥니다. "
                        + "서버마다 다른 값(school / wild)을 넣고 서버를 재시작하세요. 이미 있는 일러스트 전송만 계속합니다.");
            } else {
                getLogger().warning("[ChacaPortrait] DB 모드: MariaDB(공유) — server-id(현재 '" + settings.serverId
                        + "')는 school/wild 서버마다 반드시 달라야 합니다. 같으면 한 서버 재시작이 다른 서버의 진행 중 작업을 정리합니다.");
            }
        } else {
            getLogger().warning("[ChacaPortrait] DB 모드: SQLite(이 서버 전용 파일) — 일러스트·예산·다시 그리기 기록이 서버마다 따로 저장됩니다 (school/wild 간 공유 안 됨). "
                    + "공유하려면 database.properties로 MariaDB를 설정하세요.");
            if (!settings.serverIdSet) {
                getLogger().warning("[ChacaPortrait] server-id가 비어 있어 'school'로 동작합니다 (SQLite 모드에서만 허용).");
            }
        }
        itemsAdder = new ItemsAdderHook(getLogger());
        service = new PortraitService(this);
        service.start();
        rerolls = new RerollService(this);
        channel = new PortraitChannel(this);
        channel.register();
        Bukkit.getPluginManager().registerEvents(rerolls, this);
        Bukkit.getPluginManager().registerEvents(this, this);
        new PortraitCommands(this).register();
        Bukkit.getScheduler().runTaskTimer(this, rerolls::sweep, 100L, 100L);
        // 다른 서버에서 완성된 일러스트 전달 확인 (20초마다, 접속 중인 클라이언트의 SHA만 한 번에 조회)
        Bukkit.getScheduler().runTaskTimer(this, channel::syncRemote, 400L, 400L);
        for (Player p : Bukkit.getOnlinePlayers()) {
            service.online(p.getUniqueId(), true);
            rerolls.deliverRefunds(p, false);
            rerolls.notifyConsumed(p);
        }
        getLogger().info("[ChacaPortrait] 켜짐 — 모델 " + settings.imageModel + ", 품질 " + settings.quality
                + ", DB " + (db.isMariaDb() ? "MariaDB" : "SQLite") + ", ItemsAdder " + (itemsAdder.available() ? "연결" : "없음"));
    }

    @Override
    public void onDisable() {
        if (channel != null) {
            channel.unregister();
        }
        if (service != null) {
            service.stop();
        }
        if (db != null) {
            // 시작 전(PENDING)은 반환 대상으로, 유료 생성이 시작된 것(STARTED)은 소모 처리로 남긴다
            if (!generationBlocked) {
                try {
                    db.call(() -> storage.failPendingRerolls(settings.serverId) + storage.consumeStartedRerolls(settings.serverId))
                            .get(15, java.util.concurrent.TimeUnit.SECONDS);
                } catch (Exception e) { getLogger().warning("Reroll cancellation will recover on next startup."); }
            }
            // Remaining reservations are settled at the next startup.
            db.close();
        }
        ai.close();
    }

    /** 공유 DB인데 server-id가 설정되지 않아 생성·반환을 멈춘 상태인지. */
    public boolean generationBlocked() {
        return generationBlocked;
    }

    private void saveResourceIfMissing(String name) {
        if (!new File(getDataFolder(), name).exists()) {
            saveResource(name, false);
        }
    }

    /** 메인 스레드에서 실행 (플러그인이 꺼졌으면 무시). */
    public void main(Runnable r) {
        if (!isEnabled()) {
            return;
        }
        if (Bukkit.isPrimaryThread()) {
            r.run();
            return;
        }
        try {
            Bukkit.getScheduler().runTask(this, r);
        } catch (IllegalStateException ignored) {
            // 종료 중
        }
    }

    public void reloadSettings() {
        reloadConfig();
        settings = new PortraitSettings(getConfig());
        if (db.isMariaDb() && !settings.serverIdSet && !generationBlocked) {
            // 실행 중에 server-id를 지우고 reload → 'school'로 바뀌어 다른 서버 기록을 건드리지 않도록 재시작 전까지 멈춘다
            generationBlocked = true;
            getLogger().severe("[ChacaPortrait] config.yml의 server-id가 비어 있습니다 (MariaDB 공유 모드) — 일러스트 생성·다시 그리기·아이템 반환을 멈춥니다. "
                    + "값을 넣고 서버를 재시작하세요.");
        }
        service.reload();
    }

    /**
     * 최초 접속: 일러스트가 없고 자동 시도 횟수가 남았으면 잠시 뒤 생성. 돌려줄 아이템·소모 안내도 처리.
     * 준비 상태(설정·레퍼런스·단가)·남은 예산·실패 간격 제한을 먼저 확인하고, 작업이 실제로 대기열에 들어간 뒤에만 안내한다.
     */
    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        Player p = e.getPlayer();
        UUID id = p.getUniqueId();
        service.online(id, true);
        rerolls.deliverRefunds(p, false);
        rerolls.notifyConsumed(p);
        PortraitSettings s = settings;
        if (!s.automaticGenerationAllowed() || !s.hasKey() || !p.hasPermission("chacaportrait.auto")) {
            return;
        }
        Bukkit.getScheduler().runTaskLater(this, () -> {
            Player now = Bukkit.getPlayer(id);
            if (now == null || service.busy(id) || !service.autoEligible(id)) {
                return; // 준비 안 됨·OpenAI 장애로 쉬는 중·최근 실패 후 간격 제한 → 조용히 건너뜀 (API·안내 없음)
            }
            PortraitSettings cur = settings;
            service.then(db.call(() -> storage.sha(id) == null && storage.autoAttempts(id) < cur.autoMaxAttempts
                    && service.budgetAvailable(cur)), need -> {
                Player again = Bukkit.getPlayer(id);
                if (!Boolean.TRUE.equals(need) || again == null || service.busy(id)) {
                    return;
                }
                PortraitService.Job job = PortraitService.jobFor(PortraitService.Kind.AUTO, again, "", null, List.of(), null);
                if (job.skin().url() == null) {
                    return; // 스킨 없음 (기본 스킨) — 다음 접속 때 다시 확인
                }
                if (service.submit(job, null)) {
                    int ahead = service.ahead(id);
                    again.sendMessage(ahead >= 0 ? settings.message("queued", "position", ahead + 1) : settings.message("started"));
                }
            }, ex -> getLogger().warning("[ChacaPortrait] 최초 생성 확인 실패: " + ex.getMessage()));
        }, Math.max(1, s.autoDelaySeconds * 20L));
    }

    @EventHandler
    public void onQuit(org.bukkit.event.player.PlayerQuitEvent e) {
        service.online(e.getPlayer().getUniqueId(), false);
    }

    public PortraitSettings settings() {
        return settings;
    }

    public Database db() {
        return db;
    }

    public PortraitStorage storage() {
        return storage;
    }

    public PortraitService service() {
        return service;
    }

    public RerollService rerolls() {
        return rerolls;
    }

    public PortraitChannel channel() {
        return channel;
    }

    public ItemsAdderHook itemsAdder() {
        return itemsAdder;
    }

    public OpenAiImageClient ai() {
        return ai;
    }
}
