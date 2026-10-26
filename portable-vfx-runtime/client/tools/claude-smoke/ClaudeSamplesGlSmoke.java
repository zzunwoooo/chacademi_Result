import com.google.gson.JsonParser;
import dev.portablevfx.client.claude.ClaudeBackend;
import dev.portablevfx.client.render.gl.WorldTargetPass;
import java.nio.file.*;
import java.util.*;
import org.joml.Matrix4f;
import org.lwjgl.BufferUtils;
import org.lwjgl.egl.EGL;
import org.lwjgl.opengl.GL;
import org.lwjgl.system.MemoryStack;
import static org.lwjgl.egl.EGL14.*;
import static org.lwjgl.opengl.GL33C.*;

/** Real GL 3.3 shader, authored geometry, retained-depth and state-restore smoke; no Minecraft. */
public final class ClaudeSamplesGlSmoke {
    public static void main(String[] args)throws Exception {
        try(var stack=MemoryStack.stackPush()){
            long display=eglGetDisplay(EGL_DEFAULT_DISPLAY);var major=stack.mallocInt(1);var minor=stack.mallocInt(1);
            if(!eglInitialize(display,major,minor))throw new IllegalStateException("EGL init "+Integer.toHexString(eglGetError()));
            EGL.createDisplayCapabilities(display,major.get(0),minor.get(0));
            var configs=stack.mallocPointer(1);var count=stack.mallocInt(1);
            if(!eglChooseConfig(display,stack.ints(EGL_SURFACE_TYPE,EGL_PBUFFER_BIT,EGL_RENDERABLE_TYPE,EGL_OPENGL_BIT,EGL_RED_SIZE,8,EGL_GREEN_SIZE,8,EGL_BLUE_SIZE,8,EGL_DEPTH_SIZE,24,EGL_NONE),configs,count)||count.get(0)==0)throw new IllegalStateException("EGL config");
            eglBindAPI(EGL_OPENGL_API);
            long context=eglCreateContext(display,configs.get(0),EGL_NO_CONTEXT,stack.ints(0x3098,3,0x30FB,3,0x30FD,1,EGL_NONE));
            long surface=eglCreatePbufferSurface(display,configs.get(0),stack.ints(EGL_WIDTH,512,EGL_HEIGHT,512,EGL_NONE));
            if(!eglMakeCurrent(display,surface,surface,context))throw new IllegalStateException("EGL makeCurrent");
            GL.createCapabilities();System.out.println("GL="+glGetString(GL_VERSION)+" renderer="+glGetString(GL_RENDERER));
            Path folder=Path.of(args[0]),output=Path.of(args[1]);Files.createDirectories(output);
            int main=glGenFramebuffers(),foreign=glGenFramebuffers(),color=glGenRenderbuffers(),depth=glGenRenderbuffers();
            glBindFramebuffer(GL_FRAMEBUFFER,main);glBindRenderbuffer(GL_RENDERBUFFER,color);glRenderbufferStorage(GL_RENDERBUFFER,GL_RGBA8,512,512);
            glFramebufferRenderbuffer(GL_FRAMEBUFFER,GL_COLOR_ATTACHMENT0,GL_RENDERBUFFER,color);
            glBindRenderbuffer(GL_RENDERBUFFER,depth);glRenderbufferStorage(GL_RENDERBUFFER,GL_DEPTH_COMPONENT24,512,512);
            glFramebufferRenderbuffer(GL_FRAMEBUFFER,GL_DEPTH_ATTACHMENT,GL_RENDERBUFFER,depth);
            if(glCheckFramebufferStatus(GL_FRAMEBUFFER)!=GL_FRAMEBUFFER_COMPLETE)throw new IllegalStateException("Test framebuffer incomplete");
            dev.portablevfx.client.claude.ClaudePreloadGlTest.run(folder,main,512,512);
            int sentinelSampler=glGenSamplers();glBindSampler(7,sentinelSampler);
            int depthTexture=glGenTextures();glBindTexture(GL_TEXTURE_2D,depthTexture);
            glTexImage2D(GL_TEXTURE_2D,0,GL_DEPTH_COMPONENT24,512,512,0,GL_DEPTH_COMPONENT,GL_FLOAT,0L);
            glTexParameteri(GL_TEXTURE_2D,GL_TEXTURE_MIN_FILTER,GL_NEAREST);glTexParameteri(GL_TEXTURE_2D,GL_TEXTURE_MAG_FILTER,GL_NEAREST);
            for(String scene:new String[]{"Fireball/projectile","Fireball/impact","FeatherFall/aura","TidalWave/wave","TidalWave/collapse","TidalWave/splash"}){
            glBindFramebuffer(GL_FRAMEBUFFER,main);
            if(scene.equals("impact-ground"))glFramebufferRenderbuffer(GL_FRAMEBUFFER,GL_DEPTH_ATTACHMENT,GL_RENDERBUFFER,depth);
            else glFramebufferTexture2D(GL_FRAMEBUFFER,GL_DEPTH_ATTACHMENT,GL_TEXTURE_2D,depthTexture,0);
            int expectedDepth=scene.equals("impact-ground")?depth:depthTexture;
            int[] counts=new int[2];
            for(boolean bloom:new boolean[]{false,true}){
                var json=JsonParser.parseString(Files.readString(folder.resolve(scene.split("/")[0]).resolve("vfx.json"))).getAsJsonObject();json.addProperty("portableVfxSystem",scene.split("/")[1]);
                if(!bloom){json.getAsJsonObject("post").getAsJsonObject("bloom").addProperty("intensity",0);json.getAsJsonObject("features").getAsJsonArray("required").remove(new com.google.gson.JsonPrimitive("bloom"));}
                try(var backend=new ClaudeBackend()){
                    var asset=backend.load(json.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8),1,p->Files.readAllBytes(folder.resolve(scene.split("/")[0]).resolve(p)));
                    var handle=backend.play(asset,123);handle.transform(0,0,0,0,0,0,1);
                    if(scene.startsWith("TidalWave/")&&!scene.endsWith("/splash"))handle.effectWidth(14);
                    if(scene.endsWith("/projectile")||scene.endsWith("/wave")||scene.endsWith("/aura")){
                        for(int frame=1;frame<=16;frame++){handle.transform(-2+frame*.25f,1,0,0,0,0,1);handle.basis(new float[]{0,0,-1,0,1,0,1,0,0});handle.sceneTime(10+frame*.05);handle.seek(frame*.05f);backend.update(.05f);}
                    }else{handle.seek(.2f);backend.update(.2f);}
                    float cx=10,cy=6,cz=18;float[] view=new Matrix4f().lookAt(cx,cy,cz,0,1,0,0,1,0).get(new float[16]);
                    float[] projection=new Matrix4f().perspective((float)Math.toRadians(50),1,.1f,100).get(new float[16]);
                    for(boolean hidden:new boolean[]{false,true}){
                        glBindFramebuffer(GL_FRAMEBUFFER,main);glDisable(GL_SCISSOR_TEST);glDisable(GL_RASTERIZER_DISCARD);glColorMask(true,true,true,true);glDepthMask(true);
                        glClearDepth(hidden?0:1);glClearColor(0,0,0,1);glClear(GL_COLOR_BUFFER_BIT|GL_DEPTH_BUFFER_BIT);
                        glBindFramebuffer(GL_DRAW_FRAMEBUFFER,foreign);glViewport(11,13,7,9);glColorMask(false,false,false,false);
                        glEnable(GL_SCISSOR_TEST);glScissor(0,0,0,0);glEnable(GL_RASTERIZER_DISCARD);glDepthRange(.2,.8);
                        glActiveTexture(GL_TEXTURE7);glPixelStorei(GL_UNPACK_ROW_LENGTH,17);glPixelStorei(GL_UNPACK_SKIP_PIXELS,2);
                        try(var pass=WorldTargetPass.begin(main,512,512)){backend.draw(view,projection,-cx,-cy,-cz,cx,cy,cz);}
                        int[] viewport=new int[4];glGetIntegerv(GL_VIEWPORT,viewport);
                        if(glGetInteger(GL_DRAW_FRAMEBUFFER_BINDING)!=foreign||!Arrays.equals(viewport,new int[]{11,13,7,9})
                                ||!glIsEnabled(GL_SCISSOR_TEST)||!glIsEnabled(GL_RASTERIZER_DISCARD)||glGetBoolean(GL_COLOR_WRITEMASK)
                                ||glGetInteger(GL_ACTIVE_TEXTURE)!=GL_TEXTURE7||glGetInteger(GL_SAMPLER_BINDING)!=sentinelSampler
                                ||glGetInteger(GL_UNPACK_ROW_LENGTH)!=17||glGetInteger(GL_UNPACK_SKIP_PIXELS)!=2)
                            throw new AssertionError("Claude GL state leaked");
                        glBindFramebuffer(GL_READ_FRAMEBUFFER,main);
                        if(glGetFramebufferAttachmentParameteri(GL_READ_FRAMEBUFFER,GL_DEPTH_ATTACHMENT,GL_FRAMEBUFFER_ATTACHMENT_OBJECT_NAME)!=expectedDepth)
                            throw new AssertionError("Claude replaced the main depth attachment");
                        var pixels=BufferUtils.createByteBuffer(512*512*4);glReadPixels(0,0,512,512,GL_RGBA,GL_UNSIGNED_BYTE,pixels);
                        int nonblack=0;for(int i=0;i<512*512;i++)if((pixels.get(i*4)&255)+(pixels.get(i*4+1)&255)+(pixels.get(i*4+2)&255)>8)nonblack++;
                        if(hidden&&nonblack!=0)throw new AssertionError("Occluded effect/bloom leaked: "+nonblack);
                        if(!hidden){if(nonblack==0)throw new AssertionError("No authored effect pixels");counts[bloom?1:0]=nonblack;
                            var image=new java.awt.image.BufferedImage(512,512,java.awt.image.BufferedImage.TYPE_INT_ARGB);
                            for(int y=0;y<512;y++)for(int x=0;x<512;x++){int i=(y*512+x)*4;image.setRGB(x,511-y,0xff000000|(pixels.get(i)&255)<<16|(pixels.get(i+1)&255)<<8|(pixels.get(i+2)&255));}
                            javax.imageio.ImageIO.write(image,"png",output.resolve(scene.replace("/","-")+"-bloom-"+(bloom?"on":"off")+".png").toFile());
                        }
                        int error=glGetError();if(error!=GL_NO_ERROR)throw new AssertionError("GL error "+error);
                        System.out.println("scene="+scene+" bloom="+bloom+" hidden="+hidden+" pixels="+nonblack);
                    }
                }
            }
            if(counts[1]==0||counts[0]==0)throw new AssertionError("No authored pixels: "+scene);
            }
            glDisable(GL_RASTERIZER_DISCARD);glDisable(GL_SCISSOR_TEST);glColorMask(true,true,true,true);glDepthRange(0,1);
            glPixelStorei(GL_UNPACK_ROW_LENGTH,0);glPixelStorei(GL_UNPACK_SKIP_PIXELS,0);glBindSampler(7,0);glDeleteSamplers(sentinelSampler);
            glBindFramebuffer(GL_FRAMEBUFFER,0);glDeleteFramebuffers(main);glDeleteFramebuffers(foreign);glDeleteRenderbuffers(color);glDeleteRenderbuffers(depth);glDeleteTextures(depthTexture);
            if(glGetError()!=GL_NO_ERROR)throw new AssertionError("Cleanup GL error");
            System.out.println("PASS ClaudeSamplesGlSmoke: shaders, geometry, HDR bloom, retained depth, state restore, cleanup");
            eglMakeCurrent(display,EGL_NO_SURFACE,EGL_NO_SURFACE,EGL_NO_CONTEXT);eglDestroySurface(display,surface);eglDestroyContext(display,context);eglTerminate(display);
        }
    }
}
