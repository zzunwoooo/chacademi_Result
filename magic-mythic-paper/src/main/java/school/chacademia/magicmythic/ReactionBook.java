package school.chacademia.magicmythic;

import java.util.*;
import school.magiccodex.protocol.ElementProtocol.Element;
import school.magiccodex.protocol.ElementProtocol.Reaction;

/**
 * Pure bookkeeping for element marks (no Bukkit, unit-tested). Time is the server tick passed in by the caller.
 * Nothing is scheduled: expiry is checked lazily on the next hit, and {@link #sweep} drops stale rows.
 *
 * Rules: a hit leaves its element for markTicks. A hit of another element while the mark is alive reacts once and
 * consumes the mark. After a reaction the target is immune for cooldownTicks (owner: 3 s internal cooldown): hits
 * during that time leave no mark at all and spreads skip it. The same element only refreshes the timer.
 */
final class ReactionBook {
    sealed interface Outcome permits Marked, Reacted, Immune {}
    /** Icon should show `element` for `ticks`. */
    record Marked(Element element, int ticks) implements Outcome {}
    /** Reaction fired; the mark is gone. `markCaster` is who left the first element (may equal the trigger). */
    record Reacted(Reaction reaction, UUID markCaster) implements Outcome {}

    /** Target is in its post-reaction cooldown: nothing recorded, no icon. */
    record Immune() implements Outcome {}

    private record Mark(Element element, long until, UUID caster) {}
    /** Live mark on a target: its element and who left it. */
    record MarkInfo(Element element, UUID caster) {}

    private final Map<UUID, Mark> marks = new HashMap<>();
    private final Map<UUID, Long> cooldown = new HashMap<>();
    private int markTicks, cooldownTicks;

    ReactionBook(int markTicks, int cooldownTicks) { configure(markTicks, cooldownTicks); }

    void configure(int markTicks, int cooldownTicks) {
        this.markTicks = Math.clamp(markTicks, 1, 20 * 60);
        this.cooldownTicks = Math.clamp(cooldownTicks, 0, 20 * 60);
    }

    int markTicks() { return markTicks; }

    Outcome hit(UUID target, UUID caster, Element element, long now) { return hit(target, caster, element, now, markTicks); }

    /** Same as {@link #hit(UUID, UUID, Element, long)} with this caster's mark duration (artifacts may change it). */
    Outcome hit(UUID target, UUID caster, Element element, long now, int ticks) {
        Objects.requireNonNull(target); Objects.requireNonNull(element);
        ticks = Math.clamp(ticks, 1, 20 * 60);
        if (coolingDown(target, now)) return new Immune();
        Mark old = marks.get(target);
        if (old != null && old.until() > now && old.element() != element) {
            marks.remove(target);
            if (cooldownTicks > 0) cooldown.put(target, now + cooldownTicks);
            return new Reacted(Reaction.of(old.element(), element), old.caster());
        }
        marks.put(target, new Mark(element, now + ticks, caster));
        return new Marked(element, ticks);
    }

    /** Puts a mark even during the post-reaction immunity (artifact: element ink bottle). Replaces any mark. */
    void force(UUID target, UUID caster, Element element, int ticks, long now) {
        cooldown.remove(target);
        marks.put(target, new Mark(element, now + Math.clamp(ticks, 1, 20 * 60), caster));
    }

    /** Live mark or null. */
    MarkInfo mark(UUID target, long now) {
        Mark m = marks.get(target);
        return m == null || m.until() <= now ? null : new MarkInfo(m.element(), m.caster());
    }

    /** Removes and returns the live mark (artifact: reaction detonator glove), or null. */
    MarkInfo take(UUID target, long now) {
        MarkInfo m = mark(target, now);
        if (m != null) marks.remove(target);
        return m;
    }

    /**
     * Spread reactions (불씨 확산): put a mark on a neighbour without reacting. Only on targets with no live mark,
     * so a spread never overwrites or consumes someone else's element. @return true when placed
     */
    boolean spread(UUID target, UUID caster, Element element, long now) {
        if (coolingDown(target, now)) return false;
        Mark old = marks.get(target);
        if (old != null && old.until() > now) return false;
        marks.put(target, new Mark(element, now + markTicks, caster));
        return true;
    }

    boolean coolingDown(UUID target, long now) {
        Long until = cooldown.get(target);
        return until != null && until > now;
    }

    /** Current element, or null. */
    Element element(UUID target, long now) {
        Mark m = marks.get(target);
        return m == null || m.until() <= now ? null : m.element();
    }

    void forget(UUID target) { marks.remove(target); cooldown.remove(target); }

    /** Drops expired rows. @return removed mark count */
    int sweep(long now) {
        int before = marks.size();
        marks.values().removeIf(m -> m.until() <= now);
        cooldown.values().removeIf(t -> t <= now);
        return before - marks.size();
    }

    int size() { return marks.size(); }
    void clear() { marks.clear(); cooldown.clear(); }
}
