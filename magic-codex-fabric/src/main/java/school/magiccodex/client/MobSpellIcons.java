package school.magiccodex.client;
import java.util.Map;
/** Approved v2 artwork only. Preserve operator/custom paths and all gameplay fields. */
final class MobSpellIcons {
 private MobSpellIcons() {}
 static final Map<String,String> PREVIOUS=Map.ofEntries(
        Map.entry("fox_orbit", "magiccodex:textures/spells/expansion_2/firefly_mark.png"),
        Map.entry("curving_plumes", "magiccodex:textures/spells/expansion_2/firebird_hunt.png"),
        Map.entry("crystal_orbit", "magiccodex:textures/spells/expansion/frost_rupture.png"),
        Map.entry("venom_spit", "magiccodex:textures/spells/expansion_2/thorn_seeds.png"),
        Map.entry("smoke_veil", "magiccodex:textures/spells/novice_extra/faint_veil.png"),
        Map.entry("domain_stomp", "magiccodex:textures/spells/expansion_2/guardian_oath.png"),
        Map.entry("seal_circle", "magiccodex:textures/spells/intermediate/sealing_thread.png"),
        Map.entry("seal_talismans", "magiccodex:textures/spells/expansion/exorcism_flash.png"),
        Map.entry("shield_slam", "magiccodex:textures/spells/intermediate/protective_ward.png"),
        Map.entry("chain_arrow", "magiccodex:textures/spells/expansion_2/guiding_fletching.png"),
        Map.entry("mana_drain", "magiccodex:textures/spells/expansion_2/mana_conduit.png"),
        Map.entry("soul_scythe", "magiccodex:textures/spells/expansion/black_harvest.png"),
        Map.entry("oblivion_orbs", "magiccodex:textures/spells/expansion_2/abyssal_pressure.png"),
        Map.entry("bone_grasp", "magiccodex:textures/spells/novice_extra/shadow_bind.png"),
        Map.entry("shadow_leap", "magiccodex:textures/spells/novice_extra/light_leap.png"),
        Map.entry("binding_pull", "magiccodex:textures/spells/novice/feather_step.png"),
        Map.entry("fairy_dust", "magiccodex:textures/spells/expansion_2/night_lullaby.png"),
        Map.entry("moon_wind", "magiccodex:textures/spells/novice/feather_step.png"),
        Map.entry("gale_wings", "magiccodex:textures/spells/expansion/gale_dash.png"),
        Map.entry("feather_storm", "magiccodex:textures/spells/expansion_2/thorn_seeds.png"),
        Map.entry("hunting_pounce", "magiccodex:textures/spells/novice_extra/light_leap.png"),
        Map.entry("roar_leap", "magiccodex:textures/spells/expansion_2/bell_of_fear.png"),
        Map.entry("gravity_slam", "magiccodex:textures/spells/expansion/weightless_zone.png"),
        Map.entry("tail_spikes", "magiccodex:textures/spells/reviewed_20261016/pressure_lance.png"));
 static String resolve(String id,String icon) {
  String previous=PREVIOUS.get(id);
  return previous!=null && (icon==null || icon.isBlank() || previous.equals(icon))
    ? "magiccodex:textures/spells/mob_skill/"+id+".png" : icon;
 }
}
