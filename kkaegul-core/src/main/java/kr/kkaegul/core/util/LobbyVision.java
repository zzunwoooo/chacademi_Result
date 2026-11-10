package kr.kkaegul.core.util;

import kr.kkaegul.core.KkaegulCore;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

/**
 * 로비(places "lobby" 가 있는 월드)에 있는 동안 야간 투시 무한 — 입자·화면 아이콘 없이.
 * 로비를 벗어나면 이걸로 준 무한 야간 투시만 지움 (시간 있는 야간 투시 물약은 그대로).
 */
public final class LobbyVision {
    private LobbyVision() {}

    public static void start(KkaegulCore plugin) {
        Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            Location lobby = plugin.places().get("lobby");
            World w = lobby == null ? null : lobby.getWorld();
            for (Player p : Bukkit.getOnlinePlayers()) {
                PotionEffect cur = p.getPotionEffect(PotionEffectType.NIGHT_VISION);
                if (w != null && w.equals(p.getWorld())) {
                    if (cur == null || !cur.isInfinite())
                        p.addPotionEffect(new PotionEffect(PotionEffectType.NIGHT_VISION, PotionEffect.INFINITE_DURATION, 0, false, false, false));
                } else if (cur != null && cur.isInfinite() && !cur.hasParticles() && !cur.hasIcon()) {
                    p.removePotionEffect(PotionEffectType.NIGHT_VISION);
                }
            }
        }, 40L, 20L);
    }
}
