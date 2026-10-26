package school.magiccodex.client;

import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.texture.*;
import net.minecraft.client.render.RenderLayer;
import net.minecraft.util.Identifier;
import org.joml.Matrix4f;
import org.joml.Vector3f;

/**
 * Decode off-thread, upload one texture/frame. 펫 창 미리보기는 모델 3개까지, 월드 동행 펫({@link PetCompanions})은 24개까지 들고 있는다.
 * 월드 그리기({@link #drawWorld})는 GUI 와 같은 텍스처·포즈 계산을 쓰되 매 프레임 새 객체를 만들지 않는다.
 */
final class PetModelRenderer implements AutoCloseable {
    private static final ExecutorService WORKER=Executors.newSingleThreadExecutor(r->{var t=new Thread(r,"pet-model-loader");t.setDaemon(true);return t;});
    private final LinkedHashMap<String,Loaded> cache=new LinkedHashMap<>(4,.75f,true);
    private final int capacity;
    PetModelRenderer(){this(3);}
    PetModelRenderer(int capacity){this.capacity=Math.max(1,capacity);}
    private final MinecraftClient client=MinecraftClient.getInstance();
    private static int serial;
    private record Prepared(PetBbModel model,List<NativeImage> images){}
    private static final class Loaded{
        final CompletableFuture<Prepared> future;final List<Identifier> textures=new ArrayList<>();
        String error="";int upload;boolean checked;long used;
        Loaded(Path root,String id){
            future=CompletableFuture.supplyAsync(()->{
                var images=new ArrayList<NativeImage>();
                try{var model=PetBbModel.load(root,id);for(var t:model.textures)images.add(NativeImage.read(t.png()));return new Prepared(model,images);}
                catch(Exception e){images.forEach(NativeImage::close);throw new CompletionException(e);}
            },WORKER);
        }
    }
    String status(String id){var value=cache.get(id);if(value==null||!value.future.isDone())return "모델을 불러오는 중…";if(!value.error.isEmpty())return value.error;return "";}
    private Loaded get(String id){
        long now=net.minecraft.util.Util.getMeasuringTimeMs();
        var value=cache.get(id);if(value!=null){value.used=now;return value;}
        // 월드용(큰 캐시): 꽉 찼는데 가장 오래된 것도 방금(1초 안) 쓰였으면 새로 읽지 않는다 — 서로 밀어내며 매 프레임 다시 읽는 일을 막음.
        if(capacity>3&&cache.size()>=capacity&&now-cache.values().iterator().next().used<1000)return null;
        value=new Loaded(PetClient.modelRoot(),id);value.used=now;cache.put(id,value);
        while(cache.size()>capacity){var first=cache.entrySet().iterator().next();cache.remove(first.getKey());dispose(first.getValue());}
        return value;
    }
    /** 다 읽고 텍스처까지 올라간 모델, 아니면 null (이번 프레임에 텍스처 한 장을 올렸을 때도 null). */
    private Prepared ready(String id,Loaded value){
        if(value==null||!value.future.isDone()||!value.error.isEmpty())return null;
        Prepared prepared;
        try{prepared=value.future.join();}catch(CompletionException e){
            value.error=e.getCause() instanceof NoSuchFileException?"missing": "모델을 읽지 못했습니다";
            if(!(e.getCause() instanceof NoSuchFileException))org.slf4j.LoggerFactory.getLogger("magiccodex").warn("Pet model {}: {}",id,e.getCause().toString());return null;
        }
        if(value.upload<prepared.images.size()){
            NativeImage image=prepared.images.get(value.upload);
            var texture=new NativeImageBackedTexture(image);texture.setFilter(false,false);
            var textureId=Identifier.of("magiccodex","pet_model/"+serial++);
            client.getTextureManager().registerTexture(textureId,texture);
            // Keep native PNG pixels; registering/loading must not re-enable linear filtering.
            texture.setFilter(false,false);
            prepared.images.set(value.upload,null);value.textures.add(textureId);value.upload++;
            return null;
        }
        if(!value.checked){value.checked=true;for(String warning:prepared.model.warnings)org.slf4j.LoggerFactory.getLogger("magiccodex").warn("Pet model {}: {}",id,warning);}
        return prepared;
    }
    /** 월드용: 모델이 준비됐으면 돌려줌 (없거나 읽는 중이면 null). 크기·발밑 계산에 씀. */
    PetBbModel model(String id){Loaded value=get(id);Prepared p=ready(id,value);return p==null?null:p.model;}
    private void dispose(Loaded loaded){
        for(var texture:loaded.textures)client.getTextureManager().destroyTexture(texture);
        loaded.textures.clear();
        loaded.future.thenAccept(p->client.execute(()->{for(var im:p.images)if(im!=null)im.close();p.images.clear();}));
    }
    boolean draw(DrawContext c,String id,float cx,float cy,float size,float yaw,double time){
        Loaded value=get(id);Prepared prepared=ready(id,value);if(prepared==null)return false;
        var model=prepared.model;
        float animationTime=model.length>0?(float)(time%model.length):0;
        Map<String,Map<String,float[]>> pose=new HashMap<>();
        for(var track:model.tracks)pose.computeIfAbsent(track.node(),k->new HashMap<>()).put(track.channel(),PetBbModel.sample(track.keys(),animationTime));
        Matrix4f view=new Matrix4f().rotateX((float)Math.toRadians(9)).rotateY((float)Math.toRadians(yaw)).translate(-model.center[0],-model.center[1],-model.center[2]);
        var faces=new ArrayList<Quad>();for(var root:model.roots)collect(root,new Matrix4f(),view,pose,model,faces);
        faces.sort(Comparator.comparingDouble(Quad::depth));float scale=size/model.span;
        var matrix=new Matrix4f(c.getMatrices().peek().getPositionMatrix());
        c.draw(provider->{
            RenderLayer previousLayer=null;
            for(var face:faces){
                var layer=RenderLayer.getGuiTextured(value.textures.get(face.texture));
                // GUI quads have no model depth buffer: retain painter order across texture layers.
                if(previousLayer!=null&&previousLayer!=layer&&provider instanceof net.minecraft.client.render.VertexConsumerProvider.Immediate immediate)immediate.draw();
                previousLayer=layer;
                var v=provider.getBuffer(layer);
                int[] order=area(face.points)>0?new int[]{3,2,1,0}:new int[]{0,1,2,3};
                for(int i:order){var pt=face.points[i];v.vertex(matrix,cx+pt.x*scale,cy-pt.y*scale,0).texture(face.uv[i][0],face.uv[i][1]).color(face.color);}
            }
        });
        return true;
    }
    private record Quad(int texture,Vector3f[] points,float[][] uv,float depth,int color){}
    private static float area(Vector3f[] p){return (p[1].x-p[0].x)*(-(p[2].y-p[0].y))-(-(p[1].y-p[0].y))*(p[2].x-p[0].x);}
    private static Matrix4f rotate(Matrix4f m,float[] origin,float[] rotation,float[] pos,float[] scale){
        return m.translate(pos[0],pos[1],pos[2]).translate(origin[0],origin[1],origin[2])
            .rotateZYX((float)Math.toRadians(rotation[2]),(float)Math.toRadians(rotation[1]),(float)Math.toRadians(rotation[0]))
            .scale(scale[0],scale[1],scale[2]).translate(-origin[0],-origin[1],-origin[2]);
    }
    private static void collect(PetBbModel.Node n,Matrix4f parent,Matrix4f view,Map<String,Map<String,float[]>> pose,PetBbModel model,List<Quad> out){
        var channels=pose.getOrDefault(n.id(),Map.of());float[] r=n.rotation().clone(),extra=channels.getOrDefault("rotation",new float[]{0,0,0});
        for(int i=0;i<3;i++)r[i]+=extra[i];
        Matrix4f matrix=rotate(new Matrix4f(parent),n.origin(),r,channels.getOrDefault("position",new float[]{0,0,0}),channels.getOrDefault("scale",new float[]{1,1,1}));
        if(n.cube()!=null){
            var cube=n.cube();var cm=rotate(new Matrix4f(matrix),cube.origin(),cube.rotation(),new float[]{0,0,0},new float[]{1,1,1});var transform=new Matrix4f(view).mul(cm);
            for(var face:cube.faces()){
                Vector3f[] p=new Vector3f[4];float depth=0;
                for(int i=0;i<4;i++){float[] q=face.points()[i];p[i]=transform.transformPosition(new Vector3f(q[0],q[1],q[2]));depth+=p[i].z/4;}
                var tex=model.textures.get(face.texture());float[] u=face.uv();
                float[][] raw={{u[0]/tex.uvWidth(),u[1]/tex.uvHeight()},{u[2]/tex.uvWidth(),u[1]/tex.uvHeight()},{u[2]/tex.uvWidth(),u[3]/tex.uvHeight()},{u[0]/tex.uvWidth(),u[3]/tex.uvHeight()}};
                float[][] uv=new float[4][];for(int i=0;i<4;i++)uv[i]=raw[(i+face.rotation())%4];
                Vector3f normal=new Vector3f(p[1]).sub(p[0]).cross(new Vector3f(p[2]).sub(p[0]));
                float len=normal.length();if(len<.0001f)continue;normal.div(len);
                int shade=(int)(255*(.70+.30*Math.abs(normal.y*.7+normal.z*.45-normal.x*.2)));
                shade=Math.clamp(shade,100,255);out.add(new Quad(face.texture(),p,uv,depth,0xff000000|shade<<16|shade<<8|shade));
            }
        }
        for(var child:n.children())collect(child,matrix,view,pose,model,out);
    }
    // ------------------------------------------------------------------ 월드 (동행 펫)

    private final Matrix4f[] nodeStack=new Matrix4f[67];
    private final Matrix4f cubeMatrix=new Matrix4f();
    private final float[] rot=new float[3],pos=new float[3],scl=new float[3];
    private final Vector3f[] corner={new Vector3f(),new Vector3f(),new Vector3f(),new Vector3f()};
    private final Vector3f edgeA=new Vector3f(),edgeB=new Vector3f();
    private static final float[] ZERO={0,0,0},ONE={1,1,1};

    /**
     * 월드에 모델 하나를 그린다. {@code base} 는 펫 발밑(카메라 기준)에 놓이고 몸 방향으로 돌아간 행렬 — 여기서는 모델 앞(-Z)이
     * base 의 -Z 를 보게, 가장 긴 변이 {@code size} 블록이 되게, 바닥이 y=0 에 오게 줄여 그린다.
     * @return 그렸으면 true (읽는 중·없는 모델이면 false)
     */
    boolean drawWorld(Matrix4f base,net.minecraft.client.render.VertexConsumerProvider out,String id,int kind,float time,float size,int light){
        Loaded value=get(id);Prepared prepared=ready(id,value);if(prepared==null)return false;
        var model=prepared.model;var clip=model.clip(kind);
        float t=clip.length()>0?time%clip.length():0;
        float s=size/model.span;
        if(nodeStack[0]==null)for(int i=0;i<nodeStack.length;i++)nodeStack[i]=new Matrix4f();
        // 픽셀 단위 모델 → 블록: 줄이고, 가로 가운데·바닥을 원점으로.
        nodeStack[0].set(base).scale(s).translate(-model.center[0],-model.bottom,-model.center[2]);
        // 텍스처마다 한 번씩 돌며 같은 레이어끼리 모아 그린다 (보통 텍스처 1장).
        for(int tex=0;tex<value.textures.size();tex++){
            var vc=out.getBuffer(RenderLayer.getEntityCutoutNoCull(value.textures.get(tex)));
            for(var root:model.roots)emit(root,0,clip,t,model,tex,vc,light);
        }
        return true;
    }
    private void emit(PetBbModel.Node n,int depth,PetBbModel.Clip clip,float time,PetBbModel model,int tex,net.minecraft.client.render.VertexConsumer vc,int light){
        if(depth+1>=nodeStack.length)return;
        PetBbModel.Track[] tracks=clip.byNode().get(n.id());
        float[] r=n.rotation();rot[0]=r[0];rot[1]=r[1];rot[2]=r[2];
        float[] p=ZERO,sc=ONE;
        if(tracks!=null){
            if(tracks[0]!=null){PetBbModel.sample(tracks[0].keys(),time,scl);rot[0]+=scl[0];rot[1]+=scl[1];rot[2]+=scl[2];}
            if(tracks[1]!=null)p=PetBbModel.sample(tracks[1].keys(),time,pos);
            if(tracks[2]!=null)sc=PetBbModel.sample(tracks[2].keys(),time,scl);
        }
        Matrix4f m=nodeStack[depth+1].set(nodeStack[depth]);
        float[] o=n.origin();
        m.translate(p[0],p[1],p[2]).translate(o[0],o[1],o[2])
            .rotateZYX((float)Math.toRadians(rot[2]),(float)Math.toRadians(rot[1]),(float)Math.toRadians(rot[0]))
            .scale(sc[0],sc[1],sc[2]).translate(-o[0],-o[1],-o[2]);
        if(n.cube()!=null){
            var cube=n.cube();float[] co=cube.origin(),cr=cube.rotation();
            cubeMatrix.set(m).translate(co[0],co[1],co[2])
                .rotateZYX((float)Math.toRadians(cr[2]),(float)Math.toRadians(cr[1]),(float)Math.toRadians(cr[0]))
                .translate(-co[0],-co[1],-co[2]);
            for(var face:cube.faces()){
                if(face.texture()!=tex)continue;
                for(int i=0;i<4;i++){float[] q=face.points()[i];cubeMatrix.transformPosition(q[0],q[1],q[2],corner[i]);}
                edgeA.set(corner[1]).sub(corner[0]);edgeB.set(corner[2]).sub(corner[0]);edgeA.cross(edgeB);
                float len=edgeA.length();if(len<1e-7f)continue;edgeA.div(-len);   // 면 점 순서상 안쪽을 향하므로 뒤집어 바깥 법선
                var texInfo=model.textures.get(face.texture());float[] u=face.uv();
                float u0=u[0]/texInfo.uvWidth(),v0=u[1]/texInfo.uvHeight(),u1=u[2]/texInfo.uvWidth(),v1=u[3]/texInfo.uvHeight();
                for(int i=0;i<4;i++){
                    int k=(i+face.rotation())%4;   // GUI 와 같은 UV 회전
                    float uu=k==0||k==3?u0:u1,vv=k<2?v0:v1;
                    float[] q=face.points()[i];
                    vc.vertex(cubeMatrix,q[0],q[1],q[2]).color(0xFFFFFFFF).texture(uu,vv).overlay(net.minecraft.client.render.OverlayTexture.DEFAULT_UV).light(light).normal(edgeA.x,edgeA.y,edgeA.z);
                }
            }
        }
        for(var child:n.children())emit(child,depth+1,clip,time,model,tex,vc,light);
    }
    @Override public void close(){cache.values().forEach(this::dispose);cache.clear();}
}
