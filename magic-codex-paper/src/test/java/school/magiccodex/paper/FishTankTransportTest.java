package school.magiccodex.paper;
import java.util.*;import org.junit.jupiter.api.Test;import static org.junit.jupiter.api.Assertions.*;
import school.magiccodex.protocol.FishTankProtocol;
class FishTankTransportTest {
 @Test void fullHundredFishTankRoundTripFitsPaperAndClientBounds(){
  var fish=new ArrayList<FishTankProtocol.Fish>();var info=new ArrayList<FishTankProtocol.Info>();var held=new ArrayList<FishTankProtocol.Held>();
  for(int i=0;i<141;i++)info.add(new FishTankProtocol.Info("fish_"+i,"물고기"+i,i%4,i%10,"가".repeat(240)));
  for(int i=0;i<100;i++)fish.add(new FishTankProtocol.Fish(UUID.randomUUID().toString(),"fish_"+i,25.5f,1760000000000L,1000,false));
  for(int i=0;i<41;i++)held.add(new FishTankProtocol.Held(i,UUID.randomUUID().toString(),"fish_"+(i+100),25.5f,1760000000000L));
  var state=new FishTankProtocol.State(FishTankProtocol.KIND_OPEN,100,false,fish,held,info,"");var bytes=FishTankProtocol.encode(state);
  assertTrue(bytes.length<=org.bukkit.plugin.messaging.Messenger.MAX_MESSAGE_SIZE,"Paper transport limit "+org.bukkit.plugin.messaging.Messenger.MAX_MESSAGE_SIZE+"; encoded "+bytes.length);
  assertTrue(bytes.length<=FishTankProtocol.MAX_BYTES);assertEquals(state,FishTankProtocol.decode(bytes));
 }
 @Test void priceConfigRejectsNonfiniteNegativeAndUnrepresentableTotals(){
  FishShopPricing.validate(10000,.7,1.5);FishShopPricing.validate(0,0,0);
  for(double v:new double[]{Double.NaN,Double.POSITIVE_INFINITY,-1,Double.MAX_VALUE})assertThrows(IllegalArgumentException.class,()->FishShopPricing.validate(v,.7,1.5));
  assertThrows(IllegalArgumentException.class,()->FishShopPricing.validate(80,1.5,.7));
  assertThrows(IllegalArgumentException.class,()->FishShopPricing.validate(1e12,1,1e12));
 }
 static int state(String name)throws Exception{var f=FishingBridge.class.getDeclaredField(name);f.setAccessible(true);return f.getInt(null);}
 @Test void activeCastSinkingBiteAndFightBlockTankButIdleDoesNot()throws Exception{
  assertFalse(FishingBridge.blocksTank(false,state("READY")));
  for(String name:List.of("LANDING","SINKING","BITING"))assertTrue(FishingBridge.blocksTank(false,state(name)),name);
  for(String name:List.of("READY","LANDING","SINKING","BITING"))assertFalse(FishingBridge.blocksTank(true,state(name)),name);
 }
}
