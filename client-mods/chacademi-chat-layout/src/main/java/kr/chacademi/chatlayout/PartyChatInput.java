package kr.chacademi.chatlayout;
import com.ebicep.chatplus.config.Config;
import com.ebicep.chatplus.hud.ChatManager;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

/** Explicit proxy command; lack of a party/plugin can never fall back to public chat. */
public final class PartyChatInput {
 private PartyChatInput(){}
 public static boolean send(String raw){
  if(!Config.INSTANCE.getLoaded())return false;
  var tab=ChatManager.INSTANCE.getGlobalSelectedTab();
  if(tab==null||PrivateConversations.peer(tab)!=null||!tab.getName().equals("파티")||raw.stripLeading().startsWith("/"))return false;
  var client=Minecraft.getInstance();
  try{
   String text=PrivatePeer.message(raw);
   if(client.getConnection()==null)return true;
   client.getConnection().sendCommand("파티 채팅 "+text);
   ChatManager.INSTANCE.addSentMessage(raw);
  }catch(IllegalArgumentException e){client.gui.getChat().addMessage(Component.literal("[시스템] "+e.getMessage()));}
  return true;
 }
}
