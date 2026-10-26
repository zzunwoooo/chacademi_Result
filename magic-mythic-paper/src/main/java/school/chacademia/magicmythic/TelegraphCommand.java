package school.chacademia.magicmythic;

import com.mojang.brigadier.exceptions.CommandSyntaxException;
import org.bukkit.FluidCollisionMode;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import school.magiccodex.protocol.TelegraphProtocol;
import school.magiccodex.protocol.TelegraphProtocol.Shape;
import school.magiccodex.protocol.TelegraphProtocol.Show;

/**
 * 테스트용 (피해 없음, 보이기만): /테스트 몹 예고 <원형|직선|부채꼴|전부|봉인진|봉인진변색종|빙핵레이저|빙핵레이저변색종> [초=1]
 *  원형 = 바라보는 블록(최대 24칸) 중심 반경 3, 직선 = 내 발에서 바라보는 방향 길이 8 폭 2, 부채꼴 = 반경 6 각도 70.
 *  빙핵레이저(변색종) = 빙핵의 수호자 스킬과 같은 직선 24x3 예고 1.2초 + FrostLaser VFX (내 발에서 바라보는 방향).
 *  봉인진(변색종) = 봉인의 술사 스킬과 같은 반경 7 예고 1.2초 + SealCircle VFX (PortableVFX에 sealcircle 팩이 있어야 보임).
 * /테스트 몹 예고효과 <효과> <원형|원형내발|직선|부채꼴> <크기> [각도·폭] [틱=24] [선행=2] [배율=1]
 */
final class TelegraphCommand {
    private TelegraphCommand() {}

    static final java.util.List<String> KINDS = java.util.List.of("원형", "직선", "부채꼴", "전부", "봉인진", "봉인진변색종", "빙핵레이저", "빙핵레이저변색종");
    static final java.util.List<String> SHAPES = java.util.List.of("원형", "원형내발", "직선", "부채꼴");

    /** @param secondsOrNull null = 기본 (봉인진·빙핵레이저는 실제 스킬과 같은 1.2초, 나머지 1초) */
    static String run(MagicMythicPlugin plugin, Player p, String kind, Double secondsOrNull) throws CommandSyntaxException {
        double seconds = secondsOrNull == null ? 1 : Math.clamp(secondsOrNull, 0.1, 30);
        int ticks = (int) Math.round(seconds * 20);
        var feet = p.getLocation();
        float yaw = feet.getYaw();
        Location look = look(p);
        var t = plugin.telegraphs();
        switch (kind) {
            case "원형" -> show(plugin, p, circle(t, look, 3, ticks));
            case "직선" -> show(plugin, p, new Show(t.id(), Shape.LINE, feet.getX(), Telegraphs.ground(feet), feet.getZ(), yaw, 8, 2, 0, ticks, TelegraphProtocol.RED));
            case "부채꼴" -> show(plugin, p, new Show(t.id(), Shape.CONE, feet.getX(), Telegraphs.ground(feet), feet.getZ(), yaw, 6, 0, 70, ticks, TelegraphProtocol.RED));
            case "전부" -> {
                var side = feet.getDirection().setY(0).normalize();
                var left = feet.clone().add(side.getZ() * 5, 0, -side.getX() * 5);
                show(plugin, p, circle(t, look, 3, ticks));
                show(plugin, p, new Show(t.id(), Shape.LINE, feet.getX(), Telegraphs.ground(feet), feet.getZ(), yaw + 35, 8, 2, 0, ticks, TelegraphProtocol.RED));
                show(plugin, p, new Show(t.id(), Shape.CONE, left.getX(), Telegraphs.ground(left), left.getZ(), yaw, 6, 0, 70, ticks, TelegraphProtocol.RED));
            }
            case "봉인진", "봉인진변색종" -> {
                int warn = secondsOrNull == null ? 24 : ticks; // 초를 안 적으면 실제 스킬과 같은 1.2초
                var s = circle(t, look, 7, warn);
                show(plugin, p, s);
                var at = new Location(p.getWorld(), s.x(), s.y(), s.z());
                String id = kind.endsWith("변색종") ? "claude:sealcircle/seal_ruby" : "claude:sealcircle/seal";
                plugin.getServer().getScheduler().runTaskLater(plugin,
                    () -> plugin.reactions().vfx().effect(id, at, 7f / 4, 0, 40, 0xFFFFFF), Math.max(1, warn - 2));
                seconds = warn / 20.0;
            }
            case "빙핵레이저", "빙핵레이저변색종" -> {
                int warn = secondsOrNull == null ? 24 : ticks; // 초를 안 적으면 실제 스킬과 같은 1.2초
                var s = new Show(t.id(), Shape.LINE, feet.getX(), Telegraphs.ground(feet), feet.getZ(), yaw, 24, 3, 0, warn, TelegraphProtocol.RED);
                show(plugin, p, s);
                var at = new Location(p.getWorld(), s.x(), s.y(), s.z());
                String id = kind.endsWith("변색종") ? "claude:frostlaser/laser_obsidian" : "claude:frostlaser/laser";
                plugin.getServer().getScheduler().runTaskLater(plugin,
                    () -> plugin.reactions().vfx().effect(id, at, 1.5f, yaw, 40, 0xFFFFFF), Math.max(1, warn - 2));
                seconds = warn / 20.0;
            }
            default -> throw ChacaLink.error("모르는 예고 종류예요: " + kind);
        }
        return "예고 장판 " + kind + " " + seconds + "초를 보였어요 (피해 없음)";
    }

    /** 몹 스킬 시험: 예고 + 그 VFX (피해 없음). 예) earthquake/quake_club 원형내발 6 · dragonbreath/breath_nether 부채꼴 10 60 */
    static String fx(MagicMythicPlugin plugin, Player p, String effect, String shape, float size, float extra, int warn, int lead, float scale0)
            throws CommandSyntaxException {
        String id = TelegraphMech.vfxId(effect);
        if (id.isEmpty()) throw ChacaLink.error("효과 이름이 이상해요 (예: earthquake/quake_club)");
        var feet = p.getLocation();
        float yaw = feet.getYaw();
        Location look = look(p);
        var t = plugin.telegraphs();
        Show s;
        try {
            s = switch (shape) {
                case "원형" -> circle(t, look, size, warn);
                case "원형내발" -> new Show(t.id(), Shape.CIRCLE, feet.getX(), Telegraphs.ground(feet), feet.getZ(), yaw, size, 0, 0, warn, TelegraphProtocol.RED);
                case "직선" -> new Show(t.id(), Shape.LINE, feet.getX(), Telegraphs.ground(feet), feet.getZ(), yaw, size, extra > 0 ? extra : 2, 0, warn, TelegraphProtocol.RED);
                case "부채꼴" -> new Show(t.id(), Shape.CONE, feet.getX(), Telegraphs.ground(feet), feet.getZ(), yaw, size, 0, extra > 0 ? extra : 60, warn, TelegraphProtocol.RED);
                default -> null;
            };
        } catch (IllegalArgumentException e) { throw ChacaLink.error("모양 값이 범위를 벗어났어요"); }
        if (s == null) throw ChacaLink.error("모양은 원형·원형내발·직선·부채꼴 중에서 골라 주세요");
        show(plugin, p, s);
        var at = new Location(p.getWorld(), s.x(), s.y(), s.z());
        final Show shown = s; final float scale = Math.clamp(scale0, 0.25f, 4f);
        plugin.getServer().getScheduler().runTaskLater(plugin,
            () -> plugin.reactions().vfx().effect(id, at, scale, shown.yaw(), 40, 0xFFFFFF), Math.max(1, warn - lead));
        return "예고 + " + id + " (" + shape + " " + size + ", " + warn + "틱, 피해 없음)";
    }

    private static Location look(Player p) {
        var feet = p.getLocation();
        var hit = p.getWorld().rayTraceBlocks(p.getEyeLocation(), p.getEyeLocation().getDirection(), 24, FluidCollisionMode.NEVER, true);
        return hit == null ? feet.clone().add(feet.getDirection().setY(0).normalize().multiply(6)) : hit.getHitPosition().toLocation(p.getWorld());
    }

    private static Show circle(Telegraphs t, Location at, float r, int ticks) {
        return new Show(t.id(), Shape.CIRCLE, at.getX(), Telegraphs.ground(at.clone().add(0, 0.1, 0)), at.getZ(), 0, r, 0, 0, ticks, TelegraphProtocol.RED);
    }

    private static void show(MagicMythicPlugin plugin, Player p, Show s) {
        plugin.telegraphs().start(s, p, p.getWorld(), false, x -> {});
    }
}
