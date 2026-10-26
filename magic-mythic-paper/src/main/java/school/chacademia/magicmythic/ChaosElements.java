package school.chacademia.magicmythic;

import java.util.*;
import org.bukkit.Bukkit;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;
import school.magiccodex.protocol.ElementProtocol.Element;

/**
 * 혼돈의 주사위 (아티팩트, 2026-10-26): 마법을 쓸 때마다 원소가 무작위로 바뀐다. MagicCodexBridge 가 시전 직전
 * (MagicCastAttemptEvent) {@link MagicMythicPlugin#rollChaosElement} 로 이번 시전의 원소를 굴려 두면, 원소가 있는 마법이
 * 표식·반응을 만들 때 ({@link Reactions#hit}) 그 원소로 바뀐다. 피해 이벤트의 element 와 VFX 색은 그대로다.
 * 굴린 원소는 15초 동안만 쓴다 (오래 남는 장판·투사체까지 한 원소, 해제하면 곧 사라짐). 틱 작업 없음.
 */
final class ChaosElements implements Listener {
    static final long KEEP_TICKS = 300;
    private record Roll(Element element, long until) {}
    private final Map<UUID, Roll> rolls = new HashMap<>();
    private final Random random = new Random();

    /** Picks a new element for this caster's next spell, different from the previous roll. @return its id */
    String roll(Player p) {
        var before = rolls.get(p.getUniqueId());
        Element[] all = Element.values();
        Element next = all[random.nextInt(all.length)];
        if (before != null && next == before.element()) next = all[(next.ordinal() + 1 + random.nextInt(all.length - 1)) % all.length];
        rolls.put(p.getUniqueId(), new Roll(next, Bukkit.getCurrentTick() + KEEP_TICKS));
        return next.id;
    }

    void clear(UUID id) { rolls.remove(id); }

    /** The element a hit by `caster` should use: the live roll, otherwise the spell's own element. */
    Element element(LivingEntity caster, Element original) {
        if (rolls.isEmpty() || !(caster instanceof Player)) return original;
        var r = rolls.get(caster.getUniqueId());
        if (r == null) return original;
        if (r.until() < Bukkit.getCurrentTick()) { rolls.remove(caster.getUniqueId()); return original; }
        return r.element();
    }

    @EventHandler public void quit(PlayerQuitEvent e) { rolls.remove(e.getPlayer().getUniqueId()); }
}
