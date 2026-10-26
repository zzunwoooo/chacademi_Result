package dev.portablevfx.paper.internal.spell;

import java.lang.reflect.Method;
import org.bukkit.Bukkit;
import org.bukkit.FluidCollisionMode;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.util.Vector;

/**
 * 빗자루 위에서 쏘는 투사체 연출 보정. 마법 미몹(chabolt)의 CarrierShot 과 같은 공식이라 보이는 위치와 판정 위치가 맞는다.
 * <ul>
 *   <li>출발점을 "비행 속도 × (핑 + 1틱)" 만큼 앞으로 (최대 8블록, 벽·맞을 대상 바로 앞에서 멈춤)</li>
 *   <li>쏘는 방향의 비행 속도 성분만큼 투사체가 더 빠름</li>
 * </ul>
 * ChacademyMotion(빗자루 비행) 플러그인의 {@code broomVelocity(Player)} 를 리플렉션으로 부른다. 없거나 안 타면 아무것도 바꾸지 않는다.
 */
final class BroomCarrier {
    static final double MAX_LEAD_TICKS=8,MAX_LEAD_BLOCKS=8;
    private static Plugin cached;private static Method velocity;

    private BroomCarrier(){}

    /** 비행 속도(블록/틱). 빗자루를 타지 않으면 null. */
    static Vector velocity(Player player){
        Plugin p=Bukkit.getPluginManager().getPlugin("ChacademyMotion");
        if(p==null||!p.isEnabled())return null;
        if(p!=cached){
            try{velocity=p.getClass().getMethod("broomVelocity",Player.class);}catch(ReflectiveOperationException|LinkageError e){velocity=null;}
            cached=p;
        }
        if(velocity==null)return null;
        try{if(velocity.invoke(p,player) instanceof double[] v&&v.length==3)return new Vector(v[0],v[1],v[2]);}catch(ReflectiveOperationException|RuntimeException ignored){}
        return null;
    }

    static double leadTicks(int pingMs){return Math.max(1,Math.min(MAX_LEAD_TICKS,Math.max(0,pingMs)/50.0+1));}

    static Vector leadOffset(Vector v,int pingMs){Vector off=v.clone().multiply(leadTicks(pingMs));double len=off.length();if(len>MAX_LEAD_BLOCKS)off.multiply(MAX_LEAD_BLOCKS/len);return off;}

    static double extraSpeed(Vector v,Vector dir){double d=v.dot(dir);return Double.isFinite(d)?Math.max(0,d):0;}

    /**
     * 지연만큼 앞당긴 출발점. 그 사이에 벽이나 맞을 대상이 있으면 그 바로 앞에서 멈춘다
     * (바로 앞의 적을 건너뛰지 않도록 — 마법 미몹 chabolt 도 그 구간을 판정하며 지나간다).
     */
    static Location lead(Location start,Vector v,int pingMs,java.util.function.Predicate<org.bukkit.entity.Entity> targets){
        Vector off=leadOffset(v,pingMs);double len=off.length();
        if(len<1e-3||start.getWorld()==null)return start;
        Vector dir=off.clone().multiply(1/len);
        var hit=start.getWorld().rayTrace(start,dir,len,FluidCollisionMode.NEVER,true,.3,targets);
        double go=hit==null?len:Math.max(0,hit.getHitPosition().distance(start.toVector())-.3);
        return start.clone().add(dir.multiply(go));
    }
}
