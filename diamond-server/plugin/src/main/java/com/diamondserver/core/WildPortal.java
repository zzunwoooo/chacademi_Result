package com.diamondserver.core;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.entity.Display;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;
import org.bukkit.util.Transformation;
import org.joml.AxisAngle4f;
import org.joml.Vector3f;

/**
 * 로비 → 야생 이동 포탈 (11/23 오너: 홀로그램 느낌, 처음 한 번 야생으로 나갈 때 씀).
 * 로비 광장에 빛 고리(파티클) + 떠 있는 글씨(TextDisplay). 고리 안으로 들어가면 야생 월드의 시작 지점(땅 위)으로 보낸다.
 * 자리·대상 월드는 config.yml lobby-portal. 글씨는 서버가 켜질 때 세우고 꺼질 때 치운다(저장 안 함).
 */
final class WildPortal {
    private static final double HALF_W = 1.2, HALF_H = 1.6;         // 고리 가로 반지름 · 세로 반지름

    private final DiamondPlugin pl;
    private Location center;                                        // 고리 가운데 (발 높이 + HALF_H)
    private String toWorld;
    private TextDisplay title, sub;
    private long tick;

    WildPortal(DiamondPlugin pl) {
        this.pl = pl;
        Bukkit.getScheduler().runTaskTimer(pl, this::onTick, 40L, 1L);
    }

    private boolean ready() {
        if (!pl.getConfig().getBoolean("lobby-portal.enabled", false)) return false;
        if (center != null && center.getWorld() != null) return true;
        World w = Bukkit.getWorld(pl.getConfig().getString("lobby-portal.world", "lobby"));
        if (w == null) return false;
        center = new Location(w, pl.getConfig().getDouble("lobby-portal.x"), pl.getConfig().getDouble("lobby-portal.y") + HALF_H, pl.getConfig().getDouble("lobby-portal.z"));
        toWorld = pl.getConfig().getString("lobby-portal.to-world", "world");
        spawnText();
        return true;
    }

    private void spawnText() {
        removeText();
        title = text(center.clone().add(0, HALF_H + 0.9, 0), Component.text("야생으로 이동", NamedTextColor.AQUA).decoration(TextDecoration.BOLD, true), 1.6f);
        sub = text(center.clone().add(0, HALF_H + 0.45, 0), Component.text("빛 고리 안으로 들어가세요", NamedTextColor.WHITE), 0.9f);
    }

    private TextDisplay text(Location at, Component c, float scale) {
        return at.getWorld().spawn(at, TextDisplay.class, d -> {
            d.text(c);
            d.setBillboard(Display.Billboard.CENTER);
            d.setPersistent(false);
            d.setShadowed(true);
            d.setBackgroundColor(Color.fromARGB(90, 10, 40, 70));
            d.setBrightness(new Display.Brightness(15, 15));
            d.setTransformation(new Transformation(new Vector3f(), new AxisAngle4f(), new Vector3f(scale, scale, scale), new AxisAngle4f()));
        });
    }

    private void removeText() {
        if (title != null) title.remove();
        if (sub != null) sub.remove();
        title = sub = null;
    }

    private void onTick() {
        tick++;
        if (!ready()) return;
        if ((title == null || !title.isValid()) && center.isChunkLoaded() && tick % 100 == 0) spawnText();
        World w = center.getWorld();
        if (w.getPlayers().isEmpty()) return;
        if (tick % 2 == 0) draw(w);
        for (Player p : w.getPlayers()) {
            Location l = p.getLocation();
            if (Math.abs(l.getX() - center.getX()) < HALF_W * 0.85 && Math.abs(l.getZ() - center.getZ()) < 0.9
                    && l.getY() > center.getY() - HALF_H - 0.5 && l.getY() < center.getY() + HALF_H) send(p);
        }
    }

    /** 세로로 선 빛 고리 + 안쪽에서 천천히 도는 빛 + 바닥 원 */
    private void draw(World w) {
        Particle.DustOptions edge = new Particle.DustOptions(Color.fromRGB(90, 230, 255), 1.1f);
        Particle.DustOptions inner = new Particle.DustOptions(Color.fromRGB(190, 250, 255), 0.8f);
        double spin = tick * 0.06;
        for (int i = 0; i < 36; i++) {
            double a = Math.PI * 2 * i / 36;
            w.spawnParticle(Particle.DUST, center.clone().add(Math.cos(a) * HALF_W, Math.sin(a) * HALF_H, 0), 1, 0, 0, 0, 0, edge);
        }
        for (int i = 0; i < 6; i++) {
            double a = spin + Math.PI * 2 * i / 6, r = 0.35 + 0.5 * ((tick / 2 + i * 7) % 20) / 20.0;
            w.spawnParticle(Particle.DUST, center.clone().add(Math.cos(a) * HALF_W * r, Math.sin(a) * HALF_H * r, 0), 1, 0, 0, 0, 0, inner);
        }
        if (tick % 6 == 0) w.spawnParticle(Particle.END_ROD, center, 2, HALF_W * 0.4, HALF_H * 0.5, 0.05, 0.01);
        if (tick % 4 == 0) {
            Location floor = center.clone().add(0, -HALF_H + 0.05, 0);
            for (int i = 0; i < 20; i++) {
                double a = spin * 0.5 + Math.PI * 2 * i / 20;
                w.spawnParticle(Particle.DUST, floor.clone().add(Math.cos(a) * 1.5, 0, Math.sin(a) * 1.0), 1, 0, 0, 0, 0, edge);
            }
        }
    }

    private void send(Player p) {
        World w = Bukkit.getWorld(toWorld);
        if (w == null) return;
        Location s = w.getSpawnLocation();
        Location to = new Location(w, s.getBlockX() + 0.5, w.getHighestBlockYAt(s) + 1.0, s.getBlockZ() + 0.5, p.getLocation().getYaw(), 0);
        p.getWorld().spawnParticle(Particle.END_ROD, p.getLocation().add(0, 1, 0), 30, 0.3, 0.6, 0.3, 0.05);
        p.teleport(to);
        p.setFallDistance(0);
        p.playSound(to, Sound.ENTITY_ENDERMAN_TELEPORT, 0.8f, 1.2f);
        p.playSound(to, Sound.BLOCK_BEACON_ACTIVATE, 0.8f, 1.4f);
        pl.net.toast(p, "야생으로 이동했습니다.", true);
    }

    void shutdown() { removeText(); }
}
