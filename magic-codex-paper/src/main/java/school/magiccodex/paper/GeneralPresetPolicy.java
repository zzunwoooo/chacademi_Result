package school.magiccodex.paper;
import java.util.Map;
import school.magiccodex.protocol.GeneralPresetProtocol;
final class GeneralPresetPolicy {
 static int status(int selected,int target,boolean ready,boolean combat,boolean casting,Map<String,Long> cooldowns,long now){
  if(!ready)return GeneralPresetProtocol.UNAVAILABLE;
  if(target==0||target==selected)return GeneralPresetProtocol.OK;
  if(combat)return GeneralPresetProtocol.COMBAT;
  if(casting||cooldowns.values().stream().anyMatch(until->until>now))return GeneralPresetProtocol.COOLDOWN;
  return GeneralPresetProtocol.OK;
 }
}
