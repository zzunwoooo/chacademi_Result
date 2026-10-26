package dev.portablevfx.protocol;

import java.util.UUID;

/**
 * Starts or replaces a visual instance using a client-installed effect definition.
 * Angles are degrees; RGB is a 24-bit tint; duration is in 20 Hz game ticks.
 * Animation frames and rendering behavior come from the client's effect pack.
 */
public record PlayEffect(
        UUID instanceId,
        String effectId,
        String dimensionId,
        double x,
        double y,
        double z,
        float yaw,
        float pitch,
        float roll,
        float scale,
        int rgb,
        float opacity,
        int durationTicks, UUID followEntity,
        long seed, long startTick, EffectAnchor anchor, float offsetX, float offsetY, float offsetZ,
        double effectWidth, double scaleInput, double linkLength
) implements EffectMessage {
    public PlayEffect(UUID instanceId, String effectId, String dimensionId, double x, double y, double z,
            float yaw,float pitch,float roll,float scale,int rgb,float opacity,int durationTicks,UUID followEntity,
            long seed,long startTick,EffectAnchor anchor,float offsetX,float offsetY,float offsetZ,double effectWidth) {
        this(instanceId,effectId,dimensionId,x,y,z,yaw,pitch,roll,scale,rgb,opacity,durationTicks,followEntity,
            seed,startTick,anchor,offsetX,offsetY,offsetZ,effectWidth,0,-1);
    }
    public boolean parameterized() { return scaleInput!=0 || linkLength>=0; }
    public PlayEffect withParameters(double scaleInput, double linkLength) {
        return new PlayEffect(instanceId,effectId,dimensionId,x,y,z,yaw,pitch,roll,scale,rgb,opacity,durationTicks,
            followEntity,seed,startTick,anchor,offsetX,offsetY,offsetZ,effectWidth,scaleInput,linkLength);
    }
    /** Existing extended PLAY constructor preserves its byte-for-byte legacy wire form. */
    public PlayEffect(UUID instanceId, String effectId, String dimensionId, double x, double y, double z,
            float yaw, float pitch, float roll, float scale, int rgb, float opacity, int durationTicks,
            UUID followEntity, long seed, long startTick, EffectAnchor anchor, float offsetX, float offsetY, float offsetZ) {
        this(instanceId, effectId, dimensionId, x, y, z, yaw, pitch, roll, scale, rgb, opacity,
                durationTicks, followEntity, seed, startTick, anchor, offsetX, offsetY, offsetZ, 0);
    }

    public PlayEffect(UUID instanceId, String effectId, String dimensionId, double x, double y, double z,
            float yaw, float pitch, float roll, float scale, int rgb, float opacity, int durationTicks) {
        this(instanceId, effectId, dimensionId, x, y, z, yaw, pitch, roll, scale, rgb, opacity, durationTicks, null);
    }
    /** Legacy entity-follow constructor; retains the exact existing opcode 4 wire form. */
    public PlayEffect(UUID instanceId, String effectId, String dimensionId, double x, double y, double z,
            float yaw, float pitch, float roll, float scale, int rgb, float opacity, int durationTicks,
            UUID followEntity) {
        this(instanceId, effectId, dimensionId, x, y, z, yaw, pitch, roll, scale, rgb, opacity,
                durationTicks, followEntity, defaultSeed(instanceId), -1,
                followEntity == null ? EffectAnchor.WORLD : EffectAnchor.ENTITY, 0, 0, 0);
    }

    /** Stable across processes and JVMs; no Random or platform hash implementation involved. */
    public static long defaultSeed(UUID id) {
        ProtocolValidation.requireUuid(id);
        long value = id.getMostSignificantBits() ^ Long.rotateLeft(id.getLeastSignificantBits(), 23);
        value = (value ^ (value >>> 30)) * 0xbf58476d1ce4e5b9L;
        value = (value ^ (value >>> 27)) * 0x94d049bb133111ebL;
        return value ^ (value >>> 31);
    }

    /** Whether encoding needs the separately negotiated extended PLAY opcode. */
    public boolean extended() {
        return parameterized() || effectWidth != 0 || startTick != -1 || seed != defaultSeed(instanceId) || !legacyPlacement();
    }

    /** Only timing/seed may be downgraded; never silently move a named/offset attachment. */
    public boolean legacyPlacement() {
        return anchor == (followEntity == null ? EffectAnchor.WORLD : EffectAnchor.ENTITY)
                && Float.floatToIntBits(offsetX) == 0 && Float.floatToIntBits(offsetY) == 0
                && Float.floatToIntBits(offsetZ) == 0;
    }

    public PlayEffect withStartTick(long tick) {
        return new PlayEffect(instanceId, effectId, dimensionId, x, y, z, yaw, pitch, roll, scale,
                rgb, opacity, durationTicks, followEntity, seed, tick, anchor, offsetX, offsetY, offsetZ, effectWidth,scaleInput,linkLength);
    }

    /** Metres along the authored right axis; zero uses the asset reference width. */
    public PlayEffect withEffectWidth(double width) {
        return new PlayEffect(instanceId, effectId, dimensionId, x, y, z, yaw, pitch, roll, scale,
                rgb, opacity, durationTicks, followEntity, seed, startTick, anchor, offsetX, offsetY, offsetZ, width,scaleInput,linkLength);
    }

    public PlayEffect legacy() {
        if(parameterized())throw new IllegalStateException("Parameterized effects cannot be downgraded");
        if (effectWidth != 0) throw new IllegalStateException("Explicit effectWidth requires width playback");
        if (!legacyPlacement()) throw new IllegalStateException("Named/offset anchors require extended playback");
        return new PlayEffect(instanceId, effectId, dimensionId, x, y, z, yaw, pitch, roll, scale,
                rgb, opacity, durationTicks, followEntity);
    }

    public PlayEffect {
        if(!Double.isFinite(scaleInput)||scaleInput<0||Double.doubleToLongBits(scaleInput)==Double.doubleToLongBits(-0.0d)||scaleInput>256 || !Double.isFinite(linkLength)||(linkLength<0&&linkLength!=-1)||linkLength>256)
            throw new IllegalArgumentException("Invalid scale input or link length");
        if((scaleInput!=0||linkLength>=0)&&(effectId==null||!effectId.startsWith("claude:")||scale!=1f))
            throw new IllegalArgumentException("Parameters require Claude unit scale");
        ProtocolValidation.requireUuid(instanceId);
        if (!Double.isFinite(effectWidth) || effectWidth < 0 || effectWidth > VfxProtocol.MAX_EFFECT_WIDTH
                || Double.doubleToLongBits(effectWidth) == Double.doubleToLongBits(-0.0d))
            throw new IllegalArgumentException("effectWidth must be 0 (authored reference) or in (0, " + VfxProtocol.MAX_EFFECT_WIDTH + "]");
        if (effectWidth != 0 && (effectId == null || !effectId.startsWith("claude:") || scale != 1f))
            throw new IllegalArgumentException("Explicit effectWidth requires a claude: effect and scale=1");
        if (anchor == null) throw new IllegalArgumentException("anchor must not be null");
        if ((anchor == EffectAnchor.WORLD) != (followEntity == null)) {
            throw new IllegalArgumentException("WORLD requires no entity; other anchors require a followEntity UUID");
        }
        if (startTick < -1) throw new IllegalArgumentException("startTick must be -1 or nonnegative world game time");
        validateOffset(offsetX, "offsetX"); validateOffset(offsetY, "offsetY"); validateOffset(offsetZ, "offsetZ");
        VfxProtocol.validateId(effectId);
        VfxProtocol.validateId(dimensionId);
        ProtocolValidation.requirePosition(x, "x");
        ProtocolValidation.requirePosition(y, "y");
        ProtocolValidation.requirePosition(z, "z");
        ProtocolValidation.requireFinite(yaw, "yaw");
        ProtocolValidation.requireFinite(pitch, "pitch");
        ProtocolValidation.requireFinite(roll, "roll");
        ProtocolValidation.requireFinite(scale, "scale");
        if (scale <= 0.0f || scale > VfxProtocol.MAX_SCALE) {
            throw new IllegalArgumentException("scale must be greater than 0 and at most " + VfxProtocol.MAX_SCALE);
        }
        if (rgb < 0 || rgb > 0xFFFFFF) {
            throw new IllegalArgumentException("rgb must be a 24-bit integer in [0, 0xFFFFFF]");
        }
        ProtocolValidation.requireFinite(opacity, "opacity");
        if (opacity < 0.0f || opacity > 1.0f) {
            throw new IllegalArgumentException("opacity must be in [0, 1]");
        }
        if (durationTicks < 1 || durationTicks > VfxProtocol.MAX_DURATION_TICKS) {
            throw new IllegalArgumentException("durationTicks must be in [1, " + VfxProtocol.MAX_DURATION_TICKS + "]");
        }
    }
    private static void validateOffset(float value, String name) {
        ProtocolValidation.requireFinite(value, name);
        if (Math.abs(value) > VfxProtocol.MAX_ANCHOR_OFFSET) {
            throw new IllegalArgumentException(name + " exceeds " + VfxProtocol.MAX_ANCHOR_OFFSET);
        }
    }
}
