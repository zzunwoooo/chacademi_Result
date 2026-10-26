package dev.portablevfx.client.claude;
import dev.portablevfx.client.render.gl.GlStateSnapshot;
import org.lwjgl.BufferUtils;
import static org.lwjgl.opengl.GL33C.*;
/** Writable effect depth is copied once per frame, shared between groups, never written to world. */
public final class ClaudeDepthGlTest {
 public static void run(){
  try(var ignored=new GlStateSnapshot();var post=new ClaudePostPass()){
   int f=glGenFramebuffers(),c=glGenTextures(),d=glGenRenderbuffers(),dt=glGenTextures();
   try {
    glBindFramebuffer(GL_FRAMEBUFFER,f);glActiveTexture(GL_TEXTURE0);glBindTexture(GL_TEXTURE_2D,c);
    glTexImage2D(GL_TEXTURE_2D,0,GL_RGBA8,32,32,0,GL_RGBA,GL_UNSIGNED_BYTE,0L);glFramebufferTexture2D(GL_FRAMEBUFFER,GL_COLOR_ATTACHMENT0,GL_TEXTURE_2D,c,0);
    glBindRenderbuffer(GL_RENDERBUFFER,d);glRenderbufferStorage(GL_RENDERBUFFER,GL_DEPTH_COMPONENT24,32,32);
    var settings=new ClaudePostPass.Settings(1,.5,0,.5,1,1,1,false);
    // Reproduce Minecraft's unsized GL_DEPTH_COMPONENT (6402), then keep sized formats covered.
    for(int format:new int[]{0,GL_DEPTH_COMPONENT,GL_DEPTH_COMPONENT16,GL_DEPTH_COMPONENT24,GL_DEPTH_COMPONENT32,GL_DEPTH_COMPONENT32F}) {
     boolean texture=format!=0;
     if(texture){
      glBindTexture(GL_TEXTURE_2D,dt);glTexImage2D(GL_TEXTURE_2D,0,format,32,32,0,GL_DEPTH_COMPONENT,GL_FLOAT,0L);
      System.out.println("depth input="+format+" reported="+glGetTexLevelParameteri(GL_TEXTURE_2D,0,GL_TEXTURE_INTERNAL_FORMAT)
       +" bits="+glGetTexLevelParameteri(GL_TEXTURE_2D,0,GL_TEXTURE_DEPTH_SIZE)+" type="+glGetTexLevelParameteri(GL_TEXTURE_2D,0,GL_TEXTURE_DEPTH_TYPE));
     }
     glBindFramebuffer(GL_FRAMEBUFFER,f);
     if(texture)glFramebufferTexture2D(GL_FRAMEBUFFER,GL_DEPTH_ATTACHMENT,GL_TEXTURE_2D,dt,0);else glFramebufferRenderbuffer(GL_FRAMEBUFFER,GL_DEPTH_ATTACHMENT,GL_RENDERBUFFER,d);
     glViewport(0,0,32,32);glDisable(GL_SCISSOR_TEST);glDepthMask(true);glClearDepth(.8);glClear(GL_DEPTH_BUFFER_BIT);
     try(var state=new GlStateSnapshot()){
      post.prewarm(32,32);glBindFramebuffer(GL_DRAW_FRAMEBUFFER,f);
      if(!post.prewarmDepthFromBoundTarget()||!post.depthPrepared())throw new AssertionError("World depth not ready: "+format);
     }
     checkDepth(.8);
     post.beginFrame(true);
     post.render(settings,()->{checkDepth(.8);glDepthMask(true);glClearDepth(.25);glClear(GL_DEPTH_BUFFER_BIT);checkDepth(.25);});
     glBindFramebuffer(GL_FRAMEBUFFER,f);checkDepth(.8);
     post.render(settings,()->checkDepth(.25));
     glBindFramebuffer(GL_FRAMEBUFFER,f);checkDepth(.8);
     post.beginFrame(true);post.render(settings,()->checkDepth(.8));
     glBindFramebuffer(GL_FRAMEBUFFER,f);checkDepth(.8);
     if(glGetFramebufferAttachmentParameteri(GL_FRAMEBUFFER,GL_DEPTH_ATTACHMENT,GL_FRAMEBUFFER_ATTACHMENT_OBJECT_NAME)!=(texture?dt:d))throw new AssertionError("World depth replaced");
     if(glGetError()!=GL_NO_ERROR)throw new AssertionError("Private depth GL error for format "+format);
    }
    if(glGetError()!=GL_NO_ERROR)throw new AssertionError("Private depth GL error");
    System.out.println("PASS ClaudeDepthGlTest: unsized/sized texture and renderbuffer warmup; host depth preserved; cross-group private depth and next-frame reset");
   } finally {glDeleteTextures(dt);glDeleteRenderbuffers(d);glDeleteTextures(c);glDeleteFramebuffers(f);}
  }
 }
 private static void checkDepth(double expected){
  int draw=glGetInteger(GL_DRAW_FRAMEBUFFER_BINDING),read=glGetInteger(GL_READ_FRAMEBUFFER_BINDING);glBindFramebuffer(GL_READ_FRAMEBUFFER,draw);
  var value=BufferUtils.createFloatBuffer(1);glReadPixels(16,16,1,1,GL_DEPTH_COMPONENT,GL_FLOAT,value);glBindFramebuffer(GL_READ_FRAMEBUFFER,read);
  if(Math.abs(value.get(0)-expected)>1e-5)throw new AssertionError("Expected depth "+expected+" got "+value.get(0));
 }
}
