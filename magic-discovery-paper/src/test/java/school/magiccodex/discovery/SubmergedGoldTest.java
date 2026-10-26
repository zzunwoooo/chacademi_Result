package school.magiccodex.discovery;
import org.junit.jupiter.api.Test;
import org.bukkit.Material;
import static org.junit.jupiter.api.Assertions.*;
class SubmergedGoldTest {
 @Test void fullSourceAboveBothVanillaOres(){assertTrue(SubmergedGold.qualifies(Material.GOLD_ORE,Material.WATER,0));assertTrue(SubmergedGold.qualifies(Material.DEEPSLATE_GOLD_ORE,Material.WATER,0));}
 @Test void sideContactFlowAndOtherGoldDoNotQualify(){assertFalse(SubmergedGold.qualifies(Material.GOLD_ORE,Material.AIR,-1));assertFalse(SubmergedGold.qualifies(Material.GOLD_ORE,Material.WATER,1));assertFalse(SubmergedGold.qualifies(Material.GOLD_ORE,Material.WATER,8));assertFalse(SubmergedGold.qualifies(Material.NETHER_GOLD_ORE,Material.WATER,0));assertFalse(SubmergedGold.qualifies(Material.GOLD_BLOCK,Material.WATER,0));}
}
