package school.magiccodex.paper;
import com.mojang.brigadier.tree.LiteralCommandNode;
import com.mojang.brigadier.StringReader;
import io.papermc.paper.command.brigadier.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.logging.Logger;
import org.bukkit.command.*;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
class CommandHubContractTest {
 MagicCodexBridge plugin;CommandHub hub;AtomicReference<Consumer<Commands>> bind;Map<String,LiteralCommandNode<CommandSourceStack>> roots;
 @BeforeEach void setup(){plugin=mock(MagicCodexBridge.class);when(plugin.getLogger()).thenReturn(Logger.getLogger("hub-contract"));bind=new AtomicReference<>();hub=new CommandHub(plugin,bind::set);roots=new LinkedHashMap<>();}
 @AfterEach void cleanup(){hub.close();}
 @SuppressWarnings("unchecked") void register(){var registrar=mock(Commands.class);doAnswer(i->{LiteralCommandNode<CommandSourceStack> node=i.getArgument(0);assertNull(roots.put(node.getName(),node),"duplicate root registration");return Set.of(node.getName());}).when(registrar).register(any(LiteralCommandNode.class),anyString(),anyCollection());bind.get().accept(registrar);}
 CommandSourceStack source(CommandSender sender){var source=mock(CommandSourceStack.class);when(source.getSender()).thenReturn(sender);return source;}
 @Test void sharedBranchesMergeWithoutRemovingExistingChildren(){var owner=mock(Plugin.class);hub.branch(owner,ChacaApi.테스트,Commands.literal("큐디치").then(Commands.literal("시작").executes(ctx->1)).build(),"시작","큐디치 시작 ");hub.branch(owner,ChacaApi.테스트,Commands.literal("큐디치").then(Commands.literal("화면").executes(ctx->1)).build(),"미리보기","큐디치 화면 ");register();var node=roots.get("테스트").getChild("큐디치");assertNotNull(node.getChild("시작"));assertNotNull(node.getChild("화면"));assertNotNull(roots.get("관리").getCommand());}
 @Test void managementRootsHideFromNonOpAndRemainAvailableToOpAndConsole(){register();var player=mock(Player.class);for(var node:roots.values())assertFalse(node.canUse(source(player)));when(player.isOp()).thenReturn(true);for(var node:roots.values())assertTrue(node.canUse(source(player)));for(var node:roots.values())assertTrue(node.canUse(source(mock(ConsoleCommandSender.class))));}
 @Test void playerCommandsAndAliasesDoNotInheritAdminRestriction(){hub.playerCommand(mock(Plugin.class),Commands.literal("기숙사").executes(ctx->1).build(),"학교 기증",List.of());register();assertTrue(roots.get("기숙사").canUse(source(mock(Player.class))));assertFalse(roots.containsKey("기숙사점수"));assertFalse(roots.containsKey("학교기증"));assertFalse(roots.containsKey("qa"));}
 @Test void koreanWordsAndSuggestionsKeepSpacesAsArgumentBoundaries()throws Exception{var word=new ChacaArguments.Word(()->List.of("파랑","빨강"));var input=new StringReader("아르케온(본관) 다음");assertEquals("아르케온(본관)",word.parse(input));assertEquals(' ',input.peek());var suggestions=word.listSuggestions(null,new com.mojang.brigadier.suggestion.SuggestionsBuilder("파",0)).get();assertEquals(List.of("파랑"),suggestions.getList().stream().map(s->s.getText()).toList());}
}
