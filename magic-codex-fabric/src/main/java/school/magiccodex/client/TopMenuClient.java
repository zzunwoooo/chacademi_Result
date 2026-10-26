package school.magiccodex.client;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.sound.PositionedSoundInstance;
import net.minecraft.sound.SoundEvents;
import net.minecraft.util.Util;

public final class TopMenuClient {
    private static TopMenuState state=new TopMenuState();
    private static TopMenuRenderer renderer;
    private TopMenuClient(){}
    public static void season(HudSeason value){state.season(value);}
    private static TopMenuRenderer renderer(){
        if(renderer==null)renderer=new TopMenuRenderer(MinecraftClient.getInstance(),state);
        return renderer;
    }
    public static void render(DrawContext ctx,long now){
        var c=MinecraftClient.getInstance();var w=c.getWindow();
        try(var customization=menuFrame(ctx)){
        renderer().render(ctx,w.getScaledWidth(),w.getScaledHeight(),HudPointer.x(),HudPointer.y(),HudPointer.active(),now);

        }
}
    public static void renderTooltip(DrawContext ctx){
        var c=MinecraftClient.getInstance();var w=c.getWindow();
        try(var customization=menuFrame(ctx)){
        renderer().renderTooltip(ctx,w.getScaledWidth(),w.getScaledHeight(),HudPointer.x(),HudPointer.y(),HudPointer.active());

        }
}
    public static boolean click(double x,double y,int button){
        if(HudCustomization.editingGesture())return false;var hudPoint=HudCustomization.pointer("menu",x,y);x=hudPoint[0];y=hudPoint[1];
        var c=MinecraftClient.getInstance();
        if(button!=0 || !PlayerHudClient.active() || c.options.hudHidden || !HudPointer.active())return false;
        float scale=PlayerHudLayout.of(c.getWindow().getScaledWidth(),c.getWindow().getScaledHeight()).scale();
        state.update(Util.getMeasuringTimeMs());
        float mx=(float)x/scale-6,my=(float)y/scale-3,base=renderer().baseWidth();
        int hit=state.hit(mx,my,base);
        if(hit==-2){
            if(state.insideDropdown(mx,my,base))return true;
            if(state.group()>=0){state.closeGroup();return true;}
            return false;
        }
        if(hit>=0&&hit<TopMenuState.LABELS.length&&!TopMenuState.enabled(hit))return true;
        c.getSoundManager().play(PositionedSoundInstance.master(SoundEvents.UI_BUTTON_CLICK.value(),1.15f,.23f));
        if(hit==-1){state.toggle(Util.getMeasuringTimeMs());return true;}
        if(TopMenuState.isGroup(hit)){state.toggleGroup(hit,Util.getMeasuringTimeMs());return true;}
        state.closeGroup();
        switch(hit){
            case 110 -> {MagicCodexClient.dismiss();c.setScreen(new StatsScreen());}
            case 111 -> EquipmentClient.open();
            case 112 -> TitleClient.open();
            case 140 -> MagicCodexClient.openFromMenu();
            case 141 -> PetClient.open();
            case 142 -> {MagicCodexClient.dismiss();c.setScreen(new ArtifactCodexScreen(null));}
            case 143 -> FishingClient.openCodex();   // 물고기 도감 (the server answers with the codex frame)
            case 120 -> SocialClient.open();
            case 121 -> {MagicCodexClient.dismiss();c.setScreen(new PartyScreen());}
            case 122 -> SchoolClient.open();
            case 3 -> TravelClient.open();   // 이동: the server opens the map window (same as /지도 · J)
            case 6 -> QuestClient.open();
            case 7 -> MailboxClient.open(null);
            case 8 -> {MagicCodexClient.dismiss();c.setScreen(new SettingsScreen(null));}
            case 5 -> {if(c.getNetworkHandler()!=null){c.setScreen(null);c.getNetworkHandler().sendChatCommand("큐디치");}}
            case 0 -> MileageClient.open();   // 마일리지샵 (2026-10-24): 마일리지 상점 창
            default -> {return false;}
        }
        return true;
    }
    private static HudCustomization.Frame menuFrame(DrawContext ctx){var c=MinecraftClient.getInstance();float s=PlayerHudLayout.of(c.getWindow().getScaledWidth(),c.getWindow().getScaledHeight()).scale();return HudCustomization.begin(ctx,"menu",6*s,3*s,state.width(renderer().baseWidth())*s,(state.group()<0?36:40+TopMenuState.dropdownHeight(state.group()))*s);}
    public static void verify(){if(renderer!=null)renderer.verify();}
    public static void close(){state.closeGroup();if(renderer!=null){renderer.close();renderer=null;}}
    public static void reset(){close();state=new TopMenuState();}
}
