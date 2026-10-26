package school.magiccodex.client;

import java.nio.file.*;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.*;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.multiplayer.ConnectScreen;
import net.minecraft.client.network.*;
import net.minecraft.client.util.ScreenshotRecorder;
import school.magiccodex.protocol.EquipmentProtocol;

/** Isolated live Paper/Fabric integration fixture, never packaged in the mod. */
public final class EquipmentLiveCheck {
    public static void register(){int[] phase={-2},ticks={0};long start=System.currentTimeMillis();
        ClientLifecycleEvents.CLIENT_STARTED.register(started->ClientTickEvents.END_CLIENT_TICK.register(c->{
            if(System.currentTimeMillis()-start>300000)throw new IllegalStateException("Equipment timeout "+phase[0]);
            if(c.getOverlay()!=null)return;
            if(phase[0]==-2&&c.currentScreen!=null){
                if(!Files.exists(Path.of("../../output/equipment-ui-v1/server-test/ready.txt")))return;
                c.options.pauseOnLostFocus=false;c.options.getGuiScale().setValue(2);c.options.getViewDistance().setValue(3);c.onResolutionChanged();c.getTutorialManager().setStep(net.minecraft.client.tutorial.TutorialStep.NONE);
                ConnectScreen.connect(c.currentScreen,c,ServerAddress.parse("127.0.0.1:25597"),new ServerInfo("Equipment isolated test","127.0.0.1:25597",ServerInfo.ServerType.OTHER),false,null);phase[0]=-1;return;
            }
            if(c.player==null||c.world==null)return;
            if(phase[0]==-1){if(c.currentScreen!=null)return;phase[0]=0;ticks[0]=0;}
            c.inGameHud.getChatHud().clear(false);if(++ticks[0]<60)return;ticks[0]=0;
            try{switch(phase[0]++){
                case 0->{server(c,"clear @s");server(c,"gamemode survival");server(c,"item replace entity @s weapon.mainhand with minecraft:amethyst_shard 2");server(c,"장비설정 artifact 12 4 40 2");}
                case 1->EquipmentClient.open();
                case 2->{check(c.currentScreen instanceof EquipmentScreen,"Open equipment");check(EquipmentClient.data!=null,"Server snapshot");check(EquipmentClient.data.candidates().size()==1,"One eligible accessory");shot(c,"01-empty");request(1,4,EquipmentClient.data.candidates().getFirst().inventory());}
                case 3->{check(EquipmentClient.data.mana()==40&&EquipmentClient.data.health()==4,"Equipment bonuses");check(c.player.getMaxHealth()==24,"Actual health modifier");check(!EquipmentClient.equipped.get(4).isEmpty(),"Accessory equipped");check(c.player.getInventory().getStack(0).getCount()==1,"One stacked item consumed");shot(c,"02-equipped");request(1,4,0);}
                case 4->{check(c.player.getMaxHealth()==24&&EquipmentClient.data.mana()==40,"Swap does not stack modifiers");check(c.player.getInventory().getStack(0).getCount()==1,"Old accessory returned");request(2,4,0);}
                case 5->{check(c.player.getMaxHealth()==20&&EquipmentClient.data.mana()==0,"Removal clears bonuses");check(EquipmentClient.equipped.get(4).isEmpty(),"Removed");server(c,"item replace entity @s hotbar.1 with minecraft:diamond_helmet");}
                case 6->request(0,0,0);
                case 7->{request(1,0,1);}
                case 8->{check(!c.player.getInventory().getArmorStack(3).isEmpty(),"Vanilla armor authoritative");shot(c,"03-armor");request(1,4,0);}
                case 9->{check(c.player.getMaxHealth()==24,"Reequipped accessory");server(c,"give @s stone 2304");}
                case 10->request(0,0,0);
                case 11->request(2,4,0);
                case 12->{check(!EquipmentClient.equipped.get(4).isEmpty()&&c.player.getMaxHealth()==24,"Full inventory rejects removal");check(EquipmentClient.notice.contains("빈 칸"),"Full inventory notice");server(c,"clear @s minecraft:stone");}
                case 13->{request(0,0,0);}
                case 14->{server(c,"item replace entity @s hotbar.0 with minecraft:dirt");request(1,4,0);}
                case 15->{check(!EquipmentClient.equipped.get(4).isEmpty(),"Stale inventory cannot overwrite equipment");check(EquipmentClient.notice.contains("변경"),"Stale notice");server(c,"gamerule keepInventory true");server(c,"kill @s");}
                case 16->{c.player.requestRespawn();}
                case 17->{EquipmentClient.open();}
                case 18->{check(!EquipmentClient.equipped.get(4).isEmpty()&&c.player.getMaxHealth()==24,"KeepInventory retains accessory and reapplies modifier");shot(c,"04-respawn");server(c,"gamerule keepInventory false");server(c,"kill @s");}
                case 19->{c.player.requestRespawn();}
                case 20->{EquipmentClient.open();}
                case 21->{check(EquipmentClient.equipped.get(4).isEmpty()&&c.player.getMaxHealth()==20,"Death drops accessory and removes bonuses");server(c,"deop CodexPreview");server(c,"item replace entity @s hotbar.0 with minecraft:diamond");}
                case 22->{server(c,"장비설정 artifact 999 200 999 999");request(0,0,0);}
                case 23->{check(EquipmentClient.data.mana()==0,"Unauthorized admin command cannot grant modifiers");shot(c,"05-final");Path out=Path.of("visual-check/equipment-0220");Files.createDirectories(out);Files.writeString(out.resolve("done.txt"),"EQUIPMENT_E2E_OK: equip, swap, remove, armor, stacked items, full inventory, stale request, keepInventory and death, permissions");c.scheduleStop();}
            }}catch(Exception e){throw new IllegalStateException("Equipment phase "+phase[0],e);}
        }));
    }
    private static void request(int action,int slot,int inventory){check(EquipmentClient.request(action,slot,inventory),"Request accepted "+action);}
    private static void server(MinecraftClient c,String s){c.getNetworkHandler().sendChatCommand(s);}
    private static void check(boolean ok,String s){if(!ok)throw new IllegalStateException(s);}
    private static void shot(MinecraftClient c,String name)throws Exception{Path out=Path.of("visual-check/equipment-0220");Files.createDirectories(out);try(var im=ScreenshotRecorder.takeScreenshot(c.getFramebuffer())){im.writeTo(out.resolve(name+".png"));}}
}
