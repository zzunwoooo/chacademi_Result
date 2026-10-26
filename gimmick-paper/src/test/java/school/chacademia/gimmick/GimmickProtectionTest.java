package school.chacademia.gimmick;
import java.lang.reflect.Proxy;
import org.bukkit.*;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class GimmickProtectionTest {
 World world=(World)Proxy.newProxyInstance(getClass().getClassLoader(),new Class<?>[]{World.class},(o,m,a)->null);
 @Test void denyAppliesToBothEndsEvenForOperator(){Location origin=new Location(world,0,64,0),denied=new Location(world,10,64,0);Player op=(Player)Proxy.newProxyInstance(getClass().getClassLoader(),new Class<?>[]{Player.class},(o,m,a)->switch(m.getName()){case "isOnline","isOp","hasPermission"->true;case "getGameMode"->GameMode.CREATIVE;case "getLocation"->origin;default->throw new UnsupportedOperationException(m.getName());});var protection=new GimmickProtection(at->at.getX()<5);assertTrue(protection.pair(op,origin));assertFalse(protection.pair(op,denied));}
 @Test void unavailableRegionsFailClosed(){var p=new GimmickProtection(at->{throw new IllegalStateException();});assertFalse(p.at(new Location(world,0,64,0)));assertFalse(p.at(null));}
}
