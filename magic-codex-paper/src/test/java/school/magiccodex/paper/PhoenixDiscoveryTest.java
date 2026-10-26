package school.magiccodex.paper;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class PhoenixDiscoveryTest {
 @Test void onlyManifestPhoenixIdsQualify(){for(String variant:new String[]{"flame","darkflame","light","thunder"}){assertTrue(PhoenixDiscovery.isPhoenix("ca_phoenix_"+variant));assertTrue(PhoenixDiscovery.isPhoenix("ca_phoenix_"+variant+"_shiny"));}assertFalse(PhoenixDiscovery.isPhoenix(null));assertFalse(PhoenixDiscovery.isPhoenix("phoenix"));assertFalse(PhoenixDiscovery.isPhoenix("ca_phoenix_flame_fake"));}
}
