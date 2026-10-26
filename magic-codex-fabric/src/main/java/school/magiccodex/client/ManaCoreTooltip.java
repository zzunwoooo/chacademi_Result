package school.magiccodex.client;

import java.util.List;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.item.ItemStack;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.text.*;
import net.minecraft.util.Identifier;

/** Presentation only. Server item metadata opts into this tooltip; no enhancement logic. */
final class ManaCoreTooltip {
    private static final Identifier PANEL=Identifier.of("magiccodex","textures/gui/tooltip/circuit-panel.png");
    private static final Identifier GLOW=Identifier.of("magiccodex","textures/gui/tooltip/mana-glow.png");
    private static final float[][] NODES={{82,55},{127,36},{127,9},{172,55}};
    private static final int[] ROUTE={0,1,2,1,3,1,0};
    private static net.minecraft.nbt.NbtCompound stored(ItemStack item){
        var d=item.get(DataComponentTypes.CUSTOM_DATA);
        return d==null?new net.minecraft.nbt.NbtCompound():d.copyNbt().getCompound("PublicBukkitValues");
    }
    static boolean matches(ItemStack item){
        var data=item.get(DataComponentTypes.CUSTOM_DATA);
        return stored(item).contains("magiccodexbridge:core_state")||(data!=null&&data.copyNbt().getBoolean("magiccodex_mana_core"));
    }
    static boolean unidentified(ItemStack item){
        var tag=stored(item);if(tag.contains("magiccodexbridge:core_state"))return tag.getInt("magiccodexbridge:core_state")==0;
        var d=item.get(DataComponentTypes.CUSTOM_DATA);return d!=null&&d.copyNbt().getBoolean("magiccodex_unidentified");
    }
    static int height(List<OrderedText> names,List<OrderedText> types,List<OrderedText> body){
        return 176+names.size()*11+types.size()*9+body.size()*10;
    }
    static void render(DrawContext c,TextRenderer font,int x,int y,int w,List<OrderedText> names,List<OrderedText> types,List<OrderedText> body,long now,ItemStack item,TooltipRarity.Grade rarity){
        boolean unidentified=unidentified(item);var saved=stored(item);boolean server=saved.contains("magiccodexbridge:core_state");boolean broken=server&&saved.getInt("magiccodexbridge:core_state")==2;int count=server?Math.clamp(saved.getInt("magiccodexbridge:core_nodes"),0,10):3;
        int left=x+10,inner=w-20,cy=y+12;
        for(var line:names){c.drawText(font,line,left,cy,0xFFEBD19F,false);cy+=11;}
        ItemTooltipRenderer.drawRarity(c,font,x,cy,w,rarity);
        for(var line:types){c.drawText(font,line,left,cy,0xFF9DA5B2,false);cy+=9;}
        divider(c,left,cy+3,inner);cy+=15;
        int[] icons={0,2,7};String[] labels=new String[3];for(int j=0;j<3;j++)labels[j]=school.magiccodex.protocol.CoreAffinity.label(j,saved.getInt("magiccodexbridge:core_affinity"));
        for(int i=0;i<3;i++){
            c.getMatrices().push();c.getMatrices().translate(left,cy,0);
            if(unidentified||broken){
                HudMesh.star(c,7,5,2.7f,0xFF80B6C3);
                String[] runes={"kael vorth","syl enara","oth velis"};
                Text unread=Text.literal(runes[i]).setStyle(Style.EMPTY.withFont(Identifier.of("minecraft","alt")).withColor(0xA9C6CF));
                c.drawText(font,unread,22,0,0xFFA9C6CF,false);
            }else{
                StatIcons.draw(c,icons[i],7,5,12);
                c.drawText(font,label(labels[i],"tooltip_section",0xE0E3EB),22,0,0xFFE0E3EB,false);
            }
            c.draw();c.getMatrices().pop();cy+=19;
        }
        divider(c,left,cy-5,inner);cy+=7;
        c.drawText(font,label("아이템 설명","tooltip_section",0xEBD19F),left,cy,0xFFEBD19F,false);cy+=13;
        for(var line:body){c.drawText(font,line,left,cy,0xFFB7BDC8,false);cy+=10;}
        cy+=8;
        c.drawText(font,label(server&&!unidentified&&!broken?"회로 미리보기 · "+count+"/10":"회로 미리보기","tooltip_section",0xEBD19F),left,cy,0xFFEBD19F,false);cy+=8;
        var cache=UiResources.images();
        cache.drawTexture(c,PANEL,left,cy,0,0,inner,46,2172,724,2172,724);
        if(unidentified||broken){
            int center=left+inner/2;
            cache.drawTexture(c,GLOW,center-16,cy+7,0,0,32,32,1254,1254,1254,1254,0x70FFFFFF);
            c.getMatrices().push();
            try{
                c.getMatrices().translate(center,cy+14,0);c.getMatrices().scale(2,2,1);
                Text question=label(broken?"×":"?","tooltip_name",0x8FE8EB);
                c.drawText(font,question,-font.getWidth(question)/2,0,0xFF8FE8EB,false);c.draw();
            }finally{c.getMatrices().pop();}
            divider(c,left,cy+48,inner);return;
        }
        float scale=inner/254f;
        c.getMatrices().push();c.getMatrices().translate(left,cy+5,0);c.getMatrices().scale(scale,scale,1);
        float[][] nodes=new float[count][2];
        for(int i=0;i<count;i++){double a=-Math.PI/2+i*Math.PI*2/Math.max(1,count);nodes[i][0]=127+(float)Math.cos(a)*72;nodes[i][1]=36+(float)Math.sin(a)*25;}
        for(int i=0;i<count;i++){
            float[] a=nodes[i],b=nodes[(i+1)%count];
            if(count>1){HudMesh.line(c,a[0],a[1],b[0],b[1],5,0x1266E6EC);HudMesh.line(c,a[0],a[1],b[0],b[1],2,0xFF66DCE2);}
            ring(c,a[0],a[1],4,now);
        }
        if(count>0){double t=(now%5000)/5000.0*count;int index=(int)t;float u=(float)(t-index);float[] a=nodes[index],b=nodes[(index+1)%count];float px=a[0]+(b[0]-a[0])*u,py=a[1]+(b[1]-a[1])*u;cache.drawTexture(c,GLOW,Math.round(px)-16,Math.round(py)-16,0,0,32,32,1254,1254,1254,1254);HudMesh.disk(c,px,py,1.5f,0xFFEFFFFF);}
        c.draw();c.getMatrices().pop();
        divider(c,left,cy+48,inner);
    }
    private static double distance(int a,int b){return Math.hypot(NODES[a][0]-NODES[b][0],NODES[a][1]-NODES[b][1]);}
    private static void ring(DrawContext c,float x,float y,float r,long now){
        for(int i=0;i<32;i++){
            double a=i*Math.PI/16,b=(i+1)*Math.PI/16;
            float ax=x+(float)Math.cos(a)*r,ay=y+(float)Math.sin(a)*r,bx=x+(float)Math.cos(b)*r,by=y+(float)Math.sin(b)*r;
            HudMesh.line(c,ax,ay,bx,by,5,0x1866E6EC);HudMesh.line(c,ax,ay,bx,by,2,0xFF66DCE2);
        }
    }
    private static Text label(String s,String face,int color){return Text.literal(s).setStyle(Style.EMPTY.withFont(Identifier.of("magiccodex",face)).withColor(color));}
    private static void divider(DrawContext c,int x,int y,int w){int m=x+w/2;c.fill(x,y,m-4,y+1,0x909C9384);c.fill(m+4,y,x+w,y+1,0x909C9384);HudMesh.star(c,m,y+.5f,2.5f,0xFFBDAC8F);}
    static void warm(HudTextureCache c){c.prepareRegion(PANEL,0,0,2172,724);c.prepareRegion(GLOW,0,0,1254,1254);}
}
