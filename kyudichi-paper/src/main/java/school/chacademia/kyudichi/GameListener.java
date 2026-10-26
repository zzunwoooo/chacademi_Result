package school.chacademia.kyudichi;

import io.papermc.paper.event.player.AsyncChatEvent;
import java.lang.reflect.Method;
import org.bukkit.Bukkit;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.entity.TNTPrimed;
import org.bukkit.event.Event;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.FoodLevelChangeEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerSwapHandItemsEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.plugin.Plugin;
import org.bukkit.util.Vector;

/** Bukkit events → the player's match. */
final class GameListener implements Listener {
    private final KyudichiPlugin plugin;
    GameListener(KyudichiPlugin plugin) { this.plugin = plugin; }

    /**
     * The match this player is still playing in. A player counted as left (back after the 60 s window) is no longer in it:
     * no teleport guard, no blocked casts / F key / food, not anyone's ally (#75, 2026-10-25).
     */
    private Match match(Player p) { Match m = plugin.matches().of(p.getUniqueId()); return m != null && m.playing(p.getUniqueId()) ? m : null; }

    // ------------------------------------------------------------------ F key (vanilla swap-hands = F, works without the client mod)
    @EventHandler(priority = EventPriority.LOWEST)
    public void swap(PlayerSwapHandItemsEvent e) {
        Match m = match(e.getPlayer());
        if (m == null) return;
        e.setCancelled(true);
        m.pressKey(e.getPlayer());
    }

    // ------------------------------------------------------------------ damage
    static Player attacker(EntityDamageEvent e) {
        if (!(e instanceof EntityDamageByEntityEvent by)) return null;
        Entity d = by.getDamager();
        if (d instanceof Player p) return p;
        if (d instanceof Projectile pr && pr.getShooter() instanceof Player p) return p;
        if (d instanceof TNTPrimed t && t.getSource() instanceof Player p) return p;
        return null;
    }

    /** Friendly fire and outsiders. */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void pvp(EntityDamageByEntityEvent e) {
        Player a = attacker(e);
        if (a == null) return;
        Match am = match(a);
        if (!(e.getEntity() instanceof Player v)) { return; }
        Match vm = match(v);
        if (am == null && vm == null) return;
        if (am != vm) { e.setCancelled(true); return; }   // outsiders can't hit players and vice versa
        if (a.equals(v)) return;
        MatchPlayer ma = am.get(a.getUniqueId()), mv = am.get(v.getUniqueId());
        if (ma.team == mv.team) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void damage(EntityDamageEvent e) {
        if (!(e.getEntity() instanceof Player p)) return;
        Match m = match(p);
        if (m == null) return;
        MatchPlayer mp = m.get(p.getUniqueId());
        if (m.phase == Match.Phase.ENDED) return;
        if (!m.live() || mp.dead(m.now) || m.now < mp.invulnerableUntil || m.opt.invulnerable().contains(mp.id)) { e.setCancelled(true); return; }
        Player a = attacker(e);
        MatchPlayer ma = a == null ? null : m.get(a.getUniqueId());
        if (ma != null && ma.id.equals(mp.id)) ma = null;
        if (e.getFinalDamage() >= p.getHealth()) {
            e.setCancelled(true);
            if (ma != null) m.recordDamage(ma, mp, p.getHealth());
            m.kill(mp, p, ma);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void damaged(EntityDamageEvent e) {
        if (!(e.getEntity() instanceof Player p) || e.getFinalDamage() <= 0) return;
        Match m = match(p);
        if (m == null || !m.live()) return;
        MatchPlayer mp = m.get(p.getUniqueId());
        Player a = attacker(e);
        MatchPlayer ma = a == null ? null : m.get(a.getUniqueId());
        if (ma != null && !ma.id.equals(mp.id)) m.recordDamage(ma, mp, e.getFinalDamage());
        Vector push = a == null ? null : p.getLocation().toVector().subtract(a.getLocation().toVector());
        m.damaged(mp, ma != null && !ma.id.equals(mp.id) ? ma : null, push);
    }

    /** Safety net (e.g. /kill): never drop items in a match; respawn at the team point.
     *  HIGH (not HIGHEST) so keepInventory is set before MagicCodexBridge's HIGHEST accessory-drop handler,
     *  which skips keepInventory deaths — otherwise accessories were moved into drops and then cleared here (lost). */
    @EventHandler(priority = EventPriority.HIGH)
    public void death(PlayerDeathEvent e) {
        Match m = match(e.getPlayer());
        if (m == null) return;
        e.setKeepInventory(true); e.getDrops().clear(); e.setKeepLevel(true); e.setDroppedExp(0);
        MatchPlayer mp = m.get(e.getPlayer().getUniqueId());
        Bukkit.getScheduler().runTask(plugin, () -> { if (e.getPlayer().isDead()) e.getPlayer().spigot().respawn(); m.kill(mp, e.getPlayer(), null); });
    }

    @EventHandler(ignoreCancelled = true)
    public void food(FoodLevelChangeEvent e) { if (e.getEntity() instanceof Player p && match(p) != null) e.setCancelled(true); }

    @EventHandler(ignoreCancelled = true)
    public void drop(PlayerDropItemEvent e) { if (match(e.getPlayer()) != null) e.setCancelled(true); }

    // ------------------------------------------------------------------ movement, teleports
    @EventHandler(ignoreCancelled = true)
    public void move(PlayerMoveEvent e) {
        Match m = match(e.getPlayer());
        if (m == null || !m.frozen()) return;
        var from = e.getFrom(); var to = e.getTo();
        if (from.getX() != to.getX() || from.getY() != to.getY() || from.getZ() != to.getZ())
            e.setTo(new org.bukkit.Location(from.getWorld(), from.getX(), from.getY(), from.getZ(), to.getYaw(), to.getPitch()));
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void teleport(PlayerTeleportEvent e) {
        Player p = e.getPlayer();
        Match m = match(p);
        if (m != null) {
            if (m.ourTeleport || m.phase == Match.Phase.ENDED || !m.rules.teleportGuard) return;
            switch (e.getCause()) {
                case PLUGIN, ENDER_PEARL, CHORUS_FRUIT, UNKNOWN, SPECTATE -> {
                    // owner 2026-10-20: short hops that stay inside the arena (빗자루 순간이동 마법 등) are fine;
                    // only teleports that leave the arena (귀환, 집, 다른 월드 …) are blocked
                    var to = e.getTo();
                    if (e.getCause() != PlayerTeleportEvent.TeleportCause.SPECTATE && to != null && to.getWorld() == m.world
                            && m.arena.bounds != null && m.arena.bounds.contains(to.toVector())
                            && to.distanceSquared(e.getFrom()) <= 64 * 64) return;
                    e.setCancelled(true);
                    ChacaLink.notify(p, "warning", "kyudichi:teleport", "순간이동할 수 없어요", "큐디치 경기 중이에요", 3000);
                }
                default -> { }
            }
            return;
        }
        Match s = plugin.matches().spectating(p.getUniqueId());
        if (s != null && e.getCause() == PlayerTeleportEvent.TeleportCause.SPECTATE && e.getTo().getWorld() != s.world) e.setCancelled(true);
    }

    // ------------------------------------------------------------------ join / quit
    @EventHandler
    public void join(PlayerJoinEvent e) {
        plugin.store().loadAll(e.getPlayer().getUniqueId(), plugin.hooks().nick(e.getPlayer()));
        plugin.matches().joined(e.getPlayer());
        plugin.link().joined(e.getPlayer());
    }

    @EventHandler
    public void quit(PlayerQuitEvent e) {
        Player p = e.getPlayer();
        Match m = match(p);
        if (m != null) m.quit(p);
        if (plugin.spectate().spectating(p.getUniqueId())) plugin.spectate().leave(p);
        plugin.pings().forget(p.getUniqueId());
        if (plugin.matches().of(p.getUniqueId()) == null) plugin.store().forget(p.getUniqueId());
        plugin.link().quit(p);
    }

    // ------------------------------------------------------------------ spectator chat stays among spectators
    @EventHandler(ignoreCancelled = true)
    public void chat(AsyncChatEvent e) {
        Player p = e.getPlayer();
        if (!plugin.spectate().spectating(p.getUniqueId())) return;
        e.viewers().removeIf(v -> v instanceof Player o && !plugin.spectate().spectating(o.getUniqueId()));
    }

    // ------------------------------------------------------------------ MagicCodexBridge events (by name: no compile dependency)
    void hookBridge() {
        Plugin bridge = Bukkit.getPluginManager().getPlugin("MagicCodexBridge");
        if (bridge == null) { plugin.getLogger().warning("MagicCodexBridge 없음: 퀘이플 소유 중 시전 차단·금지 마법/아티팩트가 동작하지 않음"); return; }
        ClassLoader cl = bridge.getClass().getClassLoader();
        register(cl, "school.magiccodex.paper.MagicCastAttemptEvent", "getSpellId", (e, p, id, cancel) -> {
            Match m = match(p);
            if (m == null || m.phase == Match.Phase.ENDED) return;
            MatchPlayer mp = m.get(p.getUniqueId());
            String why = null;
            // the flight key while riding only gets off the broom: always allowed
            if (id.equals("flight") && plugin.hooks().riding(p)) return;
            if (!m.live()) why = "경기가 시작되면 마법을 쓸 수 있어요";
            else if (mp.dead(m.now)) why = "쓰러진 동안은 마법을 쓸 수 없어요";
            else if (m.quaffle != null && mp.id.equals(m.quaffle.holder)) why = "퀘이플을 들고 있을 때는 마법을 쓸 수 없어요";
            else if (m.duel && m.rules.duelBannedSpells.contains(id)) why = id.equals("flight") ? "1:1 대전에서는 비행할 수 없어요" : "1:1 대전에서 쓸 수 없는 마법이에요";
            else if (!m.duel && m.rules.bannedSpells.contains(id)) why = "큐디치에서 쓸 수 없는 마법이에요";
            if (why != null) cancel.accept(why);
        });
        hookAimPoint(cl);
        register(cl, "school.magiccodex.paper.ArtifactUseAttemptEvent", "getArtifactId", (e, p, id, cancel) -> {
            Match m = match(p);
            if (m != null && m.phase != Match.Phase.ENDED && m.rules.bannedArtifacts.contains(id)) cancel.accept("큐디치에서 쓸 수 없는 아티팩트예요");
        });
    }

    /** 공중 조준: the client sent the wheel-chosen point just before the cast; use it once if it is fresh and inside the arena. */
    private void hookAimPoint(ClassLoader cl) {
        try {
            @SuppressWarnings("unchecked")
            Class<? extends Event> type = (Class<? extends Event>) Class.forName("school.magiccodex.paper.MagicAimPointEvent", true, cl);
            Method player = type.getMethod("getPlayer"), set = type.getMethod("setLocation", org.bukkit.Location.class);
            Bukkit.getPluginManager().registerEvent(type, this, EventPriority.NORMAL, (l, e) -> {
                if (!type.isInstance(e)) return;
                try {
                    Player p = (Player) player.invoke(e);
                    Match m = match(p); if (m == null || !m.live()) return;
                    MatchPlayer mp = m.get(p.getUniqueId());
                    if (mp == null || mp.aim == null || m.now - mp.aimAt > 20) return;
                    var v = mp.aim; mp.aim = null;
                    if (!m.arena.bounds.contains(v) || p.getWorld() != m.world) return;
                    set.invoke(e, new org.bukkit.Location(m.world, v.getX(), v.getY(), v.getZ()));
                } catch (ReflectiveOperationException ex) { plugin.getLogger().warning("MagicAimPointEvent 처리 실패: " + ex); }
            }, plugin, true);
        } catch (ReflectiveOperationException | LinkageError ex) {
            plugin.getLogger().warning("MagicAimPointEvent 없음 (MagicCodexBridge 업데이트 필요): 공중 조준은 최대 사거리로만 됩니다");
        }
    }

    private interface AttemptHandler { void handle(Event e, Player p, String id, java.util.function.Consumer<String> cancel); }

    private void register(ClassLoader cl, String className, String idGetter, AttemptHandler h) {
        try {
            @SuppressWarnings("unchecked")
            Class<? extends Event> type = (Class<? extends Event>) Class.forName(className, true, cl);
            Method player = type.getMethod("getPlayer"), id = type.getMethod(idGetter), setCancelled = type.getMethod("setCancelled", boolean.class),
                    setMessage = type.getMethod("setMessage", String.class);
            Bukkit.getPluginManager().registerEvent(type, this, EventPriority.HIGH, (l, e) -> {
                if (!type.isInstance(e)) return;
                try {
                    h.handle(e, (Player) player.invoke(e), (String) id.invoke(e), msg -> {
                        try { setCancelled.invoke(e, true); setMessage.invoke(e, msg); } catch (ReflectiveOperationException ignored) { }
                    });
                } catch (ReflectiveOperationException ex) { plugin.getLogger().warning(className + " 처리 실패: " + ex); }
            }, plugin, true);
        } catch (ReflectiveOperationException | LinkageError ex) {
            plugin.getLogger().warning(className.substring(className.lastIndexOf('.') + 1) + " 없음 (MagicCodexBridge 업데이트 필요): " + ex.getMessage());
        }
    }

    /** C03: in a match only the match team is friendly (random matching included); a player outside the match is never an ally. */
    void hookAlly() {
        plugin.hooks().listenAlly(this, (a, b) -> {
            Match ma = match(a), mb = match(b);
            if (ma == null && mb == null) return null;
            if (ma != mb) return false;
            MatchPlayer pa = ma.get(a.getUniqueId()), pb = ma.get(b.getUniqueId());
            return pa != null && pb != null && pa.team == pb.team;
        });
    }

    /** #33: 경기 중 선수의 경기 종류 (큐디치 전투 보정용). 경기 밖이면 null. */
    void hookMatch() {
        plugin.hooks().listenMatch(this, p -> { Match m = match(p); return m == null ? null : m.duel ? "duel" : "quidditch"; });
    }

    void hookHeal() {
        plugin.hooks().listenHeal(this, (caster, target, amount, set) -> {
            if (!(caster instanceof Player c)) return;
            Match m = match(target);
            if (m == null || m != match(c)) return;
            MatchPlayer mc = m.get(c.getUniqueId()), mt = m.get(target.getUniqueId());
            if (mc.team != mt.team) set.accept(0);
            else if (!mc.id.equals(mt.id)) {
                double max = target.getAttribute(org.bukkit.attribute.Attribute.MAX_HEALTH) == null ? 20 : target.getAttribute(org.bukkit.attribute.Attribute.MAX_HEALTH).getValue();
                mc.heal += Math.max(0, Math.min(amount, max - target.getHealth()));
            }
        });
    }
}
