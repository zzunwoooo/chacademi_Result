package kr.chacademy.npc.integration;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.Test;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Map;
import java.util.logging.Logger;
import static org.junit.jupiter.api.Assertions.*;
import java.lang.reflect.Proxy;
class GiftReceiptApiTest {
 public static final class Provider {
  int result; boolean fail; String token; int slot;
  public int takeGift(Player player,int slot,ItemStack one,String token){if(fail)throw new IllegalStateException("unavailable");this.slot=slot;this.token=token;return result;}
 }
 private static MagicCodexLink link() {
  Plugin plugin=(Plugin)Proxy.newProxyInstance(Plugin.class.getClassLoader(),new Class<?>[]{Plugin.class},(proxy,method,args)->method.getName().equals("getLogger")?Logger.getAnonymousLogger():null);return new MagicCodexLink(plugin);
 }
 @SuppressWarnings("unchecked") private static void connect(MagicCodexLink link,Provider provider)throws Exception{
  Field facade=MagicCodexLink.class.getDeclaredField("facade");facade.setAccessible(true);facade.set(link,provider);
  Field field=MagicCodexLink.class.getDeclaredField("methods");field.setAccessible(true);Map<String,Method> methods=(Map<String,Method>)field.get(link);methods.put("takeGift",Provider.class.getMethod("takeGift",Player.class,int.class,ItemStack.class,String.class));
 }
 @Test void receiptApiDescriptorsRemainAvailable()throws Exception{
  assertEquals(boolean.class,MagicCodexLink.class.getMethod("giftReceipts").getReturnType());
  assertEquals(int.class,MagicCodexLink.class.getMethod("takeGift",Player.class,int.class,ItemStack.class,String.class).getReturnType());
 }
 @Test void disconnectedAndUnknownOutcomesStayUnknownRatherThanRefundable(){var link=link();assertFalse(link.giftReceipts());assertEquals(-1,link.takeGift(null,3,null,"receipt"));}
 @Test void sourceReceiptBoundaryPreservesTakenNotTakenAndUnknown()throws Exception{
  var link=link();var provider=new Provider();connect(link,provider);assertTrue(link.giftReceipts());
  for(int value:new int[]{1,0,-1}){provider.result=value;assertEquals(value,link.takeGift(null,5,null,"receipt-"+value));assertEquals(5,provider.slot);assertEquals("receipt-"+value,provider.token);}
  provider.fail=true;assertEquals(-1,link.takeGift(null,5,null,"unknown"));
 }
}
