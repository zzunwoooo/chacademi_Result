package dev.portablevfx.client.claude;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import static dev.portablevfx.client.claude.ClaudeEffect.*;
import static org.junit.jupiter.api.Assertions.*;

/** Render sampling must never advance the fixed 120 Hz interpreter or change authored counts. */
class ClaudeSimulationInterpolationTest {
    private static final double H=1.0/120;

    @Test void substepFollowUsesLatestPositionAndBasisWithoutAdvancingSimulation() throws Exception {
        var sim=new ClaudeSimulation(single("local",true),"test",Transform.IDENTITY,7);
        sim.advance(3*H,Transform.IDENTITY);
        var exact=sim.snapshot();long spawned=sim.totalSpawned();double time=sim.time();
        Transform pose=Transform.attached(new Vec3(4,-2,7),Vec3.X);
        sim.advance(H*.25,pose);
        var rendered=sim.renderSnapshot().getFirst();
        double age=2.25*H;
        vector(rendered.worldPosition(),pose.apply(Vec3.X.add(Vec3.Y.multiply(2*age))));
        vector(rendered.worldVelocity(),pose.basis().apply(Vec3.Y.multiply(2)));
        assertEquals(pose.basis(),rendered.basis());
        assertEquals(age,rendered.age(),1e-10);
        assertEquals(4*age,rendered.rotation(),1e-10);
        vector(rendered.rotation3D(),new Vec3(2,3,4).multiply(age));
        assertEquals(1+age,rendered.size(),1e-10);
        vector(rendered.color(),new Vec3(.5,.5,.5).multiply(age));
        assertEquals(age/2,rendered.alpha(),1e-10);
        assertEquals(exact,sim.snapshot());assertEquals(spawned,sim.totalSpawned());assertEquals(time,sim.time());
        assertEquals(sim.renderSnapshot(),sim.renderSnapshot());
    }

    @Test void particleMotionIsSmoothAtNonDivisorAndHighRefreshRates() throws Exception {
        for(String space:List.of("local","world")) for(int hz:new int[]{50,60,75,144,240}) {
            var sim=new ClaudeSimulation(single(space,false),"test",Transform.IDENTITY,7);
            sim.advance(.2,Transform.IDENTITY);
            double previousY=sim.renderSnapshot().getFirst().worldPosition().y();
            double previousAge=sim.renderSnapshot().getFirst().age();
            for(int frame=1;frame<=hz/2;frame++) {
                double elapsed=1.0/hz;
                sim.advance(elapsed,new Transform(new Vec3(frame*elapsed*5,0,0),Basis.IDENTITY));
                var particle=sim.renderSnapshot().getFirst();
                assertEquals(2*elapsed,particle.worldPosition().y()-previousY,1e-9,"uniform rendered displacement at "+hz+" Hz");
                assertEquals(elapsed,particle.age()-previousAge,1e-9,"uniform rendered curve time at "+hz+" Hz");
                assertEquals(space.equals("local")?1+frame*elapsed*5:1,particle.worldPosition().x(),1e-9);
                previousY=particle.worldPosition().y();previousAge=particle.age();
            }
        }
    }

    @Test void worldParticlesAndTheirTrailsIgnoreSubstepEmitterMotion() throws Exception {
        var sim=new ClaudeSimulation(single("world",true),"test",Transform.IDENTITY,7);
        sim.advance(4.5*H,Transform.IDENTITY);
        var before=sim.renderSnapshot();var exact=sim.snapshot();
        sim.advance(0,Transform.attached(new Vec3(30,-10,20),Vec3.X));
        assertEquals(before,sim.renderSnapshot());assertEquals(exact,sim.snapshot());
        assertFalse(before.getFirst().trail().isEmpty());
    }

    @Test void localTrailsFollowTheLatestPoseAndNeverLeadTheInterpolatedHead() throws Exception {
        var sim=new ClaudeSimulation(single("local",true),"test",Transform.IDENTITY,7);
        sim.advance(4.5*H,Transform.IDENTITY);
        var before=sim.renderSnapshot().getFirst();
        Transform pose=Transform.attached(new Vec3(2,3,4),Vec3.X);
        sim.advance(0,pose);
        var after=sim.renderSnapshot().getFirst();
        vector(after.worldPosition(),pose.apply(before.worldPosition()));
        assertFalse(after.trail().isEmpty());assertEquals(before.trail().size(),after.trail().size());
        for(int i=0;i<after.trail().size();i++) {
            vector(after.trail().get(i).worldPosition(),pose.apply(before.trail().get(i).worldPosition()));
            assertTrue(after.trail().get(i).age()>=0);
            assertTrue(before.trail().get(i).worldPosition().y()<=before.worldPosition().y());
        }
    }

    @Test void renderSamplingPreservesDeterminismAndAuthoredEmission() throws Exception {
        ClaudeEffect effect=source();
        var sampled=new ClaudeSimulation(effect,"aura",Transform.IDENTITY,41);
        var control=new ClaudeSimulation(effect,"aura",Transform.IDENTITY,41);
        for(int frame=1;frame<=240;frame++) {
            Transform pose=Transform.attached(new Vec3(frame*.015,-frame*.002,0),new Vec3(Math.sin(frame*.01),0,Math.cos(frame*.01)));
            sampled.advance(1.0/240,pose);control.advance(1.0/240,pose);
            sampled.renderSnapshot();sampled.renderSnapshot();
        }
        assertEquals(control.snapshot(),sampled.snapshot());
        assertEquals(control.totalSpawned(),sampled.totalSpawned());assertEquals(control.time(),sampled.time());
        sampled.stopEmission(true);
        assertTrue(sampled.renderSnapshot().stream().allMatch(p->p.layer().worldSpace()));
        sampled.advance(10,sampled.transform());assertTrue(sampled.isFinished());
        assertTrue(sampled.renderSnapshot().isEmpty());
    }

    private static ClaudeEffect source() throws Exception {
        return ClaudeEffect.parse(Files.readAllBytes(Path.of(ClaudeSimulationInterpolationTest.class.getResource("/claude/samples/FeatherFall/vfx.json").toURI())));
    }
    private static ClaudeEffect single(String space,boolean withTrail) throws Exception {
        ClaudeEffect source=source();Layer base=source.system("aura").layers().getFirst();
        Range zero=new Range(0,0);Range3 zero3=new Range3(zero,zero,zero);
        Trail trail=withTrail?new Trail(base.material(),1,.0001,List.of(new CurveKey(0,1,0,0)),List.of(new ColorKey(0,Vec3.X))):null;
        Layer layer=new Layer("linear",base.material(),base.render(),0,space,Vec3.ZERO,
            new Emission(0,0,List.of(new Burst(0,1,1,0))),new Shape("none",0,1,Vec3.X,0),
            new Start(new Range(2,2),new Range(1,1),new Range(2,2),zero,null),
            new Range3(new Range(2,2),new Range(3,3),new Range(4,4)),new Velocity("local",zero3,zero,zero),0,0,null,
            new Gradient(List.of(new ColorKey(0,Vec3.ZERO),new ColorKey(1,new Vec3(1,1,1))),List.of(new AlphaKey(0,0),new AlphaKey(1,1))),
            List.of(new CurveKey(0,1,2,2),new CurveKey(1,3,2,2)),null,trail,Set.of());
        return new ClaudeEffect("interpolation","1.1.0",H,source.gravity(),source.post(),source.materials(),source.meshes(),
            List.of(new SystemDef("test","attached",1,false,List.of(layer))),Set.of());
    }
    private static void vector(Vec3 actual,Vec3 expected) {
        assertEquals(expected.x(),actual.x(),1e-9);assertEquals(expected.y(),actual.y(),1e-9);assertEquals(expected.z(),actual.z(),1e-9);
    }
}
