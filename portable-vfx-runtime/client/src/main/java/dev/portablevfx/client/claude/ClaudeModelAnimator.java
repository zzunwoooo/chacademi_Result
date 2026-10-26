package dev.portablevfx.client.claude;

import java.util.Arrays;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;

/** Deterministic glTF LINEAR node-TRS sampling in schema Unity coordinates. */
public final class ClaudeModelAnimator {
    private ClaudeModelAnimator() { }
    public static final class Pose {
        private final Matrix4f[] globals;
        private final double clipTime;
        private Pose(Matrix4f[] globals,double clipTime) { this.globals=globals;this.clipTime=clipTime; }
        public int nodeCount() { return globals.length; }
        public double clipTime() { return clipTime; }
        public Matrix4f global(int node) { return new Matrix4f(globals[node]); }
        public Matrix4f global(int node,Matrix4f destination) { return destination.set(globals[node]); }
    }
    /** Null clip samples the rest pose. Seconds are already age * animation.speed. */
    public static Pose sample(ClaudeModel model,String clip,double seconds,boolean loop) {
        if(!Double.isFinite(seconds))throw new IllegalArgumentException("Non-finite animation time");
        var animation=clip==null?null:model.animations().get(clip);
        if(clip!=null&&animation==null)throw new IllegalArgumentException("Unknown model animation: "+clip);
        double time=animation==null?0:clipTime(seconds,animation.duration(),loop);
        int count=model.nodes().size();
        var translations=new Vector3f[count];var rotations=new Quaternionf[count];var scales=new Vector3f[count];
        for(int i=0;i<count;i++) {
            var node=model.nodes().get(i);var t=node.translation();var q=node.rotation();var s=node.scale();
            translations[i]=new Vector3f(t.x(),t.y(),t.z());rotations[i]=new Quaternionf(q.x(),q.y(),q.z(),q.w());
            scales[i]=new Vector3f(s.x(),s.y(),s.z());
        }
        if(animation!=null)for(var channel:animation.channels()) {
            float[] keys=channel.times,values=channel.values;
            int lower,upper;float fraction;
            if(time<=keys[0]) { lower=upper=0;fraction=0; }
            else if(time>=keys[keys.length-1]) { lower=upper=keys.length-1;fraction=0; }
            else {
                int found=Arrays.binarySearch(keys,(float)time);
                if(found>=0){lower=upper=found;fraction=0;}
                else {upper=-found-1;lower=upper-1;fraction=(float)((time-keys[lower])/(keys[upper]-keys[lower]));}
            }
            if(channel.target==ClaudeModel.Target.ROTATION) {
                int a=lower*4,b=upper*4;
                var start=new Quaternionf(values[a],values[a+1],values[a+2],values[a+3]);
                var end=new Quaternionf(values[b],values[b+1],values[b+2],values[b+3]);
                start.slerp(end,fraction,rotations[channel.node]).normalize();
            } else {
                int a=lower*3,b=upper*3;
                Vector3f value=channel.target==ClaudeModel.Target.TRANSLATION?translations[channel.node]:scales[channel.node];
                value.set(values[a]+(values[b]-values[a])*fraction,values[a+1]+(values[b+1]-values[a+1])*fraction,
                        values[a+2]+(values[b+2]-values[a+2])*fraction);
            }
        }
        Matrix4f[] global=new Matrix4f[count];
        for(int i:model.sceneOrder()) {
            var local=new Matrix4f().translationRotateScale(translations[i],rotations[i],scales[i]);
            int parent=model.nodes().get(i).parent();
            global[i]=parent<0?local:new Matrix4f(global[parent]).mul(local);
            if(!global[i].isFinite())throw new IllegalArgumentException("Model transform overflow at node "+i);
        }
        return new Pose(global,time);
    }
    public static double clipTime(double seconds,double duration,boolean loop) {
        if(!Double.isFinite(seconds)||!Double.isFinite(duration)||duration<0)
            throw new IllegalArgumentException("Invalid animation time/duration");
        if(duration==0)return 0;
        if(!loop)return Math.max(0,Math.min(duration,seconds));
        double wrapped=seconds%duration;
        return wrapped<0?wrapped+duration:wrapped;
    }
}
