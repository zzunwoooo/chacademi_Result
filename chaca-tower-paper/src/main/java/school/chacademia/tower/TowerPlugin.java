package school.chacademia.tower;

import org.bukkit.Bukkit;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * ChacaTower — 1인 로그라이크 탑 (오너 2026-11-09). 학교 서버 던전 월드의 전투방 하나를 모든 층이 같이 쓴다.
 * 입장 때 소지품·마법·지능을 맡기고 기본 공격 마법 하나로 시작, 층을 깰 때마다 증강 카드 3장 중 하나.
 */
public final class TowerPlugin extends JavaPlugin {
    private TowerService service;
    private Records records;
    private SetupTool tool;

    TowerService service() { return service; }
    SetupTool tool() { return tool; }

    @Override public void onEnable() {
        saveDefaultConfig();
        var magic = new MagicBridge(this);
        records = new Records(getLogger());
        service = new TowerService(this, magic, new Stash(getDataFolder(), getLogger()), records, new ItemsAdderItems(getLogger()));
        reload();
        var dataDir = getDataFolder().toPath();
        records.open(dataDir.resolve(service.s.databaseFile).normalize(), dataDir.resolve("records.db")).whenComplete((ok, error) -> {
            if (error != null) getLogger().severe("기록 DB 를 열지 못했어요 — 기록 없이 진행: " + error);
        });
        tool = new SetupTool(this);
        getServer().getPluginManager().registerEvents(new TowerListener(service), this);
        getServer().getPluginManager().registerEvents(tool, this);
        var commands = new Commands(this, records);
        var tower = getCommand("탑");
        if (tower != null) { tower.setExecutor(commands.player()); tower.setTabCompleter(commands.player()); }
        var admin = getCommand("탑관리");
        if (admin != null) { admin.setExecutor(commands.admin()); admin.setTabCompleter(commands.admin()); }
        Bukkit.getScheduler().runTaskTimer(this, service::tick, 20L, 10L);
        for (var p : Bukkit.getOnlinePlayers()) service.join(p);
    }

    @Override public void onDisable() {
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
