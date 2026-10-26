package school.magiccodex.client;
import java.nio.file.*;
import java.util.List;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.*;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.multiplayer.ConnectScreen;
import net.minecraft.client.network.*;
import net.minecraft.client.util.ScreenshotRecorder;
import net.minecraft.component.DataComponentTypes;
import school.magiccodex.protocol.*;
public final class ReconfigurationLiveCheck {
    static ReconfigurationScreen screen(MinecraftClient c){if(!(c.currentScreen instanceof ReconfigurationScreen))throw new IllegalStateException("No reconfiguration UI");return (ReconfigurationScreen)c.currentScreen;}
    static void cmd(MinecraftClient c,String s){c.getNetworkHandler().sendChatCommand(s);}
    static void check(boolean b,String s){if(!b)throw new IllegalStateException(s);}
    static net.minecraft.nbt.NbtCompound item(MinecraftClient c,int slot){return c.player.getInventory().getStack(slot).get(DataComponentTypes.CUSTOM_DATA).copyNbt().getCompound("PublicBukkitValues");}
    static void apply(MinecraftClient c){var r=screen(c).snapshot();float scale=Math.min(screen(c).width/680f,screen(c).height/585f);screen(c).mouseClicked((screen(c).width-640*scale)/2+320*scale,(screen(c).height-550*scale)/2+505*scale,0);ReconfigurationClient.send(r.token(),0,r.mode());}
    static void select(MinecraftClient c,int mode){var r=screen(c).snapshot();ReconfigurationClient.send(r.token(),1,mode);}
    static void shot(MinecraftClient c,String s)throws Exception{Path p=Path.of("visual-check/reconfiguration-0280");Files.createDirectories(p);try(var im=ScreenshotRecorder.takeScreenshot(c.getFramebuffer())){im.writeTo(p.resolve(s+".png"));}}
    public static void register(){int[] phase={-2},ticks={0};int[] previous={0};long started=System.currentTimeMillis();ClientLifecycleEvents.CLIENT_STARTED.register(mc->ClientTickEvents.END_CLIENT_TICK.register(c->{try{
        if(System.currentTimeMillis()-started>230000)throw new IllegalStateException("Reconfiguration timeout "+phase[0]);if(c.getOverlay()!=null)return;
        if(phase[0]==-2){if(!Files.exists(Path.of("../../output/reconfiguration-system-v1/server-test/ready.txt")))return;c.options.pauseOnLostFocus=false;c.options.getGuiScale().setValue(2);c.onResolutionChanged();ConnectScreen.connect(c.currentScreen,c,ServerAddress.parse("127.0.0.1:25598"),new ServerInfo("Reconfiguration test","127.0.0.1:25598",ServerInfo.ServerType.OTHER),false,null);phase[0]=-1;return;}
        if(c.player==null||c.world==null)return;if(phase[0]==-1){if(c.currentScreen!=null)return;phase[0]=0;}
        c.getToastManager().clear();c.inGameHud.getChatHud().clear(false);if(++ticks[0]<50)return;ticks[0]=0;
        switch(phase[0]++){
            case 0->cmd(c,"reconfigfixture setup");
            case 1->cmd(c,"마력재구성 복구");
            case 2->{check(screen(c).snapshot().available(),"restore available");shot(c,"01-restore");apply(c);}
            case 3->{var r=screen(c).snapshot();check(r.state()==1&&r.credits()==1,"restore once: "+r);check(item(c,0).getInt("magiccodexbridge:enhance_attempts")==4,"restore one attempt");check(item(c,0).getInt("magiccodexbridge:enhance_successes")==2&&item(c,0).getDouble("magiccodexbridge:wand_power")==35,"preserve successes");shot(c,"02-restored");select(c,0);}
            case 4->{check(screen(c).snapshot().after().equals(List.of(24d,0d,0d)),"baseline");shot(c,"03-reset");apply(c);}
            case 5->{check(screen(c).snapshot().state()==1&&screen(c).snapshot().credits()==1,"reset once");check(item(c,0).getInt("magiccodexbridge:enhance_attempts")==0&&item(c,0).getInt("magiccodexbridge:enhance_successes")==0,"reset counters");check(item(c,0).getDouble("magiccodexbridge:wand_power")==24&&item(c,0).getDouble("magiccodexbridge:wand_mana")==0&&item(c,0).getDouble("magiccodexbridge:wand_haste")==0,"reset stats");check(ManaClient.maximum()==100&&ManaClient.haste()==0,"equipment refresh");select(c,2);}
            case 6->{shot(c,"04-affinity");apply(c);}
            case 7->{var r=screen(c).snapshot();check(r.state()==1&&r.nextAffinity()!=0&&r.credits()==1,"affinity changed");previous[0]=r.nextAffinity();check(item(c,1).getInt("magiccodexbridge:core_nodes")==7&&item(c,1).getDouble("magiccodexbridge:core_stat_gain")==4&&item(c,1).getDouble("magiccodexbridge:core_base_success")==.3,"core invariants");shot(c,"05-affinity-result");select(c,2);}
            case 8->apply(c);
            case 9->{check(screen(c).snapshot().credits()==0&&screen(c).snapshot().nextAffinity()!=previous[0],"second affinity");select(c,2);}
            case 10->{check(!screen(c).snapshot().available()&&screen(c).snapshot().message().contains("주문서"),"credit required");shot(c,"06-no-credit");select(c,0);}
            case 11->{check(!screen(c).snapshot().available(),"no changes no debit");c.currentScreen.close();cmd(c,"reconfigfixture legacy");}
            case 12->cmd(c,"마력재구성 초기화");
            case 13->{check(!screen(c).snapshot().available()&&screen(c).snapshot().message().contains("원본"),"legacy protected");shot(c,"07-legacy");c.currentScreen.close();cmd(c,"재구성관리 원본 20 0 0");}
            case 14->cmd(c,"마력재구성 초기화");
            case 15->{check(screen(c).snapshot().available(),"legacy baseline registered");cmd(c,"reconfigfixture change");}
            case 16->apply(c);
            case 17->{check(screen(c).snapshot().state()==2&&screen(c).snapshot().credits()==1,"changed item rejected, no debit");shot(c,"08-changed-item");c.currentScreen.close();cmd(c,"reconfigfixture verify");cmd(c,"강화");}
            case 18->{check(c.currentScreen instanceof EnhancementScreen,"enhancement integration");var r=((EnhancementScreen)c.currentScreen).snapshot();int affinity=item(c,1).getInt("magiccodexbridge:core_affinity");check(r.affinity()==affinity,"hover affinity");double[] base={4,10,2};for(int i=0;i<3;i++)check(Math.abs(r.gains().get(i)-base[i]*CoreAffinity.multiplier(i,affinity))<.0001,"actual gain "+i);c.currentScreen.close();
                c.options.getGuiScale().setValue(3);c.onResolutionChanged();c.setScreen(new ReconfigurationScreen(new ReconfigurationProtocol.Response(999,0,0,0,"오래된 별빛을 간직한 마법 학교의 특별한 수습 마법봉","",99999,0,0,-1,100,50,100,0,List.of(99999d,100000d,99999.9),List.of(12345d,12345d,12345d),true)));}
            case 19->{shot(c,"09-long-name-scale3");Files.writeString(Path.of("visual-check/reconfiguration-0280/done.txt"),"RECONFIG_E2E_OK");c.scheduleStop();}
        }
    }catch(Exception e){throw new IllegalStateException(e);}}));}
}
