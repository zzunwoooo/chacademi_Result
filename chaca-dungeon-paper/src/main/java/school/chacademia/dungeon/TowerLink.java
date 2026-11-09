package school.chacademia.dungeon;

import java.lang.reflect.Method;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

// [기능:탑연결 시작]
/**
 * 외부 던전 "tower" = ChacaTower (school.chacademia.tower.TowerApi) 를 리플렉션으로 부른다 (컴파일 의존 없음).
 * 입장창에 띄울 상태 · 입장 · 대기 취소만.
 */
final class TowerLink {
    private TowerLink() {}

    /** 입장창 상태. button = DungeonProtocol.BTN_* , line = 부제 뒤에 붙일 기록 한 줄. */
    record Info(int button, String lock, String line, int active, int max) {
        static final Info OFF = new Info(school.chacademia.dungeon.protocol.DungeonProtocol.BTN_LOCKED, "지금은 들어갈 수 없어요", "", 0, 1);
    }

    private static Class<?> api() throws ReflectiveOperationException {
        var plugin = Bukkit.getPluginManager().getPlugin("ChacaTower");
        if (plugin == null || !plugin.isEnabled()) throw new ClassNotFoundException("ChacaTower 꺼짐");
        return Class.forName("school.chacademia.tower.TowerApi", true, plugin.getClass().getClassLoader());
    }

    static Info info(String external, Player p) {
        if (!external.equals("tower")) return Info.OFF;
        try {
            Method m = api().getMethod("info", Player.class);
            Object[] r = (Object[]) m.invoke(null, p);
            return new Info((Integer) r[0], (String) r[1], (String) r[2], (Integer) r[3], (Integer) r[4]);
        } catch (ReflectiveOperationException | RuntimeException e) { return Info.OFF; }
    }

    /** 입장창 보상 칸 (탑): {도달, 첫 도달, 수문장, 확률%}. 실패하면 null. */
    static Object[] rewards(String external, Player p) {
        if (!external.equals("tower")) return null;
        try { return (Object[]) api().getMethod("rewards", Player.class).invoke(null, p); }
        catch (ReflectiveOperationException | RuntimeException e) { return null; }
    }

    static boolean enter(String external, Player p) { return call(external, "enter", p); }

    static boolean leaveQueue(String external, Player p) { return call(external, "leaveQueue", p); }

    private static boolean call(String external, String name, Player p) {
        if (!external.equals("tower")) return false;
        try { api().getMethod(name, Player.class).invoke(null, p); return true; }
        catch (ReflectiveOperationException | RuntimeException e) { Bukkit.getLogger().warning("[ChacaDungeon] 탑 연결 실패 " + name + ": " + e); return false; }
    }
}
// [기능:탑연결 끝]
