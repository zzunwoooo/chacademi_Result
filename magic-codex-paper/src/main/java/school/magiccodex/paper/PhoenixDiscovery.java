package school.magiccodex.paper;
import java.util.Set;
/** Exact IDs from source-owned custom creature MCPets and taming manifests. */
final class PhoenixDiscovery {
    private static final Set<String> IDS=Set.of("ca_phoenix_flame","ca_phoenix_flame_shiny","ca_phoenix_darkflame","ca_phoenix_darkflame_shiny","ca_phoenix_light","ca_phoenix_light_shiny","ca_phoenix_thunder","ca_phoenix_thunder_shiny");
    static boolean isPhoenix(String id){return id!=null&&IDS.contains(id);}
}
