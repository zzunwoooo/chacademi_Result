package school.magiccodex.client;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Map;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.minecraft.client.MinecraftClient;
import school.magiccodex.client.api.ExternalFrame;
import school.magiccodex.protocol.DialogueProtocol;

/**
 * {@link school.magiccodex.client.api.ExternalDialogue} 의 구현부 (다른 모드는 api 쪽만 부른다).
 * 외부(클라 주도) 대화 세션 하나를 들고, 같은 {@link DialogueScreen} 을 "외부 드라이버" 모드로 돌린다.
 * <ul>
 *   <li>외부 세션은 DialogueProtocol 패킷을 절대 보내지 않는다 (화면이 드라이버만 부른다).</li>
 *   <li>외부 세션이 살아 있는 동안 온 서버 대화 응답은 DialogueClient 가 마지막 것 하나만 들고 있다가,
 *       외부 세션이 끝나면 연다 (외부 대화 우선).</li>
 *   <li>외부 대화를 열 때 서버 NPC 대화창이 떠 있었다면 평소 닫기처럼 서버에 알리고 닫는다.</li>
 *   <li>세션은 한 번에 하나. 다른 session 으로 show 하면 앞 세션은 콜백 없이 밀려난다.</li>
 *   <li>모든 상태는 클라이언트 스레드에서만 만진다.</li>
 * </ul>
 */
public final class ExternalDialogueHost {
    /** 서버 세션 id(UUID)와 절대 겹치지 않게 화면 쪽 세션 이름에 붙이는 머리말. */
    private static final String WIRE="external/";
    /** 열 때마다 다른 화면 세션 이름을 쓴다: 같은 id 를 닫았다 다시 열어도(이어 보기) 앞 창의 순번과 섞이지 않는다. */
    private static int opens;
    private static final org.slf4j.Logger LOG=org.slf4j.LoggerFactory.getLogger("magiccodex");
    private static Session active;
    private static boolean hooked,notifying;
    private ExternalDialogueHost(){}

    private static final class Session implements DialogueScreen.Driver {
        final String id,wire;BiConsumer<String,String> onChoice;Consumer<String> onClosed;
        int sequence;boolean closable;DialogueScreen screen;
        Session(String id){this.id=id;this.wire=WIRE+(++opens)+"/"+id;}
        @Override public void choose(int sequence,String choice){
            // 화면이 보고 있는 프레임이 최신일 때만 (오래된 화면의 입력은 버린다)
            if(active!=this||sequence!=this.sequence)return;
            try{onChoice.accept(id,choice);}
            catch(RuntimeException|LinkageError e){LOG.warn("외부 대화 {} 의 선택 콜백이 실패해 대화를 닫습니다",id,e);close(id);}
        }
        @Override public void removed(){
            if(active!=this)return;
            if(closable)active=null; // 닫을 수 있는 대화는 화면이 사라지면 끝. 닫을 수 없는 대화는 부르는 쪽이 다시 보여 준다
            notifying=true;
            try{onClosed.accept(id);}
            catch(RuntimeException|LinkageError e){LOG.warn("외부 대화 {} 의 닫힘 콜백 실패",id,e);}
            finally{notifying=false;}
        }
    }

    /** 외부 세션이 살아 있거나(화면이 잠깐 가려져 있어도), 끝난 외부 대화의 창(마지막 "내 차례")이 아직 떠 있는 동안. 이때 온 서버 대화는 DialogueClient 가 미룬다. */
    static boolean busy(MinecraftClient c){return active!=null||c.currentScreen instanceof DialogueScreen screen&&screen.external();}

    private static void hook(){
        if(hooked)return;hooked=true;
        ClientPlayConnectionEvents.DISCONNECT.register((h,c)->c.execute(ExternalDialogueHost::reset));
        ClientTickEvents.END_CLIENT_TICK.register(c->{
            if(active!=null&&(c.player==null||c.world==null)){reset();return;}
            // 외부 대화(와 그 마지막 "내 차례")가 다 끝난 뒤에 미뤄 둔 서버 대화를 연다
            if(!busy(c)){ExternalPortraits.clear();DialogueClient.releaseHeld(c);} // 세션이 끝나면 파일 초상화 텍스처도 내린다
        });
    }

    private static void reset(){active=null;ExternalPortraits.clear();DialogueClient.dropHeld();}

    public static boolean show(Map<String,Object> raw,BiConsumer<String,String> onChoice,Consumer<String> onClosed){
        var c=MinecraftClient.getInstance();
        if(raw==null||onChoice==null||onClosed==null||c==null||!c.isOnThread()||notifying||c.player==null||c.world==null)return false;
        ExternalFrame f;
        try{f=ExternalFrame.parse(raw);}
        catch(IllegalArgumentException|ClassCastException e){LOG.warn("외부 대화 프레임 거부: {}",e.getMessage());return false;}
        hook();
        var step=ExternalFrame.step(active==null?null:active.id,active==null?-1:active.sequence,f);
        if(step==ExternalFrame.Step.STALE)return false;
        var choices=new ArrayList<DialogueProtocol.Choice>();
        for(var choice:f.choices())choices.add(new DialogueProtocol.Choice(choice.id(),choice.text()));
        var x=new DialogueScreen.External(f.closable(),f.last(),f.playerPortrait(),f.portraitFile());
        for(Path p:f.preload())ExternalPortraits.request(p);
        ExternalPortraits.request(f.portraitFile());
        if(step==ExternalFrame.Step.OPEN){
            // 다른 외부 세션이 살아 있었다면 조용히 밀려난다 (그 세션의 onClosed 는 부르지 않는다)
            var s=new Session(f.session());s.onChoice=onChoice;s.onClosed=onClosed;s.sequence=f.sequence();s.closable=f.closable();
            var r=response(s,f,choices);
            active=s;
            if(c.currentScreen instanceof DialogueScreen screen&&screen.external()){
                // 바로 앞 외부 대화의 창(마지막 "내 차례" 포함)이 아직 떠 있으면 그 창을 이어 쓴다: 내 차례가 끝난 뒤 새 대화가 보인다
                s.screen=screen;screen.adopt(s,r,x);
            }else{
                // 서버 NPC 대화가 떠 있었다면 서버에 닫힘을 알리고(평소 닫기와 같음) 외부 대화로 바꾼다
                if(c.currentScreen instanceof DialogueScreen server)server.close();
                s.screen=new DialogueScreen(r,s,x);MagicCodexClient.dismiss();c.setScreen(s.screen);
            }
            return true;
        }
        var s=active;s.onChoice=onChoice;s.onClosed=onClosed;s.sequence=f.sequence();s.closable=f.closable();
        s.screen.receiveExternal(response(s,f,choices),x);
        if(c.currentScreen!=s.screen){MagicCodexClient.dismiss();c.setScreen(s.screen);}
        return true;
    }

    private static DialogueProtocol.Response response(Session s,ExternalFrame f,java.util.List<DialogueProtocol.Choice> choices){
        return new DialogueProtocol.Response(s.wire,f.sequence(),false,false,f.title(),f.speaker(),f.portrait(),f.text(),java.util.List.copyOf(choices),f.message());
    }

    public static void close(String session){
        var c=MinecraftClient.getInstance();
        if(c==null||session==null)return;
        if(!c.isOnThread()){c.execute(()->close(session));return;}
        var s=active;
        if(s==null||!s.id.equals(session))return;
        active=null;
        s.screen.externalClose();
    }

    public static boolean isShowing(String session){
        var c=MinecraftClient.getInstance();var s=active;
        return c!=null&&s!=null&&session!=null&&s.id.equals(session)&&!notifying&&c.currentScreen==s.screen;
    }
}
