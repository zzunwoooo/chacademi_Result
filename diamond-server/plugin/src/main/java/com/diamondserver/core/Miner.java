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
 * 후원자 닉네임·스킨으로 스트리머 옆에 나타나 스스로 굴을 파며 다이아 광석을 찾아 캔다 (11/23 바뀜, step 참고).
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
        Block dig;                                  // 지금 파는 블럭
        int digLeft, tries, run;                    // 남은 파기 횟수 · 목표까지 시도 · 같은 방향으로 판 칸
        org.bukkit.block.BlockFace heading;         // 굴 파는 방향

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
            for (Player p : Bukkit.getOnlinePlayers()) pl.net.toast(p, donor + " AI 플레이어가 " + pl.nick(owner) + "님을 도우러 왔습니다.", true);
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

    // [기능:AI 굴 파기 시작]
    // 11/23 오너: "따라다니기만 해서 가치가 없어 → 주변 블럭 알아서 캐면서 다이아 찾아서 캐게".
    // 스트리머를 따라다니지 않고 스스로 굴을 판다: 주변(miner.radius)에 다이아 광석이 보이면 그쪽으로 파고 들어가 캐고,
    // 없으면 다이아가 많은 깊이(miner.dig-to-y)까지 계단식으로 내려간 뒤 옆으로 굴을 파며 계속 찾는다.
    // 파는 것은 돌·흙 같은 자연 블럭만 (상자·건축물·기반암은 안 건드림), 파낸 돌은 떨어뜨리지 않음, 용암·물은 조약돌로 막고 지나감.
    private static final Set<Material> DIGGABLE = java.util.EnumSet.of(
            Material.STONE, Material.DEEPSLATE, Material.COBBLED_DEEPSLATE, Material.TUFF, Material.GRANITE, Material.DIORITE, Material.ANDESITE,
            Material.CALCITE, Material.DRIPSTONE_BLOCK, Material.POINTED_DRIPSTONE, Material.SMOOTH_BASALT, Material.COBBLESTONE, Material.MOSSY_COBBLESTONE,
            Material.DIRT, Material.COARSE_DIRT, Material.ROOTED_DIRT, Material.GRASS_BLOCK, Material.PODZOL, Material.MYCELIUM, Material.MUD, Material.CLAY,
            Material.MOSS_BLOCK, Material.GRAVEL, Material.SAND, Material.RED_SAND, Material.SANDSTONE, Material.RED_SANDSTONE,
            Material.TERRACOTTA, Material.SNOW_BLOCK, Material.ICE, Material.PACKED_ICE, Material.NETHERRACK, Material.BASALT, Material.BLACKSTONE,
            Material.SOUL_SAND, Material.SOUL_SOIL, Material.END_STONE, Material.AMETHYST_BLOCK, Material.BUDDING_AMETHYST, Material.MAGMA_BLOCK);

    private boolean diggable(Block bl) {
        Material m = bl.getType();
        if (m == Material.BEDROCK || bl.getState() instanceof org.bukkit.block.TileState) return false;
        return DIGGABLE.contains(m) || isOre(m) || m.name().endsWith("_TERRACOTTA") || m.name().endsWith("_LEAVES") || m.name().endsWith("_LOG");
    }

    private static boolean clear(Block bl) { return bl.isPassable() && !bl.isLiquid(); }

    private static int sgn(int v) { return Integer.compare(v, 0); }

    /** 이 블럭을 파기 시작한다 (다이아·광석은 조금 더 오래) */
    private void dig(Bot b, Block bl) {
        b.dig = bl;
        Material m = bl.getType();
        b.digLeft = isDiamond(m) ? 4 : isOre(m) || m == Material.DEEPSLATE || m == Material.COBBLED_DEEPSLATE ? 2 : 1;
    }

    private void breakBlock(Bot b, Player owner, Block bl) {
        Material m = bl.getType();
        if (m.isAir()) return;
        bl.getWorld().playEffect(bl.getLocation(), Effect.STEP_SOUND, m);
        bl.setType(Material.AIR);                                       // 파낸 것은 떨어뜨리지 않음 (굴이 돌로 어지럽지 않게)
        if (isDiamond(m)) {
            pl.ledger.addHeld(owner, 1);                                // 다이아는 스트리머에게 바로
            b.mined++;
            pl.net.toast(owner, b.donor + " AI 플레이어가 다이아를 캤습니다! (+1, 지금까지 " + b.mined + "개)", true);
            owner.playSound(owner.getLocation(), Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 0.8f, 1.2f);
        }
    }

    /** 용암·물이 굴로 흘러들지 않게 그 칸과 둘레를 조약돌로 막는다 */
    private static void plug(Block cell) {
        for (org.bukkit.block.BlockFace f : new org.bukkit.block.BlockFace[]{org.bukkit.block.BlockFace.SELF, org.bukkit.block.BlockFace.UP, org.bukkit.block.BlockFace.DOWN,
                org.bukkit.block.BlockFace.NORTH, org.bukkit.block.BlockFace.SOUTH, org.bukkit.block.BlockFace.EAST, org.bukkit.block.BlockFace.WEST}) {
            Block n = cell.getRelative(f);
            if (n.isLiquid()) n.setType(f == org.bukkit.block.BlockFace.SELF ? Material.AIR : Material.COBBLESTONE, false);
        }
    }

    /**
     * 발 위치 feet 에서 next 칸으로 한 칸 나아간다: 막혀 있으면 막은 블럭을 파기 시작하고, 뚫려 있으면 옮겨 간다.
     * 팔 수 없는 블럭(기반암·건축물)에 막히면 false.
     */
    private boolean advance(Bot b, Block feet, Block next) {
        if (next.getY() > feet.getY()) {                                // 올라갈 땐 머리 위도 뚫어야 함
            Block above = feet.getRelative(0, 2, 0);
            if (!clear(above)) { if (above.isLiquid()) { plug(above); return true; } if (!diggable(above)) return false; dig(b, above); return true; }
        }
        Block nh = next.getRelative(0, 1, 0);
        for (Block c : new Block[]{nh, next}) {
            if (c.isLiquid()) { plug(c); return true; }
            if (!clear(c)) { if (!diggable(c)) return false; dig(b, c); return true; }
        }
        plug(next); plug(nh);
        Block under = next.getRelative(0, -1, 0);
        if (under.getType() == Material.LAVA) under.setType(Material.COBBLESTONE, false);
        Location to = next.getLocation().add(0.5, 0, 0.5);
        Location from = b.npc.getEntity().getLocation();
        to.setYaw(from.getYaw()); to.setPitch(from.getPitch());
        if (next.getX() != feet.getX() || next.getZ() != feet.getZ())
            to.setDirection(new org.bukkit.util.Vector(next.getX() - feet.getX(), 0, next.getZ() - feet.getZ()));
        b.npc.teleport(to, PlayerTeleportEvent.TeleportCause.PLUGIN);
        return true;
    }

    /** 다이아 광석만 찾는다 (가장 가까운 것) */
    private Block scanDiamond(Block center) {
        int r = radius();
        Block best = null;
        double bd = Double.MAX_VALUE;
        for (int dx = -r; dx <= r; dx++) for (int dy = -r; dy <= r; dy++) for (int dz = -r; dz <= r; dz++) {
            Block bl = center.getRelative(dx, dy, dz);
            if (!isDiamond(bl.getType()) || claimed.contains(bl) || skipped.contains(bl)) continue;
            double d = dx * dx + dy * dy + dz * dz;
            if (d < bd) { bd = d; best = bl; }
        }
        return best;
    }

    private static boolean touches(Block feet, Block ore) {
        for (Block c : new Block[]{feet, feet.getRelative(0, 1, 0)}) {
            int dx = Math.abs(c.getX() - ore.getX()), dy = Math.abs(c.getY() - ore.getY()), dz = Math.abs(c.getZ() - ore.getZ());
            if (dx + dy + dz == 1) return true;
        }
        return false;
    }

    private void step(Bot b) {
        Player owner = Bukkit.getPlayer(b.owner);
        if (owner == null || tick >= b.endTick) { end(b, owner != null); return; }
        if (!b.npc.isSpawned()) { b.npc.spawn(beside(owner)); return; }
        Entity e = b.npc.getEntity();
        Location at = e.getLocation();
        String lobby = pl.getConfig().getString("lobby-portal.world", "lobby");

        // 로비에서는 파지 않고 스트리머 옆에만 있음
        if (owner.getWorld().getName().equals(lobby)) {
            if (at.getWorld() != owner.getWorld() || at.distanceSquared(owner.getLocation()) > 6 * 6) b.npc.teleport(beside(owner), PlayerTeleportEvent.TeleportCause.PLUGIN);
            return;
        }
        // 너무 멀어지거나 다른 월드면 스트리머 옆에서 다시 시작
        int leash = Math.max(16, pl.getConfig().getInt("miner.leash", 48));
        double hx = at.getX() - owner.getLocation().getX(), hz = at.getZ() - owner.getLocation().getZ();   // 가로 거리만 (아래로 파 내려가는 건 괜찮음)
        if (at.getWorld() != owner.getWorld() || hx * hx + hz * hz > (double) leash * leash) {
            release(b);
            b.dig = null;
            b.npc.getNavigator().cancelNavigation();
            b.npc.teleport(beside(owner), PlayerTeleportEvent.TeleportCause.PLUGIN);
            return;
        }

        Block feet = at.getBlock();
        // 파는 중
        if (b.dig != null) {
            if (b.dig.getType().isAir() || b.dig.isLiquid()) { b.dig = null; return; }
            b.npc.faceLocation(b.dig.getLocation().add(0.5, 0.5, 0.5));
            if (e instanceof LivingEntity le) le.swingMainHand();
            b.dig.getWorld().playSound(b.dig.getLocation(), Sound.BLOCK_STONE_HIT, 0.6f, 1f);
            if (--b.digLeft <= 0) {
                Block d = b.dig;
                b.dig = null;
                if (d.equals(b.ore)) release(b);
                breakBlock(b, owner, d);
            }
            return;
        }
        // 발밑이 비었으면 떨어짐 (동굴 바닥까지)
        if (feet.getRelative(0, -1, 0).isPassable() && !feet.getRelative(0, -1, 0).isLiquid()) {
            for (int i = 2; i <= 24; i++) {
                Block g = feet.getRelative(0, -i, 0);
                if (g.isLiquid()) { if (g.getType() == Material.LAVA) g.setType(Material.COBBLESTONE, false); }
                if (!g.isPassable() || g.getType() == Material.COBBLESTONE) {
                    b.npc.teleport(g.getRelative(0, 1, 0).getLocation().add(0.5, 0, 0.5), PlayerTeleportEvent.TeleportCause.PLUGIN);
                    return;
                }
            }
            return;
        }

        // 다이아 광석 찾기 (1.5초마다)
        if (b.ore != null && !isDiamond(b.ore.getType())) release(b);
        if (b.ore == null && tick - b.lastScan >= 6) {
            b.lastScan = tick;
            Block ore = scanDiamond(feet);
            if (ore != null) { b.ore = ore; claimed.add(ore); b.tries = 0; }
        }
        if (b.ore != null) {
            if (touches(feet, b.ore)) { dig(b, b.ore); return; }
            if (++b.tries > 160) { skipped.add(b.ore); release(b); return; }   // 40초 넘게 못 닿으면 포기
            int dx = b.ore.getX() - feet.getX(), dy = b.ore.getY() - feet.getY(), dz = b.ore.getZ() - feet.getZ();
            Block next;
            if (Math.abs(dx) >= Math.abs(dz) && Math.abs(dx) >= Math.abs(dy) && dx != 0) next = feet.getRelative(sgn(dx), dy > 1 ? 1 : dy < 0 ? -1 : 0, 0);
            else if (Math.abs(dz) >= Math.abs(dy) && dz != 0) next = feet.getRelative(0, dy > 1 ? 1 : dy < 0 ? -1 : 0, sgn(dz));
            else next = feet.getRelative(0, sgn(dy), 0);
            if (next.getY() < feet.getY() && next.getX() == feet.getX() && next.getZ() == feet.getZ()) {   // 바로 아래로: 발밑을 판다
                Block under = feet.getRelative(0, -1, 0);
                if (!diggable(under)) { skipped.add(b.ore); release(b); return; }
                dig(b, under);
                return;
            }
            if (!advance(b, feet, next)) { skipped.add(b.ore); release(b); }
            return;
        }

        // 안 보이면: 다이아 깊이까지 계단식으로 내려가고, 거기선 옆으로 굴을 판다
        if (b.heading == null || ++b.run > 40) { b.heading = randomFace(); b.run = 0; }
        int targetY = pl.getConfig().getInt("miner.dig-to-y", -54);
        boolean down = owner.getWorld().getEnvironment() == org.bukkit.World.Environment.NORMAL && feet.getY() > targetY;
        Block next = feet.getRelative(b.heading.getModX(), down ? -1 : 0, b.heading.getModZ());
        if (!advance(b, feet, next)) { b.heading = randomFace(); b.run = 0; }
    }

    private static org.bukkit.block.BlockFace randomFace() {
        org.bukkit.block.BlockFace[] f = {org.bukkit.block.BlockFace.NORTH, org.bukkit.block.BlockFace.SOUTH, org.bukkit.block.BlockFace.EAST, org.bukkit.block.BlockFace.WEST};
        return f[java.util.concurrent.ThreadLocalRandom.current().nextInt(4)];
    }
    // [기능:AI 굴 파기 끝]

    /** 예전 동작(따라다니며 주변 광석 캐기) — 지금은 쓰지 않음 */
    @SuppressWarnings("unused")
    private void stepFollow(Bot b, Player owner, Entity e, Location at) {
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
