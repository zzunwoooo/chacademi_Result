package school.magiccodex.paper;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

/**
 * 발견한 마법 수 × 0.2 지능 (2026-10-27). 발견 수 = 스탯창과 같은 값 (발견 권한을 가진 마법 수).
 * MagicCodexBridge.equipmentPower 맨 앞에서 base 에 더하므로 장신구·아티팩트·펫 배율과 스탯창·책 조건에 그대로 반영된다.
 * 권한 검사는 유저별 10초 캐시.
 */
final class DiscoveryIntel {
    static final double PER_SPELL = 0.2;
    private record Count(long until, int count) {}
    private static final Map<UUID, Count> CACHE = new ConcurrentHashMap<>();
    private DiscoveryIntel() {}

    static Double add(ManaBridge mana, UUID id, Double base) {
        if (mana == null || id == null) return base;
        try {
            long now = System.nanoTime() / 1_000_000;
            Count c = CACHE.get(id);
            if (c == null || c.until() < now) {
                Player p = Bukkit.getPlayer(id);
                if (p == null) return base;
                int n = 0;
                for (String permission : mana.discoveryPermissions()) if (p.hasPermission(permission)) n++;
                c = new Count(now + 10_000, n);
                CACHE.put(id, c);
                if (CACHE.size() > 512) CACHE.entrySet().removeIf(e -> e.getValue().until() < now);
            }
            if (c.count() == 0) return base;
            double bonus = c.count() * PER_SPELL;
            return base == null ? bonus : base + bonus;
        } catch (RuntimeException | LinkageError e) {
            return base;
        }
    }
}
