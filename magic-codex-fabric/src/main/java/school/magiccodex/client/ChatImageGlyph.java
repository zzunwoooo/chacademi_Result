package school.magiccodex.client;
import net.minecraft.client.font.*;
import net.minecraft.client.render.VertexConsumer;
import org.joml.Matrix4f;
/** Direct full-resolution GUI texture quad, never copied into a bitmap font atlas.
 * FontStorage only reserves layout space. ChatPlus retains wrapping, scrolling and hit testing. */
public final class ChatImageGlyph extends BakedGlyph {
 public ChatImageGlyph(TextRenderLayerSet layers){super(layers,0,1,0,1,0,ChatImageMetrics.SIZE,ChatImageMetrics.TOP,ChatImageMetrics.BOTTOM);}
 @Override public void draw(DrawnGlyph glyph,Matrix4f matrix,VertexConsumer vertices,int light){
  // Preserve row fade alpha, but prevent nickname tint, fake bold, italic skew and text shadows on artwork.
  var image=new DrawnGlyph(glyph.x(),glyph.y(),ChatImageMetrics.untinted(glyph.color()),0,this,
    glyph.style().withBold(false).withItalic(false),0,0);
  super.draw(image,matrix,vertices,light);
 }
}
