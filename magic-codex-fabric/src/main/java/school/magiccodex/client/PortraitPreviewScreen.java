package school.magiccodex.client;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
final class PortraitPreviewScreen extends Screen {
 private static final Identifier FONT=Identifier.of("magiccodex","label"),BOLD=Identifier.of("magiccodex","hud_bold");
 PortraitPreviewScreen(){super(Text.literal("내 일러스트"));}
 @Override public boolean shouldPause(){return false;}
 @Override public void renderBackground(DrawContext c,int x,int y,float delta){}
 @Override public void tick(){if(client==null||client.player==null||client.world==null)close();}
 @Override public void render(DrawContext c,int mouseX,int mouseY,float delta){
  var images=UiResources.images();images.beginFrame();UiResources.text().beginFrame();
  try{
   c.fill(0,0,width,height,0xEB101D2C);
   label(c,PortraitClient.displayName()+" · 내 일러스트",width/2f,28,22,0xFFE8D19B,BOLD,true);
   label(c,"닫기 ×",width-62,28,18,0xFFE3E8EF,FONT,true);
   if(PortraitClient.ready()){
    var box=PortraitPreviewLayout.fit(width,height,PortraitClient.imageWidth(),PortraitClient.imageHeight());
    PortraitClient.draw(c,box.x(),box.y(),box.width());
   }else label(c,PortraitClient.status(),width/2f,height/2f,20,0xFFD5E1EE,FONT,true);
   label(c,"저장된 내 일러스트 · ESC 닫기",width/2f,height-24,16,0xFFB8C4D0,FONT,true);
  }finally{images.endFrame();}
 }
 private static void label(DrawContext c,String text,float x,float y,float size,int color,Identifier font,boolean center){
  UiResources.text().draw(c,text,x,y,size,color,font,center);
 }
 @Override public boolean mouseClicked(double x,double y,int button){
  if(button==0&&x>=width-116&&y>=8&&y<=48){close();return true;}
  return super.mouseClicked(x,y,button);
 }
 @Override public void close(){if(client!=null)client.setScreen(null);}
}
