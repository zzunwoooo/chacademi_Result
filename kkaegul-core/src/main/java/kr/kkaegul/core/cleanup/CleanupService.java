package kr.kkaegul.core.cleanup;

import kr.kkaegul.core.KkaegulCore;
import kr.kkaegul.core.util.Txt;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.entity.Boss;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Item;
import org.bukkit.entity.Mob;
import org.bukkit.entity.Player;
import org.bukkit.entity.Tameable;

/**
 * 바닥 청소 (렉 줄이기): 야생 월드(wild.world)에서만, cleanup.interval-seconds(기본 180초)마다
 *  - 바닥에 떨어진 아이템 삭제 (방금 떨어진 5초 이내 것은 남김 — 캐자마자 사라지지 않게)
 *  - 주인 없는 몹 삭제: 길들인 것·이름표 붙인 것·끈에 묶인 것·타고 있는 것·플러그인이 만든 것(NPC, 미믹, 경주 개구리 등)은 그대로
 * 알림은 조용하게: 10초 전 채팅 한 줄, 3·2·1 은 액션바, 끝나면 채팅 한 줄.
 */
public final class CleanupService {
    private final KkaegulCore plugin;
    private int left;

    public CleanupService(KkaegulCore plugin) {
        this.plugin = plugin;
        left = interval();
        Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 20L, 20L);
    }

    private int interval() { return Math.max(30, plugin.getConfig().getInt("cleanup.interval-seconds", 180)); }

    private void tick() {
        if (!plugin.getConfig().getBoolean("cleanup.enabled", true)) { left = interval(); return; }
        left--;
        World wild = Bukkit.getWorld(plugin.getConfig().getString("wild.world", "world"));
        if (wild == null) { left = interval(); return; }
        if (left == 10) for (Player p : wild.getPlayers()) p.sendMessage(Txt.color(Txt.notice("&710초 뒤 야생 바닥에 떨어진 아이템을 치워요.")));
        else if (left >= 1 && left <= 3) for (Player p : wild.getPlayers()) plugin.ui().actionbar(p, "&7바닥 청소 &f" + left);
        else if (left <= 0) {
            left = interval();
            int items = 0, mobs = 0;
            {   // 야생 월드(wild.world)만 — 로비·마을·광산·미니게임 월드는 안 건드림
                for (Entity e : wild.getEntities()) {
                    if (e instanceof Item it) {
                        if (it.getTicksLived() < 100) continue;
                        it.remove(); items++;
                    } else if (e instanceof Mob m && unowned(m)) {
                        m.remove(); mobs++;
                    }
                }
            }
            for (Player p : wild.getPlayers()) p.sendMessage(Txt.color(Txt.notice("&7바닥 아이템 " + items + "개를 치웠어요.")));
            plugin.getLogger().info("바닥 청소: 아이템 " + items + "개, 주인 없는 몹 " + mobs + "마리");
        }
    }

    /** 자연 스폰된 평범한 몹만 true */
    private static boolean unowned(Mob m) {
        if (m instanceof Boss) return false;
        if (m instanceof Tameable t && t.isTamed()) return false;
        if (m.customName() != null || m.isLeashed() || m.isInsideVehicle() || !m.getPassengers().isEmpty()) return false;
        if (!m.getRemoveWhenFarAway()) return false;      // 주민·플러그인 소환 몹 등 '남겨둘' 몹
        if (m.hasMetadata("NPC") || !m.getScoreboardTags().isEmpty()) return false;
        if (!m.getPersistentDataContainer().getKeys().isEmpty()) return false; // 미믹·모델엔진·깨굴 경주 개구리 등 표시 붙은 몹
        return true;
    }
}
