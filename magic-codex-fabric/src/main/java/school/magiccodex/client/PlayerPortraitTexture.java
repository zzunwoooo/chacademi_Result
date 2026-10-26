package school.magiccodex.client;

import com.mojang.blaze3d.systems.RenderSystem;
import java.io.ByteArrayInputStream;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gl.Defines;
import net.minecraft.client.gl.ShaderProgramKey;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.render.RenderLayer;
import net.minecraft.client.render.VertexFormat;
import net.minecraft.client.render.VertexFormats;
import net.minecraft.client.texture.NativeImage;
import net.minecraft.client.texture.NativeImageBackedTexture;
import net.minecraft.util.Identifier;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;
import org.lwjgl.opengl.GL30;

/**
 * 내 일러스트 GPU 텍스처 (서버에서 받은 PNG). NPC 초상화와 같은 premultiplied-alpha + trilinear 밉맵 방식으로 그린다.
 * 렌더 스레드에서만 만들고 지운다.
 */
final class PlayerPortraitTexture implements AutoCloseable {
    private static final ShaderProgramKey PROGRAM=new ShaderProgramKey(
            Identifier.of("magiccodex","core/hud_premultiplied"),VertexFormats.POSITION_TEXTURE_COLOR,Defines.EMPTY);
    private static int serial;
    private final Identifier id;
    private final RenderLayer layer;
    private final int width,height;
    private final PlayerTurnPortraitLayout.Bounds bounds;
    private boolean closed;

    private PlayerPortraitTexture(Identifier id,int width,int height,PlayerTurnPortraitLayout.Bounds bounds){
        this.bounds=bounds;
        this.id=id;this.width=width;this.height=height;this.layer=new PortraitLayer(MinecraftClient.getInstance(),id);
    }

    /** CPU decode and premultiplication: worker thread, no OpenGL. */
    static NativeImage decode(byte[] png,int maxSide)throws Exception{
        int[] size=pngSize(png);
        if(size[0]<=0||size[1]<=0||size[0]>maxSide||size[1]>maxSide)throw new IllegalArgumentException("portrait size");
        NativeImage image=NativeImage.read(new ByteArrayInputStream(png));
        try{image.apply(PremultipliedAlpha::pixel);return image;}
        catch(Throwable error){image.close();throw error;}
    }

    /** Takes ownership of the decoded image. GPU work is render-thread only. */
    static PlayerPortraitTexture upload(NativeImage image,PlayerTurnPortraitLayout.Bounds bounds)throws Exception{
        RenderSystem.assertOnRenderThread();
        NativeImageBackedTexture texture=null;
        try{
            int w=image.getWidth(),h=image.getHeight();
            texture=new NativeImageBackedTexture(image);image=null;
            int levels=31-Integer.numberOfLeadingZeros(Math.max(w,h));
            texture.bindTexture();
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D,GL12.GL_TEXTURE_MAX_LEVEL,levels);
            GL11.glTexParameterf(GL11.GL_TEXTURE_2D,GL12.GL_TEXTURE_MIN_LOD,0);
            GL11.glTexParameterf(GL11.GL_TEXTURE_2D,GL12.GL_TEXTURE_MAX_LOD,levels);
            GL30.glGenerateMipmap(GL11.GL_TEXTURE_2D);
            texture.setClamp(true);
            texture.setFilter(true,true);
            var id=Identifier.of("magiccodex","player_portrait/"+(serial++));
            MinecraftClient.getInstance().getTextureManager().registerTexture(id,texture);
            texture=null;
            return new PlayerPortraitTexture(id,w,h,bounds);
        }finally{
            if(texture!=null)texture.close();
            if(image!=null)image.close();
        }
    }

    /** PNG IHDR에서 가로·세로 (디코드 전에 크기 확인). */
    static int[] pngSize(byte[] png){
        if(png==null||png.length<24||(png[0]&0xFF)!=0x89||png[1]!='P'||png[2]!='N'||png[3]!='G')return new int[]{-1,-1};
        int w=((png[16]&0xFF)<<24)|((png[17]&0xFF)<<16)|((png[18]&0xFF)<<8)|(png[19]&0xFF);
        int h=((png[20]&0xFF)<<24)|((png[21]&0xFF)<<16)|((png[22]&0xFF)<<8)|(png[23]&0xFF);
        return new int[]{w,h};
    }

    int width(){return width;}
    int height(){return height;}

    /** (x,y)부터 가로 w에 맞춰 비율 유지로 그린다 (NPC 초상화 1024x1536 → 700x1050과 같은 배치). */
    void draw(DrawContext c,int x,int y,int w){
        if(closed)return;
        int h=Math.round(w*(float)height/width);
        c.drawTexture(any->layer,id,x,y,0,0,w,h,width,height,width,height,0xFFFFFFFF);
    }

    /**
     * Round face crop for small avatars (결투장): a square at the top of the drawn figure (its alpha bounds), as wide as about
     * 45 % of the figure, centred. Tuned for bust / upper-body illustrations.
     */
    void drawFace(DrawContext c,float cx,float cy,float radius){
        if(closed)return;
        float side=Math.min(bounds.width()*.46f,bounds.height()*.34f);
        float fx=bounds.x()+bounds.width()/2f-side/2,fy=bounds.y()+side*.06f;
        HudMesh.texturedDisk(c,layer,cx,cy,radius,fx/width,fy/height,(fx+side)/width,(fy+side)/height,0xFFFFFFFF);
    }

    void drawTurn(DrawContext c){
        var p=PlayerTurnPortraitLayout.place(width,height,bounds);
        draw(c,p.x(),p.y(),p.width());
    }

    @Override public void close(){
        RenderSystem.assertOnRenderThread();
        if(closed)return;closed=true;
        MinecraftClient.getInstance().getTextureManager().destroyTexture(id);
    }

    private static final class PortraitLayer extends RenderLayer {
        PortraitLayer(MinecraftClient client,Identifier id){
            super("magiccodex_player_portrait",VertexFormats.POSITION_TEXTURE_COLOR,VertexFormat.DrawMode.QUADS,1536,false,false,
                    ()->{
                        RenderSystem.enableBlend();
                        RenderSystem.blendFuncSeparate(GL11.GL_ONE,GL11.GL_ONE_MINUS_SRC_ALPHA,GL11.GL_ONE,GL11.GL_ONE_MINUS_SRC_ALPHA);
                        RenderSystem.disableCull();
                        RenderSystem.enableDepthTest();
                        RenderSystem.depthFunc(GL11.GL_LEQUAL);
                        RenderSystem.depthMask(false);
                        RenderSystem.setShader(PROGRAM);
                        RenderSystem.setShaderTexture(0,id);
                        client.getTextureManager().getTexture(id).setFilter(true,true);
                    },()->{
                        RenderSystem.depthMask(true);
                        RenderSystem.enableCull();
                        RenderSystem.disableBlend();
                        RenderSystem.defaultBlendFunc();
                    });
        }
    }
}
