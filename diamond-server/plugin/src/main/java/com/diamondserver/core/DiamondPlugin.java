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
 * 2차: 후원 이벤트 공통 틀(후원 받기 → 대기열 → 실행)과 고정 이벤트(밥·목표 증감·즉사).
 * 3~4차: 100개 장난 풀 · 300개 다이아 연출 풀 · 1000개 룰렛 · 2000개 AI 플레이어 · 모델 연출.
 * 아직 없는 것: UFO 납치 2종(모델 파일 대기) · 3000개 채팅 투표 · 목표 달성 뒤 처리.
 */
public final class DiamondPlugin extends JavaPlugin {
    Store store;
    Items items;
    Ledger ledger;
    Shop shop;
    Net net;
    Cmd cmd;
    Events events;
    Effects effects;
    Models models;
    Pranks pranks;
    Rewards rewards;
    Miner miner;
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
        events = new Events(this);
        models = new Models(this);
        effects = new Effects(this);
        pranks = new Pranks(this);
        rewards = new Rewards(this);
        miner = new Miner(this);
        getServer().getPluginManager().registerEvents(effects, this);
        getServer().getPluginManager().registerEvents(pranks, this);
        getServer().getPluginManager().registerEvents(rewards, this);
        shop.load();
        events.load();
        loadSpawn();

        getServer().getMessenger().registerOutgoingPluginChannel(this, Net.CH);
        getServer().getMessenger().registerIncomingPluginChannel(this, Net.CH, net);
        getServer().getPluginManager().registerEvents(new Listeners(this), this);
        // 후원 받기: donation 플러그인이 있을 때만 연결
        if (getServer().getPluginManager().getPlugin("donation") != null) {
            getServer().getPluginManager().registerEvents(new DonationHook(this), this);
            getLogger().info("후원 연동(donation 플러그인) 켜짐");
        } else {
            getLogger().info("donation 플러그인이 없어 후원 연동은 꺼짐 (/후원테스트 는 사용 가능)");
        }
        for (String name : new String[]{"다이아", "상점", "제출", "인출", "입금", "목표", "후원테스트"}) {
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
        if (effects != null) effects.shutdown();      // 작아진 몸·마그마로 바꾼 블럭을 되돌린다
        if (pranks != null) pranks.shutdown();        // 까마귀가 훔친 다이아·겉날개를 되돌린다
        if (rewards != null) rewards.shutdown();
        if (miner != null) miner.shutdown();
        if (models != null) models.shutdown();        // 세워 둔 모델을 치운다
        if (store != null) store.saveAll();
    }

    void reload() {
        reloadConfig();
        shop.load();
        events.load();
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
