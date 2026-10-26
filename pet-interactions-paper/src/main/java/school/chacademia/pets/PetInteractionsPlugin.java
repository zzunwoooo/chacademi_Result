package school.chacademia.pets;
import fr.nocsy.mcpets.data.Pet;
import org.bukkit.plugin.java.JavaPlugin;
public final class PetInteractionsPlugin extends JavaPlugin {
 @Override public void onEnable(){
  var policy=new PetInteractionPolicy(Pet::getFromEntity,e->getLogger().warning("Pet interaction failed safely: "+e.getClass().getSimpleName()));
  getServer().getPluginManager().registerEvents(policy,this);
  if(getServer().getPluginManager().isPluginEnabled("ModelEngine"))getServer().getPluginManager().registerEvents(new ModelMountGuard(Pet::getFromEntity),this);
  getLogger().info("Owner-only right-click dismissal enabled; MCPets riding disabled. No ownership/data changes.");
 }
}
