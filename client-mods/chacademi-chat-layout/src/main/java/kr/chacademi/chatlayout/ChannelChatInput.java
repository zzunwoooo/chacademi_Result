package kr.chacademi.chatlayout;
import com.ebicep.chatplus.config.Config;
import com.ebicep.chatplus.hud.ChatManager;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

public final class ChannelChatInput {
    private ChannelChatInput() {}
    public static boolean send(String raw){
        if(!Config.INSTANCE.getLoaded())return false;
        var tab=ChatManager.INSTANCE.getGlobalSelectedTab();if(tab==null)return false;
        var route=ChannelInputPolicy.route(tab.getName(),PrivateConversations.peer(tab)!=null,raw);
        if(route==ChannelInputPolicy.Route.PASS)return false;
        var client=Minecraft.getInstance();
        if(route==ChannelInputPolicy.Route.SYSTEM){client.gui.getChat().addMessage(Component.literal("[시스템] 이 탭은 수신 전용입니다. 전체 또는 기숙사 탭을 선택해 주세요."));return true;}
        try{
            String command=ChannelInputPolicy.command(route,raw);
            if(client.getConnection()==null){client.gui.getChat().addMessage(Component.literal("[시스템] 서버에 연결되지 않아 메시지를 보내지 않았습니다."));return true;}
            client.getConnection().sendCommand(command);ChatManager.INSTANCE.addSentMessage(raw);
        }catch(IllegalArgumentException e){client.gui.getChat().addMessage(Component.literal("[시스템] "+e.getMessage()));}
        return true; // Missing proxy support never falls back to public/another channel.
    }
}
