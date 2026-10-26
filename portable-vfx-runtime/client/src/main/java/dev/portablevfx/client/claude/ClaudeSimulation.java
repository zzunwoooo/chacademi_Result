package dev.portablevfx.client.claude;

import java.util.*;
import static dev.portablevfx.client.claude.ClaudeEffect.*;

/** Deterministic fixed-120-Hz particle interpreter. No renderer, Minecraft or OpenGL dependency.
 * Every authored range is sampled once at birth. Geometry is returned in world metres;
 * colors stay sRGB until the renderer performs the schema's c^2.2 conversion.
 */
public final class ClaudeSimulation {
    private static final double EPS=1e-10;
    // Fail loudly on pathological inputs. Never truncate particle counts or simulation time.
    public static final int MAX_LIVE_PARTICLES=8192;
    public static final int MAX_TRAIL_POINTS_PER_PARTICLE=512;
    public static final int MAX_TRAIL_POINTS=262_144;
    private final ClaudeEffect effect;
    private final SystemDef system;
    private final List<LayerState> layers=new ArrayList<>();
    private final Random random;
    private final double widthScale,instanceScale;
    private double linkLength=Double.NaN,requestedLinkLength=Double.NaN,lastInputLinkLength=Double.NaN;
    private Transform transform;
    private Transform lastInputTransform;
    private double accumulator;
    private double inputSceneTime;
    private long stepCount;
    private long particleSequence;
    private long totalSpawned;
    private int trailPointCount;
    private boolean emitting=true;

    /** Trail points are newest first, excluding the current particle head. age = point age in seconds. */
    public record TrailPoint(Vec3 worldPosition, double age) {}
    public record ParticleView(Layer layer, Vec3 worldPosition, Vec3 worldVelocity, double size,
                               double rotation, Vec3 rotation3D, Basis basis, Vec3 color,
                               double alpha, double normalizedAge, double age, double lifetime,
                               List<TrailPoint> trail, double widthScale) {
        /** Source-compatible unscaled particle constructor. */
        public ParticleView(Layer layer,Vec3 worldPosition,Vec3 worldVelocity,double size,
                            double rotation,Vec3 rotation3D,Basis basis,Vec3 color,
                            double alpha,double normalizedAge,double age,double lifetime,List<TrailPoint> trail) {
            this(layer,worldPosition,worldVelocity,size,rotation,rotation3D,basis,color,alpha,normalizedAge,age,lifetime,trail,1);
        }
        public ParticleView {
            trail=List.copyOf(trail);
            if(!Double.isFinite(widthScale)||widthScale<=0) throw new IllegalArgumentException("Invalid particle width scale");
        }
    }
    private record StoredTrailPoint(Vec3 position,double bornAt) {}
    private record ScheduledBurst(double time,int count) {}
    private static final class LayerState {
        final Layer layer;
        final double countScale;
        final ArrayList<Particle> particles=new ArrayList<>();
        final ArrayList<ScheduledBurst> bursts=new ArrayList<>();
        int burstCursor;
        double rateAccumulator,distanceAccumulator;
        LayerState(Layer layer,double duration,double countScale) {
            this.layer=layer;this.countScale=countScale;
            for(Burst burst:layer.emission().bursts()) {
                for(int cycle=0;cycle<burst.cycles();cycle++) {
                    double time=burst.time()+cycle*burst.interval();
                    if(time>=duration) break;
                    if(bursts.size()>=100_000) throw new IllegalArgumentException("Too many scheduled bursts in layer "+layer.id());
                    long count=countScale==1?burst.count():Math.max(1,Math.round(burst.count()*countScale));
                    if(count>MAX_LIVE_PARTICLES) throw new IllegalArgumentException("Width-scaled burst exceeds particle budget in layer "+layer.id());
                    bursts.add(new ScheduledBurst(time,(int)count));
                }
            }
            // TimSort is stable, preserving file order for equal event times.
            bursts.sort(Comparator.comparingDouble(ScheduledBurst::time));
        }
    }
    private record PathSample(Vec3 origin,double bendX,double bendY,double amplitude,double cycles,double phase) { }
    private static final class Particle {
        final long id;
        final double lifetime,startSize,rotation,orbital,radial;
        final Vec3 startRotation3D,angularVelocity,linear;
        final Basis birthBasis;
        final PathSample path;
        final ArrayDeque<StoredTrailPoint> trail=new ArrayDeque<>();
        Vec3 position,velocity,renderVelocity,previousPosition,previousRenderVelocity;
        double age,previousAge,ageOffset;
        long ageSteps;
        Particle(long id,double lifetime,double startSize,double rotation,double orbital,double radial,
                 Vec3 startRotation3D,Vec3 angularVelocity,Vec3 linear,Basis birthBasis,
                 Vec3 position,Vec3 velocity,PathSample path) {
            this.id=id;this.path=path;this.lifetime=lifetime;this.startSize=startSize;this.rotation=rotation;
            this.orbital=orbital;this.radial=radial;this.startRotation3D=startRotation3D;
            this.angularVelocity=angularVelocity;this.linear=linear;this.birthBasis=birthBasis;
            this.position=position;this.previousPosition=position;
            this.velocity=velocity;this.renderVelocity=velocity;this.previousRenderVelocity=velocity;
        }
    }
    public ClaudeSimulation(ClaudeEffect effect,String systemId,Transform initialTransform,long seed) {
        this(effect,systemId,initialTransform,seed,null);
    }
    /** effectWidth is a once-per-cast metre input; null uses the authored reference width. */
    public ClaudeSimulation(ClaudeEffect effect,String systemId,Transform initialTransform,long seed,Double effectWidth) {
        this(effect,systemId,initialTransform,seed,effectWidth,null);
    }
    /** scaleInputMetres is targetHeight or areaRadius according to the authored scale descriptor. */
    public ClaudeSimulation(ClaudeEffect effect,String systemId,Transform initialTransform,long seed,Double effectWidth,Double scaleInputMetres) {
        this.effect=Objects.requireNonNull(effect);this.system=effect.system(systemId);
        this.transform=Objects.requireNonNull(initialTransform);this.lastInputTransform=initialTransform;
        this.random=new Random(seed);
        if(effectWidth!=null&&(!Double.isFinite(effectWidth)||effectWidth<=0)) throw new IllegalArgumentException("effectWidth must be finite and positive metres");
        this.widthScale=effect.width()==null?1:effect.width().factor(systemId,effectWidth);
        if(scaleInputMetres!=null&&(!Double.isFinite(scaleInputMetres)||scaleInputMetres<=0))throw new IllegalArgumentException("Scale input must be finite and positive metres");
        if(scaleInputMetres!=null&&effect.scale()==null)throw new IllegalArgumentException("Scale input supplied without an authored scale module");
        this.instanceScale=effect.scale()==null?1:effect.scale().factor(scaleInputMetres);
        if(Math.abs(effect.fixedTimeStep()-1.0/120.0)>1e-12) throw new IllegalArgumentException("ClaudeSimulation requires fixedTimeStep=1/120");
        for(Layer layer:system.layers()) {
            boolean scaled=effect.width()!=null&&effect.width().countScaledLayers().contains(systemId+"/"+layer.id());
            layers.add(new LayerState(layer,system.duration(),scaled?widthScale:1));
        }
    }
    public ClaudeEffect effect() { return effect; }
    public SystemDef system() { return system; }
    public Transform transform() { return transform; }
    public double widthScale() { return widthScale; }
    public double instanceScale() { return instanceScale; }
    /** Set current endpoint distance before advance; zero is valid, an absent endpoint is not. */
    public void linkLength(double metres) {
        if(!system.role().equals("link"))throw new IllegalArgumentException("Link length requires a link system");
        if(!Double.isFinite(metres)||metres<0||metres>1_000_000)throw new IllegalArgumentException("Link length must be finite in 0..1000000 metres");
        if(Double.isNaN(lastInputLinkLength)){lastInputLinkLength=metres;linkLength=metres;}
        requestedLinkLength=metres;
    }
    public double linkLength() { return requestedLinkLength; }
    private void requireLinkInput() {
        if(system.role().equals("link")&&Double.isNaN(requestedLinkLength))throw new IllegalStateException("Link system requires a current source/target distance before simulation");
    }
    public double time() { return stepCount*effect.fixedTimeStep(); }
    public double interpolationFraction() { return accumulator/effect.fixedTimeStep(); }
    public long totalSpawned() { return totalSpawned; }
    public int particleCount() { int count=0;for(LayerState l:layers) count+=l.particles.size();return count; }
    public boolean isEmitting() { return emitting && (system.loop()||time()<system.duration()); }
    public boolean isFinished() { return !isEmitting()&&particleCount()==0; }

    /** Advance toward a sampled emitter transform without losing fractional elapsed time.
     * Emission positions are evaluated on fixed-step boundaries, including sub-step input frames.
     * The system basis is orthonormalized during interpolation, so vertical projectiles remain stable.
     */
    public void advance(double elapsedSeconds,Transform target) {
        advance(elapsedSeconds,target,inputSceneTime+elapsedSeconds);
    }
    /** Use the shared scene clock here so independently spawned systems sample identical noise phases. */
    public void advance(double elapsedSeconds,Transform target,double sceneTimeAtEnd) {
        Objects.requireNonNull(target);
        if(!Double.isFinite(elapsedSeconds)||elapsedSeconds<0||!Double.isFinite(sceneTimeAtEnd))
            throw new IllegalArgumentException("Claude elapsed/scene time must be finite and elapsed nonnegative");
        requireLinkInput();
        double h=effect.fixedTimeStep();
        if((elapsedSeconds+accumulator)/h>120_000) throw new IllegalArgumentException("Claude advance exceeds 120,000 fixed steps; time was not discarded");
        Transform start=lastInputTransform;
        double firstOffset=h-accumulator;
        double sceneStart=sceneTimeAtEnd-elapsedSeconds;
        double combined=accumulator+elapsedSeconds;
        long steps=(long)Math.floor((combined+EPS)/h);
        for(long step=0;step<steps;step++) {
            double offset=firstOffset+step*h;
            double fraction=elapsedSeconds>0?Math.max(0,Math.min(1,offset/elapsedSeconds)):1;
            if(system.role().equals("link"))linkLength=lastInputLinkLength+(requestedLinkLength-lastInputLinkLength)*fraction;
            fixedStep(interpolate(start,target,fraction),sceneStart+offset);
        }
        accumulator=combined-steps*h;
        if(accumulator<0&&accumulator>-EPS) accumulator=0;
        lastInputTransform=target;lastInputLinkLength=requestedLinkLength;inputSceneTime=sceneTimeAtEnd;
    }
    /** One exact fixed step, for deterministic fixtures or a caller that owns the 120-Hz clock.
     * Do not mix this method and advance on one instance: it deliberately resets the fractional input clock.
     */
    public void step(Transform next,double sceneTime) {
        if(!Double.isFinite(sceneTime)) throw new IllegalArgumentException("Scene time must be finite");
        requireLinkInput();linkLength=requestedLinkLength;lastInputLinkLength=requestedLinkLength;
        fixedStep(Objects.requireNonNull(next),sceneTime);
        lastInputTransform=next;inputSceneTime=sceneTime;accumulator=0;
    }
    /** Stop new particles. A projectile hit must pass true: local layers clear immediately,
     * while world-space layers finish their existing particles at their existing positions. */
    public void stopEmission(boolean clearLocal) {
        if(!emitting)return;
        emitting=false;
        for(LayerState layer:layers) {
            if(clearLocal&&!layer.layer.worldSpace()) {
                for(Particle p:layer.particles) trailPointCount-=p.trail.size();
                layer.particles.clear();
            } else if(layer.layer.onStop().jumpsToTail()) {
                double tail=layer.layer.onStop().tail();
                for(Iterator<Particle> it=layer.particles.iterator();it.hasNext();) {
                    Particle p=it.next();
                    if(p.lifetime-p.age>tail) {
                        if(tail==0) { trailPointCount-=p.trail.size();it.remove();continue; }
                        double next=p.lifetime-tail;
                        p.ageOffset+=next-p.age;p.age=next;p.previousAge=next;
                    }
                }
            } else {
                // 마법이 끝났는데 수명이 아주 긴 입자(손에 든 조약돌 10초, 대기 화살 20초 등)가 그대로 떠 있지 않게,
                // 남은 수명을 MAX_FINISH_LINGER 초로 줄여 수명 끝의 페이드만 보여 준다 (오너 2026-10-26, 메모 #79).
                for(Particle p:layer.particles) {
                    if(p.lifetime-p.age>MAX_FINISH_LINGER) {
                        double next=p.lifetime-MAX_FINISH_LINGER;
                        p.ageOffset+=next-p.age;p.age=next;p.previousAge=next;
                    }
                }
            }
        }
    }
    /** FINISH 뒤 남은 입자가 더 보일 수 있는 최대 시간(초). 보통 연기·불씨 꼬리는 4초 안이라 영향이 없다. */
    static final double MAX_FINISH_LINGER=4.0;
    public void clear() { emitting=false;for(LayerState layer:layers) layer.particles.clear();trailPointCount=0; }

    private void fixedStep(Transform next,double sceneTime) {
        double h=effect.fixedTimeStep(),t=time(),end=t+h;
        Transform previous=transform;transform=next;
        boolean emit=emitting&&(system.loop()||t<system.duration()-EPS);
        double rateDuration=system.loop()?h:Math.max(0,Math.min(h,system.duration()-t));
        for(LayerState state:layers) {
            Layer layer=state.layer;
            if(emit) {
                // Bursts only occur within the authored duration, even if rate emission loops forever.
                while(state.burstCursor<state.bursts.size()) {
                    ScheduledBurst burst=state.bursts.get(state.burstCursor);
                    if(burst.time()>=end-EPS) break;
                    if(burst.time()>=t-EPS) spawnMany(state,burst.count(),next);
                    state.burstCursor++;
                }
                state.rateAccumulator+=layer.emission().rateOverTime()*state.countScale*rateDuration;
                int count=takeCount(state.rateAccumulator,layer.id());
                state.rateAccumulator-=count;spawnMany(state,count,next);
                state.distanceAccumulator+=layer.emission().rateOverDistance()/instanceScale*state.countScale*next.position().subtract(previous.position()).length();
                int distanceCount=takeCount(state.distanceAccumulator,layer.id());state.distanceAccumulator-=distanceCount;
                for(int i=1;i<=distanceCount;i++) {
                    Vec3 p=previous.position().lerp(next.position(),(double)i/(distanceCount+1));
                    spawnMany(state,1,new Transform(p,next.basis()));
                }
            }
            for(Iterator<Particle> it=state.particles.iterator();it.hasNext();) {
                Particle particle=it.next();
                particle.previousAge=particle.age;
                particle.age=++particle.ageSteps*h+particle.ageOffset;
                if(particle.age>=particle.lifetime) { trailPointCount-=particle.trail.size();it.remove();continue; }
                Vec3 prev=particle.position;
                particle.previousPosition=prev;particle.previousRenderVelocity=particle.renderVelocity;
                if(particle.path!=null) {
                    particle.position=pathPosition(particle.path,layer.path(),particle.age/particle.lifetime,linkLength);
                } else {
                Vec3 gravity=new Vec3(0,-effect.gravity()*layer.gravityModifier()*instanceScale,0);
                if(!layer.worldSpace()) gravity=next.basis().inverse(gravity,widthScale);
                particle.velocity=particle.velocity.add(gravity.multiply(h)).multiply(Math.max(0,1-layer.drag()*h));
                particle.position=particle.position.add(particle.velocity.multiply(h));
                Vec3 linear=particle.linear;
                if(layer.worldSpace()&&!layer.velocityOverLifetime().space().equals("world")) linear=next.basis().apply(linear,widthScale);
                else if(!layer.worldSpace()&&layer.velocityOverLifetime().space().equals("world")) linear=next.basis().inverse(linear,widthScale);
                particle.position=particle.position.add(linear.multiply(h));
                if(!layer.worldSpace()) particle.position=particle.position.rotateY(particle.orbital*h);
                // Schema 4.2 defines this using p in the particle's own stored frame.
                double length=particle.position.length();
                if(length>1e-4) particle.position=particle.position.add(particle.position.multiply(particle.radial*h/length));
                Noise noise=layer.noise();
                if(noise!=null) particle.position=particle.position.add(noise3(particle.position,sceneTime,noise.frequency()/instanceScale,noise.scrollSpeed()).multiply(noise.strength()*instanceScale*h));
                }
                particle.renderVelocity=particle.position.subtract(prev).multiply(1/h);
                if(layer.trail()!=null) updateTrail(particle,layer.trail());
            }
        }
        stepCount++;
    }
    private static int takeCount(double accumulator,String layer) {
        if(!Double.isFinite(accumulator)||accumulator>MAX_LIVE_PARTICLES)
            throw new IllegalStateException("Claude particle emission budget exceeded in layer "+layer+"; no silent count reduction");
        return (int)Math.floor(accumulator+EPS);
    }
    private void spawnMany(LayerState state,int count,Transform spawnTransform) {
        if(count<=0) return;
        if((long)particleCount()+count>MAX_LIVE_PARTICLES)
            throw new IllegalStateException("Claude live particle budget exceeded in layer "+state.layer.id()+"; no silent particle dropping");
        for(int i=0;i<count;i++) state.particles.add(spawn(state.layer,spawnTransform));
    }
    private Particle spawn(Layer layer,Transform spawnTransform) {
        Shape shape=layer.shape();Vec3 point=Vec3.ZERO,direction=Vec3.Y;
        switch(shape.type()) {
            case "sphere" -> {
                do { point=new Vec3(random.nextDouble()*2-1,random.nextDouble()*2-1,random.nextDouble()*2-1); }
                while(point.lengthSquared()>1||point.lengthSquared()<1e-16);
                direction=point.normalized();point=direction.multiply(shape.radius()*(1-shape.radiusThickness()*random.nextDouble()));
            }
            case "circle" -> {
                double angle=random.nextDouble()*Math.PI*2;
                direction=new Vec3(Math.cos(angle),0,Math.sin(angle));
                point=direction.multiply(shape.radius()*(1-shape.radiusThickness()*random.nextDouble()));
            }
            case "box" -> point=new Vec3(shape.width()==0?0:(random.nextDouble()-0.5)*shape.width(),
                (random.nextDouble()-0.5)*shape.height(),shape.depth()==0?0:(random.nextDouble()-0.5)*shape.depth());
            case "none" -> { }
            default -> throw new IllegalArgumentException("Unsupported Claude shape "+shape.type());
        }
        point=point.add(shape.offset()).multiply(instanceScale);
        Start start=layer.start();
        double lifetime=start.lifetime().sample(random),size=start.size().sample(random)*instanceScale,speed=start.speed().sample(random)*instanceScale,rotation=start.rotation().sample(random);
        Vec3 rotation3D=start.rotation3D()==null?Vec3.ZERO:start.rotation3D().sample(random);
        Vec3 angular=layer.rotationOverLifetime().sample(random),linear=layer.velocityOverLifetime().linear().sample(random).multiply(instanceScale);
        double orbital=layer.velocityOverLifetime().orbitalY().sample(random),radial=layer.velocityOverLifetime().radial().sample(random)*instanceScale;
        Vec3 velocity=direction.multiply(speed);
        PathSample path=null;
        if(layer.path()!=null) {
            var authored=layer.path();path=new PathSample(point,authored.bendX().sample(random)*instanceScale,authored.bendY().sample(random)*instanceScale,
                authored.waveAmplitude().sample(random)*instanceScale,authored.waveCycles().sample(random),authored.wavePhase().sample(random));
            point=pathPosition(path,authored,0,linkLength);
        }
        if(layer.worldSpace()) {
            point=spawnTransform.apply(layer.position().multiply(instanceScale).add(point),widthScale);
            velocity=spawnTransform.basis().apply(velocity,widthScale);
        }
        Particle particle=new Particle(++particleSequence,lifetime,size,rotation,orbital,radial,rotation3D,angular,linear,spawnTransform.basis(),point,velocity,path);
        if(layer.trail()!=null) {
            if(trailPointCount>=MAX_TRAIL_POINTS) throw new IllegalStateException("Claude total trail point budget exceeded");
            particle.trail.addFirst(new StoredTrailPoint(point,0));trailPointCount++;
        }
        totalSpawned++;return particle;
    }
    private void updateTrail(Particle particle,Trail trail) {
        double maximumAge=trail.lifetimeRatio()*particle.lifetime;
        double motionAge=particle.ageSteps*effect.fixedTimeStep();
        while(!particle.trail.isEmpty()&&motionAge-particle.trail.getLast().bornAt()>maximumAge) { particle.trail.removeLast();trailPointCount--; }
        if(particle.trail.isEmpty()||particle.position.subtract(particle.trail.getFirst().position()).length()>trail.minVertexDistance()*instanceScale) {
            if(particle.trail.size()>=MAX_TRAIL_POINTS_PER_PARTICLE||trailPointCount>=MAX_TRAIL_POINTS)
                throw new IllegalStateException("Claude trail point budget exceeded; no silent trail truncation");
            particle.trail.addFirst(new StoredTrailPoint(particle.position,motionAge));trailPointCount++;
        }
    }
    /** Exact fixed-step state, for deterministic consumers. File/layer order is preserved. */
    public List<ParticleView> snapshot() {
        return snapshot(false);
    }
    /** Render between the last two completed steps without changing simulation or emission.
     * Particle motion/curves lag the input clock by one fixed step (8.33 ms), rather than
     * extrapolating gravity, orbit or noise. Local geometry uses the latest input pose,
     * so an attached emitter follows every rendered entity frame without that extra lag.
     * World particles retain their birth frame and never inherit this attachment correction.
     */
    public List<ParticleView> renderSnapshot() {
        return snapshot(true);
    }
    private List<ParticleView> snapshot(boolean interpolated) {
        ArrayList<ParticleView> result=new ArrayList<>();
        double fraction=interpolated?Math.max(0,Math.min(1,interpolationFraction())):1;
        Transform pose=interpolated?lastInputTransform:transform;
        for(LayerState state:layers) {
            Layer layer=state.layer;
            for(Particle particle:state.particles) {
                double age=interpolated?particle.previousAge+(particle.age-particle.previousAge)*fraction:particle.age;
                double motionAge=particle.ageSteps*effect.fixedTimeStep();
                if(interpolated)motionAge=Math.max(0,motionAge-effect.fixedTimeStep()+fraction*effect.fixedTimeStep());
                double normalized=Math.max(0,Math.min(1,age/particle.lifetime));
                Vec3 position=interpolated?particle.previousPosition.lerp(particle.position,fraction):particle.position;
                if(interpolated&&particle.path!=null)position=pathPosition(particle.path,layer.path(),normalized,requestedLinkLength);
                Vec3 velocity=interpolated?particle.previousRenderVelocity.lerp(particle.renderVelocity,fraction):particle.renderVelocity;
                Vec3 p=worldPosition(layer,position,pose),v=layer.worldSpace()?velocity:pose.basis().apply(velocity,widthScale);
                ArrayList<TrailPoint> trail=new ArrayList<>();
                for(StoredTrailPoint point:particle.trail) {
                    // The newest fixed-step trail sample can lie ahead of the interpolated head.
                    if((interpolated && point.bornAt()>motionAge+EPS) || point.position().subtract(position).lengthSquared()<1e-20) continue;
                    trail.add(new TrailPoint(worldPosition(layer,point.position(),pose),interpolated?Math.max(0,motionAge-point.bornAt()):motionAge-point.bornAt()));
                }
                result.add(new ParticleView(layer,p,v,particle.startSize*layer.sizeAt(normalized),
                    particle.rotation+particle.angularVelocity.z()*age,
                    particle.startRotation3D.add(particle.angularVelocity.multiply(age)),
                    layer.worldSpace()?particle.birthBasis:pose.basis(),
                    layer.colorOverLifetime().colorAt(normalized),layer.colorOverLifetime().alphaAt(normalized),
                    normalized,age,particle.lifetime,trail,widthScale));
            }
        }
        return List.copyOf(result);
    }
    private Vec3 worldPosition(Layer layer,Vec3 p,Transform pose) { return layer.worldSpace()?p:pose.apply(layer.position().multiply(instanceScale).add(p),widthScale); }
    /** Evaluates authored progress without clamping its value: reverse/overshoot paths are valid. */
    private static Vec3 pathPosition(PathSample p,PathFollow path,double normalizedAge,double length) {
        double progress=path.progressAt(Math.max(0,Math.min(1,normalizedAge))),envelope=Math.sin(Math.PI*progress);
        double angle=2*Math.PI*p.cycles()*progress+p.phase(),remainder=1-progress;
        return new Vec3(remainder*p.origin().x()+envelope*(p.bendX()+p.amplitude()*Math.sin(angle)),
            remainder*p.origin().y()+envelope*(p.bendY()+p.amplitude()*path.verticalRatio()*Math.cos(angle)),
            remainder*p.origin().z()+progress*length);
    }
    public static Vec3 noise3(Vec3 p,double time,double frequency,double scrollSpeed) {
        double x=p.x()*frequency+time*scrollSpeed,y=p.y()*frequency+time*scrollSpeed*0.7,z=p.z()*frequency-time*scrollSpeed*0.5;
        return new Vec3(0.6*(Math.sin(1.7*y+0.9*z)+0.5*Math.sin(2.3*z+1.3)),
            0.6*(Math.sin(1.3*z+1.1*x+2.1)+0.5*Math.sin(2.9*x)),
            0.6*(Math.sin(1.9*x+0.7*y+4.2)+0.5*Math.sin(2.1*y+0.4)));
    }
    private static Transform interpolate(Transform a,Transform b,double t) {
        if(t<=0)return a;if(t>=1)return b;
        Vec3 f=a.basis().forward().lerp(b.basis().forward(),t);
        if(f.lengthSquared()<1e-12) f=b.basis().forward();f=f.normalized();
        Vec3 up=a.basis().up().lerp(b.basis().up(),t),r=up.cross(f);
        if(r.lengthSquared()<1e-12) { Vec3 ref=Math.abs(f.y())<0.9?Vec3.Y:Vec3.Z;r=ref.cross(f); }
        r=r.normalized();return new Transform(a.position().lerp(b.position(),t),new Basis(r,f.cross(r).normalized(),f));
    }
}
