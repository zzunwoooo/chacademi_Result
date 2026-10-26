package school.magiccodex.discovery;
import org.bukkit.Material;
final class SubmergedGold {
 static boolean qualifies(Material ore,Material above,int level){return (ore==Material.GOLD_ORE||ore==Material.DEEPSLATE_GOLD_ORE)&&above==Material.WATER&&level==0;}
}
