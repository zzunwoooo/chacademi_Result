package school.magiccodex.paper;

import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import org.bukkit.OfflinePlayer;

final class ClimateExpansion extends PlaceholderExpansion {
    private final ClimateService climate;
    ClimateExpansion(ClimateService climate){this.climate=climate;}
    public String getIdentifier(){return "chaclimate";}
    public String getAuthor(){return "Chacademia";}
    public String getVersion(){return "0.13.0";}
    public boolean persist(){return true;}
    public String onRequest(OfflinePlayer player,String key){return switch(key){
        case "season"->climate.season().id();case "season_name"->climate.season().label;
        case "fire_power"->Double.toString(climate.powerMultiplier("fire"));case "water_power"->Double.toString(climate.powerMultiplier("water"));
        case "temperature"->player==null?"":climate.temperature(player.getUniqueId()).stream().mapToObj(ManaExpansion::number).findFirst().orElse("");default->null;
    };}
}
