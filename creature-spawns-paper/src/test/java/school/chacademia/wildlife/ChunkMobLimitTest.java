package school.chacademia.wildlife;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.util.*;
import org.bukkit.Chunk;
import org.bukkit.entity.*;
import org.junit.jupiter.api.Test;

class ChunkMobLimitTest {
    private final Map<UUID, String> types = new HashMap<>();
    private final List<Entity> entities = new ArrayList<>();
    private final Chunk chunk = mock(Chunk.class);

    ChunkMobLimitTest() {
        when(chunk.getEntities()).thenAnswer(call -> entities.toArray(Entity[]::new));
    }

    private <T extends Entity> T entity(Class<T> api, String type, String... tags) {
        T entity = mock(api);
        UUID id = UUID.randomUUID();
        when(entity.getUniqueId()).thenReturn(id);
        when(entity.isValid()).thenReturn(true);
        when(entity.getScoreboardTags()).thenReturn(Set.of(tags));
        if (type != null) types.put(id, type);
        entities.add(entity);
        return entity;
    }

    private LivingEntity wild() { return entity(LivingEntity.class, "CA_Wild_squirrel"); }
    private boolean full(ChunkMobLimit limit) { return limit.full(chunk, null, types::get); }

    @Test void emptyChunkAcceptsFirstSpawn() {
        assertFalse(full(new ChunkMobLimit(6)));
    }

    @Test void allWildSpeciesAndShinyShareTheSameCap() {
        for (int i = 0; i < 3; i++) entity(LivingEntity.class, "CA_Wild_squirrel");
        for (int i = 0; i < 2; i++) entity(LivingEntity.class, "CA_Wild_owl_shiny");
        var limit = new ChunkMobLimit(6);
        assertFalse(full(limit));
        entity(LivingEntity.class, "CA_Wild_goblin");
        assertTrue(full(limit));
    }

    @Test void excludesPlayersItemsPetsVanillaAndOtherMythicMobs() {
        entity(Player.class, "CA_Wild_player");
        entity(Item.class, "CA_Wild_item");
        entity(LivingEntity.class, "CA_Wild_wolf", "chacademia_pet");
        entity(LivingEntity.class, "CA_Pet_wolf");
        entity(LivingEntity.class, "ChAcademiaPetWolf");
        entity(LivingEntity.class, "OtherCustomBoss");
        entity(LivingEntity.class, null);
        assertEquals(0, new ChunkMobLimit(1).count(chunk, null, types::get));
    }

    @Test void deadAndInvalidEntitiesDoNotConsumeSlots() {
        var dead = wild(); when(dead.isDead()).thenReturn(true);
        var unloaded = wild(); when(unloaded.isValid()).thenReturn(false);
        assertFalse(full(new ChunkMobLimit(1)));
    }

    @Test void pendingEntityDoesNotRejectTheLastAvailableSlot() {
        for (int i = 0; i < 5; i++) wild();
        var pending = wild();
        var limit = new ChunkMobLimit(6);
        assertTrue(full(limit));
        assertFalse(limit.full(chunk, pending.getUniqueId(), types::get));
    }

    @Test void pendingSeventhEntityIsRejectedEvenWhenAlreadyRegistered() {
        for (int i = 0; i < 6; i++) wild();
        var pending = wild();
        assertTrue(new ChunkMobLimit(6).full(chunk, pending.getUniqueId(), types::get));
    }

    @Test void successiveGroupAndOtherSpawnerAttemptsStopAtSix() {
        var limit = new ChunkMobLimit(6);
        for (int attempt = 0; attempt < 20; attempt++) if (!full(limit)) wild();
        assertEquals(6, limit.count(chunk, null, types::get));
        assertTrue(full(limit));
    }

    @Test void checksTheDestinationChunkRatherThanTheLeadersChunk() {
        var leaderChunk = mock(Chunk.class);
        when(leaderChunk.getEntities()).thenReturn(new Entity[0]);
        for (int i = 0; i < 6; i++) wild();
        var limit = new ChunkMobLimit(6);
        assertFalse(limit.full(leaderChunk, null, types::get));
        assertTrue(full(limit));
    }

    @Test void anExistingOverCapChunkIsBlockedWithoutRemovingAnything() {
        for (int i = 0; i < 12; i++) wild();
        assertTrue(full(new ChunkMobLimit(6)));
        assertEquals(12, entities.size());
        for (Entity entity : entities) verify(entity, never()).remove();
    }

    @Test void zeroAndNegativeCapsCannotSilentlyDisableProtection() {
        assertThrows(IllegalArgumentException.class, () -> new ChunkMobLimit(0));
        assertThrows(IllegalArgumentException.class, () -> new ChunkMobLimit(-1));
    }

    @Test void aConfiguredSmallerPositiveCapIsEnforced() {
        var limit = new ChunkMobLimit(2);
        wild(); assertFalse(full(limit));
        wild(); assertTrue(full(limit));
        assertEquals(2, limit.cap());
    }
}
