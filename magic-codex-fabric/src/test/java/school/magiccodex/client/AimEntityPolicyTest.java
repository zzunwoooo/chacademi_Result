package school.magiccodex.client;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class AimEntityPolicyTest {
 @Test void invisibleModelBasesAndOrdinaryMobsRemainTargets(){assertTrue(AimEntityPolicy.allowed(true,false,false,false,true));assertTrue(AimEntityPolicy.allowed(true,false,false,false,false));}
 @Test void ordinaryPlayersAreAllowedButStealthPlayersAreNot(){assertTrue(AimEntityPolicy.allowed(true,false,false,true,false));assertFalse(AimEntityPolicy.allowed(true,false,false,true,true));}
 @Test void deadArmorStandAndSpectatorTargetsRemainRejected(){for(boolean invisible:new boolean[]{false,true}){assertFalse(AimEntityPolicy.allowed(false,false,false,false,invisible));assertFalse(AimEntityPolicy.allowed(true,true,false,false,invisible));assertFalse(AimEntityPolicy.allowed(true,false,true,true,invisible));}}
}