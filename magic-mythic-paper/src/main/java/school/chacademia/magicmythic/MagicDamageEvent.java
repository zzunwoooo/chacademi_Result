package school.chacademia.magicmythic;

import java.util.UUID;
import org.bukkit.entity.LivingEntity;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;

/**
 * Fired right before magic damage is applied (spell hits, bolts, zones, reaction damage), only when the caster is a
 * player and someone listens. Listeners may change the amount (artifact bonuses). Not fired for plain melee.
 * `element` is the spell's element= option ("" for reaction / mark damage), `reaction` the reaction id
 * (lower-case Reaction name, "" for normal spell damage). `markElement`/`markCaster` describe the element mark the
 * target carried before this hit ("" / null when none). `spell` = the spell id the damage comes from ("" when unknown;
 * 2026-10-26, 순서의 목걸이: 마지막 칸 마법의 피해만 올리려고).
 */
public final class MagicDamageEvent extends Event {
    private static final HandlerList HANDLERS = new HandlerList();
    private final LivingEntity caster, target;
    private final String element, reaction, markElement, spell;
    private final UUID markCaster;
    private final double original;
    private double damage;

    public MagicDamageEvent(LivingEntity caster, LivingEntity target, String element, String reaction, String markElement, UUID markCaster, double damage) {
        this(caster, target, element, reaction, markElement, markCaster, damage, "");
    }
    public MagicDamageEvent(LivingEntity caster, LivingEntity target, String element, String reaction, String markElement, UUID markCaster, double damage, String spell) {
        this.caster = caster; this.target = target; this.element = element; this.reaction = reaction;
        this.markElement = markElement; this.markCaster = markCaster; this.original = damage; this.damage = damage;
        this.spell = spell == null ? "" : spell;
    }
    public LivingEntity getCaster() { return caster; }
    public LivingEntity getTarget() { return target; }
    public String getElement() { return element; }
    public String getReaction() { return reaction; }
    public boolean isReaction() { return !reaction.isEmpty(); }
    public String getMarkElement() { return markElement; }
    /** Spell id this damage belongs to (SpellTrace), "" when unknown. */
    public String getSpell() { return spell; }
    public UUID getMarkCaster() { return markCaster; }
    public double getOriginalDamage() { return original; }
    public double getDamage() { return damage; }
    public void setDamage(double damage) { this.damage = Double.isFinite(damage) ? Math.max(0, damage) : 0; }
    @Override public HandlerList getHandlers() { return HANDLERS; }
    public static HandlerList getHandlerList() { return HANDLERS; }
    static boolean listened() { return HANDLERS.getRegisteredListeners().length > 0; }
}
