package school.chacademia.kyudichi;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Logger;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.plugin.Plugin;

/**
 * Soft links to the other Chacademi plugins, all by reflection so this plugin loads (and degrades) without them.
 * Every method is main-thread only. A missing plugin or method = the feature quietly does nothing (logged once).
 */
final class Hooks {
    private final Plugin plugin; private final Logger log;
    Hooks(Plugin plugin) { this.plugin = plugin; this.log = plugin.getLogger(); }

    private final java.util.Set<String> warned = new java.util.HashSet<>();
    private void warnOnce(String key, String msg) { if (warned.add(key)) log.warning(msg); }
    void reset() { warned.clear(); vfxResolved = false; manaResolved = false; BallVisual.ModelEngineVisual.reset(); }

    // ------------------------------------------------------------------ PortableVFX
    private boolean vfxResolved; private Object vfx; private Method vfxPlay, vfxStop, handle; private Constructor<?> request;

    private boolean vfx() {
        if (vfxResolved) return vfx != null;
        vfxResolved = true; vfx = null;
        Plugin p = Bukkit.getPluginManager().getPlugin("PortableVFX");
        if (p == null || !p.isEnabled()) return false;
        try {
            ClassLoader cl = p.getClass().getClassLoader();
            Class<?> svc = Class.forName("dev.portablevfx.paper.api.PortableVfxService", true, cl);
            Class<?> req = Class.forName("dev.portablevfx.paper.api.EffectRequest", true, cl);
            var reg = Bukkit.getServicesManager().getRegistration(svc);
            if (reg == null) return false;
            request = req.getConstructor(String.class, String.class, double.class, double.class, double.class,
                    float.class, int.class, float.class, float.class, float.class, int.class, float.class, double.class);
            vfxPlay = svc.getMethod("play", req);
            vfxStop = svc.getMethod("stop", UUID.class);
            handle = vfxPlay.getReturnType().getMethod("handle");
            vfx = reg.getProvider();
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) { warnOnce("vfx", "PortableVFX 연결 실패: " + e); }
        return vfx != null;
    }

    /** Plays claude:<pack>/<system>; returns the handle (for stop) or null. */
    UUID playVfx(String pack, String system, Location at, int ticks) { return playVfx(pack, system, at, ticks, 1f); }
    UUID playVfx(String pack, String system, Location at, int ticks, float scale) {
        if (at.getWorld() == null || !vfx()) return null;
        try {
            Object r = request.newInstance("claude:" + pack + "/" + system, at.getWorld().getName(), at.getX(), at.getY(), at.getZ(),
                    Math.clamp(scale, 0.1f, 4f), Math.clamp(ticks, 1, 12000), 0f, 0f, 0f, 0xFFFFFF, 1f, 96.0);
            return (UUID) handle.invoke(vfxPlay.invoke(vfx, r));
        } catch (ReflectiveOperationException | RuntimeException e) {
            Throwable c = e.getCause() != null ? e.getCause() : e;
            if (!(c instanceof java.util.concurrent.RejectedExecutionException)) warnOnce("vfxplay", "성소 VFX 재생 실패 (" + pack + "/" + system + "): " + c);
            return null;
        }
    }
    void stopVfx(UUID h) {
        if (h == null || !vfx()) return;
        try { vfxStop.invoke(vfx, h); } catch (ReflectiveOperationException | RuntimeException ignored) { }
    }

    // ------------------------------------------------------------------ Mana (MagicCodexBridge ManaService)
    private boolean manaResolved; private Object mana; private Method manaAdd, manaSnapshot, snapMax;

    private boolean mana() {
        if (manaResolved) return mana != null;
        manaResolved = true; mana = null;
        try {
            for (Class<?> c : Bukkit.getServicesManager().getKnownServices()) {
                if (!c.getName().equals("school.magiccodex.paper.ManaService")) continue;
                var reg = Bukkit.getServicesManager().getRegistration(c);
                if (reg == null) break;
                manaAdd = c.getMethod("add", UUID.class, double.class);
                manaSnapshot = c.getMethod("snapshot", UUID.class);
                mana = reg.getProvider();
            }
        } catch (ReflectiveOperationException | RuntimeException e) { warnOnce("mana", "마나 연결 실패: " + e); }
        if (mana == null) warnOnce("mana-missing", "MagicCodexBridge ManaService 없음: 마나 성소는 효과 없음");
        return mana != null;
    }

    private Method manaClear; private boolean manaClearMissing;
    /** Makes a spell ready again (ManaService.clearCooldown; the client gets the reset too). */
    void clearCooldown(Player p, String spell) {
        if (manaClearMissing || !mana()) return;
        try {
            if (manaClear == null) manaClear = mana.getClass().getMethod("clearCooldown", UUID.class, String.class);
            manaClear.invoke(mana, p.getUniqueId(), spell);
        } catch (NoSuchMethodException e) { manaClearMissing = true; warnOnce("mana-clear", "MagicCodexBridge 가 오래돼 비행 쿨타임을 없앨 수 없어요");
        } catch (ReflectiveOperationException | RuntimeException e) { warnOnce("mana-clear-err", "쿨타임 초기화 실패: " + e); }
    }

    /** Adds `percent` of the player's maximum mana. */
    void restoreMana(Player p, double percent) {
        if (!mana()) return;
        try {
            var opt = (java.util.Optional<?>) manaSnapshot.invoke(mana, p.getUniqueId());
            if (opt.isEmpty()) return;
            Object snap = opt.get();
            if (snapMax == null) snapMax = snap.getClass().getMethod("maximum");
            double max = (double) snapMax.invoke(snap);
            manaAdd.invoke(mana, p.getUniqueId(), max * percent / 100.0);
        } catch (ReflectiveOperationException | RuntimeException e) { warnOnce("mana-add", "마나 회복 실패: " + e); }
    }

    // ------------------------------------------------------------------ Broom (ChacademyMotion)
    private Object motion(String method, Player p) {
        Plugin m = Bukkit.getPluginManager().getPlugin("ChacademyMotion");
        if (m == null || !m.isEnabled()) return null;
        try { return m.getClass().getMethod(method, Player.class).invoke(m, p); }
        catch (NoSuchMethodException e) { warnOnce("motion-" + method, "ChacademyMotion." + method + " 없음 (모션 모드/플러그인 업데이트 필요)"); return null; }
        catch (ReflectiveOperationException | RuntimeException e) { warnOnce("motion-err-" + method, "ChacademyMotion." + method + " 실패: " + e); return null; }
    }
    /** 큐디치(2026-10-20): 낙마 대신 매달림 켜기/끄기. Needs the motion update (setBroomHangMode). */
    void broomHangMode(Player p, boolean on) {
        Plugin m = Bukkit.getPluginManager().getPlugin("ChacademyMotion");
        if (m == null || !m.isEnabled()) return;
        try { m.getClass().getMethod("setBroomHangMode", Player.class, boolean.class).invoke(m, p, on); }
        catch (NoSuchMethodException e) { warnOnce("motion-hang", "ChacademyMotion.setBroomHangMode 없음 (모션 모드/플러그인 업데이트 필요)"); }
        catch (ReflectiveOperationException | RuntimeException e) { warnOnce("motion-err-hang", "ChacademyMotion.setBroomHangMode 실패: " + e); }
    }
    boolean riding(Player p) { return Boolean.TRUE.equals(motion("isRidingBroom", p)); }
    void stopBroomIfRiding(Player p) { if (riding(p)) motion("stopBroom", p); }
    String broomId(Player p) { return motion("equippedBroomId", p) instanceof String s ? s : ""; }
    int house(Player p) {
        Plugin b = Bukkit.getPluginManager().getPlugin("MagicCodexBridge");
        if (b == null) return -1;
        try { return b.getClass().getMethod("houseOf", Player.class).invoke(b, p) instanceof Integer i ? i : -1; }
        catch (ReflectiveOperationException | LinkageError e) { warnOnce("bridge-house", "MagicCodexBridge.houseOf 없음 (Bridge 업데이트 필요)"); return -1; }
    }
    /** MagicCodex 한글 닉네임 (MagicCodexBridge.nicknameOf); the account name when the bridge is missing or old. */
    String nick(org.bukkit.OfflinePlayer p) {
        String fallback = p.getName() == null ? "플레이어" : p.getName();
        Plugin b = Bukkit.getPluginManager().getPlugin("MagicCodexBridge");
        if (b == null || !b.isEnabled()) return fallback;
        try { return b.getClass().getMethod("nicknameOf", org.bukkit.OfflinePlayer.class).invoke(b, p) instanceof String n && !n.isBlank() ? n : fallback; }
        catch (ReflectiveOperationException | LinkageError | RuntimeException e) { warnOnce("bridge-nick", "MagicCodexBridge.nicknameOf 없음 (Bridge 업데이트 필요)"); return fallback; }
    }
    /** Stability shrine: refill the client balance gauge. Needs the motion update (refillBroomBalance). */
    void refillBroomBalance(Player p) { motion("refillBroomBalance", p); }
    /** Haste shrine: the normal broom boost without using / starting its cooldown. Needs the motion update (freeBroomBoost). */
    void freeBroomBoost(Player p) { motion("freeBroomBoost", p); }

    // ------------------------------------------------------------------ Spell catalog (MagicCodexBridge)
    @SuppressWarnings("unchecked")
    Map<String, Map<String, String>> spellCatalog() {
        Plugin b = Bukkit.getPluginManager().getPlugin("MagicCodexBridge");
        if (b == null || !b.isEnabled()) return Map.of();
        try { return (Map<String, Map<String, String>>) b.getClass().getMethod("registeredSpellCatalog").invoke(b); }
        catch (ReflectiveOperationException | RuntimeException e) { warnOnce("catalog", "마법 목록 읽기 실패: " + e); return Map.of(); }
    }

    // ------------------------------------------------------------------ Magic heal (ChAcademiaMagicMythic MagicHealEvent)
    /** Calls `handler(caster, target, event)`; the handler may call setAmount(0) through {@link #setHealAmount}. */
    void listenHeal(Listener owner, HealHandler handler) {
        Plugin m = Bukkit.getPluginManager().getPlugin("ChAcademiaMagicMythic");
        if (m == null) { warnOnce("heal", "ChAcademiaMagicMythic 없음: 상대 팀 회복 차단·힐량 집계 안 됨"); return; }
        try {
            @SuppressWarnings("unchecked")
            Class<? extends Event> type = (Class<? extends Event>) Class.forName("school.chacademia.magicmythic.MagicHealEvent", true, m.getClass().getClassLoader());
            Method caster = type.getMethod("getCaster"), target = type.getMethod("getTarget"), amount = type.getMethod("getAmount"), set = type.getMethod("setAmount", double.class);
            Bukkit.getPluginManager().registerEvent(type, owner, EventPriority.HIGH, (l, e) -> {
                if (!type.isInstance(e)) return;
                try {
                    handler.heal((LivingEntity) caster.invoke(e), (Player) target.invoke(e), (double) amount.invoke(e), v -> { try { set.invoke(e, v); } catch (ReflectiveOperationException ignored) { } });
                } catch (ReflectiveOperationException | RuntimeException ex) { warnOnce("heal-run", "회복 이벤트 처리 실패: " + ex); }
            }, plugin, false);
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) { warnOnce("heal-reg", "MagicHealEvent 연결 실패: " + e); }
    }
    interface HealHandler { void heal(LivingEntity caster, Player target, double amount, java.util.function.DoubleConsumer setAmount); }

    /**
     * C03 (owner decision 7): MagicMythic asks "are these two players on the same side?" (MagicAllyEvent). During a match
     * the match team answers (party ignored); outside matches this plugin stays silent and the party decides.
     */
    void listenAlly(Listener owner, AllyHandler handler) {
        Plugin m = Bukkit.getPluginManager().getPlugin("ChAcademiaMagicMythic");
        if (m == null) { warnOnce("ally", "ChAcademiaMagicMythic 없음: 경기 팀 아군 판정 안 됨"); return; }
        try {
            @SuppressWarnings("unchecked")
            Class<? extends Event> type = (Class<? extends Event>) Class.forName("school.chacademia.magicmythic.MagicAllyEvent", true, m.getClass().getClassLoader());
            Method first = type.getMethod("getFirst"), second = type.getMethod("getSecond"), set = type.getMethod("setAlly", boolean.class);
            Bukkit.getPluginManager().registerEvent(type, owner, EventPriority.NORMAL, (l, e) -> {
                if (!type.isInstance(e)) return;
                try {
                    Boolean ally = handler.ally((Player) first.invoke(e), (Player) second.invoke(e));
                    if (ally != null) set.invoke(e, ally.booleanValue());
                } catch (ReflectiveOperationException | RuntimeException ex) { warnOnce("ally-run", "아군 판정 이벤트 처리 실패: " + ex); }
            }, plugin, false);
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) { warnOnce("ally-reg", "MagicAllyEvent 연결 실패 (MagicMythic 업데이트 필요): " + e); }
    }
    /** null = not a match question (let the party decide). */
    interface AllyHandler { Boolean ally(Player first, Player second); }

    /**
     * #33 (오너 결정 2026-10-25): MagicMythic 이 "이 선수는 지금 어떤 경기 중인가" 를 묻는다 (MagicMatchEvent).
     * 큐디치 선수면 "quidditch", 1:1 결투면 "duel" — 큐디치 전투 보정(투사체 속도·비행 피격 범위·유도 추적)은 큐디치에만 걸린다.
     */
    void listenMatch(Listener owner, java.util.function.Function<Player, String> kind) {
        Plugin m = Bukkit.getPluginManager().getPlugin("ChAcademiaMagicMythic");
        if (m == null) return;
        try {
            @SuppressWarnings("unchecked")
            Class<? extends Event> type = (Class<? extends Event>) Class.forName("school.chacademia.magicmythic.MagicMatchEvent", true, m.getClass().getClassLoader());
            Method player = type.getMethod("getPlayer"), set = type.getMethod("setKind", String.class);
            Bukkit.getPluginManager().registerEvent(type, owner, EventPriority.NORMAL, (l, e) -> {
                if (!type.isInstance(e)) return;
                try {
                    String k = kind.apply((Player) player.invoke(e));
                    if (k != null) set.invoke(e, k);
                } catch (ReflectiveOperationException | RuntimeException ex) { warnOnce("match-run", "경기 판정 이벤트 처리 실패: " + ex); }
            }, plugin, false);
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) { warnOnce("match-reg", "MagicMatchEvent 연결 실패 (MagicMythic 업데이트 필요 — 큐디치 전투 보정 꺼짐): " + e); }
    }

    static boolean isOurs(org.bukkit.entity.Entity e) { return e.getScoreboardTags().contains(BallVisual.TAG); }
}
