package school.magiccodex.paper;

import org.bukkit.OfflinePlayer;
import me.clip.placeholderapi.expansion.PlaceholderExpansion;

/** Returns plain display data; never resolves permissions or changes the account name.
 * PlaceholderAPI는 식별자당 확장 하나만 허용한다: "magiccodex" 확장은 nickname/account 외의 키를 delegate(마나 확장)에 넘긴다. */
final class NicknameExpansion extends PlaceholderExpansion {
    private final NicknameBridge bridge;private final String identifier,version;
    private volatile java.util.function.BiFunction<OfflinePlayer,String,String> delegate;
    void delegate(java.util.function.BiFunction<OfflinePlayer,String,String> next){delegate=next;}
    NicknameExpansion(NicknameBridge bridge,String identifier,String version){this.bridge=bridge;this.identifier=identifier;this.version=version;}
    public String getIdentifier(){return identifier;}
    public String getAuthor(){return "Chacademia";}
    public String getVersion(){return version;}
    public boolean persist(){return true;}
    public String onRequest(OfflinePlayer player,String params){
        if(params.equals("nickname")||params.equals("account"))return bridge.answer(player,params);
        var next=delegate;return next!=null?next.apply(player,params):player==null?"":null;
    }
}
