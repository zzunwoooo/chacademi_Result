package school.magiccodex.paper;

import java.util.UUID;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

/** Resolve via Bukkit ServicesManager. Call on the server main thread. Values last until disconnect. */
public final class TemperatureService {
    private final TemperatureFeed feed;
    TemperatureService(TemperatureFeed feed){this.feed=feed;}
    public float getTemperature(UUID player){checkThread();return feed.get(player);}
    public void setTemperature(Player player,float celsius){
        checkThread();if(!player.isOnline())throw new IllegalArgumentException("접속 중인 플레이어를 지정하세요.");
        feed.set(player.getUniqueId(),celsius);
    }
    public void resetTemperature(Player player){checkThread();feed.reset(player.getUniqueId());}
    void computed(Player player,float value){checkThread();feed.computed(player.getUniqueId(),value);}
    void clearComputed(Player player){checkThread();feed.clearComputed(player.getUniqueId());}
    private static void checkThread(){if(!Bukkit.isPrimaryThread())throw new IllegalStateException("Temperature API requires the server main thread");}
}
