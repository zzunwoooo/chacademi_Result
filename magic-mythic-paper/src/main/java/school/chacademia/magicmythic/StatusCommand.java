package school.chacademia.magicmythic;

import com.mojang.brigadier.exceptions.CommandSyntaxException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.bukkit.Bukkit;
import org.bukkit.FluidCollisionMode;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import school.magiccodex.protocol.StatusProtocol.Status;

/**
 * 테스트용: /테스트 몹 상태이상 <종류|해제|목록> [초=5] [나|바라봄|닉네임=바라봄] [세기]
 * 예) /테스트 몹 상태이상 기절 3 바라봄 · /테스트 몹 상태이상 화상 5 나 · /테스트 몹 상태이상 둔화 4 바라봄 0.5 · /테스트 몹 상태이상 해제 바라봄
 * 보스 규칙·면역은 실제와 같다 (보스 기절 -> 둔화). 크리에이티브·관전자에게는 걸리지 않는다.
 */
final class StatusCommand {
    private StatusCommand() {}

    static List<String> labels() { var out = new ArrayList<String>(); for (Status s : Status.values()) out.add(s.label); return out; }

    static List<String> list() {
        var out = new ArrayList<String>();
        out.add("── 상태이상 종류 ──");
        for (Status s : Status.values()) out.add(" " + s.label + " (" + s.id + ")");
        return out;
    }

    /** 대상 칸 후보: 나, 바라봄, 접속 중인 유저 닉네임. */
    static List<String> targets() {
        var out = new ArrayList<String>(List.of("나", "바라봄"));
        for (Player p : Bukkit.getOnlinePlayers()) out.add(ChacaLink.name(p.getUniqueId()));
        return out;
    }

    static String apply(MagicMythicPlugin plugin, CommandSender sender, String kind, double seconds, String who, double power) throws CommandSyntaxException {
        Status s = Status.of(kind);
        if (s == null) throw ChacaLink.error("모르는 상태이상이에요: " + kind + " (/테스트 몹 상태이상 목록)");
        LivingEntity target = target(sender, who);
        LivingEntity source = sender instanceof Player p && !p.equals(target) ? p : null;
        boolean ok = plugin.status().apply(source, target, s, (int) Math.round(seconds * 20), power);
        if (!ok) throw ChacaLink.error(name(target) + "에게 " + s.label + "을(를) 걸 수 없어요 (면역·보스·크리에이티브·마나번 내부쿨)");
        return name(target) + "에게 " + s.label + " " + seconds + "초를 걸었어요";
    }

    static String clear(MagicMythicPlugin plugin, CommandSender sender, String who) throws CommandSyntaxException {
        LivingEntity target = target(sender, who);
        plugin.status().clear(target);
        return name(target) + "의 상태이상을 모두 지웠어요";
    }

    private static String name(LivingEntity e) { return e instanceof Player p ? ChacaLink.name(p.getUniqueId()) + " 님" : e.getType().name().toLowerCase(Locale.ROOT); }

    private static LivingEntity target(CommandSender sender, String who) throws CommandSyntaxException {
        if (who.equals("나")) {
            if (sender instanceof Player p) return p;
            throw ChacaLink.error("콘솔에서는 닉네임을 적어 주세요");
        }
        if (who.equals("바라봄")) {
            if (!(sender instanceof Player p)) throw ChacaLink.error("콘솔에서는 닉네임을 적어 주세요");
            var hit = p.getWorld().rayTrace(p.getEyeLocation(), p.getEyeLocation().getDirection(), 32, FluidCollisionMode.NEVER, true, 0.3,
                e -> e instanceof LivingEntity && !e.equals(p));
            if (hit != null && hit.getHitEntity() instanceof LivingEntity le) return le;
            throw ChacaLink.error("바라보는 대상이 없어요 (32칸 안)");
        }
        var id = ChacaLink.find(who);
        Player p = id == null ? null : Bukkit.getPlayer(id);
        if (p == null) throw ChacaLink.error(who + " 님을 찾을 수 없어요 (접속 중이어야 해요)");
        return p;
    }
}
