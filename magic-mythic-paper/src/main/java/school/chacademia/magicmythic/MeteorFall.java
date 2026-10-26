package school.chacademia.magicmythic;

/**
 * A16 — pure timeline of one falling spell (unit tested). CHARGING for `charge` ticks, then FALLING for
 * ceil(distance / speed) ticks (the same count PortableVFX needs to fly the authored sky offset at that speed),
 * then the landing tick, exactly once. Obstacles never shorten it (owner decision 3: pass through roofs).
 */
final class MeteorFall {
    enum State { CHARGING, FALLING, LANDED, CANCELLED }
    final int chargeTicks, fallTicks;
    private int age;
    private State state = State.CHARGING;

    MeteorFall(int chargeTicks, double distance, double speed) {
        this.chargeTicks = Math.max(0, chargeTicks);
        double d = Double.isFinite(distance) ? Math.max(0, distance) : 0, v = Double.isFinite(speed) && speed > 0 ? speed : 1;
        this.fallTicks = Math.max(1, (int) Math.ceil(d / v - 1e-9));
    }

    /** Ticks from the cast to the landing tick. */
    int landingTick() { return chargeTicks + fallTicks; }
    State state() { return state; }
    int age() { return age; }

    /** Advances one server tick. @return true only on the landing tick (once per cast, never after cancel). */
    boolean tick() {
        if (state == State.LANDED || state == State.CANCELLED) return false;
        age++;
        if (age >= landingTick()) { state = State.LANDED; return true; }
        state = age > chargeTicks ? State.FALLING : State.CHARGING;
        return false;
    }

    void cancel() { if (state != State.LANDED) state = State.CANCELLED; }
    boolean finished() { return state == State.LANDED || state == State.CANCELLED; }
}
