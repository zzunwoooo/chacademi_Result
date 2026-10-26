package school.chacademia.magicmythic;
import java.util.Set;
final class SupportPolicy {
 private static final Set<String> BENEFICIAL=Set.of("speed","haste","strength","instant_health","jump_boost","regeneration","resistance","fire_resistance","water_breathing","invisibility","night_vision","health_boost","absorption","saturation","slow_falling","conduit_power","dolphins_grace","luck");
 static boolean beneficial(String key){return BENEFICIAL.contains(key);}
 static boolean permits(boolean beneficial,boolean self,boolean hostileAllowed){return beneficial||self||hostileAllowed;}
}
