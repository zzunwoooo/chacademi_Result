package com.diamondserver.core;

import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;

import java.io.File;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/** 플레이어별 다이아 장부. 파일: plugins/DiamondCore/players/<UUID>.yml */
final class Store {
    /** 한 사람의 장부 */
    static final class PData {
        final UUID id;
        String name = "";
        String nick = "";       // 한글 닉네임 (없으면 빈 글자)
        long goal;          // 남은 목표 다이아 (0 이 되면 달성)
        long held;          // 보유 다이아 (캔 다이아가 여기로 들어옴, 상점 화폐)
        double frac;        // 판매 소수점 누적 (1 이 넘으면 다이아 1개)
        boolean done;       // 목표 달성 여부
        boolean dirty;

        PData(UUID id) { this.id = id; }
    }

    private final DiamondPlugin pl;
    private final File dir;
    private final Map<UUID, PData> map = new HashMap<>();

    // [기능:공동 목표 시작]
    // 남은 목표 다이아는 모두 함께 쓰는 하나 (11/23 오너: "다이아 목표는 통일, 후원·사망으로 늘어도 다 같이 보여야 해").
    // 보유 다이아는 그대로 사람마다 따로. 파일: plugins/DiamondCore/shared.yml
    long goal;
    boolean done;
    boolean sharedDirty;
    private final File sharedFile;

    private void loadShared() {
        if (sharedFile.isFile()) {
            YamlConfiguration y = YamlConfiguration.loadConfiguration(sharedFile);
            goal = Math.max(0, y.getLong("goal"));
            done = y.getBoolean("done");
        } else {
            long min = Math.max(0, pl.getConfig().getLong("goal.start-min", 1000));
            long max = Math.max(min, pl.getConfig().getLong("goal.start-max", 1000));
            goal = ThreadLocalRandom.current().nextLong(min, max + 1);
            done = false;
            sharedDirty = true;
            saveShared();
        }
    }

    void saveShared() {
        if (!sharedDirty) return;
        YamlConfiguration y = new YamlConfiguration();
        y.set("goal", goal);
        y.set("done", done);
        try {
            y.save(sharedFile);
            sharedDirty = false;
        } catch (IOException e) {
            pl.getLogger().warning("공동 목표 저장 실패: " + e.getMessage());
        }
    }
    // [기능:공동 목표 끝]

    Store(DiamondPlugin pl) {
        this.pl = pl;
        this.dir = new File(pl.getDataFolder(), "players");
        dir.mkdirs();
        this.sharedFile = new File(pl.getDataFolder(), "shared.yml");
        loadShared();
    }

    PData get(Player p) {
        PData d = get(p.getUniqueId());
        if (!p.getName().equals(d.name)) { d.name = p.getName(); d.dirty = true; }
        return d;
    }

    PData get(UUID id) {
        PData d = map.computeIfAbsent(id, this::load);
        d.goal = goal;          // 공동 목표를 그대로 비춰 줌 (사람마다 따로 두지 않음)
        d.done = done;
        return d;
    }

    private PData load(UUID id) {
        PData d = new PData(id);
        File f = new File(dir, id + ".yml");
        if (f.isFile()) {
            YamlConfiguration y = YamlConfiguration.loadConfiguration(f);
            d.name = y.getString("name", "");
            d.nick = y.getString("nick", "");
            d.held = Math.max(0, y.getLong("held"));
            d.frac = Math.max(0, y.getDouble("frac"));
        } else {
            d.dirty = true;
        }
        return d;
    }

    void save(PData d) {
        if (!d.dirty) return;
        YamlConfiguration y = new YamlConfiguration();
        y.set("name", d.name);
        y.set("nick", d.nick);
        y.set("goal", d.goal);
        y.set("held", d.held);
        y.set("frac", d.frac);
        y.set("done", d.done);
        try {
            y.save(new File(dir, d.id + ".yml"));
            d.dirty = false;
        } catch (IOException e) {
            pl.getLogger().warning("장부 저장 실패 " + d.id + ": " + e.getMessage());
        }
    }

    void saveAll() {
        saveShared();
        for (PData d : map.values()) save(d);
    }

    /** 접속을 끊은 사람은 저장하고 메모리에서 내린다 */
    void unload(UUID id) {
        PData d = map.remove(id);
        if (d != null) save(d);
    }
}
