package school.magiccodex.paper;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.bukkit.Bukkit;
import org.bukkit.Sound;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import school.magiccodex.protocol.ManaHeatProtocol;
import school.magiccodex.protocol.ManaProtocol;

/**
 * 마법 규칙을 바꾸는 아티팩트 5종 (2026-10-26, 보류 29개 중 그룹 C). 학교·야생·아레나 같은 규칙이다.
 *
 *  - 순서의 목걸이 (sequence_necklace): 키세팅 1번→6번을 차례로 쓰면 6번 마법의 피해 +40%. 칸 사이 10초 안,
 *    다른 칸·키세팅 밖 마법을 쓰면 처음부터. 6번 시전 뒤 10초 동안 그 마법(MagicDamageEvent.getSpell)의 피해에만 붙는다.
 *  - 무지개 프리즘 (rainbow_prism): 키세팅 6칸 원소가 6원소 하나씩이면 내가 일으킨 원소 반응의 피해·무지개 회복·범위 2배,
 *    반응 CC 시간 x1.5. 원소는 spell-elements.yml (도감 분류).
 *  - 혼돈의 주사위 (chaos_dice): 시전 직전에 MagicMythic 에 이번 시전 원소를 굴려 둔다 (표식·반응만 바뀜).
 *  - 단 하나의 서 (the_only_tome): 키세팅에 마법이 1개뿐이고 그 마법의 기본 재사용 대기가 30초 이하면 켜진다.
 *    충전 4회 연속 시전, 첫 충전을 쓸 때 (대기 x2) 타이머가 돌고 끝나면 충전이 모두 찬다. 켜져 있는 동안 지능 -50%.
 *  - 거꾸로 된 마나병 (inverted_mana_flask): ManaAccount 과열 모드 (그 클래스 설명 참고) + 클라이언트에 과열 표시 신호.
 *
 * 키세팅은 서버가 원래 모른다: MagicCodex 클라이언트가 chacademia:spell_slots 로 보고한 칸 (큐디치 경기 중에는 경기 프리셋,
 * 클라이언트가 지금 쓰는 칸 그대로)을 MagicMythic SpellSlots 에서 읽는다. 배운 마법만 센다.
 * 1초 주기(ArtifactEffects.tick)에 장착자만 칸을 다시 읽고, 나머지는 시전·피해 이벤트에서 맵 조회만 한다.
 */
final class ArtifactMagicRules implements Listener {
    static final long SEQUENCE_GAP_MS = 10_000, SEQUENCE_FINAL_MS = 10_000;
    static final double SEQUENCE_BONUS = 1.40;
    static final int TOME_CHARGES = 4, TOME_MAX_COOLDOWN_MS = 30_000;
    static final Set<String> ELEMENTS = Set.of("fire", "water", "wind", "earth", "light", "dark");
    private final ArtifactEffects fx;
    private final Map<String, String> elements;
    private final Map<UUID, State> states = new HashMap<>();
    private long slotRetryAt;

    static final class State {
        List<String> slots = List.of();
        int seqNext; long seqAt; String seqFinal; long seqFinalUntil;
        boolean prism, chaos, tomeHint;
        String tome; int charges = TOME_CHARGES; long refillAt;
        Boolean heatSent; boolean lockSent; long restoredLock;
    }

    ArtifactMagicRules(ArtifactEffects fx) {
        this.fx = fx;
        var map = new HashMap<String, String>();
        try (var in = fx.plugin.getResource("spell-elements.yml")) {
            if (in != null) {
                var y = YamlConfiguration.loadConfiguration(new InputStreamReader(in, StandardCharsets.UTF_8));
                var s = y.getConfigurationSection("spells");
                if (s != null) for (String id : s.getKeys(false)) { String e = s.getString(id, ""); if (ELEMENTS.contains(e)) map.put(id, e); }
            }
        } catch (java.io.IOException | RuntimeException e) { fx.plugin.getLogger().warning("spell-elements.yml 읽기 실패 (무지개 프리즘 꺼짐): " + e); }
        elements = Map.copyOf(map);
        fx.plugin.getServer().getMessenger().registerOutgoingPluginChannel(fx.plugin, ManaHeatProtocol.CHANNEL);
    }

    private State state(Player p) { return states.computeIfAbsent(p.getUniqueId(), k -> new State()); }
    private boolean has(Player p, String id) { return fx.has(p, id); }

    /** Key slots in order (learned spells only). Empty when unknown / MagicMythic missing. */
    @SuppressWarnings("unchecked")
    List<String> slots(Player p) {
        long now = System.currentTimeMillis();
        if (now < slotRetryAt || !fx.magic().connected()) return List.of();
        Object v = fx.magic().api("spellSlots", new Class<?>[]{Player.class}, p);
        if (v == null) { slotRetryAt = now + 60_000; return List.of(); } // 이전 MagicMythic: 1분 뒤 다시
        return v instanceof List<?> l ? (List<String>) l : List.of();
    }

    static boolean rainbow(List<String> slots, Map<String, String> elements) {
        if (slots.size() != 6) return false;
        var seen = new HashSet<String>();
        for (String id : slots) { String e = elements.get(id); if (e == null || !seen.add(e)) return false; }
        return seen.size() == 6;
    }

    // ------------------------------------------------------------------ every second (ArtifactEffects.tick)

    void second(Player p, long now) {
        boolean seq = has(p, "sequence_necklace"), prism = has(p, "rainbow_prism"), tome = has(p, "the_only_tome"),
                chaos = has(p, "chaos_dice"), flask = has(p, "inverted_mana_flask");
        var old = states.get(p.getUniqueId());
        if (old == null && !seq && !prism && !tome && !chaos && !flask) return;
        var s = state(p);
        s.slots = seq || prism || tome ? slots(p) : List.of();
        if (!seq) { s.seqNext = 0; s.seqFinal = null; }
        boolean prismBefore = s.prism;
        s.prism = prism && rainbow(s.slots, elements);
        if (s.prism && !prismBefore) {
            ArtifactEffects.notice(p, "rainbow_prism", "무지개 프리즘", "6원소가 모였어요 · 원소 반응 피해·회복·범위 2배");
            p.playSound(p.getLocation(), Sound.BLOCK_AMETHYST_BLOCK_CHIME, 1f, 1.5f);
        }
        if (s.chaos && !chaos) fx.magic().api("clearChaosElement", new Class<?>[]{Player.class}, p);
        s.chaos = chaos;
        tomeSecond(p, s, tome, now);
        heatSecond(p, s, flask, now);
        if (!seq && !prism && !tome && !chaos && !flask && s.heatSent == null) states.remove(p.getUniqueId());
    }

    // ------------------------------------------------------------------ before / after a cast

    /** Before the cast is checked (ManaBridge fires it for every player cast route). Runs before any hit of that cast. */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void attempt(MagicCastAttemptEvent e) {
        Player p = e.getPlayer();
        if (!fx.wearsAny(p)) return;
        if (has(p, "chaos_dice")) fx.magic().api("rollChaosElement", new Class<?>[]{Player.class}, p);
        if (has(p, "sequence_necklace")) {
            var s = state(p); long now = System.currentTimeMillis();
            s.slots = slots(p);
            String spell = e.getSpellId();
            if (completes(s, spell, now)) { s.seqFinal = spell; s.seqFinalUntil = now + SEQUENCE_FINAL_MS; }
            else if (spell.equals(s.seqFinal)) s.seqFinal = null; // 같은 마법을 순서 없이 다시 씀
        }
    }

    private static boolean completes(State s, String spell, long now) {
        return s.slots.size() == 6 && s.seqNext == 5 && s.slots.indexOf(spell) == 5 && now - s.seqAt <= SEQUENCE_GAP_MS;
    }

    /** After a successful cast (ArtifactEffects.cast). */
    void cast(Player p, String spell, long now) {
        if (has(p, "sequence_necklace")) sequence(p, state(p), spell, now);
        if (has(p, "inverted_mana_flask") && fx.mana.heatLockRemaining(p.getUniqueId()) > 0) {
            ArtifactEffects.notice(p, "flask_lock", "거꾸로 된 마나병", "과열! 30초 동안 마법을 쓸 수 없어요");
            p.playSound(p.getLocation(), Sound.BLOCK_FIRE_EXTINGUISH, 1f, 0.8f);
            sendHeat(p, state(p), true);
        }
    }

    private void sequence(Player p, State s, String spell, long now) {
        int idx = s.slots.size() == 6 ? s.slots.indexOf(spell) : -1;
        if (idx >= 0 && idx == s.seqNext && (idx == 0 || now - s.seqAt <= SEQUENCE_GAP_MS)) {
            s.seqAt = now;
            if (++s.seqNext >= 6) {
                s.seqNext = 0;
                ArtifactEffects.notice(p, "sequence_necklace", "순서의 목걸이", "순서 완성! 마지막 마법 피해 +40%");
                p.playSound(p.getLocation(), Sound.BLOCK_AMETHYST_BLOCK_RESONATE, 1f, 1.6f);
            } else p.playSound(p.getLocation(), Sound.BLOCK_AMETHYST_BLOCK_CHIME, .6f, 0.8f + 0.15f * idx); // 칸마다 점점 높은 소리 (본인만)
        } else if (idx == 0) { s.seqNext = 1; s.seqAt = now; p.playSound(p.getLocation(), Sound.BLOCK_AMETHYST_BLOCK_CHIME, .6f, 0.8f); }
        else s.seqNext = 0;
    }

    // ------------------------------------------------------------------ magic events (ArtifactEffects.MagicHooks)

    private boolean spellMissing;
    private String spellOf(Event e) {
        if (spellMissing) return "";
        try { return String.valueOf(fx.magic().get(e, "getSpell")); }
        catch (IllegalStateException old) { spellMissing = true; fx.plugin.getLogger().warning("순서의 목걸이: MagicMythic 이 이전 버전이라 피해의 마법 ID를 몰라요 (MagicMythic 함께 배포)"); return ""; }
    }

    /** Damage multiplier from these artifacts for one MagicDamageEvent. */
    double damage(Player p, Event e, String reaction) {
        var s = states.get(p.getUniqueId());
        if (s == null) return 1;
        double m = 1;
        if (!reaction.isEmpty()) {
            if (s.prism && !reaction.equals("detonate") && has(p, "rainbow_prism")) m *= 2;   // 원소 반응 피해 2배
        } else if (s.seqFinal != null && System.currentTimeMillis() < s.seqFinalUntil && has(p, "sequence_necklace") && s.seqFinal.equals(spellOf(e))) m *= SEQUENCE_BONUS;
        return m;
    }

    void reaction(Player p, boolean control, Event e) {
        var s = states.get(p.getUniqueId());
        if (s == null || !s.prism || !has(p, "rainbow_prism")) return;
        fx.magic().call(e, "multiplyRadius", 2.0);            // 범위 2배 (MagicMythic 이전 버전이면 조용히 없음)
        if (control) fx.magic().call(e, "multiplyControl", 1.5); // CC 시간 +50%
    }

    double heal(Player caster, String reaction, double amount) {
        var s = states.get(caster.getUniqueId());
        return s != null && s.prism && "rainbow".equals(reaction) && has(caster, "rainbow_prism") ? amount * 2 : amount; // 무지개 회복 2배
    }

    // ------------------------------------------------------------------ 단 하나의 서

    private void tomeSecond(Player p, State s, boolean worn, long now) {
        String before = s.tome;
        s.tome = null;
        if (worn && s.slots.size() == 1) {
            var rule = fx.plugin.spellRule(s.slots.get(0));
            if (rule != null && rule.cooldown() > 0 && rule.cooldown() <= TOME_MAX_COOLDOWN_MS) s.tome = rule.id();
        }
        if (s.tome != null && !s.tome.equals(before)) {
            // 충전·타이머는 마법을 바꿔도 이어진다 (바꿔 끼워 충전을 채우는 방법 없음)
            if (s.refillAt <= now) { s.charges = TOME_CHARGES; s.refillAt = 0; }
            ArtifactEffects.notice(p, "the_only_tome", "단 하나의 서", "충전 " + s.charges + " / " + TOME_CHARGES + " · 재사용 대기 2배 · 지능 -50%");
            s.tomeHint = false;
        } else if (worn && s.tome == null && !s.tomeHint) {
            s.tomeHint = true;
            ArtifactEffects.notice(p, "the_only_tome_hint", "단 하나의 서", "키세팅에 재사용 대기 30초 이하 마법 1개만 넣으면 켜져요");
        }
        if (!worn) s.tomeHint = false;
        if (s.tome != null && s.refillAt > 0 && now >= s.refillAt) {
            s.refillAt = 0;
            if (s.charges < TOME_CHARGES) { s.charges = TOME_CHARGES; ArtifactEffects.notice(p, "the_only_tome_full", "단 하나의 서", "충전이 모두 찼어요 (" + TOME_CHARGES + " / " + TOME_CHARGES + ")"); }
        }
    }

    /** 지능 배율 (MagicCodexBridge.equipmentPower). */
    double powerFactor(UUID id) { var s = states.get(id); return s != null && s.tome != null ? 0.5 : 1; }

    /**
     * ManaBridge, right after a successful cast and before the client gets its cooldown: with charges left the spell
     * stays ready (cooldown 0), the last charge waits for the doubled timer that started at the first charge.
     */
    ManaCasting.Result cooldown(Player p, ManaSpells.Spell spell, ManaCasting.Result result) {
        if (result.status() != ManaProtocol.OK || result.cooldownMillis() <= 0) return result;
        var s = states.get(p.getUniqueId());
        if (s == null || !spell.id().equals(s.tome) || !has(p, "the_only_tome") || spell.cooldown() > TOME_MAX_COOLDOWN_MS) return result;
        long now = System.currentTimeMillis();
        if (s.refillAt <= now) { s.charges = TOME_CHARGES; s.refillAt = now + 2L * result.cooldownMillis(); }
        s.charges = Math.max(0, s.charges - 1);
        var a = fx.mana.account(p.getUniqueId());
        if (s.charges > 0) {
            a.cooldowns.remove(spell.id());
            ArtifactEffects.notice(p, "the_only_tome_" + s.charges, "단 하나의 서", "충전 " + s.charges + " / " + TOME_CHARGES);
            return new ManaCasting.Result(ManaProtocol.OK, 0);
        }
        a.cooldown(spell.id(), s.refillAt, now);
        long left = s.refillAt - now;
        ArtifactEffects.notice(p, "the_only_tome_0", "단 하나의 서", "충전을 모두 썼어요 · " + (left + 999) / 1000 + "초 뒤 모두 참");
        return new ManaCasting.Result(ManaProtocol.OK, (int) Math.min(86_400_000, left));
    }

    // ------------------------------------------------------------------ 거꾸로 된 마나병

    private void heatSecond(Player p, State s, boolean worn, long now) {
        UUID id = p.getUniqueId();
        if (fx.mana.snapshot(id).isEmpty()) return;
        if (fx.mana.heatMode(id) != worn) {
            fx.mana.setHeatMode(id, worn);
            if (worn && s.restoredLock > now) fx.mana.account(id).heat.lockUntil = s.restoredLock; // 서버 이동 전 과열 잠김
            s.restoredLock = 0;
        }
        sendHeat(p, s, false);
    }

    /** Sends the overheat state when it changed (on/off, lock start/end). Old clients never registered the channel. */
    private void sendHeat(Player p, State s, boolean force) {
        UUID id = p.getUniqueId();
        boolean on = fx.mana.heatMode(id);
        long lock = fx.mana.heatLockRemaining(id);
        if (!force && Objects.equals(s.heatSent, on) && s.lockSent == (lock > 0)) return;
        if (!on && s.heatSent == null) return;
        s.heatSent = on ? Boolean.TRUE : null; s.lockSent = lock > 0;
        if (!p.getListeningPluginChannels().contains(ManaHeatProtocol.CHANNEL)) return;
        p.sendPluginMessage(fx.plugin, ManaHeatProtocol.CHANNEL, ManaHeatProtocol.encode(new ManaHeatProtocol.State(on, (int) Math.min(600_000, lock))));
    }

    // ------------------------------------------------------------------ shared runtime (school/wild) and cleanup

    /** Appended to ArtifactEffects.runtimeText: tome charges + refill time, overheat lock. */
    String runtime(Player p) {
        var s = states.get(p.getUniqueId());
        var out = new StringBuilder();
        long now = System.currentTimeMillis();
        if (s != null && s.refillAt > now) out.append("tome=").append(s.charges).append(',').append(s.refillAt).append(';');
        long lock = fx.mana.heatLockRemaining(p.getUniqueId());
        if (lock > 0) out.append("heat=").append(now + lock).append(';');
        return out.toString();
    }

    /** ArtifactEffects.restoreRuntime: one key it does not know. */
    void restore(Player p, String k, String v, long now) {
        switch (k) {
            case "tome" -> {
                String[] t = v.split(",");
                long until = Long.parseLong(t[1]);
                if (until > now && until - now <= 2L * TOME_MAX_COOLDOWN_MS * 20) { var s = state(p); s.charges = Math.clamp(Integer.parseInt(t[0]), 0, TOME_CHARGES); s.refillAt = until; }
            }
            case "heat" -> { long until = Long.parseLong(v); if (until > now && until - now <= 30_000) state(p).restoredLock = until; }
            default -> { }
        }
    }

    void quit(UUID id) { states.remove(id); }

    void close() { fx.plugin.getServer().getMessenger().unregisterOutgoingPluginChannel(fx.plugin, ManaHeatProtocol.CHANNEL); }
}
