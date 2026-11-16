package com.diamondserver.core;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.command.PluginCommand;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;

/**
 * 나니 다이아서버 플러그인.
 * 1차: 다이아 장부(남은 목표·보유 다이아) · 캔 다이아 흡수 · 제출 · 상점(구매·판매) · 전용 아이템 4종.
 * 후원 이벤트는 아직 없다.
 */
public final class DiamondPlugin extends JavaPlugin {
    Store store;
    Items items;
    Ledger ledger;
    Shop shop;
    Net net;
    Cmd cmd;
    private Location spawn;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        store = new Store(this);
        items = new Items(this);
        ledger = new Ledger(this);
        shop = new Shop(this);
        net = new Net(this);
        cmd = new Cmd(this);
        shop.load();
        loadSpawn();

        getServer().getMessenger().registerOutgoingPluginChannel(this, Net.CH);
        getServer().getMessenger().registerIncomingPluginChannel(this, Net.CH, net);
        getServer().getPluginManager().registerEvents(new Listeners(this), this);
        for (String name : new String[]{"다이아", "상점", "제출", "인출", "입금", "목표"}) {
            PluginCommand c = getCommand(name);
            if (c == null) { getLogger().warning("명령어 등록 실패: " + name); continue; }
            c.setExecutor(cmd);
            c.setTabCompleter(cmd);
        }

        // 5분마다 저장
        Bukkit.getScheduler().runTaskTimer(this, store::saveAll, 20L * 300, 20L * 300);
        // 모드 없이 들어온 사람에게는 화면 아래 글자로 남은 목표·보유 다이아를 보여 준다
        Bukkit.getScheduler().runTaskTimer(this, () -> {
            for (Player p : Bukkit.getOnlinePlayers()) {
                if (net.hasMod(p)) continue;
                Store.PData d = store.get(p);
                p.sendActionBar(Component.text("남은 목표 " + d.goal + " · 보유 다이아 " + d.held, NamedTextColor.AQUA));
            }
        }, 40L, 40L);
        getLogger().info("나니 다이아서버 플러그인 켜짐");
    }

    @Override
    public void onDisable() {
        if (store != null) store.saveAll();
    }

    void reload() {
        reloadConfig();
        shop.load();
        for (Player p : Bukkit.getOnlinePlayers()) if (net.hasMod(p)) net.shop(p);
    }

    // ── 귀환권이 돌아올 시작 지점 (locations.yml) ─────────────────
    private File locFile() { return new File(getDataFolder(), "locations.yml"); }

    private void loadSpawn() {
        File f = locFile();
        if (f.isFile()) spawn = YamlConfiguration.loadConfiguration(f).getLocation("spawn");
    }

    /** 정해 둔 시작 지점. 없으면 기본 월드의 스폰 */
    Location spawn() {
        if (spawn != null && spawn.getWorld() != null) return spawn.clone();
        return Bukkit.getWorlds().get(0).getSpawnLocation().add(0.5, 0, 0.5);
    }

    void setSpawn(Location l) {
        spawn = l.clone();
        YamlConfiguration y = new YamlConfiguration();
        y.set("spawn", spawn);
        try { y.save(locFile()); } catch (IOException e) { getLogger().warning("시작 지점 저장 실패: " + e.getMessage()); }
    }
}
