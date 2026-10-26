package dev.portablevfx.client.claude;

import dev.portablevfx.client.claude.ClaudeEffect.ModelShading;
import dev.portablevfx.client.claude.ClaudeEffect.Vec3;
import dev.portablevfx.client.claude.ClaudeSimulation.ParticleView;
import java.util.*;
import org.joml.Matrix3f;
import org.joml.Matrix4f;
import org.lwjgl.system.MemoryUtil;
import static org.lwjgl.opengl.GL33C.*;

/** Authored model-particle shading and opaque/outline/translucent passes; no substitute geometry. */
final class ClaudeModelRenderer implements AutoCloseable {
    private static final int STRIDE=12;
    private int program;
    private long shaderLinks,textureUploads,geometryUploads;
    private static final String VERTEX="""
        #version 330 core
        layout(location=0) in vec3 position;layout(location=1) in vec3 normal;
        layout(location=2) in vec2 uv;layout(location=3) in vec4 vertexColor;
        uniform mat4 viewProjection;uniform mat4 modelMatrix;uniform mat3 normalMatrix;
        uniform float outlineWidth;
        out vec3 worldPosition;out vec3 worldNormal;out vec2 texcoord;out vec4 srgbColor;
        void main(){vec4 world=modelMatrix*vec4(position+normal*outlineWidth,1.0);
            worldPosition=world.xyz;worldNormal=normalize(normalMatrix*normal);
            texcoord=uv;srgbColor=vertexColor;gl_Position=viewProjection*world;}
        """;
    private static final String FRAGMENT="""
        #version 330 core
        in vec3 worldPosition;in vec3 worldNormal;in vec2 texcoord;in vec4 srgbColor;
        uniform sampler2D baseTexture;uniform bool useTexture;uniform vec4 baseColorFactor;
        uniform vec3 cameraPosition;uniform vec3 particleColor;uniform float particleAlpha;uniform vec3 tint;
        uniform vec3 lightDirection;uniform float ambient;uniform float diffuse;uniform float wrap;
        uniform vec3 shadowTint;uniform float threshold;uniform float softness;uniform int shadingMode;
        uniform vec3 rimColor;uniform float rimPower;uniform float rimStrength;uniform float fresnelMin;
        uniform bool unlit;uniform bool doubleSided;uniform int alphaMode;uniform float alphaCutoff;
        uniform bool outlinePass;uniform vec3 outlineColor;
        layout(location=0) out vec4 color;layout(location=1) out vec4 light;
        void main(){
            vec3 n=normalize(worldNormal);vec3 v=normalize(cameraPosition-worldPosition);
            if(outlinePass){if(dot(n,v)>0.0)discard;
                color=vec4(pow(max(outlineColor,vec3(0)),vec3(2.2))*pow(max(particleColor,vec3(0)),vec3(2.2)),particleAlpha);
                light=vec4(0,0,0,particleAlpha);return;}
            if(alphaMode==2){if(dot(n,v)<0.0)n=-n;}
            else if(doubleSided&&!gl_FrontFacing)n=-n;
            vec4 texel=useTexture?texture(baseTexture,texcoord):vec4(1);
            if(alphaMode==1&&texel.a*baseColorFactor.a*srgbColor.a<alphaCutoff)discard;
            vec3 base=pow(max(srgbColor.rgb*texel.rgb*baseColorFactor.rgb,vec3(0)),vec3(2.2))*tint*pow(max(particleColor,vec3(0)),vec3(2.2));
            float fr=1.0-max(0.0,dot(n,v));vec3 rgb=base;
            if(!unlit){
                float nd=dot(n,normalize(lightDirection));
                if(shadingMode==1){float lit=softness>0.0?smoothstep(threshold-softness,threshold+softness,nd*0.5+0.5):step(threshold,nd*0.5+0.5);
                    rgb=base*(ambient+diffuse*mix(pow(max(shadowTint,vec3(0)),vec3(2.2)),vec3(1),lit));}
                else {float d=max(0.0,(nd+wrap)/(1.0+wrap));rgb=base*(ambient+diffuse*d);}
                rgb+=rimColor*rimStrength*pow(fr,rimPower);
            }
            float a=particleAlpha*texel.a;
            if(alphaMode==2)a*=baseColorFactor.a*(fresnelMin+(1.0-fresnelMin)*fr);
            if(a<=0.0)discard;color=vec4(rgb,a);light=vec4(0,0,0,a);
        }
        """;
    static final class GpuPrimitive {
        final ClaudeModel.Primitive source;int vao,vbo,ebo;
        GpuPrimitive(ClaudeModel.Primitive source){this.source=source;}
        void close(){if(vao!=0)glDeleteVertexArrays(vao);if(vbo!=0)glDeleteBuffers(vbo);if(ebo!=0)glDeleteBuffers(ebo);vao=vbo=ebo=0;}
    }
    static final class Asset implements AutoCloseable {
        final ClaudeModel source;final Map<ClaudeModel.Primitive,GpuPrimitive> primitives=new IdentityHashMap<>();
        final int[] images,samplers;boolean closed;
        Asset(ClaudeModel source){this.source=source;images=new int[source.images().size()];samplers=new int[source.samplers().size()];
            for(var mesh:source.meshes())for(var p:mesh.primitives())primitives.put(p,new GpuPrimitive(p));}
        boolean initialized(){return Arrays.stream(images).anyMatch(i->i!=0)||Arrays.stream(samplers).anyMatch(i->i!=0)||primitives.values().stream().anyMatch(p->p.vao!=0);}
        boolean ready(){return !closed&&Arrays.stream(images).allMatch(i->i!=0)&&Arrays.stream(samplers).allMatch(i->i!=0)&&primitives.values().stream().allMatch(p->p.vao!=0);}
        @Override public void close(){if(closed)return;closed=true;for(var p:primitives.values())p.close();for(int id:images)if(id!=0)glDeleteTextures(id);for(int id:samplers)if(id!=0)glDeleteSamplers(id);Arrays.fill(images,0);Arrays.fill(samplers,0);}
    }
    Asset install(ClaudeModel model){return new Asset(model);}
    boolean prewarmStep(Asset asset){
        if(asset.closed)throw new IllegalStateException("Closed model asset");
        if(program==0){program=ClaudeBackend.link(VERTEX,FRAGMENT);shaderLinks++;return false;}
        for(int i=0;i<asset.images.length;i++)if(asset.images[i]==0){uploadImage(asset,i);return false;}
        for(int i=0;i<asset.samplers.length;i++)if(asset.samplers[i]==0){
            var s=asset.source.samplers().get(i);int id=glGenSamplers();asset.samplers[i]=id;
            glSamplerParameteri(id,GL_TEXTURE_MAG_FILTER,s.magFilter());glSamplerParameteri(id,GL_TEXTURE_MIN_FILTER,s.minFilter());
            glSamplerParameteri(id,GL_TEXTURE_WRAP_S,s.wrapS());glSamplerParameteri(id,GL_TEXTURE_WRAP_T,s.wrapT());return false;
        }
        for(var mesh:asset.source.meshes())for(var p:mesh.primitives())if(asset.primitives.get(p).vao==0){uploadGeometry(asset.primitives.get(p));return false;}
        return true;
    }
    private void uploadImage(Asset asset,int index){
        var source=asset.source.images().get(index);int id=glGenTextures();java.nio.ByteBuffer pixels=null;
        try {
            glActiveTexture(GL_TEXTURE0);glBindSampler(0,0);glBindTexture(GL_TEXTURE_2D,id);
            var rgba=source.rgba();pixels=MemoryUtil.memAlloc(rgba.remaining());pixels.put(rgba).flip();
            glTexImage2D(GL_TEXTURE_2D,0,GL_RGBA8,source.width(),source.height(),0,GL_RGBA,GL_UNSIGNED_BYTE,pixels);
            glTexParameteri(GL_TEXTURE_2D,GL_TEXTURE_MIN_FILTER,GL_LINEAR_MIPMAP_LINEAR);glTexParameteri(GL_TEXTURE_2D,GL_TEXTURE_MAG_FILTER,GL_LINEAR);
            glTexParameteri(GL_TEXTURE_2D,GL_TEXTURE_WRAP_S,GL_REPEAT);glTexParameteri(GL_TEXTURE_2D,GL_TEXTURE_WRAP_T,GL_REPEAT);glGenerateMipmap(GL_TEXTURE_2D);
            if(glGetTexLevelParameteri(GL_TEXTURE_2D,0,GL_TEXTURE_WIDTH)!=source.width())throw new dev.portablevfx.client.render.gl.GpuFailureException("Model PNG upload failed");
            asset.images[index]=id;textureUploads++;
        } finally {if(pixels!=null)MemoryUtil.memFree(pixels);if(asset.images[index]==0)glDeleteTextures(id);}
    }
    private void uploadGeometry(GpuPrimitive gpu){
        var p=gpu.source;var pos=p.positions();var normal=p.normals();var uv=p.texcoords();var color=p.colors();
        float[] vertices=new float[p.vertexCount()*STRIDE];
        for(int i=0,o=0;i<p.vertexCount();i++){for(int j=0;j<3;j++)vertices[o++]=pos.get(i*3+j);for(int j=0;j<3;j++)vertices[o++]=normal.get(i*3+j);
            for(int j=0;j<2;j++)vertices[o++]=uv.get(i*2+j);for(int j=0;j<4;j++)vertices[o++]=color.get(i*4+j);}
        int[] indices=new int[p.indexCount()];p.indices().get(indices);
        try {
            gpu.vao=glGenVertexArrays();gpu.vbo=glGenBuffers();gpu.ebo=glGenBuffers();glBindVertexArray(gpu.vao);glBindBuffer(GL_ARRAY_BUFFER,gpu.vbo);
            glBufferData(GL_ARRAY_BUFFER,vertices,GL_STATIC_DRAW);glBindBuffer(GL_ELEMENT_ARRAY_BUFFER,gpu.ebo);glBufferData(GL_ELEMENT_ARRAY_BUFFER,indices,GL_STATIC_DRAW);
            int offset=0;int[] sizes={3,3,2,4};for(int i=0;i<sizes.length;i++){glEnableVertexAttribArray(i);glVertexAttribPointer(i,sizes[i],GL_FLOAT,false,STRIDE*4,(long)offset*4);offset+=sizes[i];}
            geometryUploads++;
        }catch(RuntimeException|Error e){gpu.close();throw e;}
    }
    /** Returns actual submitted index count including depth and outline passes; 0 (skipped this frame) when over the remaining frame budget. */
    int draw(Asset asset,ClaudeEffect.Model descriptor,ParticleView particle,Vec3 tint,Matrix4f vp,Vec3 origin,Vec3 camera,int remaining){return draw(asset,descriptor,particle,tint,vp,origin,camera,remaining,1);}
    int draw(Asset asset,ClaudeEffect.Model descriptor,ParticleView particle,Vec3 tint,Matrix4f vp,Vec3 origin,Vec3 camera,int remaining,float opacity){
        if(particle.size()<=0||particle.alpha()<=0||opacity<=0)return 0;
        var animation=particle.layer().animation();String clip=animation==null?null:animation.clip();
        double time=animation==null?0:particle.age()*animation.speed();boolean loop=animation!=null&&animation.loop();
        var pose=ClaudeModelAnimator.sample(asset.source,clip,time,loop);var shading=descriptor.shading();
        long cost=0;boolean faded=particle.alpha()*opacity<1;
        for(int node:asset.source.sceneOrder())if(asset.source.nodes().get(node).mesh()>=0)
            for(var p:asset.source.meshes().get(asset.source.nodes().get(node).mesh()).primitives()) {
                var material=asset.source.materials().get(p.material());boolean opaque=material.alphaMode()!=ClaudeModel.AlphaMode.BLEND;
                cost+=(long)p.indexCount()*(1+(opaque&&faded?1:0)+(opaque&&!material.noOutline()&&shading.outline().width()>0?1:0));
            }
        if(cost>remaining)return 0;
        while(!prewarmStep(asset)) { /* cold-load fallback is bounded by validated primitive/image counts */ }
        Matrix4f placement=placement(particle,origin);
        glUseProgram(program);matrix("viewProjection",vp);vec("cameraPosition",camera);vec("particleColor",particle.color());f("particleAlpha",particle.alpha()*opacity);vec("tint",tint);
        glUniform1i(location("baseTexture"),0);glActiveTexture(GL_TEXTURE0);
        vec("lightDirection",shading.lightDirection());f("ambient",shading.ambient());f("diffuse",shading.diffuse());f("wrap",shading.wrap());
        vec("shadowTint",shading.shadowTint());f("threshold",shading.threshold());f("softness",shading.softness());i("shadingMode",shading.mode().equals("toon")?1:0);
        vec("rimColor",shading.rimColor());f("rimPower",shading.rimPower());f("rimStrength",shading.rimStrength());f("fresnelMin",shading.translucentFresnelMin());vec("outlineColor",shading.outline().color());
        glEnable(GL_DEPTH_TEST);glDepthFunc(GL_LEQUAL);glFrontFace(GL_CCW);glEnable(GL_BLEND);
        glBlendEquationSeparate(GL_FUNC_ADD,GL_FUNC_ADD);glBlendFuncSeparate(GL_SRC_ALPHA,GL_ONE_MINUS_SRC_ALPHA,GL_ONE,GL_ONE_MINUS_SRC_ALPHA);
        if(faded&&opacity>=1){glColorMask(false,false,false,false);glDepthMask(true);pass(asset,pose,placement,descriptor,false,false);glColorMask(true,true,true,true);}
        glDepthMask(!faded);pass(asset,pose,placement,descriptor,false,false);
        if(shading.outline().width()>0){glDepthMask(!faded);pass(asset,pose,placement,descriptor,false,true);}
        glDepthMask(false);pass(asset,pose,placement,descriptor,true,false);
        glColorMask(true,true,true,true);glDepthMask(false);glDisable(GL_CULL_FACE);glBindSampler(0,0);
        return (int)cost;
    }
    private void pass(Asset asset,ClaudeModelAnimator.Pose pose,Matrix4f placement,ClaudeEffect.Model descriptor,boolean transparent,boolean outline){
        i("outlinePass",outline?1:0);f("outlineWidth",outline?descriptor.shading().outline().width():0);
        for(int node:asset.source.sceneOrder()) {
            int mesh=asset.source.nodes().get(node).mesh();if(mesh<0)continue;
            Matrix4f transform=new Matrix4f(placement).mul(pose.global(node));
            float determinant=transform.determinant3x3();if(Math.abs(determinant)<1e-12)continue;
            glFrontFace(determinant<0?GL_CW:GL_CCW);
            matrix("modelMatrix",transform);glUniformMatrix3fv(location("normalMatrix"),false,new Matrix3f(transform).invert().transpose().get(new float[9]));
            for(var p:asset.source.meshes().get(mesh).primitives()) {
                var m=asset.source.materials().get(p.material());if((m.alphaMode()==ClaudeModel.AlphaMode.BLEND)!=transparent||outline&&m.noOutline())continue;
                if(outline||m.doubleSided()||transparent)glDisable(GL_CULL_FACE);else{glEnable(GL_CULL_FACE);glCullFace(GL_BACK);}
                var color=m.baseColor();glUniform4f(location("baseColorFactor"),color.r(),color.g(),color.b(),color.a());
                i("unlit",m.unlit()?1:0);i("doubleSided",m.doubleSided()?1:0);i("alphaMode",m.alphaMode().ordinal());f("alphaCutoff",m.alphaCutoff());
                boolean textured=descriptor.textures().equals("baseColor")&&m.texture()>=0;i("useTexture",textured?1:0);
                if(textured){var texture=asset.source.textures().get(m.texture());glBindTexture(GL_TEXTURE_2D,asset.images[texture.image()]);glBindSampler(0,asset.samplers[texture.sampler()]);}
                else {glBindSampler(0,0);glBindTexture(GL_TEXTURE_2D,0);}
                var gpu=asset.primitives.get(p);glBindVertexArray(gpu.vao);glDrawElements(GL_TRIANGLES,p.indexCount(),GL_UNSIGNED_INT,0L);
            }
        }
    }
    static Matrix4f placement(ParticleView p,Vec3 origin){
        var b=p.basis();var r=b.right();var u=b.up();var f=b.forward();var pos=p.worldPosition().subtract(origin);var e=p.rotation3D();
        return new Matrix4f((float)r.x(),(float)r.y(),(float)r.z(),0,(float)u.x(),(float)u.y(),(float)u.z(),0,(float)f.x(),(float)f.y(),(float)f.z(),0,
                (float)pos.x(),(float)pos.y(),(float)pos.z(),1).scale((float)p.widthScale(),1,1).rotateY((float)e.y()).rotateX((float)e.x()).rotateZ((float)e.z()).scale((float)p.size());
    }
    long shaderLinks(){return shaderLinks;}long textureUploads(){return textureUploads;}long geometryUploads(){return geometryUploads;}
    private int location(String name){return glGetUniformLocation(program,name);}
    private void i(String name,int value){glUniform1i(location(name),value);}
    private void f(String name,double value){glUniform1f(location(name),(float)value);}
    private void vec(String name,Vec3 value){glUniform3f(location(name),(float)value.x(),(float)value.y(),(float)value.z());}
    private void matrix(String name,Matrix4f value){glUniformMatrix4fv(location(name),false,value.get(new float[16]));}
    boolean initialized(){return program!=0;}
    @Override public void close(){if(program!=0)glDeleteProgram(program);program=0;}
}
