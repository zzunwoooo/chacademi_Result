package school.magiccodex.client;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import school.magiccodex.protocol.TamingProtocol;
import static org.junit.jupiter.api.Assertions.*;
class TamingTargetAnchorTest {
 final UUID id=UUID.randomUUID();
 final TamingProtocol.Anchor base=new TamingProtocol.Anchor(id,2,65,3,1.8);
 TamingProtocol.State state(int status){return new TamingProtocol.State(0,status,12,"CA_Wild_alpha_boar",false,.08,0,0,0,"");}
 @Test void hiddenModelEngineBaseStillUsesServerPosition(){assertEquals(base,TamingTargetAnchor.resolve(state(1),500,base,null,false,false));}
 @Test void trackedVanillaUsesSmoothClientPosition(){var moving=new TamingProtocol.Anchor(id,2.1,65,3,1.8);assertEquals(moving,TamingTargetAnchor.resolve(state(1),500,base,moving,true,true));assertEquals(moving,TamingTargetAnchor.resolve(state(1),500,null,moving,true,true));}
 @Test void unregisteredPetNpcAndExpiredTargetsNeverKeepALabel(){for(int status:new int[]{0,5})assertNull(TamingTargetAnchor.resolve(state(status),0,base,null,false,false));assertNull(TamingTargetAnchor.resolve(state(1),1201,base,null,false,false));assertNull(TamingTargetAnchor.resolve(null,0,base,null,false,false));}
 @Test void reusedDisplayOrDeadEntityCannotInheritTheLabel(){assertNull(TamingTargetAnchor.resolve(state(1),0,base,null,true,true));assertNull(TamingTargetAnchor.resolve(state(1),0,base,base,true,false));var other=new TamingProtocol.Anchor(UUID.randomUUID(),2,65,3,1);assertNull(TamingTargetAnchor.resolve(state(1),0,base,other,true,true));}
 @Test void oldServerMissingBaseDoesNotGuessNearbyVisual(){assertNull(TamingTargetAnchor.resolve(state(1),0,null,null,false,false));}
}
