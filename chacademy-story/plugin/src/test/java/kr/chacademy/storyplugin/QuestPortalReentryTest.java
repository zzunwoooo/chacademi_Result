package kr.chacademy.storyplugin;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Logger;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.scheduler.BukkitScheduler;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import static org.mockito.Mockito.*;
import static org.junit.jupiter.api.Assertions.*;

class QuestPortalReentryTest {
    @SuppressWarnings("unchecked") static <T> T field(Object obj, String name) throws Exception {
        Field f = obj.getClass().getDeclaredField(name); f.setAccessible(true); return (T) f.get(obj);
    }
    static void set(Object obj, String name, Object value) throws Exception {
        Field f = obj.getClass().getDeclaredField(name); f.setAccessible(true); f.set(obj, value);
    }
    static void call(QuestEngine e, String name, Class<?>[] types, Object... args) throws Exception {
        Method m = QuestEngine.class.getDeclaredMethod(name, types); m.setAccessible(true); m.invoke(e, args);
    }
    static final class Fixture implements AutoCloseable {
        final UUID id = UUID.randomUUID();
        final StoryPlugin plugin = mock(StoryPlugin.class);
        final Player player = mock(Player.class);
        final World world = mock(World.class);
        final QuestEngine engine = new QuestEngine(plugin);
        final QuestDefs.Step step = new QuestDefs.Step("portal");
        final QuestProgress.P state;
        final MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class);
        Fixture() throws Exception {
            when(world.getName()).thenReturn("world");
            when(player.getUniqueId()).thenReturn(id);
            when(player.getName()).thenReturn("test-player");
            when(player.getListeningPluginChannels()).thenReturn(Set.of());
            when(player.getLocation()).thenReturn(new Location(world, 0, 64, 0));
            when(plugin.getLogger()).thenReturn(Logger.getLogger("portal-test"));
            when(plugin.getConfig()).thenReturn(new YamlConfiguration());
            when(plugin.requestNicknameDialogue(player, "dialogue")).thenReturn(CompletableFuture.completedFuture(true));
            bukkit.when(() -> Bukkit.getPlayer(id)).thenReturn(player);
            bukkit.when(Bukkit::getOnlinePlayers).thenReturn(List.of(player));
            bukkit.when(Bukkit::getScheduler).thenReturn(mock(BukkitScheduler.class));
            bukkit.when(Bukkit::isPrimaryThread).thenReturn(true);
            step.dialogue = "dialogue";
            step.trigger.type = QuestDefs.TrigType.LOCATION;
            step.trigger.world = "world"; step.trigger.y = 64; step.trigger.radius = 2;
            QuestDefs defs = field(engine, "defs"); defs.steps.put(step.id, step);
            QuestProgress progress = field(engine, "prog"); state = progress.of(id); state.enter(step.id);
            state.phase = QuestProgress.Phase.PLAYING; state.waiting = "d:dialogue";
            Map<UUID,Integer> sessions = field(engine,"sessions"); sessions.put(id,1);
        }
        void abort() { engine.dialogueAborted(id, "dialogue"); }
        void tick() throws Exception { set(engine,"ready",true); call(engine,"tick",new Class<?>[0]); }
        void expireCooldown() throws Exception { ((Map<?,?>)field(engine,"retryAt")).clear(); }
        public void close() { bukkit.close(); }
    }
    @Test void cancelledPortalStaysClosedThenReopensOnceAfterLeavingAndReturning() throws Exception {
        try (Fixture f = new Fixture()) {
            f.abort(); f.expireCooldown();
            f.tick(); f.tick();
            call(f.engine,"checkLocation",new Class<?>[]{Player.class},f.player);
            verify(f.plugin,never()).requestNicknameDialogue(any(),anyString());
            assertEquals(QuestProgress.Phase.ACTIVE,f.state.phase);
            when(f.player.getLocation()).thenReturn(new Location(f.world,10,64,0)); f.tick();
            when(f.player.getLocation()).thenReturn(new Location(f.world,0,64,0)); f.tick();
            verify(f.plugin,times(1)).requestNicknameDialogue(f.player,"dialogue");
            assertEquals(QuestProgress.Phase.PLAYING,f.state.phase);
        }
    }
    @Test void unrelatedDialogueAbortDoesNotCancelPlayingStep() throws Exception {
        try (Fixture f = new Fixture()) {
            f.engine.dialogueAborted(f.id,"other");
            assertEquals(QuestProgress.Phase.PLAYING,f.state.phase);
            assertTrue(((Set<?>)field(f.engine,"mustLeave")).isEmpty());
        }
    }
    @Test void automaticTriggerStillRetriesAfterDialogueAbort() throws Exception {
        try (Fixture f = new Fixture()) {
            f.step.trigger.type=QuestDefs.TrigType.AUTO; f.step.trigger.delay=0;
            f.abort(); f.expireCooldown(); f.tick();
            verify(f.plugin,times(1)).requestNicknameDialogue(f.player,"dialogue");
        }
    }
    @Test void quittingClearsPortalReentryGate() throws Exception {
        try (Fixture f = new Fixture()) {
            f.abort(); assertTrue(((Set<?>)field(f.engine,"mustLeave")).contains(f.id));
            PlayerQuitEvent event=mock(PlayerQuitEvent.class); when(event.getPlayer()).thenReturn(f.player);
            f.engine.onQuit(event); assertFalse(((Set<?>)field(f.engine,"mustLeave")).contains(f.id));
        }
    }
}
