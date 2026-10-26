package school.magiccodex.paper;

final class ClimateMath {
    private ClimateMath(){}
    static float temperature(double min,double max,long time,double biomeOffset,boolean rain,double rainCooling){
        double blend=(1+Math.cos((Math.floorMod(time,24000)-6000)*Math.PI*2/24000))/2;
        return (float)Math.clamp(min+(max-min)*blend+biomeOffset-(rain?rainCooling:0),-100,100);
    }
}
