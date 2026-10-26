package school.chacademia.magicmythic;

import io.lumine.mythic.api.adapters.AbstractEntity;
import io.lumine.mythic.api.skills.ThreadSafetyLevel;
import io.lumine.mythic.api.skills.conditions.IEntityCondition;
import io.lumine.mythic.core.skills.SkillCondition;
import org.bukkit.entity.Player;

/** chaenemy - true when a player's spell may hurt this entity (same rule as every cha* mechanic). */
public final class EnemyCondition extends SkillCondition implements IEntityCondition {
    private final MagicMythicPlugin plugin;
    public EnemyCondition(MagicMythicPlugin plugin, String line) {
        super(line); this.plugin = plugin;
        this.threadSafetyLevel = ThreadSafetyLevel.SYNC_ONLY;
    }
    @Override public boolean check(AbstractEntity entity) {
        var e = entity.getBukkitEntity();
        // Without a caster here, judge as if any online player cast it (player caster rules).
        Player any = e.getWorld().getPlayers().stream().filter(p -> !p.equals(e)).findFirst().orElse(null);
        return any != null && plugin.targets().enemy(any, e);
    }
}
