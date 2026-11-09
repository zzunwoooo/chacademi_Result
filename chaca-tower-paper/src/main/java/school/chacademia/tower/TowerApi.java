package school.chacademia.tower;

import org.bukkit.entity.Player;

/**
 * 다른 플러그인(ChacaDungeon 입장창)이 리플렉션으로 부르는 입구. 메인 스레드에서만.
 * info 반환 = {버튼(DungeonProtocol.BTN_*), 잠금 문구, 기록 한 줄, 진행 중 판 수, 최대 판 수}.
 */
public final class TowerApi {
    private TowerApi() {}

    static TowerService service;

    public static Object[] info(Player p) {
        var s = service;
        if (s == null) return new Object[]{3, "지금은 들어갈 수 없어요", "", 0, 1};
        return s.entryInfo(p);
    }

    /** 입장창 보상 칸: {도달 보상 아이템들, 아직 안 받은 첫 도달 보상, 수문장 보상, 수문장 확률(%)}. 아이템 = serializeAsBytes. */
    public static Object[] rewards(Player p) {
        var s = service;
        if (s == null) return new Object[]{java.util.List.of(), java.util.List.of(), java.util.List.of(), 0};
        return s.entryRewards(p);
    }

    public static void enter(Player p) { if (service != null) service.enter(p); }

    public static void leaveQueue(Player p) { if (service != null) service.leaveQueue(p); }
}
