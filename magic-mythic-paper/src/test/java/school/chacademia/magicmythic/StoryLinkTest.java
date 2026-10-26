package school.chacademia.magicmythic;
import org.bukkit.Bukkit;import org.bukkit.entity.Player;import org.bukkit.plugin.*;import org.junit.jupiter.api.Test;import static org.junit.jupiter.api.Assertions.*;import static org.mockito.Mockito.*;
class StoryLinkTest {
 public interface GuideProvider {int questGuide(Player player);}
 @Test void supportedStoryApiPassesEveryGuideStatus() {
  try(var b=mockStatic(Bukkit.class)) {var manager=mock(PluginManager.class);b.when(Bukkit::getPluginManager).thenReturn(manager);var plugin=mock(Plugin.class,withSettings().extraInterfaces(GuideProvider.class));when(plugin.isEnabled()).thenReturn(true);when(manager.getPlugin("ChacademyStory")).thenReturn(plugin);var player=mock(Player.class);
   for(int code:new int[]{StoryLink.NONE,StoryLink.OK,StoryLink.OTHER_WORLD,StoryLink.NEAR}){when(((GuideProvider)plugin).questGuide(player)).thenReturn(code);assertEquals(code,StoryLink.guide(player));}
  }
 }
 @Test void absentLegacyOrFailedApiKeepsLegacyFallback(){try(var b=mockStatic(Bukkit.class)){var manager=mock(PluginManager.class);b.when(Bukkit::getPluginManager).thenReturn(manager);var player=mock(Player.class);assertEquals(StoryLink.NONE,StoryLink.guide(player));var old=mock(Plugin.class);when(old.isEnabled()).thenReturn(true);when(manager.getPlugin("ChacademyStory")).thenReturn(old);assertEquals(StoryLink.NONE,StoryLink.guide(player));}}
}
