package school.chacademia.gimmick;

// [기능:던전설정도구 시작] — 새 파일 하나만 추가 (다른 클래스는 안 바꿈). 배포 jar 에 이 클래스만 넣으면 된다.

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.util.Vector;

/**
 * 다른 플러그인(ChacaDungeon 던전 설정 도구)이 기믹을 설치·조절하는 공개 창구. 기믹 막대(DebugTool)와 같은 규칙:
 * 블록 격자, 90° 회전, 다른 기믹과 겹치면 거절, 보호 구역이면 거절. 메인 스레드에서만.
 * 결과 문자열: 성공이면 "" 또는 새 기믹 id, 실패면 "!" 로 시작하는 이유.
 */
public final class GimmickApi {
    private GimmickApi() {}

    private static GimmickPlugin plugin() {
        var p = Bukkit.getPluginManager().getPlugin("ChacaGimmick");
        return p instanceof GimmickPlugin g && g.isEnabled() ? g : null;
    }

    public static boolean ready() { return plugin() != null; }

    /** 설치할 수 있는 모델: id, 이름, 역할(입력·출력·도관·장치), 가로 칸, 깊이 칸, 높이(블록). */
    public static List<String[]> models() {
        var pl = plugin();
        var out = new ArrayList<String[]>();
        if (pl == null) return out;
        for (String id : new java.util.TreeSet<>(pl.catalog().ids())) {
            var info = pl.catalog().get(id);
            out.add(new String[]{id, info.name(), Signals.role(info.kind()), String.valueOf(info.footW()), String.valueOf(info.footD()),
                    String.valueOf(Math.max(1, (int) Math.ceil(info.height() - 1e-6)))});
        }
        return out;
    }

    /** feet 블록에 설치 (facing 0 남 · 1 서 · 2 북 · 3 동). */
    public static String place(Player p, String model, Block feet, int facing) {
        var pl = plugin();
        if (pl == null) return "!기믹 플러그인이 꺼져 있어요";
        var refused = new String[1];
        Gimmick g = pl.placer().place(p, model, feet, Math.floorMod(facing, 4), r -> refused[0] = r);
        return g == null ? "!" + (refused[0] == null ? "설치하지 못했어요" : refused[0]) : g.id;
    }

    /** 이 엔티티(기믹 히트박스·모델)가 기믹이면 id, 아니면 null. */
    public static String byEntity(Entity e) {
        var pl = plugin();
        if (pl == null) return null;
        Gimmick g = pl.world().byEntity(e);
        return g == null || g.attached() ? null : g.id;
    }

    /** 가장 가까운 기믹 id (반경 안), 없으면 null. */
    public static String nearest(Location at, double radius) {
        var pl = plugin();
        if (pl == null) return null;
        Gimmick best = null;
        double bd = radius;
        for (Gimmick g : pl.world().near(at, radius)) {
            if (g.attached()) continue;
            double d = g.location().distance(at);
            if (d <= bd) { bd = d; best = g; }
        }
        return best == null ? null : best.id;
    }

    /** 기믹 정보 (없으면 null): id, model, name, role, world, x, y, z, facing, scale, answer(없으면 ""), latched. */
    public static Map<String, String> info(String id) {
        var pl = plugin();
        if (pl == null) return null;
        Gimmick g = pl.world().get(id);
        if (g == null || g.attached()) return null;
        var info = pl.catalog().get(g.model);
        var m = new LinkedHashMap<String, String>();
        m.put("id", g.id); m.put("model", g.model); m.put("name", info.name()); m.put("role", Signals.role(info.kind()));
        m.put("world", g.world); m.put("x", "" + g.bx); m.put("y", "" + g.by); m.put("z", "" + g.bz);
        m.put("facing", DebugTool.FACING[Math.floorMod(g.facing, 4)]);
        m.put("scale", String.format(Locale.ROOT, "%.2f", g.scale));
        m.put("answer", Signals.hasAnswer(info.kind()) ? Signals.answerLabel(info.kind(), g.answer) : "");
        m.put("latched", "" + g.latched);
        m.put("footW", "" + info.footW()); m.put("footD", "" + info.footD()); m.put("layers", "" + g.layers(info));
        return m;
    }

    /**
     * 조절. action: rotate · move(dx,dz) · height(dy) · scale · answer · delete. sign = +1 / −1.
     * move 는 dx·dz 블록 (부르는 쪽이 보는 방향에서 계산). 실패면 "!이유".
     */
    public static String edit(String id, String action, int sign, int dx, int dz) {
        var pl = plugin();
        if (pl == null) return "!기믹 플러그인이 꺼져 있어요";
        Gimmick g = pl.world().get(id);
        if (g == null || g.attached()) return "!그 기믹이 없어요";
        var info = pl.catalog().get(g.model);
        switch (action) {
            case "rotate" -> {
                int old = g.facing; g.facing = Math.floorMod(g.facing + sign, 4);
                if (overlap(pl, g, info)) { g.facing = old; return "!다른 기믹과 겹쳐요"; }
            }
            case "move" -> {
                g.bx += dx; g.bz += dz;
                if (overlap(pl, g, info)) { g.bx -= dx; g.bz -= dz; return "!다른 기믹과 겹쳐요"; }
            }
            case "height" -> {
                g.by += sign;
                if (overlap(pl, g, info)) { g.by -= sign; return "!다른 기믹과 겹쳐요"; }
            }
            case "scale" -> g.scale = Math.clamp(Math.round((g.scale + sign * pl.getConfig().getDouble("tool.scale-step", 0.1)) * 1000) / 1000.0, 0.1, 8);
            case "answer" -> {
                if (!Signals.hasAnswer(info.kind())) return "!정답이 있는 기믹이 아니에요";
                if (info.kind().equals("platform") && g.travel > 0) return "!움직인 발판이에요. 칸 초기화 뒤에 바꿔 주세요";
                g.answer = Math.floorMod(g.answer + sign, Signals.answerCount(info.kind()));
                pl.world().markDirty();
                pl.signals().dirty();
                return "";
            }
            case "delete" -> { pl.world().remove(g); return ""; }
            default -> { return "!모르는 조절: " + action; }
        }
        pl.world().transform(g);
        return "";
    }

    private static boolean overlap(GimmickPlugin pl, Gimmick g, ModelCatalog.Info info) {
        return pl.world().overlap(g.world, g.columns(info), g.by, g.layers(info), g) != null;
    }

    /** 구역 안 기믹을 (dx,dy,dz) 만큼 옮긴 자리에 같은 모델·방향·크기·정답으로 새로 설치 (칸 복사). 돌려준 값 = 설치한 수. */
    public static int copyArea(World from, int[] min, int[] max, World to, int dx, int dy, int dz) {
        var pl = plugin();
        if (pl == null) return 0;
        int n = 0;
        for (Gimmick g : new ArrayList<>(pl.world().all())) {
            if (g.attached() || !from.getName().equals(g.world)) continue;
            if (g.bx < min[0] || g.bx > max[0] || g.by < min[1] || g.by > max[1] || g.bz < min[2] || g.bz > max[2]) continue;
            var info = pl.catalog().get(g.model);
            var probe = new Gimmick("probe", g.model, to.getName(), g.bx + dx, g.by + dy, g.bz + dz, g.facing);
            probe.scale = g.scale;
            if (pl.world().overlap(probe.world, probe.columns(info), probe.by, probe.layers(info), null) != null) continue;
            Gimmick c = pl.world().create(g.model, to, g.bx + dx, g.by + dy, g.bz + dz, g.facing);
            c.scale = g.scale; c.answer = g.answer;
            pl.world().transform(c);
            pl.world().markDirty();
            pl.signals().dirty();
            n++;
        }
        return n;
    }

    /** 보는 방향의 수평 한 칸 (이동 버튼용): {dx, dz}. */
    public static int[] axis(Vector look) {
        return Math.abs(look.getX()) > Math.abs(look.getZ()) ? new int[]{(int) Math.signum(look.getX()), 0} : new int[]{0, (int) Math.signum(look.getZ())};
    }
}
// [기능:던전설정도구 끝]
