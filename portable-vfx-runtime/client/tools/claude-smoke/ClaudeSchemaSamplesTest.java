import com.google.gson.*;
import dev.portablevfx.client.claude.*;
import dev.portablevfx.client.claude.ClaudeSimulation.ParticleView;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.function.Consumer;
import static dev.portablevfx.client.claude.ClaudeEffect.*;

/** CPU contracts exercised against byte-identical supplied schema 1.1 and 1.12 samples.
 * No demo geometry, gameplay, target detection, damage, or spell-specific runtime is installed.
 */
public final class ClaudeSchemaSamplesTest {
    private static final double H=1.0/120;
    private static int assertions;
    public static void main(String[] args)throws Exception {
        Path root=Path.of(args.length==0?"client/src/test/resources/claude/samples":args[0]);
        byte[] feather=fixture(root,"FeatherFall","7b86a34698c84590bad491826697ab68ad36b214dc9322e148df3c7d59e9955e");
        byte[] tidal=fixture(root,"TidalWave","797bf86b2009e2cd4ffd95dabad46985f9ddd5825392c86d995f223906214908");
        ClaudeEffect f=ClaudeEffect.parse(feather),t=ClaudeEffect.parse(tidal);
        check(f.schemaVersion().equals("1.1.0"),"actual FeatherFall schema");
        check(f.system("aura").role().equals("attached"),"actual attached role");
        equal(f.system("aura").layers().size(),18,"actual FeatherFall layers");
        check(t.schemaVersion().equals("1.12.0"),"actual TidalWave schema");
        equal(t.systems().size(),3,"actual TidalWave systems");
        equal(t.meshes().get("waveBody").vertices().size(),1767,"actual wave mesh retained");
        testAttached(f);testAttachedRenderInterpolation(f);testWidthMath(t);testCountsAndBoxes(t);testGeometry(t);testFlipbooks(t);
        testActualLifecycles(t);testRejects(feather,tidal);
        System.out.println("ClaudeSchemaSamplesTest: "+assertions+" assertions passed (untouched FeatherFall + TidalWave)");
    }
    private static byte[] fixture(Path root,String name,String expected)throws Exception {
        byte[] bytes=Files.readAllBytes(root.resolve(name).resolve("vfx.json"));
        check(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)).equals(expected),name+" source SHA-256 unchanged");
        return bytes;
    }
    private static void testAttached(ClaudeEffect effect) {
        var fixed=new ClaudeSimulation(effect,"aura",Transform.IDENTITY,1);
        var moving=new ClaudeSimulation(effect,"aura",Transform.IDENTITY,1);
        fixed.advance(.5,Transform.IDENTITY);moving.advance(.5,Transform.IDENTITY);
        fixed.stopEmission(false);moving.stopEmission(false);
        Transform moved=Transform.attached(new Vec3(12,-3,7),new Vec3(3,100,4));
        vector(moved.basis().up(),Vec3.Y,"attached keeps world up");
        vector(moved.basis().forward(),new Vec3(.6,0,.8),"attached uses horizontal body facing");
        vector(Basis.attached(Vec3.Y).forward(),Vec3.Z,"vertical facing has stable horizontal fallback");
        fixed.step(Transform.IDENTITY,.5+H);moving.step(moved,.5+H);
        var a=fixed.snapshot();var b=moving.snapshot();equal(a.size(),b.size(),"attached same particles");
        int local=0,world=0;
        for(int i=0;i<a.size();i++) {
            var p=a.get(i);var q=b.get(i);
            if(p.layer().worldSpace()) { world++;vector(q.worldPosition(),p.worldPosition(),"attached world wake stays behind"); }
            else { local++;vector(q.worldPosition(),moved.apply(p.worldPosition()),"attached local follows fall/turn/translation"); }
        }
        check(local>0&&world>0,"actual attached sample exercises both spaces");
        moving.stopEmission(true);equal(moving.particleCount(),world,"entity removal clears only local particles");
        moving.advance(10,moved);check(moving.isFinished(),"attached wake drains after removal");
        var natural=new ClaudeSimulation(effect,"aura",Transform.IDENTITY,2);
        natural.advance(10,Transform.IDENTITY);check(natural.isFinished(),"actual attached effect naturally ends");
    }
    private static void testAttachedRenderInterpolation(ClaudeEffect effect) {
        var fixed=new ClaudeSimulation(effect,"aura",Transform.IDENTITY,13);
        var moving=new ClaudeSimulation(effect,"aura",Transform.IDENTITY,13);
        fixed.advance(.5,Transform.IDENTITY);moving.advance(.5,Transform.IDENTITY);
        var exact=moving.snapshot();long emitted=moving.totalSpawned();
        // Both calls are less than one step. A renderer must still follow this latest entity pose.
        Transform pose=Transform.attached(new Vec3(4,-2,7),Vec3.X);
        fixed.advance(H*.25,Transform.IDENTITY);moving.advance(H*.25,pose);
        check(moving.snapshot().equals(exact),"render-only fraction preserves every fixed-step particle field");
        equal(moving.totalSpawned(),emitted,"render-only fraction emits no extra particles");
        var a=fixed.renderSnapshot();var b=moving.renderSnapshot();
        equal(a.size(),b.size(),"render interpolation preserves attached particle counts");
        int local=0,world=0;
        for(int i=0;i<a.size();i++) {
            var p=a.get(i);var q=b.get(i);
            near(p.age(),exact.get(i).age()-.75*H,"particle curves use fractional render age");
            if(p.layer().worldSpace()) { world++;check(q.equals(p),"sub-step world wake unaffected by follow pose"); }
            else {
                local++;vector(q.worldPosition(),pose.apply(p.worldPosition()),"sub-step attached position follows latest pose");
                vector(q.worldVelocity(),pose.basis().apply(p.worldVelocity()),"sub-step attached velocity follows latest basis");
                check(q.basis().equals(pose.basis()),"sub-step mesh uses latest entity basis");
                equal(q.trail().size(),p.trail().size(),"local trail point count preserved");
                for(int j=0;j<p.trail().size();j++) vector(q.trail().get(j).worldPosition(),pose.apply(p.trail().get(j).worldPosition()),"local trail follows latest pose");
            }
            for(var point:q.trail()) check(point.age()>=0,"render trails never contain future points");
        }
        check(local>0&&world>0,"render follow regression exercises actual local and world FeatherFall layers");
        var once=moving.renderSnapshot();check(once.equals(moving.renderSnapshot()),"multiple draws do not mutate simulation");
        fixed.advance(H*.75,Transform.IDENTITY);moving.advance(H*.75,Transform.IDENTITY);
        // Particle rendering did not consume random numbers or change any particle integration.
        check(fixed.snapshot().equals(moving.snapshot()),"render reads preserve next-step deterministic state");
    }
    private static void testWidthMath(ClaudeEffect e) {
        near(e.width().factor(null),1,"missing width uses reference");
        near(e.width().factor(.01),.5,"width lower clamp");near(e.width().factor(1000.0),2.2,"width upper clamp");
        near(e.width().factor("wave",14.0),2,"wave width selected");near(e.width().factor("collapse",14.0),2,"collapse width selected");
        near(e.width().factor("splash",14.0),1,"enemy splash width excluded");
        double c=Math.sqrt(.5);Basis tilted=new Basis(new Vec3(c,c,0),new Vec3(-c,c,0),Vec3.Z);
        for(double sx:new double[]{.5,1,2.2}) vector(tilted.inverse(tilted.apply(new Vec3(2,3,4),sx),sx),new Vec3(2,3,4),"true nonuniform inverse");
        Layer source=e.system("wave").layers().getFirst();
        Layer local=plain(source,"local",new Shape("none",0,1,Vec3.ZERO,0),new Emission(0,0,List.of(new Burst(0,1,1,0))),new Vec3(3,4,5),"world",1,0,null);
        ClaudeEffect one=single(e,"wave",local,Set.of());
        Transform pose=new Transform(Vec3.ZERO,tilted);
        var simulation=new ClaudeSimulation(one,"wave",pose,3,14.0);simulation.step(pose,H);
        Vec3 expected=new Vec3(3*H,4*H-e.gravity()*H*H,5*H);
        vector(simulation.snapshot().getFirst().worldPosition(),expected,"world linear and gravity are not width-scaled on a tilted local frame");
        Layer world=plain(source,"world",new Shape("sphere",2,0,new Vec3(.5,0,0),0),local.emission(),new Vec3(1,2,3),"local",0,3,null);
        var narrow=new ClaudeSimulation(single(e,"wave",world,Set.of()),"wave",Transform.IDENTITY,5,7.0);
        var wide=new ClaudeSimulation(single(e,"wave",world,Set.of()),"wave",Transform.IDENTITY,5,14.0);
        narrow.step(Transform.IDENTITY,H);wide.step(Transform.IDENTITY,H);
        var p=narrow.snapshot().getFirst();var q=wide.snapshot().getFirst();
        vector(q.worldPosition(),p.worldPosition().multiply(new Vec3(2,1,1)),"world spawn point, birth velocity and local linear are width-scaled");
        vector(q.worldVelocity(),p.worldVelocity().multiply(new Vec3(2,1,1)),"world render velocity follows nonuniform basis");
        near(q.size(),p.size(),"width does not change particle size");
        for(double invalid:new double[]{0,-1,Double.NaN,Double.POSITIVE_INFINITY}) {
            rejectsAction(()->new ClaudeSimulation(e,"wave",Transform.IDENTITY,1,invalid),"invalid runtime width");
        }
    }
    private static void testCountsAndBoxes(ClaudeEffect effect) {
        for(SystemDef system:effect.systems()) for(Layer layer:system.layers()) for(double width:new double[]{3.5,7,15.4}) {
            var one=single(effect,system.id(),layer,effect.width().countScaledLayers());
            var sim=new ClaudeSimulation(one,system.id(),Transform.IDENTITY,17,width);
            double t=.25,distance=1,sx=effect.width().countScaledLayers().contains(system.id()+"/"+layer.id())?effect.width().factor(system.id(),width):1;
            sim.advance(t,new Transform(Vec3.Z,Basis.IDENTITY));
            long expected=(long)Math.floor(layer.emission().rateOverTime()*t*sx+1e-9)+(long)Math.floor(layer.emission().rateOverDistance()*distance*sx+1e-9);
            for(Burst burst:layer.emission().bursts()) for(int cycle=0;cycle<burst.cycles();cycle++) {
                double at=burst.time()+cycle*burst.interval();
                if(at<t-1e-9&&at<system.duration()) expected+=sx==1?burst.count():Math.max(1,Math.round(burst.count()*sx));
            }
            equal(sim.totalSpawned(),expected,"actual selected counts/rates "+system.id()+"/"+layer.id()+" width="+width);
        }
        Layer source=effect.system("wave").layers().getFirst();
        Layer box=plain(source,"local",new Shape("box",0,1,Vec3.ZERO,2,6,4),new Emission(0,0,List.of(new Burst(0,200,1,0))),Vec3.ZERO,"local",0,0,null);
        var sim=new ClaudeSimulation(single(effect,"wave",box,Set.of()),"wave",Transform.IDENTITY,19,14.0);sim.step(Transform.IDENTITY,H);
        double maxX=0,maxY=0,maxZ=0;
        for(ParticleView p:sim.snapshot()) {
            Vec3 point=p.worldPosition();check(Math.abs(point.x())<=6&&Math.abs(point.y())<=1&&Math.abs(point.z())<=2,"rectangular box bounds with width transform");
            maxX=Math.max(maxX,Math.abs(point.x()));maxY=Math.max(maxY,Math.abs(point.y()));maxZ=Math.max(maxZ,Math.abs(point.z()));
        }
        check(maxX>5&&maxY>.8&&maxZ>1.5,"box spans each authored dimension");
        Trail trail=new Trail(source.material(),1,.001,List.of(new CurveKey(0,1,0,0)),List.of(new ColorKey(0,new Vec3(1,1,1))));
        Layer tr=plain(source,"local",new Shape("none",0,1,Vec3.ZERO,0),new Emission(0,0,List.of(new Burst(0,1,1,0))),Vec3.X,"local",0,0,trail);
        var a=new ClaudeSimulation(single(effect,"wave",tr,Set.of()),"wave",Transform.IDENTITY,2,7.0);
        var b=new ClaudeSimulation(single(effect,"wave",tr,Set.of()),"wave",Transform.IDENTITY,2,14.0);
        a.advance(.1,Transform.IDENTITY);b.advance(.1,Transform.IDENTITY);
        var p=a.snapshot().getFirst();var q=b.snapshot().getFirst();equal(q.trail().size(),p.trail().size(),"unscaled local trail sampling");
        for(int i=0;i<p.trail().size();i++) vector(q.trail().get(i).worldPosition(),p.trail().get(i).worldPosition().multiply(new Vec3(2,1,1)),"trail points stretch with system");
    }
    private static void testGeometry(ClaudeEffect e) {
        Layer meshLayer=e.system("wave").layers().getFirst();Mesh mesh=e.meshes().get(meshLayer.render().mesh());
        Vec3 rotation=new Vec3(.4,.5,.7),center=new Vec3(3,4,5);Basis basis=Basis.projectile(new Vec3(1,.5,1));
        ParticleView p=particle(meshLayer,center,rotation,basis,2);
        float[] points=ClaudeGeometry.particles(e,List.of(p),Vec3.X,Vec3.Y,new Vec3(0,4,10),100_000);
        for(int i=0;i<mesh.triangles().size();i++) {
            Vec3 vertex=mesh.vertices().get(mesh.triangles().get(i)).rotateEuler(rotation);
            Vec3 expected=center.add(basis.apply(vertex,2));int index=i*ClaudeGeometry.STRIDE;
            nearFloat(points[index],expected.x(),"mesh X stretch after Euler");nearFloat(points[index+1],expected.y(),"mesh Y transform");nearFloat(points[index+2],expected.z(),"mesh Z transform");
        }
        Layer billboard=e.system("wave").layers().stream().filter(l->l.render().type().equals("billboard")).findFirst().orElseThrow();
        float[] a=ClaudeGeometry.particles(e,List.of(particle(billboard,center,Vec3.ZERO,Basis.IDENTITY,1)),Vec3.X,Vec3.Y,new Vec3(0,0,10),6);
        float[] b=ClaudeGeometry.particles(e,List.of(particle(billboard,center,Vec3.ZERO,Basis.IDENTITY,2)),Vec3.X,Vec3.Y,new Vec3(0,0,10),6);
        check(Arrays.equals(a,b),"billboard dimensions never inherit width scale");
        Layer stretched=e.system("wave").layers().stream().filter(l->l.render().type().equals("stretched")).findFirst().orElseThrow();
        a=ClaudeGeometry.particles(e,List.of(particle(stretched,center,Vec3.ZERO,Basis.IDENTITY,1)),Vec3.X,Vec3.Y,new Vec3(0,0,10),6);
        b=ClaudeGeometry.particles(e,List.of(particle(stretched,center,Vec3.ZERO,Basis.IDENTITY,2)),Vec3.X,Vec3.Y,new Vec3(0,0,10),6);
        check(Arrays.equals(a,b),"stretched quad receives scale only through center/velocity");
    }
    private static ParticleView particle(Layer layer,Vec3 p,Vec3 rotation,Basis basis,double sx) {
        return new ParticleView(layer,p,new Vec3(1,1,0),1,0,rotation,basis,new Vec3(1,1,1),1,.2,.2,1,List.of(),sx);
    }
    private static void testFlipbooks(ClaudeEffect e) {
        Flipbook f=e.system("wave").layers().stream().filter(l->l.id().equals("W10_Body")).findFirst().orElseThrow().flipbook();
        equal(f.cycles(),30,"actual repeated flowing water cycles");
        for(double t:new double[]{0,.02,.0334,.1,.25,.5,.999,1}) {
            int expected=(int)Math.floor(t*.999*8*30)%8;equal(f.frame(t),expected,"schema repeated frame phase");
            double[] uv=ClaudeGeometry.uv(f,t,0,0);near(uv[0],(expected%2)/2.0,"repeating UV column");near(uv[1],1-(expected/2+1)/4.0,"repeating UV top row");
        }
        Flipbook old=new Flipbook(4,4,"rowMajorFromTopLeft","oncePerLifetime");equal(old.cycles(),1,"legacy constructor cycles default");equal(old.frame(1),15,"legacy last frame");
    }
    private static void testActualLifecycles(ClaudeEffect e) {
        for(double width:new double[]{3.5,7,15.4}) {
            var wave=new ClaudeSimulation(e,"wave",Transform.IDENTITY,31,width);
            wave.advance(.45,Transform.IDENTITY);wave.advance(1.5,new Transform(new Vec3(0,0,12),Basis.IDENTITY));
            check(wave.isEmitting(),"looping wave still emits before stop");
            check(wave.snapshot().stream().anyMatch(p->p.layer().render().type().equals("mesh")),"wave body alive during travel");
            wave.stopEmission(true);check(wave.snapshot().stream().allMatch(p->p.layer().worldSpace()),"wave stop clears local body");
            wave.advance(5,wave.transform());check(wave.isFinished(),"wave world tail drains");
            for(String id:List.of("collapse","splash")) {
                var s=new ClaudeSimulation(e,id,new Transform(new Vec3(0,0,12),Basis.IDENTITY),37,width);
                s.advance(.2,s.transform());check(s.particleCount()>0,id+" emits actual particles");
                float[] geometry=ClaudeGeometry.particles(e,s.snapshot(),Vec3.X,Vec3.Y,new Vec3(0,4,20),2_000_000);
                check(geometry.length>0,id+" generates actual geometry");
                for(float v:geometry) check(Float.isFinite(v),id+" finite geometry");
                s.advance(10,s.transform());check(s.isFinished(),id+" naturally drains");
            }
        }
    }
    private static void testRejects(byte[] feather,byte[] tidal) {
        reject(feather,o->o.getAsJsonObject("features").getAsJsonArray("required").remove(new JsonPrimitive("attachedFollow")),"attached must declare capability");
        reject(tidal,o->o.getAsJsonObject("width").addProperty("axis","up"),"wrong width axis");
        reject(tidal,o->o.getAsJsonObject("width").addProperty("reference",0),"zero width reference");
        reject(tidal,o->o.getAsJsonObject("width").addProperty("min",0),"singular width scale");
        reject(tidal,o->o.getAsJsonObject("width").getAsJsonArray("systems").add("missing"),"unknown width system");
        reject(tidal,o->o.getAsJsonObject("width").getAsJsonArray("countScaledLayers").add("splash/missing"),"unknown/out-of-scope density layer");
        reject(tidal,o->firstLayer(o).getAsJsonObject("shape").addProperty("depth",-1),"negative box depth");
        reject(tidal,o->firstLayer(o).getAsJsonObject("flipbook").addProperty("cycles",1.5),"fractional cycles");
        reject(tidal,o->firstLayer(o).getAsJsonObject("flipbook").addProperty("cycles",0),"zero cycles");
        reject(tidal,o->o.getAsJsonObject("features").getAsJsonArray("required").remove(new JsonPrimitive("widthScale")),"width capability required");
        reject(tidal,o->firstLayer(o).getAsJsonObject("flipbook").addProperty("cycles",2),"cycles must declare capability per layer");
        reject(tidal,o->firstLayer(o).getAsJsonObject("onStop").addProperty("mode","jumpToTail"),"active unsupported stop mode");
        reject(tidal,o->firstLayer(o).add("animation",new JsonObject()),"active unsupported animation");
        reject(tidal,o->firstLayer(o).add("path",new JsonObject()),"active unsupported path");
        reject(tidal,o->o.add("sequence",new JsonObject()),"active unsupported sequence");
        reject(tidal,o->o.add("salvo",new JsonObject()),"active unsupported salvo");
        reject(tidal,o->o.add("scale",new JsonObject()),"active unsupported uniform scale");
        reject(tidal,o->o.getAsJsonObject("models").add("body",new JsonObject()),"active unsupported model");
        reject(tidal,o->o.getAsJsonObject("materials").entrySet().iterator().next().getValue().getAsJsonObject().addProperty("depthWrite",true),"active unsupported depth write");
        reject(tidal,o->o.getAsJsonArray("systems").get(0).getAsJsonObject().addProperty("phase","sustain"),"active unsupported system phase");
        reject(tidal,o->firstLayer(o).addProperty("secretModule",true),"unknown newer field still rejects");
    }
    private static Layer plain(Layer b,String space,Shape shape,Emission emission,Vec3 linear,String velocitySpace,double gravity,double speed,Trail trail) {
        return new Layer("test",b.material(),b.render(),0,space,Vec3.ZERO,emission,shape,new Start(new Range(10,10),new Range(1,1),new Range(speed,speed),zero(),null),new Range3(zero(),zero(),zero()),new Velocity(velocitySpace,new Range3(range(linear.x()),range(linear.y()),range(linear.z())),zero(),zero()),gravity,0,null,new Gradient(List.of(new ColorKey(0,new Vec3(1,1,1))),List.of(new AlphaKey(0,1))),List.of(new CurveKey(0,1,0,0)),null,trail,Set.of());
    }
    private static ClaudeEffect single(ClaudeEffect e,String id,Layer layer,Set<String> selectedCounts) {
        Width width=new Width(e.width().reference(),e.width().min(),e.width().max(),e.width().systems(),selectedCounts);
        SystemDef system=e.system(id);
        return new ClaudeEffect(e.name(),e.schemaVersion(),e.fixedTimeStep(),e.gravity(),e.post(),e.materials(),e.meshes(),List.of(new SystemDef(id,system.role(),system.duration(),system.loop(),List.of(layer))),Set.of(),width);
    }
    private static Range zero(){return range(0);}private static Range range(double n){return new Range(n,n);}
    private static JsonObject firstLayer(JsonObject o){return o.getAsJsonArray("systems").get(0).getAsJsonObject().getAsJsonArray("layers").get(0).getAsJsonObject();}
    private static void reject(byte[] bytes,Consumer<JsonObject> mutation,String why) {var o=JsonParser.parseString(new String(bytes,java.nio.charset.StandardCharsets.UTF_8)).getAsJsonObject();mutation.accept(o);rejectsAction(()->ClaudeEffect.parse(o.toString()),why);}
    private static void rejectsAction(Runnable action,String why) {try{action.run();throw new AssertionError(why+" accepted");}catch(IllegalArgumentException expected){check(!expected.getMessage().isBlank(),why);}}
    private static void vector(Vec3 a,Vec3 b,String why){near(a.x(),b.x(),why+" X");near(a.y(),b.y(),why+" Y");near(a.z(),b.z(),why+" Z");}
    private static void near(double a,double b,String why){check(Math.abs(a-b)<1e-8,why+" expected="+b+" actual="+a);}
    private static void nearFloat(double a,double b,String why){check(Math.abs(a-b)<2e-6,why+" expected="+b+" actual="+a);}
    private static void equal(long a,long b,String why){check(a==b,why+" expected="+b+" actual="+a);}
    private static void check(boolean value,String why){assertions++;if(!value)throw new AssertionError(why);}
}
