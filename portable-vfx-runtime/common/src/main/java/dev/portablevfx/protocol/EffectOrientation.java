package dev.portablevfx.protocol;
import java.util.UUID;
/** Bounded scalar state for one already-created effect; stale/foreign updates never apply. */
public final class EffectOrientation {
 private final UUID id;private long sequence=-1;private float yaw,pitch,roll;
 public EffectOrientation(PlayEffect initial){id=initial.instanceId();yaw=initial.yaw();pitch=initial.pitch();roll=initial.roll();}
 public boolean accept(OrientEffect update){if(!id.equals(update.instanceId())||update.sequence()<=sequence)return false;sequence=update.sequence();yaw=update.yaw();pitch=update.pitch();roll=update.roll();return true;}
 public float yaw(){return yaw;}public float pitch(){return pitch;}public float roll(){return roll;}
 public float nativePitch(){return (float)Math.toRadians(pitch);}public float nativeYaw(){return (float)Math.toRadians(-yaw);}public float nativeRoll(){return (float)Math.toRadians(roll);}
 /** For assets authored along +X, matches Effekseer Euler convention used by this runtime. */
 public static OrientEffect towardPositiveX(UUID id,long sequence,double x,double y,double z){
  if(!Double.isFinite(x)||!Double.isFinite(y)||!Double.isFinite(z))throw new IllegalArgumentException("finite direction required");
  double maximum=Math.max(Math.abs(x),Math.max(Math.abs(y),Math.abs(z)));
  if(maximum<1e-9)throw new IllegalArgumentException("nonzero direction required");
  x/=maximum;y/=maximum;z/=maximum;double horizontal=Math.hypot(x,z);
  return new OrientEffect(id,sequence,(float)Math.toDegrees(Math.atan2(z,x)),0,(float)Math.toDegrees(Math.atan2(y,horizontal)));
 }
}
