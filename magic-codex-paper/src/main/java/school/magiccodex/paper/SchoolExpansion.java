package school.magiccodex.paper;
import org.bukkit.OfflinePlayer;
import me.clip.placeholderapi.expansion.PlaceholderExpansion;
final class SchoolExpansion extends PlaceholderExpansion {
    private final SchoolBridge bridge;
    SchoolExpansion(SchoolBridge bridge){this.bridge=bridge;}
    public String getIdentifier(){return "chacademia";}
    public String getAuthor(){return "Chacademia";}
    public String getVersion(){return "0.14.0";}
    public boolean persist(){return true;}
    public String onRequest(OfflinePlayer player,String params){
        if(params.equals("donations_total"))return Integer.toString(bridge.data().records().size());
        String[] ids={"arkeon","lumina","bestiaz","noxer"};
        for(int i=0;i<4;i++)if(params.equals("house_"+ids[i]+"_points"))return Long.toString(bridge.data().scores().get(i));
        return null;
    }
}
