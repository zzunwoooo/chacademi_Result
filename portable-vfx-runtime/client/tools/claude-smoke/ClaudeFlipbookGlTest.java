package dev.portablevfx.client.claude;

import dev.portablevfx.client.render.gl.GlStateSnapshot;
import org.joml.Matrix4f;
import org.lwjgl.BufferUtils;
import static org.lwjgl.opengl.GL33C.*;

/** Synthetic pixel checks against the actual production shaders, in an existing GL context. */
public final class ClaudeFlipbookGlTest {
    private static int assertions;
    private ClaudeFlipbookGlTest() {}
    public static void run()throws Exception {
        var vertex=ClaudeBackend.class.getDeclaredField("VERTEX");vertex.setAccessible(true);
        var fragment=ClaudeBackend.class.getDeclaredField("FRAGMENT");fragment.setAccessible(true);
        try(var ignored=new GlStateSnapshot()){
            int program=ClaudeBackend.link((String)vertex.get(null),(String)fragment.get(null));
            int fbo=glGenFramebuffers(),output=glGenTextures(),atlas=glGenTextures(),vao=glGenVertexArrays(),vbo=glGenBuffers();
            try{
                glActiveTexture(GL_TEXTURE0);glBindSampler(0,0);glPixelStorei(GL_UNPACK_ROW_LENGTH,0);glPixelStorei(GL_UNPACK_SKIP_PIXELS,0);glPixelStorei(GL_UNPACK_SKIP_ROWS,0);
                glBindTexture(GL_TEXTURE_2D,output);glTexImage2D(GL_TEXTURE_2D,0,GL_RGBA32F,8,8,0,GL_RGBA,GL_FLOAT,0L);
                glBindFramebuffer(GL_FRAMEBUFFER,fbo);glFramebufferTexture2D(GL_FRAMEBUFFER,GL_COLOR_ATTACHMENT0,GL_TEXTURE_2D,output,0);
                if(glCheckFramebufferStatus(GL_FRAMEBUFFER)!=GL_FRAMEBUFFER_COMPLETE)throw new AssertionError("Flipbook test framebuffer");
                glBindTexture(GL_TEXTURE_2D,atlas);glTexParameteri(GL_TEXTURE_2D,GL_TEXTURE_MIN_FILTER,GL_LINEAR);glTexParameteri(GL_TEXTURE_2D,GL_TEXTURE_MAG_FILTER,GL_LINEAR);
                glTexParameteri(GL_TEXTURE_2D,GL_TEXTURE_WRAP_S,GL_CLAMP_TO_EDGE);glTexParameteri(GL_TEXTURE_2D,GL_TEXTURE_WRAP_T,GL_CLAMP_TO_EDGE);
                glUseProgram(program);glUniform1i(glGetUniformLocation(program,"colorTexture"),0);glUniform3f(glGetUniformLocation(program,"tint"),1,1,1);
                glUniform2f(glGetUniformLocation(program,"flipbookGrid"),2,2);glUniformMatrix4fv(glGetUniformLocation(program,"viewProjection"),false,new Matrix4f().get(new float[16]));
                glBindVertexArray(vao);glBindBuffer(GL_ARRAY_BUFFER,vbo);int offset=0;int[] sizes={3,2,3,1,3};
                for(int i=0;i<sizes.length;i++){glEnableVertexAttribArray(i);glVertexAttribPointer(i,sizes[i],GL_FLOAT,false,ClaudeGeometry.STRIDE*4,(long)offset*4);offset+=sizes[i];}
                glViewport(0,0,8,8);glDisable(GL_DEPTH_TEST);glDisable(GL_BLEND);glDisable(GL_CULL_FACE);glDisable(GL_SCISSOR_TEST);glDisable(GL_RASTERIZER_DISCARD);glDisable(GL_FRAMEBUFFER_SRGB);glColorMask(true,true,true,true);
                // Top row: opaque white / transparent magenta; bottom row: opaque green / opaque blue.
                atlas(new float[][]{{1,1,1,1},{1,0,1,0},{0,1,0,1},{0,0,1,1}});
                checkPixel(program,.25f,.75f,0,1,.5f,1,true,new float[]{1,1,1,.5f},"Transparent neighbor contributes no black/color fringe");
                checkPixel(program,.75f,.75f,1,2,.5f,1,true,new float[]{0,1,0,.5f},"Adjacent frame crosses atlas row safely");
                checkPixel(program,.25f,.75f,0,1,.5f,.25f,true,new float[]{1,1,1,.125f},"Particle opacity applied exactly once");
                checkPixel(program,.5f,.5f,0,1,0,1,true,new float[]{1,1,1,1},"Half-texel cell clamp excludes all neighboring atlas cells");
                checkPixel(program,.25f,.75f,0,1,.5f,1,false,new float[]{1,1,1,1},"Compatibility mode keeps discrete source frame");
                atlas(new float[][]{{1,0,0,1},{0,0,1,1},{0,1,0,1},{1,1,1,1}});
                checkPixel(program,.25f,.75f,0,1,.5f,1,true,new float[]{.5f,0,.5f,1},"Opaque crossfade blends in linear light without extra opacity");
                // With no flipbook grid, both smoothing settings use identical original texture sampling.
                glUniform2f(glGetUniformLocation(program,"flipbookGrid"),1,1);
                checkPixel(program,.25f,.75f,0,0,0,.4f,true,new float[]{1,0,0,.4f},"Nonflipbook and trail sampling stays unchanged");
                if(glGetError()!=GL_NO_ERROR)throw new AssertionError("Flipbook GL error");
                System.out.println("PASS ClaudeFlipbookGlTest assertions="+assertions+" (linear alpha-safe blend, atlas boundaries, source-mode fallback)");
            }finally{glDeleteBuffers(vbo);glDeleteVertexArrays(vao);glDeleteTextures(atlas);glDeleteTextures(output);glDeleteFramebuffers(fbo);glDeleteProgram(program);}
        }
    }
    private static void atlas(float[][] cells){
        var pixels=BufferUtils.createFloatBuffer(8*8*4);
        for(int y=0;y<8;y++)for(int x=0;x<8;x++)pixels.put(cells[(1-y/4)*2+x/4]);pixels.flip();
        glTexImage2D(GL_TEXTURE_2D,0,GL_RGBA32F,8,8,0,GL_RGBA,GL_FLOAT,pixels);
    }
    private static void checkPixel(int program,float u,float v,float current,float next,float blend,float alpha,boolean smooth,float[] expected,String name){
        float[] positions={-1,-1,1,-1,1,1,-1,-1,1,1,-1,1};
        float[] vertices=new float[6*ClaudeGeometry.STRIDE];int n=0;
        for(int i=0;i<6;i++)for(float value:new float[]{positions[i*2],positions[i*2+1],0,u,v,1,1,1,alpha,current,next,blend})vertices[n++]=value;
        glUniform1i(glGetUniformLocation(program,"interpolateFlipbooks"),smooth?1:0);glBufferData(GL_ARRAY_BUFFER,vertices,GL_STREAM_DRAW);glDrawArrays(GL_TRIANGLES,0,6);
        var pixel=BufferUtils.createFloatBuffer(4);glReadPixels(4,4,1,1,GL_RGBA,GL_FLOAT,pixel);
        for(int i=0;i<4;i++){assertions++;if(Math.abs(pixel.get(i)-expected[i])>1e-5)throw new AssertionError(name+" component="+i+" actual="+pixel.get(i)+" expected="+expected[i]);}
    }
}
