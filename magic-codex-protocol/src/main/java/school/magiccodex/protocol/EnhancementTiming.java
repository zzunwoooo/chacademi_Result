package school.magiccodex.protocol;
/** Shared visual judgement window. Server applies its measured round-trip compensation first. */
public final class EnhancementTiming {
 private EnhancementTiming(){}
 public static boolean inWindow(double elapsed,int target,int window){return Math.abs(elapsed-target)<=window;}
 public static float radius(double elapsed,int previous,int target){return (float)Math.clamp(15+28*(target-elapsed)/Math.max(1,target-previous),2,43);}
}
