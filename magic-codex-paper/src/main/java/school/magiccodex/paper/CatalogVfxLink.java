package school.magiccodex.paper;

import java.lang.reflect.InvocationTargetException;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

/** Narrow optional bridge, invoked inside ManaCasting's existing charge/refund transaction. */
final class CatalogVfxLink {
    private CatalogVfxLink() {}
    static Boolean cast(MagicCodexBridge owner,Player player,String spellId) { return cast(owner,player,spellId,null); }
    /**
     * #77 (2026-10-25): target = the entity SpellRuntime already validated (client aim + server checks). PortableVFX then
     * follows it instead of re-tracing its own thin eye ray, which missed players / moving targets and refused the cast.
     * Older PortableVFX without the 3-argument method: the 2-argument call (old behaviour).
     */
    static Boolean cast(MagicCodexBridge owner,Player player,String spellId,org.bukkit.entity.Entity target) {
        var plugin=Bukkit.getPluginManager().getPlugin("PortableVFX");
        if(plugin==null||!plugin.isEnabled())return owner.requiresCatalogVisual(spellId)?Boolean.FALSE:null;
        try {
            Object result=null;boolean called=false;
            if(target instanceof org.bukkit.entity.LivingEntity living){
                try{result=plugin.getClass().getMethod("castAuthorizedSpell",Player.class,String.class,org.bukkit.entity.LivingEntity.class).invoke(plugin,player,spellId,living);called=true;}
                catch(NoSuchMethodException older){/* fall through to the 2-argument call */}
            }
            if(!called)result=plugin.getClass().getMethod("castAuthorizedSpell",Player.class,String.class).invoke(plugin,player,spellId);
            if(result==null)return owner.requiresCatalogVisual(spellId)?Boolean.FALSE:null;
            if(result instanceof Boolean)return (Boolean)result;
            owner.getLogger().warning("PortableVFX catalog bridge returned an invalid result");return false;
        } catch(NoSuchMethodException unavailable) {
            return owner.requiresCatalogVisual(spellId)?Boolean.FALSE:null; // Never fall back to legacy effects for the new catalogue.
        } catch(ReflectiveOperationException | RuntimeException error) {
            Throwable cause=error instanceof InvocationTargetException e?e.getCause():error;
            owner.getLogger().warning("Catalog VFX dispatch failed: "+cause.getClass().getSimpleName());
            return false;
        }
    }
}
