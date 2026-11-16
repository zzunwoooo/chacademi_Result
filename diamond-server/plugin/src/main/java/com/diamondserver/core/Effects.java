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
            BLACKOUT = "blackout", LAVA = "lava", BUNNY = "bunny";

    private record Active(String name, long endTick) { }

    private final DiamondPlugin pl;
    private final Map<UUID, Map<String, Active>> active = new HashMap<>();
    private final Map<UUID, Long> nextHiss = new HashMap<>();
    private final Map<Location, BlockData> lavaBlocks = new LinkedHashMap<>();   // 마그마로 바꾼 블럭의 원래 모습
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
    void apply(Player p, String id, String name, int seconds) {
        boolean fresh = !has(p, id);
        active.computeIfAbsent(p.getUniqueId(), k -> new LinkedHashMap<>()).put(id, new Active(name, tick + seconds * 20L));
        if (fresh) start(p, id);
        switch (id) {       // 모드가 직접 처리하는 화면 효과는 남은 시간을 다시 알려 준다
            case REVERSE, BLACKOUT -> pl.net.fx(p, id, seconds * 1000L);
            default -> { }
        }
        if (id.equals(BLACKOUT) && !pl.net.hasMod(p)) {        // 모드가 없으면 어둠 효과로 대신
            p.addPotionEffect(new PotionEffect(PotionEffectType.DARKNESS, seconds * 20, 0, false, false));
            p.addPotionEffect(new PotionEffect(PotionEffectType.BLINDNESS, seconds * 20, 0, false, false));
        }
        sendList(p);
    }

    private void start(Player p, String id) {
        if (id.equals(ANT)) scale(p, pl.getConfig().getDouble("effects.ant-scale", 0.2));
    }

    private void stop(Player p, String id) {
        switch (id) {
            case ANT -> scale(p, 1.0);
            case REVERSE, BLACKOUT -> pl.net.fx(p, id, 0);
            default -> { }
        }
        if (id.equals(LAVA) && !anyone(LAVA)) restoreLava();
    }

    private static void scale(Player p, double v) {
        AttributeInstance a = p.getAttribute(Attribute.SCALE);
        if (a != null) a.setBaseValue(v);
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

    // ── 바닥은 용암: 밟고 선 블럭이 마그마로 ──────────────────────
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
            rows.add(new String[]{e.getKey(), e.getValue().name, String.valueOf(Math.max(0, (e.getValue().endTick - tick) * 50))});
        pl.net.effects(p, rows);
    }

    void sendListTo(Player p) { sendList(p); }
}
