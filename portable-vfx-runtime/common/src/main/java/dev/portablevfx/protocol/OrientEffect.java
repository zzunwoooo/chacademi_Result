package dev.portablevfx.protocol;
import java.util.UUID;
/** Visual orientation only. Does not restart particles, extend TTL, move entities, or change follow mode. */
public record OrientEffect(UUID instanceId, long sequence, float yaw, float pitch, float roll) implements EffectMessage {
 public OrientEffect {
  ProtocolValidation.requireUuid(instanceId);
  if(sequence<0)throw new IllegalArgumentException("sequence must be nonnegative");
  for(float v:new float[]{yaw,pitch,roll})if(!Float.isFinite(v)||Math.abs(v)>360)throw new IllegalArgumentException("angles must be finite and within +/-360 degrees");
 }
}
