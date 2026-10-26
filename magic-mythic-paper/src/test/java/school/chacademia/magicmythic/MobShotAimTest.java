package school.chacademia.magicmythic;
import org.bukkit.entity.LivingEntity;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
class MobShotAimTest {
 @Test void selfTargetNeverAttractsOwnProjectile() {var caster=mock(LivingEntity.class);assertNull(MobShotMech.aimed(new Mech.Ctx(null,caster,caster,null,0)));}
 @Test void pointAimNeedsNoEntity() {var caster=mock(LivingEntity.class);assertNull(MobShotMech.aimed(new Mech.Ctx(null,caster,null,null,0)));}
 @Test void actualEntityTargetIsPreserved() {var caster=mock(LivingEntity.class);var target=mock(LivingEntity.class);assertSame(target,MobShotMech.aimed(new Mech.Ctx(null,caster,target,null,0)));}
}
