package school.magiccodex.paper;

import java.util.*;
import school.magiccodex.protocol.ManaProtocol.Snapshot;

/** Main-thread account. Immutable snapshots are published separately for asynchronous readers. */
final class ManaAccount {
    double current,baseMaximum,baseRegen,baseHaste;
    private final Map<String,Double> hasteModifiers=new HashMap<>();
    private final Map<String,double[]> modifiers=new HashMap<>();
    private final Map<String,Double> regenerationMultipliers=new HashMap<>();
    final Map<String,Long> cooldowns=new HashMap<>();
    ManaAccount(double current,double maximum,double regeneration){
        valid(maximum);valid(regeneration);valid(current);
        baseMaximum=maximum;baseRegen=regeneration;this.current=Math.min(current,maximum);
    }
    static void valid(double value){if(!Double.isFinite(value)||value<0||value>1_000_000)throw new IllegalArgumentException("Expected 0..1000000");}
    /**
     * 거꾸로 된 마나병 (아티팩트, 2026-10-26): null = 보통 마나. 켜져 있으면 마나 대신 과열 게이지 —
     * 마법 비용만큼 과열이 차고 초당 {@link #COOLING} 식으며, 가득 차면 {@link #LOCK_MS} 동안 마법을 못 쓴다 (consume 거부).
     * 스냅숏(클라이언트 마나 바)은 current = 남은 여유(최대 - 과열), maximum = 과열 최대치(마나 재생량 x20), regeneration = 냉각량.
     * 보통 마나(current 필드)는 같은 비율로 따라가서(여유 비율 = 마나 비율) 저장·서버 이동·해제 때 그대로 쓰인다:
     * 해제·재장착으로 과열을 지우거나 마나를 채울 수 없다. 잠김 시각은 서버 안에서만 기억한다.
     */
    static final class Heat { double value; long lockUntil; boolean lockedByLast; }
    static final double COOLING=5;
    static final long LOCK_MS=30_000;
    Heat heat;
    static double heatMaximum(double regeneration){return Math.clamp(regeneration*20,20,1_000_000);}
    void heatMode(boolean on){
        if(on==(heat!=null))return;
        var normal=normalSnapshot();
        if(on){heat=new Heat();double max=heatMaximum(normal.regeneration());heat.value=normal.maximum()<=0?0:max*(1-normal.current()/normal.maximum());}
        else{syncHeat();heat=null;}
    }
    boolean heatLocked(long now){return heat!=null&&now<heat.lockUntil;}
    /** Keeps the normal current mana at the same fraction as the free heat capacity. */
    private void syncHeat(){
        if(heat==null)return;var normal=normalSnapshot();double max=heatMaximum(normal.regeneration());
        heat.value=Math.clamp(heat.value,0,max);current=normal.maximum()*(1-heat.value/max);
    }
    Snapshot snapshot(){
        var normal=normalSnapshot();if(heat==null)return normal;
        double max=heatMaximum(normal.regeneration());
        return new Snapshot(Math.clamp(max-heat.value,0,max),max,COOLING,normal.haste());
    }
    Snapshot normalSnapshot(){
        double max=baseMaximum,regen=baseRegen;
        for(var m:modifiers.values()){max+=m[0];regen+=m[1];}
        for(double factor:regenerationMultipliers.values())regen*=factor;
        max=Math.clamp(max,0,1_000_000);regen=Math.clamp(regen,0,1_000_000);
        double haste=baseHaste;for(double bonus:hasteModifiers.values())haste+=bonus;
        return new Snapshot(Math.min(current,max),max,regen,Math.clamp(haste,0,1_000_000));
    }
    void hasteModifier(String key,double value){
        if(key==null||!key.matches("[a-z0-9_.-]+:[a-z0-9_./-]+"))throw new IllegalArgumentException("Invalid modifier key");
        school.magiccodex.protocol.MagicHaste.valid(value);
        if(!hasteModifiers.containsKey(key)&&hasteModifiers.size()>=128)throw new IllegalArgumentException("Too many modifiers");
        if(value==0)hasteModifiers.remove(key);else hasteModifiers.put(key,value);
    }
    void normalize(){current=normalSnapshot().current();syncHeat();}
    /** Sets the normal current mana (heat mode: the heat follows the same fraction). */
    void setCurrent(double value){current=Math.min(value,normalSnapshot().maximum());if(heat!=null){var n=normalSnapshot();heat.value=n.maximum()<=0?0:heatMaximum(n.regeneration())*(1-current/n.maximum());syncHeat();}}
    void modifier(String key,double max,double regen){
        if(key==null||!key.matches("[a-z0-9_.-]+:[a-z0-9_./-]+")||!Double.isFinite(max)||!Double.isFinite(regen)
                ||Math.abs(max)>1_000_000||Math.abs(regen)>1_000_000)throw new IllegalArgumentException("Invalid modifier");
        if(!modifiers.containsKey(key)&&modifiers.size()>=128)throw new IllegalArgumentException("Too many mana modifiers");
        modifiers.put(key,new double[]{max,regen});normalize();
    }
    void removeModifier(String key){modifiers.remove(key);normalize();}
    void regenerationMultiplier(String key,double value){
        if(key==null||!key.matches("[a-z0-9_.-]+:[a-z0-9_./-]+")||!Double.isFinite(value)||value<0||value>10)throw new IllegalArgumentException("Invalid regeneration multiplier");
        if(value==1){regenerationMultipliers.remove(key);return;}
        if(!regenerationMultipliers.containsKey(key)&&regenerationMultipliers.size()>=128)throw new IllegalArgumentException("Too many modifiers");
        regenerationMultipliers.put(key,value);
    }
    void regenerate(){
        if(heat!=null){heat.value=Math.max(0,heat.value-COOLING);heat.lockedByLast=false;syncHeat();return;}
        var s=snapshot();current=Math.min(s.maximum(),s.current()+s.regeneration());
    }
    boolean consume(double cost){
        valid(cost);normalize();
        if(heat!=null){
            long now=System.currentTimeMillis();if(now<heat.lockUntil)return false;
            double max=heatMaximum(normalSnapshot().regeneration());heat.value+=cost;heat.lockedByLast=false;
            if(heat.value>=max){heat.value=max;heat.lockUntil=now+LOCK_MS;heat.lockedByLast=true;}
            syncHeat();return true;
        }
        if(current<cost)return false;current-=cost;return true;
    }
    void add(double value){
        if(!Double.isFinite(value))throw new IllegalArgumentException("Invalid mana delta");
        if(heat!=null){
            heat.value-=value; // 마나를 더하면 식고, 빼면 과열된다
            double max=heatMaximum(normalSnapshot().regeneration());
            if(value>0&&heat.lockedByLast&&heat.value<max)heat.lockUntil=0; // 방금 잠근 시전이 환불됨 (명령 실패)
            heat.lockedByLast=false;syncHeat();return;
        }
        current=Math.clamp(current+value,0,snapshot().maximum());
    }
    long remaining(String id,long now){return Math.max(0,cooldowns.getOrDefault(id,0L)-now);}
    void cooldown(String id,long until,long now){cooldowns.entrySet().removeIf(e->e.getValue()<=now);cooldowns.put(id,until);}
}
