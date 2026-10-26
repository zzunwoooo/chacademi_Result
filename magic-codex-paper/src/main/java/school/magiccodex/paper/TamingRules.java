package school.magiccodex.paper;

final class TamingRules {
    static double chance(double base,double bonus,double health,double maximum,boolean boss){
        if(!Double.isFinite(base)||!Double.isFinite(bonus)||maximum<=0)return 0;
        return Math.clamp(base+(boss?0:bonus*(1-Math.clamp(health/maximum,0,1))),0,1);
    }
    static boolean succeeds(double chance,double draw){return draw>=0&&draw<chance;}
}
