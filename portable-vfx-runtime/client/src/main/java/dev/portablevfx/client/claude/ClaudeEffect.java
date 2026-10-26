package dev.portablevfx.client.claude;

import com.google.gson.*;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Strict, Minecraft-independent model for Claude schema 1.0/1.1 and the supported 1.12 modules.
 * Newer minor versions are accepted only if every field and required capability is understood.
 * This is deliberately not a Gson POJO binding: unknown modules must fail, not disappear.
 */
public record ClaudeEffect(String name, String schemaVersion, double fixedTimeStep, double gravity,
                           Post post, Map<String, Material> materials, Map<String, Mesh> meshes,
                           List<SystemDef> systems, Set<String> requiredFeatures, Width width, Scale scale, Map<String,Model> models) {
    public static final Set<String> SUPPORTED_FEATURES = Set.of("billboard", "stretchedBillboard",
        "meshParticle", "flipbook", "additiveBlend", "alphaBlend", "hdrTint", "bloom",
        "tonemapNeutral", "worldSpace", "rateOverDistance", "rotation3D", "rotationOverLifetime",
        "orbitalVelocity", "radialVelocity", "linearVelocity", "gravity", "drag", "noise", "trails",
        "attachedFollow", "widthScale", "flipbookCycles", "phasedSequence", "salvoSequence", "triggeredEnd", "depthWrite", "stopTail", "pathFollow", "linkTarget", "instanceScale", "modelParticle");
    public ClaudeEffect {
        models = Collections.unmodifiableMap(new LinkedHashMap<>(models));
        materials = Collections.unmodifiableMap(new LinkedHashMap<>(materials));
        meshes = Collections.unmodifiableMap(new LinkedHashMap<>(meshes));
        systems = List.copyOf(systems);
        requiredFeatures = Collections.unmodifiableSet(new LinkedHashSet<>(requiredFeatures));
    }
    /** Source-compatible constructor before external model resources. */
    public ClaudeEffect(String name,String schemaVersion,double fixedTimeStep,double gravity,
                        Post post,Map<String,Material> materials,Map<String,Mesh> meshes,
                        List<SystemDef> systems,Set<String> requiredFeatures,Width width,Scale scale) {
        this(name,schemaVersion,fixedTimeStep,gravity,post,materials,meshes,systems,requiredFeatures,width,scale,Map.of());
    }
    /** Source-compatible width-aware constructor. */
    public ClaudeEffect(String name,String schemaVersion,double fixedTimeStep,double gravity,
                        Post post,Map<String,Material> materials,Map<String,Mesh> meshes,
                        List<SystemDef> systems,Set<String> requiredFeatures,Width width) {
        this(name,schemaVersion,fixedTimeStep,gravity,post,materials,meshes,systems,requiredFeatures,width,null);
    }
    /** Source-compatible schema 1.0 constructor. */
    public ClaudeEffect(String name, String schemaVersion, double fixedTimeStep, double gravity,
                        Post post, Map<String, Material> materials, Map<String, Mesh> meshes,
                        List<SystemDef> systems, Set<String> requiredFeatures) {
        this(name,schemaVersion,fixedTimeStep,gravity,post,materials,meshes,systems,requiredFeatures,null);
    }
    /** Raw input is metres (target height or area radius), resolved once at cast start. */
    public record Scale(String input,double reference,double min,double max) {
        public Scale {
            if((!"targetHeight".equals(input)&&!"areaRadius".equals(input))||!Double.isFinite(reference)||reference<=0
                    ||!Double.isFinite(min)||!Double.isFinite(max)||min<=0||min>max)
                throw new IllegalArgumentException("Invalid Claude instance scale");
        }
        public double factor(Double inputMetres) {
            if(inputMetres!=null&&(!Double.isFinite(inputMetres)||inputMetres<=0))
                throw new IllegalArgumentException("Scale input must be finite and positive metres");
            return Math.max(min,Math.min(max,(inputMetres==null?reference:inputMetres)/reference));
        }
    }
    /** Diagnostics for authored fields whose applicability is explicitly restricted by the schema. */
    public List<String> warnings() {
        var result=new ArrayList<String>();
        for(SystemDef system:systems)for(Layer layer:system.layers())
            if(layer.worldSpace()&&layer.velocityOverLifetime().orbitalY().nonzero())
                result.add(system.id()+"/"+layer.id()+": nonzero world-space orbitalY is nonapplicable; schema step 7 rotates local-space particles only");
        return List.copyOf(result);
    }
    /** Width is fixed per cast. It affects only the named systems and density-selected layers. */
    public record Width(double reference, double min, double max, Set<String> systems, Set<String> countScaledLayers) {
        public Width {
            if(!Double.isFinite(reference)||reference<=0||!Double.isFinite(min)||!Double.isFinite(max)||min<=0||min>max)
                throw new IllegalArgumentException("Invalid Claude width reference or scale bounds");
            systems=Set.copyOf(systems);countScaledLayers=Set.copyOf(countScaledLayers);
        }
        public double factor(Double effectWidth) {
            if(effectWidth!=null&&(!Double.isFinite(effectWidth)||effectWidth<=0))
                throw new IllegalArgumentException("effectWidth must be finite and positive metres");
            return Math.max(min,Math.min(max,(effectWidth==null?reference:effectWidth)/reference));
        }
        public double factor(String systemId,Double effectWidth) {
            double scale=factor(effectWidth);return systems.contains(systemId)?scale:1;
        }
    }
    public SystemDef system(String id) {
        return systems.stream().filter(s -> s.id().equals(id)).findFirst()
            .orElseThrow(() -> new IllegalArgumentException("No Claude system: " + id));
    }
    public static ClaudeEffect parse(byte[] utf8) { return parse(new String(utf8, StandardCharsets.UTF_8)); }
    public static ClaudeEffect parse(String json) {
        try { return new Parser().parse(JsonParser.parseString(json)); }
        catch (ValidationException e) { throw e; }
        catch (RuntimeException e) { throw new ValidationException("Invalid Claude vfx.json: " + e.getMessage(), e); }
    }
    public static ClaudeEffect parse(Reader reader) {
        try { return new Parser().parse(JsonParser.parseReader(reader)); }
        catch (ValidationException e) { throw e; }
        catch (RuntimeException e) { throw new ValidationException("Invalid Claude vfx.json: " + e.getMessage(), e); }
    }
    public static final class ValidationException extends IllegalArgumentException {
        public ValidationException(String message) { super(message); }
        public ValidationException(String message, Throwable cause) { super(message, cause); }
    }
    public record Vec3(double x, double y, double z) {
        public static final Vec3 ZERO = new Vec3(0, 0, 0), X = new Vec3(1, 0, 0), Y = new Vec3(0, 1, 0), Z = new Vec3(0, 0, 1);
        public Vec3 add(Vec3 b) { return new Vec3(x+b.x,y+b.y,z+b.z); }
        public Vec3 subtract(Vec3 b) { return new Vec3(x-b.x,y-b.y,z-b.z); }
        public Vec3 multiply(double s) { return new Vec3(x*s,y*s,z*s); }
        public Vec3 multiply(Vec3 b) { return new Vec3(x*b.x,y*b.y,z*b.z); }
        public double dot(Vec3 b) { return x*b.x+y*b.y+z*b.z; }
        public Vec3 cross(Vec3 b) { return new Vec3(y*b.z-z*b.y,z*b.x-x*b.z,x*b.y-y*b.x); }
        public double lengthSquared() { return dot(this); }
        public double length() { return Math.sqrt(lengthSquared()); }
        public Vec3 normalized() { double l=length(); return l > 1e-12 ? multiply(1/l) : ZERO; }
        public Vec3 lerp(Vec3 b, double t) { return add(b.subtract(this).multiply(t)); }
        public Vec3 rotateY(double a) { double c=Math.cos(a),s=Math.sin(a); return new Vec3(x*c+z*s,y,-x*s+z*c); }
        /** Exactly Ry(y) Rx(x) Rz(z), with the signs specified in SCHEMA.md. */
        public Vec3 rotateEuler(Vec3 a) {
            double cz=Math.cos(a.z),sz=Math.sin(a.z),cx=Math.cos(a.x),sx=Math.sin(a.x);
            double xx=x*cz-y*sz, yy=x*sz+y*cz;
            return new Vec3(xx,yy*cx-z*sx,yy*sx+z*cx).rotateY(a.y);
        }
        public Vec3 linear() { return new Vec3(Math.pow(x,2.2),Math.pow(y,2.2),Math.pow(z,2.2)); }
    }
    public record Basis(Vec3 right, Vec3 up, Vec3 forward) {
        public static final Basis IDENTITY = new Basis(Vec3.X,Vec3.Y,Vec3.Z);
        public Basis {
            if (!finite(right) || !finite(up) || !finite(forward)
                    || Math.abs(right.lengthSquared()-1)>1e-5 || Math.abs(up.lengthSquared()-1)>1e-5
                    || Math.abs(forward.lengthSquared()-1)>1e-5 || Math.abs(right.dot(up))>1e-5
                    || Math.abs(right.dot(forward))>1e-5 || Math.abs(up.dot(forward))>1e-5
                    || right.cross(up).dot(forward)<0.99999)
                throw new IllegalArgumentException("Claude basis must be finite, orthonormal and right/up/forward ordered");
        }
        public Vec3 apply(Vec3 v) { return right.multiply(v.x).add(up.multiply(v.y)).add(forward.multiply(v.z)); }
        public Vec3 inverse(Vec3 v) { return new Vec3(right.dot(v),up.dot(v),forward.dot(v)); }
        /** M diag(sx,1,1), with the non-uniform scale kept out of the orientation basis. */
        public Vec3 apply(Vec3 v,double sx) {
            positiveWidth(sx);return apply(new Vec3(v.x*sx,v.y,v.z));
        }
        /** True inverse diag(1/sx,1,1) M^T; transpose alone is incorrect. */
        public Vec3 inverse(Vec3 v,double sx) {
            positiveWidth(sx);Vec3 local=inverse(v);return new Vec3(local.x/sx,local.y,local.z);
        }
        public static Basis attached(Vec3 facing) {
            Vec3 f=finite(facing)?new Vec3(facing.x,0,facing.z):Vec3.Z;
            f=unitOr(f,Vec3.Z);return new Basis(Vec3.Y.cross(f),Vec3.Y,f);
        }
        /** Schema link fallback: world +Z below 1 mm, world +X for a vertical link. */
        public static Basis link(Vec3 direction) {
            Vec3 f=finite(direction)&&direction.length()>=1e-3?direction.normalized():Vec3.Z;
            Vec3 r=Vec3.Y.cross(f);r=r.lengthSquared()<1e-12?Vec3.X:r.normalized();
            return new Basis(r,f.cross(r).normalized(),f);
        }
        public static Basis projectile(Vec3 direction) {
            Vec3 f=unitOr(direction,Vec3.Z), reference=Math.abs(f.y)<0.999 ? Vec3.Y : Vec3.Z;
            Vec3 r=reference.cross(f).normalized(); return new Basis(r,f.cross(r).normalized(),f);
        }
        public static Basis impact(Vec3 normal, Vec3 incoming) {
            Vec3 direction=unitOr(incoming,Vec3.Z), u=unitOr(normal==null ? direction.multiply(-1) : normal,Vec3.Y);
            Vec3 f=direction.subtract(u.multiply(direction.dot(u)));
            if(f.lengthSquared()<1e-12) { Vec3 a=Math.abs(u.y)<0.9 ? Vec3.Y : Vec3.Z; f=a.subtract(u.multiply(a.dot(u))); }
            f=f.normalized(); return new Basis(u.cross(f).normalized(),u,f);
        }
    }
    public record Transform(Vec3 position, Basis basis) {
        public static final Transform IDENTITY = new Transform(Vec3.ZERO,Basis.IDENTITY);
        public Transform { if (!finite(position) || basis==null) throw new IllegalArgumentException("Invalid Claude transform"); }
        public Vec3 apply(Vec3 p) { return position.add(basis.apply(p)); }
        public Vec3 apply(Vec3 p,double sx) { return position.add(basis.apply(p,sx)); }
        public static Transform attached(Vec3 anchor,Vec3 facing) { return new Transform(anchor,Basis.attached(facing)); }
        public static Transform projectile(Vec3 p, Vec3 direction) { return new Transform(p,Basis.projectile(direction)); }
        public static Transform link(Vec3 source,Vec3 target) {
            if(!finite(source)||!finite(target))throw new IllegalArgumentException("Link endpoints must be finite");
            return new Transform(source,Basis.link(target.subtract(source)));
        }
        public static Transform impact(Vec3 p, Vec3 normal, Vec3 incoming) { return new Transform(p,Basis.impact(normal,incoming)); }
    }
    public record Range(double min, double max) {
        public double sample(Random random) { return min+(max-min)*random.nextDouble(); }
        public boolean nonzero() { return min!=0 || max!=0; }
        public double minimum() { return Math.min(min,max); }
        public double maximum() { return Math.max(min,max); }
    }
    public record Range3(Range x, Range y, Range z) {
        public Vec3 sample(Random random) { return new Vec3(x.sample(random),y.sample(random),z.sample(random)); }
        public boolean nonzero() { return x.nonzero() || y.nonzero() || z.nonzero(); }
    }
    public record Material(String texture, String blend, Vec3 tint, boolean depthWrite) {
        public Material(String texture,String blend,Vec3 tint) { this(texture,blend,tint,false); }
    }
    public record Uv(double u, double v) {}
    public record Mesh(List<Vec3> vertices, List<Uv> uvs, List<Integer> triangles, boolean doubleSided) {
        public Mesh { vertices=List.copyOf(vertices); uvs=List.copyOf(uvs); triangles=List.copyOf(triangles); }
    }
    public record Bloom(double threshold, String thresholdSpace, double softKnee, double intensity, double scatter, Vec3 tint) {
        public double linearThreshold() { return Math.pow(threshold,2.2); }
        public double scatterScale() { return 0.05+0.9*scatter; }
    }
    public record Post(Bloom bloom, String tonemapping) {}
    public record SystemDef(String id, String role, double duration, boolean loop, List<Layer> layers) {
        public SystemDef { layers=List.copyOf(layers); }
        public double maximumLifetime() { return layers.stream().mapToDouble(l -> l.start.lifetime.maximum()).max().orElse(0); }
    }
    public record Model(String file,double height,Map<String,ModelClip> clips,Map<String,ModelSocket> sockets,String textures,ModelShading shading) {
        public Model { clips=Collections.unmodifiableMap(new LinkedHashMap<>(clips));sockets=Collections.unmodifiableMap(new LinkedHashMap<>(sockets)); }
    }
    public record ModelClip(double duration,boolean loop) { }
    public record ModelSocket(Vec3 position,String clip,double time) { }
    public record ModelOutline(double width,Vec3 color) { }
    public record ModelShading(String mode,Vec3 lightDirection,double ambient,double diffuse,double wrap,Vec3 shadowTint,
                               double threshold,double softness,Vec3 rimColor,double rimPower,double rimStrength,
                               double translucentFresnelMin,ModelOutline outline) { }
    public record ModelAnimation(String clip,double speed,boolean loop) { }
    public record Render(String type, String mesh, double velocityScale, double lengthScale,String model) {
        public Render(String type,String mesh,double velocityScale,double lengthScale) { this(type,mesh,velocityScale,lengthScale,null); }
    }
    public record Burst(double time, int count, int cycles, double interval) {}
    public record Emission(double rateOverTime, double rateOverDistance, List<Burst> bursts) {
        public Emission { bursts=List.copyOf(bursts); }
    }
    public record Shape(String type, double radius, double radiusThickness, Vec3 offset, double height, double width, double depth) {
        public Shape(String type,double radius,double radiusThickness,Vec3 offset,double height) {
            this(type,radius,radiusThickness,offset,height,0,0);
        }
    }
    public record Start(Range lifetime, Range size, Range speed, Range rotation, Range3 rotation3D) {}
    public record Velocity(String space, Range3 linear, Range orbitalY, Range radial) {}
    public record Noise(double strength, double frequency, double scrollSpeed) {}
    public record ColorKey(double t, Vec3 rgb) {}
    public record AlphaKey(double t, double a) {}
    public record CurveKey(double t, double value, double inTangent, double outTangent) {}
    public record Gradient(List<ColorKey> color, List<AlphaKey> alpha) {
        public Gradient { color=List.copyOf(color); alpha=List.copyOf(alpha); }
        public Vec3 colorAt(double t) { return sampleColor(color,t); }
        public double alphaAt(double t) { return sampleAlpha(alpha,t); }
    }
    public record Flipbook(int columns, int rows, String frameOrder, String playback, int cycles) {
        public Flipbook(int columns,int rows,String frameOrder,String playback) { this(columns,rows,frameOrder,playback,1); }
        public Flipbook { if(columns<1||rows<1||(long)columns*rows>65536||cycles<1) throw new IllegalArgumentException("Invalid flipbook dimensions or cycles"); }
        public int frame(double age) {
            if(!Double.isFinite(age)) throw new IllegalArgumentException("Flipbook age must be finite");
            int frames=columns*rows;
            return (int)(Math.floor(Math.max(0,Math.min(1,age))*0.999*frames*cycles)%frames);
        }
        /** Optional presentation interpolation; the authored discrete frame phase is unchanged.
         * Intermediate cycles blend across the last-to-first boundary. The last authored
         * frame in the final cycle holds instead of fading back to the first frame. */
        public record FrameBlend(int current,int next,double fraction) {}
        public FrameBlend frameBlend(double age) {
            if(!Double.isFinite(age)) throw new IllegalArgumentException("Flipbook age must be finite");
            int frames=columns*rows;
            double phase=Math.max(0,Math.min(1,age))*0.999*frames*cycles;
            long whole=(long)Math.floor(phase);
            int current=(int)(whole%frames);
            if(frames==1||whole>=(long)frames*cycles-1) return new FrameBlend(current,current,0);
            return new FrameBlend(current,(current+1)%frames,phase-whole);
        }
        public Uv uv(double u, double v, double age) { int f=frame(age); return new Uv((f%columns+u)/columns,(f/columns+1-v)/rows); }
    }
    public record Trail(String material, double lifetimeRatio, double minVertexDistance,
                        List<CurveKey> widthOverTrail, List<ColorKey> color) {
        public Trail { widthOverTrail=List.copyOf(widthOverTrail); color=List.copyOf(color); }
        public double widthAt(double t) { return sampleCurve(widthOverTrail,t); }
        public Vec3 colorAt(double t) { return sampleColor(color,t); }
    }
    public record PathFollow(List<CurveKey> progress,Range bendX,Range bendY,Range waveAmplitude,
                             Range waveCycles,Range wavePhase,double verticalRatio) {
        public PathFollow { progress=List.copyOf(progress); }
        public double progressAt(double t) { return sampleCurve(progress,t); }
    }
    public record StopBehavior(String mode,double tail) {
        public static final StopBehavior FINISH=new StopBehavior("finish",0);
        public StopBehavior {
            if((!"finish".equals(mode)&&!"jumpToTail".equals(mode))||!Double.isFinite(tail)||tail<0)
                throw new IllegalArgumentException("Invalid Claude stop behavior");
        }
        public boolean jumpsToTail() { return mode.equals("jumpToTail"); }
    }
    public record Layer(String id, String material, Render render, double sortingFudge, String space,
                        Vec3 position, Emission emission, Shape shape, Start start, Range3 rotationOverLifetime,
                        Velocity velocityOverLifetime, double gravityModifier, double drag, Noise noise,
                        Gradient colorOverLifetime, List<CurveKey> sizeOverLifetime, Flipbook flipbook,
                        Trail trail, Set<String> requires, StopBehavior onStop, PathFollow path, ModelAnimation animation) {
        public Layer { sizeOverLifetime=List.copyOf(sizeOverLifetime); requires=Set.copyOf(requires); Objects.requireNonNull(onStop,"onStop"); }
        /** Source-compatible constructor for layers before model animation support. */
        public Layer(String id,String material,Render render,double sortingFudge,String space,
                     Vec3 position,Emission emission,Shape shape,Start start,Range3 rotationOverLifetime,
                     Velocity velocityOverLifetime,double gravityModifier,double drag,Noise noise,
                     Gradient colorOverLifetime,List<CurveKey> sizeOverLifetime,Flipbook flipbook,
                     Trail trail,Set<String> requires,StopBehavior onStop,PathFollow path) {
            this(id,material,render,sortingFudge,space,position,emission,shape,start,rotationOverLifetime,
                velocityOverLifetime,gravityModifier,drag,noise,colorOverLifetime,sizeOverLifetime,flipbook,trail,requires,onStop,path,null);
        }
        /** Source-compatible constructor for layers before path support. */
        public Layer(String id,String material,Render render,double sortingFudge,String space,
                     Vec3 position,Emission emission,Shape shape,Start start,Range3 rotationOverLifetime,
                     Velocity velocityOverLifetime,double gravityModifier,double drag,Noise noise,
                     Gradient colorOverLifetime,List<CurveKey> sizeOverLifetime,Flipbook flipbook,
                     Trail trail,Set<String> requires,StopBehavior onStop) {
            this(id,material,render,sortingFudge,space,position,emission,shape,start,rotationOverLifetime,
                velocityOverLifetime,gravityModifier,drag,noise,colorOverLifetime,sizeOverLifetime,flipbook,trail,requires,onStop,null);
        }
        /** Source-compatible constructor for schema layers without an authored stop policy. */
        public Layer(String id,String material,Render render,double sortingFudge,String space,
                     Vec3 position,Emission emission,Shape shape,Start start,Range3 rotationOverLifetime,
                     Velocity velocityOverLifetime,double gravityModifier,double drag,Noise noise,
                     Gradient colorOverLifetime,List<CurveKey> sizeOverLifetime,Flipbook flipbook,
                     Trail trail,Set<String> requires) {
            this(id,material,render,sortingFudge,space,position,emission,shape,start,rotationOverLifetime,
                velocityOverLifetime,gravityModifier,drag,noise,colorOverLifetime,sizeOverLifetime,flipbook,trail,requires,StopBehavior.FINISH);
        }
        public double sizeAt(double t) { return sampleCurve(sizeOverLifetime,t); }
        public boolean worldSpace() { return space.equals("world"); }
    }
    public static double sampleCurve(List<CurveKey> keys,double t) {
        if(t<=keys.getFirst().t) return keys.getFirst().value;
        for(int i=1;i<keys.size();i++) { CurveKey a=keys.get(i-1),b=keys.get(i); if(t<=b.t) {
            double d=b.t-a.t,u=(t-a.t)/d,u2=u*u,u3=u2*u;
            return (2*u3-3*u2+1)*a.value+(u3-2*u2+u)*a.outTangent*d+(-2*u3+3*u2)*b.value+(u3-u2)*b.inTangent*d;
        }} return keys.getLast().value;
    }
    public static Vec3 sampleColor(List<ColorKey> keys,double t) {
        if(t<=keys.getFirst().t) return keys.getFirst().rgb;
        for(int i=1;i<keys.size();i++) { ColorKey a=keys.get(i-1),b=keys.get(i); if(t<=b.t) return a.rgb.lerp(b.rgb,(t-a.t)/(b.t-a.t)); }
        return keys.getLast().rgb;
    }
    public static double sampleAlpha(List<AlphaKey> keys,double t) {
        if(t<=keys.getFirst().t) return keys.getFirst().a;
        for(int i=1;i<keys.size();i++) { AlphaKey a=keys.get(i-1),b=keys.get(i); if(t<=b.t) return a.a+(b.a-a.a)*(t-a.t)/(b.t-a.t); }
        return keys.getLast().a;
    }
    private static void positiveWidth(double sx) { if(!Double.isFinite(sx)||sx<=0) throw new IllegalArgumentException("Width scale must be finite and positive"); }
    private static Vec3 unitOr(Vec3 v,Vec3 fallback) { return finite(v)&&v.lengthSquared()>1e-12 ? v.normalized() : fallback; }
    private static boolean finite(Vec3 v) { return v!=null && Double.isFinite(v.x) && Double.isFinite(v.y) && Double.isFinite(v.z); }

    private static final class Parser {
        private Map<String,Material> materials;
        private Map<String,Mesh> meshes;
        private Map<String,Model> models;
        private AuditedPackLimits.Limits limits;
        private final LinkedHashSet<String> actualRequired=new LinkedHashSet<>();
        ClaudeEffect parse(JsonElement root) {
            JsonObject o=rawObj(root,"$");
            limits=AuditedPackLimits.forEffect(o);
            String version=str(o,"schemaVersion","$");
            if(!version.matches("1\\.\\d+\\.\\d+")) fail("$.schemaVersion","unsupported version '"+version+"'; major 1 required");
            // Reject required capabilities before parsing newer fields, identifying every affected layer.
            JsonObject features=obj(req(o,"features","$"),"$.features","catalog","required","byLayer");
            Set<String> declared=strings(req(features,"required","$.features"),"$.features.required");
            JsonObject byLayer=rawObj(req(features,"byLayer","$.features"),"$.features.byLayer");
            ArrayList<String> unsupported=new ArrayList<>();
            for(String feature:declared) if(!SUPPORTED_FEATURES.contains(feature)) {
                ArrayList<String> affected=new ArrayList<>();
                for(var e:byLayer.entrySet()) if(strings(e.getValue(),"$.features.byLayer."+e.getKey()).contains(feature)) affected.add(e.getKey());
                unsupported.add(feature+" ("+(affected.isEmpty()?"effect-wide":String.join(", ",affected))+")");
            }
            if(!unsupported.isEmpty()) fail("$.features.required","unsupported capabilities: "+String.join("; ",unsupported));
            obj(root,"$","schemaVersion","name","units","coordinateSystem","simulation","post","materials","meshes","features","systems","demo","portableVfxSystem","models","sequence","salvo","scale","width");
            models=new LinkedHashMap<>();
            if(o.has("models"))for(var entry:rawObj(o.get("models"),"$.models").entrySet()) {
                if(models.size()>=16)fail("$.models","runtime limit: 16 models");
                models.put(entry.getKey(),model(entry.getValue(),"$.models."+entry.getKey()));
            }
            ClaudeLifecycle.validate(o);
            if(o.has("sequence")&&!o.get("sequence").isJsonNull()) {
                actualRequired.add("phasedSequence");
                var seq=o.getAsJsonObject("sequence");if(seq.has("triggerInput")&&!seq.get("triggerInput").isJsonNull())actualRequired.add("triggeredEnd");
            }
            if(o.has("salvo")&&!o.get("salvo").isJsonNull())actualRequired.add("salvoSequence");
            rawObj(req(features,"catalog","$.features"),"$.features.catalog");
            JsonObject units=obj(req(o,"units","$"),"$.units","length","time","angle","angularVelocity","color","tint");
            eq(str(units,"length","$.units"),"m (1 = 1 block)","$.units.length");
            eq(str(units,"time","$.units"),"s","$.units.time"); eq(str(units,"angle","$.units"),"rad","$.units.angle");
            eq(str(units,"angularVelocity","$.units"),"rad/s","$.units.angularVelocity");
            eq(str(units,"color","$.units"),"sRGB 0..1","$.units.color"); eq(str(units,"tint","$.units"),"linear HDR multiplier","$.units.tint");
            JsonObject coordinates=obj(req(o,"coordinateSystem","$"),"$.coordinateSystem","handedness","up","forward","eulerOrder");
            eq(str(coordinates,"handedness","$.coordinateSystem"),"left","$.coordinateSystem.handedness");
            eq(str(coordinates,"up","$.coordinateSystem"),"+Y","$.coordinateSystem.up"); eq(str(coordinates,"forward","$.coordinateSystem"),"+Z","$.coordinateSystem.forward");
            eq(str(coordinates,"eulerOrder","$.coordinateSystem"),"Z, then X, then Y","$.coordinateSystem.eulerOrder");
            JsonObject simulation=obj(req(o,"simulation","$"),"$.simulation","fixedTimeStep","gravity");
            double fixed=num(simulation,"fixedTimeStep","$.simulation");
            if(Math.abs(fixed-1.0/120)>1e-12) fail("$.simulation.fixedTimeStep","only normative 1/120 second integration is supported");
            double gravity=nonnegative(num(simulation,"gravity","$.simulation"),"$.simulation.gravity");
            Post post=post(req(o,"post","$"));
            materials=new LinkedHashMap<>();
            for(var e:rawObj(req(o,"materials","$"),"$.materials").entrySet()) materials.put(e.getKey(),material(e.getValue(),"$.materials."+e.getKey()));
            if(materials.isEmpty()||materials.size()>256) fail("$.materials","require 1..256 materials");
            meshes=new LinkedHashMap<>();
            for(var e:rawObj(req(o,"meshes","$"),"$.meshes").entrySet()) meshes.put(e.getKey(),mesh(e.getValue(),"$.meshes."+e.getKey()));
            ArrayList<SystemDef> systems=new ArrayList<>(); HashSet<String> ids=new HashSet<>();
            for(JsonElement s:array(req(o,"systems","$"),"$.systems")) {
                if(systems.size()>=limits.systems()) fail("$.systems","runtime limit: "+limits.systems()+" systems");
                SystemDef system=system(s,"$.systems["+systems.size()+"]",byLayer);
                if(!ids.add(system.id)) fail("$.systems","duplicate id "+system.id); systems.add(system);
            }
            if(systems.isEmpty()) fail("$.systems","at least one system required");
            Width width=width(o.get("width"),systems);
            if(width!=null) actualRequired.add("widthScale");
            Scale scale=scale(o.get("scale"));
            if(scale!=null) actualRequired.add("instanceScale");
            Set<String> missing=new LinkedHashSet<>(actualRequired); missing.removeAll(declared);
            if(!missing.isEmpty()) fail("$.features.required","missing capabilities used by fields: "+missing);
            for(String f:declared) if(!actualRequired.contains(f)) fail("$.features.required","declared capability has no corresponding module: "+f);
            if(o.has("portableVfxSystem")) { String selected=str(o,"portableVfxSystem","$"); if(!ids.contains(selected)) fail("$.portableVfxSystem","unknown system "+selected); }
            return new ClaudeEffect(str(o,"name","$"),version,fixed,gravity,post,materials,meshes,systems,declared,width,scale,models);
        }
        private Model model(JsonElement e,String p) {
            JsonObject o=obj(e,p,"file","format","coordinateConversion","rig","height","clips","sockets","textures","shading");
            String file=str(o,"file",p);
            if(!file.matches("models/[A-Za-z0-9_./-]+\\.glb")||file.contains("..")||file.contains("//"))fail(p+".file","expected safe relative models/*.glb path");
            eq(str(o,"format",p),"gltf-binary",p+".format");eq(str(o,"coordinateConversion",p),"gltfToUnity",p+".coordinateConversion");eq(str(o,"rig",p),"nodeTRS",p+".rig");
            double height=num(o,"height",p);if(height<=0)fail(p+".height","positive model height required");
            Map<String,ModelClip> clips=new LinkedHashMap<>();
            for(var entry:rawObj(req(o,"clips",p),p+".clips").entrySet()) {
                String cp=p+".clips."+entry.getKey();JsonObject clip=obj(entry.getValue(),cp,"duration","loop","note");
                if(entry.getKey().isBlank()||entry.getKey().length()>128||clips.size()>=64)fail(cp,"model clips require bounded nonempty names and at most 64 entries");
                double duration=num(clip,"duration",cp);if(duration<=0||duration>600)fail(cp+".duration","clip duration must be in (0,600]");
                if(clip.has("note"))str(clip,"note",cp);
                clips.put(entry.getKey(),new ModelClip(duration,bool(clip,"loop",cp)));
            }
            Map<String,ModelSocket> sockets=new LinkedHashMap<>();
            for(var entry:rawObj(req(o,"sockets",p),p+".sockets").entrySet()) {
                String sp=p+".sockets."+entry.getKey();JsonObject socket=obj(entry.getValue(),sp,"position","clip","time","note");
                if(entry.getKey().isBlank()||entry.getKey().length()>128||sockets.size()>=256)fail(sp,"model sockets require bounded nonempty names and at most 256 entries");
                String clip=nullableString(req(socket,"clip",sp),sp+".clip");double time=nn(socket,"time",sp);
                if(clip!=null&&(!clips.containsKey(clip)||time>clips.get(clip).duration()))fail(sp,"socket references missing clip or time beyond clip duration");
                if(socket.has("note"))str(socket,"note",sp);
                sockets.put(entry.getKey(),new ModelSocket(vec(req(socket,"position",sp),sp+".position",false),clip,time));
            }
            String textures=o.has("textures")?choice(str(o,"textures",p),p+".textures","none","baseColor"):"none";
            String sp=p+".shading";JsonObject sh=obj(req(o,"shading",p),sp,"mode","lightDirection","ambient","diffuse","wrap","shadowTint","threshold","softness","rimColor","rimPower","rimStrength","translucentFresnelMin","outline");
            String mode=sh.has("mode")?choice(str(sh,"mode",sp),sp+".mode","lambert","toon"):"lambert";
            Vec3 light=vec(req(sh,"lightDirection",sp),sp+".lightDirection",false);if(light.lengthSquared()<1e-12)fail(sp+".lightDirection","nonzero light direction required");
            Vec3 shadow=sh.has("shadowTint")?vec(sh.get("shadowTint"),sp+".shadowTint",true):new Vec3(1,1,1);
            Vec3 rim=vec(req(sh,"rimColor",sp),sp+".rimColor",false);if(rim.x()<0||rim.y()<0||rim.z()<0)fail(sp+".rimColor","negative HDR rim color");
            double threshold=sh.has("threshold")?unit(num(sh,"threshold",sp),sp+".threshold"):.5,softness=sh.has("softness")?nn(sh,"softness",sp):.1;
            ModelOutline outline=new ModelOutline(0,Vec3.ZERO);
            if(sh.has("outline")) { JsonObject ol=obj(sh.get("outline"),sp+".outline","width","color");outline=new ModelOutline(nn(ol,"width",sp+".outline"),vec(req(ol,"color",sp+".outline"),sp+".outline.color",true)); }
            double rimPower=nn(sh,"rimPower",sp);if(rimPower==0)fail(sp+".rimPower","positive rim power required");
            var shading=new ModelShading(mode,light,nn(sh,"ambient",sp),nn(sh,"diffuse",sp),nn(sh,"wrap",sp),shadow,threshold,softness,rim,rimPower,nn(sh,"rimStrength",sp),unit(num(sh,"translucentFresnelMin",sp),sp+".translucentFresnelMin"),outline);
            return new Model(file,height,clips,sockets,textures,shading);
        }
        private Scale scale(JsonElement e) {
            if(e==null||e.isJsonNull())return null;
            String p="$.scale";JsonObject o=obj(e,p,"input","unit","reference","min","max");
            eq(str(o,"unit",p),"m",p+".unit");
            String input=choice(str(o,"input",p),p+".input","targetHeight","areaRadius");
            double reference=num(o,"reference",p),min=num(o,"min",p),max=num(o,"max",p);
            if(reference<=0||min<=0||min>max)fail(p,"positive reference and 0 < min <= max required");
            return new Scale(input,reference,min,max);
        }
        private Width width(JsonElement e,List<SystemDef> systems) {
            if(e==null||e.isJsonNull()) return null;
            String p="$.width";JsonObject o=obj(e,p,"input","unit","reference","min","max","axis","systems","countScaledLayers");
            eq(str(o,"input",p),"effectWidth",p+".input");eq(str(o,"unit",p),"m",p+".unit");eq(str(o,"axis",p),"right",p+".axis");
            double reference=num(o,"reference",p),min=num(o,"min",p),max=num(o,"max",p);
            if(reference<=0||min<=0||min>max) fail(p,"positive reference and 0 < min <= max required");
            Set<String> selected=strings(req(o,"systems",p),p+".systems"),counts=strings(req(o,"countScaledLayers",p),p+".countScaledLayers");
            Set<String> known=new HashSet<>(),knownLayers=new HashSet<>();
            for(SystemDef system:systems) {
                known.add(system.id());
                if(selected.contains(system.id())) for(Layer layer:system.layers()) knownLayers.add(system.id()+"/"+layer.id());
            }
            if(selected.isEmpty()||!known.containsAll(selected)) fail(p+".systems","nonempty list of known systems required");
            if(!knownLayers.containsAll(counts)) fail(p+".countScaledLayers","every entry must name a layer in a width-scaled system");
            return new Width(reference,min,max,selected,counts);
        }
        private Post post(JsonElement e) {
            String p="$.post"; JsonObject o=obj(e,p,"bloom","tonemapping"); Bloom bloom=null;
            if(!req(o,"bloom",p).isJsonNull()) {
                JsonObject b=obj(o.get("bloom"),p+".bloom","threshold","thresholdSpace","softKnee","intensity","scatter","tint");
                String space=str(b,"thresholdSpace",p+".bloom"); eq(space,"gamma",p+".bloom.thresholdSpace");
                bloom=new Bloom(nn(b,"threshold",p+".bloom"),space,unit(num(b,"softKnee",p+".bloom"),p+".bloom.softKnee"),nn(b,"intensity",p+".bloom"),unit(num(b,"scatter",p+".bloom"),p+".bloom.scatter"),vec(req(b,"tint",p+".bloom"),p+".bloom.tint",false));
                if(bloom.intensity>0) actualRequired.add("bloom");
            }
            String tonemap=choice(str(o,"tonemapping",p),p+".tonemapping","neutral","none");
            if(tonemap.equals("neutral")) actualRequired.add("tonemapNeutral"); return new Post(bloom,tonemap);
        }
        private Material material(JsonElement e,String p) {
            JsonObject o=obj(e,p,"texture","blend","tint","depthWrite"); String texture=str(o,"texture",p);
            boolean depthWrite=o.has("depthWrite")&&bool(o,"depthWrite",p);
            if(!texture.matches("textures/[A-Za-z0-9_./-]+\\.png") || texture.contains("..") || texture.contains("//")) fail(p+".texture","expected safe relative textures/*.png path");
            Vec3 tint=vec(req(o,"tint",p),p+".tint",false);
            if(tint.x<0||tint.y<0||tint.z<0) fail(p+".tint","negative HDR multiplier");
            String blend=choice(str(o,"blend",p),p+".blend","alpha","additive");
            if(depthWrite&&!blend.equals("alpha")) fail(p+".depthWrite","depth-writing materials require alpha blend");
            return new Material(texture,blend,tint,depthWrite);
        }
        private Mesh mesh(JsonElement e,String p) {
            JsonObject o=obj(e,p,"vertices","uvs","triangles","doubleSided");
            ArrayList<Vec3> vertices=new ArrayList<>(); for(JsonElement v:array(req(o,"vertices",p),p+".vertices")) vertices.add(vec(v,p+".vertices["+vertices.size()+"]",false));
            ArrayList<Uv> uvs=new ArrayList<>(); for(JsonElement uv:array(req(o,"uvs",p),p+".uvs")) { JsonArray a=tuple(uv,2,p+".uvs["+uvs.size()+"]"); uvs.add(new Uv(number(a.get(0),p),number(a.get(1),p))); }
            if(vertices.isEmpty()||vertices.size()>65536||vertices.size()!=uvs.size()) fail(p,"vertices/UVs must be same length, with 1..65536 vertices");
            ArrayList<Integer> indices=new ArrayList<>(); for(JsonElement v:array(req(o,"triangles",p),p+".triangles")) { int n=integer(v,p+".triangles"); if(n<0||n>=vertices.size()) fail(p+".triangles","index out of range: "+n); indices.add(n); }
            if(indices.isEmpty()||indices.size()>393216||indices.size()%3!=0) fail(p+".triangles","runtime limit: 1..131072 triangle index triples required");
            boolean doubleSided=bool(o,"doubleSided",p); if(!doubleSided) fail(p+".doubleSided","schema 1.0 requires no culling (true)");
            return new Mesh(vertices,uvs,indices,doubleSided);
        }
        private SystemDef system(JsonElement e,String p,JsonObject byLayer) {
            JsonObject o=obj(e,p,"id","role","duration","loop","layers","phase","endBranch"); String id=str(o,"id",p);
            if(o.has("phase")) choice(str(o,"phase",p),p+".phase","main","begin","sustain","end");
            if(o.has("endBranch")) choice(str(o,"endBranch",p),p+".endBranch","any","expire","trigger");
            String role=choice(str(o,"role",p),p+".role","projectile","impact","static","attached","link");
            if(role.equals("attached")) actualRequired.add("attachedFollow");
            if(role.equals("link")) actualRequired.add("linkTarget");
            double duration=num(o,"duration",p); if(duration<=0||duration>600) fail(p+".duration","runtime limit: duration must be in (0,600] seconds");
            ArrayList<Layer> layers=new ArrayList<>(); HashSet<String> ids=new HashSet<>();
            for(JsonElement l:array(req(o,"layers",p),p+".layers")) {
                if(layers.size()>=limits.layers()) fail(p+".layers","runtime limit: "+limits.layers()+" layers per system");
                Layer layer=layer(l,p+".layers["+layers.size()+"]");
                if(layer.path()!=null&&!role.equals("link"))fail(p+".layers["+layers.size()+"].path","path requires role link");
                if(!ids.add(layer.id)) fail(p+".layers","duplicate id "+layer.id);
                String key=id+"/"+layer.id;
                Set<String> listed=strings(req(byLayer,key,"$.features.byLayer"),"$.features.byLayer."+key);
                if(!listed.equals(layer.requires)) fail("$.features.byLayer."+key,"does not match layer.requires"); layers.add(layer);
            }
            return new SystemDef(id,role,duration,bool(o,"loop",p),layers);
        }
        private Layer layer(JsonElement e,String p) {
            JsonObject o=obj(e,p,"id","material","render","sortingFudge","space","position","emission","shape","start","rotationOverLifetime","velocityOverLifetime","gravityModifier","drag","noise","colorOverLifetime","sizeOverLifetime","flipbook","trail","requires","animation","onStop","path");
            StopBehavior onStop=StopBehavior.FINISH;
            if(o.has("onStop")) {
                JsonObject stop=obj(o.get("onStop"),p+".onStop","mode","tail");
                onStop=new StopBehavior(choice(str(stop,"mode",p+".onStop"),p+".onStop.mode","finish","jumpToTail"),nn(stop,"tail",p+".onStop"));
            }
            String id=str(o,"id",p),material=str(o,"material",p),space=space(o,"space",p);
            if(!materials.containsKey(material)) fail(p+".material","unknown material "+material);
            JsonObject r=obj(req(o,"render",p),p+".render","type","mesh","velocityScale","lengthScale","model");
            String modelId=r.has("model")?nullableString(r.get("model"),p+".render.model"):null;
            String type=choice(str(r,"type",p+".render"),p+".render.type","billboard","stretched","mesh","model"),mesh=nullableString(req(r,"mesh",p+".render"),p+".render.mesh");
            if(type.equals("model")) {
                if(modelId==null||!models.containsKey(modelId))fail(p+".render.model","known model required");
                Material m=materials.get(material);
                if(!m.blend().equals("alpha")||!m.depthWrite())fail(p+".material","model layers require alpha blend and depthWrite true");
            } else if(modelId!=null)fail(p+".render.model","model reference on non-model renderer");
            if(type.equals("mesh") && (mesh==null || !meshes.containsKey(mesh))) fail(p+".render.mesh","known mesh required");
            if(!type.equals("mesh") && mesh!=null) fail(p+".render.mesh","mesh reference on non-mesh renderer");
            Render render=new Render(type,mesh,nn(r,"velocityScale",p+".render"),nn(r,"lengthScale",p+".render"),modelId);
            ModelAnimation animation=null;
            if(o.has("animation")&&!o.get("animation").isJsonNull()) {
                if(!type.equals("model"))fail(p+".animation","animation is only defined for model particles");
                JsonObject a=obj(o.get("animation"),p+".animation","clip","speed","loop");
                String clip=str(a,"clip",p+".animation");if(!models.get(modelId).clips().containsKey(clip))fail(p+".animation.clip","unknown model clip "+clip);
                animation=new ModelAnimation(clip,nn(a,"speed",p+".animation"),bool(a,"loop",p+".animation"));
            }
            JsonObject em=obj(req(o,"emission",p),p+".emission","rateOverTime","rateOverDistance","bursts"); ArrayList<Burst> bursts=new ArrayList<>();
            for(JsonElement b:array(req(em,"bursts",p+".emission"),p+".emission.bursts")) { String bp=p+".emission.bursts["+bursts.size()+"]"; JsonObject bo=obj(b,bp,"time","count","cycles","interval"); int count=integer(req(bo,"count",bp),bp+".count"),cycles=integer(req(bo,"cycles",bp),bp+".cycles"); if(count<0||count>8192||cycles<1||cycles>1024) fail(bp,"runtime limits: count 0..8192, cycles 1..1024"); bursts.add(new Burst(nn(bo,"time",bp),count,cycles,nn(bo,"interval",bp))); }
            Emission emission=new Emission(nn(em,"rateOverTime",p+".emission"),nn(em,"rateOverDistance",p+".emission"),bursts);
            if(emission.rateOverTime>10000||emission.rateOverDistance>10000) fail(p+".emission","runtime limit: emission rates <=10000");
            if(bursts.size()>1024) fail(p+".emission.bursts","runtime limit: 1024 burst entries");
            if(emission.rateOverDistance>0&&!space.equals("world")) fail(p+".emission.rateOverDistance","distance emission requires world-space particles");
            JsonObject sh=obj(req(o,"shape",p),p+".shape","type","radius","radiusThickness","offset","height","width","depth");
            Shape shape=new Shape(choice(str(sh,"type",p+".shape"),p+".shape.type","none","sphere","circle","box"),nn(sh,"radius",p+".shape"),unit(num(sh,"radiusThickness",p+".shape"),p+".shape.radiusThickness"),vec(req(sh,"offset",p+".shape"),p+".shape.offset",false),nn(sh,"height",p+".shape"),sh.has("width")?nn(sh,"width",p+".shape"):0,sh.has("depth")?nn(sh,"depth",p+".shape"):0);
            JsonObject st=obj(req(o,"start",p),p+".start","lifetime","size","speed","rotation","rotation3D");
            Start start=new Start(range(req(st,"lifetime",p+".start"),p+".start.lifetime"),range(req(st,"size",p+".start"),p+".start.size"),range(req(st,"speed",p+".start"),p+".start.speed"),range(req(st,"rotation",p+".start"),p+".start.rotation"),st.get("rotation3D")==null||st.get("rotation3D").isJsonNull()?null:range3(st.get("rotation3D"),p+".start.rotation3D"));
            req(st,"rotation3D",p+".start"); if(start.lifetime.minimum()<=0||start.lifetime.maximum()>60||start.size.minimum()<0) fail(p+".start","runtime limits: lifetime in (0,60] seconds and size nonnegative");
            if(start.rotation3D!=null&&!type.equals("mesh")&&!type.equals("model")) fail(p+".start.rotation3D","3D rotation is only defined for mesh/model particles");
            JsonObject rot=obj(req(o,"rotationOverLifetime",p),p+".rotationOverLifetime","x","y","z");
            Range3 rotation=new Range3(range(req(rot,"x",p),p+".rotationOverLifetime.x"),range(req(rot,"y",p),p+".rotationOverLifetime.y"),range(req(rot,"z",p),p+".rotationOverLifetime.z"));
            JsonObject vel=obj(req(o,"velocityOverLifetime",p),p+".velocityOverLifetime","space","linear","orbitalY","radial");
            Velocity velocity=new Velocity(space(vel,"space",p+".velocityOverLifetime"),range3(req(vel,"linear",p+".velocityOverLifetime"),p+".velocityOverLifetime.linear"),range(req(vel,"orbitalY",p),p+".velocityOverLifetime.orbitalY"),range(req(vel,"radial",p),p+".velocityOverLifetime.radial"));
            // Orbital motion is expressly local-only (schema step 7). Preserve the field;
            // warnings() reports nonapplicable world-space values without inventing motion.
            double gravity=num(o,"gravityModifier",p),drag=nn(o,"drag",p); Noise noise=null;
            if(!req(o,"noise",p).isJsonNull()) { JsonObject n=obj(o.get("noise"),p+".noise","strength","frequency","scrollSpeed"); noise=new Noise(nn(n,"strength",p+".noise"),nn(n,"frequency",p+".noise"),num(n,"scrollSpeed",p+".noise")); }
            JsonObject gr=obj(req(o,"colorOverLifetime",p),p+".colorOverLifetime","color","alpha"); Gradient gradient=new Gradient(colors(req(gr,"color",p),p+".colorOverLifetime.color"),alphas(req(gr,"alpha",p),p+".colorOverLifetime.alpha"));
            List<CurveKey> size=curve(req(o,"sizeOverLifetime",p),p+".sizeOverLifetime"); Flipbook flipbook=null;
            if(!req(o,"flipbook",p).isJsonNull()) { JsonObject f=obj(o.get("flipbook"),p+".flipbook","columns","rows","frameOrder","playback","cycles"); int cols=integer(req(f,"columns",p),p+".flipbook.columns"),rows=integer(req(f,"rows",p),p+".flipbook.rows"); if(cols<1||rows<1||(long)cols*rows>65536) fail(p+".flipbook","invalid sheet dimensions"); eq(str(f,"frameOrder",p),"rowMajorFromTopLeft",p+".flipbook.frameOrder"); eq(str(f,"playback",p),"oncePerLifetime",p+".flipbook.playback"); int cycles=f.has("cycles")?integer(f.get("cycles"),p+".flipbook.cycles"):1; if(cycles<1) fail(p+".flipbook.cycles","positive integer required"); flipbook=new Flipbook(cols,rows,"rowMajorFromTopLeft","oncePerLifetime",cycles); }
            Trail trail=null;
            if(!req(o,"trail",p).isJsonNull()) { JsonObject tr=obj(o.get("trail"),p+".trail","material","lifetimeRatio","minVertexDistance","widthOverTrail","color"); String mat=str(tr,"material",p+".trail"); if(!materials.containsKey(mat)) fail(p+".trail.material","unknown material "+mat); trail=new Trail(mat,nn(tr,"lifetimeRatio",p+".trail"),nn(tr,"minVertexDistance",p+".trail"),curve(req(tr,"widthOverTrail",p),p+".trail.widthOverTrail"),colors(req(tr,"color",p),p+".trail.color")); }
            PathFollow path=path(o.get("path"),p+".path");
            if(path!=null) {
                if(!space.equals("local"))fail(p+".path","path requires local space");
                if(start.speed.nonzero()||gravity!=0||drag!=0||noise!=null||velocity.linear.nonzero()||velocity.orbitalY.nonzero()||velocity.radial.nonzero())
                    fail(p+".path","path requires zero speed, gravity, drag and velocity modules, and null noise");
                if(vec(req(o,"position",p),p+".position",false).lengthSquared()!=0)fail(p+".position","path layers require zero position to reach the target");
            }
            Set<String> requires=strings(req(o,"requires",p),p+".requires"); for(String f:requires) if(!SUPPORTED_FEATURES.contains(f)) fail(p+".requires","unsupported capability "+f+" in layer "+id);
            Set<String> used=new LinkedHashSet<>(); used.add(switch(type) { case "mesh" -> "meshParticle"; case "model" -> "modelParticle"; case "stretched" -> "stretchedBillboard"; default -> "billboard"; });
            addMaterialFeatures(used,materials.get(material));
            if(space.equals("world")) used.add("worldSpace"); if(emission.rateOverDistance>0) used.add("rateOverDistance");
            if(start.rotation3D!=null) used.add("rotation3D"); if(rotation.nonzero()) used.add("rotationOverLifetime");
            if(velocity.linear.nonzero()) used.add("linearVelocity"); if(velocity.orbitalY.nonzero()) used.add("orbitalVelocity"); if(velocity.radial.nonzero()) used.add("radialVelocity");
            if(gravity!=0) used.add("gravity"); if(drag!=0) used.add("drag"); if(noise!=null) used.add("noise"); if(flipbook!=null) { used.add("flipbook");if(flipbook.cycles>1) used.add("flipbookCycles"); }
            if(trail!=null) { used.add("trails"); addMaterialFeatures(used,materials.get(trail.material)); }
            if(onStop.jumpsToTail()) used.add("stopTail");
            if(path!=null)used.add("pathFollow");
            if(!used.equals(requires)) fail(p+".requires","must match actual modules. Computed "+used+"; declared "+requires);
            actualRequired.addAll(used);
            return new Layer(id,material,render,num(o,"sortingFudge",p),space,vec(req(o,"position",p),p+".position",false),emission,shape,start,rotation,velocity,gravity,drag,noise,gradient,size,flipbook,trail,requires,onStop,path,animation);
        }
        private PathFollow path(JsonElement e,String p) {
            if(e==null||e.isJsonNull())return null;
            JsonObject o=obj(e,p,"progress","bend","wave"),bend=obj(req(o,"bend",p),p+".bend","x","y"),wave=obj(req(o,"wave",p),p+".wave","amplitude","cycles","phase","verticalRatio");
            return new PathFollow(curve(req(o,"progress",p),p+".progress"),range(req(bend,"x",p+".bend"),p+".bend.x"),range(req(bend,"y",p+".bend"),p+".bend.y"),
                range(req(wave,"amplitude",p+".wave"),p+".wave.amplitude"),range(req(wave,"cycles",p+".wave"),p+".wave.cycles"),range(req(wave,"phase",p+".wave"),p+".wave.phase"),num(wave,"verticalRatio",p+".wave"));
        }
        private void addMaterialFeatures(Set<String> set,Material m) { set.add(m.blend.equals("alpha")?"alphaBlend":"additiveBlend"); if(m.tint.x>1||m.tint.y>1||m.tint.z>1) set.add("hdrTint"); if(m.depthWrite) set.add("depthWrite"); }
        private List<CurveKey> curve(JsonElement e,String p) {
            ArrayList<CurveKey> keys=new ArrayList<>(); double previous=-1;
            for(JsonElement el:array(e,p)) { String kp=p+"["+keys.size()+"]"; JsonObject k=obj(el,kp,"t","value","inTangent","outTangent"); double t=keyTime(k,kp,previous); previous=t; keys.add(new CurveKey(t,num(k,"value",kp),num(k,"inTangent",kp),num(k,"outTangent",kp))); }
            if(keys.isEmpty()) fail(p,"nonempty curve required"); return List.copyOf(keys);
        }
        private List<ColorKey> colors(JsonElement e,String p) {
            ArrayList<ColorKey> keys=new ArrayList<>(); double previous=-1;
            for(JsonElement el:array(e,p)) { String kp=p+"["+keys.size()+"]"; JsonObject k=obj(el,kp,"t","rgb"); double t=keyTime(k,kp,previous); previous=t; keys.add(new ColorKey(t,vec(req(k,"rgb",kp),kp+".rgb",true))); }
            if(keys.isEmpty()) fail(p,"nonempty color gradient required"); return List.copyOf(keys);
        }
        private List<AlphaKey> alphas(JsonElement e,String p) {
            ArrayList<AlphaKey> keys=new ArrayList<>(); double previous=-1;
            for(JsonElement el:array(e,p)) { String kp=p+"["+keys.size()+"]"; JsonObject k=obj(el,kp,"t","a"); double t=keyTime(k,kp,previous); previous=t; keys.add(new AlphaKey(t,unit(num(k,"a",kp),kp+".a"))); }
            if(keys.isEmpty()) fail(p,"nonempty alpha gradient required"); return List.copyOf(keys);
        }
        private double keyTime(JsonObject k,String p,double previous) { double t=unit(num(k,"t",p),p+".t"); if(t<=previous) fail(p+".t","keys must be strictly increasing"); return t; }
        private Range range(JsonElement e,String p) { JsonArray a=tuple(e,2,p); double min=number(a.get(0),p+"[0]"),max=number(a.get(1),p+"[1]"); return new Range(min,max); }
        private Range3 range3(JsonElement e,String p) { JsonArray a=tuple(e,3,p); return new Range3(range(a.get(0),p+"[0]"),range(a.get(1),p+"[1]"),range(a.get(2),p+"[2]")); }
        private Vec3 vec(JsonElement e,String p,boolean color) { JsonArray a=tuple(e,3,p); double x=number(a.get(0),p+"[0]"),y=number(a.get(1),p+"[1]"),z=number(a.get(2),p+"[2]"); if(color) {unit(x,p);unit(y,p);unit(z,p);} return new Vec3(x,y,z); }
        private static JsonObject rawObj(JsonElement e,String p) { if(e==null||!e.isJsonObject()) fail(p,"object required"); return e.getAsJsonObject(); }
        private static JsonObject obj(JsonElement e,String p,String... names) { JsonObject o=rawObj(e,p); Set<String> allowed=Set.of(names); for(String key:o.keySet()) if(!allowed.contains(key)) fail(p+"."+key,"unsupported field; not silently ignored"); return o; }
        private static void absentOrNull(JsonObject o,String key,String p) {
            if(o.has(key)&&!o.get(key).isJsonNull()) fail(p+"."+key,"unsupported active module; only null is accepted");
        }
        private static JsonElement req(JsonObject o,String key,String p) { JsonElement e=o.get(key); if(e==null) fail(p+"."+key,"required field missing"); return e; }
        private static JsonArray array(JsonElement e,String p) { if(e==null||!e.isJsonArray()) fail(p,"array required"); return e.getAsJsonArray(); }
        private static JsonArray tuple(JsonElement e,int n,String p) { JsonArray a=array(e,p); if(a.size()!=n) fail(p,"expected "+n+" values"); return a; }
        private static String str(JsonObject o,String key,String p) { return string(req(o,key,p),p+"."+key); }
        private static String string(JsonElement e,String p) { if(e==null||!e.isJsonPrimitive()||!e.getAsJsonPrimitive().isString()) fail(p,"string required"); String s=e.getAsString(); if(s.isBlank()) fail(p,"nonempty string required"); return s; }
        private static String nullableString(JsonElement e,String p) { return e.isJsonNull()?null:string(e,p); }
        private static boolean bool(JsonObject o,String key,String p) { JsonElement e=req(o,key,p); if(!e.isJsonPrimitive()||!e.getAsJsonPrimitive().isBoolean()) fail(p+"."+key,"boolean required"); return e.getAsBoolean(); }
        private static double num(JsonObject o,String key,String p) { return number(req(o,key,p),p+"."+key); }
        private static double number(JsonElement e,String p) { if(e==null||!e.isJsonPrimitive()||!e.getAsJsonPrimitive().isNumber()) fail(p,"number required"); double n=e.getAsDouble(); if(!Double.isFinite(n)||Math.abs(n)>1_000_000) fail(p,"finite number with magnitude <=1000000 required"); return n; }
        private static int integer(JsonElement e,String p) { double n=number(e,p); if(n!=Math.rint(n)||n<Integer.MIN_VALUE||n>Integer.MAX_VALUE) fail(p,"integer required"); return (int)n; }
        private static double nn(JsonObject o,String key,String p) { return nonnegative(num(o,key,p),p+"."+key); }
        private static double nonnegative(double n,String p) { if(n<0) fail(p,"must be nonnegative"); return n; }
        private static double unit(double n,String p) { if(n<0||n>1) fail(p,"must be in 0..1"); return n; }
        private static String space(JsonObject o,String key,String p) { return choice(str(o,key,p),p+"."+key,"local","world"); }
        private static String choice(String value,String p,String... choices) { for(String c:choices) if(value.equals(c)) return value; fail(p,"unsupported value '"+value+"' (supported: "+String.join(", ",choices)+")"); return null; }
        private static void eq(String actual,String expected,String p) { if(!actual.equals(expected)) fail(p,"unsupported convention '"+actual+"'; expected '"+expected+"'"); }
        private static Set<String> strings(JsonElement e,String p) { LinkedHashSet<String> s=new LinkedHashSet<>(); for(JsonElement v:array(e,p)) { String value=string(v,p); if(!s.add(value)) fail(p,"duplicate entry "+value); } return s; }
        private static void fail(String p,String message) { throw new ValidationException(p+": "+message); }
    }
}
