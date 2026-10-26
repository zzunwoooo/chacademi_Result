package kr.chacademi.chatlayout;
import java.lang.reflect.*;
import java.util.*;
import java.util.function.Consumer;
import com.ebicep.chatplus.config.Config;
import com.ebicep.chatplus.features.chattabs.*;
import com.ebicep.chatplus.features.chatwindows.ChatWindow;
import com.ebicep.chatplus.hud.ChatManager;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.*;
import net.minecraft.resources.ResourceLocation;
/** Optional narrow bridge to MagicCodex. Private input always fails closed, never becomes public chat. */
public final class PrivateConversations {
 private static Method reply,cancel;private static boolean attempted;
 private PrivateConversations(){}
 public static String scope(){
  var c=Minecraft.getInstance();
  return c.getConnection()==null||c.player==null?"":(c.getCurrentServer()==null?"local":c.getCurrentServer().ip)+"/"+c.player.getUUID();
 }
 public static PrivatePeer peer(ChatTab tab){return tab==null?null:PrivatePeer.parse(tab.getAutoPrefix());}
 public static void filters(ChatWindow window){
  for(var tab:window.getTabSettings().getTabs()){
   if(peer(tab)!=null)continue;
   var settings=tab.getCurrentSettings();String old=settings.getPattern();
   if(tab.getName().equals("파티")&&!old.contains("\\A\\[파티\\]")){
    settings.setPattern("(?:\\A\\[파티\\].*)|(?:"+old+")");
    settings.updateRegex();com.ebicep.chatplus.config.ConfigKt.setQueueUpdateConfig(true);continue;
   }
   if(tab.getName().equals("전체")){
    if(!old.equals("(?s).*")||!settings.getAlwaysAdd()||settings.getSkipOthers()){
     settings.setPattern("(?s).*");settings.setAlwaysAdd(true);settings.setSkipOthers(false);
     settings.updateRegex();com.ebicep.chatplus.config.ConfigKt.setQueueUpdateConfig(true);
    }
    continue;
   }
   if(old.contains("\\x{E101}"))continue;
   if(tab.getName().equals("귓말"))settings.setPattern("(?:\\x{E101}.*)|(?:"+old+")");
   else continue;
   settings.updateRegex();com.ebicep.chatplus.config.ConfigKt.setQueueUpdateConfig(true);
  }
 }
 public static void tick(){
  if(attempted)return;attempted=true;
  try{
   Class<?> api=Class.forName("school.magiccodex.client.SocialClient");
   reply=api.getMethod("replyFromChat",UUID.class,String.class);
   cancel=api.getMethod("cancelChatReply",UUID.class);
   api.getMethod("installChatListener",Consumer.class).invoke(null,(Consumer<Object>)PrivateConversations::receive);
  }catch(ReflectiveOperationException unavailable){reply=null;cancel=null;}
 }
 private static Object get(Object r,String field)throws ReflectiveOperationException{return r.getClass().getMethod(field).invoke(r);}
 private static void receive(Object response){
  if(response==null||scope().isEmpty())return;
  try{
   int kind=(Integer)get(response,"kind");UUID id=(UUID)get(response,"target");
   String name=(String)get(response,"name"),text=(String)get(response,"text");
   var peer=new PrivatePeer(scope(),id);
   if(kind==5){
    ChatTab tab=ChatLayout.privateTab(peer,name,true);if(tab!=null)add(tab,name,text,id);
   }else if(kind==4){
    ChatTab tab=ChatLayout.privateTab(peer,name,true);
    if(tab!=null)add(tab,"나",text,Minecraft.getInstance().player.getUUID());
   }else if(kind==2){
    ChatTab tab=ChatLayout.privateTab(peer,name,false);
    if(tab!=null)notice(tab,text);
   }
  }catch(ReflectiveOperationException|RuntimeException problem){
   org.slf4j.LoggerFactory.getLogger("chacademi-chat-layout").warn("Private chat update failed: {}",problem.getClass().getSimpleName());
  }
 }
 private static void add(ChatTab tab,String sender,String text,UUID id){
  var icon=Component.literal("\uE101").withStyle(Style.EMPTY.withFont(ResourceLocation.fromNamespaceAndPath("magiccodex","whisper")));
  var line=icon.append(Component.literal(" "+sender+" : "+text).withStyle(Style.EMPTY.withFont(ResourceLocation.withDefaultNamespace("default"))));
  tab.addNewMessage(new AddNewMessageEvent(line,line,id,null,Minecraft.getInstance().gui.getGuiTicks(),null,false));
  // SocialBridge already sends the vanilla whisper component to sender and recipient.
  // ChatPlus routes that single component into 전체/귓말 through their existing filters.
  // The private payload belongs only to the peer tab; mirroring it duplicates aggregate history.

 }
 private static void notice(ChatTab tab,String text){
  var line=Component.literal(text).withStyle(net.minecraft.ChatFormatting.YELLOW);
  tab.addNewMessage(new AddNewMessageEvent(line,line,null,null,Minecraft.getInstance().gui.getGuiTicks(),null,false));
 }
 public static boolean send(String raw){
  if(!Config.INSTANCE.getLoaded())return false;
  var tab=ChatManager.INSTANCE.getGlobalSelectedTab();var peer=peer(tab);
  if(peer==null)return false;
  tick();
  if(!peer.scope().equals(scope())){notice(tab,"이 서버에서 받은 귓말 탭이 아닙니다.");return true;}
  try{
   String text=PrivatePeer.message(raw);
   if(reply==null||!Boolean.TRUE.equals(reply.invoke(null,peer.id(),text))){
    notice(tab,"전언을 보낼 수 없습니다. MagicCodex 업데이트 또는 진행 중인 전송을 확인해 주세요.");
   }else ChatManager.INSTANCE.addSentMessage(raw);
  }catch(IllegalArgumentException invalid){notice(tab,invalid.getMessage());}
   catch(ReflectiveOperationException failed){notice(tab,"전언 연결에 실패했습니다. 메시지는 전송되지 않았습니다.");}
  return true;
 }
 public static void closed(ChatTab tab){
  var peer=peer(tab);if(peer==null||cancel==null||!peer.scope().equals(scope()))return;
  try{cancel.invoke(null,peer.id());}catch(ReflectiveOperationException ignored){}
 }
}