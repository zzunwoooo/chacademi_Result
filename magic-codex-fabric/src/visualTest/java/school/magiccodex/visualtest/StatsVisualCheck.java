package school.magiccodex.visualtest;

import java.nio.file.*;
import java.util.stream.IntStream;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.*;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.TitleScreen;
import net.minecraft.client.gui.screen.ChatScreen;
import net.minecraft.client.util.ScreenshotRecorder;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.resource.DataConfiguration;
import net.minecraft.world.*;
import net.minecraft.world.gen.GeneratorOptions;
import net.minecraft.world.gen.WorldPresets;
import net.minecraft.world.level.LevelInfo;
import net.minecraft.item.*;
import net.minecraft.entity.EquipmentSlot;
import org.lwjgl.glfw.GLFW;
import school.magiccodex.client.*;
import school.magiccodex.protocol.*;

/** Isolated world and real plugin-format payloads. Not packaged in the user mod. */
final class StatsVisualCheck {
    private static final java.util.UUID REMOTE=java.util.UUID.fromString("e3f66196-37da-4128-998c-5534d35cc481");
    private static final java.util.List<Integer> remoteRequests=new java.util.concurrent.CopyOnWriteArrayList<>();
    private static StatsProtocol.Profile remoteProfile;
    private static final java.util.List<Long> discoveryAcks=new java.util.concurrent.CopyOnWriteArrayList<>();
    static void register(){
        int[] phase={0},ticks={0};long start=System.currentTimeMillis();
        ClientLifecycleEvents.CLIENT_STARTED.register(initial->ClientTickEvents.END_CLIENT_TICK.register(c->{
            if(System.currentTimeMillis()-start>240000)throw new IllegalStateException("Stats check timeout "+phase[0]);
            if(c.getOverlay()!=null)return;
            if(phase[0]==0 && c.currentScreen!=null){
                var catalog=new SpellYamlLoader().load(Path.of("../../output/spells-finalization-v1/config/magiccodex/spells"));
                check(catalog.success()&&catalog.spells().size()==200,"200-spell fixture missing");
                CodexCatalog.permissions().replace(catalog.spells());
                ServerPlayNetworking.registerGlobalReceiver(DiscoveryClient.Ack.ID,(payload,context)->{long token=DiscoveryProtocol.request(payload.bytes());if(token>0)discoveryAcks.add(token);});
                ServerPlayNetworking.registerGlobalReceiver(RemoteStatsClient.Query.ID,(payload,context)->{
                    var r=StatsProtocol.decodeRequest(payload.bytes());
                    check(r.target().equals(REMOTE)&&r.session()==73,"Remote request wrong target/session");remoteRequests.add(r.action());
                    if(r.action()==StatsProtocol.FRIEND||r.action()==StatsProtocol.POPULARITY)ServerPlayNetworking.send(context.player(),new RemoteStatsClient.Reply(StatsProtocol.encodeResponse(new StatsProtocol.Response(StatsProtocol.NOTICE,73,REMOTE,"요청은 전달됐지만 해당 기능은 아직 준비 중입니다.",null))));
                    else if(r.action()==StatsProtocol.REFRESH)ServerPlayNetworking.send(context.player(),new RemoteStatsClient.Reply(StatsProtocol.encodeResponse(new StatsProtocol.Response(StatsProtocol.SNAPSHOT,73,REMOTE,"",remoteProfile))));
                });
                ServerPlayNetworking.registerGlobalReceiver(ManaClient.Request.ID,(payload,context)->{
                    ManaProtocol.decodeRequest(payload.bytes());
                    ServerPlayNetworking.send(context.player(),new ManaClient.Response(ManaProtocol.encode(
                        new ManaProtocol.Response(0,ManaProtocol.SNAPSHOT,0,new ManaProtocol.Snapshot(78,100,5)))));
                });
                ServerPlayNetworking.registerGlobalReceiver(PermissionPayloads.Request.ID,(payload,context)->{
                    try{
                        var r=PermissionProtocol.decodeRequest(payload.bytes());
                        if(r.action()==PermissionProtocol.OPEN)ServerPlayNetworking.send(context.player(),
                            new PermissionPayloads.Response(PermissionProtocol.encodeResponse(new PermissionProtocol.Response(r.id(),
                                IntStream.range(0,r.permissions().size()).mapToObj(i->i<62).toList()))));
                    }catch(Exception e){throw new IllegalStateException(e);}
                });
                c.options.pauseOnLostFocus=false;c.options.getGuiScale().setValue(2);c.options.getViewDistance().setValue(3);c.onResolutionChanged();
                c.getTutorialManager().setStep(net.minecraft.client.tutorial.TutorialStep.NONE);
                phase[0]=1;
                if(Files.exists(Path.of("saves/stats-014-visual/level.dat")))c.createIntegratedServerLoader().start("stats-014-visual",()->{});
                else c.createIntegratedServerLoader().createAndStart("stats-014-visual",
                    new LevelInfo("Stats preview",GameMode.SURVIVAL,false,Difficulty.PEACEFUL,true,new GameRules(net.minecraft.resource.featuretoggle.FeatureFlags.DEFAULT_ENABLED_FEATURES),DataConfiguration.SAFE_MODE),
                    new GeneratorOptions(42L,false,false),r->r.getOrThrow(RegistryKeys.WORLD_PRESET).getOrThrow(WorldPresets.FLAT).value().createDimensionsRegistryHolder(),new TitleScreen());
                return;
            }
            if(c.player==null||c.world==null||c.getServer()==null)return;
            if(phase[0]==7)pointAt(c,StatsLayout.BOTTOM_BUTTONS[1]);
            if(phase[0]==8)pointAt(c,StatsLayout.POPULARITY_BUTTONS[0]);
            if(phase[0]==1){
                if(c.currentScreen!=null)return;
                c.getServer().execute(()->{
                    var p=c.getServer().getPlayerManager().getPlayer(c.player.getUuid());
                    p.equipStack(EquipmentSlot.HEAD,new ItemStack(Items.LEATHER_HELMET));
                    p.equipStack(EquipmentSlot.CHEST,new ItemStack(Items.LEATHER_CHESTPLATE));
                    p.getInventory().setStack(0,new ItemStack(Items.BLAZE_ROD));
                });
                PlayerHudClient.frame(5);
                // Mimic another mod/vanilla binding owning P in the vanilla lookup.
                new net.minecraft.client.option.KeyBinding("key.stats_fixture.conflict",GLFW.GLFW_KEY_P,"category.fixture");
                GLFW.glfwFocusWindow(c.getWindow().getHandle());c.onWindowFocusChanged(true);
                key(c,GLFW.GLFW_PRESS);phase[0]=2;ticks[0]=0;return;
            }
            if(++ticks[0]<35)return;ticks[0]=0;
            try{
                if(phase[0]==2){
                    check(c.currentScreen instanceof StatsScreen,"P failed to open");
                    key(c,GLFW.GLFW_REPEAT);check(c.currentScreen instanceof StatsScreen,"Repeat closed screen");
                    key(c,GLFW.GLFW_RELEASE);
                    var v=StatsClient.values();
                    check(v.maxMana()!=null&&v.maxMana()==100&&v.mana()==78&&v.manaRegen()==5,"Mana payload not shown");
                    check(v.learned()!=null&&v.learned()==62,"Permission response not counted");
                    check(v.power()==null&&v.popularity()==null&&v.dormitory().isEmpty(),"Missing stats fabricated");
                    check(v.armor()>0&&v.health()==20,"Vanilla attributes not read");
                    screenshot(c,"01-live-1280");
                    key(c,GLFW.GLFW_PRESS);key(c,GLFW.GLFW_REPEAT);key(c,GLFW.GLFW_RELEASE);
                    check(c.currentScreen==null,"P failed to close");
                    c.setScreen(new ChatScreen(""));key(c,GLFW.GLFW_PRESS);key(c,GLFW.GLFW_RELEASE);
                    check(c.currentScreen instanceof ChatScreen,"P stole chat input");c.setScreen(null);
                    GLFW.glfwSetWindowSize(c.getWindow().getHandle(),1920,1080);
                    c.options.getGuiScale().setValue(3);c.onResolutionChanged();c.onWindowFocusChanged(true);
                    key(c,GLFW.GLFW_PRESS);key(c,GLFW.GLFW_RELEASE);phase[0]=3;
                }else if(phase[0]==3){
                    screenshot(c,"02-live-1920");
                    c.getServer().execute(()->ServerPlayNetworking.send(c.getServer().getPlayerManager().getPlayer(c.player.getUuid()),
                        new ManaClient.Response(ManaProtocol.encode(new ManaProtocol.Response(0,ManaProtocol.SNAPSHOT,0,new ManaProtocol.Snapshot(23,180,7.5))))));
                    StatsClient.updateAdditional(new StatsClient.Additional(24.0,12.0,"",9));phase[0]=4;
                }else if(phase[0]==4){
                    var v=StatsClient.values();check(v.mana()==23&&v.maxMana()==180&&v.manaRegen()==7.5,"Push update stale");
                    check(v.circle()==9&&v.power()==24,"Optional stats hook failed");
                    screenshot(c,"03-updated-class9");
                    StatsClient.updateAdditional(null);PlayerHudClient.frame(1);
                    c.options.getGuiScale().setValue(4);c.onResolutionChanged();phase[0]=5;
                }else if(phase[0]==5){
                    screenshot(c,"04-class1-gui4");
                    var l=StatsLayout.fit(c.currentScreen.width,c.currentScreen.height);
                    check(!c.currentScreen.mouseClicked(l.x()+StatsLayout.BOTTOM_BUTTONS[1].centerX()*l.scale(),l.y()+StatsLayout.BOTTOM_BUTTONS[1].centerY()*l.scale(),1),"Right click triggered social action");
                    check(c.currentScreen.mouseClicked(l.x()+StatsLayout.BOTTOM_BUTTONS[1].centerX()*l.scale(),l.y()+StatsLayout.BOTTOM_BUTTONS[1].centerY()*l.scale(),0),"Friend target missed");
                    check(c.currentScreen instanceof StatsScreen,"Placeholder closed stats");phase[0]=7;
                }else if(phase[0]==7){
                    screenshot(c,"05-friend-notice");
                    var l=StatsLayout.fit(c.currentScreen.width,c.currentScreen.height);
                    var calls=new java.util.ArrayList<StatsSocialActions.Action>();
                    StatsSocialActions.connect((action,target)->{check(target.equals(c.player.getUuid()),"Wrong target UUID");calls.add(action);return "테스트 연결 확인";});
                    check(c.currentScreen.mouseClicked(l.x()+StatsLayout.POPULARITY_BUTTONS[0].centerX()*l.scale(),l.y()+StatsLayout.POPULARITY_BUTTONS[0].centerY()*l.scale(),0),"Popularity target missed");
                    check(calls.isEmpty(),"Own popularity button must stay disabled");
                    c.currentScreen.mouseClicked(l.x()+StatsLayout.POPULARITY_BUTTONS[0].centerX()*l.scale(),l.y()+StatsLayout.POPULARITY_BUTTONS[0].centerY()*l.scale(),0);
                    check(calls.isEmpty(),"Disabled button sent an action");StatsSocialActions.reset();
                    check(StatsClient.values().popularity()==null,"UI fabricated popularity");phase[0]=8;
                }else if(phase[0]==8){
                    screenshot(c,"06-popularity-hover");
                    var l=StatsLayout.fit(c.currentScreen.width,c.currentScreen.height);
                    c.currentScreen.mouseClicked(l.x()+StatsLayout.CLOSE.centerX()*l.scale(),l.y()+StatsLayout.CLOSE.centerY()*l.scale(),0);
                    check(c.currentScreen==null,"Scaled close target failed");phase[0]=6;
                }else if(phase[0]==6){
                    check(CodexCatalog.permissions().active()==null,"Permission lease not closed");
                    System.out.println("STATS_VISUAL_OK: raw P with conflict/repeat/chat/close, real mana and permission payloads, pushed updates, optional fields, class 1/5/9, 1280/1920 GUI2/3/4, equipped player, GPU filter verified.");
                    var helmet=new ItemStack(Items.DIAMOND_HELMET);var nbt=(net.minecraft.nbt.NbtCompound)helmet.toNbt(c.world.getRegistryManager());nbt.putInt("DataVersion",4189);
                    var bytes=new java.io.ByteArrayOutputStream();net.minecraft.nbt.NbtIo.writeCompressed(nbt,bytes);
                    remoteProfile=new StatsProtocol.Profile(REMOTE,"RemoteMage",5,"바람의 기숙사",24.0,12.0,40,12,140.0,240.0,8.0,157,200,"","",java.util.List.of(new byte[0],new byte[0],new byte[0],new byte[0],new byte[0],bytes.toByteArray()));
                    c.setScreen(new ChatScreen("/스탯창 RemoteMage"));remoteReply(c,StatsProtocol.OPEN,73,REMOTE,remoteProfile);phase[0]=10;
                }else if(phase[0]==10){
                    var screen=(StatsScreen)c.currentScreen;var v=screen.displayedValues();
                    check(v.nickname().equals("RemoteMage")&&v.mana()==140&&v.learned()==157,"Wrong remote values");
                    check(screen.portraitPlayer().getUuid().equals(REMOTE),"Avatar is viewer instead of target");
                    check(screen.portraitPlayer().getEquippedStack(EquipmentSlot.HEAD).isOf(Items.DIAMOND_HELMET),"Remote equipment NBT failed");
                    check(ManaClient.current()==23&&ManaClient.maximum()==180,"Remote mana overwrote local HUD");
                    check(CodexCatalog.permissions().active()==null,"Remote view subscribed viewer permissions");
                    screenshot(c,"07-remote-profile");var l=StatsLayout.fit(screen.width,screen.height);
                    screen.mouseClicked(l.x()+StatsLayout.BOTTOM_BUTTONS[1].centerX()*l.scale(),l.y()+StatsLayout.BOTTOM_BUTTONS[1].centerY()*l.scale(),0);phase[0]=11;
                }else if(phase[0]==11){
                    check(java.util.Collections.frequency(remoteRequests,StatsProtocol.FRIEND)==1,"Friend request missing or duplicated");
                    screenshot(c,"08-remote-friend-response");
                    c.options.getGuiScale().setValue(3);c.onResolutionChanged();
                    var l=StatsLayout.fit(c.currentScreen.width,c.currentScreen.height);
                    c.currentScreen.mouseClicked(l.x()+StatsLayout.POPULARITY_BUTTONS[0].centerX()*l.scale(),l.y()+StatsLayout.POPULARITY_BUTTONS[0].centerY()*l.scale(),0);phase[0]=12;
                }else if(phase[0]==12){
                    check(!remoteRequests.contains(StatsProtocol.POPULARITY),"v2 server: popularity buttons must stay disabled");
                    check(!remoteRequests.contains(StatsProtocol.CLOSE),"Resize closed remote session");
                    check(remoteRequests.contains(StatsProtocol.REFRESH),"Open remote screen did not refresh");
                    // A stale old session must never replace a current profile.
                    remoteReply(c,StatsProtocol.SNAPSHOT,72,REMOTE,new StatsProtocol.Profile(REMOTE,"WrongMage",1,"",null,null,20,0,1.0,1.0,0.0,0,200,"","",java.util.Collections.nCopies(6,new byte[0])));
                    phase[0]=13;
                }else if(phase[0]==13){
                    check(((StatsScreen)c.currentScreen).displayedValues().nickname().equals("RemoteMage"),"Stale session replaced target");
                    remoteReply(c,StatsProtocol.GONE,73,REMOTE,null);phase[0]=14;
                }else if(phase[0]==14){
                    var l=StatsLayout.fit(c.currentScreen.width,c.currentScreen.height);
                    c.currentScreen.mouseClicked(l.x()+StatsLayout.POPULARITY_BUTTONS[0].centerX()*l.scale(),l.y()+StatsLayout.POPULARITY_BUTTONS[0].centerY()*l.scale(),0);
                    check(java.util.Collections.frequency(remoteRequests,StatsProtocol.POPULARITY)==0,"Offline target received action");
                    screenshot(c,"09-remote-offline");c.currentScreen.close();phase[0]=15;
                }else if(phase[0]==15){
                    check(remoteRequests.contains(StatsProtocol.CLOSE),"Remote subscription not closed");
                    remoteReply(c,StatsProtocol.SNAPSHOT,73,REMOTE,remoteProfile);phase[0]=16;
                }else if(phase[0]==16){
                    check(c.currentScreen==null,"Late snapshot reopened closed window");
                    check(StatsClient.values().nickname().equals(c.player.getName().getString()),"Personal stats lost viewer identity");
                    System.out.println("REMOTE_STATS_VISUAL_OK: remote identity/equipment/values, personal HUD isolation, requests and acknowledgement, 5s refresh, GUI resize, stale response, offline target and close.");
                    discovery(c,501);discovery(c,501);phase[0]=18;
                }else if(phase[0]==18){
                    check(DiscoveryClient.active(),"Discovery notification not rendered");
                    var dir=Path.of("visual-check/discovery-016");Files.createDirectories(dir);
                    try(var shot=ScreenshotRecorder.takeScreenshot(c.getFramebuffer())){shot.writeTo(dir.resolve("01-discovery.png"));}
                    discovery(c,501);phase[0]=19;ticks[0]=-60;
                }else if(phase[0]==19){
                    check(java.util.Collections.frequency(discoveryAcks,501L)==1,"Duplicate notification/ack or missing completion");
                    check(!DiscoveryClient.active(),"Toast did not finish");c.setScreen(new ChatScreen("test"));discovery(c,502);phase[0]=20;ticks[0]=-70;
                }else if(phase[0]==20){
                    check(!discoveryAcks.contains(502L),"Hidden notification acknowledged without display");c.setScreen(null);phase[0]=21;
                }else if(phase[0]==21){
                    check(DiscoveryClient.active(),"Queued toast lost after closing GUI");phase[0]=22;ticks[0]=-65;
                }else if(phase[0]==22){
                    check(java.util.Collections.frequency(discoveryAcks,502L)==1,"Second toast acknowledgement missing");
                    System.out.println("DISCOVERY_VISUAL_OK: real notice/ack payload, dynamic PNG/icon/text, replay deduplication, hidden-GUI queue, fade lifetime and OGG cue.");c.scheduleStop();phase[0]=23;
                }
            }catch(Exception e){throw new IllegalStateException(e);}
        }));
    }
    private static void remoteReply(MinecraftClient c,int kind,long session,java.util.UUID target,StatsProtocol.Profile profile){
        c.getServer().execute(()->ServerPlayNetworking.send(c.getServer().getPlayerManager().getPlayer(c.player.getUuid()),new RemoteStatsClient.Reply(StatsProtocol.encodeResponse(new StatsProtocol.Response(kind,session,target,kind==StatsProtocol.GONE?"상대방이 접속을 종료했습니다.":"",profile)))));
    }
    private static void discovery(MinecraftClient c,long token){
        var bytes=DiscoveryProtocol.encode(new DiscoveryProtocol.Notice(token,"harvest_wind","수확의 바람","magiccodex:textures/spells/novice/harvest_wind.png",false));
        c.getServer().execute(()->ServerPlayNetworking.send(c.getServer().getPlayerManager().getPlayer(c.player.getUuid()),new DiscoveryClient.Notification(bytes)));
    }
    private static void key(MinecraftClient c,int action){c.keyboard.onKey(c.getWindow().getHandle(),GLFW.GLFW_KEY_P,0,action,0);}
    private static void pointAt(MinecraftClient c,StatsLayout.Box r){
        if(!(c.currentScreen instanceof StatsScreen))return;
        var l=StatsLayout.fit(c.currentScreen.width,c.currentScreen.height);
        try{
            var x=c.mouse.getClass().getDeclaredField("x");x.setAccessible(true);
            var y=c.mouse.getClass().getDeclaredField("y");y.setAccessible(true);
            x.setDouble(c.mouse,(l.x()+r.centerX()*l.scale())*c.getWindow().getWidth()/c.getWindow().getScaledWidth());
            y.setDouble(c.mouse,(l.y()+r.centerY()*l.scale())*c.getWindow().getHeight()/c.getWindow().getScaledHeight());
        }catch(ReflectiveOperationException e){throw new IllegalStateException(e);}
    }
    private static void check(boolean ok,String message){if(!ok)throw new IllegalStateException(message);}
    private static void screenshot(MinecraftClient c,String name)throws Exception{
        ((StatsScreen)c.currentScreen).verifyRenderer();
        Path dir=Path.of("visual-check/stats-014");Files.createDirectories(dir);
        try(var shot=ScreenshotRecorder.takeScreenshot(c.getFramebuffer())){shot.writeTo(dir.resolve(name+".png"));}
    }
}
