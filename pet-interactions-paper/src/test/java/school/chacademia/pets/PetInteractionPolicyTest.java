package school.chacademia.pets;
import fr.nocsy.mcpets.data.*;
import fr.nocsy.mcpets.events.EntityMountPetEvent;
import com.ticxo.modelengine.api.events.ModelMountEvent;
import java.util.*;
import java.util.function.Function;
import org.bukkit.entity.*;
import org.bukkit.event.entity.EntityMountEvent;
import org.bukkit.event.player.*;
import org.bukkit.inventory.EquipmentSlot;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
class PetInteractionPolicyTest {
 final UUID owner=UUID.randomUUID();final Player player=mock(Player.class);final Entity entity=mock(Entity.class);final Pet pet=mock(Pet.class);final List<Throwable> errors=new ArrayList<>();
 PetInteractionPolicyTest(){when(player.getUniqueId()).thenReturn(owner);when(entity.getScoreboardTags()).thenReturn(Set.of());when(pet.getOwner()).thenReturn(owner);when(pet.isStillHere()).thenReturn(true);}
 PetInteractionPolicy policy(Pet found){return new PetInteractionPolicy(e->found,errors::add);}
 @Test void ownerRightClickOnlyDismissesActivePet(){var e=new PlayerInteractEntityEvent(player,entity,EquipmentSlot.HAND);policy(pet).click(e);assertTrue(e.isCancelled());verify(pet).despawn(PetDespawnReason.REVOKE);verify(entity,never()).remove();verify(pet,never()).setOwner(any());assertTrue(errors.isEmpty());}
 @Test void otherOwnerIncludingAdminCannotDismiss(){when(player.hasPermission(anyString())).thenReturn(true);when(pet.getOwner()).thenReturn(UUID.randomUUID());var e=new PlayerInteractEntityEvent(player,entity);policy(pet).click(e);assertFalse(e.isCancelled());verify(pet,never()).despawn(any());}
 @Test void unownedPetCannotDismiss(){when(pet.getOwner()).thenReturn(null);policy(pet).click(new PlayerInteractEntityEvent(player,entity));verify(pet,never()).despawn(any());}
 @Test void staleInactivePetCannotDismiss(){when(pet.isStillHere()).thenReturn(false);policy(pet).click(new PlayerInteractEntityEvent(player,entity));verify(pet,never()).despawn(any());}
 @Test void vanillaAndWildMobsAreUntouched(){var e=new PlayerInteractEntityEvent(player,entity);policy(null).click(e);assertFalse(e.isCancelled());verify(entity,never()).remove();}
 @Test void citizensNpcIsUntouchedEvenIfLookupWasWrong(){when(entity.hasMetadata("NPC")).thenReturn(true);policy(pet).click(new PlayerInteractEntityEvent(player,entity));verify(pet,never()).despawn(any());}
 @Test void taggedNpcIsUntouched(){when(entity.getScoreboardTags()).thenReturn(Set.of("chacademia_npc"));policy(pet).click(new PlayerInteractEntityEvent(player,entity));verify(pet,never()).despawn(any());}
 @Test void cancelledProtectionInteractionRemainsUntouched(){var e=new PlayerInteractEntityEvent(player,entity);e.setCancelled(true);policy(pet).click(e);verify(pet,never()).despawn(any());}
 @Test void offhandCanDismissAndEntityAtEventWorks(){var e=new PlayerInteractAtEntityEvent(player,entity,new org.bukkit.util.Vector(),EquipmentSlot.OFF_HAND);policy(pet).clickAt(e);assertTrue(e.isCancelled());verify(pet).despawn(PetDespawnReason.REVOKE);}
 @Test void pairedHandPacketsDoNotDismissTwiceAfterPetUnregisters(){boolean[] active={true};when(pet.despawn(any())).thenAnswer(i->{active[0]=false;return true;});var policy=new PetInteractionPolicy(e->active[0]?pet:null,errors::add);policy.click(new PlayerInteractEntityEvent(player,entity,EquipmentSlot.HAND));policy.click(new PlayerInteractEntityEvent(player,entity,EquipmentSlot.OFF_HAND));verify(pet,times(1)).despawn(PetDespawnReason.REVOKE);}
 @Test void mcpetsMountEventBlockedWithoutDeletingPet(){var e=new EntityMountPetEvent(player,pet);policy(pet).petMount(e);assertTrue(e.isCancelled());verify(pet,never()).despawn(any());}
 @Test void genericPetMountBlocked(){var e=new EntityMountEvent(player,entity);policy(pet).entityMount(e);assertTrue(e.isCancelled());}
 @Test void broomsQuidditchAndOtherVehiclesUnaffected(){var e=new EntityMountEvent(player,entity);policy(null).entityMount(e);assertFalse(e.isCancelled());verify(entity,never()).eject();}
 @Test void directModelEnginePetSeatBlocked(){var e=mock(ModelMountEvent.class,RETURNS_DEEP_STUBS);UUID id=UUID.randomUUID();when(e.getVehicle().getModeledEntity().getBase().getUUID()).thenReturn(id);new ModelMountGuard(x->pet,x->entity).mount(e);verify(e).setCancelled(true);verify(pet,never()).despawn(any());}
 @Test void directNonPetModelEngineSeatUnaffected(){var e=mock(ModelMountEvent.class,RETURNS_DEEP_STUBS);new ModelMountGuard(x->null,x->entity).mount(e);verify(e,never()).setCancelled(true);}
 @Test void failedPetApiNeverFallsBackToRemovingEntity(){var policy=new PetInteractionPolicy(e->{throw new IllegalStateException();},errors::add);policy.click(new PlayerInteractEntityEvent(player,entity));verify(entity,never()).remove();assertEquals(1,errors.size());}
}
