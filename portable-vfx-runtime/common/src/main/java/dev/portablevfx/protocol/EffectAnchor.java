package dev.portablevfx.protocol;

/** Stable wire IDs; shoulders/head are visual approximations, not skeleton bone bindings. */
public enum EffectAnchor {
    WORLD(0, "world"), ENTITY(1, "entity"), HEAD(2, "head"),
    LEFT_SHOULDER(3, "left_shoulder"), RIGHT_SHOULDER(4, "right_shoulder");

    private final int wireId;
    private final String id;
    EffectAnchor(int wireId, String id) { this.wireId = wireId; this.id = id; }
    public int wireId() { return wireId; }
    public String id() { return id; }
    public static EffectAnchor fromWireId(int wireId) {
        for (EffectAnchor value : values()) if (value.wireId == wireId) return value;
        throw new IllegalArgumentException("Unknown anchor ID: " + wireId);
    }
    public static EffectAnchor parse(String id) {
        for (EffectAnchor value : values()) if (value.id.equals(id)) return value;
        throw new IllegalArgumentException("anchor must be world, entity, head, left_shoulder or right_shoulder");
    }
}
