package dev.portablevfx.client.claude;

import com.google.gson.JsonParser;
import dev.portablevfx.client.render.EffectBackend;
import dev.portablevfx.client.render.gl.GlStateSnapshot;
import dev.portablevfx.client.render.gl.WorldTargetPass;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import org.joml.Matrix4f;
import org.lwjgl.BufferUtils;
import static org.lwjgl.opengl.GL33C.*;

/** Loading-screen prewarm proof, including exact cold/prewarmed pixels for all six systems. */
public final class ClaudePreloadGlTest {
    private record Source(String name,Path folder,byte[] bytes) { }
    private static final String[] SCENES={"Fireball/projectile","Fireball/impact","FeatherFall/aura","TidalWave/wave","TidalWave/collapse","TidalWave/splash"};
    public static void run(Path samples,int world,int width,int height)throws Exception{
        try(var ignored=new GlStateSnapshot()){
            List<Source> sources=new ArrayList<>();
            for(String name:SCENES){
                Path folder=samples.resolve(name.split("/")[0]);var json=JsonParser.parseString(Files.readString(folder.resolve("vfx.json"))).getAsJsonObject();
                json.addProperty("portableVfxSystem",name.split("/")[1]);sources.add(new Source(name,folder,json.toString().getBytes(StandardCharsets.UTF_8)));
            }
            List<byte[]> coldPixels=new ArrayList<>();long coldLoad=0,coldFirst=0;
            try(var cold=new ClaudeBackend()){
                List<EffectBackend.Asset> assets=new ArrayList<>();long start=System.nanoTime();
                for(var source:sources)assets.add(cold.load(source.bytes,1,p->Files.readAllBytes(source.folder.resolve(p))));
                coldLoad=System.nanoTime()-start;
                for(int i=0;i<assets.size();i++){
                    start=System.nanoTime();coldPixels.add(render(cold,assets.get(i),sources.get(i).name,world,width,height));coldFirst+=System.nanoTime()-start;
                }
                var d=cold.diagnostics();check(d.textureUploads()>0&&d.textureUploads()<=30&&d.shaderLinks()==2&&d.targetAllocations()==14,"Cold first play performs texture/shader/target work: "+d);
                System.out.println("PRELOAD cold baseline loadMs="+ms(coldLoad)+" sixFirstDrawMs="+ms(coldFirst)+" "+d);
            }
            var cache=new ClaudeBackend.PreparationCache(64L*1024*1024);var backend=new ClaudeBackend();AtomicInteger reads=new AtomicInteger();
            List<ClaudeBackend.Prepared> prepared=new ArrayList<>();List<EffectBackend.Asset> assets=new ArrayList<>();
            try{
                long start=System.nanoTime();
                // A real worker thread without a GL context owns all schema parsing/PNG decoding.
                prepared.addAll(CompletableFuture.supplyAsync(()->{
                    List<ClaudeBackend.Prepared> result=new ArrayList<>();
                    try{
                        for(var source:sources)result.add(ClaudeBackend.prepare(source.bytes,1,p->{reads.incrementAndGet();return Files.readAllBytes(source.folder.resolve(p));},cache));
                        return result;
                    }catch(Exception failure){for(var item:result)item.close();throw new RuntimeException(failure);}
                }).get());
                long prepare=System.nanoTime()-start;
                for(var item:prepared){assets.add(backend.installPrepared(item));item.close();}prepared.clear();cache.close();
                check(backend.diagnostics().retainedTextureBytes()==("off".equals(System.getProperty("portablevfx.claude.textureCompression"))?58_195_968L:16_416_768L),"All sample data fits shared64MiB cap (BC7 16.4 MB, RGBA 58.2 MB)");
                check(backend.diagnostics().pendingTextures()==30&&backend.diagnostics().textureUploads()==0,"CPU install has no texture uploads");
                // A loading screen has no retained world depth. Also exercise inherited hostile state.
                glBindFramebuffer(GL_FRAMEBUFFER,0);glViewport(11,13,7,9);glActiveTexture(GL_TEXTURE7);
                glPixelStorei(GL_UNPACK_ROW_LENGTH,17);glPixelStorei(GL_UNPACK_SKIP_PIXELS,2);glEnable(GL_SCISSOR_TEST);
                start=System.nanoTime();int steps=0;boolean ready;
                do{
                    long uploads=backend.diagnostics().textureUploads();ready=backend.prewarmStep(width,height);steps++;
                    check(backend.diagnostics().textureUploads()-uploads<=1,"A preload step uploads at most one PNG");
                    int[] viewport=new int[4];glGetIntegerv(GL_VIEWPORT,viewport);
                    check(glGetInteger(GL_DRAW_FRAMEBUFFER_BINDING)==0&&Arrays.equals(viewport,new int[]{11,13,7,9})
                        &&glGetInteger(GL_ACTIVE_TEXTURE)==GL_TEXTURE7&&glGetInteger(GL_UNPACK_ROW_LENGTH)==17
                        &&glGetInteger(GL_UNPACK_SKIP_PIXELS)==2&&glIsEnabled(GL_SCISSOR_TEST),"Every prewarm stage restores host GL state");
                    check(steps<=32,"Preload work must finish after two stages plus30unique textures");
                }while(!ready);
                long gpu=System.nanoTime()-start;int dependencyReads=reads.get();var warm=backend.diagnostics();
                check(steps==32&&warm.decodeCount()==30&&warm.parseCount()==3&&warm.textureUploads()==30&&warm.shaderLinks()==2&&warm.targetAllocations()==14,"Exactly one cold preparation/allocation per unique resource");
                long warmDraw=0;
                for(int i=0;i<assets.size();i++){
                    start=System.nanoTime();byte[] pixels=render(backend,assets.get(i),sources.get(i).name,world,width,height);warmDraw+=System.nanoTime()-start;
                    check(Arrays.equals(coldPixels.get(i),pixels),"Preloading must preserve exact alpha4 output: "+sources.get(i).name);
                    unchanged(warm,backend.diagnostics());
                }
                check(reads.get()==dependencyReads,"First casts do not perform dependency reads");
                // render stops only instances; simulates clear/disconnect and tests warm reconnect.
                long replayStart=System.nanoTime();
                check(Arrays.equals(coldPixels.get(0),render(backend,assets.get(0),sources.get(0).name,world,width,height)),"Replay retains identical pixels after stopAll");
                unchanged(warm,backend.diagnostics());
                System.out.println("PRELOAD warm prepareMs="+ms(prepare)+" gpuMs="+ms(gpu)+" stages="+steps+" sixFirstDrawMs="+ms(warmDraw)+" replayMs="+ms(System.nanoTime()-replayStart)+" "+backend.diagnostics());
                System.out.println("PRELOAD firstCastDelta decode=0 dependencyRead=0 upload=0 shader=0 target=0; sixColdVsWarmImages=byte-identical; stopAllReplay=warm");
                List<Integer> textureIds=textureIds(backend);
                for(var asset:assets)asset.close();
                check(backend.diagnostics().textureCount()==0&&backend.diagnostics().retainedTextureBytes()==0&&cache.diagnostics().retainedBytes()==0,"Last shared asset releases GPU/CPU leases");
                for(int id:textureIds)check(!glIsTexture(id),"Released sample texture is deleted from GL");
                // Resize rebuilds only post targets, exactly once, without a world depth attachment.
                glBindFramebuffer(GL_FRAMEBUFFER,0);check(!backend.prewarmStep(width/2,height/2),"Resize allocates before world draw");
                check(backend.prewarmStep(width/2,height/2),"Second same-size preload does no allocation");
                check(backend.diagnostics().targetAllocations()==28&&backend.diagnostics().shaderLinks()==2,"Resize rebuilds targets but not shaders");
            }finally{
                for(var item:prepared)item.close();backend.close();cache.close();
            }
            check(glGetError()==GL_NO_ERROR,"Preload/cleanup leaves no GL error");
            System.out.println("PASS ClaudePreloadGlTest: background CPU,32 bounded stages,exact6scene pixels,zero firstcast/replay cachework,GL/CPU release,resize");
        }
    }
    private static void unchanged(ClaudeBackend.Diagnostics before,ClaudeBackend.Diagnostics after){
        check(before.decodeCount()==after.decodeCount()&&before.textureUploads()==after.textureUploads()&&before.shaderLinks()==after.shaderLinks()&&before.targetAllocations()==after.targetAllocations(),"Firstcast/replay must not repeat decode/upload/shader/target work");
    }
    private static List<Integer> textureIds(ClaudeBackend backend)throws Exception{
        var field=ClaudeBackend.class.getDeclaredField("sharedTextures");field.setAccessible(true);List<Integer> ids=new ArrayList<>();
        for(Object texture:((Map<?,?>)field.get(backend)).values()){var id=texture.getClass().getDeclaredField("id");id.setAccessible(true);ids.add(id.getInt(texture));}return ids;
    }
    private static byte[] render(ClaudeBackend backend,EffectBackend.Asset asset,String name,int world,int width,int height){
        backend.stopAll();var handle=backend.play(asset,123);handle.transform(0,0,0,0,0,0,1);
        if(name.startsWith("TidalWave/")&&!name.endsWith("/splash"))handle.effectWidth(14);
        if(name.endsWith("/projectile")||name.endsWith("/wave")||name.endsWith("/aura")){
            for(int frame=1;frame<=16;frame++){handle.transform(-2+frame*.25f,1,0,0,0,0,1);handle.basis(new float[]{0,0,-1,0,1,0,1,0,0});handle.sceneTime(10+frame*.05);handle.seek(frame*.05f);backend.update(.05f);}
        }else{handle.seek(.2f);backend.update(.2f);}
        glBindFramebuffer(GL_FRAMEBUFFER,world);glDisable(GL_SCISSOR_TEST);glDisable(GL_RASTERIZER_DISCARD);glColorMask(true,true,true,true);glDepthMask(true);
        glClearDepth(1);glClearColor(0,0,0,1);glClear(GL_COLOR_BUFFER_BIT|GL_DEPTH_BUFFER_BIT);
        float cx=10,cy=6,cz=18;float[] view=new Matrix4f().lookAt(cx,cy,cz,0,1,0,0,1,0).get(new float[16]);
        float[] projection=new Matrix4f().perspective((float)Math.toRadians(50),1,.1f,100).get(new float[16]);
        try(var pass=WorldTargetPass.begin(world,width,height)){backend.draw(view,projection,-cx,-cy,-cz,cx,cy,cz);}
        var pixels=BufferUtils.createByteBuffer(width*height*4);glPixelStorei(GL_PACK_ROW_LENGTH,0);glPixelStorei(GL_PACK_SKIP_PIXELS,0);glReadPixels(0,0,width,height,GL_RGBA,GL_UNSIGNED_BYTE,pixels);
        byte[] result=new byte[pixels.remaining()];pixels.get(result);return result;
    }
    private static long ms(long nanos){return Math.round(nanos/1_000_000.0);}
    private static void check(boolean value,String message){if(!value)throw new AssertionError(message);}
}
