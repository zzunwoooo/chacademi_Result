package kr.chacademy.npc.integration;
import java.lang.reflect.*;
import java.util.Map;
import java.util.logging.Logger;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class PlayerNameLinkTest {
    public static class Facade {
        private final String nickname;
        Facade(String nickname){this.nickname=nickname;}
        public String playerName(Player ignored){return nickname;}
    }
    private Player player(){return (Player)Proxy.newProxyInstance(Player.class.getClassLoader(),new Class[]{Player.class},(p,m,a)->m.getName().equals("getName")?"EnglishAccount":null);}
    private MagicCodexLink link() {
        Plugin plugin=(Plugin)Proxy.newProxyInstance(Plugin.class.getClassLoader(),new Class[]{Plugin.class},(p,m,a)->m.getName().equals("getLogger")?Logger.getLogger("nickname-link-test"):null);
        return new MagicCodexLink(plugin);
    }
    @SuppressWarnings("unchecked")
    private void install(MagicCodexLink link,String nickname)throws Exception{
        Field facade=MagicCodexLink.class.getDeclaredField("facade");facade.setAccessible(true);facade.set(link,new Facade(nickname));
        Field methods=MagicCodexLink.class.getDeclaredField("methods");methods.setAccessible(true);
        ((Map<String,Method>)methods.get(link)).put("playerName",Facade.class.getMethod("playerName",Player.class));
    }
    @Test void configuredPlainNicknameReplacesAccountDefaultAcrossOptionalFacadeBoundary()throws Exception{
        var link=link();install(link,"준우");assertEquals("준우",link.playerName(player()));
    }
    @Test void missingOptionalMethodKeepsOlderBridgeCompatible(){
        assertEquals("EnglishAccount",link().playerName(player()));
    }
    @Test void unavailableNicknameDoesNotBreakDialogue()throws Exception{
        var link=link();install(link,"");assertEquals("EnglishAccount",link.playerName(player()));
    }
}
