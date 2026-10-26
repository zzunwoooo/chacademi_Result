package school.chacademia.pets;
import fr.nocsy.mcpets.data.Pet;
import com.ticxo.modelengine.api.events.ModelMountEvent;
import java.util.function.Function;
import org.bukkit.Bukkit;
import org.bukkit.entity.Entity;
import org.bukkit.event.*;
final class ModelMountGuard implements Listener {
 private final Function<Entity,Pet> lookup;private final Function<java.util.UUID,Entity> entities;
 ModelMountGuard(Function<Entity,Pet> lookup){this(lookup,Bukkit::getEntity);}
 ModelMountGuard(Function<Entity,Pet> lookup,Function<java.util.UUID,Entity> entities){this.lookup=lookup;this.entities=entities;}
 @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true)
 public void mount(ModelMountEvent event){
  if(event.getVehicle()==null||event.getVehicle().getModeledEntity()==null||event.getVehicle().getModeledEntity().getBase()==null)return;
  Entity base=entities.apply(event.getVehicle().getModeledEntity().getBase().getUUID());
  if(base!=null&&lookup.apply(base)!=null)event.setCancelled(true);
 }
}
