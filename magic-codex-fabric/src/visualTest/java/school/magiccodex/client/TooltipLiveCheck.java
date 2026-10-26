package school.magiccodex.client;
import java.nio.file.*;
import java.util.*;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.*;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.screen.multiplayer.ConnectScreen;
import net.minecraft.client.network.*;
import net.minecraft.client.util.ScreenshotRecorder;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.LoreComponent;
import net.minecraft.item.*;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
public final class TooltipLiveCheck {
    static class Preview extends Screen {
        final ItemStack item;final int mode;
        Preview(int mode){super(Text.literal("Tooltip preview"));this.mode=mode;item=new ItemStack(mode==0?Items.AMETHYST_SHARD:mode==1?Items.DIAMOND_SWORD:Items.APPLE);
            if(mode==0){var tag=new net.minecraft.nbt.NbtCompound();tag.putBoolean("magiccodex_mana_core",true);item.set(DataComponentTypes.CUSTOM_DATA,net.minecraft.component.type.NbtComponent.of(tag));}
            if(mode<2){item.set(DataComponentTypes.CUSTOM_NAME,Text.literal(mode==0?"마력코어":"수습 마법봉").formatted(Formatting.GOLD));
                var lore=new ArrayList<Text>();lore.add(Text.literal(mode==0?"강화 재료":"마법 장비").formatted(Formatting.GRAY));
                lore.add(Text.literal("마력이 흐르는 회로가 새겨져 있습니다.").formatted(Formatting.WHITE));
                lore.add(Text.literal("강화 시 회로를 따라 마력을 연결합니다.").formatted(Formatting.AQUA));
                if(mode==1)for(int i=0;i<35;i++)lore.add(Text.literal("연구 기록 "+(i+1)+" · 긴 설명도 잘리지 않게 확인합니다."));
                item.set(DataComponentTypes.LORE,new LoreComponent(lore));}
        }
        public void render(DrawContext c,int mx,int my,float d){
            c.fill(0,0,width,height,0x40101C29);
            c.fill(30,60,170,150,0xD0172938);
            c.drawText(textRenderer,"아이템 툴팁 확인",42,70,0xFFE8D6AA,false);
            c.drawItem(item,90,98);
            c.drawItemTooltip(textRenderer,item,mode==1?width-12:112,mode==1?height-15:100);
        }
        public boolean shouldPause(){return false;}
    }
    static class InventoryHover extends net.minecraft.client.gui.screen.ingame.InventoryScreen {
        InventoryHover(MinecraftClient c,int mode){super(c.player);c.player.getInventory().setStack(9,new Preview(mode).item);}
        @Override public void render(DrawContext c,int mx,int my,float d){var slot=getScreenHandler().slots.get(9);super.render(c,x+slot.x+8,y+slot.y+8,d);}
    }
    static class ChestHover extends net.minecraft.client.gui.screen.ingame.GenericContainerScreen {
        ChestHover(MinecraftClient c){super(net.minecraft.screen.GenericContainerScreenHandler.createGeneric9x3(42,c.player.getInventory(),new net.minecraft.inventory.SimpleInventory(27)),c.player.getInventory(),Text.literal("아이템 보관함"));var item=new Preview(0).item;var tag=item.get(DataComponentTypes.CUSTOM_DATA).copyNbt();tag.putBoolean("magiccodex_unidentified",true);item.set(DataComponentTypes.CUSTOM_DATA,net.minecraft.component.type.NbtComponent.of(tag));if(!ManaCoreTooltip.unidentified(item))throw new IllegalStateException("Unidentified marker lost");getScreenHandler().slots.getFirst().setStack(item);}
        @Override public void render(DrawContext c,int mx,int my,float d){var slot=getScreenHandler().slots.getFirst();super.render(c,x+slot.x+8,y+slot.y+8,d);}
    }
    static class CreativeHover extends net.minecraft.client.gui.screen.ingame.CreativeInventoryScreen {
        CreativeHover(MinecraftClient c){super(c.player,c.world.getEnabledFeatures(),true);}
        @Override public void render(DrawContext c,int mx,int my,float d){var slot=getScreenHandler().slots.stream().filter(t->t.hasStack()).findFirst().orElse(getScreenHandler().slots.getFirst());super.render(c,x+slot.x+8,y+slot.y+8,d);}
    }
    public static void register(){int[] phase={-2},ticks={0};long start=System.currentTimeMillis();
        ClientLifecycleEvents.CLIENT_STARTED.register(started->ClientTickEvents.END_CLIENT_TICK.register(c->{
            if(System.currentTimeMillis()-start>240000)throw new IllegalStateException("Tooltip timeout");
            if(c.getOverlay()!=null)return;
            if(phase[0]==-2&&c.currentScreen!=null){if(!Files.exists(Path.of("../../output/magic-haste-v1/server-test/ready.txt")))return;
                c.options.pauseOnLostFocus=false;c.options.getGuiScale().setValue(2);c.options.getViewDistance().setValue(3);c.onResolutionChanged();
                ConnectScreen.connect(c.currentScreen,c,ServerAddress.parse("127.0.0.1:25598"),new ServerInfo("Tooltip test","127.0.0.1:25598",ServerInfo.ServerType.OTHER),false,null);phase[0]=-1;return;}
            if(c.player==null||c.world==null)return;
            if(phase[0]==-1){if(c.currentScreen!=null)return;phase[0]=0;ticks[0]=0;}
            c.inGameHud.getChatHud().clear(false);if(++ticks[0]<70)return;ticks[0]=0;
            try{switch(phase[0]++){
                case 0->c.setScreen(new InventoryHover(c,0));
                case 1->{verify(c);shot(c,"01-inventory");c.setScreen(new ChestHover(c));}
                case 2->{verify(c);shot(c,"02-chest");c.setScreen(new InventoryHover(c,1));}
                case 3->{verify(c);shot(c,"03-long");c.setScreen(new InventoryHover(c,2));}
                case 4->{if(!ItemTooltipRenderer.localName(new ItemStack(Items.APPLE),Text.literal("Apple")).getString().equals("사과"))throw new IllegalStateException("Korean item name missing");verify(c);shot(c,"04-ordinary");c.currentScreen.close();c.getNetworkHandler().sendChatCommand("gamemode creative");}
                case 5->c.setScreen(new CreativeHover(c));
                case 6->{verify(c);shot(c,"05-creative");Path out=Path.of("visual-check/haste-0230");Files.createDirectories(out);Files.writeString(out.resolve("done.txt"),"HASTE_E2E_OK TOOLTIP_REAL_INVENTORY_CHEST_CREATIVE_OK");c.scheduleStop();}
            }}catch(Exception e){throw new IllegalStateException(e);}
        }));
    }
    static void verify(MinecraftClient c){if(!ItemTooltipRenderer.renderedOn(c.currentScreen))throw new IllegalStateException("Real slot hover did not reach renderer: "+c.currentScreen.getClass());}
    static void shot(MinecraftClient c,String name)throws Exception{Path p=Path.of("visual-check/mana-core-0255");Files.createDirectories(p);try(var im=ScreenshotRecorder.takeScreenshot(c.getFramebuffer())){im.writeTo(p.resolve(name+".png"));}}
}
