package dev.portablevfx.client.render.gl;

import org.lwjgl.opengl.GL;
import static org.lwjgl.opengl.GL33C.*;

/** Raw GL restore leaves Minecraft's cached state consistent with the actual pre-call state. */
public final class GlStateSnapshot implements AutoCloseable {
    private final int program = glGetInteger(GL_CURRENT_PROGRAM), vao = glGetInteger(GL_VERTEX_ARRAY_BINDING);
    private final int array = glGetInteger(GL_ARRAY_BUFFER_BINDING), element = glGetInteger(GL_ELEMENT_ARRAY_BUFFER_BINDING);
    private final int drawFb = glGetInteger(GL_DRAW_FRAMEBUFFER_BINDING), readFb = glGetInteger(GL_READ_FRAMEBUFFER_BINDING);
    private final int renderbuffer = glGetInteger(GL_RENDERBUFFER_BINDING), active = glGetInteger(GL_ACTIVE_TEXTURE);
    private final int packBuffer = glGetInteger(GL_PIXEL_PACK_BUFFER_BINDING), unpackBuffer = glGetInteger(GL_PIXEL_UNPACK_BUFFER_BINDING);
    private final int[] viewport = ints(GL_VIEWPORT, 4), scissorBox = ints(GL_SCISSOR_BOX, 4);
    private final int[] polygonMode = ints(GL_POLYGON_MODE, 2);
    private final int srcRgb = glGetInteger(GL_BLEND_SRC_RGB), dstRgb = glGetInteger(GL_BLEND_DST_RGB);
    private final int srcAlpha = glGetInteger(GL_BLEND_SRC_ALPHA), dstAlpha = glGetInteger(GL_BLEND_DST_ALPHA);
    private final int eqRgb = glGetInteger(GL_BLEND_EQUATION_RGB), eqAlpha = glGetInteger(GL_BLEND_EQUATION_ALPHA);
    private final int depthFunc = glGetInteger(GL_DEPTH_FUNC), cullMode = glGetInteger(GL_CULL_FACE_MODE), frontFace = glGetInteger(GL_FRONT_FACE);
    private final boolean depthMask = glGetBoolean(GL_DEPTH_WRITEMASK);
    private final int[] colorMask = ints(GL_COLOR_WRITEMASK, 4);
    private final float[] blendColor = floats(0x8005, 4), depthRange = floats(GL_DEPTH_RANGE, 2);
    private final float lineWidth = glGetFloat(GL_LINE_WIDTH), offsetFactor = glGetFloat(GL_POLYGON_OFFSET_FACTOR), offsetUnits = glGetFloat(GL_POLYGON_OFFSET_UNITS);
    private final int[] caps = {GL_BLEND, GL_DEPTH_TEST, GL_CULL_FACE, GL_SCISSOR_TEST, GL_STENCIL_TEST, GL_POLYGON_OFFSET_FILL, GL_FRAMEBUFFER_SRGB, GL_RASTERIZER_DISCARD, GL_SAMPLE_ALPHA_TO_COVERAGE};
    private final boolean[] enabled = new boolean[caps.length];
    private final int[] pixelKeys = {GL_PACK_ALIGNMENT,GL_PACK_ROW_LENGTH,GL_PACK_SKIP_PIXELS,GL_PACK_SKIP_ROWS,GL_PACK_IMAGE_HEIGHT,GL_PACK_SKIP_IMAGES,GL_PACK_SWAP_BYTES,GL_PACK_LSB_FIRST,GL_UNPACK_ALIGNMENT,GL_UNPACK_ROW_LENGTH,GL_UNPACK_SKIP_PIXELS,GL_UNPACK_SKIP_ROWS,GL_UNPACK_IMAGE_HEIGHT,GL_UNPACK_SKIP_IMAGES,GL_UNPACK_SWAP_BYTES,GL_UNPACK_LSB_FIRST};
    private final int[] pixelValues = new int[pixelKeys.length];
    private final boolean hasSamplers = GL.getCapabilities().OpenGL33;
    /** PortableVFX binds only units 0 (particles/models/post source), 1 (bloom lower) and 2 (split light). */
    public static final int MOD_TEXTURE_UNITS = 3;
    private final int[] textures;
    private final int[] samplers;
    private boolean closed;

    public GlStateSnapshot() { this(MOD_TEXTURE_UNITS); }
    /** Saves/restores texture units 0..textureUnits-1 only; callers must not touch higher units. */
    public GlStateSnapshot(int textureUnits) {
        if (textureUnits < 0) throw new IllegalArgumentException("Negative texture unit count");
        textures = new int[Math.min(textureUnits, glGetInteger(GL_MAX_COMBINED_TEXTURE_IMAGE_UNITS))];
        samplers = new int[textures.length];
        for (int i=0;i<caps.length;i++) enabled[i] = glIsEnabled(caps[i]);
        for (int i=0;i<pixelKeys.length;i++) pixelValues[i] = glGetInteger(pixelKeys[i]);
        for (int i=0;i<textures.length;i++) {
            glActiveTexture(GL_TEXTURE0+i);
            textures[i] = glGetInteger(GL_TEXTURE_BINDING_2D);
            if (hasSamplers) samplers[i] = glGetInteger(GL_SAMPLER_BINDING);
        }
        glActiveTexture(active);
        // Texture uploads use CPU pointers, so inherited PBOs/row strides must not reinterpret them.
        glBindBuffer(GL_PIXEL_PACK_BUFFER, 0); glBindBuffer(GL_PIXEL_UNPACK_BUFFER, 0);
        for (int key : pixelKeys) glPixelStorei(key, key == GL_PACK_ALIGNMENT || key == GL_UNPACK_ALIGNMENT ? 1 : 0);
    }
    private static int[] ints(int key, int count) { int[] result = new int[count]; glGetIntegerv(key,result); return result; }
    private static float[] floats(int key, int count) { float[] result = new float[count]; glGetFloatv(key,result); return result; }
    public void close() {
        if (closed) return;
        closed=true;
        glUseProgram(program);
        glBindVertexArray(vao);
        glBindBuffer(GL_ARRAY_BUFFER,array);
        if (vao != 0) glBindBuffer(GL_ELEMENT_ARRAY_BUFFER,element);
        glBindFramebuffer(GL_DRAW_FRAMEBUFFER,drawFb); glBindFramebuffer(GL_READ_FRAMEBUFFER,readFb);
        glBindRenderbuffer(GL_RENDERBUFFER,renderbuffer);
        glBindBuffer(GL_PIXEL_PACK_BUFFER,packBuffer); glBindBuffer(GL_PIXEL_UNPACK_BUFFER,unpackBuffer);
        for (int i=0;i<textures.length;i++) { glActiveTexture(GL_TEXTURE0+i); glBindTexture(GL_TEXTURE_2D,textures[i]); if(hasSamplers) glBindSampler(i,samplers[i]); }
        glActiveTexture(active);
        for (int i=0;i<pixelKeys.length;i++) glPixelStorei(pixelKeys[i],pixelValues[i]);
        glViewport(viewport[0],viewport[1],viewport[2],viewport[3]); glScissor(scissorBox[0],scissorBox[1],scissorBox[2],scissorBox[3]);
        glBlendFuncSeparate(srcRgb,dstRgb,srcAlpha,dstAlpha); glBlendEquationSeparate(eqRgb,eqAlpha);
        glBlendColor(blendColor[0],blendColor[1],blendColor[2],blendColor[3]);
        glDepthFunc(depthFunc); glDepthMask(depthMask); glDepthRange(depthRange[0],depthRange[1]);
        glColorMask(colorMask[0]!=0,colorMask[1]!=0,colorMask[2]!=0,colorMask[3]!=0);
        glCullFace(cullMode); glFrontFace(frontFace); glPolygonMode(GL_FRONT_AND_BACK,polygonMode[0]);
        glLineWidth(lineWidth); glPolygonOffset(offsetFactor,offsetUnits);
        for(int i=0;i<caps.length;i++) { if(enabled[i]) glEnable(caps[i]); else glDisable(caps[i]); }
    }
}
