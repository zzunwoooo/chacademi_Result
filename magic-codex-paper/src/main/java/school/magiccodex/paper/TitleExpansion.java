package school.magiccodex.paper;

import org.bukkit.OfflinePlayer;
import me.clip.placeholderapi.expansion.PlaceholderExpansion;

/** A separate identifier avoids collisions with the existing chacademia house expansion. */
final class TitleExpansion extends PlaceholderExpansion {
    private final TitleBridge bridge;private final String version;
    TitleExpansion(TitleBridge bridge,String version){this.bridge=bridge;this.version=version;}
    public String getIdentifier(){return "chacademiatitle";}
    public String getAuthor(){return "Chacademia";}
    public String getVersion(){return version;}
    public boolean persist(){return true;}
    public String onRequest(OfflinePlayer player,String params){return player==null?"":bridge.placeholder(player.getUniqueId(),params);}
}
