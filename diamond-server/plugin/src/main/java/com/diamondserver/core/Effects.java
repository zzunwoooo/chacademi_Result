package com.diamondserver.core;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.block.Block;
import org.bukkit.block.TileState;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.Item;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockDropItemEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.util.Vector;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 시간이 걸리는 장난 효과를 사람별로 관리한다 (걸기 · 매 틱 처리 · 끝나면 되돌리기).
 * 같은 효과가 또 걸리면 남은 시간만 새로 채운다. 나가거나 서버가 꺼지면 바로 되돌린다.
 */
final class Effects implements Listener {
    static final String ANT = "ant", CURSE = "curse", CREEPER = "creeper", REVERSE = "reverse",
            BLACKOUT = "blackout", LAVA = "lava", BUNNY = "bunny",
            MIDAS = "midas", GIANT = "giant", DANCE = "dance", STUN = "stun", SEAL = "seal", MONSTERS = "monsters";

    private record Active(String name, String icon, long endTick, long totalTicks) { }

    private final DiamondPlugin pl;
    private final Map<UUID, Map<String, Active>> active = new HashMap<>();
    private final Map<UUID, Long> nextHiss = new HashMap<>();
    private final Map<Location, BlockData> lavaBlocks = new LinkedHashMap<>();   // 마그마로 바꾼 블럭의 원래 모습
    private final Map<UUID, Map<Location, BlockData>> midasBlocks = new HashMap<>();   // 미다스: 사람별로 광석으로 바꾼 블럭의 원래 모습
    private long tick;

    Effects(DiamondPlugin pl) {
        this.pl = pl;
        Bukkit.getScheduler().runTaskTimer(pl, this::onTick, 1L, 1L);
    }

    boolean has(Player p, String id) {
        Map<String, Active> m = active.get(p.getUniqueId());
        return m != null && m.containsKey(id);
    }

    private boolean anyone(String id) {
        for (Map<String, Active> m : active.values()) if (m.containsKey(id)) return true;
        return false;
    }

    /** 효과를 건다 (이미 걸려 있으면 시간만 새로) */
    void apply(Player p, String id, String name, String icon, int seconds) {
        // 개미화와 거인화는 같이 걸릴 수 없다: 나중에 걸린 것이 앞의 것을 지운다
        if (id.equals(ANT)) drop(p, GIANT); else if (id.equals(GIANT)) drop(p, ANT);
        boolean fresh = !has(p, id);
        active.computeIfAbsent(p.getUniqueId(), k -> new LinkedHashMap<>()).put(id, new Active(name, icon, tick + seconds * 20L, seconds * 20L));
        if (fresh) start(p, id);
        else if (id.equals(MONSTERS)) pl.monsters.summon(p);         // 습격 중에 또 걸리면 몇 마리 더
        switch (id) {       // 모드가 직접 처리하는 화면 효과는 남은 시간을 다시 알려 준다
            case REVERSE, BLACKOUT -> pl.net.fx(p, id, seconds * 1000L);
            default -> { }
        }
        if (id.equals(BLACKOUT) && !pl.net.hasMod(p)) {        // 모드가 없으면 어둠 효과로 대신
            p.addPotionEffect(new PotionEffect(PotionEffectType.DARKNESS, seconds * 20, 0, false, false));
            p.addPotionEffect(new PotionEffect(PotionEffectType.BLINDNESS, seconds * 20, 0, false, false));
        }
        // 머리 위 표시 모델
        switch (id) {
            case CURSE -> pl.models.followHead(p, "curse_mark", seconds, "idle", "leave");
            case MIDAS -> pl.models.followHead(p, "golden_crown", seconds, "idle", "leave");
            case DANCE -> pl.models.followHead(p, "disco_ball", seconds, "idle", "leave");
            case STUN -> pl.models.followHead(p, "stun_stars", seconds, "idle", "leave");
            default -> { }
        }
        if (id.equals(DANCE)) for (Player o : Bukkit.getOnlinePlayers()) pl.net.motion(o, p.getUniqueId(), "forced_dance");
        sendList(p);
    }

    /** 효과 하나를 조용히 지운다 (되돌리기 없이 목록에서만) */
    private void drop(Player p, String id) {
        Map<String, Active> m = active.get(p.getUniqueId());
        if (m != null) m.remove(id);
    }

    private void start(Player p, String id) {
        if (id.equals(ANT)) scale(p, pl.getConfig().getDouble("effects.ant-scale", 0.2));
        if (id.equals(GIANT)) scale(p, pl.getConfig().getDouble("effects.giant-scale", 2.0));
        if (id.equals(SEAL)) pl.seal.seal(p, pl.getConfig().getInt("effects.seal-slots", 9));
        if (id.equals(MONSTERS)) pl.monsters.summon(p);
    }

    private void stop(Player p, String id) {
        switch (id) {
            case ANT, GIANT -> { if (!has(p, ANT) && !has(p, GIANT)) scale(p, 1.0); }
            case REVERSE, BLACKOUT -> pl.net.fx(p, id, 0);
            case MIDAS -> restoreMidas(p.getUniqueId());
            case CURSE -> pl.models.clearHead(p, "curse_mark");
            case DANCE -> { pl.models.clearHead(p, "disco_ball"); for (Player o : Bukkit.getOnlinePlayers()) pl.net.motion(o, p.getUniqueId(), "stop"); }
            case STUN -> pl.models.clearHead(p, "stun_stars");
            case SEAL -> pl.seal.unseal(p);
            case MONSTERS -> pl.monsters.clear(p);
            default -> { }
        }
        if (id.equals(LAVA) && !anyone(LAVA)) restoreLava();
    }

    private static void scale(Player p, double v) {
        AttributeInstance a = p.getAttribute(Attribute.SCALE);
        if (a != null) a.setBaseValue(v);
    }

    /** 효과 하나를 바로 끝낸다 (되돌리기 포함) */
    void end(Player p, String id) {
        Map<String, Active> m = active.get(p.getUniqueId());
        if (m == null || m.remove(id) == null) return;
        stop(p, id);
        sendList(p);
    }

    /** 그 사람의 효과를 전부 끝낸다 */
    void clear(Player p) {
        Map<String, Active> m = active.remove(p.getUniqueId());
        nextHiss.remove(p.getUniqueId());
        if (m != null) for (String id : new ArrayList<>(m.keySet())) stop(p, id);
        sendList(p);
    }

    /** 서버가 꺼질 때: 전부 되돌린다 */
    void shutdown() {
        for (Player p : Bukkit.getOnlinePlayers()) clear(p);
        restoreLava();
        for (UUID id : new ArrayList<>(midasBlocks.keySet())) restoreMidas(id);
    }

    private void onTick() {
        tick++;
        for (Iterator<Map.Entry<UUID, Map<String, Active>>> it = active.entrySet().iterator(); it.hasNext(); ) {
            Map.Entry<UUID, Map<String, Active>> e = it.next();
            Player p = Bukkit.getPlayer(e.getKey());
            if (p == null) continue;
            Map<String, Active> m = e.getValue();
            boolean changed = false;
            for (Iterator<Map.Entry<String, Active>> i2 = m.entrySet().iterator(); i2.hasNext(); ) {
                Map.Entry<String, Active> a = i2.next();
                if (a.getValue().endTick > tick) continue;
                i2.remove();
                stopLater(p, a.getKey());
                changed = true;
            }
            if (m.containsKey(BUNNY)) bunny(p);
            if (m.containsKey(LAVA) && tick % 4 == 0) lava(p);
            if (m.containsKey(MIDAS) && tick % 2 == 0) midas(p);
            if (m.containsKey(CREEPER)) hiss(p);
            if (m.containsKey(BLACKOUT) && tick % 30 == 0) p.playSound(p.getLocation(), Sound.ENTITY_WARDEN_HEARTBEAT, 1.5f, 1f);
            if (changed) sendList(p);
            if (m.isEmpty()) it.remove();
        }
    }

    /** 끝난 효과 되돌리기는 목록에서 뺀 다음에 해야 "아직 걸린 사람이 있나" 판단이 맞는다 */
    private void stopLater(Player p, String id) {
        Bukkit.getScheduler().runTask(pl, () -> stop(p, id));
    }

    // ── 강제 바니홉: 땅에 닿을 때마다 뛴다 ──────────────────────
    @SuppressWarnings("deprecation")
    private void bunny(Player p) {
        if (!p.isOnGround() || p.isInsideVehicle() || p.isDead()) return;
        Vector v = p.getVelocity();
        p.setVelocity(new Vector(v.getX(), 0.42, v.getZ()));
    }

    // ── 바닥은 마그마 블럭: 밟고 선 블럭이 마그마로 ──────────────────────
    @SuppressWarnings("deprecation")
    private void lava(Player p) {
        if (!p.isOnGround() || p.isDead()) return;
        Block b = p.getLocation().subtract(0, 0.2, 0).getBlock();
        Material t = b.getType();
        if (t == Material.MAGMA_BLOCK || t == Material.BEDROCK || !t.isSolid() || !t.isOccluding()) return;
        if (b.getState() instanceof TileState) return;             // 상자·화로 같은 것은 건드리지 않는다
        lavaBlocks.putIfAbsent(b.getLocation(), b.getBlockData());
        b.setType(Material.MAGMA_BLOCK, false);
    }

    private void restoreLava() {
        for (Map.Entry<Location, BlockData> e : lavaBlocks.entrySet()) {
            Block b = e.getKey().getBlock();
            if (b.getType() == Material.MAGMA_BLOCK) b.setBlockData(e.getValue(), false);   // 캐 간 자리는 그대로 둔다
        }
        lavaBlocks.clear();
    }

    // ── 미다스의 손: 밟는 블럭이 다이아 광석으로. 끝나면 안 캔 광석 대부분은 원래 블럭으로 ──────
    @SuppressWarnings("deprecation")
    private void midas(Player p) {
        if (!p.isOnGround() || p.isDead()) return;
        Block b = p.getLocation().subtract(0, 0.2, 0).getBlock();
        Material t = b.getType();
        if (t.name().endsWith("_ORE") || t == Material.BEDROCK || !t.isSolid() || !t.isOccluding()) return;
        if (b.getState() instanceof TileState) return;
        midasBlocks.computeIfAbsent(p.getUniqueId(), k -> new LinkedHashMap<>()).putIfAbsent(b.getLocation(), b.getBlockData());
        b.setType(b.getY() < 0 || t.name().contains("DEEPSLATE") ? Material.DEEPSLATE_DIAMOND_ORE : Material.DIAMOND_ORE, false);
        b.getWorld().playSound(b.getLocation(), Sound.BLOCK_AMETHYST_BLOCK_CHIME, 0.5f, 1.6f);
    }

    private void restoreMidas(UUID id) {
        Map<Location, BlockData> m = midasBlocks.remove(id);
        if (m == null) return;
        double keep = Math.max(0, Math.min(1, pl.getConfig().getDouble("effects.midas-keep-ratio", 0.15)));
        for (Map.Entry<Location, BlockData> e : m.entrySet()) {
            Block b = e.getKey().getBlock();
            boolean ore = b.getType() == Material.DIAMOND_ORE || b.getType() == Material.DEEPSLATE_DIAMOND_ORE;
            if (ore && ThreadLocalRandom.current().nextDouble() >= keep) b.setBlockData(e.getValue(), false);
        }
    }

    // ── 거인화: 몸이 커져 머리가 천장 블럭에 끼어도 질식 피해는 받지 않는다 (좋은 이벤트가 갱도에서 사망으로 이어지지 않게) ──
    @EventHandler(ignoreCancelled = true)
    public void onGiantSuffocate(org.bukkit.event.entity.EntityDamageEvent e) {
        if (e.getCause() == org.bukkit.event.entity.EntityDamageEvent.DamageCause.SUFFOCATION
                && e.getEntity() instanceof Player p && has(p, GIANT)) e.setCancelled(true);
    }

    // ── 거인화: 한 번 캘 때 3x3 ─────────────────────────────────
    @EventHandler(ignoreCancelled = true, priority = org.bukkit.event.EventPriority.MONITOR)
    public void onGiantBreak(org.bukkit.event.block.BlockBreakEvent e) {
        Player p = e.getPlayer();
        if (!has(p, GIANT)) return;
        Block c = e.getBlock();
        ItemStack tool = p.getInventory().getItemInMainHand();
        float pitch = p.getLocation().getPitch();
        float yaw = ((p.getLocation().getYaw() % 360) + 360) % 360;
        boolean flat = Math.abs(pitch) > 50;                         // 위·아래를 보고 캐면 바닥 면으로 3x3
        boolean alongX = (yaw > 45 && yaw < 135) || (yaw > 225 && yaw < 315);   // 동·서를 보고 있음
        for (int a = -1; a <= 1; a++) for (int b2 = -1; b2 <= 1; b2++) {
            if (a == 0 && b2 == 0) continue;
            Block n = flat ? c.getRelative(a, 0, b2) : alongX ? c.getRelative(0, a, b2) : c.getRelative(b2, a, 0);
            Material t = n.getType();
            if (t.isAir() || t == Material.BEDROCK || !t.isSolid() || t.getHardness() < 0 || n.getState() instanceof TileState) continue;
            n.breakNaturally(tool);
        }
    }

    // ── 강제 댄스 · 기절: 자리에서 움직일 수 없다 (둘러보기는 됨) ────────────
    @EventHandler(ignoreCancelled = true)
    public void onMove(org.bukkit.event.player.PlayerMoveEvent e) {
        Player p = e.getPlayer();
        if (!has(p, DANCE) && !has(p, STUN)) return;
        Location from = e.getFrom(), to = e.getTo();
        if (from.getX() == to.getX() && from.getZ() == to.getZ() && to.getY() <= from.getY()) return;   // 떨어지는 것은 막지 않는다
        Location stay = from.clone();
        stay.setYaw(to.getYaw());
        stay.setPitch(to.getPitch());
        e.setTo(stay);
    }

    // ── 스토커 크리퍼: 등 뒤에서 "쉬익" 소리만 ────────────────────
    private void hiss(Player p) {
        long at = nextHiss.getOrDefault(p.getUniqueId(), 0L);
        if (tick < at) return;
        nextHiss.put(p.getUniqueId(), tick + ThreadLocalRandom.current().nextInt(50, 130));
        Location behind = p.getLocation().subtract(p.getLocation().getDirection().setY(0).normalize().multiply(2.5));
        p.playSound(behind, Sound.ENTITY_CREEPER_PRIMED, 1f, 1f);
    }

    // ── 저주받은 곡괭이: 다이아가 석탄으로 나온다 ─────────────────
    @EventHandler(ignoreCancelled = true)
    public void onDrop(BlockDropItemEvent e) {
        if (!has(e.getPlayer(), CURSE)) return;
        for (Item item : e.getItems()) {
            ItemStack s = item.getItemStack();
            if (s.getType() != Material.DIAMOND) continue;
            item.setItemStack(new ItemStack(Material.COAL, s.getAmount()));
        }
    }

    // ── 즉시 끝나는 장난 ──────────────────────────────────────
    /** 핫바 셔플: 핫바 9칸을 섞는다 */
    static void shuffleHotbar(Player p) {
        List<ItemStack> bar = new ArrayList<>();
        for (int i = 0; i < 9; i++) bar.add(p.getInventory().getItem(i));
        List<ItemStack> before = new ArrayList<>(bar);
        for (int tries = 0; tries < 5; tries++) {
            Collections.shuffle(bar);
            boolean same = true;
            for (int i = 0; i < 9; i++) if (bar.get(i) != before.get(i)) { same = false; break; }
            if (!same) break;
        }
        for (int i = 0; i < 9; i++) p.getInventory().setItem(i, bar.get(i));
        p.playSound(p.getLocation(), Sound.ENTITY_ITEM_PICKUP, 0.8f, 0.6f);
    }

    /** 자리 셔플: 접속한 사람들의 위치를 서로 바꾼다 (제자리에 남는 사람 없음) */
    static boolean swapPlaces(List<Player> players) {
        if (players.size() < 2) return false;
        List<Player> order = new ArrayList<>(players);
        Collections.shuffle(order);
        List<Location> spots = new ArrayList<>();
        for (Player p : order) spots.add(p.getLocation());
        for (int i = 0; i < order.size(); i++) {
            Player p = order.get(i);
            p.teleport(spots.get((i + 1) % order.size()));
            p.playSound(p.getLocation(), Sound.ENTITY_ENDERMAN_TELEPORT, 0.8f, 1.2f);
        }
        return true;
    }

    // ── 접속·퇴장 ────────────────────────────────────────────
    @EventHandler
    public void onQuit(PlayerQuitEvent e) { clear(e.getPlayer()); }

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        // 서버가 갑자기 꺼졌을 때 작아진 채로 남는 일이 없게
        if (!has(e.getPlayer(), ANT)) scale(e.getPlayer(), 1.0);
    }

    /** 지금 걸려 있는 효과 목록을 모드에 보낸다 (화면에 남은 시간 표시) */
    private void sendList(Player p) {
        if (!p.isOnline() || !pl.net.hasMod(p)) return;
        List<String[]> rows = new ArrayList<>();
        Map<String, Active> m = active.get(p.getUniqueId());
        if (m != null) for (Map.Entry<String, Active> e : m.entrySet())
            rows.add(new String[]{e.getKey(), e.getValue().name, String.valueOf(Math.max(0, (e.getValue().endTick - tick) * 50)), e.getValue().icon, String.valueOf(e.getValue().totalTicks * 50)});
        pl.net.effects(p, rows);
    }

    void sendListTo(Player p) { sendList(p); }
}
