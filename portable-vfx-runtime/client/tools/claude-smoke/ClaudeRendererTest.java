package dev.portablevfx.client.claude;

import com.google.gson.JsonParser;
import dev.portablevfx.client.render.EffectBackend;
import java.nio.file.*;
import java.util.*;
import static dev.portablevfx.client.claude.ClaudeEffect.*;

/** CPU/backend lifecycle regression harness. Does not create an OpenGL context. */
public final class ClaudeRendererTest {
    private static int assertions;
    private static void check(boolean value,String message){assertions++;if(!value)throw new AssertionError(message);}
    private static void near(double value,double expected,String message){check(Math.abs(value-expected)<1e-5,message+": "+value+" != "+expected);}
    static ClaudeSimulation simulation(EffectBackend.Instance handle)throws Exception {
        var field=handle.getClass().getDeclaredField("simulation");field.setAccessible(true);return (ClaudeSimulation)field.get(handle);
    }
    public static void main(String[] args)throws Exception {
        Path folder=Path.of(args[0]);var json=JsonParser.parseString(Files.readString(folder.resolve("vfx.json"))).getAsJsonObject();
        var effect=ClaudeEffect.parse(json.toString());json.addProperty("portableVfxSystem","projectile");
        try(var backend=new ClaudeBackend()){
            var asset=backend.load(json.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8),1,p->Files.readAllBytes(folder.resolve(p)));
            var handle=backend.play(asset,123);handle.worldOrigin(1_000_000,200,2_000_000);handle.transform(3,4,5,0,0,0,1);
            handle.sceneTime(10.05);handle.seek(.05f);backend.update(.05f);near(simulation(handle).time(),.05,"First absolute seek");
            var reference=new ClaudeSimulation(effect,"projectile",new Transform(new Vec3(1_000_003,204,2_000_005),Basis.IDENTITY),123);reference.advance((double).05f,reference.transform(),10.05);
            check(simulation(handle).snapshot().equals(reference.snapshot()),"Shared scene clock drives the same world-space noise phase");
            handle.sceneTime(10.1);handle.seek(.1f);backend.update(.05f);near(simulation(handle).time(),.1,"Second absolute seek is delta .05");
            handle.sceneTime(10.15);handle.seek(.15f);backend.update(.05f);near(simulation(handle).time(),.15,"Third absolute seek is delta .05");
            handle.sceneTime(10.15);handle.seek(.15f);backend.update(.05f);near(simulation(handle).time(),.15,"Repeated seek does not double-advance");
            check(simulation(handle).transform().position().equals(new Vec3(1_000_003,204,2_000_005)),"Simulation uses absolute world position");
            boolean rewind=false;try{handle.seek(.1f);}catch(IllegalArgumentException expected){rewind=true;}check(rewind,"Rewind must reject instead of simulating incorrect history");
            handle.seek(.2f);handle.finishEmission();near(simulation(handle).time(),.2,"Hit advances pending seek before stopping");
            check(simulation(handle).snapshot().stream().allMatch(p->p.layer().worldSpace()),"Hit clears all local layers");
            check(!simulation(handle).isEmitting(),"Hit stops world-layer emission");
            handle.seek(4);backend.update(.1f);check(!handle.exists(),"World particles drain naturally");asset.close();
            var noBloom=json.deepCopy();noBloom.getAsJsonObject("post").add("bloom",com.google.gson.JsonNull.INSTANCE);
            noBloom.getAsJsonObject("features").getAsJsonArray("required").remove(new com.google.gson.JsonPrimitive("bloom"));
            backend.load(noBloom.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8),1,p->Files.readAllBytes(folder.resolve(p))).close();
            check(true,"Nullable bloom loads without a null dereference");
        }
        Layer billboard=effect.system("projectile").layers().stream().filter(l->l.render().type().equals("billboard")).findFirst().orElseThrow();
        var particle=new ClaudeSimulation.ParticleView(billboard,Vec3.ZERO,Vec3.ZERO,2,0,Vec3.ZERO,Basis.IDENTITY,new Vec3(1,1,1),1,.5,.5,1,List.of());
        float[] quad=ClaudeGeometry.particles(effect,List.of(particle),Vec3.X,Vec3.Y,new Vec3(0,0,10),6);
        check(quad.length==6*ClaudeGeometry.STRIDE,"Billboard has six vertices");near(quad[0],-1,"Billboard half-width");near(quad[1],-1,"Billboard half-height");
        Vec3 distant=new Vec3(30_000_000,30_000_000,30_000_000);
        var far=new ClaudeSimulation.ParticleView(billboard,distant.add(new Vec3(.25,.5,.75)),Vec3.ZERO,2,0,Vec3.ZERO,Basis.IDENTITY,new Vec3(1,1,1),1,.5,.5,1,List.of());
        float[] rebased=ClaudeGeometry.particles(effect,List.of(far),Vec3.X,Vec3.Y,distant.add(new Vec3(0,0,10)),distant,6);
        near(rebased[0],-.75,"Rebase before float conversion retains distant sub-block precision");near(rebased[1],-.5,"Distant y precision");near(rebased[2],.75,"Distant z precision");
        Flipbook sheet=new Flipbook(4,4,"row-major-top-left","lifetime");
        double[] first=ClaudeGeometry.uv(sheet,0,0,0),last=ClaudeGeometry.uv(sheet,1,0,0);
        near(first[0],0,"First flipbook column");near(first[1],.75,"First flipbook row is top");near(last[0],.75,"Last flipbook column");near(last[1],0,"Last flipbook row is bottom");
        testFrameInterpolation(effect,billboard);
        Vec3 rotated=ClaudeGeometry.rotateEuler(Vec3.X,new Vec3(Math.PI/2,Math.PI/2,Math.PI/2));
        near(rotated.x(),1,"Z then X then Y Euler");near(rotated.y(),0,"Euler y");near(rotated.z(),0,"Euler z");
        var trail=new Trail(billboard.material(),.5,.1,List.of(new CurveKey(0,1,0,0),new CurveKey(1,0,0,0)),List.of(new ColorKey(0,new Vec3(1,1,1)),new ColorKey(1,new Vec3(1,.5,0))));
        var trailLayer=new Layer(billboard.id(),billboard.material(),billboard.render(),billboard.sortingFudge(),billboard.space(),billboard.position(),billboard.emission(),billboard.shape(),billboard.start(),billboard.rotationOverLifetime(),billboard.velocityOverLifetime(),billboard.gravityModifier(),billboard.drag(),billboard.noise(),billboard.colorOverLifetime(),billboard.sizeOverLifetime(),billboard.flipbook(),trail,billboard.requires());
        var trailParticle=new ClaudeSimulation.ParticleView(trailLayer,Vec3.ZERO,Vec3.X,2,0,Vec3.ZERO,Basis.IDENTITY,new Vec3(1,1,1),1,.5,.5,1,List.of(new ClaudeSimulation.TrailPoint(new Vec3(-.2,0,0),.1),new ClaudeSimulation.TrailPoint(new Vec3(-.4,0,0),.2)));
        float[] strip=ClaudeGeometry.trails(List.of(trailParticle),new Vec3(0,0,10),Vec3.X,12);
        check(strip.length==12*ClaudeGeometry.STRIDE,"Two trail segments generate twelve vertices");near(strip[1],-1,"Trail head full authored width");near(strip[11*ClaudeGeometry.STRIDE+8],0,"Trail oldest alpha fades to zero");
        for(int i=0;i<strip.length;i+=ClaudeGeometry.STRIDE)for(int j=9;j<12;j++)near(strip[i+j],0,"Trails keep unanimated texture metadata");
        boolean bounded=false;try{ClaudeGeometry.particles(effect,List.of(particle),Vec3.X,Vec3.Y,new Vec3(0,0,10),5);}catch(IllegalStateException expected){bounded=true;}check(bounded,"Vertex budget rejects, never truncates");
        var smoke=new ClaudeSimulation(effect,"impact",Transform.IDENTITY,456);smoke.advance(.2,Transform.IDENTITY);
        for(var p:smoke.snapshot()){
            float[] geometry=ClaudeGeometry.particles(effect,List.of(p),Vec3.X,Vec3.Y,new Vec3(0,3,10),5000);
            for(float value:geometry)check(Float.isFinite(value),"Fireball geometry is finite");
        }
        System.out.println("PASS ClaudeRendererTest assertions="+assertions+" (CPU geometry, exact seeking, drain, world coordinates)");
    }
    private static void testFrameInterpolation(ClaudeEffect effect,Layer base){
        Flipbook sheet=new Flipbook(2,8,"rowMajorFromTopLeft","oncePerLifetime");
        double rate=.999*16;
        var start=sheet.frameBlend(0);check(start.current()==0&&start.next()==1,"First cell blends toward the adjacent cell");near(start.fraction(),0,"No blend at birth");
        var mid=sheet.frameBlend(1.5/rate);check(mid.current()==1&&mid.next()==2,"Row boundary blends last column toward next row");near(mid.fraction(),.5,"Half-frame interpolation");
        var before=sheet.frameBlend((2-1e-7)/rate);var after=sheet.frameBlend((2+1e-7)/rate);
        check(before.current()==1&&before.next()==2&&after.current()==2&&after.next()==3,"Frame boundary retains authored cell timing");
        near(before.fraction(),1,"Boundary approaches next cell continuously");near(after.fraction(),0,"Boundary restarts from the same visible cell");
        for(double age:new double[]{15.5/rate,1,2}){var last=sheet.frameBlend(age);check(last.current()==15&&last.next()==15,"Final cycle holds final image");near(last.fraction(),0,"Final image never fades to birth image");}
        Flipbook cycles=new Flipbook(2,8,"rowMajorFromTopLeft","oncePerLifetime",3);
        var wrap=cycles.frameBlend(15.5/(rate*3));check(wrap.current()==15&&wrap.next()==0,"Intermediate cycle blends through wrap");near(wrap.fraction(),.5,"Cycle wrap fraction");
        var end=cycles.frameBlend(1);check(end.current()==15&&end.next()==15,"Repeated sheet clamps final cycle");
        for(double age:new double[]{-.1,0,.01,.1,.25,.5,.9,1,2})check(cycles.frameBlend(age).current()==cycles.frame(age),"Smoothing preserves exact source frame selection");
        var one=new Flipbook(1,1,"rowMajorFromTopLeft","oncePerLifetime",3).frameBlend(.3);check(one.current()==0&&one.next()==0&&one.fraction()==0,"Single-cell sheet is unchanged");
        boolean rejected=false;try{sheet.frameBlend(Double.NaN);}catch(IllegalArgumentException expected){rejected=true;}check(rejected,"Nonfinite interpolation age rejects");
        var layer=new Layer(base.id(),base.material(),base.render(),base.sortingFudge(),base.space(),base.position(),base.emission(),base.shape(),base.start(),base.rotationOverLifetime(),base.velocityOverLifetime(),base.gravityModifier(),base.drag(),base.noise(),base.colorOverLifetime(),base.sizeOverLifetime(),sheet,null,base.requires());
        var p=new ClaudeSimulation.ParticleView(layer,Vec3.ZERO,Vec3.ZERO,2,0,Vec3.ZERO,Basis.IDENTITY,new Vec3(.2,.4,.6),.7,1.5/rate,.5,1,List.of());
        float[] vertices=ClaudeGeometry.particles(effect,List.of(p),Vec3.X,Vec3.Y,new Vec3(0,0,10),6);
        for(int i=0;i<vertices.length;i+=ClaudeGeometry.STRIDE){near(vertices[i+9],1,"Vertex current frame");near(vertices[i+10],2,"Vertex next frame");near(vertices[i+11],.5,"Vertex blend fraction");near(vertices[i+8],.7,"Particle alpha is not doubled by interpolation");}
        double[] sourceUv=ClaudeGeometry.uv(sheet,1.5/rate,0,0);near(vertices[3],sourceUv[0],"Original U retained");near(vertices[4],sourceUv[1],"Original V retained");
    }
}
