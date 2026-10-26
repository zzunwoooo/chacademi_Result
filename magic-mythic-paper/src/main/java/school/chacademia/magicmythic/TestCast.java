package school.chacademia.magicmythic;

import com.mojang.brigadier.exceptions.CommandSyntaxException;
import io.lumine.mythic.bukkit.MythicBukkit;
import java.util.*;
import org.bukkit.FluidCollisionMode;
import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;

/**
 * /테스트 마법 시전 <마법> (OP): run a spell's gameplay skill directly, skipping mana, cooldown, permission and the VFX
 * catalogue - for testing new spells before their VFX are enabled. Reads the route (skill, target, range, damage) from
 * plugins/MagicCodexBridge/spell-runtime.yml, picks the target like the Bridge does.
 */
final class TestCast {
    private TestCast() {}

    static String cast(MagicMythicPlugin plugin, Player p, String id) throws CommandSyntaxException {
        var routes = plugin.spellNames();
        var route = routes.route(id);
        if (route == null) {
            if (!routes.available()) throw new IllegalStateException("spell-runtime.yml 이 없습니다: " + routes.file());
            throw ChacaLink.error("미몹 스킬이 연결되지 않은 마법이에요: " + id);
        }
        String spellId = routes.resolve(id);
        String skill = route.getString("skill", ""), target = route.getString("target", "SELF");
        double range = route.getDouble("range", 24), damage = route.getDouble("base-damage", 0) + route.getDouble("power-ratio", 0) * Links.power(p);
        Entity chosen = null;
        Location at = p.getLocation();
        if (!target.equals("SELF") && !target.equals("BLOCK")) {
            var eye = p.getEyeLocation();
            var hit = p.getWorld().rayTrace(eye, eye.getDirection(), range, FluidCollisionMode.NEVER, true, 0.55, e -> !e.equals(p) && plugin.canTarget(p, e));
            at = hit != null ? hit.getHitPosition().toLocation(p.getWorld()) : eye.clone().add(eye.getDirection().multiply(range));
            if (hit != null && hit.getHitEntity() instanceof LivingEntity le) chosen = le;
            if (target.endsWith("ENTITY") && chosen == null) throw ChacaLink.error("이 마법은 대상이 필요해요. 몹을 바라보고 다시 써 주세요");
        }
        final double dmg = damage;
        boolean ok = MythicBukkit.inst().getAPIHelper().castSkill(p, skill, p, p.getLocation(), chosen == null ? List.of() : List.of(chosen), List.of(at), 1f,
            data -> { data.getVariables().putDouble("damage", dmg); data.getVariables().putDouble("magic_power", Links.power(p)); });
        if (!ok) throw new IllegalStateException("MythicMobs castSkill 실패: " + spellId + " (" + skill + ")");
        return routes.label(spellId) + " 시험 시전 (" + skill + ", 피해값 " + String.format("%.1f", dmg) + ")";
    }
}
