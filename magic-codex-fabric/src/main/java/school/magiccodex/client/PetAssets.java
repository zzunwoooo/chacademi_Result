package school.magiccodex.client;
import java.util.*;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.util.Identifier;
final class PetAssets {
    record Region(Identifier id,int x,int y,int w,int h){}
    static final Map<String,Region> REGIONS=new LinkedHashMap<>();
    static {
        add("title_plate",62,177,2076,331);
        add("filter_track",182,233,1809,254);
        add("filter_active",363,239,1046,404);
        add("summon_button",120,154,1933,418);
        add("round_button",98,160,1059,896);
        add("favorite_off",247,262,759,717);
        add("paw",222,295,809,662);
        add("favorite_on",246,262,762,719);
        add("chevron_left",391,283,458,688);
        add("close",335,340,580,576);
        add("name_rule",146,297,1902,126);
        add("sparkle_node",375,371,505,512);
        add("nebula_soft",0,0,1672,941);
    }
    static void add(String id,int x,int y,int w,int h){REGIONS.put(id,new Region(Identifier.of("magiccodex","textures/gui/pets/"+id+".png"),x,y,w,h));}
    static void prepare(){var images=UiResources.images();images.beginFrame();for(var r:REGIONS.values())images.prepareRegion(r.id,r.x,r.y,r.w,r.h);images.endFrame();}
    static void draw(DrawContext c,String key,float x,float y,float w,float h,int tint){var r=REGIONS.get(key);UiResources.images().drawTexture(c,r.id,Math.round(x),Math.round(y),r.x,r.y,Math.round(w),Math.round(h),r.w,r.h,r.x+r.w,r.y+r.h,tint);}
    private PetAssets(){}
}
