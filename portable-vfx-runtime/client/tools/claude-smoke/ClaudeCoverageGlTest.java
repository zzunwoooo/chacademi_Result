package dev.portablevfx.client.claude;
import dev.portablevfx.client.render.gl.GlStateSnapshot;
import org.lwjgl.BufferUtils;
import static org.lwjgl.opengl.GL33C.*;
/** Regression: a faint alpha card cannot impose its coverage on unrelated additive light. */
public final class ClaudeCoverageGlTest {
 public static void run() throws Exception {
  try(var ignored=new GlStateSnapshot();var post=new ClaudePostPass()){
   int f=glGenFramebuffers(),c=glGenTextures(),d=glGenRenderbuffers();
   try{
    glBindFramebuffer(GL_FRAMEBUFFER,f);glActiveTexture(GL_TEXTURE0);glBindTexture(GL_TEXTURE_2D,c);
    glTexImage2D(GL_TEXTURE_2D,0,GL_RGBA32F,64,64,0,GL_RGBA,GL_FLOAT,0L);
    glFramebufferTexture2D(GL_FRAMEBUFFER,GL_COLOR_ATTACHMENT0,GL_TEXTURE_2D,c,0);
    glBindRenderbuffer(GL_RENDERBUFFER,d);glRenderbufferStorage(GL_RENDERBUFFER,GL_DEPTH_COMPONENT24,64,64);
    glFramebufferRenderbuffer(GL_FRAMEBUFFER,GL_DEPTH_ATTACHMENT,GL_RENDERBUFFER,d);
    float baseline=draw(post,f,0,false);
    for(boolean bloom:new boolean[]{false,true})for(float alpha:new float[]{.001f,.02f,.22f}){
     float actual=draw(post,f,alpha,bloom);
     // Surface coverage may attenuate the background but must not erase additive emission.
     if(actual<baseline-.08f)throw new AssertionError("Coverage darkened additive light: baseline="+baseline+" alpha="+alpha+" actual="+actual+" bloom="+bloom);
     System.out.println("coverage="+alpha+" bloom="+bloom+" baseline="+baseline+" actual="+actual);
    }
    var config=java.nio.file.Files.createTempFile("pvfx-bloom-test", ".properties");
    try {
     java.nio.file.Files.writeString(config,"version=2\nstrength=1");ClaudeBloomConfig.load(config);
     float original=draw(post,f,0,true),originalCore=draw(post,f,0,false);
     java.nio.file.Files.writeString(config,"version=2\nstrength=0.4");ClaudeBloomConfig.load(config);
     float reduced=draw(post,f,0,true),reducedCore=draw(post,f,0,false);
     java.nio.file.Files.writeString(config,"version=2\nstrength=0");ClaudeBloomConfig.load(config);
     float disabled=draw(post,f,0,true);
     if(!(original>reduced&&reduced>disabled))throw new AssertionError("Bloom gain is not monotonic: "+original+" / "+reduced+" / "+disabled);
     if(Math.abs(originalCore-reducedCore)>1e-6f||Math.abs(disabled-originalCore)>1e-6f)throw new AssertionError("Bloom multiplier changed core-only output");
     System.out.println("PASS global bloom: original="+original+" reduced="+reduced+" off="+disabled+" unchanged core="+originalCore);
    } finally {
     java.nio.file.Files.writeString(config,"version=2\nstrength=0.4");ClaudeBloomConfig.load(config);java.nio.file.Files.delete(config);
    }
    if(glGetError()!=GL_NO_ERROR)throw new AssertionError("Coverage GL error");
    System.out.println("PASS ClaudeCoverageGlTest: alpha coverage independent of additive HDR light");
   }finally{glDeleteRenderbuffers(d);glDeleteTextures(c);glDeleteFramebuffers(f);}
  }
 }
 private static float draw(ClaudePostPass post,int f,float alpha,boolean bloom){
  glBindFramebuffer(GL_FRAMEBUFFER,f);glViewport(0,0,64,64);glDisable(GL_SCISSOR_TEST);glColorMask(true,true,true,true);
  glClearColor(.2f,.2f,.2f,1);glClear(GL_COLOR_BUFFER_BIT);
  post.render(new ClaudePostPass.Settings(.1,.5,bloom?1:0,.5,1,1,1,true),()->{
   boolean split=glGetFramebufferAttachmentParameteri(GL_DRAW_FRAMEBUFFER,GL_COLOR_ATTACHMENT1,GL_FRAMEBUFFER_ATTACHMENT_OBJECT_TYPE)!=GL_NONE;
   float surface=.01f*alpha,emission=.3f;
   glClearBufferfv(GL_COLOR,0,new float[]{surface+(split?0:emission),surface+(split?0:emission),surface+(split?0:emission),alpha});
   if(split)glClearBufferfv(GL_COLOR,1,new float[]{emission,emission,emission,0});
  });
  glBindFramebuffer(GL_READ_FRAMEBUFFER,f);var pixel=BufferUtils.createFloatBuffer(4);glReadPixels(32,32,1,1,GL_RGBA,GL_FLOAT,pixel);return pixel.get(0);
 }
}
