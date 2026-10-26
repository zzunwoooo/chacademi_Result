package school.magiccodex.paper;

import java.nio.file.*;
import java.util.Set;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class DonationCatalogTest {
    // Retired spell with no current successor; kept so historic donation records still resolve to a name.
    private static final Set<String> KNOWN_ORPHANS=Set.of("wind_vault");

    // 2026-10-21: every learnable catalog spell (mob 300~323, arcana, gimmick 239~250, fish_trap) is donatable.
    private static final Set<String> ADDED_20261021=Set.of(
        "fox_orbit","curving_plumes","crystal_orbit","venom_spit","smoke_veil","domain_stomp",
        "seal_circle","seal_talismans","shield_slam","chain_arrow","mana_drain","soul_scythe",
        "oblivion_orbs","bone_grasp","shadow_leap","binding_pull","fairy_dust","moon_wind",
        "gale_wings","feather_storm","hunting_pounce","roar_leap","gravity_slam","tail_spikes",
        "landing_airbrake","hushed_soles","thorn_manners","cinder_skipring","droplet_relay","folding_tideplane","fish_trap",
        "thawing_heat","steam_ignition","water_level","stone_weight","vine_awaken","light_beam","mirror_tune","prism_tint","statue_wake","rewind","conduit_turn","dimension_mark");

    /** Every catalog id, enabled or not (ManaSpells.byId keeps only enabled spells). */
    private static Set<String> catalogIds()throws Exception{
        var y=new YamlConfiguration();y.load(Path.of("src/main/resources/mana-spells.yml").toFile());
        var spells=y.getConfigurationSection("spells");assertNotNull(spells);return spells.getKeys(false);
    }
    @Test void everyDonationEntryUsesACurrentCatalogId()throws Exception{
        var all=catalogIds();
        var y=new YamlConfiguration();y.load(Path.of("src/main/resources/donation-spells.yml").toFile());
        var root=y.getConfigurationSection("spells");assertNotNull(root);
        for(String id:root.getKeys(false)){
            assertEquals("magic.learned."+id,root.getString(id+".permission"),id);
            if(KNOWN_ORPHANS.contains(id))continue;
            assertTrue(all.contains(id),"donation-spells.yml uses retired id: "+id);
        }
        assertTrue(all.containsAll(ADDED_20261021));
        for(String id:all)assertTrue(root.contains(id),"mana spell missing from donation catalog: "+id);
        for(String retired:Set.of("feather_step","dampen_powder","handful_of_snow","wet_footprints","pressure_weight","cold_storage","liquid_barrier","water_rebound","thaw_pulse"))
            assertFalse(root.contains(retired),retired);
    }
    @Test void runtimeRoutesUseCurrentCatalogIds()throws Exception{
        var all=catalogIds();
        for(String id:SpellRules.load(Path.of("src/main/resources/spell-runtime.yml").toFile()).keySet())
            assertTrue(all.contains(id),"spell-runtime.yml uses retired id: "+id);
    }
}
