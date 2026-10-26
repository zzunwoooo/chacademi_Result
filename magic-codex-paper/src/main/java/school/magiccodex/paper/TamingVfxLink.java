package school.magiccodex.paper;

import java.lang.reflect.InvocationTargetException;
import java.util.UUID;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

/** Server-only visual session. Gameplay remains owned by TamingBridge. */
final class TamingVfxLink {
    record Session(Plugin provider, UUID handle) {}
    private TamingVfxLink() {}
    static Session start(MagicCodexBridge owner, Player player, LivingEntity target) {
        Plugin provider=Bukkit.getPluginManager().getPlugin("PortableVFX");
        if(provider==null||!provider.isEnabled())return null;
        try {
            Object handle=provider.getClass().getMethod("startAuthorizedTargetedSpell",Player.class,String.class,LivingEntity.class)
                    .invoke(provider,player,TamingSpell.ID,target);
            return handle instanceof UUID id?new Session(provider,id):null;
        } catch(ReflectiveOperationException|RuntimeException|LinkageError error) {
            warn(owner,"시작",error);return null;
        }
    }
    static void finish(MagicCodexBridge owner, Session session, Player player, boolean success, Location lastTarget) {
        if(session==null||!session.provider().isEnabled())return;
        try {
            Object delivered=session.provider().getClass().getMethod("finishAuthorizedTargetedSpell",Player.class,UUID.class,String.class,Location.class)
                    .invoke(session.provider(),player,session.handle(),success?"external:trigger":"expire",lastTarget);
            if(!Boolean.TRUE.equals(delivered))cancel(owner,session,player);
        } catch(ReflectiveOperationException|RuntimeException|LinkageError error) { warn(owner,"종료",error);cancel(owner,session,player); }
    }
    static void cancel(MagicCodexBridge owner, Session session, Player player) {
        if(session==null||!session.provider().isEnabled())return;
        try {
            session.provider().getClass().getMethod("cancelAuthorizedTargetedSpell",Player.class,UUID.class)
                    .invoke(session.provider(),player,session.handle());
        } catch(ReflectiveOperationException|RuntimeException|LinkageError error) { warn(owner,"정리",error); }
    }
    private static void warn(MagicCodexBridge owner,String phase,Throwable error) {
        Throwable cause=error instanceof InvocationTargetException invocation?invocation.getCause():error;
        owner.getLogger().warning("교화 VFX "+phase+" 실패: "+cause.getClass().getSimpleName());
    }
}
