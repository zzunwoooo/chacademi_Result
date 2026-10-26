package school.magiccodex.paper;
import java.util.Map;import school.magiccodex.protocol.GeneralPresetProtocol;import school.magiccodex.protocol.GeneralPresetModeProtocol;
final class GeneralPresetModePolicy{
 static int status(int selected,int target,boolean ready,boolean combat,boolean casting,Map<String,Long> cooldowns,long now){
  if(!GeneralPresetModeProtocol.valid(selected)||target<0||target>GeneralPresetModeProtocol.OFF)return GeneralPresetProtocol.UNAVAILABLE;
  return GeneralPresetPolicy.status(selected,target,ready,combat,casting,cooldowns,now);
 }
}
