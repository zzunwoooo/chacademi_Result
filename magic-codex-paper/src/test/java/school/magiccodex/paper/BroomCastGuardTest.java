package school.magiccodex.paper;
import org.junit.jupiter.api.Test;
import school.magiccodex.protocol.ManaProtocol;
import static org.junit.jupiter.api.Assertions.*;

class BroomCastGuardTest {
 @Test void ridingBlocksOnlyTheTwoMovementSpellsAndRemount(){
  for(String id:new String[]{"gale_dash","light_leap","flight","hunting_pounce","roar_leap"}){assertTrue(BroomCastGuard.blocked(id,true));assertFalse(BroomCastGuard.blocked(id,false));}
  for(String id:new String[]{"taming","wind_message","fireball","water_bomb"})assertFalse(BroomCastGuard.blocked(id,true));
 }
 @Test void unavailableIsRejectedBeforeConsumeDispatchAndCooldownEvenWithNoMana(){
  for(double mana:new double[]{0,100}){
   var a=new ManaAccount(mana,100,0);a.cooldowns.put("light_leap",10000L);
   var before=a.snapshot();var spell=new ManaSpells.Spell("light_leap","cast light_leap","magic.learned.light_leap",80,30000);
   var r=ManaCasting.attempt(a,spell,true,100,true,()->{fail("must never dispatch or publish a deducted balance");return true;});
   assertEquals(ManaProtocol.UNAVAILABLE,r.status());assertEquals(before,a.snapshot());assertEquals(10000L,a.cooldowns.get("light_leap"));
   assertEquals(r.status(),ManaProtocol.decode(ManaProtocol.encode(new ManaProtocol.Response(1,r.status(),0,a.snapshot()))).status());
  }
 }
 @Test void normalMountStillChargesOnceAndStartsExistingCooldown(){
  var a=new ManaAccount(100,100,0);var spell=new ManaSpells.Spell("flight","cast flight","magic.learned.flight",80,30000);
  assertEquals(ManaProtocol.OK,ManaCasting.attempt(a,spell,true,100,false,()->true).status());
  assertEquals(20,a.current);assertEquals(30000,a.remaining("flight",100));
 }
}
