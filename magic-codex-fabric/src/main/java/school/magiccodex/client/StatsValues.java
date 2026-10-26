package school.magiccodex.client;

import java.math.BigDecimal;
import java.math.RoundingMode;

/** Presentation data; null means unavailable, never a fabricated zero. */
public record StatsValues(String nickname,int circle,String dormitory,Double power,Double health,
                          Double mana,Double maxMana,Double manaRegen,Double armor,Integer learned,
                          int total,Double popularity,Double haste) {
    public StatsValues(String nickname,int circle,String dormitory,Double power,Double health,Double mana,Double maxMana,Double manaRegen,Double armor,Integer learned,int total,Double popularity){this(nickname,circle,dormitory,power,health,mana,maxMana,manaRegen,armor,learned,total,popularity,null);}
    public StatsValues {
        nickname=nickname==null?"":nickname;
        dormitory=dormitory==null?"":dormitory;
        circle=Math.clamp(circle,0,9);
        total=Math.max(0,total);
        learned=learned==null?null:Math.clamp(learned,0,total);
    }
    public static String number(Double value){
        if(value==null || !Double.isFinite(value))return "—";
        return BigDecimal.valueOf(value).setScale(1,RoundingMode.HALF_UP).stripTrailingZeros().toPlainString();
    }
    public static float fraction(Double current,Double maximum){
        if(current==null || maximum==null || !Double.isFinite(current) || !Double.isFinite(maximum) || maximum<=0)return 0;
        return (float)Math.clamp(current/maximum,0,1);
    }
}
