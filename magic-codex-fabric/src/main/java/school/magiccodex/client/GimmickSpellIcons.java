package school.magiccodex.client;
import java.util.Set;
final class GimmickSpellIcons {
 private static final Set<String> IDS=Set.of("thawing_heat","steam_ignition","water_level","stone_weight","vine_awaken","light_beam","mirror_tune","prism_tint","statue_wake","rewind","conduit_turn","dimension_mark");
 static String resolve(String id,String icon){return IDS.contains(id)&&(icon==null||icon.isBlank())?"magiccodex:textures/spells/gimmick/"+id+".png":icon;}
}
