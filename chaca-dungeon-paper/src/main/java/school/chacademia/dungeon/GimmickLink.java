package school.chacademia.dungeon;

import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;

/**
 * ChacaGimmick 의 공개 창구 GimmickApi 를 리플렉션으로 부른다 (기믹 jar 에 GimmickApi 가 없으면 기믹 설치 메뉴만 꺼짐).
 */
final class GimmickLink {
    private Class<?> api;
    private boolean tried;

    private Class<?> api() {
        var p = Bukkit.getPluginManager().getPlugin("ChacaGimmick");
        if (p == null || !p.isEnabled()) return null;
        if (!tried || api == null) {
            tried = true;
            try { api = Class.forName("school.chacademia.gimmick.GimmickApi", true, p.getClass().getClassLoader()); }
            catch (ClassNotFoundException | LinkageError e) { api = null; }
        }
        return api;
    }

    boolean ready() { return api() != null; }

    private Object call(String name, Class<?>[] types, Object... args) {
        var a = api();
        if (a == null) return null;
        try {
            Method m = a.getMethod(name, types);
            return m.invoke(null, args);
        } catch (ReflectiveOperationException | RuntimeException e) {
            Bukkit.getLogger().warning("[ChacaDungeon] 기믹 연결 실패 " + name + ": " + e);
            return null;
        }
    }

    @SuppressWarnings("unchecked")
    List<String[]> models() { var r = call("models", new Class<?>[0]); return r == null ? List.of() : (List<String[]>) r; }

    String place(Player p, String model, Block feet, int facing) {
        var r = call("place", new Class<?>[]{Player.class, String.class, Block.class, int.class}, p, model, feet, facing);
        return r == null ? "!기믹 플러그인 연결이 없어요" : (String) r;
    }

    String byEntity(Entity e) { return (String) call("byEntity", new Class<?>[]{Entity.class}, e); }

    String nearest(Location at, double radius) { return (String) call("nearest", new Class<?>[]{Location.class, double.class}, at, radius); }

    @SuppressWarnings("unchecked")
    Map<String, String> info(String id) { return (Map<String, String>) call("info", new Class<?>[]{String.class}, id); }

    String edit(String id, String action, int sign, int dx, int dz) {
        var r = call("edit", new Class<?>[]{String.class, String.class, int.class, int.class, int.class}, id, action, sign, dx, dz);
        return r == null ? "!기믹 플러그인 연결이 없어요" : (String) r;
    }

    int copyArea(World from, int[] min, int[] max, World to, int dx, int dy, int dz) {
        var r = call("copyArea", new Class<?>[]{World.class, int[].class, int[].class, World.class, int.class, int.class, int.class}, from, min, max, to, dx, dy, dz);
        return r == null ? 0 : (int) r;
    }
}
