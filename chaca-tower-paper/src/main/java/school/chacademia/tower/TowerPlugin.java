package school.chacademia.tower;

import org.bukkit.Bukkit;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * ChacaTower — 1인 로그라이크 탑 (오너 2026-11-09). 학교 서버 던전 월드의 전투방 하나를 모든 층이 같이 쓴다.
 * 입장 때 소지품·마법·지능을 맡기고 기본 공격 마법 하나로 시작, 층을 깰 때마다 증강 카드 3장 중 하나.
 */
public final class TowerPlugin extends JavaPlugin {
    private TowerService service;
    private Records records;
    private SetupTool tool;
    private BridgeLock bridgeLock;

    TowerService service() { return service; }
    SetupTool tool() { return tool; }

    @Override public void onEnable() {
        saveDefaultConfig();
        var magic = new MagicBridge(this);
        records = new Records(getLogger());
        service = new TowerService(this, magic, new Stash(getDataFolder(), getLogger()), records, new ItemsAdderItems(getLogger()));
        TowerApi.service = service;
        reload();
        var dataDir = getDataFolder().toPath();
        records.open(dataDir.resolve(service.s.databaseFile).normalize(), dataDir.resolve("records.db")).whenComplete((ok, error) -> {
            if (error != null) getLogger().severe("기록 DB 를 열지 못했어요 — 기록 없이 진행: " + error);
        });
        tool = new SetupTool(this);
        getServer().getPluginManager().registerEvents(new TowerListener(service), this);
        quitFirst();
        // HuskSync: 판 중 저장 취소 + 불러오기 끝난 뒤 남은 맡김 복구 (11/12 점검)
        service.huskHooked = HuskHook.install(this, service::savingBlocked, service::synced);
        // 탑 안에서 배낭 쪽 넘기기·우편·상점 같은 모드 화면 요청 막기 (다른 사람 요청은 그대로 넘김)
        bridgeLock = new BridgeLock(this);
        bridgeLock.install(service.s.lockedChannels, p -> service.inRun(p) || service.waitingRestore(p));
        getServer().getPluginManager().registerEvents(tool, this);
        var commands = new Commands(this, records);
        var tower = getCommand("탑");
        if (tower != null) { tower.setExecutor(commands.player()); tower.setTabCompleter(commands.player()); }
        var admin = getCommand("탑관리");
        if (admin != null) { admin.setExecutor(commands.admin()); admin.setTabCompleter(commands.admin()); }
        Bukkit.getScheduler().runTaskTimer(this, service::tick, 20L, 10L);
        for (var p : Bukkit.getOnlinePlayers()) service.join(p);
    }

    /**
     * 나가기(PlayerQuitEvent) 처리는 HuskSync·MagicCodexBridge 보다 먼저 돌아야 한다: 둘 다 LOWEST 에서 저장하는데 먼저 켜진 플러그인이 먼저 불리므로,
     * 그대로 두면 탑 소지품(빈 가방)이 공용 DB 에 저장된 뒤에 맡긴 것을 돌려주게 된다. 같은 LOWEST 의 다른 처리기를 뺐다가 다시 넣어 우리 것을 맨 앞에 둔다.
     */
    private void quitFirst() {
        var list = PlayerQuitEvent.getHandlerList();
        var others = java.util.Arrays.stream(list.getRegisteredListeners())
                .filter(r -> r.getPlugin() != this && r.getPriority() == org.bukkit.event.EventPriority.LOWEST).toList();
        for (var r : others) list.unregister(r);
        for (var r : others) list.register(r);
        list.bake();
        var order = new java.util.ArrayList<String>();
        for (var r : list.getRegisteredListeners()) if (r.getPriority() == org.bukkit.event.EventPriority.LOWEST) order.add(r.getPlugin().getName());
        getLogger().info("나가기 처리 순서(LOWEST): " + String.join(" → ", order));
    }

    @Override public void onDisable() {
        TowerApi.service = null;
        if (bridgeLock != null) bridgeLock.uninstall();   // 다른 플러그인의 채널 처리기를 원래대로 (안 하면 그 채널이 죽은 채로 남음)
        if (service != null) service.shutdown();
        if (records != null) records.close();
    }

    void reload() {
        reloadConfig();
        service.apply(new Settings(getConfig()));
    }

    /** 인게임 설정 하나를 config.yml 에 저장하고 다시 읽는다. */
    void set(String path, Object value) {
        getConfig().set(path, value);
        saveConfig();
        reload();
    }
}
