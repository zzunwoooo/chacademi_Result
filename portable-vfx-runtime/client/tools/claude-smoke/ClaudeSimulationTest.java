import dev.portablevfx.client.claude.ClaudeEffect;
import dev.portablevfx.client.claude.ClaudeSimulation;
import static dev.portablevfx.client.claude.ClaudeEffect.*;
import java.nio.file.*;
import java.util.*;
import com.google.gson.*;

/** Standalone contract tests; no Minecraft, rendering context or testing-library dependency. */
public final class ClaudeSimulationTest {
    private static int assertions;
    private static final double H=1.0/120;
    public static void main(String[] args)throws Exception {
        if(args.length!=1)throw new IllegalArgumentException("Pass actual Fireball/vfx.json path");
        byte[] json=Files.readAllBytes(Path.of(args[0]));
        ClaudeEffect effect=ClaudeEffect.parse(json);
        equal(effect.systems().size(),2,"two systems");
        equal(effect.system("projectile").layers().size(),7,"projectile layers");
        equal(effect.system("impact").layers().size(),11,"impact layers");
        testMath();testActualEffect(effect);testFrames(effect);testIntegration(effect);testOrbitAndNoise(effect);testTrail(effect);testRejects(json);
        System.out.println("ClaudeSimulationTest: "+assertions+" assertions passed");
    }
    private static void testMath() {
        var curve=List.of(new CurveKey(0,0,0,2),new CurveKey(1,1,0,0));
        close(sampleCurve(curve,0.5),0.75,"Hermite outgoing tangent");
        close(sampleCurve(curve,-1),0,"Hermite left hold");close(sampleCurve(curve,2),1,"Hermite right hold");
        var gradient=new Gradient(List.of(new ColorKey(0,Vec3.ZERO),new ColorKey(1,new Vec3(1,1,1))),List.of(new AlphaKey(0,0),new AlphaKey(1,1)));
        close(gradient.colorAt(.5).x(),.5,"color interpolates sRGB before linearization");
        close(gradient.colorAt(.5).linear().x(),Math.pow(.5,2.2),"gamma2.2 conversion");close(gradient.alphaAt(.25),.25,"linear alpha");
        Flipbook flipbook=new Flipbook(4,4,"rowMajorFromTopLeft","oncePerLifetime");
        equal(flipbook.frame(0),0,"first flipbook frame");equal(flipbook.frame(1),15,"last flipbook frame");
        close(flipbook.uv(0,1,0).u(),0,"top-left first U");close(flipbook.uv(0,1,0).v(),0,"top-left first V");
        close(flipbook.uv(1,0,1).u(),1,"last U");close(flipbook.uv(1,0,1).v(),1,"last V");
        vector(Vec3.X.rotateEuler(new Vec3(0,Math.PI/2,0)),new Vec3(0,0,-1),"schema Y Euler sign");
        vector(Vec3.X.rotateEuler(new Vec3(Math.PI/2,0,Math.PI/2)),Vec3.Z,"Euler Z then X");
        var n=ClaudeSimulation.noise3(Vec3.ZERO,0,1,1);
        vector(n,new Vec3(.3*Math.sin(1.3),.6*Math.sin(2.1),.6*(Math.sin(4.2)+.5*Math.sin(.4))),"exact trigonometric noise");
        for(Vec3 dir:List.of(Vec3.X,Vec3.Y,Vec3.Y.multiply(-1),Vec3.Z,new Vec3(.2,.3,.8))) {
            Basis b=Basis.projectile(dir);vector(b.forward(),dir.normalized(),"projectile forward");
            vector(b.inverse(b.apply(new Vec3(2,3,4))),new Vec3(2,3,4),"orthonormal inverse");
        }
        for(Vec3 normal:List.of(Vec3.Y,Vec3.X,Vec3.Y.multiply(-1))) {
            Basis b=Basis.impact(normal,Vec3.X);vector(b.up(),normal,"impact surface normal");
            close(b.up().dot(b.forward()),0,"impact forward tangent");
        }
        vector(Basis.impact(null,Vec3.Z).up(),Vec3.Z.multiply(-1),"air impact normal");
    }
    private static void testActualEffect(ClaudeEffect effect) {
        var a=new ClaudeSimulation(effect,"projectile",Transform.IDENTITY,123);
        var b=new ClaudeSimulation(effect,"projectile",Transform.IDENTITY,123);
        a.advance(1,new Transform(new Vec3(6,0,0),Basis.IDENTITY));
        for(int i=1;i<=240;i++) b.advance(1.0/240,new Transform(new Vec3(i/40.0,0,0),Basis.IDENTITY));
        equal(a.totalSpawned(),277,"actual flight rate and distance emission");equal(a.totalSpawned(),b.totalSpawned(),"sub-step partition same emissions");
        equal(a.particleCount(),b.particleCount(),"sub-step partition same count");
        compare(a.snapshot(),b.snapshot(),"sub-step partition stable");
        var c=new ClaudeSimulation(effect,"projectile",Transform.IDENTITY,123);c.advance(1,new Transform(new Vec3(6,0,0),Basis.IDENTITY));
        compare(a.snapshot(),c.snapshot(),"deterministic seed");
        int world=(int)a.snapshot().stream().filter(p->p.layer().worldSpace()).count();
        a.stopEmission(true);a.stopEmission(true);equal(a.particleCount(),world,"hit clears local and preserves world");
        check(a.snapshot().stream().allMatch(p->p.layer().worldSpace()),"no local particle after hit");
        long spawned=a.totalSpawned();a.advance(3,new Transform(new Vec3(100,0,0),Basis.IDENTITY));
        equal(a.totalSpawned(),spawned,"idempotent finish emits nothing");check(a.isFinished(),"world tail drains");
        var impact=new ClaudeSimulation(effect,"impact",Transform.IDENTITY,567);
        impact.step(Transform.IDENTITY,H);equal(impact.totalSpawned(),86,"impact includes t0 bursts in first step");
        impact.advance(1-H,Transform.IDENTITY);equal(impact.totalSpawned(),181,"actual impact full burst count");
        impact.advance(2,Transform.IDENTITY);check(impact.isFinished(),"impact naturally finishes");
    }
    private static void testFrames(ClaudeEffect effect) {
        Layer base=effect.system("projectile").layers().getFirst();
        Layer local=make(base,"local",new Vec3(1,0,0),new Emission(0,0,List.of(new Burst(0,1,1,0))),new Velocity("local",zero3(),zero(),zero()),0,0,null,null,new Range(2,2),zero());
        Layer world=make(base,"world",new Vec3(1,0,0),local.emission(),local.velocityOverLifetime(),0,0,null,null,new Range(2,2),zero());
        var ls=simulation(effect,local);var ws=simulation(effect,world);
        ls.step(Transform.IDENTITY,H);ws.step(Transform.IDENTITY,H);
        Transform moved=new Transform(new Vec3(10,0,0),Basis.IDENTITY);
        ls.step(moved,2*H);ws.step(moved,2*H);
        vector(ls.snapshot().getFirst().worldPosition(),new Vec3(11,0,0),"local follows system");
        vector(ws.snapshot().getFirst().worldPosition(),new Vec3(1,0,0),"world freezes birth frame");
        Layer falling=make(base,"local",Vec3.ZERO,local.emission(),new Velocity("world",new Range3(zero(),new Range(1,1),zero()),zero(),zero()),1,0,null,null,new Range(2,2),zero());
        Basis wall=Basis.impact(Vec3.X,Vec3.X);
        var fs=new ClaudeSimulation(single(effect,falling),"test",new Transform(Vec3.ZERO,wall),8);
        fs.step(new Transform(Vec3.ZERO,wall),H);
        Vec3 pos=fs.snapshot().getFirst().worldPosition();close(pos.x(),0,"wall world gravity no X");close(pos.y(),H-effect.gravity()*H*H,"wall gravity and world linear remain vertical");
    }
    private static void testIntegration(ClaudeEffect effect) {
        Layer base=effect.system("projectile").layers().getFirst();
        var burst=new Emission(0,0,List.of(new Burst(0,1,1,0)));
        var velocity=new Velocity("local",new Range3(new Range(3,3),zero(),zero()),zero(),zero());
        Layer l=make(base,"local",Vec3.ZERO,burst,velocity,1,2,null,null,new Range(2,2),new Range(4,4));
        var s=simulation(effect,l);s.step(Transform.IDENTITY,H);var p=s.snapshot().getFirst();
        close(p.worldPosition().x(),3*H,"linear velocity not damped by drag");
        close(p.worldPosition().y(),(4-effect.gravity()*H)*(1-2*H)*H,"gravity then drag then displacement");
        vector(p.worldVelocity(),p.worldPosition().multiply(1/H),"render velocity includes all motion");
        Layer distance=make(base,"world",Vec3.ZERO,new Emission(0,2,List.of()),new Velocity("world",zero3(),zero(),zero()),0,0,null,null,new Range(2,2),zero());
        var d=simulation(effect,distance);d.step(new Transform(new Vec3(2,0,0),Basis.IDENTITY),H);
        equal(d.particleCount(),4,"distance count");for(int i=0;i<4;i++)close(d.snapshot().get(i).worldPosition().x(),.4*(i+1),"distance interpolated spawn "+i);
        Layer events=make(base,"local",Vec3.ZERO,new Emission(0,0,List.of(new Burst(H,2,2,H))),new Velocity("local",zero3(),zero(),zero()),0,0,null,null,new Range(2,2),zero());
        var e=simulation(effect,events);e.step(Transform.IDENTITY,H);equal(e.totalSpawned(),0,"upper burst interval exclusive");e.step(Transform.IDENTITY,2*H);equal(e.totalSpawned(),2,"next interval includes boundary");e.step(Transform.IDENTITY,3*H);equal(e.totalSpawned(),4,"burst cycles honored");
    }
    private static void testOrbitAndNoise(ClaudeEffect effect) {
        Layer base=effect.system("projectile").layers().getFirst();
        Layer l=make(base,"local",Vec3.ZERO,new Emission(0,0,List.of(new Burst(0,1,1,0))),new Velocity("local",zero3(),new Range(Math.PI/2/H,Math.PI/2/H),new Range(2,2)),0,0,null,null,new Range(2,2),zero());
        l=withShape(l,new Shape("none",0,1,Vec3.X,0));
        var s=simulation(effect,l);s.step(Transform.IDENTITY,H);
        vector(s.snapshot().getFirst().worldPosition(),new Vec3(0,0,-1-2*H),"orbital then radial integration");
        Layer noisy=make(base,"local",Vec3.ZERO,new Emission(0,0,List.of(new Burst(0,1,1,0))),new Velocity("local",zero3(),zero(),zero()),0,0,new Noise(2,3,.7),null,new Range(2,2),zero());
        var n=simulation(effect,noisy);n.step(Transform.IDENTITY,10);
        vector(n.snapshot().getFirst().worldPosition(),ClaudeSimulation.noise3(Vec3.ZERO,10,3,.7).multiply(2*H),"noise uses shared scene time");
    }
    private static Layer withShape(Layer l,Shape shape) {
        return new Layer(l.id(),l.material(),l.render(),l.sortingFudge(),l.space(),l.position(),l.emission(),shape,l.start(),l.rotationOverLifetime(),l.velocityOverLifetime(),l.gravityModifier(),l.drag(),l.noise(),l.colorOverLifetime(),l.sizeOverLifetime(),l.flipbook(),l.trail(),l.requires());
    }
    private static void testTrail(ClaudeEffect effect) {
        Layer base=effect.system("projectile").layers().getFirst();
        var trail=new Trail(base.material(),.1,.005,List.of(new CurveKey(0,1,-1,-1),new CurveKey(1,0,-1,-1)),List.of(new ColorKey(0,new Vec3(1,1,1)),new ColorKey(1,new Vec3(1,0,0))));
        Layer l=make(base,"local",Vec3.ZERO,new Emission(0,0,List.of(new Burst(0,1,1,0))),new Velocity("local",zero3(),zero(),zero()),0,0,null,trail,new Range(2,2),new Range(1,1));
        var s=simulation(effect,l);s.advance(.5,Transform.IDENTITY);var p=s.snapshot().getFirst();
        check(!p.trail().isEmpty(),"trail retains moving history");
        double age=-1;for(var pt:p.trail()){check(pt.age()>age,"trail newest first");check(pt.age()<=.2+1e-9,"trail lifetime bound");age=pt.age();}
        close(trail.widthAt(.5),.5,"trail width curve");vector(trail.colorAt(.5),new Vec3(1,.5,.5),"trail color interpolation");
    }
    private static void testRejects(byte[] bytes) {
        String json=new String(bytes,java.nio.charset.StandardCharsets.UTF_8);
        mutateReject(json,o->o.addProperty("schemaVersion","2.0.0"),"unsupported major");
        mutateReject(json,o->o.addProperty("unimplementedSecretModule",true),"unknown root field");
        mutateReject(json,o->layer(o).addProperty("collision",true),"unknown layer field");
        mutateReject(json,o->o.getAsJsonObject("simulation").addProperty("fixedTimeStep",.05),"non120Hz timestep");
        mutateReject(json,o->layer(o).getAsJsonObject("start").add("lifetime",JsonParser.parseString("[1,100]")),"lifetime budget");
        mutateReject(json,o->layer(o).getAsJsonObject("render").addProperty("type","model"),"unsupported renderer");
        mutateReject(json,o->o.getAsJsonObject("materials").getAsJsonObject("glow").addProperty("texture","textures/../../secret.png"),"path traversal");
        mutateReject(json,o->o.getAsJsonObject("features").getAsJsonArray("required").add("modelParticle"),"required unsupported capability");
        JsonObject metadata=JsonParser.parseString(json).getAsJsonObject();metadata.addProperty("portableVfxSystem","projectile");check(ClaudeEffect.parse(metadata.toString())!=null,"loader selector accepted");
    }
    private static JsonObject layer(JsonObject root){return root.getAsJsonArray("systems").get(0).getAsJsonObject().getAsJsonArray("layers").get(0).getAsJsonObject();}
    private static void mutateReject(String json,java.util.function.Consumer<JsonObject> mutate,String name){var o=JsonParser.parseString(json).getAsJsonObject();mutate.accept(o);try{ClaudeEffect.parse(o.toString());throw new AssertionError(name+" accepted");}catch(ClaudeEffect.ValidationException expected){check(!expected.getMessage().isBlank(),name);}}
    private static Layer make(Layer b,String space,Vec3 position,Emission emission,Velocity velocity,double gravity,double drag,Noise noise,Trail trail,Range lifetime,Range speed) {
        return new Layer("test",b.material(),b.render(),0,space,position,emission,new Shape("none",0,1,Vec3.ZERO,0),new Start(lifetime,new Range(1,1),speed,zero(),null),zero3(),velocity,gravity,drag,noise,b.colorOverLifetime(),List.of(new CurveKey(0,1,0,0)),null,trail,b.requires());
    }
    private static ClaudeEffect single(ClaudeEffect e,Layer l){return new ClaudeEffect("test","1.0.0",H,9.81,e.post(),e.materials(),e.meshes(),List.of(new SystemDef("test","static",2,false,List.of(l))),Set.of());}
    private static ClaudeSimulation simulation(ClaudeEffect e,Layer l){return new ClaudeSimulation(single(e,l),"test",Transform.IDENTITY,1);}
    private static Range zero(){return new Range(0,0);}private static Range3 zero3(){return new Range3(zero(),zero(),zero());}
    private static void compare(List<ClaudeSimulation.ParticleView> a,List<ClaudeSimulation.ParticleView>b,String why){equal(a.size(),b.size(),why);for(int i=0;i<a.size();i++){var x=a.get(i);var y=b.get(i);check(x.layer().id().equals(y.layer().id()),why+" layer");vector(x.worldPosition(),y.worldPosition(),why+" position");vector(x.worldVelocity(),y.worldVelocity(),why+" velocity");close(x.age(),y.age(),why+" age");close(x.size(),y.size(),why+" size");}}
    private static void vector(Vec3 a,Vec3 b,String name){close(a.x(),b.x(),name+" x");close(a.y(),b.y(),name+" y");close(a.z(),b.z(),name+" z");}
    private static void close(double a,double b,String name){check(Math.abs(a-b)<=1e-8,name+" expected="+b+" actual="+a);}
    private static void equal(long a,long b,String name){check(a==b,name+" expected="+b+" actual="+a);}
    private static void check(boolean condition,String name){assertions++;if(!condition)throw new AssertionError(name);}
}
