package school.chacademia.housing;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.bukkit.entity.Player;
import school.magiccodex.protocol.HousingProtocol;

/**
 * 하우징 창이 열려 있지 않을 때 온 VISIT (정보창 "내 방 가기"·"상대 방 가기", 2026-10-27).
 * Rooms.handle 은 창이 없으면 요청을 버렸으므로 그 앞에서 받아 창을 연다: 내 방 = 편집 가능(home), 남의 방 = 평소처럼 친구 확인.
 */
final class WindowlessVisit {
    private static final Map<UUID, Long> LAST = new ConcurrentHashMap<>();
    private WindowlessVisit() {}

    /** true = 여기서 처리함 (Rooms.handle 은 바로 끝낸다). */
    static boolean handle(Rooms rooms, Player p, HousingProtocol.Request r) {
        if (!(r instanceof HousingProtocol.Visit v) || v.owner() == null) return false;
        UUID id = p.getUniqueId();
        if (rooms.session(id) != null) return false;
        long now = System.currentTimeMillis();
        Long last = LAST.get(id);
        if (last != null && now - last < 1000) return true;
        LAST.put(id, now);
        if (LAST.size() > 512) LAST.entrySet().removeIf(e -> now - e.getValue() > 60_000);
        rooms.open(p, v.owner(), v.owner().equals(id));
        return true;
    }
}
