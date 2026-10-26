package dev.portablevfx.client.claude;

import com.google.gson.JsonParser;
import dev.portablevfx.client.claude.ClaudeEffect.*;
import dev.portablevfx.client.claude.ClaudeSimulation.ParticleView;
import dev.portablevfx.client.render.gl.GlStateSnapshot;
import dev.portablevfx.client.render.gl.GpuFailureException;
import dev.portablevfx.client.render.EffectBackend;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.lwjgl.opengl.GL;
import org.lwjgl.system.MemoryUtil;
import static org.lwjgl.opengl.GL33C.*;

/** Generic schema-v1 particle backend; no spell names, generated textures, Unity or native plugins. */
public final class ClaudeBackend implements EffectBackend {
    public static final int MAX_INSTANCES=128,MAX_FRAME_PARTICLES=8192,MAX_FRAME_VERTICES=1_048_576;
    private final Thread owner=Thread.currentThread();
    private final Set<ClaudeAsset> assets=Collections.newSetFromMap(new IdentityHashMap<>());
    private final List<ClaudeInstance> instances=new ArrayList<>();
    private final Map<String,Texture> sharedTextures=new LinkedHashMap<>();
    private final Map<String,ModelRuntime> sharedModels=new LinkedHashMap<>();
    private final ClaudeModelRenderer modelRenderer=new ClaudeModelRenderer();
    private final Set<PreparationCache> preparationCaches=Collections.newSetFromMap(new IdentityHashMap<>());
    private final ClaudePostPass post=new ClaudePostPass();
    private final boolean interpolateFlipbooks;
    private int program,vao,vbo;
    private long retainedTextureBytes;
    private long textureUploads,shaderLinks,budgetStops;
    private long assetGeneration,depthGeneration=-1;private boolean depthCached;
    private boolean closed;
    private static final org.slf4j.Logger LOG=org.slf4j.LoggerFactory.getLogger("PortableVFX");
    private final PreparationCache fallbackPreparationCache;
    private final long residentRgbaLimit;
    public static final class CapacityException extends IOException { public CapacityException(String message) { super(message); } }

    /** A generation-scoped, CPU-only cache. Its byte ceiling includes pinned and idle RGBA data. */
    public static final class PreparationCache implements AutoCloseable {
        private final long maxBytes;
        private final LinkedHashMap<String,DecodedEntry> textures=new LinkedHashMap<>(16,.75f,true);
        private final LinkedHashMap<String,ModelEntry> models=new LinkedHashMap<>(16,.75f,true);
        private final LinkedHashMap<String,ClaudeEffect> effects=new LinkedHashMap<>(16,.75f,true);
        private long retainedBytes,decodeCount,cacheHits,parseCount;
        private boolean closed;
        public PreparationCache(long maxBytes){
            if(maxBytes<1||maxBytes>ClaudeTexture.MAX_BACKEND_RGBA_BYTES)throw new IllegalArgumentException("Claude preparation cache must be 1..1024 MiB");
            this.maxBytes=maxBytes;
        }
        private synchronized ClaudeEffect effect(String manifest)throws IOException{
            ensureOpen();String key=digest(manifest.getBytes(StandardCharsets.UTF_8));
            var effect=effects.get(key);if(effect!=null)return effect;
            effect=ClaudeEffect.parse(manifest);parseCount++;
            // Definitions have a separate count cap and never keep encoded PNG data alive.
            if(effects.size()>=2048)effects.remove(effects.keySet().iterator().next());
            effects.put(key,effect);return effect;
        }
        private synchronized DecodedEntry acquire(byte[] png)throws IOException{
            ensureOpen();
            if(png==null||png.length<45||png.length>ClaudeTexture.MAX_PNG_BYTES)throw new IOException("PNG must be within the 4 MiB encoded byte budget");
            String key=digest(png);var found=textures.get(key);
            if(found!=null){found.references++;cacheHits++;return found;}
            // Estimate only to reserve space. The strict decoder still validates the full PNG,
            // including header, CRCs, dimensions, chunk ordering, and decoded dimensions.
            int width=ByteBuffer.wrap(png).getInt(16),height=ByteBuffer.wrap(png).getInt(20);
            if(width<1||height<1||width>ClaudeTexture.MAX_DIMENSION||height>ClaudeTexture.MAX_DIMENSION)
                throw new IOException("PNG dimensions must be 1..2048");
            long bytes=(long)width*height*4;
            makeRoom(bytes);
            if(retainedBytes+bytes>maxBytes)throw new CapacityException("Claude shared decoded PNG cache byte budget exceeded");
            // The reservation covers the transient RGBA decode; only the (usually BC7) GPU bytes stay retained.
            var decoded=ClaudeTexture.prepare(png,key,maxBytes-retainedBytes);decodeCount++;
            var entry=new DecodedEntry(this,key,decoded);entry.references=1;textures.put(key,entry);retainedBytes+=entry.bytes();return entry;
        }
        private void makeRoom(long bytes){
            var texturesIterator=textures.entrySet().iterator();
            while(retainedBytes+bytes>maxBytes&&texturesIterator.hasNext()){
                var entry=texturesIterator.next().getValue();if(entry.references==0){retainedBytes-=entry.bytes();texturesIterator.remove();}
            }
            var modelsIterator=models.entrySet().iterator();
            while(retainedBytes+bytes>maxBytes&&modelsIterator.hasNext()){
                var entry=modelsIterator.next().getValue();if(entry.references==0){retainedBytes-=entry.model.retainedBytes();modelsIterator.remove();}
            }
        }
        private synchronized ModelEntry acquireModel(byte[] glb)throws IOException{
            ensureOpen();if(glb==null)throw new IOException("Missing model GLB");
            String key=digest(glb);var existing=models.get(key);if(existing!=null){existing.references++;cacheHits++;return existing;}
            var model=ClaudeModelLoader.load(glb);makeRoom(model.retainedBytes());
            if(retainedBytes+model.retainedBytes()>maxBytes)throw new CapacityException("Claude shared model cache byte budget exceeded");
            var entry=new ModelEntry(this,key,model);entry.references=1;models.put(key,entry);retainedBytes+=model.retainedBytes();
            decodeCount+=model.images().size();parseCount++;return entry;
        }
        private synchronized void retain(ModelEntry entry){if(entry.cache!=this||entry.references<1)throw new IllegalStateException("Released model preparation");entry.references++;}
        private synchronized void release(ModelEntry entry){
            if(entry.references<=0)throw new IllegalStateException("Unbalanced model preparation");
            if(--entry.references==0&&closed){models.remove(entry.digest);retainedBytes-=entry.model.retainedBytes();}
        }
        private synchronized void retain(DecodedEntry entry){
            if(entry.cache!=this||entry.references<1)throw new IllegalStateException("Released Claude texture preparation");
            entry.references++;
        }
        private synchronized void release(DecodedEntry entry){
            if(entry.references<=0)throw new IllegalStateException("Unbalanced Claude texture preparation");
            if(--entry.references==0&&closed){textures.remove(entry.digest);retainedBytes-=entry.bytes();}
        }
        private void ensureOpen()throws IOException{if(closed)throw new IOException("Claude preparation cache is closed");}
        public synchronized PreparationDiagnostics diagnostics(){
            return new PreparationDiagnostics(decodeCount,cacheHits,parseCount,retainedBytes,textures.size(),textures.values().stream().filter(t->t.references>0).count());
        }
        /** Live Prepared/Asset leases stay valid; the last lease promptly releases their bytes. */
        @Override public synchronized void close(){
            closed=true;effects.clear();var iterator=textures.entrySet().iterator();
            while(iterator.hasNext()){var entry=iterator.next().getValue();if(entry.references==0){retainedBytes-=entry.bytes();iterator.remove();}}
            var modelIterator=models.entrySet().iterator();while(modelIterator.hasNext()){var entry=modelIterator.next().getValue();if(entry.references==0){retainedBytes-=entry.model.retainedBytes();modelIterator.remove();}}
        }
    }
    public record PreparationDiagnostics(long decodeCount,long cacheHits,long parseCount,long retainedBytes,int textureCount,long pinnedTextureCount) { }
    public record Diagnostics(long decodeCount,long cacheHits,long parseCount,long textureUploads,long shaderLinks,long targetAllocations,
                              long retainedTextureBytes,int textureCount,int pendingTextures,int assetCount) { }
    private static final class DecodedEntry {
        final PreparationCache cache;final String digest;final ClaudeTexture.Gpu decoded;int references;
        DecodedEntry(PreparationCache cache,String digest,ClaudeTexture.Gpu decoded){this.cache=cache;this.digest=digest;this.decoded=decoded;}
        long bytes(){return decoded.data().length;}
    }
    private static final class ModelEntry {
        final PreparationCache cache;final String digest;final ClaudeModel model;int references;
        ModelEntry(PreparationCache cache,String digest,ClaudeModel model){this.cache=cache;this.digest=digest;this.model=model;}
    }
    private final class ModelRuntime {
        final ModelEntry entry;final ClaudeModelRenderer.Asset gpu;int references;
        ModelRuntime(ModelEntry entry){this.entry=entry;this.gpu=modelRenderer.install(entry.model);}
    }
    private void releaseModel(ModelRuntime model){
        if(--model.references!=0)return;model.gpu.close();sharedModels.remove(model.entry.digest);
        retainedTextureBytes-=model.entry.model.retainedBytes();model.entry.cache.release(model.entry);
    }
    private static String digest(byte[] bytes){
        try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));}
        catch(NoSuchAlgorithmException impossible){throw new AssertionError(impossible);}
    }
    /** Immutable CPU result with explicit ownership; no OpenGL calls, safe to prepare off-thread. */
    public static final class Prepared implements AutoCloseable {
        private final ClaudeEffect effect;private final SystemDef system;private final float magnification;
        private final Map<String,DecodedEntry> textures;private final Map<String,ModelEntry> models;private boolean closed;
        private Prepared(ClaudeEffect effect,SystemDef system,float magnification,Map<String,DecodedEntry> textures,Map<String,ModelEntry> models){
            this.effect=effect;this.system=system;this.magnification=magnification;this.textures=textures;this.models=models;
        }
        @Override public synchronized void close(){
            if(closed)return;closed=true;for(var entry:textures.values())entry.cache.release(entry);textures.clear();
            for(var entry:models.values())entry.cache.release(entry);models.clear();
        }
    }
    private static final String VERTEX="""
        #version 330 core
        layout(location=0) in vec3 position;layout(location=1) in vec2 uv;
        layout(location=2) in vec3 particleColor;layout(location=3) in float particleAlpha;
        layout(location=4) in vec3 frameBlend;
        uniform mat4 viewProjection;uniform vec2 flipbookGrid;
        out vec2 texcoord;out vec3 srgbColor;out float alpha;
        flat out vec2 cellOrigin;flat out vec2 nextCellOrigin;flat out float frameFraction;
        vec2 origin(float frame){return vec2(mod(frame,flipbookGrid.x),flipbookGrid.y-1.0-floor(frame/flipbookGrid.x))/flipbookGrid;}
        void main(){texcoord=uv;srgbColor=particleColor;alpha=particleAlpha;
            cellOrigin=origin(frameBlend.x);nextCellOrigin=origin(frameBlend.y);frameFraction=frameBlend.z;
            gl_Position=viewProjection*vec4(position,1);}
        """;
    private static final String FRAGMENT="""
        #version 330 core
        in vec2 texcoord;in vec3 srgbColor;in float alpha;uniform float localOpacity;
        flat in vec2 cellOrigin;flat in vec2 nextCellOrigin;flat in float frameFraction;
        uniform sampler2D colorTexture;uniform vec3 tint;uniform vec2 flipbookGrid;
        uniform bool interpolateFlipbooks;uniform bool additiveMaterial;
        layout(location=0) out vec4 color;layout(location=1) out vec4 light;
        vec2 inCell(vec2 p,vec2 origin){
            vec2 cell=1.0/flipbookGrid;
            vec2 inset=min(0.5/vec2(textureSize(colorTexture,0)),cell*0.5);
            return clamp(p,origin+inset,origin+cell-inset);
        }
        void main(){
            bool smoothSheet=interpolateFlipbooks&&(flipbookGrid.x>1.0||flipbookGrid.y>1.0);
            vec4 texel=texture(colorTexture,smoothSheet?inCell(texcoord,cellOrigin):texcoord);
            // The interchange explicitly specifies c^2.2, not the hardware sRGB transfer curve.
            vec3 rgb=pow(max(texel.rgb,vec3(0)),vec3(2.2));
            if(smoothSheet&&frameFraction>0.0){
                vec4 nextTexel=texture(colorTexture,inCell(texcoord+nextCellOrigin-cellOrigin,nextCellOrigin));
                vec3 nextRgb=pow(max(nextTexel.rgb,vec3(0)),vec3(2.2));
                // Blend linear premultiplied samples, then restore straight alpha for
                // the existing alpha/additive GL blend functions. No doubled opacity.
                float mixedAlpha=mix(texel.a,nextTexel.a,frameFraction);
                rgb=mix(rgb*texel.a,nextRgb*nextTexel.a,frameFraction)/max(mixedAlpha,1e-20);
                texel.a=mixedAlpha;
            }
            float a=texel.a*alpha*localOpacity;if(a<=0.0)discard;
            rgb=rgb*tint*pow(max(srgbColor,vec3(0)),vec3(2.2));
            color=vec4(additiveMaterial?vec3(0):rgb,a);
            light=vec4(additiveMaterial?rgb:vec3(0),a);}
        """;
    /** Presentation smoothing is generic and can be disabled for exact discrete source playback. */
    public ClaudeBackend(){this(Boolean.parseBoolean(System.getProperty("portablevfx.claude.interpolateFlipbooks","true")));}
    public ClaudeBackend(boolean interpolateFlipbooks){this(interpolateFlipbooks,128L*1024*1024);}
    public ClaudeBackend(long residentRgbaLimit){this(true,residentRgbaLimit);}
    public ClaudeBackend(boolean interpolateFlipbooks,long residentRgbaLimit){
        if(residentRgbaLimit<64L*1024*1024||residentRgbaLimit>ClaudeTexture.MAX_BACKEND_RGBA_BYTES)throw new IllegalArgumentException("Resident RGBA cache must be 64..1024 MiB");
        this.interpolateFlipbooks=interpolateFlipbooks;this.residentRgbaLimit=residentRgbaLimit;
        this.fallbackPreparationCache=new PreparationCache(residentRgbaLimit);
    }
    @Override public String id(){return "claude";}
    /** Where BC7 encodings are cached between launches (null disables the disk cache). */
    public static void textureCacheDirectory(java.nio.file.Path directory){ClaudeTexture.cacheDirectory(directory);}
    /** Drops cached encodings unused for {@code days} and resets the per-reload texture counters. */
    public static void beginTexturePreparation(int pruneDays){ClaudeTexture.resetSummary();ClaudeTexture.pruneCache(pruneDays);}
    public static String textureSummary(){return ClaudeTexture.summary();}

    private final class ClaudeAsset implements Asset {
        final ClaudeEffect effect;final SystemDef system;final float magnification;
        final Map<String,Texture> textures;final Map<String,ModelRuntime> models;final ClaudePostPass.Settings postSettings;
        final boolean writesDepth;
        boolean closed;
        ClaudeAsset(ClaudeEffect effect,SystemDef system,float magnification,Map<String,Texture> textures,Map<String,ModelRuntime> models){
            this.effect=effect;this.system=system;this.magnification=magnification;this.textures=textures;this.models=models;
            var bloom=effect.post().bloom();
            postSettings=bloom==null ? new ClaudePostPass.Settings(1,.5,0,.7,1,1,1,effect.post().tonemapping().equals("neutral"))
                : new ClaudePostPass.Settings(bloom.threshold(),bloom.softKnee(),bloom.intensity(),bloom.scatter(),bloom.tint().x(),bloom.tint().y(),bloom.tint().z(),effect.post().tonemapping().equals("neutral"));
            writesDepth=system.layers().stream().anyMatch(l->effect.materials().get(l.material()).depthWrite()
                    ||l.trail()!=null&&effect.materials().get(l.trail().material()).depthWrite());
        }
        @Override public void close(){
            checkThread();if(closed)return;closed=true;assetGeneration++;
            if(textures.values().stream().anyMatch(t->t.id!=0)||models.values().stream().anyMatch(m->m.gpu.initialized()))try(var ignored=new GlStateSnapshot()){
                for(var texture:textures.values())releaseTexture(texture);for(var model:models.values())releaseModel(model);
            }else {for(var texture:textures.values())releaseTexture(texture);for(var model:models.values())releaseModel(model);}
            textures.clear();models.clear();
        }
    }
    private void releaseTexture(Texture texture){
        if(--texture.references!=0)return;
        if(texture.id!=0){glDeleteTextures(texture.id);texture.id=0;}
        sharedTextures.remove(texture.digest);retainedTextureBytes-=texture.bytes+texture.extraBytes;
        texture.releasePixels();
    }
    private static final int GL_COMPRESSED_RGBA_BPTC_UNORM=0x8E8C,GL_COMPRESSED_RGBA_S3TC_DXT5_EXT=0x83F3;
    private Boolean bptc,s3tc;
    /** BPTC is core since GL 4.2; macOS (GL 4.1) lacks it and gets a decoded upload, driver-compressed to DXT5 when offered. */
    private boolean bptcSupported(){
        if(bptc==null){var caps=GL.getCapabilities();bptc=caps.OpenGL42||caps.GL_ARB_texture_compression_bptc;s3tc=caps.GL_EXT_texture_compression_s3tc;
            if(!bptc)LOG.warn("PortableVFX: GPU lacks BPTC (BC7); BC7 textures are decoded at upload{}",s3tc?" and driver-compressed to DXT5":"");}
        return bptc;
    }
    /** GPU texture is the cache: prepared pixels are leased only until upload; bytes stay counted for the resident budget. */
    private final class Texture {
        final String digest;final long bytes;DecodedEntry entry;int id,references;long extraBytes;
        Texture(DecodedEntry entry){this.entry=entry;this.digest=entry.digest;this.bytes=entry.bytes();}
        void releasePixels(){var leased=entry;if(leased==null)return;entry=null;leased.cache.release(leased);}
        int upload(){
            if(id!=0)return id;if(entry==null)throw new IllegalStateException("Claude texture pixels released before upload");
            int created=glGenTextures();ByteBuffer staging=null;long uploadedExtra=0;
            try{
                var decoded=entry.decoded;
                glBindTexture(GL_TEXTURE_2D,created);
                glTexParameteri(GL_TEXTURE_2D,GL_TEXTURE_MIN_FILTER,GL_LINEAR);glTexParameteri(GL_TEXTURE_2D,GL_TEXTURE_MAG_FILTER,GL_LINEAR);
                glTexParameteri(GL_TEXTURE_2D,GL_TEXTURE_WRAP_S,GL_CLAMP_TO_EDGE);glTexParameteri(GL_TEXTURE_2D,GL_TEXTURE_WRAP_T,GL_CLAMP_TO_EDGE);
                glTexParameteri(GL_TEXTURE_2D,GL_TEXTURE_MAX_LEVEL,0);
                if(decoded.format()==ClaudeTexture.BC7&&bptcSupported()){
                    staging=MemoryUtil.memAlloc(decoded.data().length);staging.put(decoded.data()).flip();
                    glCompressedTexImage2D(GL_TEXTURE_2D,0,GL_COMPRESSED_RGBA_BPTC_UNORM,decoded.width(),decoded.height(),0,staging);
                }else{
                    byte[] rgba=decoded.format()==ClaudeTexture.BC7?ClaudeTextureCodec.decode(decoded.data(),decoded.width(),decoded.height()):decoded.data();
                    staging=MemoryUtil.memAlloc(rgba.length);staging.put(rgba).flip();
                    int internal=decoded.format()==ClaudeTexture.BC7&&Boolean.TRUE.equals(s3tc)?GL_COMPRESSED_RGBA_S3TC_DXT5_EXT:GL_RGBA8;
                    // BC7 admitted at 1 byte/pixel but uploaded as RGBA8 (no BPTC, no S3TC): charge the real size so later admissions see it.
                    if(decoded.format()==ClaudeTexture.BC7&&internal==GL_RGBA8)uploadedExtra=decoded.rawBytes()-bytes;
                    glTexImage2D(GL_TEXTURE_2D,0,internal,decoded.width(),decoded.height(),0,GL_RGBA,GL_UNSIGNED_BYTE,staging);
                }
                if(glGetTexLevelParameteri(GL_TEXTURE_2D,0,GL_TEXTURE_WIDTH)!=decoded.width())throw new GpuFailureException("Claude PNG texture upload failed");
                id=created;textureUploads++;extraBytes=uploadedExtra;retainedTextureBytes+=extraBytes;
            }finally{if(staging!=null)MemoryUtil.memFree(staging);if(id==0)glDeleteTextures(created);}
            releasePixels();return id;
        }
    }
    private final class ClaudeInstance implements Instance {
        final ClaudeAsset asset;final long seed;
        float localOpacity=1;
        @Override public void opacity(float value){checkThread();localOpacity=dev.portablevfx.client.api.VfxPreferences.bounded(value);}
        Double effectWidth;double scaleInput,linkLength=-1;
        ClaudeSimulation simulation;Vec3 worldOrigin=Vec3.ZERO;Transform transform;double seconds,synchronizedSeconds,sceneSeconds=Double.NaN;boolean stopped,finishing,seekPending,finishClearLocal=true;
        ClaudeInstance(ClaudeAsset asset,long seed){this.asset=asset;this.seed=seed;transform=new Transform(new Vec3(0,0,0),identityBasis());}
        @Override public boolean exists(){checkThread();return !stopped&&!asset.closed&&(simulation==null||!simulation.isFinished());}
        @Override public void worldOrigin(double x,double y,double z){
            checkThread();if(!Double.isFinite(x)||!Double.isFinite(y)||!Double.isFinite(z))throw new IllegalArgumentException("Nonfinite Claude world origin");
            Vec3 next=new Vec3(x,y,z);if(simulation!=null&&!next.equals(worldOrigin))throw new IllegalArgumentException("Claude origin cannot change during playback; reset instances first");worldOrigin=next;
        }
        @Override public void transform(float x,float y,float z,float rx,float ry,float rz,float scale){
            checkThread();for(float f:new float[]{x,y,z,rx,ry,rz,scale})if(!Float.isFinite(f))throw new IllegalArgumentException("Nonfinite Claude transform");
            if(Math.abs(scale-1)>1e-6)throw new IllegalArgumentException("Claude schema-v1 requires unit runtime scale; scaled gravity/noise semantics are unspecified");
            Matrix4f rotation=new Matrix4f().rotateXYZ(rx,ry,rz);
            transform=new Transform(new Vec3(x+worldOrigin.x(),y+worldOrigin.y(),z+worldOrigin.z()),new Basis(direction(rotation,1,0,0),direction(rotation,0,1,0),direction(rotation,0,0,1)));
        }
        @Override public void basis(float[] matrix){
            checkThread();if(matrix==null)return;if(matrix.length!=9)throw new IllegalArgumentException("Claude basis requires nine components");
            for(float f:matrix)if(!Float.isFinite(f))throw new IllegalArgumentException("Nonfinite Claude basis");
            Basis basis=new Basis(new Vec3(matrix[0],matrix[1],matrix[2]),new Vec3(matrix[3],matrix[4],matrix[5]),new Vec3(matrix[6],matrix[7],matrix[8]));
            // Basis constructors enforce the interchange's orthonormal transform contract.
            transform=new Transform(transform.position(),basis);
        }
        @Override public void effectWidth(double metres){
            checkThread();if(!Double.isFinite(metres)||metres<=0||metres>1024)throw new IllegalArgumentException("Invalid Claude effect width");
            if(simulation!=null&&!Objects.equals(effectWidth,metres))throw new IllegalArgumentException("Claude width is fixed at start");
            effectWidth=metres;
        }
        @Override public void parameters(double scaleInput,double linkLength){
            checkThread();if(!Double.isFinite(scaleInput)||scaleInput<0||scaleInput>1_000_000||!Double.isFinite(linkLength)||linkLength< -1||linkLength>1_000_000)
                throw new IllegalArgumentException("Invalid Claude scale/link parameters");
            if(simulation!=null&&this.scaleInput!=scaleInput)throw new IllegalArgumentException("Claude scale input is fixed at start");
            this.scaleInput=scaleInput;this.linkLength=linkLength;
        }
        @Override public void sceneTime(double seconds){checkThread();if(!Double.isFinite(seconds)||seconds<0)throw new IllegalArgumentException("Invalid Claude scene clock");sceneSeconds=seconds;}
        @Override public void seek(float seconds){checkThread();if(!Float.isFinite(seconds)||seconds<0)throw new IllegalArgumentException("Invalid Claude time");// A host clock that steps back (e.g. /tick freeze/unfreeze sub-tick delta) holds the last synchronized sample; replay uses a new instance.
            this.seconds=Math.max(seconds,synchronizedSeconds);seekPending=true;}
        @Override public void finishEmission(){finishEmission(true);}
        @Override public void finishEmission(boolean clearLocal){finishAt(seconds,clearLocal);}
        @Override public void finishEmissionAt(float seconds,boolean clearLocal){
            if(!Float.isFinite(seconds)||seconds<0)throw new IllegalArgumentException("Invalid Claude finish time");
            finishAt(seconds,clearLocal);
        }
        @Override public void expireEmissionAt(float seconds){finishEmissionAt(seconds,asset.system.role().equals("projectile"));}
        private void finishAt(double boundary,boolean clearLocal){
            checkThread();if(finishing)return;
            double targetSeconds=seconds,targetSceneSeconds=sceneSeconds;
            // A skipped render must not emit until the later frame or restart a tail there.
            // A FINISH received within a tick may trail an already rendered sub-tick sample;
            // preserve that sample rather than attempting an unsupported rewind.
            seconds=Math.max(synchronizedSeconds,Math.min(targetSeconds,boundary));
            if(Double.isFinite(sceneSeconds))sceneSeconds-=targetSeconds-seconds;
            synchronize(0);
            finishing=true;finishClearLocal=clearLocal;
            if(simulation!=null)simulation.stopEmission(clearLocal);
            seconds=targetSeconds;sceneSeconds=targetSceneSeconds;seekPending=true;
        }
        @Override public void stop(){checkThread();stopped=true;}
        void synchronize(float delta){
            if(!exists())return;
            if(simulation==null){simulation=new ClaudeSimulation(asset.effect,asset.system.id(),transform,seed,effectWidth,scaleInput==0?null:scaleInput);if(finishing)simulation.stopEmission(finishClearLocal);}
            if(linkLength>=0)simulation.linkLength(linkLength);
            if(finishing&&!seekPending){seconds+=delta;if(Double.isFinite(sceneSeconds))sceneSeconds+=delta;}
            simulation.advance(Math.max(0,seconds-synchronizedSeconds),transform,Double.isFinite(sceneSeconds)?sceneSeconds:seconds);synchronizedSeconds=Math.max(synchronizedSeconds,seconds);seekPending=false;
        }
    }
    private static Vec3 direction(Matrix4f matrix,float x,float y,float z){var v=matrix.transformDirection(new Vector3f(x,y,z));return new Vec3(v.x,v.y,v.z);}
    private static Basis identityBasis(){return new Basis(new Vec3(1,0,0),new Vec3(0,1,0),new Vec3(0,0,1));}

    public static Prepared prepare(byte[] data,float magnification,Dependencies dependencies,PreparationCache cache)throws IOException{
        if(!Float.isFinite(magnification)||Math.abs(magnification-1)>1e-6)throw new IOException("Claude schema-v1 requires unit magnification");
        Objects.requireNonNull(cache,"cache");Map<String,DecodedEntry> textures=new LinkedHashMap<>();Map<String,ModelEntry> models=new LinkedHashMap<>();
        try{
            var json=JsonParser.parseString(new String(data,StandardCharsets.UTF_8)).getAsJsonObject();
            String id=json.has("portableVfxSystem")?json.remove("portableVfxSystem").getAsString():null;
            ClaudeEffect effect=cache.effect(json.toString());
            if(id==null){if(effect.systems().size()!=1)throw new IOException("Multi-system Claude effect requires portableVfxSystem selector");id=effect.systems().get(0).id();}
            String selected=id;var system=effect.systems().stream().filter(s->s.id().equals(selected)).findFirst().orElseThrow(()->new IOException("Unknown Claude system: "+selected));
            long pngBytes=0,rgbaBytes=0;Set<String> digests=new HashSet<>(),references=new LinkedHashSet<>();
            for(var layer:system.layers()){
                references.add(effect.materials().get(layer.material()).texture());
                if(layer.trail()!=null)references.add(effect.materials().get(layer.trail().material()).texture());
            }
            for(String reference:references){
                if(dependencies==null)throw new IOException("Claude PNG dependency reader is required");byte[] png=dependencies.read(reference);
                if(png==null)throw new IOException("Missing Claude texture: "+reference);pngBytes+=png.length;
                if(pngBytes>ClaudeTexture.MAX_ASSET_PNG_BYTES)throw new IOException("Claude encoded PNG budget exceeded");
                var entry=cache.acquire(png);textures.put(reference,entry);
                if(digests.add(entry.digest))rgbaBytes+=entry.decoded.rawBytes();
                if(rgbaBytes>ClaudeTexture.MAX_ASSET_RGBA_BYTES)throw new IOException("Claude decoded asset PNG budget exceeded");
            }
            Set<String> modelReferences=new LinkedHashSet<>();
            for(var layer:system.layers())if(layer.render().type().equals("model"))modelReferences.add(layer.render().model());
            long modelBytes=0;
            for(String reference:modelReferences){
                if(dependencies==null)throw new IOException("Model dependency reader required");var descriptor=effect.models().get(reference);
                ClaudeModelLoader.validatePath(descriptor.file());byte[] bytes=dependencies.read(descriptor.file());
                if(bytes==null||(modelBytes+=bytes.length)>32L*1024*1024)throw new IOException("Model encoded asset byte budget exceeded");
                var entry=cache.acquireModel(bytes);models.put(reference,entry);
                if(digests.add("model:"+entry.digest))rgbaBytes+=entry.model.decodedTextureBytes();
                if(rgbaBytes>ClaudeTexture.MAX_ASSET_RGBA_BYTES)throw new IOException("Model and PNG decoded asset byte budget exceeded");
                for(var clip:descriptor.clips().entrySet()){
                    var actual=entry.model.animations().get(clip.getKey());
                    if(actual==null||Math.abs(actual.duration()-clip.getValue().duration())>1e-4)
                        throw new IOException("Model clip missing or duration mismatch: "+clip.getKey());
                }
            }
            return new Prepared(effect,system,magnification,textures,models);
        }catch(IllegalArgumentException e){
            for(var entry:textures.values())entry.cache.release(entry);for(var entry:models.values())entry.cache.release(entry);
            throw new IOException("Unsupported/invalid Claude effect: "+e.getMessage(),e);
        }catch(IOException|RuntimeException|Error e){
            for(var entry:textures.values())entry.cache.release(entry);for(var entry:models.values())entry.cache.release(entry);throw e;
        }
    }
    /** Retains its own leases; callers must close the Prepared after installing or abandoning it. */
    public Asset installPrepared(Prepared prepared)throws IOException{
        checkThread();if(closed)throw new IOException("Claude backend is closed");
        synchronized(prepared){
            if(prepared.closed)throw new IOException("Claude preparation is closed");
            Map<String,Texture> textures=new LinkedHashMap<>();Map<String,ModelRuntime> models=new LinkedHashMap<>();
            try{
                for(var reference:prepared.textures.entrySet()){
                    var entry=reference.getValue();var texture=sharedTextures.get(entry.digest);
                    if(texture==null){
                        long bytes=entry.bytes();// GPU bytes (BC7 = 1 byte/pixel); pixels are dropped after upload, bytes stay budgeted
                        if(retainedTextureBytes+bytes>residentRgbaLimit)throw new CapacityException("Claude backend texture byte budget exceeded");
                        entry.cache.retain(entry);texture=new Texture(entry);sharedTextures.put(entry.digest,texture);retainedTextureBytes+=bytes;
                    }
                    texture.references++;textures.put(reference.getKey(),texture);preparationCaches.add(entry.cache);
                }
                for(var reference:prepared.models.entrySet()){
                    var entry=reference.getValue();var model=sharedModels.get(entry.digest);
                    if(model==null){if(retainedTextureBytes+entry.model.retainedBytes()>residentRgbaLimit)throw new CapacityException("Claude backend model byte budget exceeded");
                        entry.cache.retain(entry);model=new ModelRuntime(entry);sharedModels.put(entry.digest,model);retainedTextureBytes+=entry.model.retainedBytes();}
                    model.references++;models.put(reference.getKey(),model);preparationCaches.add(entry.cache);
                }
                var asset=new ClaudeAsset(prepared.effect,prepared.system,prepared.magnification,textures,models);assets.add(asset);assetGeneration++;return asset;
            }catch(IOException|RuntimeException|Error e){
                // Installation is CPU-only. Existing shared GPU objects keep their prior leases.
                for(var texture:textures.values())releaseTexture(texture);for(var model:models.values())releaseModel(model);throw e;
            }
        }
    }
    @Override public Asset load(byte[] data,float magnification,Dependencies dependencies)throws IOException{
        checkThread();try(var prepared=prepare(data,magnification,dependencies,fallbackPreparationCache)){return installPrepared(prepared);}
    }
    /** One bounded render-thread stage: program, post targets, or one unique PNG upload. */
    public boolean prewarmStep(int framebufferWidth,int framebufferHeight){
        checkThread();if(closed)throw new IllegalStateException("Claude backend is closed");
        // Targets above ClaudePostPass.MAX_PIXELS are rendered at a capped internal HDR size and upsampled.
        if(framebufferWidth<=0||framebufferHeight<=0)throw new IllegalArgumentException("Claude preload framebuffer must be positive");
        try(var ignored=new GlStateSnapshot()){
            if(program==0){initialize();return false;}
            if(!post.ready(framebufferWidth,framebufferHeight)){post.prewarm(framebufferWidth,framebufferHeight);return false;}
            if(needsWritableDepth()&&!post.depthPrepared()&&post.prewarmDepthFromBoundTarget())return false;
            for(var texture:sharedTextures.values())if(texture.id==0){texture.upload();return sharedModels.isEmpty()&&sharedTextures.values().stream().allMatch(t->t.id!=0);}
            for(var model:sharedModels.values())if(!model.gpu.ready()){modelRenderer.prewarmStep(model.gpu);return false;}
            return true;
        }
    }
    /** Cached per asset generation (install/close); not rescanned every frame. */
    private boolean needsWritableDepth(){
        if(depthGeneration!=assetGeneration){depthCached=assets.stream().anyMatch(a->!a.closed&&a.writesDepth);depthGeneration=assetGeneration;}
        return depthCached;
    }
    /** Call before the renderer's active-empty early return so the first valid world frame warms its real depth format. */
    public boolean prewarmWorldDepth(int framebuffer,int width,int height){
        checkThread();if(closed||!needsWritableDepth())return true;
        if(framebuffer<=0||width<=0||height<=0)return false;
        // Steady state: no GL queries or state snapshot. Format changes at a fixed size are re-matched in post.render.
        if(post.depthPrepared()&&post.ready(width,height))return true;
        try(var ignored=new GlStateSnapshot()){
            glBindFramebuffer(GL_DRAW_FRAMEBUFFER,framebuffer);
            if(!post.ready(width,height))post.prewarm(width,height);
            return post.prewarmDepthFromBoundTarget();
        }
    }
    public boolean worldDepthPending(){checkThread();return needsWritableDepth()&&!post.depthPrepared();}
    /** Counts are monotonic for this backend/cache lifetime; calls do not touch OpenGL. */
    public Diagnostics diagnostics(){
        checkThread();long decodes=0,hits=0,parses=0;
        for(var cache:preparationCaches){var d=cache.diagnostics();decodes+=d.decodeCount();hits+=d.cacheHits();parses+=d.parseCount();}
        return new Diagnostics(decodes,hits,parses,textureUploads+modelRenderer.textureUploads(),shaderLinks+post.shaderLinks()+modelRenderer.shaderLinks(),post.targetAllocations(),retainedTextureBytes,
            sharedTextures.size()+sharedModels.values().stream().mapToInt(m->m.gpu.images.length).sum(),(int)sharedTextures.values().stream().filter(t->t.id==0).count()+sharedModels.values().stream().mapToInt(m->(int)Arrays.stream(m.gpu.images).filter(i->i==0).count()).sum(),(int)assets.stream().filter(a->!a.closed).count());
    }
    @Override public Instance play(Asset raw,long seed){
        checkThread();prune();if(!(raw instanceof ClaudeAsset asset)||!assets.contains(asset)||asset.closed)throw new IllegalArgumentException("Foreign/closed Claude asset");
        if(instances.size()>=MAX_INSTANCES)return null;var instance=new ClaudeInstance(asset,seed);instances.add(instance);return instance;
    }
    @Override public void update(float deltaSeconds){
        checkThread();if(!Float.isFinite(deltaSeconds)||deltaSeconds<0)throw new IllegalArgumentException("Invalid Claude delta time");
        synchronizeInstances(deltaSeconds);prune();
    }
    /** Budget overflow is a per-instance outcome: the newest live instances are stopped until the frame fits; the backend stays healthy. */
    private void synchronizeInstances(float delta){
        int count=0;
        for(var instance:instances){instance.synchronize(delta);count+=liveParticles(instance);}
        for(int i=instances.size()-1;i>=0&&count>MAX_FRAME_PARTICLES;i--){
            var instance=instances.get(i);int live=liveParticles(instance);if(live==0)continue;
            instance.stop();count-=live;
            if(budgetStops++%64==0)LOG.warn("Claude live-particle budget ({}) exceeded; stopped newest effect instance ({} total budget stops)",MAX_FRAME_PARTICLES,budgetStops);
        }
    }
    private static int liveParticles(ClaudeInstance instance){return instance.simulation==null||!instance.exists()?0:instance.simulation.particleCount();}
    /** Instances stopped because the shared live-particle budget was exceeded; monotonic for this backend. */
    public long budgetStops(){return budgetStops;}
    private void prune(){instances.removeIf(i->!i.exists());assets.removeIf(a->a.closed);}
    private record Draw(ClaudeInstance instance,Layer layer,List<ParticleView> particles) { }
    @Override public void draw(float[] view,float[] projection,float fx,float fy,float fz,float cx,float cy,float cz){
        checkThread();prune();if(instances.isEmpty())return;synchronizeInstances(0);
        try(var ignored=new GlStateSnapshot()){
            initialize();
            Matrix4f vp=new Matrix4f().set(projection).mul(new Matrix4f().set(view));
            Matrix4f inverse=new Matrix4f().set(view).invert();
            Vec3 right=direction(inverse,1,0,0),up=direction(inverse,0,1,0),camera=new Vec3(cx,cy,cz);
            var groups=new LinkedHashMap<ClaudePostPass.Settings,List<Draw>>();
            for(var instance:instances){
                if(instance.simulation==null||!instance.exists()||instance.localOpacity<=0)continue;
                Map<Layer,List<ParticleView>> particles=new IdentityHashMap<>();
                for(var particle:instance.simulation.renderSnapshot())particles.computeIfAbsent(particle.layer(),k->new ArrayList<>()).add(particle);
                for(var layer:instance.asset.system.layers())if(particles.containsKey(layer))
                    groups.computeIfAbsent(instance.asset.postSettings,k->new ArrayList<>()).add(new Draw(instance,layer,particles.get(layer)));
            }
            post.beginFrame(groups.values().stream().flatMap(Collection::stream).anyMatch(d->
                d.instance.asset.effect.materials().get(d.layer.material()).depthWrite()
                ||d.layer.trail()!=null&&d.instance.asset.effect.materials().get(d.layer.trail().material()).depthWrite()));
            // Vertex budget truncates this frame's excess primitives (geometry/model skip); it never fails the backend.
            int[] remaining={MAX_FRAME_VERTICES};
            for(var entry:groups.entrySet()){
                // Java's stable sort preserves file/instance order for equal sortingFudge.
                entry.getValue().sort(Comparator.comparingDouble((Draw d)->d.layer.sortingFudge()).reversed());
                post.render(entry.getKey(),()->{
                    prepareGeometry(vp);
                    for(var draw:entry.getValue()){
                        if(draw.layer.render().type().equals("model")){
                            var descriptor=draw.instance.asset.effect.models().get(draw.layer.render().model());var model=draw.instance.asset.models.get(draw.layer.render().model());
                            if(model==null)throw new IllegalStateException("Missing prepared model: "+draw.layer.render().model());
                            var tint=draw.instance.asset.effect.materials().get(draw.layer.material()).tint();
                            for(var particle:draw.particles)remaining[0]-=modelRenderer.draw(model.gpu,descriptor,particle,tint,vp,draw.instance.worldOrigin,camera,remaining[0],draw.instance.localOpacity);
                            prepareGeometry(vp);
                        }else{
                            float[] vertices=ClaudeGeometry.particles(draw.instance.asset.effect,draw.particles,right,up,ClaudeGeometry.add(camera,draw.instance.worldOrigin),draw.instance.worldOrigin,remaining[0]);
                            remaining[0]-=vertices.length/ClaudeGeometry.STRIDE;
                            drawVertices(draw.instance.asset,draw.layer.material(),draw.layer.flipbook(),vertices,draw.instance.localOpacity);
                        }
                        if(draw.layer.trail()!=null){
                            float[] trail=ClaudeGeometry.trails(draw.particles,ClaudeGeometry.add(camera,draw.instance.worldOrigin),right,draw.instance.worldOrigin,remaining[0]);
                            remaining[0]-=trail.length/ClaudeGeometry.STRIDE;
                            drawVertices(draw.instance.asset,draw.layer.trail().material(),null,trail,draw.instance.localOpacity);
                        }
                    }
                });
            }
        }
    }
    private void prepareGeometry(Matrix4f vp){
        glUseProgram(program);glBindVertexArray(vao);glBindBuffer(GL_ARRAY_BUFFER,vbo);
        glUniformMatrix4fv(glGetUniformLocation(program,"viewProjection"),false,vp.get(new float[16]));
        glUniform1i(glGetUniformLocation(program,"colorTexture"),0);glActiveTexture(GL_TEXTURE0);glBindSampler(0,0);
        glUniform1i(glGetUniformLocation(program,"interpolateFlipbooks"),interpolateFlipbooks?1:0);
        glEnable(GL_DEPTH_TEST);glDepthFunc(GL_LEQUAL);glDepthMask(false);glDisable(GL_CULL_FACE);
        glEnable(GL_BLEND);glBlendEquationSeparate(GL_FUNC_ADD,GL_FUNC_ADD);glColorMask(true,true,true,true);
    }
    private void drawVertices(ClaudeAsset asset,String materialName,Flipbook flipbook,float[] vertices,float opacity){
        if(vertices.length==0)return;var material=asset.effect.materials().get(materialName);var tint=material.tint();
        boolean additive=material.blend().equals("additive");
        glDepthMask(material.depthWrite()&&opacity>=1);
        glUniform1f(glGetUniformLocation(program,"localOpacity"),opacity);
        glUniform1i(glGetUniformLocation(program,"additiveMaterial"),additive?1:0);
        glBlendFuncSeparate(GL_SRC_ALPHA,additive?GL_ONE:GL_ONE_MINUS_SRC_ALPHA,additive?GL_ZERO:GL_ONE,additive?GL_ONE:GL_ONE_MINUS_SRC_ALPHA);
        glUniform3f(glGetUniformLocation(program,"tint"),(float)tint.x(),(float)tint.y(),(float)tint.z());
        glUniform2f(glGetUniformLocation(program,"flipbookGrid"),flipbook==null?1:flipbook.columns(),flipbook==null?1:flipbook.rows());
        glBindTexture(GL_TEXTURE_2D,asset.textures.get(material.texture()).upload());
        glBufferData(GL_ARRAY_BUFFER,vertices,GL_STREAM_DRAW);glDrawArrays(GL_TRIANGLES,0,vertices.length/ClaudeGeometry.STRIDE);
    }
    private void initialize(){
        if(program!=0)return;if(!GL.getCapabilities().OpenGL33)throw new GpuFailureException("Claude backend requires OpenGL 3.3");
        program=link(VERTEX,FRAGMENT);shaderLinks++;vao=glGenVertexArrays();vbo=glGenBuffers();glBindVertexArray(vao);glBindBuffer(GL_ARRAY_BUFFER,vbo);
        int[] sizes={3,2,3,1,3};int offset=0;for(int i=0;i<sizes.length;i++){
            glEnableVertexAttribArray(i);glVertexAttribPointer(i,sizes[i],GL_FLOAT,false,ClaudeGeometry.STRIDE*Float.BYTES,(long)offset*Float.BYTES);offset+=sizes[i];
        }
    }
    static int link(String vertex,String fragment){
        int vs=compile(GL_VERTEX_SHADER,vertex),fs=0,linked=0;
        try{fs=compile(GL_FRAGMENT_SHADER,fragment);linked=glCreateProgram();glAttachShader(linked,vs);glAttachShader(linked,fs);glLinkProgram(linked);
            if(glGetProgrami(linked,GL_LINK_STATUS)==GL_FALSE)throw new GpuFailureException(glGetProgramInfoLog(linked));return linked;
        }catch(RuntimeException|Error e){if(linked!=0)glDeleteProgram(linked);throw e;}
        finally{glDeleteShader(vs);if(fs!=0)glDeleteShader(fs);}
    }
    private static int compile(int type,String source){
        int shader=glCreateShader(type);glShaderSource(shader,source);glCompileShader(shader);
        if(glGetShaderi(shader,GL_COMPILE_STATUS)==GL_FALSE){String log=glGetShaderInfoLog(shader);glDeleteShader(shader);throw new GpuFailureException(log);}return shader;
    }
    @Override public void stopAll(){checkThread();for(var instance:instances)instance.stop();instances.clear();}
    @Override public void close(){
        checkThread();if(closed)return;closed=true;stopAll();for(var asset:assets)asset.close();assets.clear();assetGeneration++;fallbackPreparationCache.close();
        if(program==0&&vao==0&&vbo==0&&!post.initialized()&&!modelRenderer.initialized())return;
        try(var ignored=new GlStateSnapshot()){post.close();modelRenderer.close();if(program!=0)glDeleteProgram(program);if(vao!=0)glDeleteVertexArrays(vao);if(vbo!=0)glDeleteBuffers(vbo);program=vao=vbo=0;}
    }
    private void checkThread(){if(Thread.currentThread()!=owner)throw new IllegalStateException("Claude backend must run on its owning render thread");}
}
