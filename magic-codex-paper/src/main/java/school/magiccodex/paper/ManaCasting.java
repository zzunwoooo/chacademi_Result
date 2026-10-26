package school.magiccodex.paper;

import java.util.function.BooleanSupplier;
import school.magiccodex.protocol.ManaProtocol;

/** Single server-thread transaction: reject first, charge once, refund rejected dispatches. */
final class ManaCasting {
    record Result(int status,int cooldownMillis){}
    static Result attempt(ManaAccount a,ManaSpells.Spell spell,boolean permitted,long now,BooleanSupplier dispatch){
        return attempt(a,spell,permitted,now,false,dispatch);
    }
    static Result attempt(ManaAccount a,ManaSpells.Spell spell,boolean permitted,long now,boolean unavailable,BooleanSupplier dispatch){
        if(spell==null)return new Result(ManaProtocol.UNKNOWN,0);
        if(!permitted)return new Result(ManaProtocol.LOCKED,0);
        if(unavailable)return new Result(ManaProtocol.UNAVAILABLE,0);
        boolean wind=spell.id().equals("wind_message");long remaining=wind?0:a.remaining(spell.id(),now);
        if(remaining>0)return new Result(ManaProtocol.COOLDOWN,(int)Math.min(86_400_000,remaining));
        if(!a.consume(spell.cost()))return new Result(ManaProtocol.EMPTY,0);
        boolean success=false;
        try{success=dispatch.getAsBoolean();}
        finally{if(!success)a.add(spell.cost());}
        if(!success)return new Result(ManaProtocol.FAILED,0);
        int cooldown=wind?0:school.magiccodex.protocol.MagicHaste.cooldown(spell.cooldown(),a.snapshot().haste());
        a.cooldown(spell.id(),now+cooldown,now);
        return new Result(ManaProtocol.OK,cooldown);
    }
}
