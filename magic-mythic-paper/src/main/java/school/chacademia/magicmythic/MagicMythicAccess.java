package school.chacademia.magicmythic;
import org.bukkit.*;import org.bukkit.entity.Player;
final class MagicMythicAccess {static boolean at(Player p,Location l){var plugin=Bukkit.getPluginManager().getPlugin("ChAcademiaMagicMythic");return plugin instanceof MagicMythicPlugin m&&m.canAffect(p,l);}}
