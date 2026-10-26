package school.magiccodex.client;

import java.awt.Font;
import java.util.Map;
import java.util.concurrent.*;
import net.fabricmc.fabric.api.resource.*;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.*;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.resource.*;
import net.minecraft.util.*;

/** Session-owned screen resources. Closing a screen never destroys shared GPU work. */
public final class UiResources {
    private static HudTextureCache images;
    private static CodexTypography text;
    private static int generation;
    /**
     * 아공간 배낭 전용 그림·글자 (2026-10-26 #101 "인벤 열 때 버벅임"). 공용 캐시(192장/128MB, 글자 768개)는 도감·상점 등
     * 큰 창을 보는 동안 배낭 그림(약 23MB)과 글자를 먼저 지운다 → 다음 E 때 PNG 디코드·밉맵 업로드·글자 굽기를 다시 하며
     * "화면을 준비하고 있어요" 가림막이 떴다. 배낭 것만 따로 두면 다른 창이 밀어내지 않는다 (배낭 그림 8장 + 글자 몇십 개).
     */
    private static HudTextureCache bagImages;
    private static CodexTypography bagText;
    private UiResources(){}
    public static HudTextureCache images(){if(images==null)images=new HudTextureCache(MinecraftClient.getInstance(),true);return images;}
    public static CodexTypography text(){if(text==null)text=new CodexTypography(MinecraftClient.getInstance());return text;}
    static HudTextureCache bagImages(){if(bagImages==null)bagImages=new HudTextureCache(MinecraftClient.getInstance(),true);return bagImages;}
    static CodexTypography bagText(){if(bagText==null)bagText=new CodexTypography(MinecraftClient.getInstance());return bagText;}
    public static int generation(){return generation;}
    public static void reset(){generation++;ItemTooltipRenderer.reset();ItemIconTextures.reset();if(images!=null)images.close();if(text!=null)text.close();images=null;text=null;
        if(bagImages!=null)bagImages.close();if(bagText!=null)bagText.close();bagImages=null;bagText=null;}
    static void initialize(){
        ItemIconTextures.initialize();
        ResourceManagerHelper.get(ResourceType.CLIENT_RESOURCES).registerReloadListener(new SimpleResourceReloadListener<Map<String,Font>>(){
            public Identifier getFabricId(){return Identifier.of("magiccodex","screen_resources");}
            public CompletableFuture<Map<String,Font>> load(ResourceManager manager,Executor executor){return CompletableFuture.supplyAsync(()->CodexTypography.loadFonts(manager),executor);}
            public CompletableFuture<Void> apply(Map<String,Font> fonts,ResourceManager manager,Executor executor){return CompletableFuture.runAsync(()->{reset();CodexTypography.installFonts(fonts);},executor);}
        });
        ClientPlayConnectionEvents.DISCONNECT.register((h,c)->reset());
        ClientLifecycleEvents.CLIENT_STOPPING.register(c->reset());
        // Warm only common panels; spell icons are loaded on demand, never all 200 at once.
        ClientTickEvents.END_CLIENT_TICK.register(c->{
            if(c.world==null||c.player==null||c.getOverlay()!=null||c.currentScreen!=null)return;
            var cache=images();cache.beginFrame();
            cache.prepareRegion(Identifier.of("magiccodex","textures/gui/social/friends_panel.png"),0,0,1340,1174);
            cache.prepareRegion(Identifier.of("magiccodex","textures/gui/social/wind_message_panel.png"),0,0,2048,768);
            cache.prepareRegion(Identifier.of("magiccodex","textures/gui/codex_base.png"),0,0,1672,941);
            cache.prepareRegion(Identifier.of("magiccodex","textures/gui/stats_panel_clean.png"),0,0,1448,1086);
            StatIcons.warm(cache);
            ItemTooltipRenderer.warm(cache);
            cache.prepareRegion(Identifier.of("magiccodex","textures/gui/school/house_points.png"),0,0,1672,941);
            cache.prepareRegion(Identifier.of("magiccodex","textures/gui/equipment/panel.png"),0,0,1672,941);
            cache.prepareRegion(Identifier.of("magiccodex","textures/gui/equipment/panel_broom.png"),0,0,1672,1061);
            cache.prepareRegion(Identifier.of("magiccodex","textures/gui/spell_unavailable.png"),136,151,979,965);
            cache.prepareRegion(Identifier.of("magiccodex","textures/gui/equipment/broom_equip_slot.png"),72,186,2029,312);
            cache.prepareRegion(Identifier.of("magiccodex","textures/gui/equipment/broom_selection_card.png"),57,89,731,1652);
            cache.prepareRegion(Identifier.of("magiccodex","textures/gui/equipment/broom_selection_panel.png"),0,0,1672,941);
            cache.endFrame();
            // 아공간 배낭 (survival inventory, 2026-10-24): opened often, so warmed — in its own cache (#101, see bagImages).
            var bag=bagImages();bag.beginFrame();
            bag.prepareRegion(GalaxyBagScreen.BAG,0,0,GalaxyBagLayout.BAG_PNG_W,GalaxyBagLayout.BAG_PNG_H);
            bag.prepareRegion(GalaxyBagScreen.BAG_FILL,0,0,GalaxyBagScreen.BAG_FILL_W,GalaxyBagScreen.BAG_FILL_H);
            bag.prepareRegion(GalaxyBagScreen.PANEL,0,0,GalaxyBagLayout.PANEL_PNG_W,GalaxyBagLayout.PANEL_PNG_H);
            bag.prepareRegion(GalaxyBagScreen.PANEL_FILL,0,0,GalaxyBagScreen.PANEL_FILL_W,GalaxyBagScreen.PANEL_FILL_H);
            for(var icon:new Identifier[]{GalaxyBagScreen.SHIRT,GalaxyBagScreen.TABLE,GalaxyBagScreen.BOOK})bag.prepareRegion(icon,0,0,GalaxyBagScreen.ICON_PNG,GalaxyBagScreen.ICON_PNG);
            bag.prepareRegion(GalaxyBagScreen.PADLOCK,0,0,GalaxyBagScreen.PADLOCK_PNG,GalaxyBagScreen.PADLOCK_PNG);
            bag.endFrame();
        });
    }
    /** First-open progress covers incomplete artwork, while the game keeps rendering. */
    static final class Entrance {
        private boolean ready,waited;private int epoch=-1;private long fadeAt;
        boolean ready(){return ready&&epoch==generation;}
        void draw(DrawContext c,int w,int h){draw(c,w,h,images());}
        /** cache = the cache this screen's artwork comes from (the bag passes bagImages()). */
        void draw(DrawContext c,int w,int h,HudTextureCache cache){
            if(epoch!=generation){epoch=generation;ready=false;waited=false;fadeAt=0;}
            long now=Util.getMeasuringTimeMs();
            if(!ready && cache.missedThisFrame())waited=true;
            if(!ready && !cache.missedThisFrame()){ready=true;fadeAt=waited?now:now-140;}
            float opacity=ready?Math.max(0,1-(now-fadeAt)/140f):1;
            if(opacity<=0)return;
            c.getMatrices().push();c.getMatrices().translate(0,0,600);
            c.fill(0,0,w,h,((int)(244*opacity)<<24)|0x08141F);
            String label="화면을 준비하고 있어요";
            var renderer=MinecraftClient.getInstance().textRenderer;
            c.drawText(renderer,label,(w-renderer.getWidth(label))/2,h/2+13,((int)(255*opacity)<<24)|0xE6E0CA,false);
            for(int i=0;i<3;i++){float pulse=(float)(.35+.65*(.5+.5*Math.sin(now/180.0-i*1.2)));int color=((int)(255*opacity*pulse)<<24)|0x8FD9DE;HudMesh.disk(c,w/2f-12+i*12,h/2f-5,2.5f,color);}
            c.getMatrices().pop();
        }
    }
}
