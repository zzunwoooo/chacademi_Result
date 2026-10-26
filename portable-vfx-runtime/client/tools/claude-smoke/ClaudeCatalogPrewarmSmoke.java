import dev.portablevfx.client.claude.*;
import dev.portablevfx.client.definition.*;
import dev.portablevfx.client.render.EffectBackend;
import dev.portablevfx.client.render.gl.WorldTargetPass;
import java.nio.file.*;
import java.util.*;
import org.joml.Matrix4f;
import org.lwjgl.egl.EGL;
import org.lwjgl.opengl.GL;
import org.lwjgl.system.MemoryStack;
import static org.lwjgl.egl.EGL14.*;
import static org.lwjgl.opengl.GL33C.*;

/** Actual complete-catalog pinned CPU/GPU proof; never downloads or modifies source assets. */
public final class ClaudeCatalogPrewarmSmoke {
 public static void main(String[] args)throws Exception {
  long started=System.nanoTime();
  var config=ClaudeConfigLoader.load(Path.of(args[0]),LoadLimits.DEFAULT);
  if(!config.errors().isEmpty()||config.definitions().size()!=235)throw new AssertionError("Expected235 definitions/errors0: "+config.definitions().size()+" / "+config.errors());
  System.out.println("ADMISSION definitions="+config.definitions().size()+" encodedBytes="+config.assets().values().stream().mapToLong(a->a.length).sum());
  try(var stack=MemoryStack.stackPush()){
   long display=eglGetDisplay(EGL_DEFAULT_DISPLAY);var major=stack.mallocInt(1);var minor=stack.mallocInt(1);
   if(!eglInitialize(display,major,minor))throw new IllegalStateException("EGL init");EGL.createDisplayCapabilities(display,major.get(0),minor.get(0));
   var configs=stack.mallocPointer(1);var count=stack.mallocInt(1);
   if(!eglChooseConfig(display,stack.ints(EGL_SURFACE_TYPE,EGL_PBUFFER_BIT,EGL_RENDERABLE_TYPE,EGL_OPENGL_BIT,EGL_RED_SIZE,8,EGL_GREEN_SIZE,8,EGL_BLUE_SIZE,8,EGL_DEPTH_SIZE,24,EGL_NONE),configs,count))throw new IllegalStateException("EGL config");
   eglBindAPI(EGL_OPENGL_API);long context=eglCreateContext(display,configs.get(0),EGL_NO_CONTEXT,stack.ints(0x3098,3,0x30FB,3,0x30FD,1,EGL_NONE));
   long surface=eglCreatePbufferSurface(display,configs.get(0),stack.ints(EGL_WIDTH,64,EGL_HEIGHT,64,EGL_NONE));
   if(!eglMakeCurrent(display,surface,surface,context))throw new IllegalStateException("EGL current");GL.createCapabilities();
   System.out.println("GL="+glGetString(GL_VERSION)+" / "+glGetString(GL_RENDERER));
   int world=glGenFramebuffers(),color=glGenRenderbuffers(),depth=glGenRenderbuffers();glBindFramebuffer(GL_FRAMEBUFFER,world);
   glBindRenderbuffer(GL_RENDERBUFFER,color);glRenderbufferStorage(GL_RENDERBUFFER,GL_RGBA8,64,64);glFramebufferRenderbuffer(GL_FRAMEBUFFER,GL_COLOR_ATTACHMENT0,GL_RENDERBUFFER,color);
   glBindRenderbuffer(GL_RENDERBUFFER,depth);glRenderbufferStorage(GL_RENDERBUFFER,GL_DEPTH_COMPONENT24,64,64);glFramebufferRenderbuffer(GL_FRAMEBUFFER,GL_DEPTH_ATTACHMENT,GL_RENDERBUFFER,depth);
   var assets=new ArrayList<EffectBackend.Asset>();
   try(var cache=new ClaudeBackend.PreparationCache(LoadLimits.DEFAULT.prewarmRgbaBytes());var backend=new ClaudeBackend(LoadLimits.DEFAULT.residentRgbaBytes())){
    int n=0;
    for(var definition:config.definitions()){
     String asset=definition.asset().substring("claude:".length());
     try(var prepared=ClaudeBackend.prepare(config.assets().get(asset),1,ref->{
      byte[] value=config.assets().get(AssetPath.dependency(asset,ref));if(value==null)throw new java.io.IOException("Missing "+ref);return value;
     },cache)){assets.add(backend.installPrepared(prepared));}
     if(++n%25==0||n==235)System.out.println("PINNED "+n+" cache="+cache.diagnostics().retainedBytes()+" resident="+backend.diagnostics().retainedTextureBytes());
    }
    long cpuDone=System.nanoTime();System.out.println("ALL_PINNED "+cache.diagnostics()+" / "+backend.diagnostics()+" / elapsedMs="+(cpuDone-started)/1_000_000);
    int steps=0;while(!backend.prewarmStep(64,64)){if(++steps>10000)throw new AssertionError("Prewarm stage bound");}
    if(!backend.prewarmWorldDepth(world,64,64)||backend.worldDepthPending())throw new AssertionError("Depth prewarm incomplete");
    var before=backend.diagnostics();System.out.println("ALL_GPU_READY steps="+steps+" gpuMs="+(System.nanoTime()-cpuDone)/1_000_000+" "+before);cache.close();
    float[] view=new Matrix4f().lookAt(0,2,5,0,0,0,0,1,0).get(new float[16]);float[] projection=new Matrix4f().perspective((float)Math.toRadians(60),1,.01f,100).get(new float[16]);
    int draws=0;
    for(int i=0;i<assets.size();i++){
     var instance=backend.play(assets.get(i),123);instance.transform(0,0,0,0,0,0,1);
     if(config.definitions().get(i).role().equals("link"))instance.parameters(0,2);
     instance.seek(.05f);backend.update(.05f);
     glBindFramebuffer(GL_FRAMEBUFFER,world);glDepthMask(true);glClearDepth(1);glClearColor(0,0,0,1);glClear(GL_COLOR_BUFFER_BIT|GL_DEPTH_BUFFER_BIT);
     try(var pass=WorldTargetPass.begin(world,64,64)){backend.draw(view,projection,0,-2,-5,0,2,5);}backend.stopAll();draws++;
     var now=backend.diagnostics();if(now.decodeCount()!=before.decodeCount()||now.textureUploads()!=before.textureUploads()||now.shaderLinks()!=before.shaderLinks()||now.targetAllocations()!=before.targetAllocations())throw new AssertionError("Cold cast work at "+config.definitions().get(i).id()+": "+now);
     if(glGetError()!=GL_NO_ERROR)throw new AssertionError("Draw GL error at "+config.definitions().get(i).id());
    }
    for(var asset:assets)asset.close();
    if(cache.diagnostics().retainedBytes()!=0||backend.diagnostics().retainedTextureBytes()!=0)throw new AssertionError("Final CPU/GPU ownership leak");
    System.out.println("PASS full235 startup: casts="+draws+" firstCast decode/upload/shader/target deltas=0; final retained CPU/resident=0; totalMs="+(System.nanoTime()-started)/1_000_000);
   }
   glBindFramebuffer(GL_FRAMEBUFFER,0);glDeleteFramebuffers(world);glDeleteRenderbuffers(color);glDeleteRenderbuffers(depth);
   if(glGetError()!=GL_NO_ERROR)throw new AssertionError("Cleanup GL error");eglMakeCurrent(display,EGL_NO_SURFACE,EGL_NO_SURFACE,EGL_NO_CONTEXT);eglDestroySurface(display,surface);eglDestroyContext(display,context);eglTerminate(display);
  }
 }
}
