package school.chacademia.pets;
import fr.nocsy.mcpets.data.Pet;
import fr.nocsy.mcpets.data.PetDespawnReason;
import fr.nocsy.mcpets.events.EntityMountPetEvent;
import java.util.function.*;
import org.bukkit.entity.Entity;
import org.bukkit.event.*;
import org.bukkit.event.entity.EntityMountEvent;
import org.bukkit.event.player.*;
final class PetInteractionPolicy implements Listener {
 private final Function<Entity,Pet> lookup;private final Consumer<Throwable> errors;
 PetInteractionPolicy(Function<Entity,Pet> lookup,Consumer<Throwable> errors){this.lookup=lookup;this.errors=errors;}
 @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true)
 public void click(PlayerInteractEntityEvent event){dismiss(event);}
 @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true)
 public void clickAt(PlayerInteractAtEntityEvent event){dismiss(event);}
 private void dismiss(PlayerInteractEntityEvent event){
  if(event.isCancelled())return;
  Entity entity=event.getRightClicked();
  if(entity==null||entity.hasMetadata("NPC")||entity.getScoreboardTags().contains("chacademia_npc"))return;
  try{
   Pet pet=lookup.apply(entity);
   if(pet==null||pet.getOwner()==null||!pet.getOwner().equals(event.getPlayer().getUniqueId())||!pet.isStillHere())return;
   event.setCancelled(true);
   pet.despawn(PetDespawnReason.REVOKE);
  }catch(RuntimeException|LinkageError failure){errors.accept(failure);}
 }
 @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true)
 public void petMount(EntityMountPetEvent event){if(event.getPet()!=null)event.setCancelled(true);}
 @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true)
 public void entityMount(EntityMountEvent event){
  if(event.getMount()==null)return;
  try{if(lookup.apply(event.getMount())!=null)event.setCancelled(true);}catch(RuntimeException|LinkageError failure){errors.accept(failure);}
 }
}
