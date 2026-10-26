package dev.portablevfx.client.claude;

import dev.portablevfx.client.render.gl.GpuFailureException;
import static org.lwjgl.opengl.GL33C.*;

/**
 * Claude's authored, effect-only HDR post. The main depth attachment is never written or cleared.
 * Read-only effects share it; depth-writing effects use one private copy per backend frame. Minecraft/Iris color is neither sampled nor tonemapped. This preserves the
 * host world, but cannot be pixel-identical to a preview which tonemaps its entire backdrop.
 * Required HDR/depth failures propagate; there is deliberately no silent LDR/bloom fallback.
 * Targets above MAX_PIXELS (e.g. 4K+ with high GUI/render scale) render the effect-only HDR scene at an
 * aspect-preserving internal resolution within MAX_PIXELS, using a NEAREST-downscaled private depth copy, and the
 * final composite upsamples (bilinear) onto the full-size host target. Host color/depth are never resampled.
 */
final class ClaudePostPass implements AutoCloseable {
    static final int MAX_PIXELS = 8_388_608, MAX_LEVELS = 6;
    record Settings(double threshold, double softKnee, double intensity, double scatter,
                    double red, double green, double blue, boolean neutral) { }
    private static final String VERTEX = """
        #version 330 core
        out vec2 uv;
        void main(){vec2 p=vec2((gl_VertexID<<1)&2,gl_VertexID&2);uv=p;gl_Position=vec4(p*2.0-1.0,0,1);}
        """;
    private static final String FRAGMENT = """
        #version 330 core
        in vec2 uv; out vec4 color;
        uniform sampler2D source; uniform sampler2D lower; uniform sampler2D light;
        uniform int mode; uniform vec2 texel;
        uniform float threshold; uniform float knee; uniform float scatter;
        uniform float intensity; uniform vec3 bloomTint; uniform int neutral;
        vec3 tent(sampler2D tex,vec2 p,vec2 t){
            vec3 c=texture(tex,p).rgb*4.0;
            c+=(texture(tex,p+vec2(t.x,0)).rgb+texture(tex,p-vec2(t.x,0)).rgb
               +texture(tex,p+vec2(0,t.y)).rgb+texture(tex,p-vec2(0,t.y)).rgb)*2.0;
            c+=texture(tex,p+t).rgb+texture(tex,p-t).rgb
              +texture(tex,p+vec2(t.x,-t.y)).rgb+texture(tex,p+vec2(-t.x,t.y)).rgb;
            return c*(1.0/16.0);
        }
        vec3 neutralCurve(vec3 x){const float a=.2,b=.29,c=.24,d=.272,e=.02,f=.3;
            return ((x*(a*x+c*b)+d*e)/(x*(a*x+b)+d*f))-e/f;}
        vec3 neutralMap(vec3 x){float whiteScale=1.0/neutralCurve(vec3(5.3)).r;
            return neutralCurve(max(x,vec3(0))*whiteScale)*whiteScale;}
        void main(){
            if(mode==0){
                vec3 c=tent(source,uv,texel)+tent(light,uv,texel);float br=max(c.r,max(c.g,c.b));
                float soft=clamp(br-threshold+knee,0.0,2.0*knee);
                soft=soft*soft/(4.0*knee+1e-5);
                c*=max(soft,br-threshold)/max(br,1e-5);color=vec4(c,0);
            }else if(mode==1){color=vec4(tent(source,uv,texel),0);
            }else if(mode==2){color=vec4(mix(texture(source,uv).rgb,tent(lower,uv,texel),scatter),0);
            }else{
                vec4 base=texture(source,uv);
                vec3 glow=intensity>0.0?texture(lower,uv).rgb*bloomTint*intensity:vec3(0);
                // Coverage belongs only to alpha-over material. Additive light must not
                // inherit a faint overlapping card's coverage during nonlinear tonemapping.
                float coverage=clamp(base.a,0.0,1.0);
                vec3 surface=coverage>1e-5?base.rgb/coverage:vec3(0);
                vec3 emission=texture(light,uv).rgb+glow;
                if(neutral==1){surface=neutralMap(surface);emission=neutralMap(emission);}
                vec3 c=pow(max(surface,vec3(0)),vec3(1.0/2.2))*coverage
                     +pow(max(emission,vec3(0)),vec3(1.0/2.2));
                color=vec4(c,coverage);
            }
        }
        """;
    /** width/height are the internal HDR size; targetWidth/targetHeight the host framebuffer size it composites onto. */
    private int program, vao, sceneTexture, lightTexture, sceneFramebuffer, width, height, targetWidth, targetHeight, levels;
    private long shaderLinks,targetAllocations;
    private int privateDepth,privateDepthFormat,depthSource;
    private boolean writableDepth,depthCopied;
    /** One depth copy is shared across all post-setting groups drawn in this backend frame. */
    void beginFrame(boolean writesDepth){writableDepth=writesDepth;depthCopied=false;depthSource=0;}
    private final int[] downTexture=new int[MAX_LEVELS],downFramebuffer=new int[MAX_LEVELS];
    private final int[] upTexture=new int[MAX_LEVELS],upFramebuffer=new int[MAX_LEVELS];
    private final int[] levelWidth=new int[MAX_LEVELS],levelHeight=new int[MAX_LEVELS];

    boolean ready(int w,int h){return program!=0&&sceneFramebuffer!=0&&w==targetWidth&&h==targetHeight;}
    boolean scaled(){return width!=targetWidth||height!=targetHeight;}
    /** Aspect-preserving internal size whose pixel count never exceeds MAX_PIXELS; identity at or below the cap. */
    static int[] internalSize(int w,int h){
        if(w<1||h<1)throw new IllegalArgumentException("Claude HDR post requires a positive render target");
        if((long)w*h<=MAX_PIXELS)return new int[]{w,h};
        double scale=Math.sqrt((double)MAX_PIXELS/((double)w*h));
        int ih=Math.min(MAX_PIXELS,Math.max(1,(int)Math.floor(h*scale)));
        int iw=Math.max(1,(int)Math.min(Math.floor(w*scale),MAX_PIXELS/ih));
        return new int[]{iw,ih};
    }
    long shaderLinks(){return shaderLinks;}
    long targetAllocations(){return targetAllocations;}
    /** Color-only allocation is legal on a loading screen; world depth is attached only in render. */
    void prewarm(int w,int h){
        if(w<1||h<1)throw new IllegalArgumentException("Claude HDR post requires a positive render target");
        ensureSize(w,h);
    }

    boolean depthPrepared(){return privateDepth!=0;}
    /** Allocates only an owned depth target matching the currently bound world/loading target; never copies or attaches it. */
    boolean prewarmDepthFromBoundTarget(){
        int target=glGetInteger(GL_DRAW_FRAMEBUFFER_BINDING);
        if(target<=0||width<=0||height<=0||glGetInteger(GL_SAMPLES)>0)return false;
        int type=glGetFramebufferAttachmentParameteri(GL_DRAW_FRAMEBUFFER,GL_DEPTH_ATTACHMENT,GL_FRAMEBUFFER_ATTACHMENT_OBJECT_TYPE);
        int name=glGetFramebufferAttachmentParameteri(GL_DRAW_FRAMEBUFFER,GL_DEPTH_ATTACHMENT,GL_FRAMEBUFFER_ATTACHMENT_OBJECT_NAME);
        int format,w,h;
        if(type==GL_RENDERBUFFER){
            glBindRenderbuffer(GL_RENDERBUFFER,name);format=glGetRenderbufferParameteri(GL_RENDERBUFFER,GL_RENDERBUFFER_INTERNAL_FORMAT);
            w=glGetRenderbufferParameteri(GL_RENDERBUFFER,GL_RENDERBUFFER_WIDTH);h=glGetRenderbufferParameteri(GL_RENDERBUFFER,GL_RENDERBUFFER_HEIGHT);
        }else if(type==GL_TEXTURE){
            if(glGetFramebufferAttachmentParameteri(GL_DRAW_FRAMEBUFFER,GL_DEPTH_ATTACHMENT,GL_FRAMEBUFFER_ATTACHMENT_LAYERED)!=GL_FALSE
                    ||glGetFramebufferAttachmentParameteri(GL_DRAW_FRAMEBUFFER,GL_DEPTH_ATTACHMENT,GL_FRAMEBUFFER_ATTACHMENT_TEXTURE_CUBE_MAP_FACE)!=0)return false;
            int level=glGetFramebufferAttachmentParameteri(GL_DRAW_FRAMEBUFFER,GL_DEPTH_ATTACHMENT,GL_FRAMEBUFFER_ATTACHMENT_TEXTURE_LEVEL);
            glActiveTexture(GL_TEXTURE0);glBindTexture(GL_TEXTURE_2D,name);format=boundTextureDepthFormat(level);
            w=glGetTexLevelParameteri(GL_TEXTURE_2D,level,GL_TEXTURE_WIDTH);h=glGetTexLevelParameteri(GL_TEXTURE_2D,level,GL_TEXTURE_HEIGHT);
        }else return false;
        if(w!=targetWidth||h!=targetHeight)return false;ensurePrivateDepth(format);return true;
    }

    void render(Settings settings,Runnable geometry){
        int target=glGetInteger(GL_DRAW_FRAMEBUFFER_BINDING);
        int[] viewport=new int[4];glGetIntegerv(GL_VIEWPORT,viewport);
        if(target<=0||viewport[0]!=0||viewport[1]!=0||viewport[2]<=0||viewport[3]<=0)
            throw new GpuFailureException("Claude HDR post requires an origin-aligned retained world framebuffer");
        if(glGetInteger(GL_SAMPLES)>0)throw new GpuFailureException("Claude HDR post does not support multisampled world depth");
        int depthType=glGetFramebufferAttachmentParameteri(GL_DRAW_FRAMEBUFFER,GL_DEPTH_ATTACHMENT,GL_FRAMEBUFFER_ATTACHMENT_OBJECT_TYPE);
        int depthName=glGetFramebufferAttachmentParameteri(GL_DRAW_FRAMEBUFFER,GL_DEPTH_ATTACHMENT,GL_FRAMEBUFFER_ATTACHMENT_OBJECT_NAME);
        if(depthType!=GL_TEXTURE&&depthType!=GL_RENDERBUFFER)
            throw new GpuFailureException("Claude HDR post requires retained texture or renderbuffer depth");
        int depthLevel=0;
        if(depthType==GL_TEXTURE){
            if(glGetFramebufferAttachmentParameteri(GL_DRAW_FRAMEBUFFER,GL_DEPTH_ATTACHMENT,GL_FRAMEBUFFER_ATTACHMENT_LAYERED)!=GL_FALSE
                    ||glGetFramebufferAttachmentParameteri(GL_DRAW_FRAMEBUFFER,GL_DEPTH_ATTACHMENT,GL_FRAMEBUFFER_ATTACHMENT_TEXTURE_CUBE_MAP_FACE)!=0)
                throw new GpuFailureException("Claude HDR post does not support layered/cubemap world depth");
            depthLevel=glGetFramebufferAttachmentParameteri(GL_DRAW_FRAMEBUFFER,GL_DEPTH_ATTACHMENT,GL_FRAMEBUFFER_ATTACHMENT_TEXTURE_LEVEL);
        }
        ensureSize(viewport[2],viewport[3]);
        // A downscaled internal target cannot share the host depth attachment, so it always uses the private copy.
        boolean privateDepthCopy=writableDepth||scaled();
        glBindFramebuffer(GL_DRAW_FRAMEBUFFER,sceneFramebuffer);
        try {
        if(privateDepthCopy) {
            if(depthCopied&&depthSource!=target)throw new GpuFailureException("Claude depth source changed within a frame");
            if(!depthCopied) {
                int format,depthWidth,depthHeight;
                if(depthType==GL_RENDERBUFFER) {
                    glBindRenderbuffer(GL_RENDERBUFFER,depthName);
                    format=glGetRenderbufferParameteri(GL_RENDERBUFFER,GL_RENDERBUFFER_INTERNAL_FORMAT);
                    depthWidth=glGetRenderbufferParameteri(GL_RENDERBUFFER,GL_RENDERBUFFER_WIDTH);
                    depthHeight=glGetRenderbufferParameteri(GL_RENDERBUFFER,GL_RENDERBUFFER_HEIGHT);
                } else {
                    glActiveTexture(GL_TEXTURE0);glBindTexture(GL_TEXTURE_2D,depthName);
                    format=boundTextureDepthFormat(depthLevel);
                    depthWidth=glGetTexLevelParameteri(GL_TEXTURE_2D,depthLevel,GL_TEXTURE_WIDTH);
                    depthHeight=glGetTexLevelParameteri(GL_TEXTURE_2D,depthLevel,GL_TEXTURE_HEIGHT);
                }
                if(depthWidth!=targetWidth||depthHeight!=targetHeight)throw new GpuFailureException("Claude world depth dimensions differ from viewport");
                ensurePrivateDepth(format);
            }
            glFramebufferRenderbuffer(GL_DRAW_FRAMEBUFFER,GL_DEPTH_ATTACHMENT,GL_RENDERBUFFER,privateDepth);
        } else if(depthType==GL_RENDERBUFFER)glFramebufferRenderbuffer(GL_DRAW_FRAMEBUFFER,GL_DEPTH_ATTACHMENT,GL_RENDERBUFFER,depthName);
        else glFramebufferTexture2D(GL_DRAW_FRAMEBUFFER,GL_DEPTH_ATTACHMENT,GL_TEXTURE_2D,depthName,depthLevel);
        if(glCheckFramebufferStatus(GL_DRAW_FRAMEBUFFER)!=GL_FRAMEBUFFER_COMPLETE)
            throw new GpuFailureException("Claude HDR world-depth framebuffer is incomplete");
        normalize();glViewport(0,0,width,height);glColorMask(true,true,true,true);
        if(privateDepthCopy&&!depthCopied) {
            // Depth blits may scale only with NEAREST; formats match via ensurePrivateDepth.
            glBindFramebuffer(GL_READ_FRAMEBUFFER,target);
            glBlitFramebuffer(0,0,targetWidth,targetHeight,0,0,width,height,GL_DEPTH_BUFFER_BIT,GL_NEAREST);
            depthCopied=true;depthSource=target;
        }
        glDrawBuffers(new int[]{GL_COLOR_ATTACHMENT0,GL_COLOR_ATTACHMENT1});
        glClearBufferfv(GL_COLOR,0,new float[]{0,0,0,0});
        glClearBufferfv(GL_COLOR,1,new float[]{0,0,0,0});
        geometry.run();
        normalize();glUseProgram(program);glBindVertexArray(vao);
        glUniform1i(location("source"),0);glUniform1i(location("lower"),1);glUniform1i(location("light"),2);
        glActiveTexture(GL_TEXTURE2);glBindTexture(GL_TEXTURE_2D,lightTexture);glBindSampler(2,0);
        double intensity=ClaudeBloomConfig.intensity(settings.intensity);
        boolean bloomEnabled=Double.isFinite(intensity)&&intensity>0&&levels>0;
        glUniform1f(location("intensity"),bloomEnabled?(float)intensity:0f);
        glUniform3f(location("bloomTint"),(float)settings.red,(float)settings.green,(float)settings.blue);
        glUniform1i(location("neutral"),settings.neutral?1:0);
        int bloom=lightTexture;// never sampled when bloom is disabled; any complete texture keeps unit 1 valid
        if(bloomEnabled){
            double threshold=Math.pow(settings.threshold,2.2);
            glUniform1f(location("threshold"),(float)threshold);
            glUniform1f(location("knee"),(float)(threshold*settings.softKnee));
            glUniform1f(location("scatter"),(float)(.05+.9*settings.scatter));
            for(int i=0;i<levels;i++){
                glUniform1i(location("mode"),i==0?0:1);
                glUniform2f(location("texel"),1f/(i==0?width:levelWidth[i-1]),1f/(i==0?height:levelHeight[i-1]));
                draw(downFramebuffer[i],i==0?sceneTexture:downTexture[i-1],levelWidth[i],levelHeight[i]);
            }
            bloom=downTexture[levels-1];
            glUniform1i(location("mode"),2);
            for(int i=levels-2;i>=0;i--){
                glActiveTexture(GL_TEXTURE1);glBindTexture(GL_TEXTURE_2D,bloom);glBindSampler(1,0);
                glUniform2f(location("texel"),1f/levelWidth[i+1],1f/levelHeight[i+1]);
                draw(upFramebuffer[i],downTexture[i],levelWidth[i],levelHeight[i]);bloom=upTexture[i];
            }
        }
        glUniform1i(location("mode"),3);
        glActiveTexture(GL_TEXTURE1);glBindTexture(GL_TEXTURE_2D,bloom);glBindSampler(1,0);
        glEnable(GL_BLEND);glBlendEquationSeparate(GL_FUNC_ADD,GL_FUNC_ADD);
        glBlendFuncSeparate(GL_ONE,GL_ONE_MINUS_SRC_ALPHA,GL_ZERO,GL_ONE);
        // Full-size composite; bilinear sampling upsamples a capped internal target.
        glColorMask(true,true,true,false);draw(target,sceneTexture,targetWidth,targetHeight);
        } finally {
            // Never retain someone else's depth object beyond this render call, even when geometry/post throws.
            if(sceneFramebuffer!=0){
                glBindFramebuffer(GL_DRAW_FRAMEBUFFER,sceneFramebuffer);
                glFramebufferRenderbuffer(GL_DRAW_FRAMEBUFFER,GL_DEPTH_ATTACHMENT,GL_RENDERBUFFER,0);
            }
            glBindFramebuffer(GL_DRAW_FRAMEBUFFER,target);
        }
    }
    /** Legacy Minecraft textures may report unsized DEPTH_COMPONENT. Match actual storage,
     * not the upload data type: depth blits require equal source/destination formats. */
    private static int boundTextureDepthFormat(int level){
        int format=glGetTexLevelParameteri(GL_TEXTURE_2D,level,GL_TEXTURE_INTERNAL_FORMAT);
        if(format!=GL_DEPTH_COMPONENT&&format!=GL_DEPTH_STENCIL)return format;
        return sizedDepthFormat(format,
                glGetTexLevelParameteri(GL_TEXTURE_2D,level,GL_TEXTURE_DEPTH_SIZE),
                glGetTexLevelParameteri(GL_TEXTURE_2D,level,GL_TEXTURE_DEPTH_TYPE),
                glGetTexLevelParameteri(GL_TEXTURE_2D,level,GL_TEXTURE_STENCIL_SIZE));
    }
    static int sizedDepthFormat(int format,int bits,int type,int stencilBits){
        if(format==GL_DEPTH_COMPONENT&&stencilBits==0){
            if(type==GL_FLOAT&&bits==32)return GL_DEPTH_COMPONENT32F;
            if(type==GL_UNSIGNED_NORMALIZED)return switch(bits){
                case 16 -> GL_DEPTH_COMPONENT16;
                case 24 -> GL_DEPTH_COMPONENT24;
                case 32 -> GL_DEPTH_COMPONENT32;
                default -> throw new GpuFailureException("Unsupported Claude depth precision: "+bits);
            };
        }else if(format==GL_DEPTH_STENCIL&&stencilBits==8){
            if(type==GL_FLOAT&&bits==32)return GL_DEPTH32F_STENCIL8;
            if(type==GL_UNSIGNED_NORMALIZED&&bits==24)return GL_DEPTH24_STENCIL8;
        }
        throw new GpuFailureException("Unsupported Claude unsized depth storage: "+format+" / "+bits+" bits / type "+type+" / stencil "+stencilBits);
    }
    private void ensurePrivateDepth(int format){
        if(format!=GL_DEPTH_COMPONENT16&&format!=GL_DEPTH_COMPONENT24&&format!=GL_DEPTH_COMPONENT32
                &&format!=GL_DEPTH_COMPONENT32F&&format!=GL_DEPTH24_STENCIL8&&format!=GL_DEPTH32F_STENCIL8)
            throw new GpuFailureException("Unsupported Claude world depth format: "+format);
        if(privateDepth!=0&&privateDepthFormat==format)return;
        if(privateDepth!=0)glDeleteRenderbuffers(privateDepth);
        privateDepth=glGenRenderbuffers();privateDepthFormat=format;
        glBindRenderbuffer(GL_RENDERBUFFER,privateDepth);glRenderbufferStorage(GL_RENDERBUFFER,format,width,height);
        if(glGetRenderbufferParameteri(GL_RENDERBUFFER,GL_RENDERBUFFER_WIDTH)!=width)
            throw new GpuFailureException("Claude private depth allocation failed");
        targetAllocations++;
    }
    private int location(String name){return glGetUniformLocation(program,name);}
    private static void normalize(){
        glDisable(GL_SCISSOR_TEST);glDisable(GL_STENCIL_TEST);glDisable(GL_RASTERIZER_DISCARD);
        glDisable(GL_SAMPLE_ALPHA_TO_COVERAGE);glDisable(GL_FRAMEBUFFER_SRGB);glDisable(GL_POLYGON_OFFSET_FILL);
        glDisable(GL_DEPTH_TEST);glDepthMask(false);glDisable(GL_CULL_FACE);glDisable(GL_BLEND);
        glColorMask(true,true,true,true);glPolygonMode(GL_FRONT_AND_BACK,GL_FILL);
    }
    private static void draw(int target,int texture,int w,int h){
        glBindFramebuffer(GL_DRAW_FRAMEBUFFER,target);glViewport(0,0,w,h);
        glActiveTexture(GL_TEXTURE0);glBindTexture(GL_TEXTURE_2D,texture);glBindSampler(0,0);
        glDrawArrays(GL_TRIANGLES,0,3);
    }
    private void ensureSize(int requestedWidth,int requestedHeight){
        if(program==0){program=ClaudeBackend.link(VERTEX,FRAGMENT);shaderLinks++;vao=glGenVertexArrays();}
        if(ready(requestedWidth,requestedHeight))return;
        releaseTargets();int[] internal=internalSize(requestedWidth,requestedHeight);int w=internal[0],h=internal[1];
        try{
        int[] scene=target(w,h);sceneTexture=scene[0];sceneFramebuffer=scene[1];
        int[] light=target(w,h);lightTexture=light[0];glDeleteFramebuffers(light[1]);
        glBindFramebuffer(GL_DRAW_FRAMEBUFFER,sceneFramebuffer);
        glFramebufferTexture2D(GL_DRAW_FRAMEBUFFER,GL_COLOR_ATTACHMENT1,GL_TEXTURE_2D,lightTexture,0);
        glDrawBuffers(new int[]{GL_COLOR_ATTACHMENT0,GL_COLOR_ATTACHMENT1});
        if(glCheckFramebufferStatus(GL_DRAW_FRAMEBUFFER)!=GL_FRAMEBUFFER_COMPLETE)
            throw new GpuFailureException("Claude split-light HDR target allocation failed");
        width=w;height=h;
        for(int i=0;i<MAX_LEVELS;i++){
            w=Math.max(1,(w+1)/2);h=Math.max(1,(h+1)/2);levelWidth[i]=w;levelHeight[i]=h;
            int[] down=target(w,h);downTexture[i]=down[0];downFramebuffer[i]=down[1];
            int[] up=target(w,h);upTexture[i]=up[0];upFramebuffer[i]=up[1];
            levels++;if(w==1&&h==1)break;
        }
        targetWidth=requestedWidth;targetHeight=requestedHeight;
        }catch(RuntimeException|Error failure){releaseTargets();throw failure;}
    }
    private int[] target(int w,int h){
        int texture=glGenTextures(),framebuffer=0;
        try{
            glActiveTexture(GL_TEXTURE0);glBindTexture(GL_TEXTURE_2D,texture);
            glTexImage2D(GL_TEXTURE_2D,0,GL_RGBA16F,w,h,0,GL_RGBA,GL_FLOAT,0L);
            glTexParameteri(GL_TEXTURE_2D,GL_TEXTURE_MIN_FILTER,GL_LINEAR);glTexParameteri(GL_TEXTURE_2D,GL_TEXTURE_MAG_FILTER,GL_LINEAR);
            glTexParameteri(GL_TEXTURE_2D,GL_TEXTURE_WRAP_S,GL_CLAMP_TO_EDGE);glTexParameteri(GL_TEXTURE_2D,GL_TEXTURE_WRAP_T,GL_CLAMP_TO_EDGE);
            glTexParameteri(GL_TEXTURE_2D,GL_TEXTURE_MAX_LEVEL,0);
            framebuffer=glGenFramebuffers();glBindFramebuffer(GL_DRAW_FRAMEBUFFER,framebuffer);
            glFramebufferTexture2D(GL_DRAW_FRAMEBUFFER,GL_COLOR_ATTACHMENT0,GL_TEXTURE_2D,texture,0);
            if(glCheckFramebufferStatus(GL_DRAW_FRAMEBUFFER)!=GL_FRAMEBUFFER_COMPLETE)
                throw new GpuFailureException("Claude HDR post target allocation failed");
            targetAllocations++;return new int[]{texture,framebuffer};
        }catch(RuntimeException|Error e){if(framebuffer!=0)glDeleteFramebuffers(framebuffer);glDeleteTextures(texture);throw e;}
    }
    private void releaseTargets(){
        if(sceneTexture!=0)glDeleteTextures(sceneTexture);if(lightTexture!=0)glDeleteTextures(lightTexture);if(sceneFramebuffer!=0)glDeleteFramebuffers(sceneFramebuffer);
        sceneTexture=lightTexture=sceneFramebuffer=0;
        if(privateDepth!=0)glDeleteRenderbuffers(privateDepth);
        privateDepth=privateDepthFormat=depthSource=0;depthCopied=false;
        for(int i=0;i<MAX_LEVELS;i++){
            if(downTexture[i]!=0)glDeleteTextures(downTexture[i]);if(upTexture[i]!=0)glDeleteTextures(upTexture[i]);
            if(downFramebuffer[i]!=0)glDeleteFramebuffers(downFramebuffer[i]);if(upFramebuffer[i]!=0)glDeleteFramebuffers(upFramebuffer[i]);
            downTexture[i]=upTexture[i]=downFramebuffer[i]=upFramebuffer[i]=0;
        }
        width=height=targetWidth=targetHeight=levels=0;
    }
    boolean initialized(){return program!=0||sceneTexture!=0;}
    @Override public void close(){releaseTargets();if(program!=0)glDeleteProgram(program);if(vao!=0)glDeleteVertexArrays(vao);program=vao=0;}
}
