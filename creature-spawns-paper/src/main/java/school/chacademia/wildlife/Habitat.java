package school.chacademia.wildlife;

import java.util.*;
import org.bukkit.configuration.ConfigurationSection;

/** habitats.yml 한 항목을 로드/리로드 때 한 번만 해석해 둔 불변 값. 스폰 후보마다 설정을 다시 읽지 않는다. */
record Habitat(String id,String name,String family,Set<String> biomes,Set<String> seasons,boolean water,int minY,int maxY,int time,
               boolean thunder,boolean fullMoon,boolean nearWater,boolean naturalGround,
               int clearanceRadius,int clearanceHeight,int maxBlockLight,
               Set<String> structures,double structureRadius,int groupMin,int groupMax,int cap,double capRadius,
               String rarity,double weight) {
    static final int ANY=0,DAY=1,NIGHT=2;

    /** 필수 값이 잘못되면 예외, medium/time 오타는 경고 후 안전한 기본값(land/any)으로 둔다. */
    static final Set<String> RARITIES=Set.of("1","2","3","boss");

    /**
     * 비슷한 몹 묶음(인접 청크 중복 억제용). habitats.yml family로 직접 지정 가능.
     * 기본: 첫 단어(golem_arcane·golem_royal → golem, bear·bear_black → bear),
     * lostasset_mobN_xxx → xxx(요정 4종은 fairy), mf_warden_* → mf_warden, ncr_*_skeleton → ncr, baby_polar_bear → bear, baby_yeti → yeti.
     */
    static String family(String id) {
        if(id.startsWith("lostasset_mob")) {
            String rest=id.replaceFirst("^lostasset_mob\\d+_","");
            return rest.endsWith("fairy")||rest.endsWith("sprite")?"fairy":rest;
        }
        if(id.startsWith("mf_warden")) return "mf_warden";
        if(id.startsWith("ncr_")) return "ncr";
        if(id.equals("baby_polar_bear")) return "bear";
        if(id.equals("baby_yeti")) return "yeti";
        if(id.startsWith("voxelspawns_")) return id.substring(12);
        int cut=id.indexOf('_');
        return cut<0?id:id.substring(0,cut);
    }

    static Habitat parse(String id,ConfigurationSection s,List<String> warnings) { return parse(id,s,warnings,Map.of()); }

    /**
     * defaultRarity: 운영 서버의 habitats.yml에 rarity가 아직 없을 때 쓰는 값(플러그인에 들어 있는 기본 habitats.yml에서 읽음).
     * weight가 0보다 크면 등급 가중치 대신 이 값으로 추첨한다(종 하나만 따로 조절할 때).
     */
    static Habitat parse(String id,ConfigurationSection s,List<String> warnings,Map<String,String> defaultRarity) {
        if(s==null) throw new IllegalArgumentException("잘못된 서식지: "+id);
        if(s.getStringList("biomes").isEmpty() || s.getInt("group-min")<1 || s.getInt("group-max")>6
            || s.getInt("group-max")<s.getInt("group-min") || s.getInt("cap")<s.getInt("group-max")
            || s.getInt("min-y")>s.getInt("max-y")) throw new IllegalArgumentException("잘못된 서식지: "+id);
        Object seasons=s.get("seasons");
        if(seasons!=null && !(seasons instanceof List<?>)) throw new IllegalArgumentException("seasons는 목록으로 지정하세요: "+id);
        var normalized=new LinkedHashSet<String>();
        if(seasons instanceof List<?> values) for(Object value:values) normalized.add(WildlifePlugin.seasonId(value,id));
        String medium=String.valueOf(s.getString("medium","land")).toLowerCase(Locale.ROOT).trim();
        if(!medium.equals("land")&&!medium.equals("water")) { warnings.add(id+": medium '"+medium+"' → land"); medium="land"; }
        String period=String.valueOf(s.getString("time","any")).toLowerCase(Locale.ROOT).trim();
        int time=switch(period) { case "day" -> DAY; case "night" -> NIGHT; default -> ANY; };
        if(time==ANY&&!period.equals("any")) warnings.add(id+": time '"+period+"' → any");
        String rarity=String.valueOf(s.getString("rarity",defaultRarity.getOrDefault(id,"1"))).toLowerCase(Locale.ROOT).trim();
        if(rarity.equals("보스"))rarity="boss";
        if(!RARITIES.contains(rarity)) { warnings.add(id+": rarity '"+rarity+"' → 1"); rarity="1"; }
        double weight=s.getDouble("weight",0);
        if(weight<0) { warnings.add(id+": weight "+weight+" → 등급 기본"); weight=0; }
        return new Habitat(id,s.getString("name",id),s.getString("family",family(id)),Set.copyOf(s.getStringList("biomes")),Collections.unmodifiableSet(normalized),medium.equals("water"),
            s.getInt("min-y"),s.getInt("max-y"),time,s.getBoolean("thunder"),s.getBoolean("full-moon"),
            s.getBoolean("near-water"),s.getBoolean("natural-ground"),
            (s.getInt("clearance-width",1)-1)/2,s.getInt("clearance-height",2),s.getInt("max-block-light",7),
            Set.copyOf(s.getStringList("structures")),s.getDouble("structure-radius",48),
            s.getInt("group-min"),s.getInt("group-max"),s.getInt("cap"),s.getDouble("cap-radius",64),
            rarity,weight);
    }
}
