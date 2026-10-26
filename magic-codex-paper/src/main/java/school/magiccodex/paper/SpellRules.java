package school.magiccodex.paper;

import java.io.File;
import java.util.*;
import org.bukkit.configuration.file.YamlConfiguration;

/** Operator-owned runtime routes. No arbitrary command or client-supplied statistic is accepted. */
final class SpellRules {
    enum Target {SELF, AIM, ENTITY, AIR_ENTITY, BURNING_ENTITY, BLOCK}
    /** ground = an area/point/wall spell placed on the ground (spells.py; 1-6 #6): range is horizontal, the point drops onto the ground. */
    /** pvpBase/pvpRatio: 전투 스펙 2026-10-26 PvP 전용 피해 (spell-runtime.yml pvp-base / pvp-ratio). 없으면 -1 (MagicMythic 기본 배율). */
    record Rule(String id,String name,String skill,Target target,boolean utility,int range,double base,double ratio,boolean ground,double pvpBase,double pvpRatio){
        Rule(String id,String name,String skill,Target target,boolean utility,int range,double base,double ratio,boolean ground){this(id,name,skill,target,utility,range,base,ratio,ground,-1,-1);}
        Rule(String id,String name,String skill,Target target,boolean utility,int range,double base,double ratio){this(id,name,skill,target,utility,range,base,ratio,false);}
        double damage(double power){return Math.clamp(base+ratio*Math.clamp(power,0,1_000_000),0,1_000_000);}
        /** 미몹 변수 pvp_scale: 플레이어가 플레이어를 맞힐 때 이 시전의 모든 타격에 곱하는 배율. -1 = 없음. */
        double pvpScale(double power){return CombatSpec.pvpScale(damage(power),pvpBase,pvpRatio,power);}
    }
    /** #80: range x multiplier, rounded, 1..64 blocks. Multiplier 1.0 returns the configured range unchanged. */
    static int scaledRange(int range,double multiplier){return multiplier==1.0?range:(int)Math.clamp(Math.round(range*multiplier),1,64);}
    static Map<String,Rule> load(File file)throws Exception {
        var yaml=new YamlConfiguration();yaml.load(file);var root=yaml.getConfigurationSection("spells");
        if(root==null||root.getKeys(false).size()>512)throw new IllegalArgumentException("spells 목록을 확인하세요.");
        // #80 (2026-10-25): one multiplier for every spell's range (1.0 = unchanged). Server checks only - see scaledRange.
        double mult=yaml.getDouble("range-multiplier",1.0);
        if(!Double.isFinite(mult)||mult<0.25||mult>3)throw new IllegalArgumentException("range-multiplier 는 0.25~3 이어야 합니다.");
        var result=new LinkedHashMap<String,Rule>();
        for(String id:root.getKeys(false)){
            var s=root.getConfigurationSection(id);if(s==null||!id.matches("[a-z0-9_-]{1,64}"))throw new IllegalArgumentException(id);
            String name=s.getString("name",""),skill=s.getString("skill","");
            if(name.isBlank()||name.length()>64||!skill.matches("CHA_[a-z0-9_]{1,80}"))throw new IllegalArgumentException(id+" name/skill");
            var target=Target.valueOf(s.getString("target","SELF"));int range=s.getInt("range",24);
            double base=s.getDouble("base-damage",0),ratio=s.getDouble("power-ratio",0);
            if(range<1||range>48||!Double.isFinite(base)||!Double.isFinite(ratio)||base<0||base>10000||ratio<0||ratio>100)throw new IllegalArgumentException(id+" 수치");
            // 전투 스펙 2026-10-26: PvP 전용 피해 (둘 다 있어야 씀). 없으면 MagicMythic config pvp.fallback-scale.
            double pvpBase=-1,pvpRatio=-1;
            if(s.contains("pvp-base")||s.contains("pvp-ratio")){
                pvpBase=s.getDouble("pvp-base",Double.NaN);pvpRatio=s.getDouble("pvp-ratio",Double.NaN);
                if(!Double.isFinite(pvpBase)||!Double.isFinite(pvpRatio)||pvpBase<0||pvpBase>10000||pvpRatio<0||pvpRatio>100)throw new IllegalArgumentException(id+" pvp 수치");
            }
            result.put(id,new Rule(id,name,skill,target,s.getBoolean("utility",false),scaledRange(range,mult),base,ratio,target==Target.AIM&&s.getBoolean("ground",false),pvpBase,pvpRatio));
        }
        return Collections.unmodifiableMap(result);
    }
}
