package school.chacademia.magicmythic;

import org.bukkit.entity.LivingEntity;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;

/**
 * Fired when an element reaction happens, before its effect (only when someone listens). The icon pop is already
 * sent. Listeners (artifacts) may cancel the effect (the name still shows) or add small bonuses:
 * extra radius (sand vortex pull), extra chain-lightning bounces, extra crowd-control ticks (all CC, or only the
 * 3-second normal CC), a crowd-control duration multiplier, extra rainbow heal, an area radius multiplier
 * (2026-10-26 무지개 프리즘: 증기·불씨·모래 회오리·연쇄 번개 튀는 거리·무지개·섬광 범위).
 * Reaction damage itself goes through {@link MagicDamageEvent} with a reaction id.
 */
public final class MagicReactionEvent extends Event {
    private static final HandlerList HANDLERS = new HandlerList();
    private final LivingEntity caster, target;
    private final String reaction, markElement, triggerElement;
    private boolean effectCancelled;
    private double radiusBonus, healBonus;
    private int bounceBonus, controlBonusTicks, normalControlBonusTicks;
    private double controlMultiplier = 1, radiusMultiplier = 1;

    public MagicReactionEvent(LivingEntity caster, LivingEntity target, String reaction, String markElement, String triggerElement) {
        this.caster = caster; this.target = target; this.reaction = reaction; this.markElement = markElement; this.triggerElement = triggerElement;
    }
    public LivingEntity getCaster() { return caster; }
    public LivingEntity getTarget() { return target; }
    /** Lower-case reaction id (steam, wildfire, magma, flare, ember_curse, shock, mud, rainbow, abyss, sandstorm, flash, void_gust, crystal, bind, eclipse). */
    public String getReaction() { return reaction; }
    public String getMarkElement() { return markElement; }
    public String getTriggerElement() { return triggerElement; }
    /** Reactions that apply crowd control (2026-10-20 rework: every reaction except steam, rainbow, flash). */
    public boolean isControl() { return switch (reaction) { case "steam", "rainbow", "flash" -> false; default -> true; }; }
    public boolean isEffectCancelled() { return effectCancelled; }
    public void setEffectCancelled(boolean cancelled) { effectCancelled = cancelled; }
    public double getRadiusBonus() { return radiusBonus; }
    public void setRadiusBonus(double v) { radiusBonus = Math.clamp(v, 0, 4); }
    public int getBounceBonus() { return bounceBonus; }
    public void setBounceBonus(int v) { bounceBonus = Math.clamp(v, 0, 4); }
    public int getControlBonusTicks() { return controlBonusTicks; }
    public void setControlBonusTicks(int v) { controlBonusTicks = Math.clamp(v, 0, 40); }
    /** Extra ticks only for the normal (3 s) crowd control of this reaction, not the 1 s hard CC. */
    public int getNormalControlBonusTicks() { return normalControlBonusTicks; }
    public void setNormalControlBonusTicks(int v) { normalControlBonusTicks = Math.clamp(v, 0, 60); }
    /** Crowd-control duration multiplier (applied before the bonus ticks). Several artifacts multiply together. */
    public double getControlMultiplier() { return controlMultiplier; }
    public void multiplyControl(double v) { controlMultiplier = Math.clamp(controlMultiplier * v, 0.25, 3); }
    /** Area radius multiplier for this reaction (applied before the sand vortex radius bonus). Several multiply. */
    public double getRadiusMultiplier() { return radiusMultiplier; }
    public void multiplyRadius(double v) { radiusMultiplier = Math.clamp(radiusMultiplier * v, 0.5, 2); }
    public double getHealBonus() { return healBonus; }
    public void setHealBonus(double v) { healBonus = Math.clamp(v, 0, 10); }
    @Override public HandlerList getHandlers() { return HANDLERS; }
    public static HandlerList getHandlerList() { return HANDLERS; }
    static boolean listened() { return HANDLERS.getRegisteredListeners().length > 0; }
}
