package school.chacademia.housing;

import java.io.File;
import org.bukkit.Bukkit;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;

/** 차카데미 하우징 (기숙사 내 방 꾸미기). 인계 문서: docs/handoff/CLAUDE_HOUSING_20261017.md */
public final class HousingPlugin extends JavaPlugin implements Listener {
    private Settings settings;
    private Catalog catalog = Catalog.EMPTY;
    private HousingStore store;
    private Rooms rooms;
    private Net net;
    private Doors doors;
    private HousingSearchServer search;
    /** #51 하우징 랭킹 채널. */
    private HousingRankServer rank;
    HousingRankServer rank() { return rank; }
    /** #108 특수 가구 전시 값 (빗자루 거치대·아티팩트 진열장). */
    private Showcases showcases;
    Showcases showcases() { return showcases; }

    @Override public void onEnable() {
        saveDefaultConfig();
        if (!new File(getDataFolder(), "furniture.yml").exists()) saveResource("furniture.yml", false);
        settings = Settings.from(getConfig(), getLogger());
        catalog = loadCatalog();
        store = new HousingStore(this);
        try { store.open(getDataFolder().toPath()); }
        catch (Exception e) { getLogger().severe("하우징 DB를 열 수 없어 플러그인을 끕니다: " + e); Bukkit.getPluginManager().disablePlugin(this); return; }
        // A03: 이 서버에서 끝나지 않은 방 확장 결제는 돈이 움직였는지 알 수 없으므로 uncertain 으로 두고 관리자에게 넘긴다 (자동 환불·재출금 없음).
        store.expandRecover(settings.server(), r -> {
            if (r == null) getLogger().severe("방 확장 결제 기록을 확인하지 못했어요 (DB). 끝나지 않은 거래가 있으면 /유저관리 <닉네임> 방 확장기록 으로 확인하세요.");
            else if (r[0] > 0) getLogger().severe("방 확장 결제 " + r[0] + "건이 끝나지 않은 채 재시작됐어요 → uncertain. /유저관리 <닉네임> 방 확장기록 으로 확인하세요.");
        });
        rooms = new Rooms(this);
        showcases = new Showcases(this);
        net = new Net(this); net.register();
        search=new HousingSearchServer(this);
        rank=new HousingRankServer(this);
        doors = new Doors(this); doors.load();
        Bukkit.getPluginManager().registerEvents(this, this);
        Bukkit.getPluginManager().registerEvents(doors, this);
        new HousingCommands(this).register();
        for (var p : Bukkit.getOnlinePlayers()) rooms.join(p);
        Bukkit.getScheduler().runTaskTimer(this, () -> rooms.sync(), settings.presenceTicks(), settings.presenceTicks());
        Bukkit.getScheduler().runTaskTimer(this, () -> rooms.heartbeat(), 20 * 30, 20 * 30);
        if (!Economy.available()) getLogger().warning("Vault 경제를 찾지 못했어요. 방 확장은 경제 플러그인이 켜진 뒤에 됩니다.");
        getLogger().info("하우징 준비 완료 · 가구 " + catalog.all().size() + "개 · 문 " + doors.all().size() + "개 · 서버 이름 " + settings.server());
    }

    @Override public void onDisable() {
        if(search!=null)search.close();
        if(rank!=null)rank.close();
        if (rooms != null) rooms.shutdown();
        if (net != null) net.unregister();
        if (store != null) store.close();
    }

    void reload() {
        reloadConfig();
        settings = Settings.from(getConfig(), getLogger());
        catalog = loadCatalog();
        rooms.catalogChanged();
        doors.load();
    }

    private Catalog loadCatalog() {
        var yaml = YamlConfiguration.loadConfiguration(new File(getDataFolder(), "furniture.yml"));
        return Catalog.parse(yaml, getLogger());
    }

    @EventHandler public void onJoin(PlayerJoinEvent e) { rooms.join(e.getPlayer()); showcases.joined(e.getPlayer()); }
    @EventHandler public void onQuit(PlayerQuitEvent e) { rooms.quit(e.getPlayer()); doors.forget(e.getPlayer().getUniqueId()); showcases.quit(e.getPlayer().getUniqueId()); }

    Settings settings() { return settings; }
    Catalog catalog() { return catalog; }
    HousingStore store() { return store; }
    /**
     * 정보창 v4 (MagicCodexBridge StatsBridge 가 리플렉션으로 부른다): 이 사람 방의 하우징 좋아요 합계.
     * 콜백은 메인 스레드, 방이 없으면 0, DB 오류·준비 안 됨이면 null.
     */
    public void likesOf(java.util.UUID owner, java.util.function.Consumer<Integer> done) {
        if (store == null || owner == null) { done.accept(null); return; }
        store.likes(owner, done);
    }
    Rooms rooms() { return rooms; }
    Net net() { return net; }
    Doors doors() { return doors; }
}
