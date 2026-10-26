package school.magiccodex.client;

import school.magiccodex.protocol.TemperatureProtocol;

/** Unknown or expired values never masquerade as zero degrees. Preview never changes server state. */
public final class TemperatureState {
    private float server=Float.NaN,preview=Float.NaN;
    private long received=-1;
    public void accept(TemperatureProtocol.Snapshot value,long now){server=value.available()?value.celsius():Float.NaN;received=now;}
    public float current(long now){return Float.isFinite(preview)?preview:received>=0&&now-received<=65000?server:Float.NaN;}
    public void preview(float celsius){TemperatureProtocol.validate(celsius);preview=celsius;}
    public boolean previewing(){return Float.isFinite(preview);}
    public void live(){preview=Float.NaN;}
    public void clearServer(){server=Float.NaN;received=-1;}
    public void reset(){clearServer();live();}
    public static String format(float celsius){
        if(!Float.isFinite(celsius))return "—°C";
        float rounded=Math.round(celsius*10)/10f;
        return java.math.BigDecimal.valueOf(rounded).setScale(1,java.math.RoundingMode.HALF_UP).stripTrailingZeros().toPlainString()+"°C";
    }
}
