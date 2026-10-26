package dev.portablevfx.protocol;
import java.nio.ByteBuffer;
import java.util.UUID;
/** Optional presentation-only metadata. Existing effect opcodes and gameplay remain unchanged. */
public record EffectOwner(UUID instance,UUID owner) {
 public static final String CHANNEL="portablevfx:effect_owner";
 public EffectOwner{java.util.Objects.requireNonNull(instance);java.util.Objects.requireNonNull(owner);}
 public byte[] encode(){return ByteBuffer.allocate(33).put((byte)1).putLong(instance.getMostSignificantBits()).putLong(instance.getLeastSignificantBits()).putLong(owner.getMostSignificantBits()).putLong(owner.getLeastSignificantBits()).array();}
 public static EffectOwner decode(byte[] bytes){if(bytes==null||bytes.length!=33||bytes[0]!=1)throw new IllegalArgumentException("Effect owner metadata");var b=ByteBuffer.wrap(bytes);b.get();return new EffectOwner(new UUID(b.getLong(),b.getLong()),new UUID(b.getLong(),b.getLong()));}
}
