package com.diamondserver.core;

import net.citizensnpcs.api.CitizensAPI;
import net.citizensnpcs.api.npc.NPC;
import net.citizensnpcs.api.npc.NPCRegistry;
import net.citizensnpcs.api.trait.trait.Equipment;
import net.citizensnpcs.trait.SkinTrait;
import org.bukkit.Bukkit;
import org.bukkit.Effect;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.block.Block;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * 2000개: 후원자 AI 플레이어. 대화 없이 광질만 하는 Citizens NPC.
 * 후원자 닉네임·스킨으로 나타나 스트리머를 따라다니며 주변 8칸의 광석을 캐고(다이아 우선),
 * 다이아 광석을 캐면 스트리머의 보유 다이아로 바로 넣어 준다. 3분 뒤 퇴장. 동시에 최대 3명, 넘치면 차례를 기다린다.
 * Citizens 가 없으면 동작하지 않는다.
 */
final class Miner {
    private enum Mode { FOLLOW, GOTO, MINE }

    private final class Bot {
        final UUID owner;
        final String donor;
        NPC npc;
        Mode mode = Mode.FOLLOW;
        Block ore;
        long endTick, modeSince, lastScan;
        int mined;

        Bot(UUID owner, String donor) { this.owner = owner; this.donor = donor; }
    }

    private record Waiting(UUID owner, String donor) { }

    private final DiamondPlugin pl;
    private final boolean ok;
    private NPCRegistry registry;
    private final List<Bot> bots = new ArrayList<>();
    private final Deque<Waiting> waiting = new ArrayDeque<>();
    private final Set<Block> claimed = new HashSet<>();        // 누가 이미 캐러 가는 광석
    private final Set<Block> skipped = new HashSet<>();        // 갈 수 없어서 포기한 광석
    private long tick;

    Miner(DiamondPlugin pl) {
        this.pl = pl;
        this.ok = Bukkit.getPluginManager().getPlugin("Citizens") != null;
        pl.getLogger().info(ok ? "AI 플레이어 연동(Citizens) 켜짐" : "Citizens 가 없어 AI 플레이어는 나오지 않음");
        Bukkit.getScheduler().runTaskTimer(pl, this::onTick, 5L, 5L);
    }

    private int max() { return Math.max(1, pl.getConfig().getInt("miner.max", 3)); }
    private int radius() { return Math.max(2, pl.getConfig().getInt("miner.radius", 8)); }

    /** AI 플레이어를 부른다. 이미 가득 차 있으면 차례를 기다린다 */
    void summon(Player owner, String donor) {
        if (!ok) { pl.net.toast(owner, "AI 플레이어를 부를 수 없습니다. (Citizens 없음)", false); return; }
        if (bots.size() >= max()) {
            waiting.add(new Waiting(owner.getUniqueId(), donor));
            for (Player p : Bukkit.getOnlinePlayers()) pl.net.toast(p, donor + " AI 플레이어가 차례를 기다립니다. (대기 " + waiting.size() + "명)", true);
            return;
        }
        start(owner, donor);
    }

    private static String npcName(String donor) {
        String n = donor == null || donor.isBlank() ? "AI" : donor.strip();
        return n.length() > 16 ? n.substring(0, 16) : n;
    }

    private void start(Player owner, String donor) {
        try {
            if (registry == null) registry = CitizensAPI.createInMemoryNPCRegistry("diamondserver");
            Bot b = new Bot(owner.getUniqueId(), donor);
            b.npc = registry.createNPC(EntityType.PLAYER, npcName(donor));
            b.npc.setProtected(true);
            b.npc.getOrAddTrait(SkinTrait.class).setSkinName(npcName(donor));
            b.npc.getOrAddTrait(Equipment.class).set(Equipment.EquipmentSlot.HAND, new ItemStack(Material.DIAMOND_PICKAXE));
            b.npc.spawn(beside(owner));
            b.endTick = tick + Math.max(10, pl.getConfig().getInt("miner.seconds", 180)) * 4L;      // 5틱마다 1씩 센다
            bots.add(b);
            for (Player p : Bukkit.getOnlinePlayers()) pl.net.toast(p, donor + " AI 플레이어가 " + owner.getName() + "님을 도우러 왔습니다.", true);
        } catch (Throwable t) {
            pl.getLogger().warning("AI 플레이어 소환 실패: " + t);
        }
    }

    private static Location beside(Player owner) {
        Location l = owner.getLocation();
        Location side = l.clone().add(l.getDirection().setY(0).normalize().multiply(-1.5));
        return side.getBlock().isPassable() && side.clone().add(0, 1, 0).getBlock().isPassable() ? side : l;
    }

    private static boolean isOre(Material m) { return m.name().endsWith("_ORE") || m == Material.ANCIENT_DEBRIS; }
    private static boolean isDiamond(Material m) { return m == Material.DIAMOND_ORE || m == Material.DEEPSLATE_DIAMOND_ORE; }

    /** 주변에서 가장 가까운 광석 (다이아 광석이 있으면 그중 가장 가까운 것) */
    private Block scan(Location c) {
        int r = radius();
        Block best = null, bestDiamond = null;
        double bd = Double.MAX_VALUE, bdd = Double.MAX_VALUE;
        Block center = c.getBlock();
        for (int dx = -r; dx <= r; dx++) for (int dy = -r; dy <= r; dy++) for (int dz = -r; dz <= r; dz++) {
            Block b = center.getRelative(dx, dy, dz);
            Material m = b.getType();
            if (!isOre(m) || claimed.contains(b) || skipped.contains(b)) continue;
            double d = dx * dx + dy * dy + dz * dz;
            if (isDiamond(m)) { if (d < bdd) { bdd = d; bestDiamond = b; } }
            else if (d < bd) { bd = d; best = b; }
        }
        return bestDiamond != null ? bestDiamond : best;
    }

    /** 광석 옆에서 설 수 있는 가장 가까운 자리 (없으면 null) */
    private static Location standNear(Block ore) {
        Location best = null;
        double bd = Double.MAX_VALUE;
        for (int dx = -2; dx <= 2; dx++) for (int dy = -2; dy <= 1; dy++) for (int dz = -2; dz <= 2; dz++) {
            Block feet = ore.getRelative(dx, dy, dz);
            if (!feet.isPassable() || !feet.getRelative(0, 1, 0).isPassable() || !feet.getRelative(0, -1, 0).getType().isSolid()) continue;
            double d = dx * dx + dy * dy + dz * dz;
            if (d < bd) { bd = d; best = feet.getLocation().add(0.5, 0, 0.5); }
        }
        return best;
    }

    private void setMode(Bot b, Mode m) { b.mode = m; b.modeSince = tick; }

    private void release(Bot b) {
        if (b.ore != null) claimed.remove(b.ore);
        b.ore = null;
    }

    private void onTick() {
        tick++;
        if (!ok) return;
        for (Bot b : new ArrayList<>(bots)) {
            try { step(b); } catch (Throwable t) { pl.getLogger().warning("AI 플레이어 처리 실패: " + t); end(b, false); }
        }
        while (bots.size() < max() && !waiting.isEmpty()) {
            Waiting w = waiting.poll();
            Player owner = Bukkit.getPlayer(w.owner());
            if (owner != null) start(owner, w.donor());
        }
        if (tick % 1200 == 0) skipped.clear();
    }

    private void step(Bot b) {
        Player owner = Bukkit.getPlayer(b.owner);
        if (owner == null || tick >= b.endTick) { end(b, owner != null); return; }
        if (!b.npc.isSpawned()) { b.npc.spawn(beside(owner)); return; }
        Entity e = b.npc.getEntity();
        Location at = e.getLocation();

        // 멀어지거나 다른 월드면 스트리머 옆으로
        if (at.getWorld() != owner.getWorld() || at.distanceSquared(owner.getLocation()) > 16 * 16) {
            release(b);
            b.npc.getNavigator().cancelNavigation();
            b.npc.teleport(beside(owner), PlayerTeleportEvent.TeleportCause.PLUGIN);
            setMode(b, Mode.FOLLOW);
            return;
        }

        switch (b.mode) {
            case FOLLOW -> {
                if (tick - b.lastScan >= 6) {                         // 1.5초마다 주변 광석을 찾는다
                    b.lastScan = tick;
                    Block ore = scan(at);
                    if (ore != null) {
                        b.ore = ore;
                        claimed.add(ore);
                        setMode(b, Mode.GOTO);
                        Location stand = standNear(ore);
                        b.npc.getNavigator().setTarget(stand != null ? stand : ore.getLocation().add(0.5, 0, 0.5));
                        return;
                    }
                }
                if (at.distanceSquared(owner.getLocation()) > 3 * 3 && !b.npc.getNavigator().isNavigating())
                    b.npc.getNavigator().setTarget(owner, false);
            }
            case GOTO -> {
                if (b.ore == null || !isOre(b.ore.getType())) { release(b); setMode(b, Mode.FOLLOW); return; }
                Location oc = b.ore.getLocation().add(0.5, 0.5, 0.5);
                if (at.clone().add(0, 1, 0).distanceSquared(oc) <= 4.2 * 4.2) {
                    b.npc.getNavigator().cancelNavigation();
                    setMode(b, Mode.MINE);
                } else if (tick - b.modeSince > 24) {                 // 6초 안에 못 가면 옆으로 순간이동, 설 자리가 없으면 포기
                    Location stand = standNear(b.ore);
                    if (stand != null) {
                        b.npc.getNavigator().cancelNavigation();
                        b.npc.teleport(stand, PlayerTeleportEvent.TeleportCause.PLUGIN);
                        setMode(b, Mode.MINE);
                    } else {
                        skipped.add(b.ore);
                        release(b);
                        setMode(b, Mode.FOLLOW);
                    }
                }
            }
            case MINE -> {
                if (b.ore == null || !isOre(b.ore.getType())) { release(b); setMode(b, Mode.FOLLOW); return; }
                b.npc.faceLocation(b.ore.getLocation().add(0.5, 0.5, 0.5));
                if (e instanceof LivingEntity le) le.swingMainHand();
                b.ore.getWorld().playSound(b.ore.getLocation(), Sound.BLOCK_STONE_HIT, 0.6f, 1f);
                if (tick - b.modeSince >= 4) {                        // 1초 캐면 부서진다
                    Block ore = b.ore;
                    Material m = ore.getType();
                    ore.getWorld().playEffect(ore.getLocation(), Effect.STEP_SOUND, m);
                    if (isDiamond(m)) {
                        ore.setType(Material.AIR);
                        pl.ledger.addHeld(owner, 1);                  // 다이아는 스트리머에게 바로
                        b.mined++;
                    } else {
                        ore.breakNaturally(new ItemStack(Material.DIAMOND_PICKAXE));
                    }
                    release(b);
                    setMode(b, Mode.FOLLOW);
                }
            }
        }
    }

    private void end(Bot b, boolean announce) {
        release(b);
        bots.remove(b);
        try { if (b.npc != null) b.npc.destroy(); } catch (Throwable ignored) { }
        if (announce) for (Player p : Bukkit.getOnlinePlayers())
            pl.net.toast(p, b.donor + " AI 플레이어가 돌아갔습니다. (다이아 " + b.mined + "개 전달)", true);
    }

    /** 연동 시험: 그 자리에 NPC 를 잠깐 세웠다가 치운다. 결과 글을 돌려준다 */
    String selfTest(Location at) {
        if (!ok) return "Citizens 없음";
        try {
            if (registry == null) registry = CitizensAPI.createInMemoryNPCRegistry("diamondserver");
            NPC npc = registry.createNPC(EntityType.PLAYER, "AI");
            npc.setProtected(true);
            npc.getOrAddTrait(Equipment.class).set(Equipment.EquipmentSlot.HAND, new ItemStack(Material.DIAMOND_PICKAXE));
            boolean spawned = npc.spawn(at);
            boolean nav = npc.getNavigator() != null;
            npc.destroy();
            return "NPC 세우기 " + (spawned ? "성공" : "실패") + ", 길찾기 " + (nav ? "있음" : "없음");
        } catch (Throwable t) {
            return "NPC 시험 실패: " + t;
        }
    }

    /** 서버가 꺼질 때: 전부 치운다 */
    void shutdown() {
        for (Bot b : new ArrayList<>(bots)) end(b, false);
        waiting.clear();
    }
}
