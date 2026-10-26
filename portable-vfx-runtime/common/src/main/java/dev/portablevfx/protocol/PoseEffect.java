package dev.portablevfx.protocol;

import java.util.UUID;

/** Server-authoritative visual pose only; never moves or collides a gameplay entity. */
public record PoseEffect(UUID instanceId, String dimensionId, long sequence,
        double x, double y, double z, EffectBasis basis,double linkLength) implements EffectMessage {
    public PoseEffect(UUID instanceId,String dimensionId,long sequence,double x,double y,double z,EffectBasis basis) {
        this(instanceId,dimensionId,sequence,x,y,z,basis,-1);
    }
    public PoseEffect {
        if(!Double.isFinite(linkLength)||(linkLength<0&&linkLength!=-1)||linkLength>256)throw new IllegalArgumentException("Invalid link length");
        ProtocolValidation.requireUuid(instanceId);
        VfxProtocol.validateId(dimensionId);
        if (sequence < 0) throw new IllegalArgumentException("sequence must be nonnegative");
        ProtocolValidation.requirePosition(x,"x"); ProtocolValidation.requirePosition(y,"y"); ProtocolValidation.requirePosition(z,"z");
        if (basis == null) throw new IllegalArgumentException("basis must not be null");
    }
}
