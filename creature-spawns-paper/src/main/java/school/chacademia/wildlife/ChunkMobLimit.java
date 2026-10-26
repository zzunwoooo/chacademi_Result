package school.chacademia.wildlife;

import java.util.UUID;
import java.util.function.Function;
import org.bukkit.Chunk;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;

/** A loaded-chunk, vertical-column limit for living CA_Wild_ mobs only. */
final class ChunkMobLimit {
    static final int DEFAULT_CAP = 6;
    private final int cap;

    ChunkMobLimit(int cap) {
        if (cap < 1) throw new IllegalArgumentException("chunk-cap must be a positive integer");
        this.cap = cap;
    }

    int cap() { return cap; }

    boolean full(Chunk chunk, UUID pendingEntity, Function<UUID, String> mythicType) {
        return count(chunk, pendingEntity, mythicType) >= cap;
    }

    int count(Chunk chunk, UUID pendingEntity, Function<UUID, String> mythicType) {
        int count = 0;
        for (Entity entity : chunk.getEntities()) {
            if (!(entity instanceof LivingEntity) || entity instanceof Player
                    || !entity.isValid() || entity.isDead()
                    || entity.getUniqueId().equals(pendingEntity)
                    || entity.getScoreboardTags().contains("chacademia_pet")) continue;
            String type = mythicType.apply(entity.getUniqueId());
            if (type != null && type.startsWith("CA_Wild_")) count++;
        }
        return count;
    }
}
