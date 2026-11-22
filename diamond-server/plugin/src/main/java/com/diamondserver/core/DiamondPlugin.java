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
    Seal seal;
    Monsters monsters;
    Mlg mlg;
    WildPortal portal;
    Rewards rewards;
    Miner miner;
    Nicks nicks;
    Mail mail;
    private Location spawn;

    /** 화면에 쓸 이름 (닉네임이 있으면 닉네임, 없으면 계정 이름). 알림 글에는 항상 이것을 쓴다 */
    String nick(Player p) { return nicks.of(p); }

    @Override
    public void onEnable() {
        saveDefaultConfig();
        store = new Store(this);
        nicks = new Nicks(this);
        mail = new Mail(this);
        items = new Items(this);
        ledger = new Ledger(this);
        shop = new Shop(this);
        net = new Net(this);
        cmd = new Cmd(this);
        events = new Events(this);
        models = new Models(this);
        effects = new Effects(this);
        pranks = new Pranks(this);
        seal = new Seal(this);
        monsters = new Monsters(this);
        mlg = new Mlg(this);
        portal = new WildPortal(this);
        rewards = new Rewards(this);
        miner = new Miner(this);
        getServer().getPluginManager().registerEvents(effects, this);
        getServer().getPluginManager().registerEvents(pranks, this);
        getServer().getPluginManager().registerEvents(seal, this);
        getServer().getPluginManager().registerEvents(monsters, this);
        getServer().getPluginManager().registerEvents(mlg, this);
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
        for (String name : new String[]{"다이아", "상점", "제출", "인출", "입금", "목표", "후원테스트", "닉네임", "우편함", "명령어"}) {
            PluginCommand c = getCommand(name);
            if (c == null) { getLogger().warning("명령어 등록 실패: " + name); continue; }
            c.setExecutor(cmd);
            c.setTabCompleter(cmd);
        }

        // 발전 과제 알림은 계정 이름으로 나오므로 끈다 (화면에는 한글 닉네임만 나오게)
        for (org.bukkit.World w : Bukkit.getWorlds()) w.setGameRule(org.bukkit.GameRule.ANNOUNCE_ADVANCEMENTS, false);

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
        // 장부 저장을 가장 먼저 한다 (뒤의 정리 중 하나가 실패해도 다이아 기록은 남게). 정리는 하나씩 따로 감싼다.
        safe("장부 저장", () -> { if (store != null) store.saveAll(); });
        safe("효과 정리", () -> { if (effects != null) effects.shutdown(); });      // 작아진 몸·마그마로 바꾼 블럭을 되돌린다
        safe("장난 정리", () -> { if (pranks != null) pranks.shutdown(); });        // 까마귀가 훔친 다이아·겉날개를 되돌린다
        safe("몬스터 정리", () -> { if (monsters != null) monsters.shutdown(); });
        safe("물낙하 정리", () -> { if (mlg != null) mlg.shutdown(); });
        safe("로비 포탈 정리", () -> { if (portal != null) portal.shutdown(); });
        safe("연출 정리", () -> { if (rewards != null) rewards.shutdown(); });
        safe("AI 플레이어 정리", () -> { if (miner != null) miner.shutdown(); });
        safe("모델 정리", () -> { if (models != null) models.shutdown(); });        // 세워 둔 모델을 치운다
        safe("장부 저장", () -> { if (store != null) store.saveAll(); });          // 정리하면서 돌려준 다이아까지
    }

    private void safe(String what, Runnable r) {
        try { r.run(); } catch (Throwable t) { getLogger().warning(what + " 중 오류: " + t); }
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
