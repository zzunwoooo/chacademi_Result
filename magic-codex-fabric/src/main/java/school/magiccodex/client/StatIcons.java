package school.magiccodex.client;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.util.Identifier;
/** Transparent atlas regions retain aspect ratio and share an optical center. */
final class StatIcons {
    static final Identifier ATLAS=Identifier.of("magiccodex","textures/gui/stat_icons.png");
    private static final int[][] REGIONS={
        {82,78,279,286},{519,106,293,249},{1000,77,210,292},{1410,77,281,291},
        {88,501,268,304},{498,529,335,264},{973,511,296,278},{1405,508,287,289}
    };
    static void draw(DrawContext c,int index,int x,int y,int size){
        int[] r=REGIONS[index];float scale=(float)size/Math.max(r[2],r[3]);
        int w=Math.round(r[2]*scale),h=Math.round(r[3]*scale);
        UiResources.images().drawTexture(c,ATLAS,x-w/2,y-h/2,r[0],r[1],w,h,r[2],r[3],1774,887);
    }
    static void warm(HudTextureCache cache){for(int[] r:REGIONS)cache.prepareRegion(ATLAS,r[0],r[1],r[2],r[3]);}
}
