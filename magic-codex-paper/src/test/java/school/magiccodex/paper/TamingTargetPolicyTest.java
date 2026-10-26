package school.magiccodex.paper;
import java.lang.reflect.Proxy;
import java.util.Set;
import org.bukkit.entity.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class TamingTargetPolicyTest {
 LivingEntity entity(Class<?> kind,boolean npc,boolean tamed,boolean petTag){return (LivingEntity)Proxy.newProxyInstance(getClass().getClassLoader(),new Class<?>[]{kind},(o,m,a)->switch(m.getName()){case "hasMetadata"->npc;case "isTamed"->tamed;case "getScoreboardTags"->petTag?Set.of("chacademia_pet"):Set.of();default->throw new UnsupportedOperationException(m.getName());});}
 @Test void wildHuskBaseAndRegisteredVanillaRemainEligibleForProfileLookup(){assertFalse(TamingTargetPolicy.excluded(entity(LivingEntity.class,false,false,false),false));assertFalse(TamingTargetPolicy.excluded(entity(Tameable.class,false,false,false),false));}
 @Test void activePetTaggedPetOwnedAnimalAndNpcAreRejectedBeforeVanillaFallback(){assertTrue(TamingTargetPolicy.excluded(entity(LivingEntity.class,false,false,false),true));assertTrue(TamingTargetPolicy.excluded(entity(LivingEntity.class,false,false,true),false));assertTrue(TamingTargetPolicy.excluded(entity(Tameable.class,false,true,false),false));assertTrue(TamingTargetPolicy.excluded(entity(LivingEntity.class,true,false,false),false));}
 @Test void playerAndArmorStandAreNeverCaptureTargets(){assertTrue(TamingTargetPolicy.excluded(entity(Player.class,false,false,false),false));assertTrue(TamingTargetPolicy.excluded(entity(ArmorStand.class,false,false,false),false));}
}
