package school.chacademia.dungeon;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Map;
import org.bukkit.Bukkit;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;
import school.chacademia.dungeon.protocol.DungeonProtocol;

/**
 * ChacaDungeon — 1인 던전 (2026-10-29, 첫 던전 = 리치의 지하 연구실, 학교 서버 dungeon1 월드).
 * 던전 하나에 독립 칸(인스턴스) 여러 개(기본 3), 칸마다 1명. 다 차면 대기열. 화면은 클라이언트 모드 chacademi-dungeon.
 */
public final class DungeonPlugin extends JavaPlugin {
    private DungeonService service;
    private Records records;
    private File dungeonsFile;

    DungeonService service() { return service; }
    int offerSeconds() { return Math.max(10, getConfig().getInt("offer-seconds", 60)); }
    double rearmFactor() { return Math.max(1.1, getConfig().getDouble("portal-rearm-factor", 1.6)); }
    double startMoveDistance() { return Math.max(0.1, getConfig().getDouble("start-move-distance", 0.6)); }
    int startFallbackSeconds() { return Math.max(1, getConfig().getInt("start-fallback-seconds", 10)); }

    @Override public void onEnable() {
        saveDefaultConfig();
        dungeonsFile = new File(getDataFolder(), "dungeons.yml");
        if (!dungeonsFile.exists()) saveResource("dungeons.yml", false);
        var hooks = new Hooks(getLogger());
        records = new Records(getLogger(), id -> { var n = Bukkit.getOfflinePlayer(id).getName(); return n == null ? "" : n; });
        service = new DungeonService(this, hooks, new ItemsAdderItems(getLogger()), records);
        var dataDir = getDataFolder().toPath();
        records.open(dataDir.resolve(getConfig().getString("database-file", "../MagicCodexBridge/player-state-database.properties")).normalize(),
                dataDir.resolve("records.db")).whenComplete((ok, error) -> {
            if (error != null) getLogger().severe("기록 DB 를 열지 못했어요 — 클리어 기록 없이 진행 (첫 클리어 무료·첫 클리어 보상 판정 불가): " + error);
        });
        records.loadTitles(dataDir.resolve(getConfig().getString("titles-file", "../MagicCodexBridge/titles.yml")).normalize());
        try { reload(); } catch (Exception e) { getLogger().severe("dungeons.yml 읽기 실패: " + e); }

        var messenger = getServer().getMessenger();
        messenger.registerOutgoingPluginChannel(this, DungeonProtocol.S2C);
        var limiter = new java.util.HashMap<java.util.UUID, Long>();
        messenger.registerIncomingPluginChannel(this, DungeonProtocol.C2S, (channel, player, data) -> {
            long now = System.currentTimeMillis();
            Long last = limiter.put(player.getUniqueId(), now);
            if (last != null && now - last < 150) return; // 연타 1차 거름 (실제 중복 방지는 서비스의 busy 표시)
            try { service.action(player, DungeonProtocol.decodeAction(data)); }
            catch (java.io.IOException e) { getLogger().fine("잘못된 던전 메시지 " + player.getName() + ": " + e.getMessage()); }
        });
        getServer().getPluginManager().registerEvents(new DungeonListener(service), this);
        // 보스 스킬용 MythicMobs 메커닉 chavfx (예고 없이 VFX 만). MythicMobs 가 스킬을 읽기 전에 등록돼야 한다 (softdepend 로 뒤에 켜짐, 스킬은 서버 시작 뒤 읽힘).
        if (getServer().getPluginManager().getPlugin("MythicMobs") != null)
            getServer().getPluginManager().registerEvents(new VfxMechanic(getLogger()), this);
        Bukkit.getScheduler().runTaskTimer(this, service::tick, 20L, 10L);
        if (ChacaLink.ready()) {
            try { new AdminCommands(this).register(); }
            catch (RuntimeException e) { getLogger().warning("명령 등록 실패: " + e); }
        } else getLogger().warning("MagicCodexBridge 명령 허브가 없어 /던전관리 설정 명령을 등록하지 못했어요");
        for (var p : Bukkit.getOnlinePlayers()) service.join(p);
    }

    @Override public void onDisable() {
        if (service != null) service.shutdown();
        if (records != null) records.close();
    }

    /** config.yml + dungeons.yml 다시 읽기. 진행 중인 판은 그대로 (바뀐 단계는 다음 판부터). */
    void reload() throws Exception {
        reloadConfig();
        var yml = YamlConfiguration.loadConfiguration(dungeonsFile);
        var warnings = new ArrayList<String>();
        Map<String, Defs.Dungeon> next = Defs.read(yml.getConfigurationSection("dungeons"), warnings);
        warnings.forEach(w -> getLogger().warning("dungeons.yml: " + w));
        service.apply(next);
        records.loadTitles(getDataFolder().toPath().resolve(getConfig().getString("titles-file", "../MagicCodexBridge/titles.yml")).normalize());
        getLogger().info("던전 " + next.size() + "개 읽음" + (warnings.isEmpty() ? "" : " (경고 " + warnings.size() + ")"));
    }

    /** 인게임 설정을 dungeons.yml 에 저장 (임시 파일에 쓰고 바꿔치기). */
    void saveDungeons() {
        var yml = YamlConfiguration.loadConfiguration(dungeonsFile);
        var root = yml.createSection("dungeons");
        for (var d : service.dungeons.values()) Defs.write(root, d);
        try {
            var tmp = new File(getDataFolder(), "dungeons.yml.tmp");
            yml.save(tmp);
            Files.move(tmp.toPath(), dungeonsFile.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (Exception e) {
            getLogger().severe("dungeons.yml 저장 실패: " + e);
            throw new IllegalStateException("저장하지 못했어요 (콘솔 확인)", e);
        }
    }
}
